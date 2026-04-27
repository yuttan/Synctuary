// Package secret defines the persistence interface for long-lived
// server secrets — principally the 32-byte master_key derived in
// PROTOCOL §3.2.
//
// v0.4 ships a single on-disk implementation (see
// internal/adapter/infrastructure/secret) that writes the key to a
// mode-0600 file. A future release MAY add OS-keyring backings
// (Windows DPAPI, macOS Keychain, libsecret); callers depend only on
// this interface so the swap is transparent.
package secret

import (
	"context"
	"errors"
)

// ErrNotFound is returned by LoadMasterKey on first boot, before the
// key has been persisted. The daemon's bring-up path treats this as a
// signal to derive the key from the BIP-39 mnemonic and Save it.
var ErrNotFound = errors.New("master_key_not_found")

// Store persists the server's master_key. The 32-byte slice is handed
// off with no encryption at rest in the v0.4 file backing — the
// interface does NOT imply otherwise; confidentiality rests on FS
// permissions and full-disk encryption.
type Store interface {
	// SaveMasterKey persists `key` (exactly 32 bytes). Writes MUST
	// be atomic (write-to-temp + rename) so a crash mid-write
	// cannot leave a truncated file that would then fail to decode
	// on next boot.
	SaveMasterKey(ctx context.Context, key []byte) error

	// LoadMasterKey reads the persisted key. Returns ErrNotFound
	// when the backing store has no key yet (first boot). A
	// length-mismatched stored key MUST surface as a non-nil error
	// distinct from ErrNotFound — silent acceptance would cascade
	// into HKDF derivation errors far from the root cause.
	LoadMasterKey(ctx context.Context) ([]byte, error)
}
