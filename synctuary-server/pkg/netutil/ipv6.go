// Package netutil provides network utility functions shared across
// the server binary and HTTP handlers.
package netutil

import "net"

// DetectIPv6GUAs enumerates network interfaces and returns all IPv6
// Global Unicast Addresses (fe80:: link-local excluded). It filters for
// interfaces that are up, have multicast capability, and are not loopback.
func DetectIPv6GUAs() []string {
	var result []string
	ifaces, err := net.Interfaces()
	if err != nil {
		return result
	}
	for _, iface := range ifaces {
		flags := iface.Flags
		if (flags&net.FlagUp) == 0 || (flags&net.FlagMulticast) == 0 || (flags&net.FlagLoopback) != 0 {
			continue
		}
		addrs, _ := iface.Addrs()
		for _, a := range addrs {
			if inet, ok := a.(*net.IPNet); ok && inet.IP.To4() == nil && !inet.IP.IsLinkLocalUnicast() {
				result = append(result, inet.IP.String())
			}
		}
	}
	return result
}

// IsDefaultPort returns true if scheme+port is a standard combination
// (https:443, http:80) that can be omitted from URLs.
func IsDefaultPort(scheme, port string) bool {
	return (scheme == "https" && port == "443") || (scheme == "http" && port == "80")
}
