// Package file models the server's file-tree abstractions — both the
// metadata a caller observes (FileMeta, mirroring PROTOCOL §6.1 list
// entries) and the storage / repository interfaces that usecases depend
// on. Two interfaces live here:
//
//   - FileStorage: physical content plane (Put/Get/Delete/Move +
//     DeduplicateLink). Backed by the local filesystem in v0.4.
//   - Repository: metadata plane (SHA → location lookup). Backed by
//     the `uploads` table's completed rows in v0.4; a dedicated
//     `files` table may arrive in a later version.
//
// Errors used across the upload flow (ErrUploadInProgress,
// ErrDedupUnsupported, …) are defined in upload.go to keep them next
// to the sessions interface that returns them.
package file

import (
	"context"
	"errors"
	"io"
)

// FileMeta is the canonical metadata projection used across layers —
// both the `GET /api/v1/files` listing (§6.1) and the dedup lookup
// (§6.3.1) read from this shape.
type FileMeta struct {
	// Path is relative to the storage root, leading slash, forward
	// separators, NFC-normalized (PROTOCOL §1).
	Path string

	// Size in bytes. Always authoritative from the FS stat, not from
	// any upload declaration.
	Size int64

	// SHA256 is the 32-byte content hash. MAY be empty when the
	// caller has not requested / cached it (see §6.1 `hash=false`).
	SHA256 []byte

	// ModifiedAt is unix epoch seconds of the last content change
	// (the moment the dedup/upload flow performed the atomic rename).
	ModifiedAt int64

	// MimeType is a best-effort IANA media type (§6.1). Empty when
	// undetected; callers MAY fall back to "application/octet-stream".
	MimeType string
}

// ErrFileNotFound is returned by Repository.FindBySHA / FindByPath and
// by FileStorage readers when the requested file does not exist. It is
// distinct from os.ErrNotExist so callers can pattern-match on a
// stable domain error regardless of the backing implementation.
var ErrFileNotFound = errors.New("file_not_found")

// ErrDirectoryNotEmpty is returned by FileStorage.Delete when
// recursive=false and the directory contains entries. Maps to
// 409 directory_not_empty (PROTOCOL §6.4).
var ErrDirectoryNotEmpty = errors.New("directory_not_empty")

// DirEntry is the projection of one row in a PROTOCOL §6.1 listing.
// Exactly one of IsDir=true / IsDir=false holds; when IsDir is true,
// Size / MimeType / SHA256 are zero-valued.
type DirEntry struct {
	Name       string
	IsDir      bool
	Size       int64
	ModifiedAt int64
	MimeType   string
	SHA256     []byte // empty unless caller requested hash=true
}

// Repository is the metadata-plane lookup. Implementations query
// whatever persistent index tracks the (path ↔ sha256) mapping —
// today: completed rows of the `uploads` table.
type Repository interface {
	// FindBySHA returns the most-recently-written file whose content
	// hash matches the given 32-byte digest. Returns (nil,
	// ErrFileNotFound) when no matching row exists — this is a
	// normal "no dedup candidate" outcome, not an error condition.
	//
	// When multiple rows share the same SHA (expected whenever
	// dedup has already happened), the implementation MAY return
	// any of them; callers only need "a" source for reflink.
	FindBySHA(ctx context.Context, sha256 []byte) (*FileMeta, error)

	// FindByPath resolves a user-facing path to its metadata. Used
	// by the 409 file_exists response builder (§6.3.1) so the
	// handler can embed the existing sha256/size/modified_at.
	FindByPath(ctx context.Context, path string) (*FileMeta, error)

	// Upsert records a (path, sha256, size) tuple that was
	// materialized by a means OTHER than the chunked-upload pipeline
	// — currently the dedup hardlink and sync-copy branches in
	// FileService.InitUpload. Without this, FindBySHA / FindByPath
	// would forever return ErrFileNotFound for the new path even
	// though the file is on disk, which surfaces to clients as
	// `sha256: null` in /api/v1/files listings (§6.1).
	//
	// `deviceID` is the 16-byte id of the device that triggered the
	// dedup; it satisfies the FK on the underlying uploads-table
	// row and feeds device-scoped GC.
	//
	// Implementations MUST NOT collide with the active-session
	// uniqueness constraint (migration 002) — completed rows are
	// exempt, but the implementation must mark the synthetic row
	// completed=1 from the start.
	Upsert(ctx context.Context, meta *FileMeta, deviceID []byte) error
}

// FileStorage is the content plane. All paths are user-facing paths
// (the same namespace as FileMeta.Path); the implementation is
// responsible for mapping them to its internal layout.
//
// Implementations MUST be safe for concurrent use; callers hold no
// locks of their own.
type FileStorage interface {
	// Put streams r into `path`, creating or overwriting atomically
	// (write-to-staging + rename). Parent directories are created
	// as needed. Returns ErrFileNotFound only if the operation
	// requires — and cannot create — a missing intermediate; other
	// failures (disk full, permission) surface as wrapped errors.
	Put(ctx context.Context, path string, r io.Reader) error

	// Get opens `path` for reading between [rangeStart, rangeEnd]
	// inclusive (RFC 7233 semantics, matching PROTOCOL §6.2). Pass
	// rangeEnd = -1 for "to end of file"; pass rangeStart = 0 and
	// rangeEnd = -1 for a full read. The returned ReadCloser MUST
	// be closed by the caller.
	Get(ctx context.Context, path string, rangeStart, rangeEnd int64) (io.ReadCloser, error)

	// Delete removes `path`. When `recursive` is true and path is a
	// directory, the entire subtree is removed; when false and the
	// directory is non-empty, implementations MUST return a
	// distinguishable error that the handler maps to 409
	// directory_not_empty (§6.4).
	Delete(ctx context.Context, path string, recursive bool) error

	// Move renames `from` to `to`. When `overwrite` is false and
	// `to` already exists, implementations MUST return a
	// distinguishable error mapped to 409 file_exists by the
	// handler (§6.5).
	Move(ctx context.Context, from, to string, overwrite bool) error

	// DeduplicateLink atomically materializes an entry at
	// `targetPath` that references the same content as the file
	// currently holding `existingSHA256`. Preferred implementation
	// order (§6.3.1): reflink (CoW clone) → hardlink → fall back.
	//
	// Return values (PROTOCOL §6.3.1 + arch v3 §2.2):
	//
	//   nil                 — dedup entry materialized; caller
	//                         returns "deduplicated" to the client.
	//
	//   ErrDedupUnsupported — recoverable (cross-device link,
	//                         FS lacks reflink/hardlink). Caller
	//                         falls through to normal upload
	//                         (§6.3.1 (a)) or synchronous copy
	//                         (§6.3.1 (b)) per config.
	//
	//   other error         — unrecoverable; caller propagates as
	//                         500 after logging.
	//
	// Implementations MUST NOT return a generic "not supported"
	// error that masks a genuine failure (missing parent, corrupt
	// source) as ErrDedupUnsupported — the fallthrough path would
	// then silently paper over real bugs.
	DeduplicateLink(ctx context.Context, existingSHA256 []byte, targetPath string) error

	// SyncCopy performs a bounded synchronous full copy from an
	// existing file identified by sha256 to targetPath. Invoked by
	// the usecase only when config.Upload.DedupFallback ==
	// "sync_copy" after DeduplicateLink returns ErrDedupUnsupported
	// (§6.3.1 (b)). The caller is responsible for applying the
	// timeout via ctx; implementations MUST honor ctx.Done().
	SyncCopy(ctx context.Context, existingSHA256 []byte, targetPath string) error

	// List returns the entries of a directory in Unicode code-point
	// order of Name (PROTOCOL §6.1). Returns ErrFileNotFound if
	// `path` does not exist or is not a directory. MIME type
	// detection is best-effort and MAY be empty. Callers layer
	// SHA256 population on top when hash=true (expensive; not done
	// here).
	List(ctx context.Context, path string) ([]DirEntry, error)

	// Stat returns metadata for a single path. Returns
	// ErrFileNotFound when the path does not exist. Used both by
	// the content handler (for Content-Length) and by file-exists
	// conflict responses.
	Stat(ctx context.Context, path string) (*FileMeta, error)
}
