package db

import (
	"context"
	"crypto/ed25519"
	"database/sql"
	"errors"
	"fmt"
	"strings"

	"github.com/synctuary/synctuary-server/internal/domain/device"
)

// DeviceRepository is the SQLite-backed implementation of
// device.Repository.
type DeviceRepository struct {
	db *sql.DB
}

func NewDeviceRepository(database *sql.DB) *DeviceRepository {
	return &DeviceRepository{db: database}
}

var _ device.Repository = (*DeviceRepository)(nil)

func (r *DeviceRepository) Create(ctx context.Context, d *device.Device) error {
	if len(d.ID) != 16 {
		return fmt.Errorf("db: device.ID length %d, expected 16", len(d.ID))
	}
	if len(d.PublicKey) != ed25519.PublicKeySize {
		return fmt.Errorf("db: device.PublicKey length %d, expected %d", len(d.PublicKey), ed25519.PublicKeySize)
	}
	if len(d.TokenHash) != 32 {
		return fmt.Errorf("db: device.TokenHash length %d, expected 32", len(d.TokenHash))
	}

	_, err := r.db.ExecContext(ctx, `
		INSERT INTO devices (
			device_id, device_pub, token_hash, device_name, platform,
			created_at, last_seen_at, revoked, revoked_at
		) VALUES (?, ?, ?, ?, ?, ?, ?, 0, NULL)
	`,
		[]byte(d.ID),
		[]byte(d.PublicKey),
		d.TokenHash,
		nullableString(d.Name),
		nullableString(d.Platform),
		d.CreatedAt,
		d.LastSeenAt,
	)
	if err != nil {
		if isUniqueViolation(err) {
			return device.ErrDuplicate
		}
		return fmt.Errorf("db: insert device: %w", err)
	}
	return nil
}

func (r *DeviceRepository) GetByID(ctx context.Context, id []byte) (*device.Device, error) {
	row := r.db.QueryRowContext(ctx, `
		SELECT device_id, device_pub, token_hash, device_name, platform,
		       created_at, last_seen_at, revoked, revoked_at
		  FROM devices
		 WHERE device_id = ?
	`, id)
	return scanDevice(row)
}

func (r *DeviceRepository) GetByTokenHash(ctx context.Context, tokenHash []byte) (*device.Device, error) {
	row := r.db.QueryRowContext(ctx, `
		SELECT device_id, device_pub, token_hash, device_name, platform,
		       created_at, last_seen_at, revoked, revoked_at
		  FROM devices
		 WHERE token_hash = ?
	`, tokenHash)
	d, err := scanDevice(row)
	if err != nil {
		return nil, err
	}
	if d.Revoked {
		return nil, device.ErrRevoked
	}
	return d, nil
}

func (r *DeviceRepository) TouchLastSeen(ctx context.Context, id []byte, at int64) error {
	res, err := r.db.ExecContext(ctx, `UPDATE devices SET last_seen_at = ? WHERE device_id = ?`, at, id)
	if err != nil {
		return fmt.Errorf("db: touch last_seen: %w", err)
	}
	n, err := res.RowsAffected()
	if err != nil {
		return fmt.Errorf("db: touch last_seen rows: %w", err)
	}
	if n == 0 {
		return device.ErrNotFound
	}
	return nil
}

func (r *DeviceRepository) Revoke(ctx context.Context, id []byte, at int64) error {
	// Idempotent: the WHERE clause restricts to revoked=0 so re-issues
	// are no-ops; we then verify existence separately.
	if _, err := r.db.ExecContext(ctx,
		`UPDATE devices SET revoked = 1, revoked_at = ? WHERE device_id = ? AND revoked = 0`,
		at, id,
	); err != nil {
		return fmt.Errorf("db: revoke: %w", err)
	}
	// Confirm the device exists at all; a missing device is an error
	// even when the intent is idempotent revocation.
	var one int
	err := r.db.QueryRowContext(ctx, `SELECT 1 FROM devices WHERE device_id = ?`, id).Scan(&one)
	if errors.Is(err, sql.ErrNoRows) {
		return device.ErrNotFound
	}
	if err != nil {
		return fmt.Errorf("db: revoke existence check: %w", err)
	}
	return nil
}

func (r *DeviceRepository) List(ctx context.Context) ([]*device.Device, error) {
	rows, err := r.db.QueryContext(ctx, `
		SELECT device_id, device_pub, token_hash, device_name, platform,
		       created_at, last_seen_at, revoked, revoked_at
		  FROM devices
		 ORDER BY created_at ASC
	`)
	if err != nil {
		return nil, fmt.Errorf("db: list devices: %w", err)
	}
	defer rows.Close()

	var out []*device.Device
	for rows.Next() {
		d, err := scanDevice(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, d)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("db: list devices iter: %w", err)
	}
	return out, nil
}

// scanner abstracts *sql.Row and *sql.Rows so scanDevice can serve both.
type scanner interface {
	Scan(dest ...any) error
}

func scanDevice(s scanner) (*device.Device, error) {
	var (
		d           device.Device
		name, plat  sql.NullString
		revoked     int64
		revokedAt   sql.NullInt64
		id, pub, th []byte
	)
	if err := s.Scan(&id, &pub, &th, &name, &plat, &d.CreatedAt, &d.LastSeenAt, &revoked, &revokedAt); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, device.ErrNotFound
		}
		return nil, fmt.Errorf("db: scan device: %w", err)
	}
	d.ID = id
	d.PublicKey = ed25519.PublicKey(pub)
	d.TokenHash = th
	if name.Valid {
		d.Name = name.String
	}
	if plat.Valid {
		d.Platform = plat.String
	}
	d.Revoked = revoked == 1
	if revokedAt.Valid {
		d.RevokedAt = revokedAt.Int64
	}
	return &d, nil
}

// nullableString maps "" → sql.NullString{Valid:false} so the column
// stores NULL instead of the empty string — harmless cosmetic nicety
// that simplifies later diagnostics.
func nullableString(s string) sql.NullString {
	if s == "" {
		return sql.NullString{}
	}
	return sql.NullString{String: s, Valid: true}
}

// isUniqueViolation detects SQLite UNIQUE constraint failures. The
// modernc.org/sqlite package exposes extended error codes only via its
// internal lib/ subpackage, so we match on the SQLite-stable error
// message prefix instead ("UNIQUE constraint failed: …"). This covers
// both PRIMARY KEY and UNIQUE-index violations; both are treated as
// duplicates by the domain layer.
func isUniqueViolation(err error) bool {
	return err != nil && strings.Contains(err.Error(), "UNIQUE constraint failed")
}
