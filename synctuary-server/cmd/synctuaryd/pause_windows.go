//go:build windows

package main

import (
	"fmt"
	"os"
	"syscall"
	"unsafe"
)

// fatalPause pauses for user input when the server was launched by
// double-clicking in Explorer (no parent console). Without this the
// console window closes instantly and the error message is unreadable.
//
// Detection: GetConsoleProcessList returns the number of PIDs attached
// to the current console. When launched from Explorer a new console is
// created with only our own PID; when launched from cmd/PowerShell the
// shell's PID is also present (count ≥ 2).
func fatalPause() {
	if !isExplorerLaunch() {
		return
	}
	fmt.Fprintln(os.Stderr, "")
	fmt.Fprintln(os.Stderr, "Press Enter to exit...")
	buf := make([]byte, 1)
	os.Stdin.Read(buf) //nolint:errcheck // best-effort pause
}

func isExplorerLaunch() bool {
	kernel32 := syscall.NewLazyDLL("kernel32.dll")
	proc := kernel32.NewProc("GetConsoleProcessList")
	var pids [4]uint32
	ret, _, _ := proc.Call(uintptr(unsafe.Pointer(&pids[0])), 4)
	// ret == 1 means only our process owns the console → Explorer launch.
	return ret == 1
}
