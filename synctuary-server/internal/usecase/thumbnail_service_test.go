package usecase

import (
	"math"
	"testing"
)

// TestBuildVideoThumbArgs_DefaultMark verifies the historical behavior:
// t <= 0 extracts a frame at the fixed 1s mark, with input-side -ss.
func TestBuildVideoThumbArgs_DefaultMark(t *testing.T) {
	args := buildVideoThumbArgs("/data/clip.mp4", 0)

	ssIdx := indexOf(args, "-ss")
	iIdx := indexOf(args, "-i")
	if ssIdx < 0 || iIdx < 0 {
		t.Fatalf("expected -ss and -i, got args: %v", args)
	}
	// Fast seek: -ss MUST come BEFORE -i.
	if ssIdx >= iIdx {
		t.Fatalf("expected -ss before -i (fast input seek), got -ss@%d -i@%d: %v", ssIdx, iIdx, args)
	}
	if got := args[ssIdx+1]; got != "1" {
		t.Errorf("expected default -ss value 1, got %q", got)
	}
	// Input path immediately after -i.
	if args[iIdx+1] != "/data/clip.mp4" {
		t.Errorf("expected -i followed by input path, got args: %v", args)
	}
	// Single-frame PNG on stdout.
	for _, want := range []string{"-vframes", "image2pipe", "png", "-"} {
		if indexOf(args, want) == -1 {
			t.Errorf("expected %q in args: %v", want, args)
		}
	}
}

// TestBuildVideoThumbArgs_WithTimestamp verifies a positive t seeks to
// that timestamp, formatted with 3 decimals, still input-side.
func TestBuildVideoThumbArgs_WithTimestamp(t *testing.T) {
	args := buildVideoThumbArgs("/data/movie.mkv", 42.5)

	ssIdx := indexOf(args, "-ss")
	iIdx := indexOf(args, "-i")
	if ssIdx < 0 {
		t.Fatalf("expected -ss for positive t, got args: %v", args)
	}
	if ssIdx >= iIdx {
		t.Fatalf("expected -ss before -i, got -ss@%d -i@%d: %v", ssIdx, iIdx, args)
	}
	if got := args[ssIdx+1]; got != "42.500" {
		t.Errorf("expected -ss value 42.500, got %q", got)
	}
}

// TestBuildVideoThumbArgs_SubSecond verifies sub-second precision is
// preserved to 3 decimals.
func TestBuildVideoThumbArgs_SubSecond(t *testing.T) {
	args := buildVideoThumbArgs("/data/x.mp4", 3.125)
	ssIdx := indexOf(args, "-ss")
	if ssIdx < 0 {
		t.Fatalf("expected -ss, got args: %v", args)
	}
	if got := args[ssIdx+1]; got != "3.125" {
		t.Errorf("expected -ss value 3.125, got %q", got)
	}
}

// TestBuildVideoThumbArgs_InvalidTimestamp verifies NaN / Inf / negative
// values defensively fall back to the 1s default (the handler rejects
// them earlier with a 400).
func TestBuildVideoThumbArgs_InvalidTimestamp(t *testing.T) {
	for _, v := range []float64{math.NaN(), math.Inf(1), math.Inf(-1), -5} {
		args := buildVideoThumbArgs("/data/x.mp4", v)
		ssIdx := indexOf(args, "-ss")
		if ssIdx < 0 {
			t.Fatalf("expected -ss for t=%v, got args: %v", v, args)
		}
		if got := args[ssIdx+1]; got != "1" {
			t.Errorf("expected default -ss value 1 for t=%v, got %q", v, got)
		}
	}
}
