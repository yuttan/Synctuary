package usecase

import (
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
)

// Media tools (ffmpeg / ffprobe) are invoked as external processes for
// video thumbnails (thumbnail_service.go), on-the-fly transcode
// (transcode_service.go), and media probing (probe_service.go).
//
// Resolution is bundled-first: the installer ships ffmpeg/ffprobe in an
// <exe-dir>/ffmpeg/ subdirectory (see deploy/windows). We deliberately
// prefer that bundled copy over whatever happens to be on PATH so the
// server's behavior is consistent with the version shipped alongside it,
// rather than a random system ffmpeg a tester may already have.
//
// ffmpegPath / ffprobePath are resolved once at package init. Empty
// string means "not found anywhere" → the corresponding capability
// degrades gracefully (thumbnails skip video, transcode/probe return
// 503). ffmpegAvailable / ffprobeAvailable are derived booleans.
var (
	ffmpegPath  = resolveMediaTool("ffmpeg")
	ffprobePath = resolveMediaTool("ffprobe")

	ffmpegAvailable  = ffmpegPath != ""
	ffprobeAvailable = ffprobePath != ""
)

func init() {
	logMediaTool("ffmpeg", ffmpegPath)
	logMediaTool("ffprobe", ffprobePath)
}

func logMediaTool(name, path string) {
	if path == "" {
		slog.Info("media tool not found (feature degraded)", slog.String("tool", name))
		return
	}
	slog.Info("media tool resolved", slog.String("tool", name), slog.String("path", path))
}

// resolveMediaTool finds the absolute path of an external media tool
// (name without extension, e.g. "ffmpeg"). Returns "" when the tool is
// unavailable. See resolveMediaToolWith for the search order; this wraps
// it with the real os.Executable / os.Stat / exec.LookPath.
func resolveMediaTool(name string) string {
	exeDir := ""
	if exe, err := os.Executable(); err == nil {
		exeDir = filepath.Dir(exe)
	}
	fileExists := func(p string) bool {
		info, err := os.Stat(p)
		return err == nil && !info.IsDir()
	}
	return resolveMediaToolWith(name, exeDir, runtime.GOOS, fileExists, exec.LookPath)
}

// resolveMediaToolWith is the pure, unit-testable core of tool resolution.
// Dependencies are injected so tests can exercise the search order without
// a real ffmpeg on the box:
//
//   - exeDir:      directory of the running executable ("" if unknown)
//   - goos:        target OS (drives the .exe suffix)
//   - fileExists:  reports whether an absolute candidate path exists
//   - lookPath:    PATH resolver (exec.LookPath signature)
//
// Search order (first hit wins):
//  1. <exeDir>/ffmpeg/<name><exe>   — bundled installer layout
//  2. <exeDir>/<name><exe>          — loose, dropped beside synctuaryd.exe
//  3. lookPath(name)                — system PATH (historical behavior)
//
// On non-Windows the bundled/loose candidates are checked both with and
// without the (empty) extension so a Linux build that ships a bare
// "ffmpeg" binary next to the exe is still found.
func resolveMediaToolWith(
	name, exeDir, goos string,
	fileExists func(string) bool,
	lookPath func(string) (string, error),
) string {
	exts := []string{".exe"}
	if goos != "windows" {
		exts = []string{"", ".exe"}
	}

	if exeDir != "" {
		bases := []string{
			filepath.Join(exeDir, "ffmpeg"), // 1. bundled subdir
			exeDir,                          // 2. loose beside exe
		}
		for _, base := range bases {
			for _, ext := range exts {
				cand := filepath.Join(base, name+ext)
				if fileExists(cand) {
					return cand
				}
			}
		}
	}

	// 3. PATH fallback.
	if p, err := lookPath(name); err == nil {
		return p
	}
	return ""
}
