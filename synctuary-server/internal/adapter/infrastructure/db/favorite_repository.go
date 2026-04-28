package db

import (
	"context"
	"database/sql"
	"errors"
	"fmt"

	"github.com/synctuary/synctuary-server/internal/domain/favorite"
)

// FavoriteRepository is the SQLite-backed implementation of
// favorite.Repository (PROTOCOL v0.2.3 §8).
//
// The schema lives in migration 003_favorites.sql. Two tables:
//   - favorite_lists  (1:N)
//   - favorite_items  (composite PK on (list_id, path))
//
// All multi-statement operations use a transaction so the
// `modified_at` bump on the parent list is atomic with the item-row
// insert / delete it accompanies.
type FavoriteRepository struct {
	db *sql.DB
}

func NewFavoriteRepository(database *sql.DB) *FavoriteRepository {
	return &FavoriteRepository{db: database}
}

var _ favorite.Repository = (*FavoriteRepository)(nil)

// CreateList inserts a new list. Caller already generated the 16-byte
// id and stamped CreatedAt / ModifiedAt. The CreatedByDeviceID field
// is optional; nil/empty inserts NULL (audit lost on a system list,
// but we never expose that path today).
func (r *FavoriteRepository) CreateList(ctx context.Context, l *favorite.List) error {
	if len(l.ID) != 16 {
		return fmt.Errorf("db: list.ID length %d, expected 16", len(l.ID))
	}
	if l.CreatedByDeviceID != nil && len(l.CreatedByDeviceID) != 16 {
		return fmt.Errorf("db: list.CreatedByDeviceID length %d, expected 16 or nil", len(l.CreatedByDeviceID))
	}

	var deviceID any
	if len(l.CreatedByDeviceID) == 16 {
		deviceID = []byte(l.CreatedByDeviceID)
	} else {
		deviceID = nil
	}

	_, err := r.db.ExecContext(ctx, `
		INSERT INTO favorite_lists (
			id, name, hidden, created_at, modified_at, created_by_device_id
		) VALUES (?, ?, ?, ?, ?, ?)
	`, l.ID, l.Name, boolToInt(l.Hidden), l.CreatedAt, l.ModifiedAt, deviceID)
	if err != nil {
		if isUniqueViolation(err) {
			return favorite.ErrDuplicate
		}
		return fmt.Errorf("db: CreateList: %w", err)
	}
	return nil
}

// ListAll returns summaries (no items). Uses the partial index
// idx_favorite_lists_visible when includeHidden=false.
func (r *FavoriteRepository) ListAll(ctx context.Context, includeHidden bool) ([]favorite.List, error) {
	q := `
		SELECT l.id, l.name, l.hidden, l.created_at, l.modified_at,
		       l.created_by_device_id,
		       (SELECT COUNT(*) FROM favorite_items i WHERE i.list_id = l.id) AS item_count
		  FROM favorite_lists l`
	if !includeHidden {
		q += ` WHERE l.hidden = 0`
	}
	// modified_at is unix seconds — coarse enough that two lists
	// touched in the same second collide. Tiebreak by id DESC so
	// the order is deterministic across requests; this matters for
	// pagination and for clients that diff the response.
	q += ` ORDER BY l.modified_at DESC, l.id DESC`

	rows, err := r.db.QueryContext(ctx, q)
	if err != nil {
		return nil, fmt.Errorf("db: ListAll: %w", err)
	}
	defer rows.Close()

	var out []favorite.List
	for rows.Next() {
		var (
			l             favorite.List
			hiddenInt     int
			deviceIDBytes []byte
		)
		if err := rows.Scan(&l.ID, &l.Name, &hiddenInt, &l.CreatedAt, &l.ModifiedAt, &deviceIDBytes, &l.ItemCount); err != nil {
			return nil, fmt.Errorf("db: ListAll scan: %w", err)
		}
		l.Hidden = hiddenInt == 1
		l.CreatedByDeviceID = deviceIDBytes
		out = append(out, l)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("db: ListAll iterate: %w", err)
	}
	return out, nil
}

// GetList returns one list with its items. Two queries: one for the
// summary, one for the item slice — the alternative (single JOIN)
// would multiply rows by item_count and the post-processing isn't
// worth the saved round-trip on a localhost SQLite.
func (r *FavoriteRepository) GetList(ctx context.Context, id []byte) (*favorite.ListWithItems, error) {
	if len(id) != 16 {
		return nil, fmt.Errorf("db: GetList id length %d, expected 16", len(id))
	}

	var (
		l             favorite.List
		hiddenInt     int
		deviceIDBytes []byte
	)
	err := r.db.QueryRowContext(ctx, `
		SELECT id, name, hidden, created_at, modified_at, created_by_device_id,
		       (SELECT COUNT(*) FROM favorite_items i WHERE i.list_id = favorite_lists.id) AS item_count
		  FROM favorite_lists
		 WHERE id = ?
	`, id).Scan(&l.ID, &l.Name, &hiddenInt, &l.CreatedAt, &l.ModifiedAt, &deviceIDBytes, &l.ItemCount)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, favorite.ErrListNotFound
		}
		return nil, fmt.Errorf("db: GetList summary: %w", err)
	}
	l.Hidden = hiddenInt == 1
	l.CreatedByDeviceID = deviceIDBytes

	rows, err := r.db.QueryContext(ctx, `
		SELECT path, added_at, added_by_device_id
		  FROM favorite_items
		 WHERE list_id = ?
		 ORDER BY added_at ASC
	`, id)
	if err != nil {
		return nil, fmt.Errorf("db: GetList items: %w", err)
	}
	defer rows.Close()

	out := &favorite.ListWithItems{List: l}
	for rows.Next() {
		var (
			it       favorite.Item
			addedDev []byte
		)
		if err := rows.Scan(&it.Path, &it.AddedAt, &addedDev); err != nil {
			return nil, fmt.Errorf("db: GetList item scan: %w", err)
		}
		it.AddedByDeviceID = addedDev
		out.Items = append(out.Items, it)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("db: GetList items iterate: %w", err)
	}
	return out, nil
}

// UpdateList applies a partial update + bumps modified_at.
//
// The §8.5 patch has only two optional fields (name, hidden), so the
// shape space is small enough to enumerate all three valid combinations
// as constant SQL. This avoids string-concatenation SQL building (gosec
// G202) without giving up the "don't touch absent fields" property.
//
// The all-nil case is unreachable at this point: the handler rejects
// it with 400 bad_request and the usecase has its own ErrFavoriteEmptyPatch
// guard. We assert it again here so a future refactor can't quietly
// regress.
func (r *FavoriteRepository) UpdateList(ctx context.Context, id []byte, patch favorite.ListPatch, now int64) error {
	if len(id) != 16 {
		return fmt.Errorf("db: UpdateList id length %d, expected 16", len(id))
	}

	var (
		res sql.Result
		err error
	)
	switch {
	case patch.Name != nil && patch.Hidden != nil:
		res, err = r.db.ExecContext(ctx,
			`UPDATE favorite_lists SET modified_at = ?, name = ?, hidden = ? WHERE id = ?`,
			now, *patch.Name, boolToInt(*patch.Hidden), id,
		)
	case patch.Name != nil:
		res, err = r.db.ExecContext(ctx,
			`UPDATE favorite_lists SET modified_at = ?, name = ? WHERE id = ?`,
			now, *patch.Name, id,
		)
	case patch.Hidden != nil:
		res, err = r.db.ExecContext(ctx,
			`UPDATE favorite_lists SET modified_at = ?, hidden = ? WHERE id = ?`,
			now, boolToInt(*patch.Hidden), id,
		)
	default:
		return fmt.Errorf("db: UpdateList: empty patch (caller should have rejected at handler)")
	}
	if err != nil {
		return fmt.Errorf("db: UpdateList: %w", err)
	}
	n, err := res.RowsAffected()
	if err != nil {
		return fmt.Errorf("db: UpdateList rows affected: %w", err)
	}
	if n == 0 {
		return favorite.ErrListNotFound
	}
	return nil
}

// DeleteList drops the list. ON DELETE CASCADE on favorite_items
// handles the children.
func (r *FavoriteRepository) DeleteList(ctx context.Context, id []byte) error {
	if len(id) != 16 {
		return fmt.Errorf("db: DeleteList id length %d, expected 16", len(id))
	}
	res, err := r.db.ExecContext(ctx, `DELETE FROM favorite_lists WHERE id = ?`, id)
	if err != nil {
		return fmt.Errorf("db: DeleteList: %w", err)
	}
	n, err := res.RowsAffected()
	if err != nil {
		return fmt.Errorf("db: DeleteList rows affected: %w", err)
	}
	if n == 0 {
		return favorite.ErrListNotFound
	}
	return nil
}

// AddItem inserts (list_id, path) atomically with a modified_at bump.
// On unique-key collision (the path is already in the list), looks up
// the existing row, returns it with inserted=false, and does NOT bump
// modified_at — per §8.7 the second add is a no-op observable to the
// client only as a 200 vs 201.
func (r *FavoriteRepository) AddItem(ctx context.Context, listID []byte, item favorite.Item) (favorite.Item, bool, error) {
	if len(listID) != 16 {
		return favorite.Item{}, false, fmt.Errorf("db: AddItem listID length %d, expected 16", len(listID))
	}
	if item.AddedByDeviceID != nil && len(item.AddedByDeviceID) != 16 {
		return favorite.Item{}, false, fmt.Errorf("db: AddItem AddedByDeviceID length %d, expected 16 or nil", len(item.AddedByDeviceID))
	}

	tx, err := r.db.BeginTx(ctx, nil)
	if err != nil {
		return favorite.Item{}, false, fmt.Errorf("db: AddItem begin: %w", err)
	}
	defer func() { _ = tx.Rollback() }()

	// Check the parent list exists; this gives us a clean
	// ErrListNotFound separate from FK violation noise.
	var exists int
	if err := tx.QueryRowContext(ctx, `SELECT 1 FROM favorite_lists WHERE id = ?`, listID).Scan(&exists); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return favorite.Item{}, false, favorite.ErrListNotFound
		}
		return favorite.Item{}, false, fmt.Errorf("db: AddItem list-exists check: %w", err)
	}

	var addedDev any
	if len(item.AddedByDeviceID) == 16 {
		addedDev = []byte(item.AddedByDeviceID)
	} else {
		addedDev = nil
	}

	_, err = tx.ExecContext(ctx, `
		INSERT INTO favorite_items (list_id, path, added_at, added_by_device_id)
		VALUES (?, ?, ?, ?)
	`, listID, item.Path, item.AddedAt, addedDev)

	if err != nil {
		if isUniqueViolation(err) {
			// Idempotent re-add: load the existing row,
			// return inserted=false, do NOT bump modified_at.
			var (
				existing      favorite.Item
				existingAdder []byte
			)
			if err := tx.QueryRowContext(ctx, `
				SELECT path, added_at, added_by_device_id
				  FROM favorite_items
				 WHERE list_id = ? AND path = ?
			`, listID, item.Path).Scan(&existing.Path, &existing.AddedAt, &existingAdder); err != nil {
				return favorite.Item{}, false, fmt.Errorf("db: AddItem existing lookup: %w", err)
			}
			existing.AddedByDeviceID = existingAdder
			if err := tx.Commit(); err != nil {
				return favorite.Item{}, false, fmt.Errorf("db: AddItem commit (idempotent): %w", err)
			}
			return existing, false, nil
		}
		return favorite.Item{}, false, fmt.Errorf("db: AddItem insert: %w", err)
	}

	if _, err := tx.ExecContext(ctx,
		`UPDATE favorite_lists SET modified_at = ? WHERE id = ?`,
		item.AddedAt, listID,
	); err != nil {
		return favorite.Item{}, false, fmt.Errorf("db: AddItem bump modified_at: %w", err)
	}

	if err := tx.Commit(); err != nil {
		return favorite.Item{}, false, fmt.Errorf("db: AddItem commit: %w", err)
	}
	return item, true, nil
}

// RemoveItem deletes (list_id, path) atomically with a modified_at bump.
// Distinguishes ErrListNotFound from ErrItemNotFound so the handler
// can emit the right §9 error code.
func (r *FavoriteRepository) RemoveItem(ctx context.Context, listID []byte, path string, now int64) error {
	if len(listID) != 16 {
		return fmt.Errorf("db: RemoveItem listID length %d, expected 16", len(listID))
	}

	tx, err := r.db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("db: RemoveItem begin: %w", err)
	}
	defer func() { _ = tx.Rollback() }()

	var exists int
	if err := tx.QueryRowContext(ctx, `SELECT 1 FROM favorite_lists WHERE id = ?`, listID).Scan(&exists); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return favorite.ErrListNotFound
		}
		return fmt.Errorf("db: RemoveItem list-exists check: %w", err)
	}

	res, err := tx.ExecContext(ctx,
		`DELETE FROM favorite_items WHERE list_id = ? AND path = ?`,
		listID, path,
	)
	if err != nil {
		return fmt.Errorf("db: RemoveItem delete: %w", err)
	}
	n, err := res.RowsAffected()
	if err != nil {
		return fmt.Errorf("db: RemoveItem rows affected: %w", err)
	}
	if n == 0 {
		return favorite.ErrItemNotFound
	}

	if _, err := tx.ExecContext(ctx,
		`UPDATE favorite_lists SET modified_at = ? WHERE id = ?`,
		now, listID,
	); err != nil {
		return fmt.Errorf("db: RemoveItem bump modified_at: %w", err)
	}

	if err := tx.Commit(); err != nil {
		return fmt.Errorf("db: RemoveItem commit: %w", err)
	}
	return nil
}
