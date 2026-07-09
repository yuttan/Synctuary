package usecase

import (
	"archive/zip"
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path"
	"path/filepath"
	"sort"
	"strings"

	"github.com/bodgit/sevenzip"
	"github.com/nwaples/rardecode/v2"

	dfile "github.com/synctuary/synctuary-server/internal/domain/file"
)

// ErrArchiveUnsupported is returned when the target path is not a
// recognized archive by its extension. The handler maps it to
// 400 unsupported_type.
var ErrArchiveUnsupported = errors.New("archive: unsupported type")

// ErrArchiveUnreadable is returned when an archive cannot be opened or
// read — corrupt data, an unsupported compression method, or a
// password-protected archive/entry (we do not prompt for passwords).
// The handler maps it to 400 archive_unreadable.
var ErrArchiveUnreadable = errors.New("archive: unreadable (corrupt or password-protected)")

// ErrArchiveTooLarge is returned by List when an archive declares more
// than maxArchiveEntries members. It protects the client from an
// unbounded listing (a zip bomb of tiny entries). Maps to 400
// archive_too_large.
var ErrArchiveTooLarge = errors.New("archive: too many entries")

// ErrArchiveEntryNotFound is returned by Open when the requested inner
// entry does not exist (or resolves to a directory). Maps to 404
// entry_not_found.
var ErrArchiveEntryNotFound = errors.New("archive: entry not found")

// maxArchiveEntries caps how many members List will enumerate. Beyond
// this the archive is rejected rather than streamed to the client — a
// defensive bound against archives with pathologically many members.
const maxArchiveEntries = 10_000

// ArchiveEntry is one member of an archive listing. Path is the
// archive-internal path, always forward-slash separated and cleaned
// (no leading slash, no `.`/`..` components after normalization).
type ArchiveEntry struct {
	Path string
	Size int64
	Dir  bool
}

// ArchiveService browses, streams, and extracts archive files (zip,
// rar, 7z) that live in the user's file tree. It never persists an
// intermediate copy for browsing/streaming (the comic-reader use case
// pages through images without extracting); Extract is the only
// operation that materializes files on disk.
//
// Like TranscodeService, it holds a FileStorage that callers scope
// per-request via WithStorage to honor the ?share= query parameter.
// The service resolves user paths to absolute paths through the storage
// (Resolve) and then operates on them directly with the archive
// libraries — mirroring how the transcode/thumbnail services hand
// absolute paths to ffmpeg.
type ArchiveService struct {
	storage dfile.FileStorage
	log     *slog.Logger
}

// NewArchiveService constructs an ArchiveService. storage may be nil at
// construction; callers scope it per-request via WithStorage. log
// defaults to slog.Default().
func NewArchiveService(storage dfile.FileStorage, log *slog.Logger) *ArchiveService {
	if log == nil {
		log = slog.Default()
	}
	return &ArchiveService{storage: storage, log: log}
}

// WithStorage returns a copy of this service scoped to a different
// FileStorage (e.g. a share's HostPath root).
func (s *ArchiveService) WithStorage(storage dfile.FileStorage) *ArchiveService {
	return &ArchiveService{storage: storage, log: s.log}
}

// archiveFormat maps a filename to its archive format by extension, or
// "" when the name is not a recognized archive. Comic-book variants
// (.cbz / .cbr) are ZIP / RAR containers respectively.
func archiveFormat(name string) string {
	switch strings.ToLower(filepath.Ext(name)) {
	case ".zip", ".cbz":
		return "zip"
	case ".rar", ".cbr":
		return "rar"
	case ".7z":
		return "7z"
	default:
		return ""
	}
}

// IsArchivePath reports whether name is a browsable archive by
// extension. Kept in sync with the MIME map in
// internal/adapter/infrastructure/fs/file_storage.go (the Android
// client gates the archive UI on those MIME types).
func IsArchivePath(name string) bool { return archiveFormat(name) != "" }

// List returns a flat listing of every member of the archive at the
// user path p. Entry paths are archive-internal and normalized
// (forward-slash, cleaned, no leading slash). Directory entries are
// included with Dir=true. Returns ErrArchiveUnsupported when p is not
// an archive, ErrArchiveTooLarge past the entry cap, ErrArchiveUnreadable
// for corrupt/encrypted archives, and dfile.ErrFileNotFound when p does
// not exist.
func (s *ArchiveService) List(ctx context.Context, p string) ([]ArchiveEntry, error) {
	abs, format, err := s.resolveArchive(ctx, p)
	if err != nil {
		return nil, err
	}
	var entries []ArchiveEntry
	switch format {
	case "zip":
		entries, err = listZip(abs)
	case "rar":
		entries, err = listRar(abs)
	case "7z":
		entries, err = list7z(abs)
	}
	if err != nil {
		return nil, err
	}
	sortArchiveEntries(entries)
	return entries, nil
}

// Open streams a single entry out of the archive at user path p. The
// returned size is the entry's uncompressed size, or -1 when the format
// does not report it. The caller MUST Close the returned reader.
//
// zip uses random access via the central directory. rar and 7z scan
// sequentially from the start of the archive to the requested entry;
// for the comic-reader use case (many small images) this is acceptable,
// and zip is by far the common container.
func (s *ArchiveService) Open(ctx context.Context, p, entry string) (io.ReadCloser, int64, error) {
	abs, format, err := s.resolveArchive(ctx, p)
	if err != nil {
		return nil, -1, err
	}
	target := normalizeEntryName(entry)
	if target == "" {
		return nil, -1, ErrArchiveEntryNotFound
	}
	switch format {
	case "zip":
		return openZip(abs, target)
	case "rar":
		return openRar(abs, target)
	case "7z":
		return open7z(abs, target)
	}
	return nil, -1, ErrArchiveUnsupported
}

// Extract expands ALL entries of the archive at user path p into a
// sibling directory named after the archive stem (`/foo/bar.zip` →
// `/foo/bar/`). When that directory already exists, a numeric suffix is
// appended (` (2)`, ` (3)`, …). Returns the created directory's
// user-facing path.
//
// Security: every entry is validated to resolve strictly inside the
// destination (Zip-Slip / directory-traversal protection). Entries with
// absolute paths, drive letters, or `..` components that would escape
// the destination are skipped, never written outside it.
func (s *ArchiveService) Extract(ctx context.Context, p string) (string, error) {
	absArchive, format, err := s.resolveArchive(ctx, p)
	if err != nil {
		return "", err
	}

	// Destination: sibling directory named after the archive stem. User
	// paths are forward-slash with a leading slash, so use path.* (not
	// filepath.*) to derive the sibling path.
	dir := path.Dir(p)
	base := path.Base(p)
	stem := strings.TrimSuffix(base, filepath.Ext(base))
	if stem == "" {
		stem = "extracted"
	}

	destUser, destAbs, err := s.uniqueDest(ctx, dir, stem)
	if err != nil {
		return "", err
	}
	if err := os.MkdirAll(destAbs, 0o755); err != nil {
		return "", fmt.Errorf("archive: mkdir dest: %w", err)
	}

	switch format {
	case "zip":
		err = extractZip(absArchive, destAbs, s.log)
	case "rar":
		err = extractRar(absArchive, destAbs, s.log)
	case "7z":
		err = extract7z(absArchive, destAbs, s.log)
	}
	if err != nil {
		return "", err
	}
	return destUser, nil
}

// resolveArchive validates p as an archive, resolves it to an absolute
// path via the scoped storage, and confirms it exists on disk.
func (s *ArchiveService) resolveArchive(ctx context.Context, p string) (abs, format string, err error) {
	format = archiveFormat(p)
	if format == "" {
		return "", "", ErrArchiveUnsupported
	}
	if s.storage == nil {
		return "", "", fmt.Errorf("archive: no storage configured")
	}
	abs, err = s.storage.Resolve(ctx, p)
	if err != nil {
		return "", "", err
	}
	info, err := os.Stat(abs)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return "", "", dfile.ErrFileNotFound
		}
		return "", "", fmt.Errorf("archive: stat: %w", err)
	}
	if info.IsDir() {
		return "", "", dfile.ErrFileNotFound
	}
	return abs, format, nil
}

// uniqueDest finds a non-colliding destination directory. It returns
// both the user-facing path and the resolved absolute path. Collisions
// are disambiguated with a ` (N)` suffix, matching the archive-extract
// convention used by common desktop tools.
func (s *ArchiveService) uniqueDest(ctx context.Context, dir, stem string) (userPath, absPath string, err error) {
	for i := 1; i <= 9999; i++ {
		name := stem
		if i > 1 {
			name = fmt.Sprintf("%s (%d)", stem, i)
		}
		u := path.Join(dir, name)
		if !strings.HasPrefix(u, "/") {
			u = "/" + u
		}
		abs, rerr := s.storage.Resolve(ctx, u)
		if rerr != nil {
			return "", "", rerr
		}
		if _, serr := os.Stat(abs); serr != nil {
			if errors.Is(serr, os.ErrNotExist) {
				return u, abs, nil
			}
			return "", "", fmt.Errorf("archive: stat dest: %w", serr)
		}
	}
	return "", "", fmt.Errorf("archive: too many destination collisions for %q", stem)
}

// ── format-specific listing ─────────────────────────────────────────

func listZip(abs string) ([]ArchiveEntry, error) {
	zr, err := zip.OpenReader(abs)
	if err != nil {
		return nil, fmt.Errorf("%w: %w", ErrArchiveUnreadable, err)
	}
	defer zr.Close()

	entries := make([]ArchiveEntry, 0, len(zr.File))
	for _, f := range zr.File {
		name := normalizeEntryName(f.Name)
		if name == "" {
			continue
		}
		isDir := f.FileInfo().IsDir() || strings.HasSuffix(f.Name, "/")
		entries = append(entries, ArchiveEntry{Path: name, Size: int64(f.UncompressedSize64), Dir: isDir})
		if len(entries) > maxArchiveEntries {
			return nil, ErrArchiveTooLarge
		}
	}
	return entries, nil
}

func listRar(abs string) ([]ArchiveEntry, error) {
	rc, err := rardecode.OpenReader(abs)
	if err != nil {
		return nil, fmt.Errorf("%w: %w", ErrArchiveUnreadable, err)
	}
	defer rc.Close()

	var entries []ArchiveEntry
	for {
		hdr, err := rc.Next()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return nil, fmt.Errorf("%w: %w", ErrArchiveUnreadable, err)
		}
		name := normalizeEntryName(hdr.Name)
		if name == "" {
			continue
		}
		size := hdr.UnPackedSize
		if hdr.UnKnownSize {
			size = -1
		}
		entries = append(entries, ArchiveEntry{Path: name, Size: size, Dir: hdr.IsDir})
		if len(entries) > maxArchiveEntries {
			return nil, ErrArchiveTooLarge
		}
	}
	return entries, nil
}

func list7z(abs string) ([]ArchiveEntry, error) {
	rc, err := sevenzip.OpenReader(abs)
	if err != nil {
		return nil, fmt.Errorf("%w: %w", ErrArchiveUnreadable, err)
	}
	defer rc.Close()

	entries := make([]ArchiveEntry, 0, len(rc.File))
	for _, f := range rc.File {
		name := normalizeEntryName(f.Name)
		if name == "" {
			continue
		}
		isDir := f.FileInfo().IsDir()
		entries = append(entries, ArchiveEntry{Path: name, Size: int64(f.UncompressedSize), Dir: isDir})
		if len(entries) > maxArchiveEntries {
			return nil, ErrArchiveTooLarge
		}
	}
	return entries, nil
}

// ── format-specific single-entry streaming ──────────────────────────

func openZip(abs, target string) (io.ReadCloser, int64, error) {
	zr, err := zip.OpenReader(abs)
	if err != nil {
		return nil, -1, fmt.Errorf("%w: %w", ErrArchiveUnreadable, err)
	}
	for _, f := range zr.File {
		if f.FileInfo().IsDir() {
			continue
		}
		if normalizeEntryName(f.Name) != target {
			continue
		}
		rc, err := f.Open()
		if err != nil {
			_ = zr.Close()
			return nil, -1, fmt.Errorf("%w: %w", ErrArchiveUnreadable, err)
		}
		return &archiveEntryReadCloser{reader: rc, closers: []io.Closer{rc, zr}}, int64(f.UncompressedSize64), nil
	}
	_ = zr.Close()
	return nil, -1, ErrArchiveEntryNotFound
}

func openRar(abs, target string) (io.ReadCloser, int64, error) {
	rc, err := rardecode.OpenReader(abs)
	if err != nil {
		return nil, -1, fmt.Errorf("%w: %w", ErrArchiveUnreadable, err)
	}
	for {
		hdr, err := rc.Next()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			_ = rc.Close()
			return nil, -1, fmt.Errorf("%w: %w", ErrArchiveUnreadable, err)
		}
		if hdr.IsDir {
			continue
		}
		if normalizeEntryName(hdr.Name) != target {
			continue
		}
		size := hdr.UnPackedSize
		if hdr.UnKnownSize {
			size = -1
		}
		// rc reads the current file's content after Next(); the
		// ReadCloser is both the reader and the sole closer.
		return &archiveEntryReadCloser{reader: rc, closers: []io.Closer{rc}}, size, nil
	}
	_ = rc.Close()
	return nil, -1, ErrArchiveEntryNotFound
}

func open7z(abs, target string) (io.ReadCloser, int64, error) {
	rc, err := sevenzip.OpenReader(abs)
	if err != nil {
		return nil, -1, fmt.Errorf("%w: %w", ErrArchiveUnreadable, err)
	}
	for _, f := range rc.File {
		if f.FileInfo().IsDir() {
			continue
		}
		if normalizeEntryName(f.Name) != target {
			continue
		}
		fr, err := f.Open()
		if err != nil {
			_ = rc.Close()
			return nil, -1, fmt.Errorf("%w: %w", ErrArchiveUnreadable, err)
		}
		return &archiveEntryReadCloser{reader: fr, closers: []io.Closer{fr, rc}}, int64(f.UncompressedSize), nil
	}
	_ = rc.Close()
	return nil, -1, ErrArchiveEntryNotFound
}

// archiveEntryReadCloser couples an entry's content reader with the
// closers that own the underlying archive resources, so a single
// Close() releases the entry reader AND the archive handle.
type archiveEntryReadCloser struct {
	reader  io.Reader
	closers []io.Closer
}

func (a *archiveEntryReadCloser) Read(p []byte) (int, error) { return a.reader.Read(p) }

func (a *archiveEntryReadCloser) Close() error {
	var err error
	for _, c := range a.closers {
		if cerr := c.Close(); cerr != nil && err == nil {
			err = cerr
		}
	}
	return err
}

// ── format-specific extraction ──────────────────────────────────────

func extractZip(absArchive, destAbs string, log *slog.Logger) error {
	zr, err := zip.OpenReader(absArchive)
	if err != nil {
		return fmt.Errorf("%w: %w", ErrArchiveUnreadable, err)
	}
	defer zr.Close()

	for _, f := range zr.File {
		target, ok := safeJoin(destAbs, f.Name)
		if !ok {
			log.Warn("archive extract: skipping unsafe entry", slog.String("entry", f.Name))
			continue
		}
		if f.FileInfo().IsDir() || strings.HasSuffix(f.Name, "/") {
			if err := os.MkdirAll(target, 0o755); err != nil {
				return fmt.Errorf("archive: mkdir entry: %w", err)
			}
			continue
		}
		rc, err := f.Open()
		if err != nil {
			return fmt.Errorf("%w: %w", ErrArchiveUnreadable, err)
		}
		werr := writeExtractedFile(target, rc)
		_ = rc.Close()
		if werr != nil {
			return werr
		}
	}
	return nil
}

func extractRar(absArchive, destAbs string, log *slog.Logger) error {
	rc, err := rardecode.OpenReader(absArchive)
	if err != nil {
		return fmt.Errorf("%w: %w", ErrArchiveUnreadable, err)
	}
	defer rc.Close()

	for {
		hdr, err := rc.Next()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return fmt.Errorf("%w: %w", ErrArchiveUnreadable, err)
		}
		target, ok := safeJoin(destAbs, hdr.Name)
		if !ok {
			log.Warn("archive extract: skipping unsafe entry", slog.String("entry", hdr.Name))
			continue
		}
		if hdr.IsDir {
			if err := os.MkdirAll(target, 0o755); err != nil {
				return fmt.Errorf("archive: mkdir entry: %w", err)
			}
			continue
		}
		if err := writeExtractedFile(target, rc); err != nil {
			return err
		}
	}
	return nil
}

func extract7z(absArchive, destAbs string, log *slog.Logger) error {
	rc, err := sevenzip.OpenReader(absArchive)
	if err != nil {
		return fmt.Errorf("%w: %w", ErrArchiveUnreadable, err)
	}
	defer rc.Close()

	for _, f := range rc.File {
		target, ok := safeJoin(destAbs, f.Name)
		if !ok {
			log.Warn("archive extract: skipping unsafe entry", slog.String("entry", f.Name))
			continue
		}
		if f.FileInfo().IsDir() {
			if err := os.MkdirAll(target, 0o755); err != nil {
				return fmt.Errorf("archive: mkdir entry: %w", err)
			}
			continue
		}
		fr, err := f.Open()
		if err != nil {
			return fmt.Errorf("%w: %w", ErrArchiveUnreadable, err)
		}
		werr := writeExtractedFile(target, fr)
		_ = fr.Close()
		if werr != nil {
			return werr
		}
	}
	return nil
}

// writeExtractedFile creates target (and any missing parent dirs) and
// copies r into it. The parent MkdirAll preserves the archive's
// subdirectory structure.
func writeExtractedFile(target string, r io.Reader) error {
	if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
		return fmt.Errorf("archive: mkdir entry parent: %w", err)
	}
	f, err := os.OpenFile(target, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
	if err != nil {
		return fmt.Errorf("archive: create entry: %w", err)
	}
	if _, err := io.Copy(f, r); err != nil {
		_ = f.Close()
		return fmt.Errorf("archive: write entry: %w", err)
	}
	if err := f.Close(); err != nil {
		return fmt.Errorf("archive: close entry: %w", err)
	}
	return nil
}

// ── helpers ─────────────────────────────────────────────────────────

// normalizeEntryName canonicalizes an archive-internal entry name to a
// forward-slash, cleaned, leading-slash-free form. Backslashes (some
// legacy zips / Windows-authored archives) are converted to slashes.
// Returns "" for empty or root-only ("." / "/") names.
func normalizeEntryName(name string) string {
	name = strings.ReplaceAll(name, "\\", "/")
	name = strings.TrimPrefix(name, "/")
	name = path.Clean(name)
	if name == "." || name == "" {
		return ""
	}
	return name
}

// safeJoin joins an archive entry name under destAbs, guaranteeing the
// result stays strictly inside destAbs (Zip-Slip protection). It
// returns ok=false for entries that are absolute, contain a Windows
// drive letter, or use `..` to escape the destination — the caller MUST
// skip such entries rather than writing them.
func safeJoin(destAbs, entryName string) (string, bool) {
	name := strings.ReplaceAll(entryName, "\\", "/")
	// Reject rooted paths (POSIX "/etc/…") and Windows drive-absolute
	// ("C:/…") outright — they must never be honored.
	if strings.HasPrefix(name, "/") || hasDriveLetter(name) {
		return "", false
	}
	name = path.Clean(name)
	if name == "." || name == "" {
		return "", false
	}
	target := filepath.Join(destAbs, filepath.FromSlash(name))
	rel, err := filepath.Rel(destAbs, target)
	if err != nil || rel == ".." || strings.HasPrefix(rel, ".."+string(os.PathSeparator)) {
		return "", false
	}
	return target, true
}

// hasDriveLetter reports whether name begins with a Windows drive
// specifier like "C:" — those must be rejected on all platforms so a
// malicious archive cannot escape the destination when extracted on
// Windows.
func hasDriveLetter(name string) bool {
	if len(name) < 2 || name[1] != ':' {
		return false
	}
	c := name[0]
	return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
}

func sortArchiveEntries(es []ArchiveEntry) {
	sort.Slice(es, func(i, j int) bool { return es[i].Path < es[j].Path })
}
