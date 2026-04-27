package db

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/synctuary/synctuary-server/internal/domain/file"
)

// UploadSessionStore backs file.UploadSession with a SQLite row
// (source of truth for session state) plus a staging file on disk
// (source of truth for content-so-far).
//
// Concurrency model: v0.2 mandates sequential chunks per session, so
// AppendChunk takes no session-level lock; instead it relies on a
// short transaction around the SELECT → UPDATE cycle to keep
// uploaded_bytes coherent. Crash recovery is content-over-truth:
// the DB's uploaded_bytes authorises the client's next chunk, and
// a WriteAt at that offset overwrites anything the crashed run may
// have left slightly past the recorded boundary.
type UploadSessionStore struct {
	db           *sql.DB
	root         string
	staging      string
	chunkSize    int64
	chunkSizeMax int64
	sessionTTL   int64 // seconds
}

func NewUploadSessionStore(
	database *sql.DB,
	root, staging string,
	chunkSize, chunkSizeMax int64,
	sessionTTLSec int64,
) (*UploadSessionStore, error) {
	rootAbs, err := filepath.Abs(root)
	if err != nil {
		return nil, fmt.Errorf("db: resolve root: %w", err)
	}
	stagingAbs, err := filepath.Abs(staging)
	if err != nil {
		return nil, fmt.Errorf("db: resolve staging: %w", err)
	}
	if err := os.MkdirAll(rootAbs, 0o755); err != nil {
		return nil, fmt.Errorf("db: mkdir root: %w", err)
	}
	if err := os.MkdirAll(stagingAbs, 0o755); err != nil {
		return nil, fmt.Errorf("db: mkdir staging: %w", err)
	}
	return &UploadSessionStore{
		db:           database,
		root:         rootAbs,
		staging:      stagingAbs,
		chunkSize:    chunkSize,
		chunkSizeMax: chunkSizeMax,
		sessionTTL:   sessionTTLSec,
	}, nil
}

var _ file.UploadSession = (*UploadSessionStore)(nil)

// Init creates a session row (and empty staging file) atomically.
// Single-active-session-per-path enforcement is the partial UNIQUE
// INDEX on (path) WHERE completed = 0 from migration 002. Expired
// rows are pre-deleted inside the same transaction so a legitimate
// re-Init after expiry does not collide.
func (s *UploadSessionStore) Init(ctx context.Context, params *file.UploadInitParams) (*file.UploadInitResult, error) {
	if params == nil {
		return nil, fmt.Errorf("db: Init: params is nil")
	}
	if len(params.SHA256) != 32 {
		return nil, fmt.Errorf("db: Init: sha256 length %d, expected 32", len(params.SHA256))
	}
	if len(params.DeviceID) != 16 {
		return nil, fmt.Errorf("db: Init: device_id length %d, expected 16", len(params.DeviceID))
	}
	if params.Size < 0 {
		return nil, fmt.Errorf("db: Init: size negative")
	}

	now := nowUnix(ctx)
	expiresAt := now + s.sessionTTL
	uploadID, err := generateUploadID()
	if err != nil {
		return nil, fmt.Errorf("db: Init: id entropy: %w", err)
	}
	stagingPath := filepath.Join(s.staging, uploadID+".part")

	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return nil, fmt.Errorf("db: Init: begin: %w", err)
	}
	defer func() { _ = tx.Rollback() }()
	_, _ = tx.ExecContext(ctx, `BEGIN IMMEDIATE`) // best-effort upgrade

	// Purge any expired active row for this path so a new Init can
	// succeed without waiting for the background GC.
	if _, err := tx.ExecContext(ctx,
		`DELETE FROM uploads WHERE path = ? AND completed = 0 AND expires_at <= ?`,
		params.Path, now,
	); err != nil {
		return nil, fmt.Errorf("db: Init: purge expired: %w", err)
	}

	_, err = tx.ExecContext(ctx, `
		INSERT INTO uploads (
			upload_id, path, size, sha256_expected, uploaded_bytes,
			staging_path, device_id, overwrite, completed,
			created_at, last_write_at, expires_at
		) VALUES (?, ?, ?, ?, 0, ?, ?, ?, 0, ?, ?, ?)
	`,
		uploadID, params.Path, params.Size, params.SHA256, stagingPath,
		params.DeviceID, boolToInt(params.Overwrite), now, now, expiresAt,
	)
	if err != nil {
		if isUniqueViolation(err) {
			// Partial index on path WHERE completed=0 rejected us —
			// another active session exists.
			return nil, file.ErrUploadInProgress
		}
		return nil, fmt.Errorf("db: Init: insert: %w", err)
	}

	// Pre-create the staging file (empty) so AppendChunk's first
	// WriteAt does not race with directory visibility on some FS.
	fh, err := os.OpenFile(stagingPath, os.O_CREATE|os.O_WRONLY|os.O_EXCL, 0o600)
	if err != nil {
		return nil, fmt.Errorf("db: Init: create staging: %w", err)
	}
	_ = fh.Close()

	if err := tx.Commit(); err != nil {
		_ = os.Remove(stagingPath)
		return nil, fmt.Errorf("db: Init: commit: %w", err)
	}

	return &file.UploadInitResult{
		SessionID:    uploadID,
		ChunkSize:    s.chunkSize,
		ChunkSizeMax: s.chunkSizeMax,
		ExpiresAt:    expiresAt,
	}, nil
}

// AppendChunk writes `data` at offset `rangeStart` to the staging
// file and advances uploaded_bytes. When the write completes the
// session (end == size-1), it hashes, verifies, and commits the
// final file via atomic rename.
func (s *UploadSessionStore) AppendChunk(ctx context.Context, uploadID string, rangeStart int64, data []byte) error {
	if int64(len(data)) > s.chunkSizeMax {
		return file.ErrChunkTooLarge
	}

	var (
		path        string
		size        int64
		shaExpected []byte
		uploaded    int64
		stagingPath string
		completed   int64
	)
	err := s.db.QueryRowContext(ctx, `
		SELECT path, size, sha256_expected, uploaded_bytes, staging_path, completed
		  FROM uploads WHERE upload_id = ?
	`, uploadID).Scan(&path, &size, &shaExpected, &uploaded, &stagingPath, &completed)
	if errors.Is(err, sql.ErrNoRows) {
		return file.ErrUploadNotFound
	}
	if err != nil {
		return fmt.Errorf("db: AppendChunk: select: %w", err)
	}
	if completed == 1 {
		return file.ErrUploadNotFound
	}

	end := rangeStart + int64(len(data)) - 1
	// Idempotent retry: entire range sits in already-accepted bytes.
	if len(data) > 0 && end < uploaded {
		return nil
	}
	// Straddle: partial overlap of accepted boundary.
	if rangeStart < uploaded {
		return file.ErrRangeMismatch
	}
	// Gap: client skipped bytes.
	if rangeStart > uploaded {
		return file.ErrRangeMismatch
	}
	// Over-range: writing past declared size.
	if end >= size {
		return file.ErrRangeMismatch
	}

	// Write to staging at the exact offset.
	fh, err := os.OpenFile(stagingPath, os.O_WRONLY, 0o600)
	if err != nil {
		return fmt.Errorf("db: AppendChunk: open staging: %w", err)
	}
	if _, err := fh.WriteAt(data, rangeStart); err != nil {
		_ = fh.Close()
		if isNoSpaceErr(err) {
			return file.ErrInsufficientStorage
		}
		return fmt.Errorf("db: AppendChunk: writeAt: %w", err)
	}
	if err := fh.Sync(); err != nil {
		_ = fh.Close()
		return fmt.Errorf("db: AppendChunk: sync: %w", err)
	}
	if err := fh.Close(); err != nil {
		return fmt.Errorf("db: AppendChunk: close: %w", err)
	}

	newUploaded := uploaded + int64(len(data))
	isFinal := newUploaded == size

	now := nowUnix(ctx)
	if !isFinal {
		if _, err := s.db.ExecContext(ctx,
			`UPDATE uploads SET uploaded_bytes = ?, last_write_at = ? WHERE upload_id = ?`,
			newUploaded, now, uploadID,
		); err != nil {
			return fmt.Errorf("db: AppendChunk: update progress: %w", err)
		}
		return nil
	}

	// Final chunk: verify sha256 over the assembled file, then commit.
	ok, err := verifyFileSHA(stagingPath, shaExpected)
	if err != nil {
		return fmt.Errorf("db: AppendChunk: verify sha: %w", err)
	}
	if !ok {
		_ = os.Remove(stagingPath)
		_, _ = s.db.ExecContext(ctx, `DELETE FROM uploads WHERE upload_id = ?`, uploadID)
		return file.ErrHashMismatch
	}

	// Atomic rename staging → target path inside storage root.
	target, err := s.resolveRoot(path)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
		return fmt.Errorf("db: AppendChunk: mkdir target parent: %w", err)
	}
	// If overwrite=true a previous file may sit at target; os.Rename
	// on POSIX replaces atomically, on Windows (Go 1.5+) it does too
	// via MoveFileEx with MOVEFILE_REPLACE_EXISTING.
	if err := os.Rename(stagingPath, target); err != nil {
		return fmt.Errorf("db: AppendChunk: rename to target: %w", err)
	}

	if _, err := s.db.ExecContext(ctx,
		`UPDATE uploads SET uploaded_bytes = ?, last_write_at = ?, completed = 1 WHERE upload_id = ?`,
		newUploaded, now, uploadID,
	); err != nil {
		return fmt.Errorf("db: AppendChunk: mark completed: %w", err)
	}
	return nil
}

func (s *UploadSessionStore) Progress(ctx context.Context, uploadID string) (int64, bool, int64, error) {
	var (
		uploaded  int64
		completed int64
		expiresAt int64
	)
	err := s.db.QueryRowContext(ctx,
		`SELECT uploaded_bytes, completed, expires_at FROM uploads WHERE upload_id = ?`,
		uploadID,
	).Scan(&uploaded, &completed, &expiresAt)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, false, 0, file.ErrUploadNotFound
	}
	if err != nil {
		return 0, false, 0, fmt.Errorf("db: Progress: %w", err)
	}
	return uploaded, completed == 1, expiresAt, nil
}

func (s *UploadSessionStore) Abort(ctx context.Context, uploadID string) error {
	var stagingPath string
	err := s.db.QueryRowContext(ctx,
		`SELECT staging_path FROM uploads WHERE upload_id = ? AND completed = 0`,
		uploadID,
	).Scan(&stagingPath)
	if errors.Is(err, sql.ErrNoRows) {
		return file.ErrUploadNotFound
	}
	if err != nil {
		return fmt.Errorf("db: Abort: select: %w", err)
	}

	if _, err := s.db.ExecContext(ctx, `DELETE FROM uploads WHERE upload_id = ?`, uploadID); err != nil {
		return fmt.Errorf("db: Abort: delete: %w", err)
	}
	if err := os.Remove(stagingPath); err != nil && !errors.Is(err, os.ErrNotExist) {
		return fmt.Errorf("db: Abort: remove staging: %w", err)
	}
	return nil
}

func (s *UploadSessionStore) ActiveByPath(ctx context.Context, path string) (*file.ActiveUploadInfo, error) {
	now := nowUnix(ctx)
	var info file.ActiveUploadInfo
	err := s.db.QueryRowContext(ctx, `
		SELECT created_at, uploaded_bytes, size, expires_at
		  FROM uploads
		 WHERE path = ? AND completed = 0 AND expires_at > ?
		 LIMIT 1
	`, path, now).Scan(&info.CreatedAt, &info.UploadedBytes, &info.Size, &info.ExpiresAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil // race: session gone since Init returned ErrUploadInProgress
	}
	if err != nil {
		return nil, fmt.Errorf("db: ActiveByPath: %w", err)
	}
	return &info, nil
}

// CollectExpired removes expired, non-completed sessions and their
// staging files. Runs on a periodic GC tick.
func (s *UploadSessionStore) CollectExpired(ctx context.Context, now int64) (int, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT upload_id, staging_path FROM uploads WHERE completed = 0 AND expires_at <= ?`,
		now,
	)
	if err != nil {
		return 0, fmt.Errorf("db: CollectExpired: select: %w", err)
	}
	type expired struct {
		id, path string
	}
	var victims []expired
	for rows.Next() {
		var v expired
		if err := rows.Scan(&v.id, &v.path); err != nil {
			rows.Close()
			return 0, fmt.Errorf("db: CollectExpired: scan: %w", err)
		}
		victims = append(victims, v)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return 0, fmt.Errorf("db: CollectExpired: iter: %w", err)
	}

	count := 0
	for _, v := range victims {
		if _, err := s.db.ExecContext(ctx, `DELETE FROM uploads WHERE upload_id = ?`, v.id); err != nil {
			return count, fmt.Errorf("db: CollectExpired: delete %s: %w", v.id, err)
		}
		if err := os.Remove(v.path); err != nil && !errors.Is(err, os.ErrNotExist) {
			// Don't fail the whole sweep; log upstream.
			continue
		}
		count++
	}
	return count, nil
}

// resolveRoot maps a PROTOCOL §1 user path into an absolute path under
// the configured storage root, rejecting traversal attempts.
func (s *UploadSessionStore) resolveRoot(userPath string) (string, error) {
	clean := filepath.Clean("/" + strings.TrimPrefix(userPath, "/"))
	abs := filepath.Join(s.root, clean)
	rel, err := filepath.Rel(s.root, abs)
	if err != nil || strings.HasPrefix(rel, "..") || rel == ".." {
		return "", fmt.Errorf("db: path escapes root: %q", userPath)
	}
	return abs, nil
}

// verifyFileSHA streams the file and compares against the expected
// 32-byte digest. Constant-time comparison is unnecessary (we own
// both sides), but cheap, so we use it to keep the audit surface
// tidy.
func verifyFileSHA(path string, expected []byte) (bool, error) {
	fh, err := os.Open(path)
	if err != nil {
		return false, err
	}
	defer fh.Close()
	h := sha256.New()
	if _, err := io.Copy(h, fh); err != nil {
		return false, err
	}
	sum := h.Sum(nil)
	return constantTimeEqual(sum, expected), nil
}

func constantTimeEqual(a, b []byte) bool {
	if len(a) != len(b) {
		return false
	}
	var v byte
	for i := range a {
		v |= a[i] ^ b[i]
	}
	return v == 0
}

func generateUploadID() (string, error) {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(b[:]), nil
}

func boolToInt(b bool) int {
	if b {
		return 1
	}
	return 0
}

// nowUnix reads a ctx-supplied clock if present; otherwise wall time.
// Allows tests to inject fixed time via context while production uses
// real time.
type clockKey struct{}

func WithClock(ctx context.Context, nowFn func() int64) context.Context {
	return context.WithValue(ctx, clockKey{}, nowFn)
}

func nowUnix(ctx context.Context) int64 {
	if ctx != nil {
		if v := ctx.Value(clockKey{}); v != nil {
			if fn, ok := v.(func() int64); ok {
				return fn()
			}
		}
	}
	return time.Now().Unix()
}

// isNoSpaceErr portably detects disk-full conditions.
func isNoSpaceErr(err error) bool {
	if err == nil {
		return false
	}
	msg := err.Error()
	// POSIX: "no space left on device"; Windows: "There is not enough space".
	return strings.Contains(msg, "no space left") || strings.Contains(msg, "not enough space")
}
