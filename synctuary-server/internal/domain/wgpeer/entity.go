// Package wgpeer models a WireGuard VPN peer — a device that can
// connect to the server through the built-in userspace WireGuard tunnel.
//
// Each peer holds a Curve25519 public key and a server-assigned virtual
// IP within the configured CIDR. The optional DeviceID foreign key lets
// the admin correlate peers with paired Synctuary devices, but is not
// enforced — a WireGuard peer can exist independently of device pairing.
package wgpeer

// Peer is one row in the wg_peers table (migration 006_wg_peers.sql).
type Peer struct {
	// ID is the server-issued 16-byte opaque identifier.
	ID []byte

	// PublicKey is the Curve25519 public key (32 bytes).
	PublicKey []byte

	// AssignedIP is the virtual IP address allocated from the WireGuard
	// subnet CIDR (e.g. "10.100.0.2").
	AssignedIP string

	// Name is the admin-supplied display label for the peer.
	Name string

	// DeviceID optionally links this peer to a paired Synctuary device
	// (16-byte FK → devices.device_id). nil means unlinked.
	DeviceID []byte

	// CreatedAt is the unix epoch seconds when the peer was added.
	CreatedAt int64

	// RevokedAt is non-zero when the peer has been revoked. Revoked
	// peers are excluded from the active WireGuard configuration.
	RevokedAt int64
}

// IsRevoked returns true when the peer has been soft-deleted.
func (p *Peer) IsRevoked() bool {
	return p.RevokedAt != 0
}
