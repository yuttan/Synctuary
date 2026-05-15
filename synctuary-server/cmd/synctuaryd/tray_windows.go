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

	var buf bytes.Buffer
	_ = png.Encode(&buf, img)
	return buf.Bytes()
}
