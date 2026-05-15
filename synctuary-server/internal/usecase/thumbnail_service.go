package usecase

import (
	"bytes"
	"context"
	"encoding/hex"
	"fmt"
	"image"
	"image/jpeg"
	_ "image/gif"
	_ "image/png"
	"io"
	"log/slog"
	"strings"
	"time"

	_ "golang.org/x/image/webp"

	"github.com/disintegration/imaging"

	"github.com/synctuary/synctuary-server/internal/domain/file"
)

const (
	DefaultThumbSize = 256
	MaxThumbSize     = 512
	thumbQuality     = 80
)

type ThumbnailService struct {
	thumbs  file.ThumbnailRepository
	storage file.FileStorage
	log     *slog.Logger
}

func NewThumbnailService(thumbs file.ThumbnailRepository, storage file.FileStorage, log *slog.Logger) *ThumbnailService {
	if log == nil {
		log = slog.Default()
	}
	return &ThumbnailService{thumbs: thumbs, storage: storage, log: log}
}

func (s *ThumbnailService) Get(ctx context.Context, path string, size int) (*file.Thumbnail, error) {
	if size <= 0 {
		size = DefaultThumbSize
	}
	if size > MaxThumbSize {
		size = MaxThumbSize
	}

	meta, err := s.storage.Stat(ctx, path)
	if err != nil {
		return nil, err
	}

	if !isThumbnailable(meta.MimeType) {
		return nil, fmt.Errorf("thumbnail: unsupported mime %q", meta.MimeType)
	}

	currentHash := hex.EncodeToString(meta.SHA256)

	cached, err := s.thumbs.Get(ctx, path, size, size)
	if err == nil && cached.SourceHash == currentHash {
		return cached, nil
	}

	rc, err := s.storage.Get(ctx, path, 0, -1)
	if err != nil {
		return nil, fmt.Errorf("thumbnail: read source: %w", err)
	}
	defer rc.Close()

	data, err := generate(rc, size)
	if err != nil {
		return nil, fmt.Errorf("thumbnail: generate: %w", err)
	}

	t := &file.Thumbnail{
		Path:        path,
		Format:      "jpeg",
		Width:       size,
		Height:      size,
		Data:        data,
		SourceHash:  currentHash,
		GeneratedAt: time.Now().Unix(),
	}

	if putErr := s.thumbs.Put(ctx, t); putErr != nil {
		s.log.Warn("thumbnail: cache put failed", slog.String("err", putErr.Error()))
	}

	return t, nil
}

func generate(r io.Reader, size int) ([]byte, error) {
	src, _, err := image.Decode(r)
	if err != nil {
		return nil, err
	}

	thumb := imaging.Fit(src, size, size, imaging.Lanczos)

	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, thumb, &jpeg.Options{Quality: thumbQuality}); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

func isThumbnailable(mime string) bool {
	switch {
	case strings.HasPrefix(mime, "image/jpeg"),
		strings.HasPrefix(mime, "image/png"),
		strings.HasPrefix(mime, "image/gif"),
		strings.HasPrefix(mime, "image/webp"):
		return true
	}
	return false
}
