package usecase

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/synctuary/synctuary-server/internal/domain/device"
)

// DeviceService orchestrates the operator-facing device management
// endpoints (PROTOCOL §7.1 list, §7.2 revoke).
type DeviceService struct {
	repo device.Repository
}

func NewDeviceService(repo device.Repository) *DeviceService {
	return &DeviceService{repo: repo}
}

// ErrDeviceNotFound surfaces device.ErrNotFound to the handler layer
// without forcing handlers to import the domain package directly.
var ErrDeviceNotFound = errors.New("device_not_found")

type DeviceSummary struct {
	ID         []byte
	Name       string
	Platform   string
	CreatedAt  int64
	LastSeenAt int64
	Revoked    bool
}

func (s *DeviceService) List(ctx context.Context) ([]DeviceSummary, error) {
	rows, err := s.repo.List(ctx)
	if err != nil {
		return nil, fmt.Errorf("device_service: list: %w", err)
	}
	out := make([]DeviceSummary, 0, len(rows))
	for _, d := range rows {
		out = append(out, DeviceSummary{
			ID:         d.ID,
			Name:       d.Name,
			Platform:   d.Platform,
			CreatedAt:  d.CreatedAt,
			LastSeenAt: d.LastSeenAt,
			Revoked:    d.Revoked,
		})
	}
	return out, nil
}

// Revoke flips revoked=1 for the named device. Idempotent: a second
// call on an already-revoked id returns nil without error.
func (s *DeviceService) Revoke(ctx context.Context, id []byte) error {
	if err := s.repo.Revoke(ctx, id, time.Now().Unix()); err != nil {
		if errors.Is(err, device.ErrNotFound) {
			return ErrDeviceNotFound
		}
		return fmt.Errorf("device_service: revoke: %w", err)
	}
	return nil
}
