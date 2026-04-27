// Package crypto provides the cryptographic primitives required by the
// Synctuary wire protocol (PROTOCOL.md v0.2.2): CSPRNG, SHA-256 token
// hashing, HKDF-SHA256 master-key / device-seed derivation, and Ed25519
// pairing-signature verification.
//
// All randomness in this package is drawn exclusively from crypto/rand
// (/dev/urandom, getrandom(2), BCryptGenRandom, or the equivalent OS
// CSPRNG). math/rand MUST NOT be imported anywhere in this module; the
// top-level golangci-lint configuration forbids it.
package crypto

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"fmt"
	"io"

	"golang.org/x/crypto/hkdf"
)

// Domain-separation constants, pinned by PROTOCOL v0.2.2 §3.2 and §3.3.
// These MUST NOT change without a protocol version bump — any alteration
// reshuffles every derived key on every installed deployment.
const (
	saltMasterV1      = "synctuary-v1"
	infoMaster        = "master"
	infoDeviceEd25519 = "device-ed25519"
	pairMagic         = "synctuary-pair-v1"
)

// Fixed byte lengths from PROTOCOL §3, §4.
const (
	seedLen      = 64                    // BIP39 seed after PBKDF2
	masterKeyLen = 32                    // HKDF output length for master_key
	deviceIDLen  = 16                    // client-chosen random identifier
	pubKeyLen    = ed25519.PublicKeySize // 32
	sigLen       = ed25519.SignatureSize // 64
	nonceLen     = 32                    // §4.2 CSPRNG nonce
	fingerprint  = 32                    // SHA-256 of DER-encoded TLS leaf

	// payloadLen is fixed at 129 bytes (PROTOCOL §4.1).
	// 17 (magic) + 16 (device_id) + 32 (device_pub) + 32 (fingerprint) + 32 (nonce)
	payloadLen = len(pairMagic) + deviceIDLen + pubKeyLen + fingerprint + nonceLen
)

// GenerateRandomBytes returns n cryptographically secure random bytes sourced
// from the OS CSPRNG. Used for pairing nonces (§4.2) and device tokens
// (§4.3); both MUST be ≥256 bits (pass n ≥ 32).
func GenerateRandomBytes(n int) ([]byte, error) {
	if n <= 0 {
		return nil, fmt.Errorf("crypto: GenerateRandomBytes: n must be positive, got %d", n)
	}
	out := make([]byte, n)
	if _, err := rand.Read(out); err != nil {
		return nil, fmt.Errorf("crypto: CSPRNG read failed: %w", err)
	}
	return out, nil
}

// HashToken returns SHA-256(token). The server persists only this digest
// for device_token verification, never the raw token (PROTOCOL §4.3).
func HashToken(token []byte) []byte {
	sum := sha256.Sum256(token)
	return sum[:]
}

// DeriveMasterKey implements PROTOCOL §3.2:
//
//	master_key = HKDF-SHA256(ikm=seed_bytes, salt="synctuary-v1",
//	                         info="master", L=32)
//
// seedBytes MUST be the 64-byte output of BIP39_mnemonic_to_seed with an
// empty passphrase.
func DeriveMasterKey(seedBytes []byte) ([]byte, error) {
	if len(seedBytes) != seedLen {
		return nil, fmt.Errorf("crypto: DeriveMasterKey: seed length %d, expected %d", len(seedBytes), seedLen)
	}
	out := make([]byte, masterKeyLen)
	r := hkdf.New(sha256.New, seedBytes, []byte(saltMasterV1), []byte(infoMaster))
	if _, err := io.ReadFull(r, out); err != nil {
		return nil, fmt.Errorf("crypto: HKDF master derivation failed: %w", err)
	}
	return out, nil
}

// DeriveDeviceKeypair implements PROTOCOL §3.3:
//
//	device_seed = HKDF-SHA256(ikm=master_key, salt=device_id,
//	                          info="device-ed25519", L=32)
//	(priv, pub) = Ed25519_keypair_from_seed(device_seed)
//
// masterKey MUST be 32 bytes; deviceID MUST be 16 bytes.
func DeriveDeviceKeypair(masterKey, deviceID []byte) (ed25519.PublicKey, ed25519.PrivateKey, error) {
	if len(masterKey) != masterKeyLen {
		return nil, nil, fmt.Errorf("crypto: DeriveDeviceKeypair: master_key length %d, expected %d", len(masterKey), masterKeyLen)
	}
	if len(deviceID) != deviceIDLen {
		return nil, nil, fmt.Errorf("crypto: DeriveDeviceKeypair: device_id length %d, expected %d", len(deviceID), deviceIDLen)
	}
	seed := make([]byte, ed25519.SeedSize) // 32
	r := hkdf.New(sha256.New, masterKey, deviceID, []byte(infoDeviceEd25519))
	if _, err := io.ReadFull(r, seed); err != nil {
		return nil, nil, fmt.Errorf("crypto: HKDF device-seed derivation failed: %w", err)
	}
	priv := ed25519.NewKeyFromSeed(seed)
	pub := make(ed25519.PublicKey, ed25519.PublicKeySize)
	copy(pub, priv.Public().(ed25519.PublicKey))
	return pub, priv, nil
}

// BuildPairPayload assembles the fixed 129-byte pairing payload defined
// by PROTOCOL §4.1:
//
//	payload = ASCII("synctuary-pair-v1")  // 17 bytes, no null terminator
//	        || device_id                  // 16 bytes
//	        || device_pub                 // 32 bytes
//	        || server_fingerprint         // 32 bytes (SHA-256 of DER cert)
//	        || nonce                      // 32 bytes
//
// There are no separators or length prefixes. All four variable-length
// inputs MUST have the exact sizes listed above.
func BuildPairPayload(deviceID, devicePub, serverFingerprint, nonce []byte) ([]byte, error) {
	if len(deviceID) != deviceIDLen {
		return nil, fmt.Errorf("crypto: BuildPairPayload: device_id length %d, expected %d", len(deviceID), deviceIDLen)
	}
	if len(devicePub) != pubKeyLen {
		return nil, fmt.Errorf("crypto: BuildPairPayload: device_pub length %d, expected %d", len(devicePub), pubKeyLen)
	}
	if len(serverFingerprint) != fingerprint {
		return nil, fmt.Errorf("crypto: BuildPairPayload: server_fingerprint length %d, expected %d", len(serverFingerprint), fingerprint)
	}
	if len(nonce) != nonceLen {
		return nil, fmt.Errorf("crypto: BuildPairPayload: nonce length %d, expected %d", len(nonce), nonceLen)
	}
	buf := make([]byte, 0, payloadLen)
	buf = append(buf, pairMagic...)
	buf = append(buf, deviceID...)
	buf = append(buf, devicePub...)
	buf = append(buf, serverFingerprint...)
	buf = append(buf, nonce...)
	if len(buf) != payloadLen {
		// Defensive: refuse to return a malformed payload even if the
		// constants above are edited incorrectly.
		return nil, fmt.Errorf("crypto: BuildPairPayload: assembled %d bytes, expected %d", len(buf), payloadLen)
	}
	return buf, nil
}

// VerifyPairSignature verifies an Ed25519 signature over the 129-byte
// pairing payload (PROTOCOL §4.1, §4.4). It returns false for any length
// violation to avoid leaking cause via distinct error paths.
func VerifyPairSignature(devicePub, payload, signature []byte) bool {
	if len(devicePub) != pubKeyLen || len(payload) != payloadLen || len(signature) != sigLen {
		return false
	}
	return ed25519.Verify(devicePub, payload, signature)
}
