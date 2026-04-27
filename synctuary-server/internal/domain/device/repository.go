package device

import (
	"context"
	"errors"
)

// Sentinel errors returned by Repository implementations. Callers MUST
// use errors.Is for matching — implementations may wrap these to add
// context.
var (
	// ErrNotFound is returned when the queried device_id / token_hash
	// has no matching row.
	ErrNotFound = errors.New("device_not_found")

	// ErrRevoked is returned by GetByTokenHash when the row exists
	// but `revoked = 1`. Callers MUST treat this identically to
	// ErrNotFound from an auth standpoint (leak nothing to the
	// client); it exists separately only for server-side logging.
	ErrRevoked = errors.New("device_revoked")

	// ErrDuplicate is returned by Create when the device_id or
	// token_hash already exists. The pairing flow treats this as a
	// retry-with-fresh-entropy condition.
	ErrDuplicate = errors.New("device_duplicate")
)

// Repository persists Device rows. All timestamp fields on the passed
// Device are trusted as-is; the caller (usecase layer) is responsible
// for stamping CreatedAt / LastSeenAt with a clock source.
type Repository interface {
	// Create inserts a new device. Returns ErrDuplicate if the
	// device_id or token_hash collides with an existing row.
	Create(ctx context.Context, d *Device) error

	// GetByID returns the device with the given 16-byte id. Returns
	// ErrNotFound if no such row exists. Revoked devices ARE
	// returned (callers inspect d.Revoked); use GetByTokenHash for
	// auth paths that must reject revoked tokens inline.
	GetByID(ctx context.Context, id []byte) (*Device, error)

	// GetByTokenHash looks up a device by SHA-256(token). Returns
	// ErrNotFound if no row matches, ErrRevoked if the row exists
	// but is revoked. Intended for the bearer-auth middleware.
	GetByTokenHash(ctx context.Context, tokenHash []byte) (*Device, error)

	// TouchLastSeen updates last_seen_at to `at` (unix seconds).
	// Returns ErrNotFound if the device_id is unknown. Called
	// opportunistically from the auth middleware — implementations
	// MAY coalesce writes.
	TouchLastSeen(ctx context.Context, id []byte, at int64) error

	// Revoke sets revoked=1, revoked_at=at. Idempotent: revoking an
	// already-revoked device is a no-op that returns nil.
	Revoke(ctx context.Context, id []byte, at int64) error

	// List returns all devices (revoked or not), ordered by
	// created_at ASC. Used by the admin UI; no pagination yet
	// because fleet size is small (home-server assumption).
	List(ctx context.Context) ([]*Device, error)
}
