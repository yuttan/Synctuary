//go:build !windows

package main

// fatalPause is a no-op on non-Windows platforms. Console windows
// don't auto-close on Linux/macOS, so no pause is needed.
func fatalPause() {}
