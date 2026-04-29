// Package http — handler.go wires the HTTP endpoint set defined in
// PROTOCOL v0.2.2 (§4 pair, §5 info, §6 files, §7 devices) onto the
// usecase layer. Every binary-valued JSON field is base64url-without-
// padding per §1; the handler is responsible for decode/encode, the
// usecases never see transport encoding.
package http

import (
	"crypto/ed25519"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"path"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"

	"github.com/go-chi/chi/v5"

	"github.com/synctuary/synctuary-server/internal/domain/device"
	dfile "github.com/synctuary/synctuary-server/internal/domain/file"
	"github.com/synctuary/synctuary-server/internal/usecase"
)

// base64url-without-padding codec per PROTOCOL §1. Standard base64
// (`+`/`/`) and padded inputs MUST be rejected.
var b64url = base64.RawURLEncoding

// maxJSONBody caps request bodies for JSON endpoints. Upload chunks
// use their own raw-body path and bypass this limit.
const maxJSONBody = 1 << 20 // 1 MiB

// Handler is the chi-compatible aggregate of endpoint handlers. Each
// method binds to a route in Register.
type Handler struct {
	pairing   *usecase.PairingService
	files     *usecase.FileService
	devices   *usecase.DeviceService
	favorites *usecase.FavoriteService
	deviceRP  device.Repository

	log *slog.Logger

	// info fields (PROTOCOL §5) — populated at startup by the daemon.
	serverID         []byte // 16 bytes
	serverName       string
	encryptionMode   string // "standard" in v0.2
	transportProfile string
	tlsFingerprint   []byte // 32 bytes or nil
	serverVersion    string
	protocolVersion  string
	commit           string // ldflags-injectable; "unknown" if unset
	capabilities     map[string]bool
}

// HandlerConfig is the constructor input.
type HandlerConfig struct {
	Pairing          *usecase.PairingService
	Files            *usecase.FileService
	Devices          *usecase.DeviceService
	Favorites        *usecase.FavoriteService
	DeviceRepo       device.Repository
	Logger           *slog.Logger
	ServerID         []byte
	ServerName       string
	EncryptionMode   string
	TransportProfile string
	TLSFingerprint   []byte
	ServerVersion    string
	ProtocolVersion  string
	Commit           string // build-time git SHA; "unknown" if not injected
	Capabilities     map[string]bool
}

// NewHandler validates the config and returns a Handler ready to mount.
func NewHandler(cfg HandlerConfig) (*Handler, error) {
	if cfg.Pairing == nil || cfg.Files == nil || cfg.Devices == nil || cfg.Favorites == nil || cfg.DeviceRepo == nil {
		return nil, fmt.Errorf("http: missing usecase dependency")
	}
	if cfg.Logger == nil {
		return nil, fmt.Errorf("http: missing logger")
	}
	if len(cfg.ServerID) != 16 {
		return nil, fmt.Errorf("http: server_id must be 16 bytes, got %d", len(cfg.ServerID))
	}
	return &Handler{
		pairing:          cfg.Pairing,
		files:            cfg.Files,
		devices:          cfg.Devices,
		favorites:        cfg.Favorites,
		deviceRP:         cfg.DeviceRepo,
		log:              cfg.Logger,
		serverID:         cfg.ServerID,
		serverName:       cfg.ServerName,
		encryptionMode:   cfg.EncryptionMode,
		transportProfile: cfg.TransportProfile,
		tlsFingerprint:   cfg.TLSFingerprint,
		serverVersion:    cfg.ServerVersion,
		protocolVersion:  cfg.ProtocolVersion,
		commit:           cfg.Commit,
		capabilities:     cfg.Capabilities,
	}, nil
}

// Register binds all PROTOCOL routes onto r. Authenticated routes are
// wrapped with BearerAuth; info/pair endpoints are left open (§4, §5).
func (h *Handler) Register(r chi.Router) {
	// Unauthenticated.
	r.Get("/api/v1/info", h.Info)
	r.Post("/api/v1/pair/nonce", h.PairNonce)
	r.Post("/api/v1/pair/register", h.PairRegister)

	// Authenticated: bearer-auth middleware wraps this subtree.
	r.Group(func(r chi.Router) {
		r.Use(BearerAuth(h.deviceRP, h.log))

		r.Get("/api/v1/files", h.FilesList)
		r.Delete("/api/v1/files", h.FilesDelete)
		r.Get("/api/v1/files/content", h.FilesContent)

		r.Post("/api/v1/files/upload/init", h.UploadInit)
		r.Put("/api/v1/files/upload/{id}", h.UploadChunk)
		r.Get("/api/v1/files/upload/{id}", h.UploadProgress)
		r.Delete("/api/v1/files/upload/{id}", h.UploadAbort)

		r.Post("/api/v1/files/move", h.FilesMove)

		r.Get("/api/v1/devices", h.DevicesList)
		r.Delete("/api/v1/devices/{id}", h.DeviceRevoke)

		// PROTOCOL §8 favorites.
		r.Get("/api/v1/favorites", h.FavoritesList)
		r.Post("/api/v1/favorites", h.FavoriteCreate)
		r.Get("/api/v1/favorites/{id}", h.FavoriteGet)
		r.Patch("/api/v1/favorites/{id}", h.FavoritePatch)
		r.Delete("/api/v1/favorites/{id}", h.FavoriteDelete)
		r.Post("/api/v1/favorites/{id}/items", h.FavoriteItemAdd)
		r.Delete("/api/v1/favorites/{id}/items", h.FavoriteItemRemove)
	})
}

// ──────────────────────────────────────────────────────────────────
// §5.1 GET /api/v1/info
// ──────────────────────────────────────────────────────────────────

func (h *Handler) Info(w http.ResponseWriter, r *http.Request) {
	body := map[string]any{
		"protocol_version":  h.protocolVersion,
		"server_version":    h.serverVersion,
		"server_id":         b64url.EncodeToString(h.serverID),
		"server_name":       h.serverName,
		"encryption_mode":   h.encryptionMode,
		"transport_profile": h.transportProfile,
		"capabilities":      h.capabilities,
	}
	if len(h.tlsFingerprint) == 32 {
		body["tls_fingerprint"] = hex.EncodeToString(h.tlsFingerprint)
	}
	// Build-time commit SHA — only emit when an injected value is present
	// so devs running bare `go build` don't see a noisy "unknown" field.
	if h.commit != "" && h.commit != "unknown" {
		body["commit"] = h.commit
	}
	WriteJSON(w, http.StatusOK, body)
}

// ──────────────────────────────────────────────────────────────────
// §4.2 POST /api/v1/pair/nonce
// ──────────────────────────────────────────────────────────────────

func (h *Handler) PairNonce(w http.ResponseWriter, r *http.Request) {
	resp, retryAfter, err := h.pairing.IssueNonce(r.Context(), clientIP(r))
	if err != nil {
		switch {
		case errors.Is(err, usecase.ErrRateLimited):
			if retryAfter > 0 {
				w.Header().Set("Retry-After", strconv.FormatInt(retryAfter, 10))
			}
			WriteError(w, http.StatusTooManyRequests, "rate_limited", "pairing rate limit exceeded")
		default:
			h.log.Error("pair nonce", slog.String("err", err.Error()))
			WriteError(w, http.StatusInternalServerError, "internal_error", "nonce issue failed")
		}
		return
	}
	WriteJSON(w, http.StatusOK, map[string]any{
		"nonce":      b64url.EncodeToString(resp.Nonce),
		"expires_at": resp.ExpiresAt,
	})
}

// ──────────────────────────────────────────────────────────────────
// §4.3 POST /api/v1/pair/register
// ──────────────────────────────────────────────────────────────────

type pairRegisterBody struct {
	Nonce             string `json:"nonce"`
	DeviceID          string `json:"device_id"`
	DevicePub         string `json:"device_pub"`
	DeviceName        string `json:"device_name"`
	Platform          string `json:"platform"`
	ChallengeResponse string `json:"challenge_response"`
}

func (h *Handler) PairRegister(w http.ResponseWriter, r *http.Request) {
	var body pairRegisterBody
	if !decodeJSON(w, r, &body) {
		return
	}
	nonceB, err1 := decodeB64URL(body.Nonce)
	devID, err2 := decodeB64URL(body.DeviceID)
	devPub, err3 := decodeB64URL(body.DevicePub)
	sig, err4 := decodeB64URL(body.ChallengeResponse)
	if err1 != nil || err2 != nil || err3 != nil || err4 != nil {
		WriteError(w, http.StatusBadRequest, "bad_request", "invalid base64url field")
		return
	}
	req := &usecase.RegisterRequest{
		Nonce:             nonceB,
		DeviceID:          devID,
		DevicePub:         ed25519.PublicKey(devPub),
		DeviceName:        body.DeviceName,
		Platform:          body.Platform,
		ChallengeResponse: sig,
	}
	res, err := h.pairing.Register(r.Context(), req)
	if err != nil {
		switch {
		case errors.Is(err, usecase.ErrBadRequest):
			WriteError(w, http.StatusBadRequest, "bad_request", "invalid register payload")
		case errors.Is(err, usecase.ErrSignatureInvalid):
			WriteError(w, http.StatusUnauthorized, "pair_signature_invalid", "signature verification failed")
		case errors.Is(err, usecase.ErrNonceExpiredOrInvalid):
			WriteError(w, http.StatusGone, "pair_nonce_expired", "pairing nonce expired or invalid")
		case errors.Is(err, usecase.ErrDeviceIDCollision):
			WriteError(w, http.StatusConflict, "pair_device_id_collision", "device_id already registered")
		default:
			h.log.Error("pair register", slog.String("err", err.Error()))
			WriteError(w, http.StatusInternalServerError, "internal_error", "register failed")
		}
		return
	}
	WriteJSON(w, http.StatusOK, map[string]any{
		"device_token":     b64url.EncodeToString(res.DeviceToken),
		"server_id":        b64url.EncodeToString(h.serverID),
		"device_token_ttl": res.TTLSeconds,
		"capabilities":     h.capabilities,
	})
}

// ──────────────────────────────────────────────────────────────────
// §6.1 GET /api/v1/files
// ──────────────────────────────────────────────────────────────────

func (h *Handler) FilesList(w http.ResponseWriter, r *http.Request) {
	p, ok := validatedPath(w, r.URL.Query().Get("path"))
	if !ok {
		return
	}
	hash := parseBool(r.URL.Query().Get("hash"), false)

	result, err := h.files.List(r.Context(), p, hash)
	if err != nil {
		h.writeFileErr(w, err, "list")
		return
	}
	entries := make([]map[string]any, 0, len(result.Entries))
	for _, e := range result.Entries {
		entry := map[string]any{
			"name":        e.Name,
			"modified_at": e.ModifiedAt,
		}
		if e.IsDir {
			entry["type"] = "dir"
		} else {
			entry["type"] = "file"
			entry["size"] = e.Size
			if e.MimeType != "" {
				entry["mime_type"] = e.MimeType
			}
			if hash {
				if len(e.SHA256) == 32 {
					entry["sha256"] = hex.EncodeToString(e.SHA256)
				} else {
					entry["sha256"] = nil
				}
			}
		}
		entries = append(entries, entry)
	}
	WriteJSON(w, http.StatusOK, map[string]any{
		"path":    result.Path,
		"entries": entries,
	})
}

// ──────────────────────────────────────────────────────────────────
// §6.2 GET /api/v1/files/content
// ──────────────────────────────────────────────────────────────────

func (h *Handler) FilesContent(w http.ResponseWriter, r *http.Request) {
	p, ok := validatedPath(w, r.URL.Query().Get("path"))
	if !ok {
		return
	}

	meta, err := h.files.Stat(r.Context(), p)
	if err != nil {
		h.writeFileErr(w, err, "stat")
		return
	}

	start, end, full, ok := parseRange(r.Header.Get("Range"), meta.Size)
	if !ok {
		w.Header().Set("Content-Range", fmt.Sprintf("bytes */%d", meta.Size))
		WriteError(w, http.StatusRequestedRangeNotSatisfiable, "range_not_satisfiable", "invalid or out-of-bounds range")
		return
	}

	readEnd := end
	if full {
		readEnd = -1
	}
	rc, _, err := h.files.Read(r.Context(), p, start, readEnd)
	if err != nil {
		h.writeFileErr(w, err, "read")
		return
	}
	defer rc.Close()

	mime := meta.MimeType
	if mime == "" {
		mime = "application/octet-stream"
	}
	w.Header().Set("Content-Type", mime)
	w.Header().Set("Accept-Ranges", "bytes")
	if len(meta.SHA256) == 32 {
		w.Header().Set("ETag", `"`+hex.EncodeToString(meta.SHA256)+`"`)
	}

	if full {
		w.Header().Set("Content-Length", strconv.FormatInt(meta.Size, 10))
		w.WriteHeader(http.StatusOK)
	} else {
		w.Header().Set("Content-Length", strconv.FormatInt(end-start+1, 10))
		w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", start, end, meta.Size))
		w.WriteHeader(http.StatusPartialContent)
	}
	if _, err := io.Copy(w, rc); err != nil {
		h.log.Debug("content stream", slog.String("err", err.Error()))
	}
}

// ──────────────────────────────────────────────────────────────────
// §6.3.1 POST /api/v1/files/upload/init
// ──────────────────────────────────────────────────────────────────

type uploadInitBody struct {
	Path      string `json:"path"`
	Size      int64  `json:"size"`
	SHA256    string `json:"sha256"`
	Overwrite bool   `json:"overwrite"`
}

func (h *Handler) UploadInit(w http.ResponseWriter, r *http.Request) {
	d := DeviceFromContext(r.Context())
	if d == nil {
		WriteError(w, http.StatusUnauthorized, "unauthorized", "no device context")
		return
	}

	var body uploadInitBody
	if !decodeJSON(w, r, &body) {
		return
	}
	p, ok := validatedPath(w, body.Path)
	if !ok {
		return
	}
	if body.Size < 0 {
		WriteError(w, http.StatusBadRequest, "bad_request", "size must be non-negative")
		return
	}
	shaBytes, err := hex.DecodeString(strings.ToLower(body.SHA256))
	if err != nil || len(shaBytes) != 32 {
		WriteError(w, http.StatusBadRequest, "bad_request", "sha256 must be 64 lowercase hex chars")
		return
	}

	params := &dfile.UploadInitParams{
		Path:      p,
		Size:      body.Size,
		SHA256:    shaBytes,
		Overwrite: body.Overwrite,
		DeviceID:  d.ID,
	}
	result, err := h.files.InitUpload(r.Context(), params)
	switch {
	case err == nil:
		if result.Deduplicated {
			WriteJSON(w, http.StatusOK, map[string]any{
				"upload_id": nil,
				"status":    "deduplicated",
			})
			return
		}
		WriteJSON(w, http.StatusCreated, map[string]any{
			"upload_id":      result.SessionID,
			"chunk_size":     result.ChunkSize,
			"chunk_size_max": result.ChunkSizeMax,
			"uploaded_bytes": int64(0),
			"expires_at":     result.ExpiresAt,
		})
	case errors.Is(err, dfile.ErrFileExists):
		writeFileExists(w, result.Existing)
	case errors.Is(err, dfile.ErrUploadInProgress):
		info, aerr := h.files.ActiveByPath(r.Context(), p)
		if aerr != nil || info == nil {
			WriteError(w, http.StatusConflict, "upload_in_progress", "another upload is active but details unavailable")
			return
		}
		WriteJSON(w, http.StatusConflict, map[string]any{
			"error": map[string]any{
				"code":    "upload_in_progress",
				"message": "Another upload to this path is already active",
			},
			"active_upload": info,
		})
	default:
		h.writeFileErr(w, err, "upload init")
	}
}

func writeFileExists(w http.ResponseWriter, existing *dfile.FileMeta) {
	body := map[string]any{
		"error": map[string]any{
			"code":    "file_exists",
			"message": "Target already exists",
		},
	}
	if existing != nil {
		e := map[string]any{
			"size":        existing.Size,
			"modified_at": existing.ModifiedAt,
		}
		if len(existing.SHA256) == 32 {
			e["sha256"] = hex.EncodeToString(existing.SHA256)
		}
		body["existing"] = e
	}
	WriteJSON(w, http.StatusConflict, body)
}

// ──────────────────────────────────────────────────────────────────
// §6.3.2 PUT /api/v1/files/upload/{id}
// ──────────────────────────────────────────────────────────────────

func (h *Handler) UploadChunk(w http.ResponseWriter, r *http.Request) {
	uploadID := chi.URLParam(r, "id")
	if uploadID == "" {
		WriteError(w, http.StatusBadRequest, "bad_request", "missing upload id")
		return
	}
	ct := r.Header.Get("Content-Type")
	if ct != "" && !strings.HasPrefix(ct, "application/octet-stream") {
		WriteError(w, http.StatusUnsupportedMediaType, "unsupported_media_type", "expected application/octet-stream")
		return
	}

	start, end, total, ok := parseContentRange(r.Header.Get("Content-Range"))
	if !ok {
		WriteError(w, http.StatusRequestedRangeNotSatisfiable, "range_not_satisfiable", "malformed Content-Range")
		return
	}
	expected := end - start + 1
	data, err := io.ReadAll(io.LimitReader(r.Body, expected+1))
	if err != nil {
		WriteError(w, http.StatusBadRequest, "bad_request", "read body failed")
		return
	}
	if int64(len(data)) != expected {
		WriteError(w, http.StatusRequestedRangeNotSatisfiable, "range_not_satisfiable", "body length does not match Content-Range")
		return
	}

	if err := h.files.AppendChunk(r.Context(), uploadID, start, data); err != nil {
		switch {
		case errors.Is(err, dfile.ErrUploadNotFound):
			WriteError(w, http.StatusNotFound, "upload_not_found", "upload id not found")
		case errors.Is(err, dfile.ErrRangeMismatch):
			uploaded, _, _, perr := h.files.Progress(r.Context(), uploadID)
			msg := "chunk start does not match server uploaded_bytes"
			if perr == nil {
				WriteJSON(w, http.StatusConflict, map[string]any{
					"error": map[string]any{
						"code":    "upload_range_mismatch",
						"message": msg,
					},
					"uploaded_bytes": uploaded,
				})
				return
			}
			WriteError(w, http.StatusConflict, "upload_range_mismatch", msg)
		case errors.Is(err, dfile.ErrChunkTooLarge):
			WriteError(w, http.StatusRequestEntityTooLarge, "payload_too_large", "chunk exceeds chunk_size_max")
		case errors.Is(err, dfile.ErrHashMismatch):
			WriteError(w, http.StatusUnprocessableEntity, "upload_hash_mismatch", "final sha256 did not match")
		case errors.Is(err, dfile.ErrInsufficientStorage):
			WriteError(w, http.StatusInsufficientStorage, "insufficient_storage", "disk full")
		default:
			h.log.Error("append chunk", slog.String("err", err.Error()), slog.String("upload_id", uploadID))
			WriteError(w, http.StatusInternalServerError, "internal_error", "chunk write failed")
		}
		return
	}

	uploaded, completed, _, err := h.files.Progress(r.Context(), uploadID)
	if err != nil {
		// Chunk was written; don't fail the client just because the
		// post-append progress lookup hiccupped.
		if errors.Is(err, dfile.ErrUploadNotFound) {
			// Final chunk commit deletes the row → completed.
			WriteJSON(w, http.StatusOK, map[string]any{
				"uploaded_bytes":  total,
				"complete":        true,
				"sha256_verified": true,
			})
			return
		}
		// Transient DB error after a successful append. Fall back to
		// values derived from the request headers so the client sees
		// 200 and can continue. Whether the upload is complete is
		// inferable from "did this chunk reach `total`?".
		h.log.Warn("post-chunk progress fallback", slog.String("err", err.Error()), slog.String("upload_id", uploadID))
		isFinal := end+1 == total
		body := map[string]any{
			"uploaded_bytes": end + 1,
			"complete":       isFinal,
		}
		if isFinal {
			body["sha256_verified"] = true
		}
		WriteJSON(w, http.StatusOK, body)
		return
	}
	body := map[string]any{
		"uploaded_bytes": uploaded,
		"complete":       completed,
	}
	if completed {
		body["sha256_verified"] = true
	}
	WriteJSON(w, http.StatusOK, body)
}

// ──────────────────────────────────────────────────────────────────
// §6.3.3 GET /api/v1/files/upload/{id}
// ──────────────────────────────────────────────────────────────────

func (h *Handler) UploadProgress(w http.ResponseWriter, r *http.Request) {
	uploadID := chi.URLParam(r, "id")
	uploaded, completed, expiresAt, err := h.files.Progress(r.Context(), uploadID)
	if err != nil {
		if errors.Is(err, dfile.ErrUploadNotFound) {
			WriteError(w, http.StatusNotFound, "upload_not_found", "upload id not found")
			return
		}
		h.log.Error("progress", slog.String("err", err.Error()))
		WriteError(w, http.StatusInternalServerError, "internal_error", "progress lookup failed")
		return
	}
	WriteJSON(w, http.StatusOK, map[string]any{
		"uploaded_bytes": uploaded,
		"complete":       completed,
		"expires_at":     expiresAt,
	})
}

// ──────────────────────────────────────────────────────────────────
// §6.3.4 DELETE /api/v1/files/upload/{id}
// ──────────────────────────────────────────────────────────────────

func (h *Handler) UploadAbort(w http.ResponseWriter, r *http.Request) {
	uploadID := chi.URLParam(r, "id")
	err := h.files.Abort(r.Context(), uploadID)
	if err != nil && !errors.Is(err, dfile.ErrUploadNotFound) {
		h.log.Error("abort", slog.String("err", err.Error()))
		WriteError(w, http.StatusInternalServerError, "internal_error", "abort failed")
		return
	}
	// Per §6.3.4: idempotent 204 regardless of whether the session
	// existed (a missing session is the terminal state we're after).
	w.WriteHeader(http.StatusNoContent)
}

// ──────────────────────────────────────────────────────────────────
// §6.4 DELETE /api/v1/files
// ──────────────────────────────────────────────────────────────────

func (h *Handler) FilesDelete(w http.ResponseWriter, r *http.Request) {
	p, ok := validatedPath(w, r.URL.Query().Get("path"))
	if !ok {
		return
	}
	recursive := parseBool(r.URL.Query().Get("recursive"), false)

	if err := h.files.Delete(r.Context(), p, recursive); err != nil {
		if errors.Is(err, dfile.ErrDirectoryNotEmpty) {
			WriteError(w, http.StatusConflict, "directory_not_empty", "directory is not empty; pass recursive=true")
			return
		}
		h.writeFileErr(w, err, "delete")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// ──────────────────────────────────────────────────────────────────
// §6.5 POST /api/v1/files/move
// ──────────────────────────────────────────────────────────────────

type moveBody struct {
	From      string `json:"from"`
	To        string `json:"to"`
	Overwrite bool   `json:"overwrite"`
}

func (h *Handler) FilesMove(w http.ResponseWriter, r *http.Request) {
	var body moveBody
	if !decodeJSON(w, r, &body) {
		return
	}
	from, ok := validatedPath(w, body.From)
	if !ok {
		return
	}
	to, ok := validatedPath(w, body.To)
	if !ok {
		return
	}
	if from == to {
		WriteError(w, http.StatusBadRequest, "bad_request", "from and to resolve to the same path")
		return
	}

	if err := h.files.Move(r.Context(), from, to, body.Overwrite); err != nil {
		switch {
		case errors.Is(err, dfile.ErrFileNotFound):
			WriteError(w, http.StatusNotFound, "not_found", "source path does not exist")
		case errors.Is(err, dfile.ErrFileExists):
			// Best-effort: populate `existing` if repo has metadata.
			existing, _ := h.files.Stat(r.Context(), to)
			writeFileExists(w, existing)
		default:
			h.log.Error("move", slog.String("err", err.Error()))
			WriteError(w, http.StatusInternalServerError, "internal_error", "move failed")
		}
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// ──────────────────────────────────────────────────────────────────
// §7.1 GET /api/v1/devices
// ──────────────────────────────────────────────────────────────────

func (h *Handler) DevicesList(w http.ResponseWriter, r *http.Request) {
	current := DeviceFromContext(r.Context())
	rows, err := h.devices.List(r.Context())
	if err != nil {
		h.log.Error("devices list", slog.String("err", err.Error()))
		WriteError(w, http.StatusInternalServerError, "internal_error", "devices list failed")
		return
	}
	out := make([]map[string]any, 0, len(rows))
	for _, d := range rows {
		isCurrent := current != nil && len(current.ID) == len(d.ID) && string(current.ID) == string(d.ID)
		out = append(out, map[string]any{
			"device_id":    b64url.EncodeToString(d.ID),
			"device_name":  d.Name,
			"platform":     d.Platform,
			"created_at":   d.CreatedAt,
			"last_seen_at": d.LastSeenAt,
			"current":      isCurrent,
			"revoked":      d.Revoked,
		})
	}
	WriteJSON(w, http.StatusOK, map[string]any{"devices": out})
}

// ──────────────────────────────────────────────────────────────────
// §7.2 DELETE /api/v1/devices/{id}
// ──────────────────────────────────────────────────────────────────

func (h *Handler) DeviceRevoke(w http.ResponseWriter, r *http.Request) {
	idStr := chi.URLParam(r, "id")
	id, err := decodeB64URL(idStr)
	if err != nil || len(id) != 16 {
		WriteError(w, http.StatusBadRequest, "bad_request", "device_id must be 16-byte base64url")
		return
	}
	if err := h.devices.Revoke(r.Context(), id); err != nil {
		if errors.Is(err, usecase.ErrDeviceNotFound) {
			WriteError(w, http.StatusNotFound, "not_found", "device not found")
			return
		}
		h.log.Error("revoke", slog.String("err", err.Error()))
		WriteError(w, http.StatusInternalServerError, "internal_error", "revoke failed")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// ──────────────────────────────────────────────────────────────────
// helpers
// ──────────────────────────────────────────────────────────────────

// decodeJSON reads a JSON body with a size cap and disallows unknown
// fields (conservative on v0.2 inputs). Returns false after writing a
// 400 on any decode problem.
func decodeJSON(w http.ResponseWriter, r *http.Request, dst any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, maxJSONBody)
	dec := json.NewDecoder(r.Body)
	dec.DisallowUnknownFields()
	if err := dec.Decode(dst); err != nil {
		WriteError(w, http.StatusBadRequest, "bad_request", "invalid JSON body: "+err.Error())
		return false
	}
	return true
}

// decodeB64URL rejects inputs containing standard-base64 characters
// (`+`, `/`) or padding (`=`) per PROTOCOL §1 / §4.3.
func decodeB64URL(s string) ([]byte, error) {
	if s == "" {
		return nil, errors.New("empty")
	}
	if strings.ContainsAny(s, "+/=") {
		return nil, errors.New("non-base64url characters")
	}
	return b64url.DecodeString(s)
}

func parseBool(s string, fallback bool) bool {
	switch strings.ToLower(strings.TrimSpace(s)) {
	case "true", "1", "yes":
		return true
	case "false", "0", "no":
		return false
	case "":
		return fallback
	default:
		return fallback
	}
}

// validatedPath enforces PROTOCOL §1 path rules. Writes a 400 and
// returns ok=false on violation.
func validatedPath(w http.ResponseWriter, raw string) (string, bool) {
	if raw == "" {
		WriteError(w, http.StatusBadRequest, "bad_request", "path is required")
		return "", false
	}
	if !strings.HasPrefix(raw, "/") {
		WriteError(w, http.StatusBadRequest, "bad_request", "path must begin with /")
		return "", false
	}
	if !utf8.ValidString(raw) {
		WriteError(w, http.StatusBadRequest, "bad_request", "path is not valid UTF-8")
		return "", false
	}
	for _, r := range raw {
		if r == 0 || r == '\n' || r == '\r' {
			WriteError(w, http.StatusBadRequest, "bad_request", "path contains control character")
			return "", false
		}
		if r < 0x20 || r == 0x7f {
			WriteError(w, http.StatusBadRequest, "bad_request", "path contains control character")
			return "", false
		}
	}
	// Component-level checks.
	cleaned := path.Clean(raw)
	if cleaned != raw && cleaned+"/" != raw {
		WriteError(w, http.StatusBadRequest, "bad_request", "path must be canonical (no `.`, `..`, double slashes)")
		return "", false
	}
	parts := strings.Split(strings.Trim(raw, "/"), "/")
	for _, c := range parts {
		if c == "" {
			continue
		}
		if c == "." || c == ".." {
			WriteError(w, http.StatusBadRequest, "bad_request", "path traversal components are forbidden")
			return "", false
		}
		if strings.TrimFunc(c, unicode.IsSpace) != c {
			WriteError(w, http.StatusBadRequest, "bad_request", "path component has leading/trailing whitespace")
			return "", false
		}
		if isWindowsReserved(c) {
			WriteError(w, http.StatusBadRequest, "bad_request", "windows-reserved component name")
			return "", false
		}
	}
	return raw, true
}

var windowsReserved = map[string]struct{}{
	"CON": {}, "PRN": {}, "AUX": {}, "NUL": {},
	"COM1": {}, "COM2": {}, "COM3": {}, "COM4": {}, "COM5": {},
	"COM6": {}, "COM7": {}, "COM8": {}, "COM9": {},
	"LPT1": {}, "LPT2": {}, "LPT3": {}, "LPT4": {}, "LPT5": {},
	"LPT6": {}, "LPT7": {}, "LPT8": {}, "LPT9": {},
}

func isWindowsReserved(name string) bool {
	upper := strings.ToUpper(name)
	if dot := strings.IndexByte(upper, '.'); dot >= 0 {
		upper = upper[:dot]
	}
	_, ok := windowsReserved[upper]
	return ok
}

// parseRange handles "bytes=X-Y", "bytes=X-", "bytes=-N". Returns
// (start, end, fullFile, ok). fullFile=true means "no Range header →
// return 200 with entire body"; ok=false means the header was present
// but unparseable or out of bounds.
func parseRange(header string, size int64) (start, end int64, fullFile, ok bool) {
	if header == "" {
		return 0, size - 1, true, true
	}
	if !strings.HasPrefix(header, "bytes=") {
		return 0, 0, false, false
	}
	spec := strings.TrimPrefix(header, "bytes=")
	// Multi-range not supported in v0.2.
	if strings.Contains(spec, ",") {
		return 0, 0, false, false
	}
	dash := strings.IndexByte(spec, '-')
	if dash < 0 {
		return 0, 0, false, false
	}
	startS, endS := spec[:dash], spec[dash+1:]
	switch {
	case startS == "" && endS != "":
		// Suffix range: bytes=-N → last N bytes.
		n, err := strconv.ParseInt(endS, 10, 64)
		if err != nil || n <= 0 {
			return 0, 0, false, false
		}
		if n > size {
			n = size
		}
		return size - n, size - 1, false, size > 0
	case startS != "" && endS == "":
		s, err := strconv.ParseInt(startS, 10, 64)
		if err != nil || s < 0 || s >= size {
			return 0, 0, false, false
		}
		return s, size - 1, false, true
	case startS != "" && endS != "":
		s, err1 := strconv.ParseInt(startS, 10, 64)
		e, err2 := strconv.ParseInt(endS, 10, 64)
		if err1 != nil || err2 != nil || s < 0 || e < s || s >= size {
			return 0, 0, false, false
		}
		if e >= size {
			e = size - 1
		}
		return s, e, false, true
	}
	return 0, 0, false, false
}

// parseContentRange parses "bytes <start>-<end>/<total>" per RFC 7233.
func parseContentRange(header string) (start, end, total int64, ok bool) {
	if !strings.HasPrefix(header, "bytes ") {
		return 0, 0, 0, false
	}
	rest := strings.TrimPrefix(header, "bytes ")
	slash := strings.IndexByte(rest, '/')
	if slash < 0 {
		return 0, 0, 0, false
	}
	rangePart, totalPart := rest[:slash], rest[slash+1:]
	dash := strings.IndexByte(rangePart, '-')
	if dash < 0 {
		return 0, 0, 0, false
	}
	s, err1 := strconv.ParseInt(rangePart[:dash], 10, 64)
	e, err2 := strconv.ParseInt(rangePart[dash+1:], 10, 64)
	t, err3 := strconv.ParseInt(totalPart, 10, 64)
	if err1 != nil || err2 != nil || err3 != nil {
		return 0, 0, 0, false
	}
	if s < 0 || e < s || t <= 0 || e >= t {
		return 0, 0, 0, false
	}
	return s, e, t, true
}

// writeFileErr maps common storage errors to protocol error envelopes.
func (h *Handler) writeFileErr(w http.ResponseWriter, err error, op string) {
	switch {
	case errors.Is(err, dfile.ErrFileNotFound):
		WriteError(w, http.StatusNotFound, "not_found", "resource does not exist")
	case errors.Is(err, dfile.ErrFileExists):
		WriteError(w, http.StatusConflict, "file_exists", "target already exists")
	default:
		h.log.Error("file op", slog.String("op", op), slog.String("err", err.Error()))
		WriteError(w, http.StatusInternalServerError, "internal_error", op+" failed")
	}
}
