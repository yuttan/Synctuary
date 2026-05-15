package usecase

import (
	"bytes"
	"context"
	"encoding/hex"
	"fmt"
	"image"
	_ "image/gif"
	"image/jpeg"
	_ "image/png"
	"io"
	"log/slog"
	"os/exec"
	"strings"
	"time"

	_ "golang.org/x/image/webp"

	"github.com/disintegration/imaging"

	"github.com/synctuary/synctuary-server/internal/domain/file"
)

var ffmpegAvailable = func() bool {
	_, err := exec.LookPath("ffmpeg")
	return err == nil
}()

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

func (s *ThumbnailService) WithStorage(storage file.FileStorage) *ThumbnailService {
	return &ThumbnailService{thumbs: s.thumbs, storage: storage, log: s.log}
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

	var data []byte
	if isVideo(meta.MimeType) {
		absPath, err := s.storage.Resolve(ctx, path)
		if err != nil {
			return nil, fmt.Errorf("thumbnail: resolve path: %w", err)
		}
		data, err = generateFromVideo(ctx, absPath, size)
		if err != nil {
			return nil, fmt.Errorf("thumbnail: video generate: %w", err)
		}
	} else {
		rc, err := s.storage.Get(ctx, path, 0, -1)
		if err != nil {
			return nil, fmt.Errorf("thumbnail: read source: %w", err)
		}
		defer rc.Close()
		data, err = generate(rc, size)
		if err != nil {
			return nil, fmt.Errorf("thumbnail: generate: %w", err)
		}
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
	case isVideo(mime) && ffmpegAvailable:
		return true
	}
	return false
}

func isVideo(mime string) bool {
	return strings.HasPrefix(mime, "video/")
}

func generateFromVideo(ctx context.Context, absPath string, size int) ([]byte, error) {
	cmd := exec.CommandContext(ctx,
		"ffmpeg",
		"-ss", "1",
		"-i", absPath,
		"-vframes", "1",
		"-f", "image2pipe",
		"-vcodec", "png",
		"-",
	)
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		return nil, fmt.Errorf("ffmpeg: %w (stderr: %s)", err, stderr.String())
	}
	return generate(&stdout, size)
}
