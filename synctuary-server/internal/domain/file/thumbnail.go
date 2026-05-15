package file

import "context"

// Thumbnail is a cached preview image for a file.
type Thumbnail struct {
	Path        string
	Format      string // "jpeg"
	Width       int
	Height      int
	Data        []byte
	SourceHash  string // hex SHA-256 of the source at generation time
	GeneratedAt int64  // unix seconds
}

// ThumbnailRepository persists generated thumbnails in a database cache.
type ThumbnailRepository interface {
	Get(ctx context.Context, path string, width, height int) (*Thumbnail, error)
	Put(ctx context.Context, t *Thumbnail) error
	DeleteByPath(ctx context.Context, path string) error
}
