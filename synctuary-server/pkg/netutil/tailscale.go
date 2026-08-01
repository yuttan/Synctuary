package netutil

import (
	"net"
	"sort"
	"strings"
)

// Tailscale assigns every node an address from the CGNAT range
// 100.64.0.0/10 (IPv4) and from fd7a:115c:a1e0::/48 (its IPv6 ULA
// prefix). Detecting these lets the server show the operator a
// ready-made tailnet URL instead of asking them to look the address up
// with `tailscale ip`.
//
// Note on the IPv4 range: 100.64.0.0/10 is the shared address space
// real ISPs use for carrier-grade NAT, so on a WAN interface it would
// be ambiguous. On a host's own interface list it is, in practice,
// Tailscale (or another mesh reusing the range) — and [DetectTailscaleIPs]
// sorts addresses on Tailscale-named interfaces first so the likely
// candidate is offered before any ambiguous one.
var (
	tailscaleV4 = &net.IPNet{
		IP:   net.IPv4(100, 64, 0, 0),
		Mask: net.CIDRMask(10, 32),
	}
	tailscaleV6Prefix = "fd7a:115c:a1e0:"
)

// IsTailscaleIP reports whether ip falls in a range Tailscale assigns
// to tailnet nodes.
func IsTailscaleIP(ip net.IP) bool {
	if ip == nil {
		return false
	}
	if v4 := ip.To4(); v4 != nil {
		return tailscaleV4.Contains(v4)
	}
	return strings.HasPrefix(strings.ToLower(ip.String()), tailscaleV6Prefix)
}

// isTailscaleIface reports whether an interface name looks like one
// Tailscale created ("tailscale0" on Linux, "Tailscale" on Windows,
// "utun*" is too generic on macOS so it is not matched here).
func isTailscaleIface(name string) bool {
	return strings.Contains(strings.ToLower(name), "tailscale")
}

// DetectTailscaleIPs returns the host's tailnet addresses, IPv4 first
// and addresses on Tailscale-named interfaces before any others.
// Returns an empty slice when Tailscale is not running on the host.
func DetectTailscaleIPs() []string {
	ifaces, err := net.Interfaces()
	if err != nil {
		return nil
	}

	type candidate struct {
		ip      string
		namedIf bool
		isIPv4  bool
	}
	var found []candidate

	for _, iface := range ifaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, _ := iface.Addrs()
		for _, a := range addrs {
			inet, ok := a.(*net.IPNet)
			if !ok || !IsTailscaleIP(inet.IP) {
				continue
			}
			found = append(found, candidate{
				ip:      inet.IP.String(),
				namedIf: isTailscaleIface(iface.Name),
				isIPv4:  inet.IP.To4() != nil,
			})
		}
	}

	// Named interface wins over an ambiguous one; IPv4 before IPv6
	// because a bare 100.x address is the one users recognize.
	sort.SliceStable(found, func(i, j int) bool {
		if found[i].namedIf != found[j].namedIf {
			return found[i].namedIf
		}
		return found[i].isIPv4 && !found[j].isIPv4
	})

	out := make([]string, 0, len(found))
	seen := make(map[string]bool, len(found))
	for _, c := range found {
		if seen[c.ip] {
			continue
		}
		seen[c.ip] = true
		out = append(out, c.ip)
	}
	return out
}
