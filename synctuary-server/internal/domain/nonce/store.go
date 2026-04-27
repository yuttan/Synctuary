// Package nonce defines the server-issued single-use pairing nonce
// store (PROTOCOL §4.2). All nonces stored here are 32 raw bytes of
// CSPRNG output — base64url encoding happens at the handler boundary,
// never inside this layer.
package nonce

import (
	"context"
	"errors"
)

// Fixed byte length of a pairing nonce (PROTOCOL §4.2 "≥256 bits").
const Length = 32

var (
	// ErrNotFound is returned when the supplied nonce has no row —
	// either never issued or already garbage-collected past its
	// expiry. The handler maps this to 410 pair_nonce_expired; a
	// distinct "never issued" path is deliberately NOT exposed to
	// avoid giving an attacker a nonce-enumeration oracle.
	ErrNotFound = errors.New("nonce_not_found")

	// ErrExpired is returned when the nonce exists but
	// expires_at ≤ now. The handler maps this to 410
	// pair_nonce_expired. Kept distinct from ErrNotFound for
	// server-side logging only; both result in the same client
	// response.
	ErrExpired = errors.New("nonce_expired")

	// ErrAlreadyConsumed is returned when VerifyAndConsume sees a
	// row with consumed=1. Indicates either a replay attempt or a
	// retry after a crash between row consume and response send;
	// handler maps to 410 pair_nonce_expired (same as expired —
	// per PROTOCOL §4.2 "single-use").
	ErrAlreadyConsumed = errors.New("nonce_already_consumed")
)

// Store persists pairing nonces. Issue + VerifyAndConsume together
// implement the §4.2 protection against replay: Issue adds a single
// row; VerifyAndConsume atomically flips consumed=0 → 1 under a
// transaction, guaranteeing no two /pair/register calls can succeed
// with the same nonce.
type Store interface {
	// Issue persists a fresh nonce with (issuedAt, expiresAt,
	// sourceIP). `nonce` MUST be exactly 32 bytes of CSPRNG output
	// supplied by the caller — this layer does NOT generate
	// randomness itself, keeping all CSPRNG calls funneled through
	// the crypto package. sourceIP is stored for audit; an empty
	// string is permitted (e.g. when behind a proxy the server
	// cannot verify).
	Issue(ctx context.Context, nonce []byte, issuedAt, expiresAt int64, sourceIP string) error

	// VerifyAndConsume atomically validates `nonce` and marks it
	// consumed. Returns:
	//
	//   nil                — success; the caller may proceed with
	//                        pair registration. The row is left
	//                        in place (consumed=1) for later GC;
	//                        re-invoking VerifyAndConsume with the
	//                        same nonce returns ErrAlreadyConsumed.
	//
	//   ErrNotFound        — no row with this nonce.
	//   ErrExpired         — row exists but expires_at ≤ now.
	//   ErrAlreadyConsumed — row exists, not expired, consumed=1.
	//   other              — DB-level fault.
	//
	// The expiry check uses the `now` parameter, not wall-clock
	// inside the implementation, so tests can inject time.
	VerifyAndConsume(ctx context.Context, nonce []byte, now int64) error

	// CollectExpired deletes all rows whose expires_at ≤ now
	// (consumed or not). Returns the number of deleted rows for
	// telemetry. Called on a periodic GC tick by the daemon; MUST
	// be safe to invoke concurrently with Issue / VerifyAndConsume.
	CollectExpired(ctx context.Context, now int64) (int, error)
}
