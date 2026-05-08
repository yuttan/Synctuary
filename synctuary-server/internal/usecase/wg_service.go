package usecase

import (
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"net"
	"time"

	"github.com/synctuary/synctuary-server/internal/adapter/infrastructure/crypto"
	"github.com/synctuary/synctuary-server/internal/adapter/infrastructure/wg"
	"github.com/synctuary/synctuary-server/internal/domain/wgpeer"
)

// WGService manages WireGuard peer lifecycle: creation (key generation
// + IP allocation), listing, revocation, and client config generation.
type WGService struct {
	repo      wgpeer.Repository
	alloc     *wg.Allocator
	serverKey *wg.KeyPair
	endpoint  string // external host:port for client configs
	keepalive int    // PersistentKeepalive seconds
	now       func() int64
}

// WGServiceConfig carries the constructor dependencies.
type WGServiceConfig struct {
	Repo      wgpeer.Repository
	Allocator *wg.Allocator
	ServerKey *wg.KeyPair
	Endpoint  string // e.g. "myserver.example.com:51820"
	Keepalive int    // seconds
	Now       func() int64
}

// NewWGService validates dependencies and returns a ready service.
func NewWGService(cfg WGServiceConfig) (*WGService, error) {
	if cfg.Repo == nil {
		return nil, fmt.Errorf("wg_service: missing repo")
	}
	if cfg.Allocator == nil {
		return nil, fmt.Errorf("wg_service: missing allocator")
	}
	if cfg.ServerKey == nil {
		return nil, fmt.Errorf("wg_service: missing server key")
	}
	if cfg.Now == nil {
		cfg.Now = func() int64 { return time.Now().Unix() }
	}
	return &WGService{
		repo:      cfg.Repo,
		alloc:     cfg.Allocator,
		serverKey: cfg.ServerKey,
		endpoint:  cfg.Endpoint,
		keepalive: cfg.Keepalive,
		now:       cfg.Now,
	}, nil
}

// PeerWithConfig is the result of AddPeer — it bundles the persisted
// peer record with the one-time client config (containing the private
// key, which is NOT stored on the server).
type PeerWithConfig struct {
	Peer   wgpeer.Peer
	Config string // INI format for WireGuard client
}

// AddPeer generates a keypair, allocates an IP, persists the peer, and
// returns the client config. The peer's private key is included in the
// config and is NEVER stored on the server.
func (s *WGService) AddPeer(ctx context.Context, name string, deviceID []byte) (*PeerWithConfig, error) {
	peerKP, err := wg.GenerateKeyPair()
	if err != nil {
		return nil, fmt.Errorf("wg_service: keygen: %w", err)
	}

	usedIPs, err := s.repo.AssignedIPs(ctx)
	if err != nil {
		return nil, fmt.Errorf("wg_service: list IPs: %w", err)
	}

	assignedIP, err := s.alloc.AllocateNext(usedIPs)
	if err != nil {
		return nil, fmt.Errorf("wg_service: allocate IP: %w", err)
	}

	id, err := crypto.GenerateRandomBytes(16)
	if err != nil {
		return nil, fmt.Errorf("wg_service: id entropy: %w", err)
	}

	peer := &wgpeer.Peer{
		ID:         id,
		PublicKey:  peerKP.PublicKey[:],
		AssignedIP: assignedIP,
		Name:       name,
		DeviceID:   deviceID,
		CreatedAt:  s.now(),
	}

	// Retry once on the unlikely ID collision.
	for attempt := 0; attempt < 2; attempt++ {
		if err := s.repo.Create(ctx, peer); err != nil {
			if errors.Is(err, wgpeer.ErrDuplicate) && attempt == 0 {
				id, _ = crypto.GenerateRandomBytes(16)
				peer.ID = id
				continue
			}
			return nil, fmt.Errorf("wg_service: create peer: %w", err)
		}
		break
	}

	// Build client config.
	_, subnet, _ := net.ParseCIDR(s.alloc.Subnet())
	ones, _ := subnet.Mask.Size()

	cfg := wg.BuildClientConfig(
		s.serverKey.PublicKeyBase64(),
		s.endpoint,
		peerKP.PrivateKeyBase64(),
		assignedIP,
		ones,
		s.keepalive,
	)

	return &PeerWithConfig{
		Peer:   *peer,
		Config: cfg.ToINI(),
	}, nil
}

// ListPeers returns all peers (including revoked) for admin display.
func (s *WGService) ListPeers(ctx context.Context) ([]wgpeer.Peer, error) {
	return s.repo.ListAll(ctx)
}

// ListActivePeers returns only non-revoked peers (for WireGuard device config).
func (s *WGService) ListActivePeers(ctx context.Context) ([]wgpeer.Peer, error) {
	return s.repo.ListActive(ctx)
}

// GetPeer returns a single peer by ID.
func (s *WGService) GetPeer(ctx context.Context, id []byte) (*wgpeer.Peer, error) {
	return s.repo.GetByID(ctx, id)
}

// RevokePeer soft-deletes a peer. The peer's IP is freed for reuse.
func (s *WGService) RevokePeer(ctx context.Context, id []byte) error {
	return s.repo.Revoke(ctx, id, s.now())
}

// DeletePeer hard-removes a peer from the database.
func (s *WGService) DeletePeer(ctx context.Context, id []byte) error {
	return s.repo.Delete(ctx, id)
}

// GetPeerConfig regenerates the INI config for an existing peer.
// NOTE: This only works if the admin UI still has the private key from
// the initial AddPeer call. The server does NOT store private keys, so
// the caller must supply peerPrivKeyB64.
func (s *WGService) GetPeerConfig(ctx context.Context, id []byte, peerPrivKeyB64 string) (string, error) {
	peer, err := s.repo.GetByID(ctx, id)
	if err != nil {
		return "", fmt.Errorf("wg_service: get peer: %w", err)
	}

	_, subnet, _ := net.ParseCIDR(s.alloc.Subnet())
	ones, _ := subnet.Mask.Size()

	cfg := wg.BuildClientConfig(
		s.serverKey.PublicKeyBase64(),
		s.endpoint,
		peerPrivKeyB64,
		peer.AssignedIP,
		ones,
		s.keepalive,
	)
	return cfg.ToINI(), nil
}

// ServerPublicKey returns the server's Curve25519 public key in base64.
func (s *WGService) ServerPublicKey() string {
	return s.serverKey.PublicKeyBase64()
}

// ServerIP returns the server's virtual IP within the WireGuard subnet.
func (s *WGService) ServerIP() string {
	return s.alloc.ServerIP()
}

// PeerPublicKeyBase64 returns the base64 encoding of a 32-byte public key.
func PeerPublicKeyBase64(pub []byte) string {
	return base64.StdEncoding.EncodeToString(pub)
}
