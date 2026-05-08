package wg

import (
	"fmt"
	"strings"
)

// ClientConfig holds the data needed to generate a WireGuard client
// configuration file (.conf / INI format).
type ClientConfig struct {
	// Client (peer) side
	PrivateKey string // base64-encoded Curve25519 private key
	Address    string // assigned virtual IP with prefix (e.g. "10.100.0.2/24")
	DNS        string // optional DNS server

	// Server (endpoint) side
	ServerPublicKey     string // base64-encoded server public key
	Endpoint            string // host:port for the WireGuard listener
	AllowedIPs          string // what traffic to route through tunnel
	PersistentKeepalive int    // seconds; 0 = disabled
}

// ToINI renders the config in WireGuard INI format, suitable for import
// by the standard WireGuard client apps.
func (c *ClientConfig) ToINI() string {
	var b strings.Builder

	b.WriteString("[Interface]\n")
	fmt.Fprintf(&b, "PrivateKey = %s\n", c.PrivateKey)
	fmt.Fprintf(&b, "Address = %s\n", c.Address)
	if c.DNS != "" {
		fmt.Fprintf(&b, "DNS = %s\n", c.DNS)
	}

	b.WriteString("\n[Peer]\n")
	fmt.Fprintf(&b, "PublicKey = %s\n", c.ServerPublicKey)
	fmt.Fprintf(&b, "AllowedIPs = %s\n", c.AllowedIPs)
	if c.Endpoint != "" {
		fmt.Fprintf(&b, "Endpoint = %s\n", c.Endpoint)
	}
	if c.PersistentKeepalive > 0 {
		fmt.Fprintf(&b, "PersistentKeepalive = %d\n", c.PersistentKeepalive)
	}

	return b.String()
}

// BuildClientConfig constructs a ClientConfig from the server keypair,
// peer keypair, and network parameters.
func BuildClientConfig(
	serverPubKey string,
	serverEndpoint string,
	peerPrivKey string,
	assignedIP string,
	subnetPrefix int,
	persistentKeepalive int,
) *ClientConfig {
	return &ClientConfig{
		PrivateKey:          peerPrivKey,
		Address:             fmt.Sprintf("%s/%d", assignedIP, subnetPrefix),
		ServerPublicKey:     serverPubKey,
		Endpoint:            serverEndpoint,
		AllowedIPs:          fmt.Sprintf("%s/%d", assignedIP[:strings.LastIndex(assignedIP, ".")+1]+"0", subnetPrefix),
		PersistentKeepalive: persistentKeepalive,
	}
}
