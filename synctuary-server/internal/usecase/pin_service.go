package usecase

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/synctuary/synctuary-server/internal/domain/pin"
	"github.com/synctuary/synctuary-server/internal/domain/share"
)

// PinService composes pin.Repository with share validation for the
// Quick Access bookmark endpoints.
type PinService struct {
	pins   pin.Repository
	shares share.Repository
	now    func() int64
}

func NewPinService(pins pin.Repository, shares share.Repository, now func() int64) (*PinService, error) {
	if pins == nil {
		return nil, fmt.Errorf("pin_service: missing pins repo")
	}
	if shares == nil {
		return nil, fmt.Errorf("pin_service: missing shares repo")
	}
	if now == nil {
		now = func() int64 { return time.Now().Unix() }
	}
	return &PinService{pins: pins, shares: shares, now: now}, nil
}

var (
	ErrPinPathEmpty = errors.New("pin_path_empty")
)

func (s *PinService) Create(ctx context.Context, deviceID, shareID []byte, path, label string, sortOrder int) (*pin.Pin, error) {
	if path == "" {
		return nil, ErrPinPathEmpty
	}
	// Verify the share exists.
	if _, err := s.shares.GetByID(ctx, shareID); err != nil {
		return nil, fmt.Errorf("pin_service: share lookup: %w", err)
	}
	p := &pin.Pin{
		DeviceID:  deviceID,
		ShareID:   shareID,
		Path:      path,
		Label:     label,
		SortOrder: sortOrder,
		CreatedAt: s.now(),
	}
	if err := s.pins.Create(ctx, p); err != nil {
		return nil, fmt.Errorf("pin_service: create: %w", err)
	}
	return p, nil
}

func (s *PinService) ListByDevice(ctx context.Context, deviceID []byte) ([]pin.Pin, error) {
	return s.pins.ListByDevice(ctx, deviceID)
}

func (s *PinService) Update(ctx context.Context, deviceID, shareID []byte, path string, patch pin.PinPatch) error {
	return s.pins.Update(ctx, deviceID, shareID, path, patch)
}

func (s *PinService) Delete(ctx context.Context, deviceID, shareID []byte, path string) error {
	return s.pins.Delete(ctx, deviceID, shareID, path)
}
