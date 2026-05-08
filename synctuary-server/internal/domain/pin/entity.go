// Package pin models a per-device Quick Access bookmark — a shortcut
// to a directory within a share that appears on the client home screen,
// similar to Windows Quick Access pinned folders.
//
// Pins are device-scoped: each paired device maintains its own set.
// When a device is revoked, its pins cascade-delete.
package pin

// Pin is one row in the pins table (migration 005_pins.sql).
type Pin struct {
	// DeviceID is the 16-byte device identifier that owns this pin.
	DeviceID []byte

	// ShareID is the 16-byte share identifier this pin points into.
	ShareID []byte

	// Path is the directory path within the share (e.g. "/Photos/2024").
	Path string

	// Label is an optional user-supplied display name. Empty string
	// means the client should derive a label from the path.
	Label string

	// SortOrder controls display ordering; lower values appear first.
	SortOrder int

	// CreatedAt is unix epoch seconds.
	CreatedAt int64
}
