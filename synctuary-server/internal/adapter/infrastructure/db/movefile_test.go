package db

import (
	"os"
	"path/filepath"
	"runtime"
	"syscall"
	"testing"
)

// A genuine cross-volume rename cannot be provoked portably in CI, so
// the fallback path is exercised directly and the error classifier is
// tested against the concrete errno values.

func TestCopyThenRename(t *testing.T) {
	srcDir := t.TempDir()
	dstDir := t.TempDir()

	src := filepath.Join(srcDir, "upload.part")
	dst := filepath.Join(dstDir, "photo.jpg")
	content := []byte("fake jpeg bytes")
	if err := os.WriteFile(src, content, 0o644); err != nil {
		t.Fatalf("write src: %v", err)
	}

	if err := copyThenRename(src, dst); err != nil {
		t.Fatalf("copyThenRename: %v", err)
	}

	got, err := os.ReadFile(dst)
	if err != nil {
		t.Fatalf("read dst: %v", err)
	}
	if string(got) != string(content) {
		t.Fatalf("dst content = %q, want %q", got, content)
	}
	if _, err := os.Stat(src); !os.IsNotExist(err) {
		t.Fatalf("src still exists after move (err=%v)", err)
	}
	// No temp litter left beside the destination.
	entries, err := os.ReadDir(dstDir)
	if err != nil {
		t.Fatalf("readdir dst: %v", err)
	}
	if len(entries) != 1 {
		t.Fatalf("dst dir has %d entries, want 1 (temp file left behind?)", len(entries))
	}
}

func TestCopyThenRenameReplacesExisting(t *testing.T) {
	dir := t.TempDir()
	src := filepath.Join(dir, "new.part")
	dst := filepath.Join(dir, "existing.jpg")
	if err := os.WriteFile(src, []byte("new"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(dst, []byte("old"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := copyThenRename(src, dst); err != nil {
		t.Fatalf("copyThenRename over existing: %v", err)
	}
	got, _ := os.ReadFile(dst)
	if string(got) != "new" {
		t.Fatalf("dst = %q, want %q", got, "new")
	}
}

func TestMoveFileSameVolume(t *testing.T) {
	dir := t.TempDir()
	src := filepath.Join(dir, "a.part")
	dst := filepath.Join(dir, "b.jpg")
	if err := os.WriteFile(src, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := moveFile(src, dst); err != nil {
		t.Fatalf("moveFile same volume: %v", err)
	}
	if _, err := os.Stat(dst); err != nil {
		t.Fatalf("dst missing: %v", err)
	}
}

func TestIsCrossDeviceError(t *testing.T) {
	if !isCrossDeviceError(&os.LinkError{Op: "rename", Old: "a", New: "b", Err: syscall.EXDEV}) {
		t.Error("EXDEV not classified as cross-device")
	}
	if runtime.GOOS == "windows" {
		// ERROR_NOT_SAME_DEVICE as surfaced by os.Rename on Windows.
		winErr := &os.LinkError{Op: "rename", Old: "a", New: "b", Err: syscall.Errno(0x11)}
		if !isCrossDeviceError(winErr) {
			t.Error("ERROR_NOT_SAME_DEVICE not classified as cross-device")
		}
	}
	if isCrossDeviceError(&os.LinkError{Op: "rename", Old: "a", New: "b", Err: syscall.Errno(0x2)}) {
		t.Error("unrelated errno wrongly classified as cross-device")
	}
	if isCrossDeviceError(nil) {
		t.Error("nil wrongly classified as cross-device")
	}
}
