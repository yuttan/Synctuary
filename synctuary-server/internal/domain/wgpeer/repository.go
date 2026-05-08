package wgpeer

import (
	"context"
	"errors"
)

var (
	// ErrNotFound is returned when the queried peer id has no row.
	ErrNotFound = errors.New("wgpeer_not_found")

	// ErrDuplicate is returned by Create when the id, public_key, or
	// assigned_ip collides with an existing peer.
	ErrDuplicate = errors.New("wgpeer_duplicate")

	// ErrIPExhausted is returned when the CIDR has no free addresses
	// available for a new peer.
	ErrIPExhausted = errors.New("wgpeer_ip_exhausted")
)

// Repository persists WireGuard peer rows. Timestamp fields are trusted
// as-is; the usecase layer stamps CreatedAt with a clock source.
type Repository interface {
	// Create inserts a new peer. Returns ErrDuplicate on id/public_key/
	// assigned_ip collision.
	Create(ctx context.Context, p *Peer) error

	// GetByID returns the peer with the given 16-byte id.
	GetByID(ctx context.Context, id []byte) (*Peer, error)

	// ListActive returns all non-revoked peers ordered by created_at.
	ListActive(ctx context.Context) ([]Peer, error)

	// ListAll returns all peers (including revoked) for admin display.
	ListAll(ctx context.Context) ([]Peer, error)

	// AssignedIPs returns the set of already-allocated IP strings from
	// active (non-revoked) peers. Used by the IPAM allocator.
	AssignedIPs(ctx context.Context) ([]string, error)

	// Revoke soft-deletes a peer by setting revoked_at. Returns
	// ErrNotFound if the id is unknown or already revoked.
	Revoke(ctx context.Context, id []byte, at int64) error

	// Delete hard-removes a peer row. Returns ErrNotFound if the id
	// is unknown.
	Delete(ctx context.Context, id []byte) error
}
