package usecase

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"time"

	"github.com/synctuary/synctuary-server/internal/domain/file"
)

// FileService composes the repository (metadata index), the storage
// (content plane) and the upload session coordinator into the flow
// behind /api/v1/files*. The dedup decision tree lives here — see
// arch v3 §5 for the 3-branch logic.
type FileService struct {
	repo          file.Repository
	storage       file.FileStorage
	uploads       file.UploadSession
	dedupFallback string
	dedupTimeout  time.Duration
}

func NewFileService(
	repo file.Repository,
	storage file.FileStorage,
	uploads file.UploadSession,
	dedupFallback string,
	dedupTimeout time.Duration,
) (*FileService, error) {
	if repo == nil || storage == nil || uploads == nil {
		return nil, fmt.Errorf("file_service: missing dependency")
	}
	switch dedupFallback {
	case "fallthrough", "sync_copy":
	default:
		return nil, fmt.Errorf("file_service: unknown dedup_fallback %q", dedupFallback)
	}
	if dedupTimeout <= 0 {
		dedupTimeout = 30 * time.Second
	}
	return &FileService{
		repo:          repo,
		storage:       storage,
		uploads:       uploads,
		dedupFallback: dedupFallback,
		dedupTimeout:  dedupTimeout,
	}, nil
}

// InitUpload implements PROTOCOL §6.3.1:
//
//  1. If a distinct file already sits at params.Path and overwrite
//     is false → ErrFileExists (with result.Existing populated).
//  2. If the server already has content matching params.SHA256, try
//     DeduplicateLink; fall through to normal upload or sync-copy
//     per config when the link is unsupported.
//  3. Otherwise start a normal chunked upload session.
func (s *FileService) InitUpload(ctx context.Context, params *file.UploadInitParams) (*file.UploadInitResult, error) {
	// Step 1: file_exists precondition.
	existing, err := s.storage.Stat(ctx, params.Path)
	switch {
	case err == nil:
		if !params.Overwrite {
			// Populate SHA so handler can emit §6.3.1 409 body.
			if cached, cerr := s.repo.FindByPath(ctx, params.Path); cerr == nil && cached != nil {
				existing.SHA256 = cached.SHA256
			}
			return &file.UploadInitResult{Existing: existing}, file.ErrFileExists
		}
	case errors.Is(err, file.ErrFileNotFound):
		// good, no conflict
	default:
		return nil, fmt.Errorf("file_service: stat target: %w", err)
	}

	// Step 2: dedup attempt.
	match, err := s.repo.FindBySHA(ctx, params.SHA256)
	switch {
	case err == nil && match != nil:
		// Avoid linking a file onto itself (overwrite of the same
		// SHA at the same path is a no-op).
		if match.Path == params.Path && bytes.Equal(match.SHA256, params.SHA256) {
			return &file.UploadInitResult{Deduplicated: true}, nil
		}
		linkErr := s.storage.DeduplicateLink(ctx, params.SHA256, params.Path)
		switch {
		case linkErr == nil:
			s.recordDedupedFile(ctx, params)
			return &file.UploadInitResult{Deduplicated: true}, nil
		case errors.Is(linkErr, file.ErrDedupUnsupported):
			if s.dedupFallback == "sync_copy" {
				cctx, cancel := context.WithTimeout(ctx, s.dedupTimeout)
				defer cancel()
				if copyErr := s.storage.SyncCopy(cctx, params.SHA256, params.Path); copyErr == nil {
					s.recordDedupedFile(ctx, params)
					return &file.UploadInitResult{Deduplicated: true}, nil
				}
				// Timeout / copy failure: fall through to normal upload.
			}
			// Continue to normal upload.
		default:
			return nil, fmt.Errorf("file_service: dedup link: %w", linkErr)
		}
	case err != nil && !errors.Is(err, file.ErrFileNotFound):
		return nil, fmt.Errorf("file_service: find by sha: %w", err)
	}

	// Step 3: normal upload session.
	return s.uploads.Init(ctx, params)
}

func (s *FileService) AppendChunk(ctx context.Context, uploadID string, rangeStart int64, data []byte) error {
	return s.uploads.AppendChunk(ctx, uploadID, rangeStart, data)
}

func (s *FileService) Progress(ctx context.Context, uploadID string) (int64, bool, int64, error) {
	return s.uploads.Progress(ctx, uploadID)
}

func (s *FileService) Abort(ctx context.Context, uploadID string) error {
	return s.uploads.Abort(ctx, uploadID)
}

func (s *FileService) ActiveByPath(ctx context.Context, path string) (*file.ActiveUploadInfo, error) {
	return s.uploads.ActiveByPath(ctx, path)
}

// ListResult wraps the §6.1 response for the handler.
type ListResult struct {
	Path    string
	Entries []file.DirEntry
}

func (s *FileService) List(ctx context.Context, path string, withHash bool) (*ListResult, error) {
	entries, err := s.storage.List(ctx, path)
	if err != nil {
		return nil, err
	}
	if withHash {
		// Best-effort population from the uploads index; files we
		// have no record for are left without a hash. Per §6.1
		// servers MAY compute on demand — deferred until v0.4.1.
		for i, e := range entries {
			if e.IsDir {
				continue
			}
			full := joinPath(path, e.Name)
			meta, err := s.repo.FindByPath(ctx, full)
			if err == nil && meta != nil {
				entries[i].SHA256 = meta.SHA256
			}
		}
	}
	return &ListResult{Path: path, Entries: entries}, nil
}

func (s *FileService) Read(ctx context.Context, path string, rangeStart, rangeEnd int64) (io.ReadCloser, *file.FileMeta, error) {
	meta, err := s.storage.Stat(ctx, path)
	if err != nil {
		return nil, nil, err
	}
	rc, err := s.storage.Get(ctx, path, rangeStart, rangeEnd)
	if err != nil {
		return nil, nil, err
	}
	return rc, meta, nil
}

func (s *FileService) Delete(ctx context.Context, path string, recursive bool) error {
	return s.storage.Delete(ctx, path, recursive)
}

func (s *FileService) Move(ctx context.Context, from, to string, overwrite bool) error {
	return s.storage.Move(ctx, from, to, overwrite)
}

func (s *FileService) Stat(ctx context.Context, path string) (*file.FileMeta, error) {
	return s.storage.Stat(ctx, path)
}

// recordDedupedFile updates the metadata index after the storage
// layer materialized a dedup entry (hardlink or sync-copy). Without
// this, FindByPath / FindBySHA never see the new path and downstream
// /api/v1/files listings would emit `sha256: null` for the deduped
// file even though the bytes are correct on disk.
//
// Failure here is logged-and-swallowed, not propagated: the dedup
// itself succeeded — refusing the request because of an index update
// hiccup would leave the on-disk file orphaned and confuse the
// client. Worst case the metadata catches up next time the file is
// touched (overwrite, move, etc.).
func (s *FileService) recordDedupedFile(ctx context.Context, params *file.UploadInitParams) {
	meta := &file.FileMeta{
		Path:       params.Path,
		Size:       params.Size,
		SHA256:     params.SHA256,
		ModifiedAt: time.Now().Unix(),
	}
	if err := s.repo.Upsert(ctx, meta, params.DeviceID); err != nil {
		// Best-effort: dedup already succeeded on disk. We just
		// won't show sha256 in the next listing until the index
		// catches up.
		_ = err
	}
}

// joinPath glues a directory path and a leaf name in PROTOCOL §1
// forward-slash form, avoiding path/filepath's OS-specific separator.
func joinPath(dir, name string) string {
	if dir == "" || dir == "/" {
		return "/" + name
	}
	if dir[len(dir)-1] == '/' {
		return dir + name
	}
	return dir + "/" + name
}
