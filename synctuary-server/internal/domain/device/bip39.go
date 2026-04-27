// Package device owns the BIP-39 mnemonic / seed handling for the
// Synctuary root identity (PROTOCOL v0.2.2 §3.1–§3.2).
//
// The wordlist is embedded at build time (bip39_english.txt, 2048 words,
// one per line). The file content MUST be the canonical BIP-39 English
// wordlist; any deviation changes the mapping between entropy and human
// words and corrupts every derived key.
package device

import (
	"bufio"
	"crypto/sha256"
	"crypto/sha512"
	_ "embed"
	"errors"
	"fmt"
	"math/big"
	"strings"

	"golang.org/x/crypto/pbkdf2"
	"golang.org/x/text/unicode/norm"
)

// ErrInvalidMnemonic is returned (wrapped) whenever normalization, word
// count, unknown-word, or checksum validation fails. Callers that only
// need to surface a generic "bad mnemonic" message to the user can use
// errors.Is(err, ErrInvalidMnemonic).
var ErrInvalidMnemonic = errors.New("invalid mnemonic")

//go:embed bip39_english.txt
var bip39EnglishTxt string

var (
	wordList    []string       // index → word, len == 2048
	wordToIndex map[string]int // word → index
)

func init() {
	sc := bufio.NewScanner(strings.NewReader(bip39EnglishTxt))
	sc.Buffer(make([]byte, 64), 64) // any word fits in 64 bytes
	words := make([]string, 0, 2048)
	for sc.Scan() {
		w := strings.TrimSpace(sc.Text())
		if w == "" {
			continue
		}
		words = append(words, w)
	}
	if err := sc.Err(); err != nil {
		panic(fmt.Sprintf("bip39: scanner error: %v", err))
	}
	if len(words) != 2048 {
		panic(fmt.Sprintf("bip39: expected 2048 words in embedded list, got %d", len(words)))
	}
	wordList = words
	wordToIndex = make(map[string]int, 2048)
	for i, w := range words {
		if _, dup := wordToIndex[w]; dup {
			panic(fmt.Sprintf("bip39: duplicate word %q at index %d", w, i))
		}
		wordToIndex[w] = i
	}
}

// normalizeMnemonic applies Unicode NFKD (required by BIP-39), trims
// outer whitespace, and collapses any run of internal whitespace into a
// single ASCII space. Callers then strings.Split on " ".
func normalizeMnemonic(mnemonic string) string {
	s := norm.NFKD.String(mnemonic)
	s = strings.TrimSpace(s)
	// Collapse all internal whitespace runs (space, tab, NBSP-after-NFKD, etc.)
	// into single ASCII spaces so that strings.Split yields the word list.
	return strings.Join(strings.Fields(s), " ")
}

// MnemonicToSeed returns the 64-byte BIP-39 seed for the given mnemonic
// (PROTOCOL §3.2). The passphrase is fixed to "" per PROTOCOL.
//
// The input is NFKD-normalized and whitespace-collapsed before being fed
// to PBKDF2-HMAC-SHA512 (2048 iterations, output 64 bytes, salt =
// "mnemonic"). Callers that need checksum verification should call
// ValidateMnemonic separately.
func MnemonicToSeed(mnemonic string) ([]byte, error) {
	normalized := normalizeMnemonic(mnemonic)
	if normalized == "" {
		return nil, fmt.Errorf("bip39: empty mnemonic: %w", ErrInvalidMnemonic)
	}
	seed := pbkdf2.Key([]byte(normalized), []byte("mnemonic"), 2048, 64, sha512.New)
	return seed, nil
}

// ValidateMnemonic enforces:
//  1. exactly 24 words after NFKD + whitespace collapse,
//  2. every word belongs to the canonical BIP-39 English list,
//  3. the embedded 8-bit checksum matches SHA-256(entropy)[0].
//
// Any failure returns a wrapped ErrInvalidMnemonic with a concise
// diagnostic suffix.
func ValidateMnemonic(mnemonic string) error {
	normalized := normalizeMnemonic(mnemonic)
	words := strings.Split(normalized, " ")
	if len(words) != 24 {
		return fmt.Errorf("bip39: word count %d, expected 24: %w", len(words), ErrInvalidMnemonic)
	}

	// Concatenate 24 × 11-bit indices (big-endian) into a 264-bit integer.
	combined := new(big.Int)
	for i, w := range words {
		idx, ok := wordToIndex[w]
		if !ok {
			return fmt.Errorf("bip39: unknown word at position %d: %w", i, ErrInvalidMnemonic)
		}
		combined.Lsh(combined, 11)
		combined.Or(combined, big.NewInt(int64(idx)))
	}

	// Bottom 8 bits: claimed checksum. Top 256 bits: entropy.
	checksumBig := new(big.Int).And(combined, big.NewInt(0xFF))
	entropyBig := new(big.Int).Rsh(combined, 8)

	entropyBytes := entropyBig.Bytes()
	if len(entropyBytes) < 32 {
		pad := make([]byte, 32)
		copy(pad[32-len(entropyBytes):], entropyBytes)
		entropyBytes = pad
	}
	if len(entropyBytes) != 32 {
		// Defensive: a 24-word mnemonic can only encode 256-bit entropy.
		return fmt.Errorf("bip39: entropy length %d: %w", len(entropyBytes), ErrInvalidMnemonic)
	}

	h := sha256.Sum256(entropyBytes)
	if h[0] != byte(checksumBig.Int64()) {
		return fmt.Errorf("bip39: checksum mismatch: %w", ErrInvalidMnemonic)
	}
	return nil
}

// GenerateMnemonic converts 256 bits (32 bytes) of entropy into a
// 24-word BIP-39 mnemonic. The caller is responsible for sourcing the
// entropy from a CSPRNG (see crypto.GenerateRandomBytes).
func GenerateMnemonic(entropy []byte) (string, error) {
	if len(entropy) != 32 {
		return "", fmt.Errorf("bip39: entropy length %d, expected 32: %w", len(entropy), ErrInvalidMnemonic)
	}

	// checksum = first 8 bits of SHA-256(entropy)
	h := sha256.Sum256(entropy)
	checksum := h[0]

	// combined = entropy (256 bits, big-endian) || checksum (8 bits) = 264 bits
	combined := new(big.Int).SetBytes(entropy)
	combined.Lsh(combined, 8)
	combined.Or(combined, big.NewInt(int64(checksum)))

	// Walk from least-significant 11-bit group up, filling words[23]→words[0].
	words := make([]string, 24)
	mask11 := big.NewInt(0x7FF)
	for i := 23; i >= 0; i-- {
		low := new(big.Int).And(combined, mask11)
		words[i] = wordList[int(low.Int64())]
		combined.Rsh(combined, 11)
	}

	return strings.Join(words, " "), nil
}
