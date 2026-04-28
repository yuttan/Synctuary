package favorite

import (
	"context"
	"errors"
)

// Sentinel errors returned by Repository implementations. Callers
// MUST use errors.Is for matching — implementations may wrap to add
// SQL-layer context.
var (
	// ErrListNotFound is returned when the queried list_id has no
	// row. Maps to PROTOCOL §9 `404 favorite_list_not_found`.
	// Per §8.3 this is also the response for a hidden list whose
	// existence we do not want to leak — callers don't need to
	// distinguish the two.
	ErrListNotFound = errors.New("favorite_list_not_found")

	// ErrItemNotFound is returned by RemoveItem when the (list_id,
	// path) pair has no row. Maps to PROTOCOL §9
	// `404 favorite_item_not_found`.
	ErrItemNotFound = errors.New("favorite_item_not_found")

	// ErrDuplicate is returned by CreateList if the generated id
	// collides with an existing row. The usecase retries with a
	// fresh CSPRNG draw — collisions on a 128-bit space are
	// astronomically improbable but the path exists for correctness.
	ErrDuplicate = errors.New("favorite_duplicate")
)

// Repository persists favorite lists and items. All implementations
// MUST be safe for concurrent use across goroutines.
//
// Timestamps on the passed List/Item are trusted as-is; the usecase
// layer is responsible for stamping them with a clock source so tests
// can inject deterministic time.
type Repository interface {
	// CreateList inserts a new list. The caller has already
	// generated the 16-byte ID via CSPRNG and stamped CreatedAt /
	// ModifiedAt. Returns ErrDuplicate on id collision.
	CreateList(ctx context.Context, l *List) error

	// ListAll returns every list visible to the caller, in
	// modified_at DESC order (per §8.2). When includeHidden is
	// false, lists with hidden=1 are omitted.
	ListAll(ctx context.Context, includeHidden bool) ([]List, error)

	// GetList returns a single list with its items loaded
	// (§8.3 GET /api/v1/favorites/<id>). Items are ordered by
	// added_at ASC. Returns ErrListNotFound if id is unknown,
	// regardless of the list's hidden flag (§8.3 leak-avoidance).
	GetList(ctx context.Context, id []byte) (*ListWithItems, error)

	// UpdateList applies a metadata patch (§8.5). Only the fields
	// in `patch` that are non-nil are applied; ModifiedAt is bumped
	// to `now`. Returns ErrListNotFound if id is unknown.
	UpdateList(ctx context.Context, id []byte, patch ListPatch, now int64) error

	// DeleteList removes the list and CASCADE-deletes its items
	// (§8.6). Returns ErrListNotFound if id is unknown so callers
	// can distinguish a real 404 from a successful no-op. The
	// underlying files referenced by items are NOT touched.
	DeleteList(ctx context.Context, id []byte) error

	// AddItem inserts (list_id, path) and bumps the list's
	// modified_at. Returns ErrListNotFound if the list is unknown.
	// If the path is already in the list, returns the existing
	// Item with `inserted=false` and does NOT bump modified_at
	// (§8.7 idempotent re-add rule).
	AddItem(ctx context.Context, listID []byte, item Item) (existing Item, inserted bool, err error)

	// RemoveItem deletes (list_id, path) and bumps the list's
	// modified_at. Returns ErrListNotFound if the list is unknown,
	// ErrItemNotFound if the path is not in the list (§8.8).
	RemoveItem(ctx context.Context, listID []byte, path string, now int64) error
}

// ListPatch carries the optional fields of a §8.5 PATCH request.
// nil pointer means "unchanged"; non-nil means "set to this value".
//
// The handler / usecase layer is responsible for rejecting the
// "all-nil" body with 400 bad_request before reaching the repository.
type ListPatch struct {
	Name   *string
	Hidden *bool
}
