package admin

import (
	"embed"
	"io/fs"
	"net/http"
	"strings"
)

//go:embed dist/*
var distFS embed.FS

// SPAHandler serves the Vite-built SPA from the embedded dist/
// directory. Requests for actual files (js, css, assets) are served
// directly; all other paths fall back to index.html for client-side
// routing.
func SPAHandler() http.Handler {
	sub, err := fs.Sub(distFS, "dist")
	if err != nil {
		panic("admin: embed.FS sub failed: " + err.Error())
	}
	fileServer := http.FileServer(http.FS(sub))

	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Try to serve the actual file first.
		p := strings.TrimPrefix(r.URL.Path, "/")
		if p == "" {
			p = "index.html"
		}
		if _, err := fs.Stat(sub, p); err == nil {
			fileServer.ServeHTTP(w, r)
			return
		}
		// Fallback: serve index.html for SPA client-side routing.
		r.URL.Path = "/"
		fileServer.ServeHTTP(w, r)
	})
}
