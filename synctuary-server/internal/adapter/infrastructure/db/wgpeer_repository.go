package db

import (
	"context"
	"database/sql"
	"errors"
	"fmt"

	"github.com/synctuary/synctuary-server/internal/domain/wgpeer"
)

// WGPeerRepository is the SQLite-backed implementation of
// wgpeer.Repository (migration 006_wg_peers.sql).
type WGPeerRepository struct {
	db *sql.DB
}

func NewWGPeerRepository(database *sql.DB) *WGPeerRepository {
	return &WGPeerRepository{db: database}
}

var _ wgpeer.Repository = (*WGPeerRepository)(nil)

func (r *WGPeerRepository) Create(ctx context.Context, p *wgpeer.Peer) error {
	if len(p.ID) != 16 {
		return fmt.Errorf("db: wgpeer.ID length %d, expected 16", len(p.ID))
	}
	if len(p.PublicKey) != 32 {
		return fmt.Errorf("db: wgpeer.PublicKey length %d, expected 32", len(p.PublicKey))
	}
	_, err := r.db.ExecContext(ctx, `
		INSERT INTO wg_peers (
			id, public_key, assigned_ip, name, device_id, created_at, revoked_at
		) VALUES (?, ?, ?, ?, ?, ?, NULL)
	`, p.ID, p.PublicKey, p.AssignedIP, p.Name, nullableBlob(p.DeviceID), p.CreatedAt)
	if err != nil {
		if isUniqueViolation(err) {
			return wgpeer.ErrDuplicate
		}
		return fmt.Errorf("db: CreateWGPeer: %w", err)
	}
	return nil
}

func (r *WGPeerRepository) GetByID(ctx context.Context, id []byte) (*wgpeer.Peer, error) {
	row := r.db.QueryRowContext(ctx, `
		SELECT id, public_key, assigned_ip, name, device_id, created_at, revoked_at
		FROM wg_peers WHERE id = ?
	`, id)
	p, err := scanWGPeer(row)
	if err != nil {
		return nil, fmt.Errorf("db: GetWGPeerByID: %w", err)
	}
	return p, nil
}

func (r *WGPeerRepository) ListActive(ctx context.Context) ([]wgpeer.Peer, error) {
	return r.scanPeers(ctx, `
		SELECT id, public_key, assigned_ip, name, device_id, created_at, revoked_at
		FROM wg_peers WHERE revoked_at IS NULL ORDER BY created_at ASC
	`)
}

func (r *WGPeerRepository) ListAll(ctx context.Context) ([]wgpeer.Peer, error) {
	return r.scanPeers(ctx, `
		SELECT id, public_key, assigned_ip, name, device_id, created_at, revoked_at
		FROM wg_peers ORDER BY created_at ASC
	`)
}

func (r *WGPeerRepository) AssignedIPs(ctx context.Context) ([]string, error) {
	rows, err := r.db.QueryContext(ctx, `
		SELECT assigned_ip FROM wg_peers WHERE revoked_at IS NULL
	`)
	if err != nil {
		return nil, fmt.Errorf("db: AssignedIPs: %w", err)
	}
	defer rows.Close()

	var ips []string
	for rows.Next() {
		var ip string
		if err := rows.Scan(&ip); err != nil {
			return nil, fmt.Errorf("db: AssignedIPs scan: %w", err)
		}
		ips = append(ips, ip)
	}
	return ips, rows.Err()
}

func (r *WGPeerRepository) Revoke(ctx context.Context, id []byte, at int64) error {
	res, err := r.db.ExecContext(ctx, `
		UPDATE wg_peers SET revoked_at = ?
		WHERE id = ? AND revoked_at IS NULL
	`, at, id)
	if err != nil {
		return fmt.Errorf("db: RevokeWGPeer: %w", err)
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		return wgpeer.ErrNotFound
	}
	return nil
}

func (r *WGPeerRepository) Delete(ctx context.Context, id []byte) error {
	res, err := r.db.ExecContext(ctx, `DELETE FROM wg_peers WHERE id = ?`, id)
	if err != nil {
		return fmt.Errorf("db: DeleteWGPeer: %w", err)
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		return wgpeer.ErrNotFound
	}
	return nil
}

func (r *WGPeerRepository) scanPeers(ctx context.Context, query string) ([]wgpeer.Peer, error) {
	rows, err := r.db.QueryContext(ctx, query)
	if err != nil {
		return nil, fmt.Errorf("db: ListWGPeers: %w", err)
	}
	defer rows.Close()

	var result []wgpeer.Peer
	for rows.Next() {
		var p wgpeer.Peer
		var deviceID sql.NullString
		var revokedAt sql.NullInt64
		if err := rows.Scan(
			&p.ID, &p.PublicKey, &p.AssignedIP, &p.Name,
			&deviceID, &p.CreatedAt, &revokedAt,
		); err != nil {
			return nil, fmt.Errorf("db: ListWGPeers scan: %w", err)
		}
		if deviceID.Valid {
			p.DeviceID = []byte(deviceID.String)
		}
		if revokedAt.Valid {
			p.RevokedAt = revokedAt.Int64
		}
		result = append(result, p)
	}
	return result, rows.Err()
}

func scanWGPeer(sc scanner) (*wgpeer.Peer, error) {
	var p wgpeer.Peer
	var deviceID []byte
	var revokedAt sql.NullInt64
	err := sc.Scan(
		&p.ID, &p.PublicKey, &p.AssignedIP, &p.Name,
		&deviceID, &p.CreatedAt, &revokedAt,
	)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, wgpeer.ErrNotFound
		}
		return nil, err
	}
	p.DeviceID = deviceID
	if revokedAt.Valid {
		p.RevokedAt = revokedAt.Int64
	}
	return &p, nil
}

// nullableBlob converts a []byte to sql.NullString for nullable BLOB columns.
// nil/empty slices produce NULL; non-empty slices produce the raw bytes.
func nullableBlob(b []byte) any {
	if len(b) == 0 {
		return nil
	}
	return b
}
