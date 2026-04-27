// parsers_test.go covers the standalone parser/validator helpers in
// handler.go and middleware.go that have no HTTP plumbing — pure
// functions are cheapest to exercise exhaustively.
package http

import (
	"reflect"
	"testing"
)

func TestDecodeB64URL(t *testing.T) {
	tests := []struct {
		name    string
		input   string
		want    []byte
		wantErr bool
	}{
		{"empty", "", nil, true},
		{"standard_base64_plus", "a+bc", nil, true},
		{"standard_base64_slash", "a/bc", nil, true},
		{"padded", "SGVsbG8=", nil, true},
		{"valid_b64url_hello", "SGVsbG8", []byte("Hello"), false},
		{"valid_b64url_zero_bytes_16", "AAAAAAAAAAAAAAAAAAAAAA", make([]byte, 16), false},
		{"invalid_chars", "!!!", nil, true},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got, err := decodeB64URL(tc.input)
			if (err != nil) != tc.wantErr {
				t.Fatalf("decodeB64URL(%q) error = %v, wantErr = %v", tc.input, err, tc.wantErr)
			}
			if tc.wantErr {
				return
			}
			if !reflect.DeepEqual(got, tc.want) {
				t.Errorf("decodeB64URL(%q) got = %v, want = %v", tc.input, got, tc.want)
			}
		})
	}
}

func TestParseRange(t *testing.T) {
	tests := []struct {
		name         string
		header       string
		size         int64
		wantStart    int64
		wantEnd      int64
		wantFullFile bool
		wantOk       bool
	}{
		{"empty_header", "", 200, 0, 199, true, true},
		{"bytes_0_99_size_200", "bytes=0-99", 200, 0, 99, false, true},
		{"bytes_100_open_end_size_200", "bytes=100-", 200, 100, 199, false, true},
		{"suffix_bytes_minus_50", "bytes=-50", 200, 150, 199, false, true},
		{"suffix_bigger_than_size", "bytes=-500", 200, 0, 199, false, true},
		{"end_clamped_to_size_minus_1", "bytes=50-150", 100, 50, 99, false, true},
		{"start_at_size_is_invalid", "bytes=200-300", 200, 0, 0, false, false},
		{"wrong_unit_prefix", "items=0-99", 200, 0, 0, false, false},
		{"end_lt_start", "bytes=10-5", 200, 0, 0, false, false},
		{"multi_range_unsupported", "bytes=0-50,100-150", 200, 0, 0, false, false},
		{"missing_dash", "bytes=10", 200, 0, 0, false, false},
		{"all_empty_spec", "bytes=-", 200, 0, 0, false, false},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			start, end, fullFile, ok := parseRange(tc.header, tc.size)
			if start != tc.wantStart || end != tc.wantEnd || fullFile != tc.wantFullFile || ok != tc.wantOk {
				t.Errorf("parseRange(%q, %d) = (%d, %d, %v, %v), want (%d, %d, %v, %v)",
					tc.header, tc.size, start, end, fullFile, ok,
					tc.wantStart, tc.wantEnd, tc.wantFullFile, tc.wantOk)
			}
		})
	}
}

func TestParseContentRange(t *testing.T) {
	tests := []struct {
		name      string
		header    string
		wantStart int64
		wantEnd   int64
		wantTotal int64
		wantOk    bool
	}{
		{"valid", "bytes 0-99/100", 0, 99, 100, true},
		{"end_eq_total_invalid", "bytes 0-100/100", 0, 0, 0, false},
		{"end_gt_total_invalid", "bytes 0-99/50", 0, 0, 0, false},
		{"wrong_prefix", "items 0-99/100", 0, 0, 0, false},
		{"missing_total", "bytes 0-99", 0, 0, 0, false},
		{"empty", "", 0, 0, 0, false},
		{"neg_start", "bytes -1-99/100", 0, 0, 0, false},
		{"non_numeric_total", "bytes 0-99/abc", 0, 0, 0, false},
		{"zero_total", "bytes 0-0/0", 0, 0, 0, false},
		{"single_byte", "bytes 0-0/1", 0, 0, 1, true},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			start, end, total, ok := parseContentRange(tc.header)
			if start != tc.wantStart || end != tc.wantEnd || total != tc.wantTotal || ok != tc.wantOk {
				t.Errorf("parseContentRange(%q) = (%d, %d, %d, %v), want (%d, %d, %d, %v)",
					tc.header, start, end, total, ok,
					tc.wantStart, tc.wantEnd, tc.wantTotal, tc.wantOk)
			}
		})
	}
}

func TestParseBool(t *testing.T) {
	tests := []struct {
		name     string
		input    string
		fallback bool
		want     bool
	}{
		{"true_str", "true", false, true},
		{"one", "1", false, true},
		{"yes", "yes", false, true},
		{"true_uppercase", "TRUE", false, true},
		{"false_str", "false", true, false},
		{"zero", "0", true, false},
		{"no", "no", true, false},
		{"empty_fallback_true", "", true, true},
		{"empty_fallback_false", "", false, false},
		{"garbage_fallback_true", "garbage", true, true},
		{"garbage_fallback_false", "garbage", false, false},
		{"whitespace", "  true  ", false, true},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			if got := parseBool(tc.input, tc.fallback); got != tc.want {
				t.Errorf("parseBool(%q, %v) = %v, want %v", tc.input, tc.fallback, got, tc.want)
			}
		})
	}
}

func TestIsWindowsReserved(t *testing.T) {
	tests := []struct {
		name  string
		input string
		want  bool
	}{
		{"CON", "CON", true},
		{"PRN", "PRN", true},
		{"AUX", "AUX", true},
		{"NUL", "NUL", true},
		{"COM1", "COM1", true},
		{"LPT9", "LPT9", true},
		{"CON_with_extension", "CON.txt", true},
		{"con_lowercase", "con", true},
		{"COM10_not_reserved", "COM10", false},
		{"ConSole_substring_not_match", "ConSole", false},
		{"ordinary_name", "foo", false},
		{"empty_string", "", false},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			if got := isWindowsReserved(tc.input); got != tc.want {
				t.Errorf("isWindowsReserved(%q) = %v, want %v", tc.input, got, tc.want)
			}
		})
	}
}

func TestParseBearer(t *testing.T) {
	tests := []struct {
		name      string
		header    string
		wantToken string
		wantOK    bool
	}{
		{"valid", "Bearer abc123", "abc123", true},
		{"case_insensitive_scheme", "bearer xyz", "xyz", true},
		{"mixed_case", "BeArEr xyz", "xyz", true},
		{"missing_scheme", "abc123", "", false},
		{"empty", "", "", false},
		{"scheme_only", "Bearer ", "", false},
		{"scheme_with_only_spaces", "Bearer    ", "", false},
		{"trailing_whitespace_trimmed", "Bearer  xyz  ", "xyz", true},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got, ok := parseBearer(tc.header)
			if got != tc.wantToken || ok != tc.wantOK {
				t.Errorf("parseBearer(%q) = (%q, %v), want (%q, %v)",
					tc.header, got, ok, tc.wantToken, tc.wantOK)
			}
		})
	}
}
