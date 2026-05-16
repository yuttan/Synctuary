//go:build windows

package main

import (
	"bytes"
	"image"
	"image/color"
	"image/png"
	"math"
	"os/exec"

	"github.com/getlantern/systray"
)

func startTray(adminURL string, shutdown func()) {
	go systray.Run(func() {
		systray.SetIcon(generateTrayIcon())
		systray.SetTooltip("Synctuary Server")

		mSettings := systray.AddMenuItem("設定", "Open admin panel in browser")
		mQuit := systray.AddMenuItem("終了", "Shut down server")

		go func() {
			for {
				select {
				case <-mSettings.ClickedCh:
					_ = exec.Command("rundll32", "url.dll,FileProtocolHandler", adminURL).Start()
				case <-mQuit.ClickedCh:
					shutdown()
					systray.Quit()
					return
				}
			}
		}()
	}, func() {})
}

func stopTray() {
	systray.Quit()
}

// generateTrayIcon produces a 64x64 blue circle icon in ICO format.
//
// IMPORTANT: On Windows, systray.SetIcon() requires ICO format — NOT raw PNG.
// The underlying Win32 CreateIconFromResourceEx API only accepts ICO/CUR.
// Passing raw PNG bytes causes a misleading "The operation completed
// successfully" error (Win32 error code 0, but the icon load fails).
// This has bitten us twice — see CLAUDE.md §6.15.
func generateTrayIcon() []byte {
	const size = 64
	img := image.NewRGBA(image.Rect(0, 0, size, size))
	cx, cy := float64(size)/2, float64(size)/2
	r := float64(size)/2 - 2

	for y := range size {
		for x := range size {
			dx := float64(x) - cx + 0.5
			dy := float64(y) - cy + 0.5
			dist := math.Sqrt(dx*dx + dy*dy)
			if dist <= r-0.5 {
				img.SetRGBA(x, y, color.RGBA{R: 59, G: 130, B: 246, A: 255})
			} else if dist <= r+0.5 {
				a := uint8(255 * (r + 0.5 - dist))
				img.SetRGBA(x, y, color.RGBA{R: 59, G: 130, B: 246, A: a})
			}
		}
	}

	// Encode as PNG first
	var pngBuf bytes.Buffer
	_ = png.Encode(&pngBuf, img)
	pngData := pngBuf.Bytes()

	// Wrap in ICO container (PNG-in-ICO, supported since Windows Vista).
	// ICO format: 6-byte header + 16-byte directory entry + PNG payload.
	ico := &bytes.Buffer{}
	// ICONDIR header
	ico.Write([]byte{0, 0}) // reserved
	ico.Write([]byte{1, 0}) // type: 1 = ICO
	ico.Write([]byte{1, 0}) // count: 1 image
	// ICONDIRENTRY
	ico.WriteByte(byte(size)) // width (0 means 256)
	ico.WriteByte(byte(size)) // height
	ico.WriteByte(0)          // color palette count
	ico.WriteByte(0)          // reserved
	ico.Write([]byte{1, 0})   // color planes
	ico.Write([]byte{32, 0})  // bits per pixel
	// image data size (4 bytes, little-endian)
	sz := uint32(len(pngData))
	ico.Write([]byte{byte(sz), byte(sz >> 8), byte(sz >> 16), byte(sz >> 24)})
	// offset to image data (4 bytes, little-endian) = 6 + 16 = 22
	ico.Write([]byte{22, 0, 0, 0})
	// PNG payload
	ico.Write(pngData)

	return ico.Bytes()
}
