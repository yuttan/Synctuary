// Package device models a paired client device — the identity that signs
// PROTOCOL §4.1 pair payloads and that later authenticates HTTP requests
// via the bearer device_token (§4.3 / §5).
package device

import "crypto/ed25519"

// Device is the in-memory projection of a row in the `devices` table
// (see migrations/001_init.sql). Fields line up 1:1 with the SQL
// columns; consumers MUST NOT mutate the raw byte slices after reading.
type Device struct {
	// ID is the 16-byte client-chosen identifier (PROTOCOL §4.1).
	ID []byte

	// PublicKey is the 32-byte Ed25519 public key (PROTOCOL §3.3).
	PublicKey ed25519.PublicKey

	// TokenHash is SHA-256(device_token); the raw token is never
	// persisted (PROTOCOL §4.3).
	TokenHash []byte

	// Name / Platform are optional self-reported labels for UX only —
	// they are NOT used in any security decision.
	Name     string
	Platform string

	// CreatedAt / LastSeenAt are unix epoch seconds.
	CreatedAt  int64
	LastSeenAt int64

	// Revoked is true once the operator has invalidated the token
	// (PROTOCOL §5.2). Revoked devices MUST fail auth immediately.
	Revoked   bool
	RevokedAt int64 // 0 when !Revoked
}
