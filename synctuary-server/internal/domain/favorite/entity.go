// Package favorite models the server-managed favorite-list feature
// introduced in PROTOCOL v0.2.3 §8.
//
// A favorite list is a named, ordered collection of paths visible to
// every device paired with the same master_key. Lists carry a
// `hidden` soft-hide flag (§8.9) but no other access control — the
// server is intentionally stateless about per-call privilege. Clients
// gate the surfacing of hidden lists behind a local biometric / PIN
// prompt.
//
// Two domain entities live here:
//
//   - List — the collection metadata + audit fields
//   - Item — one path entry within a list
//
// Both are POD structs; behaviour lives in the Repository implementation
// and the FavoriteService usecase.
package favorite

// List is one row in the favorite_lists table, plus its item_count
// derived by JOIN at read time.
type List struct {
	// ID is the server-issued 16-byte opaque identifier (§8.1).
	// Encoded as base64url-without-padding on the wire.
	ID []byte

	// Name is the user-supplied label, 1..256 NFC chars after
	// trimming. Validation lives in the usecase layer because the
	// path-style rules (no traversal, no control bytes) are shared
	// with the file endpoints.
	Name string

	// Hidden is the soft-hide flag (§8.9). When true, the list is
	// omitted from default GET /api/v1/favorites responses unless
	// the caller passes ?include_hidden=true.
	Hidden bool

	// ItemCount is the cached count of favorite_items rows for
	// this list. Always populated by Repository reads — never
	// computed by JSON encoding.
	ItemCount int

	// CreatedAt is unix seconds at insert time.
	CreatedAt int64

	// ModifiedAt is unix seconds; bumped on metadata change AND on
	// item add/remove (per §8.7 / §8.8). NOT bumped on idempotent
	// re-add (§8.7 explicit rule).
	ModifiedAt int64

	// CreatedByDeviceID is the 16-byte device_id of the device
	// that performed the POST. NULL if the originating device was
	// later revoked (FK ON DELETE SET NULL); the list survives.
	CreatedByDeviceID []byte
}

// Item is one row in the favorite_items table.
type Item struct {
	// Path is the user-facing path (PROTOCOL §1 form). Validation
	// lives in the usecase / handler.
	Path string

	// AddedAt is unix seconds.
	AddedAt int64

	// AddedByDeviceID is the 16-byte device_id of the adder.
	// NULL after that device is revoked.
	AddedByDeviceID []byte
}

// ListWithItems is List with its full Item slice loaded — used by
// GET /api/v1/favorites/<id> (§8.3). The summary endpoints use bare
// List to avoid the extra JOIN/scan.
type ListWithItems struct {
	List
	Items []Item
}
