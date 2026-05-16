// Package admin implements the embedded admin Web UI HTTP endpoints.
// All endpoints are under /admin/api/ and use session-based or
// config-token authentication, separate from the PROTOCOL device
// bearer auth.
package admin

import (
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/go-chi/chi/v5"

	"github.com/synctuary/synctuary-server/internal/domain/device"
	"github.com/synctuary/synctuary-server/internal/domain/share"
	"github.com/synctuary/synctuary-server/internal/usecase"
	"github.com/synctuary/synctuary-server/pkg/config"
	"github.com/synctuary/synctuary-server/pkg/netutil"
)

const maxAdminBody = 1 << 20 // 1 MiB

// configTokenSentinel is a marker value used when the admin
// authenticated via the pre-shared config token rather than a
// login-issued session.  Not a real credential.
const configTokenSentinel = "_cfg_tok_" //nolint:gosec // G101: sentinel marker, not a credential

// Handler is the admin Web UI HTTP handler.
type Handler struct {
	admin          *usecase.AdminService
	shares         *usecase.ShareService
	devices        *usecase.DeviceService
	wg             *usecase.WGService // nil when mode != "wireguard"
	log            *slog.Logger
	configToken    string
	masterKey      []byte // 32 bytes; included in pairing QR for one-scan onboarding
	tlsFingerprint []byte // 32 bytes SHA-256 of the leaf cert DER; included in pairing QR so the client can trust the self-signed cert on first contact
	listenAddr     string
	tlsEnabled     bool
	remoteAccess   config.RemoteAccessConfig

	mu          sync.RWMutex
	pendingMode string // non-empty when the user changed the mode but hasn't restarted yet
}

// HandlerConfig is the constructor input for the admin handler.
type HandlerConfig struct {
	Admin          *usecase.AdminService
	Shares         *usecase.ShareService
	Devices        *usecase.DeviceService
	WG             *usecase.WGService // nil when mode != "wireguard"
	Logger         *slog.Logger
	ConfigToken    string // optional pre-shared token for API automation
	MasterKey      []byte // 32 bytes; for pairing QR generation
	TLSFingerprint []byte // 32 bytes; SHA-256 of the leaf cert DER for QR pairing
	ListenAddr     string // e.g. ":8443"
	TLSEnabled     bool
	RemoteAccess   config.RemoteAccessConfig
}

// NewHandler validates the config and returns an admin Handler.
func NewHandler(cfg HandlerConfig) (*Handler, error) {
	if cfg.Admin == nil || cfg.Shares == nil || cfg.Devices == nil {
		return nil, fmt.Errorf("admin: missing service dependency")
	}
	if cfg.Logger == nil {
		return nil, fmt.Errorf("admin: missing logger")
	}
	return &Handler{
		admin:        cfg.Admin,
		shares:       cfg.Shares,
		devices:      cfg.Devices,
		wg:           cfg.WG,
		log:            cfg.Logger,
		configToken:    cfg.ConfigToken,
		masterKey:      cfg.MasterKey,
		tlsFingerprint: cfg.TLSFingerprint,
		listenAddr:     cfg.ListenAddr,
		tlsEnabled:     cfg.TLSEnabled,
		remoteAccess:   cfg.RemoteAccess,
	}, nil
}

// Register mounts admin API endpoints and the SPA onto r.
// Expected mount point: /admin
func (h *Handler) Register(r chi.Router) {
	r.Route("/admin", func(r chi.Router) {
		// API endpoints.
		r.Route("/api", func(r chi.Router) {
			// Unauthenticated: setup + login + session check.
			r.Post("/setup", h.Setup)
			r.Post("/login", h.Login)
			r.Get("/session", h.Session)

			// Authenticated admin endpoints.
			r.Group(func(r chi.Router) {
				r.Use(SessionAuth(h.admin, h.configToken))

				r.Post("/logout", h.Logout)

				// Devices.
				r.Get("/devices", h.DevicesList)
				r.Delete("/devices/{id}", h.DeviceRevoke)

				// Shares.
				r.Get("/shares", h.SharesList)
				r.Post("/shares", h.SharesCreate)
				r.Patch("/shares/{id}", h.SharesUpdate)
				r.Delete("/shares/{id}", h.SharesDelete)

				// Dashboard stats.
				r.Get("/stats", h.Stats)

				// Pairing info (QR code data).
				r.Get("/pairing-info", h.PairingInfo)

				// Remote access status + mode toggle.
				r.Get("/remote-access", h.RemoteAccessStatus)
				r.Put("/remote-access", h.RemoteAccessUpdate)
				r.Get("/ipv6/status", h.IPv6Status)

				// WireGuard peer management (only functional when mode == "wireguard").
				r.Route("/wireguard", func(r chi.Router) {
					r.Get("/peers", h.WGPeersList)
					r.Post("/peers", h.WGPeersAdd)
					r.Delete("/peers/{id}", h.WGPeersDelete)
				})
			})
		})

		// SPA fallback — must be last.
		r.Handle("/*", http.StripPrefix("/admin", SPAHandler()))
	})
}

// ──────────────────────────────────────────────────────────────────
// Auth endpoints
// ──────────────────────────────────────────────────────────────────

func (h *Handler) Setup(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Password string `json:"password"`
	}
	if err := decodeAdminJSON(r, &body); err != nil {
		writeAdminError(w, http.StatusBadRequest, "bad_request", err.Error())
		return
	}

	err := h.admin.Setup(r.Context(), body.Password)
	switch {
	case errors.Is(err, usecase.ErrAdminAlreadySetUp):
		writeAdminError(w, http.StatusConflict, "already_setup", "admin password already configured")
	case errors.Is(err, usecase.ErrAdminPasswordShort):
		writeAdminError(w, http.StatusBadRequest, "password_too_short", "password must be at least 8 characters")
	case err != nil:
		h.log.Error("admin setup failed", "err", err)
		writeAdminError(w, http.StatusInternalServerError, "internal", "setup failed")
	default:
		writeAdminJSON(w, http.StatusOK, map[string]any{"ok": true})
	}
}

func (h *Handler) Login(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Password string `json:"password"`
	}
	if err := decodeAdminJSON(r, &body); err != nil {
		writeAdminError(w, http.StatusBadRequest, "bad_request", err.Error())
		return
	}

	token, expiresAt, err := h.admin.Login(r.Context(), body.Password)
	switch {
	case errors.Is(err, usecase.ErrAdminNotSetUp):
		writeAdminError(w, http.StatusPreconditionFailed, "not_setup", "admin password not yet configured")
	case errors.Is(err, usecase.ErrAdminBadPassword):
		writeAdminError(w, http.StatusUnauthorized, "bad_password", "incorrect password")
	case err != nil:
		h.log.Error("admin login failed", "err", err)
		writeAdminError(w, http.StatusInternalServerError, "internal", "login failed")
	default:
		http.SetCookie(w, &http.Cookie{
			Name:     "synctuary_admin",
			Value:    token,
			Path:     "/admin",
			HttpOnly: true,
			SameSite: http.SameSiteStrictMode,
			MaxAge:   int(time.Until(time.Unix(expiresAt, 0)).Seconds()),
		})
		writeAdminJSON(w, http.StatusOK, map[string]any{
			"ok":         true,
			"expires_at": expiresAt,
		})
	}
}

func (h *Handler) Session(w http.ResponseWriter, r *http.Request) {
	isSetUp, err := h.admin.IsSetUp(r.Context())
	if err != nil {
		h.log.Error("admin session check failed", "err", err)
		writeAdminError(w, http.StatusInternalServerError, "internal", "session check failed")
		return
	}

	resp := map[string]any{
		"setup_required": !isSetUp,
		"authenticated":  false,
	}

	if c, err := r.Cookie("synctuary_admin"); err == nil {
		if verr := h.admin.ValidateSession(r.Context(), c.Value); verr == nil {
			resp["authenticated"] = true
		}
	}

	writeAdminJSON(w, http.StatusOK, resp)
}

func (h *Handler) Logout(w http.ResponseWriter, r *http.Request) {
	token := AdminTokenFromContext(r.Context())
	if token != "" && token != configTokenSentinel {
		_ = h.admin.Logout(r.Context(), token)
	}
	http.SetCookie(w, &http.Cookie{
		Name:     "synctuary_admin",
		Value:    "",
		Path:     "/admin",
		HttpOnly: true,
		MaxAge:   -1,
	})
	writeAdminJSON(w, http.StatusOK, map[string]any{"ok": true})
}

// ──────────────────────────────────────────────────────────────────
// Devices
// ──────────────────────────────────────────────────────────────────

func (h *Handler) DevicesList(w http.ResponseWriter, r *http.Request) {
	devices, err := h.devices.List(r.Context())
	if err != nil {
		h.log.Error("admin device list failed", "err", err)
		writeAdminError(w, http.StatusInternalServerError, "internal", "failed to list devices")
		return
	}
	writeAdminJSON(w, http.StatusOK, map[string]any{"devices": devices})
}

func (h *Handler) DeviceRevoke(w http.ResponseWriter, r *http.Request) {
	idHex := chi.URLParam(r, "id")
	id, err := hex.DecodeString(idHex)
	if err != nil || len(id) != 16 {
		writeAdminError(w, http.StatusBadRequest, "bad_request", "invalid device id (expected 32 hex chars)")
		return
	}

	err = h.devices.Revoke(r.Context(), id)
	switch {
	case errors.Is(err, device.ErrNotFound):
		writeAdminError(w, http.StatusNotFound, "device_not_found", "device not found")
	case err != nil:
		h.log.Error("admin device revoke failed", "err", err)
		writeAdminError(w, http.StatusInternalServerError, "internal", "revoke failed")
	default:
		writeAdminJSON(w, http.StatusOK, map[string]any{"ok": true})
	}
}

// ──────────────────────────────────────────────────────────────────
// Shares
// ──────────────────────────────────────────────────────────────────

func (h *Handler) SharesList(w http.ResponseWriter, r *http.Request) {
	shares, err := h.shares.List(r.Context())
	if err != nil {
		h.log.Error("admin share list failed", "err", err)
		writeAdminError(w, http.StatusInternalServerError, "internal", "failed to list shares")
		return
	}
	out := make([]shareResp, len(shares))
	for i, s := range shares {
		out[i] = toShareResp(s)
	}
	writeAdminJSON(w, http.StatusOK, map[string]any{"shares": out})
}

func (h *Handler) SharesCreate(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Name      string `json:"name"`
		HostPath  string `json:"host_path"`
		ReadOnly  bool   `json:"read_only"`
		Icon      string `json:"icon"`
		SortOrder int    `json:"sort_order"`
	}
	if err := decodeAdminJSON(r, &body); err != nil {
		writeAdminError(w, http.StatusBadRequest, "bad_request", err.Error())
		return
	}

	s, err := h.shares.Create(r.Context(), body.Name, body.HostPath, body.Icon, body.ReadOnly, body.SortOrder)
	switch {
	case errors.Is(err, usecase.ErrShareNameInvalid):
		writeAdminError(w, http.StatusBadRequest, "name_invalid", "share name is invalid (1-256 chars)")
	case errors.Is(err, usecase.ErrShareHostPathEmpty):
		writeAdminError(w, http.StatusBadRequest, "host_path_empty", "host path is required")
	case errors.Is(err, usecase.ErrShareHostPathNotDir):
		writeAdminError(w, http.StatusBadRequest, "host_path_not_dir", "host path must be an existing directory")
	case errors.Is(err, share.ErrDuplicate):
		writeAdminError(w, http.StatusConflict, "duplicate", "a share with this host path already exists")
	case err != nil:
		h.log.Error("admin share create failed", "err", err)
		writeAdminError(w, http.StatusInternalServerError, "internal", "create failed")
	default:
		writeAdminJSON(w, http.StatusCreated, toShareResp(*s))
	}
}

func (h *Handler) SharesUpdate(w http.ResponseWriter, r *http.Request) {
	idHex := chi.URLParam(r, "id")
	id, err := hex.DecodeString(idHex)
	if err != nil || len(id) != 16 {
		writeAdminError(w, http.StatusBadRequest, "bad_request", "invalid share id")
		return
	}

	var body struct {
		Name      *string `json:"name"`
		HostPath  *string `json:"host_path"`
		ReadOnly  *bool   `json:"read_only"`
		Icon      *string `json:"icon"`
		SortOrder *int    `json:"sort_order"`
	}
	if err := decodeAdminJSON(r, &body); err != nil {
		writeAdminError(w, http.StatusBadRequest, "bad_request", err.Error())
		return
	}

	patch := share.SharePatch{
		Name:      body.Name,
		HostPath:  body.HostPath,
		ReadOnly:  body.ReadOnly,
		Icon:      body.Icon,
		SortOrder: body.SortOrder,
	}

	err = h.shares.Update(r.Context(), id, patch)
	switch {
	case errors.Is(err, share.ErrNotFound):
		writeAdminError(w, http.StatusNotFound, "share_not_found", "share not found")
	case errors.Is(err, usecase.ErrShareEmptyPatch):
		writeAdminError(w, http.StatusBadRequest, "empty_patch", "no fields to update")
	case errors.Is(err, usecase.ErrShareNameInvalid):
		writeAdminError(w, http.StatusBadRequest, "name_invalid", "share name is invalid")
	case errors.Is(err, share.ErrHostPathInUse):
		writeAdminError(w, http.StatusConflict, "host_path_in_use", "host path already used by another share")
	case err != nil:
		h.log.Error("admin share update failed", "err", err)
		writeAdminError(w, http.StatusInternalServerError, "internal", "update failed")
	default:
		writeAdminJSON(w, http.StatusOK, map[string]any{"ok": true})
	}
}

func (h *Handler) SharesDelete(w http.ResponseWriter, r *http.Request) {
	idHex := chi.URLParam(r, "id")
	id, err := hex.DecodeString(idHex)
	if err != nil || len(id) != 16 {
		writeAdminError(w, http.StatusBadRequest, "bad_request", "invalid share id")
		return
	}

	err = h.shares.Delete(r.Context(), id)
	switch {
	case errors.Is(err, share.ErrNotFound):
		writeAdminError(w, http.StatusNotFound, "share_not_found", "share not found")
	case err != nil:
		h.log.Error("admin share delete failed", "err", err)
		writeAdminError(w, http.StatusInternalServerError, "internal", "delete failed")
	default:
		writeAdminJSON(w, http.StatusOK, map[string]any{"ok": true})
	}
}

// ──────────────────────────────────────────────────────────────────
// Stats
// ──────────────────────────────────────────────────────────────────

func (h *Handler) Stats(w http.ResponseWriter, r *http.Request) {
	devices, _ := h.devices.List(r.Context())
	shares, _ := h.shares.List(r.Context())

	activeDevices := 0
	for _, d := range devices {
		if !d.Revoked {
			activeDevices++
		}
	}

	writeAdminJSON(w, http.StatusOK, map[string]any{
		"active_devices": activeDevices,
		"total_devices":  len(devices),
		"total_shares":   len(shares),
	})
}

// ──────────────────────────────────────────────────────────────────
// Pairing
// ──────────────────────────────────────────────────────────────────

func (h *Handler) PairingInfo(w http.ResponseWriter, r *http.Request) {
	scheme := "http"
	if h.tlsEnabled {
		scheme = "https"
	}

	_, port, _ := net.SplitHostPort(h.listenAddr)
	if port == "" {
		port = "8443"
	}

	urls := h.buildServerURLs(r, scheme, port)
	primary := ""
	if len(urls) > 0 {
		primary = urls[0]
	}

	b64url := base64.RawURLEncoding
	fpHex := ""
	if len(h.tlsFingerprint) == 32 {
		fpHex = hex.EncodeToString(h.tlsFingerprint)
	}
	var pairingURIs []string
	for _, u := range urls {
		uri := "synctuary://pair?url=" + u + "&key=" + b64url.EncodeToString(h.masterKey)
		if fpHex != "" {
			uri += "&fp=" + fpHex
		}
		pairingURIs = append(pairingURIs, uri)
	}
	primaryURI := ""
	if len(pairingURIs) > 0 {
		primaryURI = pairingURIs[0]
	}

	writeAdminJSON(w, http.StatusOK, map[string]any{
		"url":          primary,
		"urls":         urls,
		"pairing_uri":  primaryURI,
		"pairing_uris": pairingURIs,
	})
}

func (h *Handler) buildServerURLs(r *http.Request, scheme, port string) []string {
	var urls []string
	seen := map[string]bool{}

	addURL := func(host string) {
		if seen[host] {
			return
		}
		seen[host] = true
		u := scheme + "://" + host
		if !netutil.IsDefaultPort(scheme, port) {
			u += ":" + port
		}
		urls = append(urls, u)
	}

	if host := r.Host; host != "" {
		h, _, err := net.SplitHostPort(host)
		if err != nil {
			h = host
		}
		if h != "" && h != "localhost" && h != "127.0.0.1" && h != "::1" {
			addURL(h)
		}
	}

	ifaces, err := net.Interfaces()
	if err == nil {
		for _, iface := range ifaces {
			if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
				continue
			}
			addrs, _ := iface.Addrs()
			for _, addr := range addrs {
				ip, _, _ := net.ParseCIDR(addr.String())
				if ip == nil || ip.IsLoopback() || ip.IsLinkLocalUnicast() {
					continue
				}
				host := ip.String()
				if strings.Contains(host, ":") {
					host = "[" + host + "]"
				}
				addURL(host)
			}
		}
	}

	return urls
}

// ──────────────────────────────────────────────────────────────────
// WireGuard Peer Management
// ──────────────────────────────────────────────────────────────────

func (h *Handler) WGPeersList(w http.ResponseWriter, r *http.Request) {
	if h.wg == nil {
		writeAdminJSON(w, http.StatusOK, map[string]any{
			"peers":   []any{},
			"enabled": false,
		})
		return
	}
	peers, err := h.wg.ListPeers(r.Context())
	if err != nil {
		h.log.Error("wg peers list", "err", err)
		writeAdminJSON(w, http.StatusInternalServerError, map[string]any{"error": "internal_error"})
		return
	}

	type peerResp struct {
		ID         string `json:"id"`
		PublicKey  string `json:"public_key"`
		AssignedIP string `json:"assigned_ip"`
		Name       string `json:"name"`
		CreatedAt  int64  `json:"created_at"`
		RevokedAt  int64  `json:"revoked_at,omitempty"`
	}
	out := make([]peerResp, 0, len(peers))
	for _, p := range peers {
		out = append(out, peerResp{
			ID:         hex.EncodeToString(p.ID),
			PublicKey:  usecase.PeerPublicKeyBase64(p.PublicKey),
			AssignedIP: p.AssignedIP,
			Name:       p.Name,
			CreatedAt:  p.CreatedAt,
			RevokedAt:  p.RevokedAt,
		})
	}
	writeAdminJSON(w, http.StatusOK, map[string]any{
		"peers":             out,
		"enabled":           true,
		"server_public_key": h.wg.ServerPublicKey(),
		"server_ip":         h.wg.ServerIP(),
	})
}

func (h *Handler) WGPeersAdd(w http.ResponseWriter, r *http.Request) {
	if h.wg == nil {
		writeAdminJSON(w, http.StatusBadRequest, map[string]any{
			"error":   "wireguard_disabled",
			"message": "WireGuard mode is not enabled",
		})
		return
	}

	var req struct {
		Name string `json:"name"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxAdminBody)).Decode(&req); err != nil {
		writeAdminJSON(w, http.StatusBadRequest, map[string]any{
			"error":   "invalid_body",
			"message": "invalid JSON body",
		})
		return
	}
	if req.Name == "" {
		req.Name = "peer"
	}

	result, err := h.wg.AddPeer(r.Context(), req.Name, nil)
	if err != nil {
		h.log.Error("wg peer add", "err", err)
		writeAdminJSON(w, http.StatusInternalServerError, map[string]any{
			"error":   "internal_error",
			"message": err.Error(),
		})
		return
	}

	writeAdminJSON(w, http.StatusCreated, map[string]any{
		"peer": map[string]any{
			"id":          hex.EncodeToString(result.Peer.ID),
			"public_key":  usecase.PeerPublicKeyBase64(result.Peer.PublicKey),
			"assigned_ip": result.Peer.AssignedIP,
			"name":        result.Peer.Name,
			"created_at":  result.Peer.CreatedAt,
		},
		"config": result.Config,
	})
}

func (h *Handler) WGPeersDelete(w http.ResponseWriter, r *http.Request) {
	if h.wg == nil {
		writeAdminJSON(w, http.StatusBadRequest, map[string]any{
			"error":   "wireguard_disabled",
			"message": "WireGuard mode is not enabled",
		})
		return
	}

	idHex := chi.URLParam(r, "id")
	id, err := hex.DecodeString(idHex)
	if err != nil || len(id) != 16 {
		writeAdminJSON(w, http.StatusBadRequest, map[string]any{
			"error":   "invalid_id",
			"message": "id must be 32 hex characters",
		})
		return
	}

	if err := h.wg.DeletePeer(r.Context(), id); err != nil {
		h.log.Error("wg peer delete", "err", err)
		writeAdminJSON(w, http.StatusNotFound, map[string]any{
			"error":   "not_found",
			"message": "peer not found",
		})
		return
	}

	writeAdminJSON(w, http.StatusOK, map[string]any{"ok": true})
}

// ──────────────────────────────────────────────────────────────────
// Remote Access Status
// ──────────────────────────────────────────────────────────────────

func (h *Handler) RemoteAccessStatus(w http.ResponseWriter, r *http.Request) {
	mode := h.remoteAccess.Mode
	status := map[string]any{
		"mode": mode,
	}

	h.mu.RLock()
	pending := h.pendingMode
	h.mu.RUnlock()
	if pending != "" && pending != mode {
		status["pending_mode"] = pending
		status["restart_required"] = true
	}

	switch mode {
	case "ipv6":
		guas := netutil.DetectIPv6GUAs()
		status["ipv6"] = map[string]any{
			"guas":            guas,
			"advertised_addr": h.remoteAccess.IPv6.AdvertisedAddress,
			"require_tls":     h.remoteAccess.IPv6.RequireTLS,
			"tls_enabled":     h.tlsEnabled,
		}
	case "wireguard":
		wgStatus := map[string]any{
			"listen_port":          h.remoteAccess.WireGuard.ListenPort,
			"address":              h.remoteAccess.WireGuard.Address,
			"mtu":                  h.remoteAccess.WireGuard.MTU,
			"persistent_keepalive": int64(h.remoteAccess.WireGuard.PersistentKeepalive.Seconds()),
		}
		if h.wg != nil {
			wgStatus["server_public_key"] = h.wg.ServerPublicKey()
			wgStatus["server_ip"] = h.wg.ServerIP()
		}
		status["wireguard"] = wgStatus
	}
	writeAdminJSON(w, http.StatusOK, status)
}

// settingKeyRemoteAccessMode is the server_meta key for the admin-
// configured remote access mode. When present, it overrides the YAML
// config on the next server restart.
const settingKeyRemoteAccessMode = "remote_access.mode"

// validModes lists the accepted values for remote_access.mode.
var validModes = map[string]bool{
	"disabled":  true,
	"ipv6":      true,
	"wireguard": true,
}

func (h *Handler) RemoteAccessUpdate(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Mode string `json:"mode"`
	}
	if err := decodeAdminJSON(r, &body); err != nil {
		writeAdminError(w, http.StatusBadRequest, "bad_request", err.Error())
		return
	}
	if !validModes[body.Mode] {
		writeAdminError(w, http.StatusBadRequest, "invalid_mode",
			"mode must be one of: disabled, ipv6, wireguard")
		return
	}

	// Persist the new mode to server_meta so it survives restarts.
	if err := h.admin.SetSetting(r.Context(), settingKeyRemoteAccessMode, body.Mode); err != nil {
		h.log.Error("save remote access mode", "err", err)
		writeAdminError(w, http.StatusInternalServerError, "internal", "failed to save mode")
		return
	}

	h.mu.Lock()
	h.pendingMode = body.Mode
	h.mu.Unlock()

	// If the new mode matches the running mode, no restart is needed.
	restartRequired := body.Mode != h.remoteAccess.Mode
	writeAdminJSON(w, http.StatusOK, map[string]any{
		"ok":               true,
		"mode":             body.Mode,
		"restart_required": restartRequired,
	})
}

func (h *Handler) IPv6Status(w http.ResponseWriter, r *http.Request) {
	guas := netutil.DetectIPv6GUAs()
	scheme := "http"
	if h.tlsEnabled {
		scheme = "https"
	}

	_, port, _ := net.SplitHostPort(h.listenAddr)
	if port == "" {
		port = "8443"
	}

	var urls []string
	addr := h.remoteAccess.IPv6.AdvertisedAddress
	if addr == "" {
		for _, g := range guas {
			host := g
			if strings.Contains(g, ":") {
				host = "[" + g + "]"
			}
			u := scheme + "://" + host
			if !netutil.IsDefaultPort(scheme, port) {
				u += ":" + port
			}
			urls = append(urls, u)
		}
	} else {
		host := addr
		if strings.Contains(addr, ":") {
			host = "[" + addr + "]"
		}
		u := scheme + "://" + host
		if !netutil.IsDefaultPort(scheme, port) {
			u += ":" + port
		}
		urls = append(urls, u)
	}

	writeAdminJSON(w, http.StatusOK, map[string]any{
		"mode":            h.remoteAccess.Mode,
		"guas":            guas,
		"advertised_addr": addr,
		"require_tls":     h.remoteAccess.IPv6.RequireTLS,
		"tls_enabled":     h.tlsEnabled,
		"scheme":          scheme,
		"urls":            urls,
	})
}

// ──────────────────────────────────────────────────────────────────
// Helpers
// ──────────────────────────────────────────────────────────────────

type shareResp struct {
	ID         string `json:"id"`
	Name       string `json:"name"`
	HostPath   string `json:"host_path"`
	ReadOnly   bool   `json:"read_only"`
	Icon       string `json:"icon"`
	SortOrder  int    `json:"sort_order"`
	IsDefault  bool   `json:"is_default"`
	CreatedAt  int64  `json:"created_at"`
	ModifiedAt int64  `json:"modified_at"`
}

func toShareResp(s share.Share) shareResp {
	return shareResp{
		ID:         hex.EncodeToString(s.ID),
		Name:       s.Name,
		HostPath:   s.HostPath,
		ReadOnly:   s.ReadOnly,
		Icon:       s.Icon,
		SortOrder:  s.SortOrder,
		IsDefault:  s.IsDefault,
		CreatedAt:  s.CreatedAt,
		ModifiedAt: s.ModifiedAt,
	}
}

func decodeAdminJSON(r *http.Request, v any) error {
	r.Body = http.MaxBytesReader(nil, r.Body, maxAdminBody)
	dec := json.NewDecoder(r.Body)
	dec.DisallowUnknownFields()
	return dec.Decode(v)
}

func writeAdminJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(v); err != nil {
		slog.Default().Error("admin: json encode failed", "err", err)
	}
}

func writeAdminError(w http.ResponseWriter, status int, code, message string) {
	writeAdminJSON(w, status, map[string]string{
		"error":   code,
		"message": message,
	})
}
