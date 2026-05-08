package share

import (
	"context"
	"errors"
)

var (
	// ErrNotFound is returned when the queried share id has no row.
	ErrNotFound = errors.New("share_not_found")

	// ErrDuplicate is returned by Create when the id or host_path
	// collides with an existing share.
	ErrDuplicate = errors.New("share_duplicate")

	// ErrDefaultExists is returned when trying to create a second
	// default share — at most one may be marked is_default=1.
	ErrDefaultExists = errors.New("share_default_exists")

	// ErrHostPathInUse is returned when the host_path already belongs
	// to another share (host_path has a UNIQUE constraint).
	ErrHostPathInUse = errors.New("share_host_path_in_use")
)

// Repository persists Share rows. All timestamp fields on the passed
// Share are trusted as-is; the usecase layer stamps CreatedAt /
// ModifiedAt with a clock source.
type Repository interface {
	// Create inserts a new share. Returns ErrDuplicate on id/host_path
	// collision, ErrDefaultExists if is_default=1 and one already exists.
	Create(ctx context.Context, s *Share) error

	// GetByID returns the share with the given 16-byte id.
	GetByID(ctx context.Context, id []byte) (*Share, error)

	// GetDefault returns the share marked is_default=1. Returns
	// ErrNotFound if no default share exists.
	GetDefault(ctx context.Context) (*Share, error)

	// List returns all shares ordered by sort_order ASC, name ASC.
	List(ctx context.Context) ([]Share, error)

	// Update applies changes to an existing share. Only non-zero
	// fields in the patch are applied. Returns ErrNotFound if the
	// share id is unknown.
	Update(ctx context.Context, id []byte, patch SharePatch, now int64) error

	// Delete removes a share. Returns ErrNotFound if the id is
	// unknown. Does NOT touch the host filesystem — it only removes
	// the database row (and cascades pin deletion).
	Delete(ctx context.Context, id []byte) error
}

// SharePatch carries the optional fields for an update. nil pointer
// means "unchanged"; non-nil means "set to this value".
type SharePatch struct {
	Name      *string
	HostPath  *string
	ReadOnly  *bool
	Icon      *string
	SortOrder *int
}
