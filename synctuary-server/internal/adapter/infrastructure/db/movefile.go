package db

import (
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"syscall"
)

// moveFile moves src to dst, replacing dst if it already exists.
//
// Within one volume this is a single atomic os.Rename. Across volumes
// rename fails at the OS level (POSIX EXDEV; on Windows
// ERROR_NOT_SAME_DEVICE, "The system cannot move the file to a
// different disk drive") — and with multi-drive shares (PROTOCOL §10)
// that is a NORMAL topology: the upload staging directory lives under
// storage.staging_path (typically the install drive) while the target
// share may be any other drive. In that case we fall back to copying
// into a temp file in the DESTINATION directory and renaming that into
// place, so the visible materialization of the file remains atomic;
// the staging copy is then removed best-effort.
func moveFile(src, dst string) error {
	err := os.Rename(src, dst)
	if err == nil || !isCrossDeviceError(err) {
		return err
	}
	return copyThenRename(src, dst)
}

// copyThenRename is the cross-volume fallback for moveFile: copy src to
// a temp file beside dst, fsync it, rename it over dst (same-volume,
// atomic), then delete src.
func copyThenRename(src, dst string) error {
	tmp, err := os.CreateTemp(filepath.Dir(dst), ".synctuary-move-*")
	if err != nil {
		return fmt.Errorf("cross-volume move: create temp: %w", err)
	}
	tmpPath := tmp.Name()

	in, err := os.Open(src)
	if err != nil {
		_ = tmp.Close()
		_ = os.Remove(tmpPath)
		return fmt.Errorf("cross-volume move: open src: %w", err)
	}
	_, cpErr := io.Copy(tmp, in)
	_ = in.Close()
	if cpErr == nil {
		cpErr = tmp.Sync()
	}
	if clErr := tmp.Close(); cpErr == nil {
		cpErr = clErr
	}
	if cpErr != nil {
		_ = os.Remove(tmpPath)
		return fmt.Errorf("cross-volume move: copy: %w", cpErr)
	}

	if err := os.Rename(tmpPath, dst); err != nil {
		_ = os.Remove(tmpPath)
		return fmt.Errorf("cross-volume move: rename into place: %w", err)
	}
	// Staging cleanup is best-effort: the upload has already succeeded,
	// and stale .part files are bounded by the session TTL sweep.
	_ = os.Remove(src)
	return nil
}

// isCrossDeviceError reports whether err is the OS's "cannot rename
// across volumes/devices" error.
func isCrossDeviceError(err error) bool {
	if errors.Is(err, syscall.EXDEV) {
		return true
	}
	if runtime.GOOS == "windows" {
		// ERROR_NOT_SAME_DEVICE (0x11). Go's Windows syscall package
		// maps EXDEV to a synthetic value, so the real Win32 code has
		// to be checked explicitly.
		var errno syscall.Errno
		if errors.As(err, &errno) && uintptr(errno) == 0x11 {
			return true
		}
	}
	return false
}
