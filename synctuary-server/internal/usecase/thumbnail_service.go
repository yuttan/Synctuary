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
	"math"
	"os/exec"
	"strconv"
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

func (s *ThumbnailService) WithStorage(storage file.FileStorage) *ThumbnailService {
	return &ThumbnailService{thumbs: s.thumbs, storage: storage, log: s.log}
}

func (s *ThumbnailService) Get(ctx context.Context, path string, size int) (*file.Thumbnail, error) {
	return s.GetAt(ctx, path, size, 0)
}

// GetAt returns a thumbnail for path at size (px, longest side). When
// tSeconds <= 0 the behavior is identical to Get: for images and videos
// alike a DB-cached thumbnail is served/generated, with the video frame
// taken at the fixed 1s mark.
//
// When tSeconds > 0 the request is video-only (images are rejected with
// the unsupported-mime error) and a frame is extracted at that timestamp
// via ffmpeg's fast input-side -ss. Arbitrary-t frames are NOT written to
// the SQLite cache — a seek-preview scrub can request hundreds of distinct
// timestamps and would bloat the cache; the client HTTP-caches instead.
func (s *ThumbnailService) GetAt(ctx context.Context, path string, size int, tSeconds float64) (*file.Thumbnail, error) {
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

	// Arbitrary-timestamp path: video-only, uncached.
	if tSeconds > 0 {
		if !isVideo(meta.MimeType) {
			return nil, fmt.Errorf("thumbnail: unsupported mime %q for timestamped frame", meta.MimeType)
		}
		absPath, err := s.storage.Resolve(ctx, path)
		if err != nil {
			return nil, fmt.Errorf("thumbnail: resolve path: %w", err)
		}
		data, err := generateFromVideo(ctx, absPath, size, tSeconds)
		if err != nil {
			return nil, fmt.Errorf("thumbnail: video generate: %w", err)
		}
		return &file.Thumbnail{
			Path:        path,
			Format:      "jpeg",
			Width:       size,
			Height:      size,
			Data:        data,
			SourceHash:  hex.EncodeToString(meta.SHA256),
			GeneratedAt: time.Now().Unix(),
		}, nil
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
		data, err = generateFromVideo(ctx, absPath, size, 0)
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

func generateFromVideo(ctx context.Context, absPath string, size int, tSeconds float64) ([]byte, error) {
	args := buildVideoThumbArgs(absPath, tSeconds)
	cmd := exec.CommandContext(ctx, ffmpegPath, args...)
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		return nil, fmt.Errorf("ffmpeg: %w (stderr: %s)", err, stderr.String())
	}
	return generate(&stdout, size)
}

// buildVideoThumbArgs constructs the ffmpeg argument vector for a single
// PNG frame extraction on stdout. Extracted as a pure function so the -ss
// placement — input-side (BEFORE -i) for a fast keyframe seek — is
// unit-testable without invoking ffmpeg.
//
// tSeconds <= 0 (or non-finite) falls back to the fixed 1s mark, matching
// the historical default. tSeconds > 0 seeks to that timestamp, formatted
// with 3 decimals for sub-second precision.
func buildVideoThumbArgs(absPath string, tSeconds float64) []string {
	ss := "1"
	if tSeconds > 0 && !math.IsInf(tSeconds, 0) && !math.IsNaN(tSeconds) {
		ss = strconv.FormatFloat(tSeconds, 'f', 3, 64)
	}
	return []string{
		"-ss", ss,
		"-i", absPath,
		"-vframes", "1",
		"-f", "image2pipe",
		"-vcodec", "png",
		"-",
	}
}
