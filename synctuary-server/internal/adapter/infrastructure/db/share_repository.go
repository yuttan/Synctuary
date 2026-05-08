package db

import (
	"context"
	"database/sql"
	"errors"
	"fmt"

	"github.com/synctuary/synctuary-server/internal/domain/share"
)

// ShareRepository is the SQLite-backed implementation of
// share.Repository (migration 004_shares.sql).
type ShareRepository struct {
	db *sql.DB
}

func NewShareRepository(database *sql.DB) *ShareRepository {
	return &ShareRepository{db: database}
}

var _ share.Repository = (*ShareRepository)(nil)

func (r *ShareRepository) Create(ctx context.Context, s *share.Share) error {
	if len(s.ID) != 16 {
		return fmt.Errorf("db: share.ID length %d, expected 16", len(s.ID))
	}
	_, err := r.db.ExecContext(ctx, `
		INSERT INTO shares (
			id, name, host_path, read_only, icon, sort_order,
			is_default, created_at, modified_at
		) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
	`, s.ID, s.Name, s.HostPath, boolToInt(s.ReadOnly), s.Icon,
		s.SortOrder, boolToInt(s.IsDefault), s.CreatedAt, s.ModifiedAt)
	if err != nil {
		if isUniqueViolation(err) {
			return share.ErrDuplicate
		}
		return fmt.Errorf("db: CreateShare: %w", err)
	}
	return nil
}

func (r *ShareRepository) GetByID(ctx context.Context, id []byte) (*share.Share, error) {
	row := r.db.QueryRowContext(ctx, `
		SELECT id, name, host_path, read_only, icon, sort_order,
		       is_default, created_at, modified_at
		FROM shares WHERE id = ?
	`, id)
	s, err := scanShare(row)
	if err != nil {
		return nil, fmt.Errorf("db: GetShareByID: %w", err)
	}
	return s, nil
}

func (r *ShareRepository) GetDefault(ctx context.Context) (*share.Share, error) {
	row := r.db.QueryRowContext(ctx, `
		SELECT id, name, host_path, read_only, icon, sort_order,
		       is_default, created_at, modified_at
		FROM shares WHERE is_default = 1
	`)
	s, err := scanShare(row)
	if err != nil {
		return nil, fmt.Errorf("db: GetDefaultShare: %w", err)
	}
	return s, nil
}

func (r *ShareRepository) List(ctx context.Context) ([]share.Share, error) {
	rows, err := r.db.QueryContext(ctx, `
		SELECT id, name, host_path, read_only, icon, sort_order,
		       is_default, created_at, modified_at
		FROM shares
		ORDER BY sort_order ASC, name ASC
	`)
	if err != nil {
		return nil, fmt.Errorf("db: ListShares: %w", err)
	}
	defer rows.Close()

	var result []share.Share
	for rows.Next() {
		var s share.Share
		var readOnly, isDefault int
		if err := rows.Scan(
			&s.ID, &s.Name, &s.HostPath, &readOnly, &s.Icon,
			&s.SortOrder, &isDefault, &s.CreatedAt, &s.ModifiedAt,
		); err != nil {
			return nil, fmt.Errorf("db: ListShares scan: %w", err)
		}
		s.ReadOnly = readOnly != 0
		s.IsDefault = isDefault != 0
		result = append(result, s)
	}
	return result, rows.Err()
}

func (r *ShareRepository) Update(ctx context.Context, id []byte, patch share.SharePatch, now int64) error {
	var sets []string
	var args []any

	if patch.Name != nil {
		sets = append(sets, "name = ?")
		args = append(args, *patch.Name)
	}
	if patch.HostPath != nil {
		sets = append(sets, "host_path = ?")
		args = append(args, *patch.HostPath)
	}
	if patch.ReadOnly != nil {
		sets = append(sets, "read_only = ?")
		args = append(args, boolToInt(*patch.ReadOnly))
	}
	if patch.Icon != nil {
		sets = append(sets, "icon = ?")
		args = append(args, *patch.Icon)
	}
	if patch.SortOrder != nil {
		sets = append(sets, "sort_order = ?")
		args = append(args, *patch.SortOrder)
	}

	if len(sets) == 0 {
		return nil
	}

	sets = append(sets, "modified_at = ?")
	args = append(args, now)
	args = append(args, id)

	query := "UPDATE shares SET " + joinStrings(sets, ", ") + " WHERE id = ?" //nolint:gosec // G202: column names are hardcoded, values use placeholders
	res, err := r.db.ExecContext(ctx, query, args...)
	if err != nil {
		if isUniqueViolation(err) {
			return share.ErrHostPathInUse
		}
		return fmt.Errorf("db: UpdateShare: %w", err)
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		return share.ErrNotFound
	}
	return nil
}

func (r *ShareRepository) Delete(ctx context.Context, id []byte) error {
	res, err := r.db.ExecContext(ctx, `DELETE FROM shares WHERE id = ?`, id)
	if err != nil {
		return fmt.Errorf("db: DeleteShare: %w", err)
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		return share.ErrNotFound
	}
	return nil
}

func scanShare(sc scanner) (*share.Share, error) {
	var s share.Share
	var readOnly, isDefault int
	err := sc.Scan(
		&s.ID, &s.Name, &s.HostPath, &readOnly, &s.Icon,
		&s.SortOrder, &isDefault, &s.CreatedAt, &s.ModifiedAt,
	)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, share.ErrNotFound
		}
		return nil, err
	}
	s.ReadOnly = readOnly != 0
	s.IsDefault = isDefault != 0
	return &s, nil
}

func joinStrings(elems []string, sep string) string {
	result := ""
	for i, e := range elems {
		if i > 0 {
			result += sep
		}
		result += e
	}
	return result
}
