//go:build windows

package main

import (
	"encoding/binary"
	"testing"
)

// TestGenerateTrayIcon_ICOFormat validates that generateTrayIcon produces
// valid ICO format, NOT raw PNG. This test exists because passing PNG to
// systray.SetIcon on Windows causes a silent failure with a misleading
// "The operation completed successfully" error. See CLAUDE.md §6.15.
func TestGenerateTrayIcon_ICOFormat(t *testing.T) {
	data := generateTrayIcon()

	// ICO must be at least 22 bytes (6 header + 16 directory entry)
	if len(data) < 22 {
		t.Fatalf("icon too short: %d bytes", len(data))
	}

	// ICO header: reserved=0, type=1 (ICO), count>=1
	if data[0] != 0 || data[1] != 0 {
		t.Fatal("ICO reserved bytes must be 0x0000")
	}
	icoType := binary.LittleEndian.Uint16(data[2:4])
	if icoType != 1 {
		t.Fatalf("ICO type must be 1, got %d", icoType)
	}
	count := binary.LittleEndian.Uint16(data[4:6])
	if count < 1 {
		t.Fatal("ICO image count must be >= 1")
	}

	// Verify it is NOT raw PNG (PNG magic: 0x89 'P' 'N' 'G')
	if len(data) >= 4 && data[0] == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G' {
		t.Fatal("generateTrayIcon returned raw PNG — Windows systray requires ICO format")
	}

	// Validate directory entry: offset + size should cover the payload
	imgSize := binary.LittleEndian.Uint32(data[14:18])
	imgOffset := binary.LittleEndian.Uint32(data[18:22])
	if uint32(len(data)) < imgOffset+imgSize {
		t.Fatalf("ICO payload truncated: file=%d, need offset(%d)+size(%d)=%d",
			len(data), imgOffset, imgSize, imgOffset+imgSize)
	}

	// The embedded payload should be PNG (PNG magic at the offset)
	if data[imgOffset] != 0x89 || data[imgOffset+1] != 'P' {
		t.Fatal("ICO payload is not PNG — expected PNG-in-ICO format")
	}
}
