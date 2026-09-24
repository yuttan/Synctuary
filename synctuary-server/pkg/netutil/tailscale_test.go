package netutil

import (
	"net"
	"testing"
)

func TestIsTailscaleIP(t *testing.T) {
	tailnet := []string{
		"100.64.0.1",      // first address of the CGNAT range
		"100.104.8.74",    // a real tailnet address seen in server logs
		"100.127.255.254", // last usable-ish address of the range
		"fd7a:115c:a1e0::1",
		"fd7a:115c:a1e0:ab12:3456:7890:abcd:ef01",
	}
	for _, s := range tailnet {
		if !IsTailscaleIP(net.ParseIP(s)) {
			t.Errorf("IsTailscaleIP(%s) = false, want true", s)
		}
	}

	other := []string{
		"192.168.1.10",   // LAN
		"10.0.0.5",       // private
		"100.63.255.255", // just below the CGNAT range
		"100.128.0.1",    // just above the CGNAT range
		"8.8.8.8",
		"fd00::1",     // unrelated ULA
		"2001:db8::1", // GUA
		"fe80::1",     // link-local
	}
	for _, s := range other {
		if IsTailscaleIP(net.ParseIP(s)) {
			t.Errorf("IsTailscaleIP(%s) = true, want false", s)
		}
	}

	if IsTailscaleIP(nil) {
		t.Error("IsTailscaleIP(nil) = true, want false")
	}
}

func TestIsTailscaleIface(t *testing.T) {
	for _, name := range []string{"tailscale0", "Tailscale", "TAILSCALE"} {
		if !isTailscaleIface(name) {
			t.Errorf("isTailscaleIface(%q) = false, want true", name)
		}
	}
	for _, name := range []string{"eth0", "wlan0", "Ethernet", "utun3"} {
		if isTailscaleIface(name) {
			t.Errorf("isTailscaleIface(%q) = true, want false", name)
		}
	}
}

// DetectTailscaleIPs touches real interfaces, so it can only be
// asserted for shape: it must never panic and every result must be a
// parseable tailnet address.
func TestDetectTailscaleIPsShape(t *testing.T) {
	for _, s := range DetectTailscaleIPs() {
		ip := net.ParseIP(s)
		if ip == nil {
			t.Errorf("DetectTailscaleIPs returned unparseable %q", s)
			continue
		}
		if !IsTailscaleIP(ip) {
			t.Errorf("DetectTailscaleIPs returned non-tailnet %q", s)
		}
	}
}
