package usecase

import (
	"archive/zip"
	"context"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"

	dfile "github.com/synctuary/synctuary-server/internal/domain/file"
)

// stubStorage is a minimal dfile.FileStorage that only implements
// Resolve (the only method ArchiveService uses). It maps a §1 user path
// to an absolute path under root, with no containment enforcement — the
// archive service's own Zip-Slip protection is what these tests exercise.
type stubStorage struct{ root string }

func (s stubStorage) Resolve(_ context.Context, p string) (string, error) {
	return filepath.Join(s.root, filepath.FromSlash(strings.TrimPrefix(p, "/"))), nil
}

func (stubStorage) Put(context.Context, string, io.Reader) error { return nil }
func (stubStorage) Get(context.Context, string, int64, int64) (io.ReadCloser, error) {
	return nil, nil
}
func (stubStorage) Delete(context.Context, string, bool) error            { return nil }
func (stubStorage) Move(context.Context, string, string, bool) error      { return nil }
func (stubStorage) DeduplicateLink(context.Context, []byte, string) error { return nil }
func (stubStorage) SyncCopy(context.Context, []byte, string) error        { return nil }
func (stubStorage) List(context.Context, string) ([]dfile.DirEntry, error) {
	return nil, nil
}
func (stubStorage) Stat(context.Context, string) (*dfile.FileMeta, error) {
	return nil, nil
}

// zipEntry is a (name, content) pair. A nil content with a trailing
// slash in the name denotes a directory entry.
type zipEntry struct {
	name    string
	content []byte
}

// writeZip builds a zip file at absPath containing the given entries.
func writeZip(t *testing.T, absPath string, entries []zipEntry) {
	t.Helper()
	f, err := os.Create(absPath)
	if err != nil {
		t.Fatalf("create zip: %v", err)
	}
	defer f.Close()
	zw := zip.NewWriter(f)
	for _, e := range entries {
		if strings.HasSuffix(e.name, "/") {
			if _, err := zw.Create(e.name); err != nil {
				t.Fatalf("zip create dir %q: %v", e.name, err)
			}
			continue
		}
		w, err := zw.Create(e.name)
		if err != nil {
			t.Fatalf("zip create %q: %v", e.name, err)
		}
		if _, err := w.Write(e.content); err != nil {
			t.Fatalf("zip write %q: %v", e.name, err)
		}
	}
	if err := zw.Close(); err != nil {
		t.Fatalf("zip close: %v", err)
	}
}

func newTestService(t *testing.T) (*ArchiveService, string) {
	t.Helper()
	root := t.TempDir()
	svc := NewArchiveService(stubStorage{root: root}, nil)
	return svc, root
}

// ── format detection / dispatch ─────────────────────────────────────

func TestArchiveFormat(t *testing.T) {
	cases := map[string]string{
		"a.zip":       "zip",
		"a.CBZ":       "zip",
		"comic.cbr":   "rar",
		"x.RAR":       "rar",
		"data.7z":     "7z",
		"movie.mp4":   "",
		"noext":       "",
		"a.tar.gz":    "", // .gz is not a browsable archive here
		"folder.7z/x": "", // extension of the last component only
	}
	for name, want := range cases {
		if got := archiveFormat(name); got != want {
			t.Errorf("archiveFormat(%q) = %q, want %q", name, got, want)
		}
	}
}

func TestIsArchivePath(t *testing.T) {
	for _, name := range []string{"a.zip", "a.cbz", "a.rar", "a.cbr", "a.7z"} {
		if !IsArchivePath(name) {
			t.Errorf("IsArchivePath(%q) = false, want true", name)
		}
	}
	for _, name := range []string{"a.mp4", "a.txt", "a"} {
		if IsArchivePath(name) {
			t.Errorf("IsArchivePath(%q) = true, want false", name)
		}
	}
}

func TestListUnsupportedType(t *testing.T) {
	svc, _ := newTestService(t)
	if _, err := svc.List(context.Background(), "/movie.mp4"); !errors.Is(err, ErrArchiveUnsupported) {
		t.Fatalf("List(non-archive) err = %v, want ErrArchiveUnsupported", err)
	}
	if _, _, err := svc.Open(context.Background(), "/movie.mp4", "x"); !errors.Is(err, ErrArchiveUnsupported) {
		t.Fatalf("Open(non-archive) err = %v, want ErrArchiveUnsupported", err)
	}
}

func TestListMissingFile(t *testing.T) {
	svc, _ := newTestService(t)
	if _, err := svc.List(context.Background(), "/nope.zip"); !errors.Is(err, dfile.ErrFileNotFound) {
		t.Fatalf("List(missing) err = %v, want ErrFileNotFound", err)
	}
}

// rar/7z dispatch + error mapping: we cannot author valid fixtures with
// read-only libraries, so we feed garbage bytes with the right extension
// and assert the format-specific reader is reached and its open failure
// is mapped to ErrArchiveUnreadable.
func TestListCorruptRarAnd7z(t *testing.T) {
	svc, root := newTestService(t)
	for _, name := range []string{"bad.rar", "bad.cbr", "bad.7z"} {
		if err := os.WriteFile(filepath.Join(root, name), []byte("not a real archive"), 0o644); err != nil {
			t.Fatal(err)
		}
		if _, err := svc.List(context.Background(), "/"+name); !errors.Is(err, ErrArchiveUnreadable) {
			t.Errorf("List(%q) err = %v, want ErrArchiveUnreadable", name, err)
		}
	}
}

// ── zip List ────────────────────────────────────────────────────────

func TestListZip(t *testing.T) {
	svc, root := newTestService(t)
	writeZip(t, filepath.Join(root, "a.zip"), []zipEntry{
		{name: "readme.txt", content: []byte("hello")},
		{name: "images/", content: nil},
		{name: "images/002.jpg", content: []byte("second")},
		{name: "images/001.jpg", content: []byte("first!")},
	})

	entries, err := svc.List(context.Background(), "/a.zip")
	if err != nil {
		t.Fatalf("List: %v", err)
	}
	byPath := map[string]ArchiveEntry{}
	for _, e := range entries {
		byPath[e.Path] = e
	}
	if e, ok := byPath["readme.txt"]; !ok || e.Dir || e.Size != 5 {
		t.Errorf("readme.txt entry = %+v (ok=%v)", e, ok)
	}
	if e, ok := byPath["images"]; !ok || !e.Dir {
		t.Errorf("images dir entry = %+v (ok=%v)", e, ok)
	}
	if e, ok := byPath["images/001.jpg"]; !ok || e.Dir || e.Size != 6 {
		t.Errorf("images/001.jpg entry = %+v (ok=%v)", e, ok)
	}

	// Entries are sorted by Path.
	for i := 1; i < len(entries); i++ {
		if entries[i-1].Path > entries[i].Path {
			t.Errorf("entries not sorted: %q before %q", entries[i-1].Path, entries[i].Path)
		}
	}
}

// ── zip Open (content roundtrip) ────────────────────────────────────

func TestOpenZipRoundtrip(t *testing.T) {
	svc, root := newTestService(t)
	want := []byte("the quick brown fox")
	writeZip(t, filepath.Join(root, "a.zip"), []zipEntry{
		{name: "dir/file.txt", content: want},
	})

	rc, size, err := svc.Open(context.Background(), "/a.zip", "dir/file.txt")
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	defer rc.Close()
	if size != int64(len(want)) {
		t.Errorf("size = %d, want %d", size, len(want))
	}
	got, err := io.ReadAll(rc)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	if string(got) != string(want) {
		t.Errorf("content = %q, want %q", got, want)
	}
}

func TestOpenZipEntryNotFound(t *testing.T) {
	svc, root := newTestService(t)
	writeZip(t, filepath.Join(root, "a.zip"), []zipEntry{
		{name: "present.txt", content: []byte("x")},
	})
	if _, _, err := svc.Open(context.Background(), "/a.zip", "missing.txt"); !errors.Is(err, ErrArchiveEntryNotFound) {
		t.Fatalf("Open(missing entry) err = %v, want ErrArchiveEntryNotFound", err)
	}
	// A directory entry is not openable as content.
	writeZip(t, filepath.Join(root, "b.zip"), []zipEntry{
		{name: "sub/", content: nil},
		{name: "sub/x.txt", content: []byte("x")},
	})
	if _, _, err := svc.Open(context.Background(), "/b.zip", "sub"); !errors.Is(err, ErrArchiveEntryNotFound) {
		t.Fatalf("Open(dir entry) err = %v, want ErrArchiveEntryNotFound", err)
	}
}

// ── zip Extract (nested dirs) ───────────────────────────────────────

func TestExtractZipNested(t *testing.T) {
	svc, root := newTestService(t)
	writeZip(t, filepath.Join(root, "a.zip"), []zipEntry{
		{name: "top.txt", content: []byte("top")},
		{name: "d1/", content: nil},
		{name: "d1/mid.txt", content: []byte("mid")},
		{name: "d1/d2/deep.txt", content: []byte("deep")},
		{name: "empty/", content: nil},
	})

	dest, err := svc.Extract(context.Background(), "/a.zip")
	if err != nil {
		t.Fatalf("Extract: %v", err)
	}
	if dest != "/a" {
		t.Errorf("dest = %q, want /a", dest)
	}

	assertFile(t, filepath.Join(root, "a", "top.txt"), "top")
	assertFile(t, filepath.Join(root, "a", "d1", "mid.txt"), "mid")
	assertFile(t, filepath.Join(root, "a", "d1", "d2", "deep.txt"), "deep")
	// Empty directory preserved.
	if info, err := os.Stat(filepath.Join(root, "a", "empty")); err != nil || !info.IsDir() {
		t.Errorf("empty dir not preserved: err=%v", err)
	}
}

// Extracting the same archive twice yields a suffixed sibling directory.
func TestExtractZipCollisionSuffix(t *testing.T) {
	svc, root := newTestService(t)
	writeZip(t, filepath.Join(root, "a.zip"), []zipEntry{
		{name: "x.txt", content: []byte("x")},
	})

	first, err := svc.Extract(context.Background(), "/a.zip")
	if err != nil {
		t.Fatalf("Extract 1: %v", err)
	}
	second, err := svc.Extract(context.Background(), "/a.zip")
	if err != nil {
		t.Fatalf("Extract 2: %v", err)
	}
	if first != "/a" {
		t.Errorf("first dest = %q, want /a", first)
	}
	if second != "/a (2)" {
		t.Errorf("second dest = %q, want /a (2)", second)
	}
	assertFile(t, filepath.Join(root, "a (2)", "x.txt"), "x")
}

// ── Zip-Slip protection ─────────────────────────────────────────────

func TestExtractZipSlipRejected(t *testing.T) {
	svc, root := newTestService(t)
	writeZip(t, filepath.Join(root, "a.zip"), []zipEntry{
		{name: "good.txt", content: []byte("safe")},
		{name: "../evil.txt", content: []byte("pwned")},
		{name: "/abs_evil.txt", content: []byte("pwned")},
		{name: "sub/../../deep_evil.txt", content: []byte("pwned")},
	})

	dest, err := svc.Extract(context.Background(), "/a.zip")
	if err != nil {
		t.Fatalf("Extract: %v", err)
	}

	// The benign entry is extracted inside the destination.
	assertFile(t, filepath.Join(root, "a", "good.txt"), "safe")

	// No malicious entry escaped the destination directory.
	for _, escaped := range []string{
		filepath.Join(root, "evil.txt"),
		filepath.Join(root, "abs_evil.txt"),
		filepath.Join(root, "deep_evil.txt"),
	} {
		if _, err := os.Stat(escaped); !errors.Is(err, os.ErrNotExist) {
			t.Errorf("zip-slip: unexpected file escaped to %q (err=%v)", escaped, err)
		}
	}
	_ = dest
}

func TestSafeJoin(t *testing.T) {
	base := filepath.Join(t.TempDir(), "dest")
	ok := []string{"a.txt", "a/b/c.txt", "a/../b.txt", "dir/"}
	for _, name := range ok {
		if _, good := safeJoin(base, name); !good {
			t.Errorf("safeJoin(%q) = not-ok, want ok", name)
		}
	}
	bad := []string{"../evil.txt", "/etc/passwd", "C:/windows", `a\..\..\evil`, "..", "a/../../evil"}
	for _, name := range bad {
		if _, good := safeJoin(base, name); good {
			t.Errorf("safeJoin(%q) = ok, want rejected", name)
		}
	}
}

func TestNormalizeEntryName(t *testing.T) {
	cases := map[string]string{
		`a\b\c.txt`: "a/b/c.txt",
		"/leading":  "leading",
		"a/./b":     "a/b",
		".":         "",
		"":          "",
		"a//b":      "a/b",
	}
	for in, want := range cases {
		if got := normalizeEntryName(in); got != want {
			t.Errorf("normalizeEntryName(%q) = %q, want %q", in, got, want)
		}
	}
}

// ── entry cap ───────────────────────────────────────────────────────

func TestListEntryCap(t *testing.T) {
	svc, root := newTestService(t)
	f, err := os.Create(filepath.Join(root, "big.zip"))
	if err != nil {
		t.Fatal(err)
	}
	zw := zip.NewWriter(f)
	for i := 0; i < maxArchiveEntries+5; i++ {
		if _, err := zw.Create("f" + itoa(i) + ".txt"); err != nil {
			t.Fatal(err)
		}
	}
	if err := zw.Close(); err != nil {
		t.Fatal(err)
	}
	_ = f.Close()

	if _, err := svc.List(context.Background(), "/big.zip"); !errors.Is(err, ErrArchiveTooLarge) {
		t.Fatalf("List(over-cap) err = %v, want ErrArchiveTooLarge", err)
	}
}

// ── helpers ─────────────────────────────────────────────────────────

func assertFile(t *testing.T, abs, want string) {
	t.Helper()
	got, err := os.ReadFile(abs)
	if err != nil {
		t.Fatalf("read %q: %v", abs, err)
	}
	if string(got) != want {
		t.Errorf("%q content = %q, want %q", abs, got, want)
	}
}

// itoa avoids importing strconv just for a loop counter.
func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var b [20]byte
	i := len(b)
	for n > 0 {
		i--
		b[i] = byte('0' + n%10)
		n /= 10
	}
	return string(b[i:])
}
