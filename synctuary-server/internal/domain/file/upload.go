package file

import (
	"context"
	"errors"
)

// Sentinel errors returned by UploadSession. Callers MUST match with
// errors.Is — implementations MAY wrap to add context (e.g. SQL error
// on the underlying INSERT).
var (
	// ErrUploadNotFound is returned when upload_id is unknown or
	// has been GC'd (maps to 404 upload_not_found, §6.3.2 errors).
	ErrUploadNotFound = errors.New("upload_not_found")

	// ErrRangeMismatch is returned when the chunk's start byte
	// does not equal the session's current uploaded_bytes (maps to
	// 409 upload_range_mismatch, §6.3.2).
	ErrRangeMismatch = errors.New("upload_range_mismatch")

	// ErrChunkTooLarge is returned when the chunk exceeds
	// chunk_size_max (maps to 413 payload_too_large, §6.3.2).
	ErrChunkTooLarge = errors.New("upload_chunk_too_large")

	// ErrHashMismatch is returned from the final chunk write when
	// the assembled content's SHA-256 does not match the sha256
	// declared at init (maps to 422 upload_hash_mismatch, §6.3.2).
	// The session MUST be invalidated by the implementation before
	// returning this error.
	ErrHashMismatch = errors.New("upload_hash_mismatch")

	// ErrUploadInProgress is returned by Init when another active,
	// unexpired, non-completed session already exists for the same
	// path. The handler follows up with ActiveByPath to build the
	// 409 upload_in_progress body (§6.3.5).
	ErrUploadInProgress = errors.New("upload_in_progress")

	// ErrFileExists is returned by Init when a distinct file
	// (different sha256) already occupies the target path and
	// overwrite=false (maps to 409 file_exists, §6.3.1).
	ErrFileExists = errors.New("file_exists")

	// ErrDedupUnsupported is returned by FileStorage.DeduplicateLink
	// when the link cannot be materialized for a recoverable
	// reason (cross-device link, FS lacks reflink/hardlink). The
	// usecase layer catches this and falls through (§6.3.1 (a)/(b)).
	// Defined here (not in meta.go) so the whole upload error set
	// lives in one file.
	ErrDedupUnsupported = errors.New("dedup_unsupported")

	// ErrInsufficientStorage is returned when a chunk write fails
	// due to no-space-left (maps to 507 insufficient_storage,
	// §6.3.2).
	ErrInsufficientStorage = errors.New("insufficient_storage")
)

// UploadInitParams captures the init request after handler-layer
// validation. Fields mirror the JSON body in PROTOCOL §6.3.1.
type UploadInitParams struct {
	// Path is the user-facing target path (must pass §1
	// normalization; handler rejects bad paths before reaching us).
	Path string

	// Size is the declared total size in bytes. MUST be ≥ 0.
	Size int64

	// SHA256 is the declared 32-byte content hash used both for
	// dedup lookup and for final-chunk verification.
	SHA256 []byte

	// Overwrite mirrors the request flag; when false, Init MUST
	// return ErrFileExists if a distinct file already occupies
	// Path.
	Overwrite bool

	// DeviceID is the 16-byte id of the authenticated device, used
	// for FK integrity and GC scoping. Injected by the handler from
	// the bearer-auth context, not from the request body.
	DeviceID []byte
}

// UploadInitResult is the successful Init outcome. One of (SessionID
// non-empty, Deduplicated=true) is always set; the two are mutually
// exclusive.
type UploadInitResult struct {
	// SessionID is the opaque upload_id returned to the client
	// (PROTOCOL §6.3.1). Empty when Deduplicated == true.
	SessionID string

	// ChunkSize / ChunkSizeMax are echoed back from server config
	// for advertisement in the 201 response.
	ChunkSize    int64
	ChunkSizeMax int64

	// ExpiresAt is the session expiry (unix seconds), ≥ 24h from
	// creation per §6.3.3. Zero when Deduplicated == true.
	ExpiresAt int64

	// Existing, when non-nil, carries the existing file's metadata
	// for a 409 file_exists response. The usecase sets this
	// alongside returning ErrFileExists so the handler can embed it
	// without a second lookup. Zero otherwise.
	Existing *FileMeta

	// Deduplicated is true when the server materialized a dedup
	// entry synchronously (PROTOCOL §6.3.1 "deduplicated" response).
	// When true, the client MUST NOT attempt chunk uploads.
	Deduplicated bool
}

// ActiveUploadInfo is the 409 upload_in_progress response body
// (PROTOCOL §6.3.5, arch v3 §2.1 [V5]). Note the deliberate absence of
// upload_id — the other session's id MUST NOT leak to an unrelated
// caller.
type ActiveUploadInfo struct {
	CreatedAt     int64 `json:"created_at"`
	UploadedBytes int64 `json:"uploaded_bytes"`
	Size          int64 `json:"size"`
	ExpiresAt     int64 `json:"expires_at"`
}

// UploadSession is the chunked-upload coordinator. Implementations are
// backed by the `uploads` table (single-active-session enforced by
// migration 002's partial unique index) plus the staging area on disk.
//
// All methods MUST be safe for concurrent use across goroutines and
// across processes (if two synctuaryd replicas share the same DB) —
// correctness rests on DB atomicity, not in-process locks.
type UploadSession interface {
	// Init creates a new session or reports a conflict.
	//
	// Success: returns (*UploadInitResult{SessionID: ..., ...}, nil).
	//
	// Error table:
	//   ErrUploadInProgress — another active session exists for the
	//                         same path; handler calls ActiveByPath
	//                         next to fill the 409 body.
	//   ErrFileExists       — params.Overwrite is false and a
	//                         distinct file sits at params.Path.
	//                         result.Existing is populated before
	//                         the error is returned.
	//   other               — unrecoverable (DB / FS fault).
	//
	// The implementation MUST perform the "delete expired rows for
	// this path then INSERT" step atomically (BEGIN IMMEDIATE) to
	// avoid the race described in arch v3 §4.2.
	Init(ctx context.Context, params *UploadInitParams) (*UploadInitResult, error)

	// AppendChunk writes `data` at byte offset `rangeStart`.
	//
	// Contract:
	//   - rangeStart == session.uploaded_bytes on the happy path.
	//   - If the entire [rangeStart, rangeStart+len(data)-1] range
	//     lies strictly within already-accepted bytes, the call is
	//     an idempotent no-op and returns nil (§6.3.2 idempotent
	//     retry). Implementations MUST NOT re-hash or re-write.
	//   - Any range that straddles the uploaded_bytes boundary is
	//     ErrRangeMismatch.
	//   - Exceeding chunk_size_max is ErrChunkTooLarge.
	//   - On the final chunk (end == size-1), the implementation
	//     hashes, compares to the declared sha256, atomically
	//     renames staging → path, marks completed=1. Hash failure
	//     is ErrHashMismatch and the session is invalidated.
	AppendChunk(ctx context.Context, uploadID string, rangeStart int64, data []byte) error

	// Progress returns the resume-poll response fields (§6.3.3).
	// uploadedBytes is the authoritative byte count after any
	// accepted writes; `completed` flips to true only after a
	// successful final-chunk commit.
	Progress(ctx context.Context, uploadID string) (uploadedBytes int64, completed bool, expiresAt int64, err error)

	// Abort cancels the session (§6.3.4): staging file removed,
	// DB row deleted. Idempotent: aborting an unknown or
	// already-aborted id returns ErrUploadNotFound (the handler
	// MAY treat this as 204 still, but domain layer surfaces the
	// truth).
	Abort(ctx context.Context, uploadID string) error

	// ActiveByPath returns the metadata of the current active
	// session for `path`, stripped of upload_id. Invoked only
	// after Init returned ErrUploadInProgress. Returns nil if the
	// previously-observed session has since expired / completed
	// (race window is small but non-zero); the handler should then
	// fall back to generic 500 or advise the client to retry.
	ActiveByPath(ctx context.Context, path string) (*ActiveUploadInfo, error)

	// CollectExpired deletes all sessions whose expires_at ≤ now
	// and removes their staging files. Called on a periodic GC
	// tick by the daemon. Returns the number of sessions removed
	// for telemetry.
	CollectExpired(ctx context.Context, now int64) (int, error)
}
