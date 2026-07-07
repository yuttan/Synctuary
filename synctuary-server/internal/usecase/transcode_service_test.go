package usecase

import (
	"bytes"
	"math"
	"strings"
	"testing"
)

// indexOf returns the position of want in args, or -1.
func indexOf(args []string, want string) int {
	for i, a := range args {
		if a == want {
			return i
		}
	}
	return -1
}

func TestBuildTranscodeArgs_NoSeek(t *testing.T) {
	args := buildTranscodeArgs("/data/movie.avi", 0)

	// No -ss when start <= 0.
	if indexOf(args, "-ss") != -1 {
		t.Fatalf("expected no -ss for start=0, got args: %v", args)
	}

	// Input path present and immediately after -i.
	iIdx := indexOf(args, "-i")
	if iIdx < 0 || iIdx+1 >= len(args) || args[iIdx+1] != "/data/movie.avi" {
		t.Fatalf("expected -i followed by input path, got args: %v", args)
	}

	// Streamable fMP4 essentials.
	for _, want := range []string{"libx264", "aac", "pipe:1"} {
		if indexOf(args, want) == -1 {
			t.Errorf("expected %q in args: %v", want, args)
		}
	}
	// Fragmented-MP4 movflags must be present for pipe output.
	mvIdx := indexOf(args, "-movflags")
	if mvIdx < 0 || mvIdx+1 >= len(args) || !strings.Contains(args[mvIdx+1], "frag_keyframe") {
		t.Errorf("expected fragmented movflags, got args: %v", args)
	}
	// Output format mp4 to a pipe.
	fIdx := indexOf(args, "-f")
	if fIdx < 0 || args[fIdx+1] != "mp4" {
		t.Errorf("expected -f mp4, got args: %v", args)
	}
}

func TestBuildTranscodeArgs_WithSeekPlacement(t *testing.T) {
	args := buildTranscodeArgs("/data/movie.wmv", 42.5)

	ssIdx := indexOf(args, "-ss")
	iIdx := indexOf(args, "-i")
	if ssIdx < 0 {
		t.Fatalf("expected -ss for positive start, got args: %v", args)
	}
	// Fast seek: -ss MUST come BEFORE -i.
	if ssIdx >= iIdx {
		t.Fatalf("expected -ss before -i (fast input seek), got -ss@%d -i@%d: %v", ssIdx, iIdx, args)
	}
	if got := args[ssIdx+1]; got != "42.500" {
		t.Errorf("expected -ss value 42.500, got %q", got)
	}
}

func TestBuildTranscodeArgs_IgnoresInvalidStart(t *testing.T) {
	// Defensive: NaN / Inf should be treated as no-seek even though the
	// handler rejects them earlier.
	for _, v := range []float64{math.NaN(), math.Inf(1), math.Inf(-1), -5} {
		args := buildTranscodeArgs("/data/x.flv", v)
		if indexOf(args, "-ss") != -1 {
			t.Errorf("expected no -ss for start=%v, got args: %v", v, args)
		}
	}
}

func TestBuildTranscodeArgs_ScaleFilter(t *testing.T) {
	args := buildTranscodeArgs("/data/x.vob", 0)
	vfIdx := indexOf(args, "-vf")
	if vfIdx < 0 || vfIdx+1 >= len(args) {
		t.Fatalf("expected -vf filter, got args: %v", args)
	}
	// Width cap + even height (-2) to satisfy H.264 constraints.
	if !strings.Contains(args[vfIdx+1], "min(1920,iw)") || !strings.Contains(args[vfIdx+1], "-2") {
		t.Errorf("expected scale filter capping width with even height, got %q", args[vfIdx+1])
	}
}

func TestBoundedBuffer_CapsAtLimit(t *testing.T) {
	b := &boundedBuffer{limit: 10}
	n, err := b.Write([]byte("0123456789ABCDEF"))
	if err != nil {
		t.Fatalf("unexpected write error: %v", err)
	}
	// Reports full length so ffmpeg never sees a short write.
	if n != 16 {
		t.Errorf("expected reported n=16, got %d", n)
	}
	if got := b.String(); got != "0123456789" {
		t.Errorf("expected head-truncated %q, got %q", "0123456789", got)
	}

	// Subsequent writes past the cap are dropped.
	if _, err := b.Write([]byte("more")); err != nil {
		t.Fatalf("unexpected write error: %v", err)
	}
	if got := b.String(); got != "0123456789" {
		t.Errorf("expected buffer unchanged after cap, got %q", got)
	}
}

// countingFlusher records how many times Flush() was called.
type countingFlusher struct {
	buf     bytes.Buffer
	flushes int
}

func (c *countingFlusher) Write(p []byte) (int, error) { return c.buf.Write(p) }
func (c *countingFlusher) Flush()                      { c.flushes++ }

func TestFlushingWriter_FlushesOnThreshold(t *testing.T) {
	cf := &countingFlusher{}
	fw := &flushingWriter{w: cf, flusher: cf, threshold: 4}

	// 3 bytes: below threshold, no flush yet.
	if _, err := fw.Write([]byte("abc")); err != nil {
		t.Fatal(err)
	}
	if cf.flushes != 0 {
		t.Errorf("expected 0 flushes below threshold, got %d", cf.flushes)
	}
	// One more byte crosses the threshold → flush.
	if _, err := fw.Write([]byte("d")); err != nil {
		t.Fatal(err)
	}
	if cf.flushes != 1 {
		t.Errorf("expected 1 flush at threshold, got %d", cf.flushes)
	}
	if got := cf.buf.String(); got != "abcd" {
		t.Errorf("expected all bytes passed through, got %q", got)
	}
	// Explicit final flush.
	fw.flush()
	if cf.flushes != 2 {
		t.Errorf("expected 2 flushes after explicit flush, got %d", cf.flushes)
	}
}
