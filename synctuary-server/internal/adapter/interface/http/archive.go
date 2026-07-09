// archive.go implements the PROTOCOL v0.3.2 §6.9-§6.11 archive endpoint
// family — browsing, in-archive streaming, and extraction of zip / rar
// / 7z containers:
//
//	GET  /api/v1/files/archive          — list entries (§6.9)
//	GET  /api/v1/files/archive/content  — stream one entry (§6.10)
//	POST /api/v1/files/archive/extract  — extract all entries (§6.11)
//
// Lives in its own file to keep handler.go bounded; all archive-specific
// error mapping is local here. The comic-reader use case (paging through
// every image entry with swipe) is served by /content without ever
// materializing an extracted copy.
package http

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"strconv"

	"github.com/synctuary/synctuary-server/internal/adapter/infrastructure/fs"
	dfile "github.com/synctuary/synctuary-server/internal/domain/file"
	"github.com/synctuary/synctuary-server/internal/usecase"
)

// ──────────────────────────────────────────────────────────────────
// §6.9 GET /api/v1/files/archive — list archive entries
// ──────────────────────────────────────────────────────────────────

func (h *Handler) FilesArchiveList(w http.ResponseWriter, r *http.Request) {
	p, ok := validatedPath(w, r.URL.Query().Get("path"))
	if !ok {
		return
	}
	if h.archives == nil {
		WriteError(w, http.StatusNotImplemented, "not_implemented", "archive service not available")
		return
	}

	storage, _, ok := h.resolveShareStorage(w, r)
	if !ok {
		return
	}
	svc := h.archives.WithStorage(storage)
	entries, err := svc.List(r.Context(), p)
	if err != nil {
		h.writeArchiveErr(w, err, "list")
		return
	}

	out := make([]map[string]any, 0, len(entries))
	for _, e := range entries {
		item := map[string]any{
			"path": e.Path,
			"dir":  e.Dir,
		}
		if !e.Dir {
			item["size"] = e.Size
		}
		out = append(out, item)
	}
	WriteJSON(w, http.StatusOK, map[string]any{"entries": out})
}

// ──────────────────────────────────────────────────────────────────
// §6.10 GET /api/v1/files/archive/content — stream one entry
// ──────────────────────────────────────────────────────────────────

func (h *Handler) FilesArchiveContent(w http.ResponseWriter, r *http.Request) {
	p, ok := validatedPath(w, r.URL.Query().Get("path"))
	if !ok {
		return
	}
	entry := r.URL.Query().Get("entry")
	if entry == "" {
		WriteError(w, http.StatusBadRequest, "bad_request", "entry is required")
		return
	}
	if h.archives == nil {
		WriteError(w, http.StatusNotImplemented, "not_implemented", "archive service not available")
		return
	}

	storage, _, ok := h.resolveShareStorage(w, r)
	if !ok {
		return
	}
	svc := h.archives.WithStorage(storage)
	rc, size, err := svc.Open(r.Context(), p, entry)
	if err != nil {
		h.writeArchiveErr(w, err, "content")
		return
	}
	defer rc.Close()

	// Content-Type derives from the ENTRY's extension, not the container.
	mime := fs.DetectMime(entry)
	if mime == "" {
		mime = "application/octet-stream"
	}
	w.Header().Set("Content-Type", mime)
	w.Header().Set("Cache-Control", "private, max-age=86400")
	if size >= 0 {
		w.Header().Set("Content-Length", strconv.FormatInt(size, 10))
	}
	w.WriteHeader(http.StatusOK)

	flusher, canFlush := w.(http.Flusher)
	bp := streamBufPool.Get().(*[]byte)
	defer streamBufPool.Put(bp)
	buf := *bp
	for {
		n, readErr := rc.Read(buf)
		if n > 0 {
			if _, wErr := w.Write(buf[:n]); wErr != nil {
				h.log.Debug("archive content write", slog.String("err", wErr.Error()))
				break
			}
			if canFlush {
				flusher.Flush()
			}
		}
		if readErr != nil {
			if readErr != io.EOF {
				h.log.Debug("archive content read", slog.String("err", readErr.Error()))
			}
			break
		}
	}
}

// ──────────────────────────────────────────────────────────────────
// §6.11 POST /api/v1/files/archive/extract — extract all entries
// ──────────────────────────────────────────────────────────────────

type archiveExtractBody struct {
	Path  string `json:"path"`
	Share string `json:"share"`
}

func (h *Handler) FilesArchiveExtract(w http.ResponseWriter, r *http.Request) {
	var body archiveExtractBody
	if !decodeJSON(w, r, &body) {
		return
	}
	p, ok := validatedPath(w, body.Path)
	if !ok {
		return
	}
	if h.archives == nil {
		WriteError(w, http.StatusNotImplemented, "not_implemented", "archive service not available")
		return
	}

	// The share for extract is taken from the JSON body (not the query
	// string): the request body is the single source of truth for this
	// POST. resolveShareStorage reads ?share=, so mirror the body value
	// into the request URL query before resolving.
	if body.Share != "" {
		q := r.URL.Query()
		q.Set("share", body.Share)
		r.URL.RawQuery = q.Encode()
	}
	storage, _, ok := h.resolveShareStorage(w, r)
	if !ok {
		return
	}
	svc := h.archives.WithStorage(storage)
	dest, err := svc.Extract(r.Context(), p)
	if err != nil {
		h.writeArchiveErr(w, err, "extract")
		return
	}
	WriteJSON(w, http.StatusOK, map[string]any{"dest": dest})
}

// writeArchiveErr maps archive-service errors to protocol error
// envelopes with archive-specific slugs.
func (h *Handler) writeArchiveErr(w http.ResponseWriter, err error, op string) {
	switch {
	case errors.Is(err, dfile.ErrFileNotFound):
		WriteError(w, http.StatusNotFound, "not_found", "file does not exist")
	case errors.Is(err, usecase.ErrArchiveEntryNotFound):
		WriteError(w, http.StatusNotFound, "entry_not_found", "archive entry does not exist")
	case errors.Is(err, usecase.ErrArchiveUnsupported):
		WriteError(w, http.StatusBadRequest, "unsupported_type", "file is not a supported archive")
	case errors.Is(err, usecase.ErrArchiveTooLarge):
		WriteError(w, http.StatusBadRequest, "archive_too_large", "archive has too many entries to list")
	case errors.Is(err, usecase.ErrArchiveUnreadable):
		WriteError(w, http.StatusBadRequest, "archive_unreadable", "archive is corrupt or password-protected")
	case errors.Is(err, context.Canceled):
		h.log.Debug("archive op canceled by client", slog.String("op", op))
	default:
		h.log.Error("archive op", slog.String("op", op), slog.String("err", err.Error()))
		WriteError(w, http.StatusInternalServerError, "internal_error", op+" failed")
	}
}
