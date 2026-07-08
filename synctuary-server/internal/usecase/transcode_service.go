package usecase

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"math"
	"net/http"
	"os/exec"
	"strconv"

	"github.com/synctuary/synctuary-server/internal/domain/file"
)

// ErrTranscodeUnsupported is returned by TranscodeService.Stream when
// the requested file is not a video (transcode only applies to
// video/* MIME types). The handler maps it to 400 unsupported_type.
var ErrTranscodeUnsupported = errors.New("transcode: unsupported media type")

// ErrTranscoderUnavailable is returned when ffmpeg is not present on
// the host. The handler maps it to 503 transcoder_unavailable.
var ErrTranscoderUnavailable = errors.New("transcode: ffmpeg unavailable")

// transcodeFlushBytes controls how often the flushing writer forces an
// http.Flusher.Flush(). ffmpeg emits fragmented-MP4 boxes in bursts;
// flushing every 64 KiB keeps the client's buffer topped up so
// playback starts quickly and progresses smoothly on mobile networks.
const transcodeFlushBytes = 64 << 10 // 64 KiB

// stderrCapBytes bounds how much of ffmpeg's stderr we retain for
// error logging. ffmpeg with -loglevel error is terse, but a pathological
// input could spam; cap it so a failing job can't balloon memory.
const stderrCapBytes = 8 << 10 // 8 KiB

// TranscodeService performs on-the-fly video transcoding to a
// streamable fragmented-MP4 (H.264/AAC) via ffmpeg. It exists to make
// legacy container/codec combinations (AVI, FLV, WMV, VOB, …) — which
// mobile hardware decoders and ExoPlayer's built-in extractors cannot
// handle — playable in the client without a prior server-side batch
// re-encode.
//
// Unlike ThumbnailService this service does not cache: transcoding is
// a linear stream keyed on (path, start) and re-run per request. The
// output is progressive and NOT seekable; the client implements coarse
// seeking by restarting the stream with a new `start` offset.
type TranscodeService struct {
	storage file.FileStorage
	log     *slog.Logger
}

// NewTranscodeService constructs a TranscodeService. storage may be
// nil at construction; callers scope it per-request via WithStorage
// (mirroring ThumbnailService). log defaults to slog.Default().
func NewTranscodeService(storage file.FileStorage, log *slog.Logger) *TranscodeService {
	if log == nil {
		log = slog.Default()
	}
	return &TranscodeService{storage: storage, log: log}
}

// WithStorage returns a copy of this service scoped to a different
// FileStorage (e.g. a share's HostPath root). Used by the handler to
// honor the ?share= query parameter.
func (s *TranscodeService) WithStorage(storage file.FileStorage) *TranscodeService {
	return &TranscodeService{storage: storage, log: s.log}
}

// Available reports whether ffmpeg was resolved (bundled beside the exe
// or on PATH). Reuses the package-level ffmpegAvailable probe (see
// media_tools.go) so a single resolution at init time serves both
// thumbnails and transcoding.
func (s *TranscodeService) Available() bool {
	return ffmpegAvailable
}

// Stream transcodes the video at `path` to fragmented MP4 and copies
// the bytes to w, flushing periodically for progressive playback.
//
// startSeconds performs a coarse seek before decoding (fast input-side
// -ss). It MUST be >= 0 and finite; callers validate at the handler.
//
// ctx cancellation (e.g. client disconnect) kills the ffmpeg process
// via exec.CommandContext. If ffmpeg fails before writing any output,
// the underlying error is returned so the handler can emit a proper
// status. If it fails after bytes have already reached the client, the
// error is returned as well but the caller should treat it as
// best-effort (the response is already committed).
func (s *TranscodeService) Stream(ctx context.Context, path string, startSeconds float64, w io.Writer) error {
	if !s.Available() {
		return ErrTranscoderUnavailable
	}
	if s.storage == nil {
		return fmt.Errorf("transcode: no storage configured")
	}

	meta, err := s.storage.Stat(ctx, path)
	if err != nil {
		return err
	}
	if !isVideo(meta.MimeType) {
		return fmt.Errorf("%w: %q", ErrTranscodeUnsupported, meta.MimeType)
	}

	absPath, err := s.storage.Resolve(ctx, path)
	if err != nil {
		return fmt.Errorf("transcode: resolve path: %w", err)
	}

	args := buildTranscodeArgs(absPath, startSeconds)
	cmd := exec.CommandContext(ctx, ffmpegPath, args...)

	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return fmt.Errorf("transcode: stdout pipe: %w", err)
	}
	stderr := &boundedBuffer{limit: stderrCapBytes}
	cmd.Stderr = stderr

	if err := cmd.Start(); err != nil {
		return fmt.Errorf("transcode: start ffmpeg: %w", err)
	}

	fw := &flushingWriter{w: w, threshold: transcodeFlushBytes}
	if f, ok := w.(http.Flusher); ok {
		fw.flusher = f
	}

	copied, copyErr := io.Copy(fw, stdout)
	fw.flush() // final flush of any buffered tail

	waitErr := cmd.Wait()

	// io.Copy error (client gone, write failure) takes precedence — the
	// pipe read stops as soon as the writer errors.
	if copyErr != nil {
		return fmt.Errorf("transcode: stream copy: %w", copyErr)
	}
	if waitErr != nil {
		// ffmpeg exited non-zero. If we already delivered output the
		// response is committed; the handler logs rather than re-headers.
		return fmt.Errorf("transcode: ffmpeg exit (bytes=%d): %w (stderr: %s)",
			copied, waitErr, stderr.String())
	}
	return nil
}

// buildTranscodeArgs constructs the ffmpeg argument vector for a
// streaming transcode to fragmented MP4 on stdout (pipe:1). Extracted
// as a pure function so the argument ordering — which is load-bearing
// for correctness (input-side -ss for fast seek, movflags for a
// pipe-safe fMP4) — is unit-testable without invoking ffmpeg.
//
// startSeconds <= 0 omits -ss entirely (start from the beginning).
// Negative/NaN/Inf values are treated as 0 defensively; the handler
// rejects them earlier with a 400.
func buildTranscodeArgs(absPath string, startSeconds float64) []string {
	args := []string{"-hide_banner", "-loglevel", "error"}

	// Input-side -ss (BEFORE -i) is a fast keyframe seek. Placing it
	// after -i would decode-and-discard everything up to the offset,
	// which is far slower for coarse seeking.
	if startSeconds > 0 && !math.IsInf(startSeconds, 0) && !math.IsNaN(startSeconds) {
		args = append(args, "-ss", strconv.FormatFloat(startSeconds, 'f', 3, 64))
	}

	args = append(args,
		"-i", absPath,
		// Cap width at 1920, preserve aspect, force even height (H.264
		// requires even dimensions). min() keeps already-small videos
		// at native size instead of upscaling.
		"-vf", "scale='min(1920,iw)':-2",
		"-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
		"-c:a", "aac", "-b:a", "128k", "-ac", "2",
		// Fragmented MP4: frag_keyframe + empty_moov make the stream
		// playable from a pipe (no seekable moov-at-end rewrite);
		// default_base_moof improves compatibility with fMP4 parsers.
		"-movflags", "frag_keyframe+empty_moov+default_base_moof",
		"-f", "mp4", "pipe:1",
	)
	return args
}

// flushingWriter wraps an io.Writer and calls Flush() on the embedded
// http.Flusher every `threshold` bytes, so a slow/mobile client sees
// progressive fMP4 output instead of waiting on the OS/HTTP buffer.
type flushingWriter struct {
	w         io.Writer
	flusher   http.Flusher
	threshold int
	pending   int
}

func (fw *flushingWriter) Write(p []byte) (int, error) {
	n, err := fw.w.Write(p)
	fw.pending += n
	if fw.pending >= fw.threshold {
		fw.flush()
	}
	return n, err
}

func (fw *flushingWriter) flush() {
	fw.pending = 0
	if fw.flusher != nil {
		fw.flusher.Flush()
	}
}

// boundedBuffer is an io.Writer that retains at most `limit` bytes of
// what is written to it (keeping the head, dropping the overflow). Used
// to capture a bounded prefix of ffmpeg's stderr for error logging.
type boundedBuffer struct {
	buf   bytes.Buffer
	limit int
}

func (b *boundedBuffer) Write(p []byte) (int, error) {
	if remaining := b.limit - b.buf.Len(); remaining > 0 {
		if len(p) > remaining {
			b.buf.Write(p[:remaining])
		} else {
			b.buf.Write(p)
		}
	}
	// Report full length so ffmpeg's writes never short-write-error.
	return len(p), nil
}

func (b *boundedBuffer) String() string { return b.buf.String() }
