package db

import (
	"context"
	"database/sql"

	"github.com/synctuary/synctuary-server/internal/domain/file"
)

var _ file.ThumbnailRepository = (*ThumbnailRepository)(nil)

type ThumbnailRepository struct{ db *sql.DB }

func NewThumbnailRepository(db *sql.DB) *ThumbnailRepository {
	return &ThumbnailRepository{db: db}
}

func (r *ThumbnailRepository) Get(ctx context.Context, path string, width, height int) (*file.Thumbnail, error) {
	row := r.db.QueryRowContext(ctx,
		`SELECT path, format, width, height, data, source_hash, generated_at
		   FROM thumbnails WHERE path = ? AND width = ? AND height = ?`,
		path, width, height)

	var t file.Thumbnail
	var sourceHash sql.NullString
	err := row.Scan(&t.Path, &t.Format, &t.Width, &t.Height, &t.Data, &sourceHash, &t.GeneratedAt)
	if err == sql.ErrNoRows {
		return nil, file.ErrFileNotFound
	}
	if err != nil {
		return nil, err
	}
	t.SourceHash = sourceHash.String
	return &t, nil
}

func (r *ThumbnailRepository) Put(ctx context.Context, t *file.Thumbnail) error {
	_, err := r.db.ExecContext(ctx,
		`INSERT INTO thumbnails (path, format, width, height, data, source_hash, generated_at)
		 VALUES (?, ?, ?, ?, ?, ?, ?)
		 ON CONFLICT (path, width, height) DO UPDATE SET
		   data = excluded.data,
		   source_hash = excluded.source_hash,
		   generated_at = excluded.generated_at`,
		t.Path, t.Format, t.Width, t.Height, t.Data, t.SourceHash, t.GeneratedAt)
	return err
}

func (r *ThumbnailRepository) DeleteByPath(ctx context.Context, path string) error {
	_, err := r.db.ExecContext(ctx, `DELETE FROM thumbnails WHERE path = ?`, path)
	return err
}
