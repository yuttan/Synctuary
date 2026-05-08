package pin

import (
	"context"
	"errors"
)

var (
	// ErrNotFound is returned when the queried pin does not exist.
	ErrNotFound = errors.New("pin_not_found")

	// ErrDuplicate is returned when a pin with the same
	// (device_id, share_id, path) already exists.
	ErrDuplicate = errors.New("pin_duplicate")
)

// Repository persists Pin rows. All implementations MUST be safe for
// concurrent use across goroutines.
type Repository interface {
	// Create inserts a new pin. Returns ErrDuplicate if the
	// (device_id, share_id, path) composite key already exists.
	Create(ctx context.Context, p *Pin) error

	// ListByDevice returns all pins for the given device, ordered by
	// sort_order ASC.
	ListByDevice(ctx context.Context, deviceID []byte) ([]Pin, error)

	// Update changes the label and/or sort_order of an existing pin.
	// Returns ErrNotFound if the pin does not exist.
	Update(ctx context.Context, deviceID, shareID []byte, path string, patch PinPatch) error

	// Delete removes a single pin. Returns ErrNotFound if the pin
	// does not exist.
	Delete(ctx context.Context, deviceID, shareID []byte, path string) error

	// DeleteAllByDevice removes all pins for a device. Used when a
	// device is revoked (though CASCADE handles this too).
	DeleteAllByDevice(ctx context.Context, deviceID []byte) error
}

// PinPatch carries the optional fields for an update. nil pointer
// means "unchanged"; non-nil means "set to this value".
type PinPatch struct {
	Label     *string
	SortOrder *int
}
