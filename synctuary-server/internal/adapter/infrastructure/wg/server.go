package wg

import (
	"encoding/hex"
	"fmt"
	"log/slog"
	"net"
	"net/netip"
	"sync"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

// Server manages a userspace WireGuard device backed by gvisor netstack.
// It exposes a net.Listener on the virtual TUN interface so that the
// same HTTP router can serve both LAN and VPN clients without any
// application-layer changes.
//
// No CAP_NET_ADMIN or kernel TUN device is required — the WireGuard
// protocol runs entirely in userspace using the gvisor TCP/IP stack.
type Server struct {
	dev      *device.Device
	tnet     *netstack.Net
	listener net.Listener
	logger   *slog.Logger

	mu   sync.Mutex
	done bool
}

// ServerConfig carries the parameters for NewServer.
type ServerConfig struct {
	// ServerKey is the server's Curve25519 keypair (loaded or generated
	// by LoadOrGenerateServerKey).
	ServerKey *KeyPair

	// ListenPort is the UDP port for the WireGuard listener (default 51820).
	ListenPort int

	// Address is the server's virtual IP in CIDR notation (e.g. "10.100.0.1/24").
	Address string

	// MTU for the virtual TUN interface (default 1420).
	MTU int

	// HTTPPort is the TCP port to listen on inside the tunnel.
	// The same port as the external HTTPS listener (typically 8443).
	HTTPPort int

	// Logger for structured logging.
	Logger *slog.Logger
}

// NewServer creates a userspace WireGuard device and starts listening
// for TCP connections on the virtual interface. Call Listener() to get
// the net.Listener, then Serve it with your HTTP handler.
//
// Peers must be added via SetPeers or AddPeer before any client can
// connect through the tunnel.
func NewServer(cfg ServerConfig) (*Server, error) {
	if cfg.ServerKey == nil {
		return nil, fmt.Errorf("wg/server: missing server key")
	}
	if cfg.ListenPort <= 0 {
		cfg.ListenPort = 51820
	}
	if cfg.MTU <= 0 {
		cfg.MTU = 1420
	}
	if cfg.HTTPPort <= 0 {
		cfg.HTTPPort = 8443
	}
	if cfg.Logger == nil {
		cfg.Logger = slog.Default()
	}

	// Parse the server address from CIDR.
	prefix, err := netip.ParsePrefix(cfg.Address)
	if err != nil {
		return nil, fmt.Errorf("wg/server: parse address %q: %w", cfg.Address, err)
	}

	// Create the gvisor-backed TUN device. This gives us a virtual
	// network stack that speaks IP without touching the kernel.
	tun, tnet, err := netstack.CreateNetTUN(
		[]netip.Addr{prefix.Addr()},
		nil, // no DNS servers needed — we only serve HTTP
		cfg.MTU,
	)
	if err != nil {
		return nil, fmt.Errorf("wg/server: create netstack tun: %w", err)
	}

	// Bridge wireguard-go logging to slog.
	devLogger := &device.Logger{
		Verbosef: func(format string, args ...any) {
			cfg.Logger.Debug(fmt.Sprintf(format, args...), "component", "wireguard")
		},
		Errorf: func(format string, args ...any) {
			cfg.Logger.Error(fmt.Sprintf(format, args...), "component", "wireguard")
		},
	}

	// Create the WireGuard device with a standard UDP bind.
	dev := device.NewDevice(tun, conn.NewDefaultBind(), devLogger)

	// Configure the device via the IPC protocol: set private key and
	// listen port. Peers are added separately via SetPeers/AddPeer.
	ipcConfig := fmt.Sprintf(
		"private_key=%s\nlisten_port=%d\n",
		hex.EncodeToString(cfg.ServerKey.PrivateKey[:]),
		cfg.ListenPort,
	)
	if err := dev.IpcSet(ipcConfig); err != nil {
		dev.Close()
		return nil, fmt.Errorf("wg/server: ipc set config: %w", err)
	}

	// Bring the device up — it starts accepting WireGuard handshakes.
	if err := dev.Up(); err != nil {
		dev.Close()
		return nil, fmt.Errorf("wg/server: device up: %w", err)
	}

	// Listen for TCP connections on the virtual interface. VPN clients
	// connect to https://<server_virtual_ip>:<HTTPPort>.
	listener, err := tnet.ListenTCP(&net.TCPAddr{Port: cfg.HTTPPort})
	if err != nil {
		dev.Close()
		return nil, fmt.Errorf("wg/server: listen tcp %d: %w", cfg.HTTPPort, err)
	}

	cfg.Logger.Info("wireguard tunnel active",
		"listen_port", cfg.ListenPort,
		"virtual_ip", prefix.Addr().String(),
		"http_port", cfg.HTTPPort,
		"mtu", cfg.MTU,
	)

	return &Server{
		dev:      dev,
		tnet:     tnet,
		listener: listener,
		logger:   cfg.Logger,
	}, nil
}

// Listener returns the TCP listener on the virtual WireGuard interface.
// Pass this to http.Serve or http.ServeTLS alongside your router.
func (s *Server) Listener() net.Listener {
	return s.listener
}

// PeerConfig describes a WireGuard peer for IPC configuration.
type PeerConfig struct {
	PublicKey [32]byte
	AllowedIP string // single IP, e.g. "10.100.0.2/32"
}

// SetPeers replaces the entire peer list on the WireGuard device.
// This is the preferred method during startup when loading all active
// peers from the database.
func (s *Server) SetPeers(peers []PeerConfig) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.done {
		return fmt.Errorf("wg/server: device closed")
	}

	// Build IPC config: first remove all peers, then add each one.
	ipc := ""
	for _, p := range peers {
		ipc += fmt.Sprintf(
			"public_key=%s\nreplace_allowed_ips=true\nallowed_ip=%s\n",
			hex.EncodeToString(p.PublicKey[:]),
			p.AllowedIP,
		)
	}

	if err := s.dev.IpcSet(ipc); err != nil {
		return fmt.Errorf("wg/server: set peers: %w", err)
	}

	s.logger.Info("wireguard peers configured", "count", len(peers))
	return nil
}

// AddPeer adds a single peer to the WireGuard device. Use this when a
// new peer is created through the admin UI.
func (s *Server) AddPeer(p PeerConfig) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.done {
		return fmt.Errorf("wg/server: device closed")
	}

	ipc := fmt.Sprintf(
		"public_key=%s\nreplace_allowed_ips=true\nallowed_ip=%s\n",
		hex.EncodeToString(p.PublicKey[:]),
		p.AllowedIP,
	)

	if err := s.dev.IpcSet(ipc); err != nil {
		return fmt.Errorf("wg/server: add peer: %w", err)
	}

	s.logger.Debug("wireguard peer added",
		"public_key", hex.EncodeToString(p.PublicKey[:8])+"...",
		"allowed_ip", p.AllowedIP,
	)
	return nil
}

// RemovePeer removes a peer from the WireGuard device by public key.
func (s *Server) RemovePeer(publicKey [32]byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.done {
		return fmt.Errorf("wg/server: device closed")
	}

	ipc := fmt.Sprintf(
		"public_key=%s\nremove=true\n",
		hex.EncodeToString(publicKey[:]),
	)

	if err := s.dev.IpcSet(ipc); err != nil {
		return fmt.Errorf("wg/server: remove peer: %w", err)
	}

	s.logger.Debug("wireguard peer removed",
		"public_key", hex.EncodeToString(publicKey[:8])+"...",
	)
	return nil
}

// Close gracefully shuts down the WireGuard device, listener, and
// virtual network stack.
func (s *Server) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.done {
		return nil
	}
	s.done = true

	var firstErr error
	if err := s.listener.Close(); err != nil && firstErr == nil {
		firstErr = fmt.Errorf("wg/server: close listener: %w", err)
	}
	s.dev.Close()
	s.logger.Info("wireguard tunnel stopped")
	return firstErr
}

// AddTunnelPeer satisfies usecase.TunnelPeerSyncer — it wraps AddPeer
// with the /32 suffix convention used by WireGuard allowed-ips.
func (s *Server) AddTunnelPeer(publicKey [32]byte, allowedIP string) error {
	return s.AddPeer(PeerConfig{
		PublicKey: publicKey,
		AllowedIP: allowedIP + "/32",
	})
}

// RemoveTunnelPeer satisfies usecase.TunnelPeerSyncer.
func (s *Server) RemoveTunnelPeer(publicKey [32]byte) error {
	return s.RemovePeer(publicKey)
}
