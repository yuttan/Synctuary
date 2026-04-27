// Package fs implements domain/file.FileStorage on a regular
// filesystem tree. v0.4 scope: hardlink-based deduplication with a
// clean ErrDedupUnsupported fallback; reflink (FICLONE / ReFS block
// clone) is a v0.4.1 optimization that can ride on top without
// interface changes.
//
// Storage layout:
//
//	root/                   — user-facing tree; paths exposed to API
//	staging/                — in-progress uploads and atomic-write tmps
//
// `root` and `staging` MUST live on the same filesystem; all atomic
// renames (Put, upload-complete) assume this. Placing them on
// different volumes degrades silently to non-atomic fallback and is
// deliberately not supported.
package fs

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"syscall"

	domainfile "github.com/synctuary/synctuary-server/internal/domain/file"
)

var _ domainfile.FileStorage = (*FileStorage)(nil)

// SourceResolver resolves a content SHA-256 to an absolute source
// path on the local filesystem. FileStorage uses this for dedup and
// sync-copy; in v0.4 it is wired to a wrapper around
// domainfile.Repository.FindBySHA.
type SourceResolver interface {
	// ResolvePath returns the absolute on-disk path of a file whose
	// content hashes to sha256. Returns domainfile.ErrFileNotFound
	// when no such file is tracked.
	ResolvePath(ctx context.Context, sha256 []byte) (string, error)
}

type FileStorage struct {
	root     string // absolute path, no trailing slash
	staging  string // absolute path, no trailing slash
	resolver SourceResolver
}

// NewFileStorage validates and canonicalises the paths at construction
// time so per-request calls can skip them.
func NewFileStorage(root, staging string, resolver SourceResolver) (*FileStorage, error) {
	rootAbs, err := filepath.Abs(root)
	if err != nil {
		return nil, fmt.Errorf("fs: resolve root: %w", err)
	}
	stagingAbs, err := filepath.Abs(staging)
	if err != nil {
		return nil, fmt.Errorf("fs: resolve staging: %w", err)
	}
	if err := os.MkdirAll(rootAbs, 0o755); err != nil {
		return nil, fmt.Errorf("fs: mkdir root: %w", err)
	}
	if err := os.MkdirAll(stagingAbs, 0o755); err != nil {
		return nil, fmt.Errorf("fs: mkdir staging: %w", err)
	}
	if resolver == nil {
		return nil, errors.New("fs: SourceResolver is required")
	}
	return &FileStorage{
		root:     rootAbs,
		staging:  stagingAbs,
		resolver: resolver,
	}, nil
}

// resolveUserPath maps a PROTOCOL §1 path ("/photos/2026/foo.jpg") to
// an absolute path under root, refusing any result that escapes root
// via traversal. Callers have already run basic validation at the
// handler layer; this is a defense-in-depth check.
func (s *FileStorage) resolveUserPath(userPath string) (string, error) {
	if userPath == "" {
		return "", fmt.Errorf("fs: empty path")
	}
	clean := filepath.Clean("/" + strings.TrimPrefix(userPath, "/"))
	abs := filepath.Join(s.root, clean)
	// On Windows, filepath.Join normalises separators; Rel then
	// checks containment without following symlinks.
	rel, err := filepath.Rel(s.root, abs)
	if err != nil || strings.HasPrefix(rel, "..") || rel == ".." {
		return "", fmt.Errorf("fs: path escapes root: %q", userPath)
	}
	return abs, nil
}

// Put writes r's content to path atomically: stream to a staging tmp,
// fsync, rename into place. Parent directories are created as needed.
func (s *FileStorage) Put(ctx context.Context, path string, r io.Reader) error {
	dst, err := s.resolveUserPath(path)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return fmt.Errorf("fs: mkdir parent: %w", err)
	}

	tmp, err := s.newStagingFile()
	if err != nil {
		return err
	}
	tmpPath := tmp.Name()
	cleanup := func() { _ = os.Remove(tmpPath) }

	if _, err := copyCtx(ctx, tmp, r); err != nil {
		_ = tmp.Close()
		cleanup()
		return fmt.Errorf("fs: write staging: %w", err)
	}
	if err := tmp.Sync(); err != nil {
		_ = tmp.Close()
		cleanup()
		return fmt.Errorf("fs: sync staging: %w", err)
	}
	if err := tmp.Close(); err != nil {
		cleanup()
		return fmt.Errorf("fs: close staging: %w", err)
	}
	if err := os.Rename(tmpPath, dst); err != nil {
		cleanup()
		return fmt.Errorf("fs: rename into root: %w", err)
	}
	return nil
}

func (s *FileStorage) Get(_ context.Context, path string, rangeStart, rangeEnd int64) (io.ReadCloser, error) {
	src, err := s.resolveUserPath(path)
	if err != nil {
		return nil, err
	}
	fh, err := os.Open(src)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil, domainfile.ErrFileNotFound
		}
		return nil, fmt.Errorf("fs: open: %w", err)
	}
	if rangeStart == 0 && rangeEnd < 0 {
		return fh, nil
	}
	info, err := fh.Stat()
	if err != nil {
		_ = fh.Close()
		return nil, fmt.Errorf("fs: stat: %w", err)
	}
	size := info.Size()
	end := rangeEnd
	if end < 0 || end >= size {
		end = size - 1
	}
	if rangeStart < 0 || rangeStart > end {
		_ = fh.Close()
		return nil, fmt.Errorf("fs: range %d-%d out of bounds for size %d", rangeStart, rangeEnd, size)
	}
	if _, err := fh.Seek(rangeStart, io.SeekStart); err != nil {
		_ = fh.Close()
		return nil, fmt.Errorf("fs: seek: %w", err)
	}
	length := end - rangeStart + 1
	return &limitedFile{ReadCloser: fh, Reader: io.LimitReader(fh, length)}, nil
}

// limitedFile couples a LimitReader over an open file with the file's
// Close method, so callers get byte-bounded reads without losing the
// underlying resource handle.
type limitedFile struct {
	io.ReadCloser
	Reader io.Reader
}

func (l *limitedFile) Read(p []byte) (int, error) { return l.Reader.Read(p) }

func (s *FileStorage) Delete(_ context.Context, path string, recursive bool) error {
	target, err := s.resolveUserPath(path)
	if err != nil {
		return err
	}
	info, err := os.Lstat(target)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return domainfile.ErrFileNotFound
		}
		return fmt.Errorf("fs: lstat: %w", err)
	}
	if info.IsDir() && recursive {
		if err := os.RemoveAll(target); err != nil {
			return fmt.Errorf("fs: remove tree: %w", err)
		}
		return nil
	}
	if err := os.Remove(target); err != nil {
		if isNotEmptyErr(err) {
			return domainfile.ErrDirectoryNotEmpty
		}
		return fmt.Errorf("fs: remove: %w", err)
	}
	return nil
}

func (s *FileStorage) Move(_ context.Context, from, to string, overwrite bool) error {
	src, err := s.resolveUserPath(from)
	if err != nil {
		return err
	}
	dst, err := s.resolveUserPath(to)
	if err != nil {
		return err
	}
	if _, err := os.Lstat(src); err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return domainfile.ErrFileNotFound
		}
		return fmt.Errorf("fs: lstat src: %w", err)
	}
	if !overwrite {
		if _, err := os.Lstat(dst); err == nil {
			return domainfile.ErrFileExists
		} else if !errors.Is(err, os.ErrNotExist) {
			return fmt.Errorf("fs: lstat dst: %w", err)
		}
	}
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return fmt.Errorf("fs: mkdir parent: %w", err)
	}
	if err := os.Rename(src, dst); err != nil {
		return fmt.Errorf("fs: rename: %w", err)
	}
	return nil
}

// DeduplicateLink attempts a hardlink from the resolved source to
// targetPath. The target MUST NOT already exist; the caller
// (usecase layer) is responsible for having removed it when
// overwrite semantics permit.
//
// Fallback detection (returned as ErrDedupUnsupported):
//
//   - EXDEV: source and target live on different filesystems/volumes.
//   - EPERM / ENOSYS / ENOTSUP: FS does not support hardlinks.
//   - ERROR_NOT_SAME_DEVICE (Windows syscall 17).
//
// Any other failure (including "target already exists") is returned
// as a wrapped error; the usecase treats that path as unrecoverable.
func (s *FileStorage) DeduplicateLink(ctx context.Context, existingSHA256 []byte, targetPath string) error {
	src, err := s.resolver.ResolvePath(ctx, existingSHA256)
	if err != nil {
		if errors.Is(err, domainfile.ErrFileNotFound) {
			return domainfile.ErrDedupUnsupported
		}
		return fmt.Errorf("fs: resolve dedup source: %w", err)
	}
	dst, err := s.resolveUserPath(targetPath)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return fmt.Errorf("fs: mkdir parent: %w", err)
	}
	if err := os.Link(src, dst); err != nil {
		if isDedupUnsupported(err) {
			return domainfile.ErrDedupUnsupported
		}
		return fmt.Errorf("fs: hardlink: %w", err)
	}
	return nil
}

// SyncCopy performs a bounded full copy from the resolved source to
// targetPath. Ctx cancellation is honored at buffer boundaries via
// copyCtx; no progress is reported upstream.
func (s *FileStorage) SyncCopy(ctx context.Context, existingSHA256 []byte, targetPath string) error {
	src, err := s.resolver.ResolvePath(ctx, existingSHA256)
	if err != nil {
		return fmt.Errorf("fs: resolve sync-copy source: %w", err)
	}
	dst, err := s.resolveUserPath(targetPath)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return fmt.Errorf("fs: mkdir parent: %w", err)
	}
	srcFh, err := os.Open(src)
	if err != nil {
		return fmt.Errorf("fs: open source: %w", err)
	}
	defer srcFh.Close()

	tmp, err := s.newStagingFile()
	if err != nil {
		return err
	}
	tmpPath := tmp.Name()
	cleanup := func() { _ = os.Remove(tmpPath) }

	if _, err := copyCtx(ctx, tmp, srcFh); err != nil {
		_ = tmp.Close()
		cleanup()
		return fmt.Errorf("fs: sync-copy write: %w", err)
	}
	if err := tmp.Sync(); err != nil {
		_ = tmp.Close()
		cleanup()
		return fmt.Errorf("fs: sync-copy fsync: %w", err)
	}
	if err := tmp.Close(); err != nil {
		cleanup()
		return fmt.Errorf("fs: sync-copy close: %w", err)
	}
	if err := os.Rename(tmpPath, dst); err != nil {
		cleanup()
		return fmt.Errorf("fs: sync-copy rename: %w", err)
	}
	return nil
}

func (s *FileStorage) List(_ context.Context, path string) ([]domainfile.DirEntry, error) {
	dir, err := s.resolveUserPath(path)
	if err != nil {
		return nil, err
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil, domainfile.ErrFileNotFound
		}
		return nil, fmt.Errorf("fs: readdir: %w", err)
	}
	out := make([]domainfile.DirEntry, 0, len(entries))
	for _, e := range entries {
		info, err := e.Info()
		if err != nil {
			// Race with concurrent delete — skip the vanished entry.
			if errors.Is(err, os.ErrNotExist) {
				continue
			}
			return nil, fmt.Errorf("fs: info %s: %w", e.Name(), err)
		}
		de := domainfile.DirEntry{
			Name:       e.Name(),
			IsDir:      e.IsDir(),
			ModifiedAt: info.ModTime().Unix(),
		}
		if !e.IsDir() {
			de.Size = info.Size()
			de.MimeType = detectMime(e.Name())
		}
		out = append(out, de)
	}
	sortEntriesByName(out)
	return out, nil
}

func (s *FileStorage) Stat(_ context.Context, path string) (*domainfile.FileMeta, error) {
	abs, err := s.resolveUserPath(path)
	if err != nil {
		return nil, err
	}
	info, err := os.Stat(abs)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil, domainfile.ErrFileNotFound
		}
		return nil, fmt.Errorf("fs: stat: %w", err)
	}
	return &domainfile.FileMeta{
		Path:       path,
		Size:       info.Size(),
		ModifiedAt: info.ModTime().Unix(),
		MimeType:   detectMime(info.Name()),
	}, nil
}

// detectMime is a minimal extension-based lookup. The full IANA set
// plus magic-byte sniffing is deferred; PROTOCOL §6.1 allows either.
func detectMime(name string) string {
	ext := strings.ToLower(filepath.Ext(name))
	switch ext {
	case ".jpg", ".jpeg":
		return "image/jpeg"
	case ".png":
		return "image/png"
	case ".gif":
		return "image/gif"
	case ".webp":
		return "image/webp"
	case ".heic":
		return "image/heic"
	case ".mp4":
		return "video/mp4"
	case ".mov":
		return "video/quicktime"
	case ".txt":
		return "text/plain"
	case ".json":
		return "application/json"
	case ".pdf":
		return "application/pdf"
	}
	return ""
}

func sortEntriesByName(es []domainfile.DirEntry) {
	// Insertion sort is fine — directory cardinality is small in
	// home-server deployments and this keeps imports minimal.
	for i := 1; i < len(es); i++ {
		for j := i; j > 0 && es[j-1].Name > es[j].Name; j-- {
			es[j-1], es[j] = es[j], es[j-1]
		}
	}
}

// newStagingFile creates a uniquely-named file inside the staging dir
// at mode 0600 — we do not use os.CreateTemp because we want the file
// to land on the same FS as root (staging is colocated by config) and
// because CreateTemp returns 0600 on Unix but 0666&umask on Windows;
// explicit OpenFile normalises the behaviour.
func (s *FileStorage) newStagingFile() (*os.File, error) {
	var rnd [12]byte
	if _, err := rand.Read(rnd[:]); err != nil {
		return nil, fmt.Errorf("fs: staging name entropy: %w", err)
	}
	name := filepath.Join(s.staging, "tmp-"+hex.EncodeToString(rnd[:]))
	fh, err := os.OpenFile(name, os.O_CREATE|os.O_WRONLY|os.O_EXCL, 0o600)
	if err != nil {
		return nil, fmt.Errorf("fs: open staging: %w", err)
	}
	return fh, nil
}

// copyCtx is io.Copy with periodic ctx cancellation checks between
// 256 KiB buffers. Returns bytes written and the first error seen.
func copyCtx(ctx context.Context, dst io.Writer, src io.Reader) (int64, error) {
	buf := make([]byte, 256*1024)
	var total int64
	for {
		select {
		case <-ctx.Done():
			return total, ctx.Err()
		default:
		}
		n, readErr := src.Read(buf)
		if n > 0 {
			w, writeErr := dst.Write(buf[:n])
			total += int64(w)
			if writeErr != nil {
				return total, writeErr
			}
			if w != n {
				return total, io.ErrShortWrite
			}
		}
		if readErr == io.EOF {
			return total, nil
		}
		if readErr != nil {
			return total, readErr
		}
	}
}

// isDedupUnsupported recognises link(2) / CreateHardLinkW failure
// modes that the dedup-fallback path should treat as recoverable
// rather than fatal. Target-already-exists is NOT one of them — that
// caller-owned precondition surfaces as a wrapped error instead.
func isDedupUnsupported(err error) bool {
	if errors.Is(err, os.ErrExist) {
		return false
	}
	if errors.Is(err, syscall.EXDEV) || errors.Is(err, syscall.EPERM) {
		return true
	}
	if runtime.GOOS == "windows" {
		// Windows CreateHardLinkW codes we treat as recoverable:
		//   17  ERROR_NOT_SAME_DEVICE (cross-volume)
		//    1  ERROR_INVALID_FUNCTION (FS / file type does not
		//       support hardlinks, e.g. on FAT32 or to a directory)
		//   50  ERROR_NOT_SUPPORTED (reparse target, etc.)
		var errno syscall.Errno
		if errors.As(err, &errno) {
			switch uintptr(errno) {
			case 17, 1, 50:
				return true
			}
		}
	}
	return false
}

// isNotEmptyErr detects the "directory not empty" condition portably.
func isNotEmptyErr(err error) bool {
	if errors.Is(err, syscall.ENOTEMPTY) {
		return true
	}
	// Windows returns ERROR_DIR_NOT_EMPTY (145) via os.PathError.
	var errno syscall.Errno
	if errors.As(err, &errno) && uintptr(errno) == 145 {
		return true
	}
	return false
}
