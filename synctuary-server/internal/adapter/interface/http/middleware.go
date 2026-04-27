// Package http exposes the PROTOCOL v0.2.2 API over net/http via
// chi. middleware.go hosts the request-scoped concerns (bearer auth,
// request logging, common JSON writers); handler.go hosts the
// endpoint glue.
package http

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/synctuary/synctuary-server/internal/adapter/infrastructure/crypto"
	"github.com/synctuary/synctuary-server/internal/domain/device"
)

// ctxKey is unexported so external packages cannot collide with our
// context slots.
type ctxKey int

const (
	ctxKeyDevice ctxKey = iota
	ctxKeyRequestID
)

// DeviceFromContext returns the device authenticated by BearerAuth,
// or nil if the request never passed the middleware.
func DeviceFromContext(ctx context.Context) *device.Device {
	v, _ := ctx.Value(ctxKeyDevice).(*device.Device)
	return v
}

// BearerAuth enforces PROTOCOL §5: Authorization: Bearer <token>.
// The token is never persisted; we hash it and look up the device by
// its token_hash. A revoked device produces 401, same as a missing
// one — we do not distinguish client-side.
func BearerAuth(devices device.Repository, log *slog.Logger) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			token, ok := parseBearer(r.Header.Get("Authorization"))
			if !ok {
				WriteError(w, http.StatusUnauthorized, "unauthorized", "missing or malformed bearer token")
				return
			}
			// The bearer is base64url-without-padding of the raw 32-byte
			// CSPRNG token (matches the wire encoding used by §4.3
			// register response). Reject anything else with the same
			// 401 the rest of the failure modes use — never reveal
			// "wrong format" vs "wrong value" to the caller.
			raw, derr := base64.RawURLEncoding.DecodeString(token)
			if derr != nil || len(raw) != 32 {
				WriteError(w, http.StatusUnauthorized, "unauthorized", "invalid token")
				return
			}
			hash := crypto.HashToken(raw)
			d, err := devices.GetByTokenHash(r.Context(), hash)
			switch {
			case err == nil:
				// Fire-and-forget last_seen update; a stale value
				// is not a correctness issue.
				go func(id []byte) {
					ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
					defer cancel()
					if err := devices.TouchLastSeen(ctx, id, time.Now().Unix()); err != nil {
						log.Debug("touch last_seen", slog.String("err", err.Error()))
					}
				}(d.ID)
				ctx := context.WithValue(r.Context(), ctxKeyDevice, d)
				next.ServeHTTP(w, r.WithContext(ctx))
			case errors.Is(err, device.ErrNotFound), errors.Is(err, device.ErrRevoked):
				WriteError(w, http.StatusUnauthorized, "unauthorized", "invalid token")
			default:
				log.Error("auth lookup", slog.String("err", err.Error()))
				WriteError(w, http.StatusInternalServerError, "internal", "auth lookup failed")
			}
		})
	}
}

// parseBearer extracts the token from "Bearer <token>". Returns
// ("", false) on any malformation.
func parseBearer(header string) (string, bool) {
	const prefix = "Bearer "
	if len(header) <= len(prefix) || !strings.EqualFold(header[:len(prefix)], prefix) {
		return "", false
	}
	token := strings.TrimSpace(header[len(prefix):])
	if token == "" {
		return "", false
	}
	return token, true
}

// RequestLogger logs method, path, status, duration. Skips the body
// so upload traffic doesn't blow up the log volume.
func RequestLogger(log *slog.Logger) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			start := time.Now()
			rw := &statusRecorder{ResponseWriter: w, status: 200}
			next.ServeHTTP(rw, r)
			log.Info("http",
				slog.String("method", r.Method),
				slog.String("path", r.URL.Path),
				slog.Int("status", rw.status),
				slog.Duration("dur", time.Since(start)),
				slog.String("remote", clientIP(r)),
			)
		})
	}
}

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (r *statusRecorder) WriteHeader(s int) { r.status = s; r.ResponseWriter.WriteHeader(s) }

// clientIP extracts the source IP used for rate-limit keying. v0.4
// does NOT trust X-Forwarded-For (we have no proxy allowlist); this
// is the RemoteAddr host portion only.
func clientIP(r *http.Request) string {
	addr := r.RemoteAddr
	if i := strings.LastIndex(addr, ":"); i >= 0 {
		return addr[:i]
	}
	return addr
}

// WriteJSON encodes v as JSON and emits it with the given status.
// Any encoding error is logged and promoted to 500 for the client.
func WriteJSON(w http.ResponseWriter, status int, v any) {
	buf, err := json.Marshal(v)
	if err != nil {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte(`{"error":{"code":"internal","message":"encode failed"}}`))
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_, _ = w.Write(buf)
}

// WriteError emits the PROTOCOL §8 error envelope. `code` is the
// stable machine-readable string from the spec; `message` is
// free-form and MAY be empty.
func WriteError(w http.ResponseWriter, status int, code, message string) {
	WriteJSON(w, status, map[string]any{
		"error": map[string]any{
			"code":    code,
			"message": message,
		},
	})
}
