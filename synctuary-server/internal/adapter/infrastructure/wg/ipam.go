package wg

import (
	"encoding/binary"
	"fmt"
	"net"

	"github.com/synctuary/synctuary-server/internal/domain/wgpeer"
)

// Allocator manages IP address allocation within a WireGuard CIDR subnet.
type Allocator struct {
	network   *net.IPNet
	serverIP  net.IP // first usable address (the server itself)
	broadcast net.IP
}

// NewAllocator parses a CIDR string (e.g. "10.100.0.1/24") and returns
// an allocator for the subnet. The host portion of the address is used
// as the server's own IP.
func NewAllocator(cidr string) (*Allocator, error) {
	ip, network, err := net.ParseCIDR(cidr)
	if err != nil {
		return nil, fmt.Errorf("wg/ipam: parse CIDR %q: %w", cidr, err)
	}
	// Use the host address from the CIDR as server IP.
	serverIP := ip.To4()
	if serverIP == nil {
		return nil, fmt.Errorf("wg/ipam: only IPv4 CIDRs supported, got %q", cidr)
	}

	bcast := broadcastAddr(network)
	return &Allocator{
		network:   network,
		serverIP:  serverIP,
		broadcast: bcast,
	}, nil
}

// ServerIP returns the server's virtual IP address as a string.
func (a *Allocator) ServerIP() string {
	return a.serverIP.String()
}

// Subnet returns the network CIDR string (e.g. "10.100.0.0/24").
func (a *Allocator) Subnet() string {
	return a.network.String()
}

// AllocateNext finds the next available IP in the subnet, skipping
// the network address, broadcast address, server IP, and any IPs in
// the used set. Returns ErrIPExhausted if no address is available.
func (a *Allocator) AllocateNext(used []string) (string, error) {
	usedSet := make(map[string]struct{}, len(used))
	for _, ip := range used {
		usedSet[ip] = struct{}{}
	}

	// Iterate through the subnet range (network+1 .. broadcast-1).
	start := ipToUint32(a.network.IP.To4()) + 1
	end := ipToUint32(a.broadcast) // exclusive

	for i := start; i < end; i++ {
		candidate := uint32ToIP(i).String()
		if candidate == a.serverIP.String() {
			continue
		}
		if _, taken := usedSet[candidate]; taken {
			continue
		}
		return candidate, nil
	}
	return "", wgpeer.ErrIPExhausted
}

func broadcastAddr(n *net.IPNet) net.IP {
	ip := n.IP.To4()
	mask := n.Mask
	bcast := make(net.IP, 4)
	for i := 0; i < 4; i++ {
		bcast[i] = ip[i] | ^mask[i]
	}
	return bcast
}

func ipToUint32(ip net.IP) uint32 {
	return binary.BigEndian.Uint32(ip.To4())
}

func uint32ToIP(n uint32) net.IP {
	ip := make(net.IP, 4)
	binary.BigEndian.PutUint32(ip, n)
	return ip
}
