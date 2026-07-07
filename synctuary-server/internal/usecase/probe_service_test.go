package usecase

import "testing"

func TestBuildProbeArgs(t *testing.T) {
	args := buildProbeArgs("/data/movie.wmv")

	// Quiet + first video stream only.
	if indexOf(args, "-v") == -1 {
		t.Errorf("expected -v flag, got args: %v", args)
	}
	ssIdx := indexOf(args, "-select_streams")
	if ssIdx < 0 || ssIdx+1 >= len(args) || args[ssIdx+1] != "v:0" {
		t.Errorf("expected -select_streams v:0, got args: %v", args)
	}

	// JSON output format.
	ofIdx := indexOf(args, "-of")
	if ofIdx < 0 || ofIdx+1 >= len(args) || args[ofIdx+1] != "json" {
		t.Errorf("expected -of json, got args: %v", args)
	}

	// Input path must be the final argument (positional, after all flags).
	if got := args[len(args)-1]; got != "/data/movie.wmv" {
		t.Errorf("expected input path last, got %q in %v", got, args)
	}

	// Requests both stream dimensions and container duration.
	var haveStream, haveFormat bool
	for i, a := range args {
		if a == "-show_entries" && i+1 < len(args) {
			switch args[i+1] {
			case "stream=width,height":
				haveStream = true
			case "format=duration":
				haveFormat = true
			}
		}
	}
	if !haveStream {
		t.Errorf("expected -show_entries stream=width,height, got args: %v", args)
	}
	if !haveFormat {
		t.Errorf("expected -show_entries format=duration, got args: %v", args)
	}
}

func TestParseProbeOutput_Video(t *testing.T) {
	// format.duration is a STRING in ffprobe output.
	const canned = `{
		"streams": [{"width": 1920, "height": 1080}],
		"format": {"duration": "123.456000"}
	}`

	info, err := parseProbeOutput([]byte(canned))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if info.DurationSeconds != 123.456 {
		t.Errorf("expected duration 123.456, got %v", info.DurationSeconds)
	}
	if info.Width != 1920 || info.Height != 1080 {
		t.Errorf("expected 1920x1080, got %dx%d", info.Width, info.Height)
	}
}

func TestParseProbeOutput_AudioOnly(t *testing.T) {
	// No video stream selected (audio-only file): dimensions default to 0.
	const canned = `{"streams": [], "format": {"duration": "42.000000"}}`

	info, err := parseProbeOutput([]byte(canned))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if info.DurationSeconds != 42.0 {
		t.Errorf("expected duration 42, got %v", info.DurationSeconds)
	}
	if info.Width != 0 || info.Height != 0 {
		t.Errorf("expected 0x0 for audio-only, got %dx%d", info.Width, info.Height)
	}
}

func TestParseProbeOutput_MissingDuration(t *testing.T) {
	// A container with no reported duration → 0 (client treats as unknown),
	// not a hard error.
	const canned = `{"streams": [{"width": 640, "height": 480}], "format": {}}`

	info, err := parseProbeOutput([]byte(canned))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if info.DurationSeconds != 0 {
		t.Errorf("expected duration 0 for missing duration, got %v", info.DurationSeconds)
	}
	if info.Width != 640 || info.Height != 480 {
		t.Errorf("expected 640x480, got %dx%d", info.Width, info.Height)
	}
}

func TestParseProbeOutput_UnparseableDuration(t *testing.T) {
	// A non-numeric duration string is tolerated as 0, not an error.
	const canned = `{"streams": [], "format": {"duration": "N/A"}}`

	info, err := parseProbeOutput([]byte(canned))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if info.DurationSeconds != 0 {
		t.Errorf("expected duration 0 for N/A, got %v", info.DurationSeconds)
	}
}

func TestParseProbeOutput_InvalidJSON(t *testing.T) {
	if _, err := parseProbeOutput([]byte("not json")); err == nil {
		t.Error("expected error for invalid JSON, got nil")
	}
}
