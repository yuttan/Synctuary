package admin

import (
	"context"
	"net/http"
	"strings"

	"github.com/synctuary/synctuary-server/internal/usecase"
)

type ctxKey int

const ctxKeyAdminToken ctxKey = 1

// AdminTokenFromContext retrieves the admin session token from ctx.
func AdminTokenFromContext(ctx context.Context) string {
	v, _ := ctx.Value(ctxKeyAdminToken).(string)
	return v
}

// SessionAuth returns middleware that validates admin session cookies
// or Bearer tokens for the /admin/api/ endpoints.
func SessionAuth(admin *usecase.AdminService, configToken string) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			token := ""

			// 1. Check cookie first.
			if c, err := r.Cookie("synctuary_admin"); err == nil {
				token = c.Value
			}

			// 2. Fall back to Authorization header (for API automation).
			if token == "" {
				if auth := r.Header.Get("Authorization"); strings.HasPrefix(auth, "Bearer ") {
					bearer := strings.TrimPrefix(auth, "Bearer ")
					// Check against pre-shared config token.
					if configToken != "" && bearer == configToken {
						ctx := context.WithValue(r.Context(), ctxKeyAdminToken, configTokenSentinel)
						next.ServeHTTP(w, r.WithContext(ctx))
						return
					}
					token = bearer
				}
			}

			if token == "" {
				writeAdminError(w, http.StatusUnauthorized, "unauthorized", "missing admin session")
				return
			}

			if err := admin.ValidateSession(r.Context(), token); err != nil {
				writeAdminError(w, http.StatusUnauthorized, "session_expired", "admin session expired or invalid")
				return
			}

			ctx := context.WithValue(r.Context(), ctxKeyAdminToken, token)
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}
