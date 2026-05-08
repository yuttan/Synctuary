// Package wg provides the WireGuard VPN infrastructure adapters:
// key generation, IP allocation, config generation, and the netstack
// tunnel server.
package wg

import (
	"crypto/rand"
	"encoding/base64"
	"fmt"
	"os"
	"path/filepath"

	"golang.org/x/crypto/curve25519"
)

// KeyPair holds a Curve25519 private/public keypair for WireGuard.
type KeyPair struct {
	PrivateKey [32]byte
	PublicKey  [32]byte
}

// GenerateKeyPair creates a fresh Curve25519 keypair using crypto/rand.
func GenerateKeyPair() (*KeyPair, error) {
	var priv [32]byte
	if _, err := rand.Read(priv[:]); err != nil {
		return nil, fmt.Errorf("wg: keygen entropy: %w", err)
	}
	// Clamp per Curve25519 convention.
	priv[0] &= 248
	priv[31] &= 127
	priv[31] |= 64

	pub, err := curve25519.X25519(priv[:], curve25519.Basepoint)
	if err != nil {
		return nil, fmt.Errorf("wg: curve25519: %w", err)
	}

	kp := &KeyPair{}
	copy(kp.PrivateKey[:], priv[:])
	copy(kp.PublicKey[:], pub)
	return kp, nil
}

// PrivateKeyBase64 returns the standard WireGuard base64 representation.
func (kp *KeyPair) PrivateKeyBase64() string {
	return base64.StdEncoding.EncodeToString(kp.PrivateKey[:])
}

// PublicKeyBase64 returns the standard WireGuard base64 representation.
func (kp *KeyPair) PublicKeyBase64() string {
	return base64.StdEncoding.EncodeToString(kp.PublicKey[:])
}

// LoadOrGenerateServerKey loads a Curve25519 private key from path, or
// generates one if the file does not exist. Uses the same atomic-write
// pattern (tmp+rename, mode 0600) as the master_key file store.
func LoadOrGenerateServerKey(path string) (*KeyPair, error) {
	data, err := os.ReadFile(path)
	if err == nil {
		return parsePrivateKey(data)
	}
	if !os.IsNotExist(err) {
		return nil, fmt.Errorf("wg: read server key %q: %w", path, err)
	}

	// Generate a new keypair and persist.
	kp, err := GenerateKeyPair()
	if err != nil {
		return nil, err
	}
	if err := atomicWrite(path, kp.PrivateKey[:]); err != nil {
		return nil, fmt.Errorf("wg: persist server key: %w", err)
	}
	return kp, nil
}

// parsePrivateKey derives the public key from a raw 32-byte private key.
func parsePrivateKey(raw []byte) (*KeyPair, error) {
	if len(raw) != 32 {
		return nil, fmt.Errorf("wg: private key length %d, expected 32", len(raw))
	}
	pub, err := curve25519.X25519(raw, curve25519.Basepoint)
	if err != nil {
		return nil, fmt.Errorf("wg: derive public key: %w", err)
	}
	kp := &KeyPair{}
	copy(kp.PrivateKey[:], raw)
	copy(kp.PublicKey[:], pub)
	return kp, nil
}

// atomicWrite persists data to path using tmp+rename for crash safety.
func atomicWrite(path string, data []byte) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return fmt.Errorf("mkdir: %w", err)
	}
	tmp := path + ".tmp"
	fh, err := os.OpenFile(tmp, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return fmt.Errorf("open tmp: %w", err)
	}

	writeErr := func() error {
		if _, err := fh.Write(data); err != nil {
			return err
		}
		return fh.Sync()
	}()
	if cerr := fh.Close(); cerr != nil && writeErr == nil {
		writeErr = cerr
	}
	if writeErr != nil {
		_ = os.Remove(tmp)
		return fmt.Errorf("write: %w", writeErr)
	}
	if err := os.Rename(tmp, path); err != nil {
		_ = os.Remove(tmp)
		return fmt.Errorf("rename: %w", err)
	}
	return nil
}
