//go:build !windows

package main

func startTray(_ string, _ func()) {}

func stopTray() {}
