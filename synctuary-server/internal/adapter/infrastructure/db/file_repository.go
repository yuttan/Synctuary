package db

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/base64"
	"errors"
	"fmt"
	"time"

	"github.com/synctuary/synctuary-server/internal/domain/file"
)

// FileRepository backs file.Repository by reading the `uploads` table
// restricted to completed rows. It serves two purposes:
//
//   - FindBySHA powers the §6.3.1 dedup lookup.
//   - FindByPath powers the §6.3.1 409 file_exists body.
//
// Files placed on disk by means other than the upload API (manual
// copy, previous server version) are NOT indexed here; the handler
// falls back to filesystem stat + on-demand hashing in those cases.
type FileRepository struct {
	db *sql.DB
}

func NewFileRepository(database *sql.DB) *FileRepository {
	return &FileRepository{db: database}
}

var _ file.Repository = (*FileRepository)(nil)

func (r *FileRepository) FindBySHA(ctx context.Context, sha256 []byte) (*file.FileMeta, error) {
	if len(sha256) != 32 {
		return nil, fmt.Errorf("db: sha256 length %d, expected 32", len(sha256))
	}
	row := r.db.QueryRowContext(ctx, `
		SELECT path, size, sha256_expected, last_write_at
		  FROM uploads
		 WHERE sha256_expected = ? AND completed = 1
		 ORDER BY last_write_at DESC
		 LIMIT 1
	`, sha256)
	return scanFileMeta(row)
}

func (r *FileRepository) FindByPath(ctx context.Context, path string) (*file.FileMeta, error) {
	row := r.db.QueryRowContext(ctx, `
		SELECT path, size, sha256_expected, last_write_at
		  FROM uploads
		 WHERE path = ? AND completed = 1
		 ORDER BY last_write_at DESC
		 LIMIT 1
	`, path)
	return scanFileMeta(row)
}

// Upsert inserts a synthetic completed `uploads` row that records a
// file placed on disk by the dedup hardlink or sync-copy branch. The
// row mimics the post-completion shape of a normal upload session
// (uploaded_bytes == size, completed = 1, empty staging_path) so the
// existing FindBySHA / FindByPath queries — which both filter on
// completed = 1 — pick it up without further changes.
//
// We do NOT use INSERT OR REPLACE on `path`: the schema permits
// multiple completed rows for the same path (versioning), and the
// FindByPath query already returns the most-recently-written one via
// `ORDER BY last_write_at DESC LIMIT 1`. Inserting a fresh row here
// is therefore both correct (preserves history) and conflict-free
// (the active-session unique index is partial on completed = 0).
func (r *FileRepository) Upsert(ctx context.Context, meta *file.FileMeta, deviceID []byte) error {
	if meta == nil {
		return fmt.Errorf("db: Upsert: nil meta")
	}
	if len(meta.SHA256) != 32 {
		return fmt.Errorf("db: Upsert: sha256 length %d, expected 32", len(meta.SHA256))
	}
	if len(deviceID) != 16 {
		return fmt.Errorf("db: Upsert: device_id length %d, expected 16", len(deviceID))
	}

	var idBytes [16]byte
	if _, err := rand.Read(idBytes[:]); err != nil {
		return fmt.Errorf("db: Upsert: gen synth id: %w", err)
	}
	syntheticID := base64.RawURLEncoding.EncodeToString(idBytes[:])

	now := meta.ModifiedAt
	if now == 0 {
		now = time.Now().Unix()
	}

	_, err := r.db.ExecContext(ctx, `
		INSERT INTO uploads (
			upload_id, path, size, sha256_expected, uploaded_bytes,
			staging_path, device_id, overwrite, completed,
			created_at, last_write_at, expires_at
		) VALUES (?, ?, ?, ?, ?, '', ?, 0, 1, ?, ?, ?)
	`,
		syntheticID, meta.Path, meta.Size, meta.SHA256, meta.Size,
		deviceID, now, now, now,
	)
	if err != nil {
		return fmt.Errorf("db: Upsert: insert: %w", err)
	}
	return nil
}

func scanFileMeta(s scanner) (*file.FileMeta, error) {
	var (
		m   file.FileMeta
		sha []byte
	)
	if err := s.Scan(&m.Path, &m.Size, &sha, &m.ModifiedAt); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, file.ErrFileNotFound
		}
		return nil, fmt.Errorf("db: scan file meta: %w", err)
	}
	m.SHA256 = sha
	return &m, nil
}
