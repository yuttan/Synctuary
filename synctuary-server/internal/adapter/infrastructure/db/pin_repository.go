package db

import (
	"context"
	"database/sql"
	"fmt"

	"github.com/synctuary/synctuary-server/internal/domain/pin"
)

// PinRepository is the SQLite-backed implementation of
// pin.Repository (migration 005_pins.sql).
type PinRepository struct {
	db *sql.DB
}

func NewPinRepository(database *sql.DB) *PinRepository {
	return &PinRepository{db: database}
}

var _ pin.Repository = (*PinRepository)(nil)

func (r *PinRepository) Create(ctx context.Context, p *pin.Pin) error {
	if len(p.DeviceID) != 16 {
		return fmt.Errorf("db: pin.DeviceID length %d, expected 16", len(p.DeviceID))
	}
	if len(p.ShareID) != 16 {
		return fmt.Errorf("db: pin.ShareID length %d, expected 16", len(p.ShareID))
	}
	_, err := r.db.ExecContext(ctx, `
		INSERT INTO pins (device_id, share_id, path, label, sort_order, created_at)
		VALUES (?, ?, ?, ?, ?, ?)
	`, p.DeviceID, p.ShareID, p.Path, p.Label, p.SortOrder, p.CreatedAt)
	if err != nil {
		if isUniqueViolation(err) {
			return pin.ErrDuplicate
		}
		return fmt.Errorf("db: CreatePin: %w", err)
	}
	return nil
}

func (r *PinRepository) ListByDevice(ctx context.Context, deviceID []byte) ([]pin.Pin, error) {
	rows, err := r.db.QueryContext(ctx, `
		SELECT device_id, share_id, path, label, sort_order, created_at
		FROM pins
		WHERE device_id = ?
		ORDER BY sort_order ASC
	`, deviceID)
	if err != nil {
		return nil, fmt.Errorf("db: ListPinsByDevice: %w", err)
	}
	defer rows.Close()

	var result []pin.Pin
	for rows.Next() {
		var p pin.Pin
		if err := rows.Scan(
			&p.DeviceID, &p.ShareID, &p.Path, &p.Label,
			&p.SortOrder, &p.CreatedAt,
		); err != nil {
			return nil, fmt.Errorf("db: ListPinsByDevice scan: %w", err)
		}
		result = append(result, p)
	}
	return result, rows.Err()
}

func (r *PinRepository) Update(ctx context.Context, deviceID, shareID []byte, path string, patch pin.PinPatch) error {
	var sets []string
	var args []any

	if patch.Label != nil {
		sets = append(sets, "label = ?")
		args = append(args, *patch.Label)
	}
	if patch.SortOrder != nil {
		sets = append(sets, "sort_order = ?")
		args = append(args, *patch.SortOrder)
	}

	if len(sets) == 0 {
		return nil
	}

	args = append(args, deviceID, shareID, path)
	query := "UPDATE pins SET " + joinStrings(sets, ", ") + //nolint:gosec // G202: column names are hardcoded, values use placeholders
		" WHERE device_id = ? AND share_id = ? AND path = ?"

	res, err := r.db.ExecContext(ctx, query, args...)
	if err != nil {
		return fmt.Errorf("db: UpdatePin: %w", err)
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		return pin.ErrNotFound
	}
	return nil
}

func (r *PinRepository) Delete(ctx context.Context, deviceID, shareID []byte, path string) error {
	res, err := r.db.ExecContext(ctx, `
		DELETE FROM pins
		WHERE device_id = ? AND share_id = ? AND path = ?
	`, deviceID, shareID, path)
	if err != nil {
		return fmt.Errorf("db: DeletePin: %w", err)
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		return pin.ErrNotFound
	}
	return nil
}

func (r *PinRepository) DeleteAllByDevice(ctx context.Context, deviceID []byte) error {
	_, err := r.db.ExecContext(ctx, `DELETE FROM pins WHERE device_id = ?`, deviceID)
	if err != nil {
		return fmt.Errorf("db: DeleteAllPinsByDevice: %w", err)
	}
	return nil
}
