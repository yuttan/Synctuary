// Package share models a server-configured file share — a named mapping
// from a display name to a host-side directory path. Shares are the
// multi-drive abstraction that lets the admin expose multiple directories
// (e.g. different disks, NAS mounts) to paired clients.
//
// Clients discover available shares via GET /api/v1/shares and scope
// file operations with the share id.
package share

// Share is one row in the shares table (migration 004_shares.sql).
type Share struct {
	// ID is the server-issued 16-byte opaque identifier.
	ID []byte

	// Name is the admin-supplied display label (1..256 NFC chars).
	Name string

	// HostPath is the absolute directory path on the server host.
	HostPath string

	// ReadOnly when true prevents upload, delete, and move operations
	// through this share.
	ReadOnly bool

	// Icon is an optional hint for the client UI (e.g. "folder",
	// "hdd", "film"). Empty string means use the default icon.
	Icon string

	// SortOrder controls display ordering; lower values appear first.
	SortOrder int

	// IsDefault marks the legacy root_path share. At most one share
	// may be default; pre-v0.6 clients that omit ?share= are routed
	// to this share.
	IsDefault bool

	// CreatedAt / ModifiedAt are unix epoch seconds.
	CreatedAt  int64
	ModifiedAt int64
}
