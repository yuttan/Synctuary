package usecase

import (
	"context"
	"encoding/json"
	"fmt"
	"os/exec"
	"strconv"
)

// ffprobeAvailable is resolved independently of ffmpeg (see
// media_tools.go): some minimal static ffmpeg builds ship without
// ffprobe, and vice versa. The mediainfo endpoint is gated on THIS
// probe, not ffmpegAvailable.

// MediaInfo carries the coarse metadata the client needs to enable the
// transcode seek bar: the total duration and the source pixel
// dimensions. Width/Height are 0 for audio-only inputs (no video
// stream); the transcode-seek use case only requires DurationSeconds.
type MediaInfo struct {
	DurationSeconds float64
	Width           int
	Height          int
}

// Probe returns coarse media metadata (duration + dimensions) for the
// video at path via ffprobe. It exists so the Android client can enable
// its seek bar during transcode playback: when a container is unplayable
// directly, ExoPlayer errors during parsing before any duration is
// known, so the client can't otherwise learn the file length.
//
// Video-only gate (like Stream): non-video MIME types return
// ErrTranscodeUnsupported so the handler maps them to 400. Missing
// ffprobe returns ErrTranscoderUnavailable → 503.
func (s *TranscodeService) Probe(ctx context.Context, path string) (*MediaInfo, error) {
	if !ffprobeAvailable {
		return nil, ErrTranscoderUnavailable
	}
	if s.storage == nil {
		return nil, fmt.Errorf("probe: no storage configured")
	}

	meta, err := s.storage.Stat(ctx, path)
	if err != nil {
		return nil, err
	}
	if !isVideo(meta.MimeType) {
		return nil, fmt.Errorf("%w: %q", ErrTranscodeUnsupported, meta.MimeType)
	}

	absPath, err := s.storage.Resolve(ctx, path)
	if err != nil {
		return nil, fmt.Errorf("probe: resolve path: %w", err)
	}

	args := buildProbeArgs(absPath)
	cmd := exec.CommandContext(ctx, ffprobePath, args...)
	stderr := &boundedBuffer{limit: stderrCapBytes}
	cmd.Stderr = stderr

	out, err := cmd.Output()
	if err != nil {
		return nil, fmt.Errorf("probe: ffprobe: %w (stderr: %s)", err, stderr.String())
	}

	info, err := parseProbeOutput(out)
	if err != nil {
		return nil, fmt.Errorf("probe: parse ffprobe output: %w", err)
	}
	return info, nil
}

// buildProbeArgs constructs the ffprobe argument vector for a JSON dump
// of the first video stream's dimensions plus the container duration.
// Extracted as a pure function so it is unit-testable without invoking
// ffprobe.
func buildProbeArgs(absPath string) []string {
	return []string{
		"-v", "error",
		"-select_streams", "v:0",
		"-show_entries", "stream=width,height",
		"-show_entries", "format=duration",
		"-of", "json",
		absPath,
	}
}

// probeJSON mirrors the (subset of) ffprobe -of json output we consume.
// format.duration is a STRING in ffprobe's output (e.g. "123.456000"),
// so it is parsed with strconv rather than typed as a JSON number.
type probeJSON struct {
	Streams []struct {
		Width  int `json:"width"`
		Height int `json:"height"`
	} `json:"streams"`
	Format struct {
		Duration string `json:"duration"`
	} `json:"format"`
}

// parseProbeOutput decodes ffprobe -of json output into a MediaInfo.
// A missing or unparseable duration yields 0 rather than an error (the
// client treats duration<=0 as "unknown" and keeps the seek bar hidden);
// only invalid JSON is a hard failure. Missing streams (audio-only) →
// Width/Height 0.
func parseProbeOutput(data []byte) (*MediaInfo, error) {
	var p probeJSON
	if err := json.Unmarshal(data, &p); err != nil {
		return nil, err
	}

	info := &MediaInfo{}
	if p.Format.Duration != "" {
		if d, err := strconv.ParseFloat(p.Format.Duration, 64); err == nil && d > 0 {
			info.DurationSeconds = d
		}
	}
	if len(p.Streams) > 0 {
		info.Width = p.Streams[0].Width
		info.Height = p.Streams[0].Height
	}
	return info, nil
}
