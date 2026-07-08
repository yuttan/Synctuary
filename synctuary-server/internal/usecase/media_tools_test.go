package usecase

import (
	"errors"
	"path/filepath"
	"testing"
)

// setExists returns a fileExists func that reports true for exactly the
// candidate paths in the set (already filepath.Join-normalized).
func setExists(present ...string) func(string) bool {
	m := make(map[string]bool, len(present))
	for _, p := range present {
		m[p] = true
	}
	return func(p string) bool { return m[p] }
}

// lookPathReturns builds an exec.LookPath stub returning a fixed result.
func lookPathReturns(path string, err error) func(string) (string, error) {
	return func(string) (string, error) { return path, err }
}

var errNotFound = errors.New("not found on PATH")

func TestResolveMediaTool_BundledWinsOverLoose(t *testing.T) {
	exeDir := `C:\app`
	bundled := filepath.Join(exeDir, "ffmpeg", "ffmpeg.exe")
	loose := filepath.Join(exeDir, "ffmpeg.exe")

	// Both bundled and loose exist; PATH also has one — bundled must win.
	got := resolveMediaToolWith(
		"ffmpeg", exeDir, "windows",
		setExists(bundled, loose),
		lookPathReturns(`C:\sys\ffmpeg.exe`, nil),
	)
	if got != bundled {
		t.Fatalf("expected bundled %q to win, got %q", bundled, got)
	}
}

func TestResolveMediaTool_LooseWinsOverPath(t *testing.T) {
	exeDir := `C:\app`
	loose := filepath.Join(exeDir, "ffprobe.exe")

	// No bundled copy, but a loose one beside the exe — beats PATH.
	got := resolveMediaToolWith(
		"ffprobe", exeDir, "windows",
		setExists(loose),
		lookPathReturns(`C:\sys\ffprobe.exe`, nil),
	)
	if got != loose {
		t.Fatalf("expected loose %q to win over PATH, got %q", loose, got)
	}
}

func TestResolveMediaTool_FallsBackToPath(t *testing.T) {
	exeDir := `C:\app`
	sysPath := `C:\sys\ffmpeg.exe`

	// Nothing beside the exe → PATH result.
	got := resolveMediaToolWith(
		"ffmpeg", exeDir, "windows",
		setExists(), // nothing exists locally
		lookPathReturns(sysPath, nil),
	)
	if got != sysPath {
		t.Fatalf("expected PATH fallback %q, got %q", sysPath, got)
	}
}

func TestResolveMediaTool_NotFoundAnywhere(t *testing.T) {
	got := resolveMediaToolWith(
		"ffmpeg", `C:\app`, "windows",
		setExists(),
		lookPathReturns("", errNotFound),
	)
	if got != "" {
		t.Fatalf("expected empty string when unavailable, got %q", got)
	}
}

func TestResolveMediaTool_EmptyExeDirUsesPath(t *testing.T) {
	// os.Executable() failed → exeDir "" → skip local candidates, use PATH.
	sysPath := "/usr/bin/ffmpeg"
	got := resolveMediaToolWith(
		"ffmpeg", "", "linux",
		func(string) bool {
			t.Fatalf("fileExists must not be consulted when exeDir is empty")
			return false
		},
		lookPathReturns(sysPath, nil),
	)
	if got != sysPath {
		t.Fatalf("expected PATH result %q with empty exeDir, got %q", sysPath, got)
	}
}

func TestResolveMediaTool_NonWindowsBareBinaryBesideExe(t *testing.T) {
	exeDir := "/opt/synctuary"
	// A Linux build ships a bare "ffmpeg" (no extension) in the bundled dir.
	bundledBare := filepath.Join(exeDir, "ffmpeg", "ffmpeg")

	got := resolveMediaToolWith(
		"ffmpeg", exeDir, "linux",
		setExists(bundledBare),
		lookPathReturns("/usr/bin/ffmpeg", nil),
	)
	if got != bundledBare {
		t.Fatalf("expected bare bundled binary %q, got %q", bundledBare, got)
	}
}

func TestResolveMediaTool_SearchOrderBundledSubdirBeforeLooseAndPath(t *testing.T) {
	// Assert full ordering in one shot: bundled subdir dominates.
	exeDir := `D:\Synctuary`
	bundled := filepath.Join(exeDir, "ffmpeg", "ffmpeg.exe")
	loose := filepath.Join(exeDir, "ffmpeg.exe")

	got := resolveMediaToolWith(
		"ffmpeg", exeDir, "windows",
		setExists(bundled, loose),
		lookPathReturns(`X:\ffmpeg.exe`, nil),
	)
	if got != bundled {
		t.Fatalf("search order violated: expected %q, got %q", bundled, got)
	}
}
