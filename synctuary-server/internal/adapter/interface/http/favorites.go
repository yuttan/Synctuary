// favorites.go implements the PROTOCOL v0.2.3 §8 endpoint family:
//
//	GET    /api/v1/favorites
//	POST   /api/v1/favorites
//	GET    /api/v1/favorites/{id}
//	PATCH  /api/v1/favorites/{id}
//	DELETE /api/v1/favorites/{id}
//	POST   /api/v1/favorites/{id}/items
//	DELETE /api/v1/favorites/{id}/items
//
// Lives in its own file to keep handler.go from growing unbounded; all
// favorite-specific DTOs and error mapping are local to this file.
package http

import (
	"errors"
	"log/slog"
	"net/http"
	"strings"

	"github.com/go-chi/chi/v5"

	"github.com/synctuary/synctuary-server/internal/domain/favorite"
	"github.com/synctuary/synctuary-server/internal/usecase"
)

// ──────────────────────────────────────────────────────────────────
// DTOs (wire shapes — distinct from domain entities)
// ──────────────────────────────────────────────────────────────────

// favListSummaryDTO is the §8.2 list element shape and the §8.4 / §8.5
// response shape. `created_by_device_id` is base64url; emitted as null
// when the originating device was revoked (FK SET NULL).
type favListSummaryDTO struct {
	ID                string  `json:"id"`
	Name              string  `json:"name"`
	Hidden            bool    `json:"hidden"`
	ItemCount         int     `json:"item_count"`
	CreatedAt         int64   `json:"created_at"`
	ModifiedAt        int64   `json:"modified_at"`
	CreatedByDeviceID *string `json:"created_by_device_id"`
}

// favItemDTO is one item in §8.3 / §8.7 responses.
type favItemDTO struct {
	Path            string  `json:"path"`
	AddedAt         int64   `json:"added_at"`
	AddedByDeviceID *string `json:"added_by_device_id"`
}

// favListDetailDTO is the §8.3 full response shape.
type favListDetailDTO struct {
	favListSummaryDTO
	Items []favItemDTO `json:"items"`
}

func encodeListSummary(l favorite.List) favListSummaryDTO {
	return favListSummaryDTO{
		ID:                b64url.EncodeToString(l.ID),
		Name:              l.Name,
		Hidden:            l.Hidden,
		ItemCount:         l.ItemCount,
		CreatedAt:         l.CreatedAt,
		ModifiedAt:        l.ModifiedAt,
		CreatedByDeviceID: optionalDeviceIDBase64(l.CreatedByDeviceID),
	}
}

func encodeItem(it favorite.Item) favItemDTO {
	return favItemDTO{
		Path:            it.Path,
		AddedAt:         it.AddedAt,
		AddedByDeviceID: optionalDeviceIDBase64(it.AddedByDeviceID),
	}
}

func optionalDeviceIDBase64(id []byte) *string {
	if len(id) != 16 {
		return nil
	}
	s := b64url.EncodeToString(id)
	return &s
}

// ──────────────────────────────────────────────────────────────────
// URL-param validation
// ──────────────────────────────────────────────────────────────────

// favoriteListIDFromURL parses {id} into 16 raw bytes. On any error
// writes 404 favorite_list_not_found (per §8.3 leak-avoidance) and
// returns ok=false.
func (h *Handler) favoriteListIDFromURL(w http.ResponseWriter, r *http.Request) ([]byte, bool) {
	raw := chi.URLParam(r, "id")
	if raw == "" {
		WriteError(w, http.StatusNotFound, "favorite_list_not_found", "no such favorite list")
		return nil, false
	}
	id, err := decodeB64URL(raw)
	if err != nil || len(id) != 16 {
		// Malformed id is indistinguishable from "doesn't exist"
		// from the client's standpoint — both are 404 to avoid
		// information leak about the id space.
		WriteError(w, http.StatusNotFound, "favorite_list_not_found", "no such favorite list")
		return nil, false
	}
	return id, true
}

// ──────────────────────────────────────────────────────────────────
// §8.2 GET /api/v1/favorites
// ──────────────────────────────────────────────────────────────────

func (h *Handler) FavoritesList(w http.ResponseWriter, r *http.Request) {
	includeHidden := parseBool(r.URL.Query().Get("include_hidden"), false)

	lists, err := h.favorites.ListAll(r.Context(), includeHidden)
	if err != nil {
		h.log.Error("favorites list", slog.String("err", err.Error()))
		WriteError(w, http.StatusInternalServerError, "internal_error", "failed to list favorites")
		return
	}

	out := make([]favListSummaryDTO, 0, len(lists))
	for _, l := range lists {
		out = append(out, encodeListSummary(l))
	}
	WriteJSON(w, http.StatusOK, map[string]any{"lists": out})
}

// ──────────────────────────────────────────────────────────────────
// §8.3 GET /api/v1/favorites/{id}
// ──────────────────────────────────────────────────────────────────

func (h *Handler) FavoriteGet(w http.ResponseWriter, r *http.Request) {
	id, ok := h.favoriteListIDFromURL(w, r)
	if !ok {
		return
	}
	got, err := h.favorites.GetList(r.Context(), id)
	if err != nil {
		h.writeFavoriteErr(w, err, "get")
		return
	}
	body := favListDetailDTO{
		favListSummaryDTO: encodeListSummary(got.List),
		Items:             make([]favItemDTO, 0, len(got.Items)),
	}
	for _, it := range got.Items {
		body.Items = append(body.Items, encodeItem(it))
	}
	WriteJSON(w, http.StatusOK, body)
}

// ──────────────────────────────────────────────────────────────────
// §8.4 POST /api/v1/favorites
// ──────────────────────────────────────────────────────────────────

type favCreateRequest struct {
	Name   string `json:"name"`
	Hidden bool   `json:"hidden"`
}

func (h *Handler) FavoriteCreate(w http.ResponseWriter, r *http.Request) {
	d := DeviceFromContext(r.Context())
	if d == nil {
		WriteError(w, http.StatusUnauthorized, "unauthorized", "missing device context")
		return
	}

	var body favCreateRequest
	if !decodeJSON(w, r, &body) {
		return
	}
	// Trim wire whitespace at the boundary; usecase enforces the
	// "no leading/trailing whitespace" rule against the raw input
	// after trim — which means a name that was ALL whitespace
	// becomes empty and fails the 1..256 rune-count check.
	trimmed := strings.TrimSpace(body.Name)
	if trimmed != body.Name || trimmed == "" {
		WriteError(w, http.StatusBadRequest, "favorite_name_invalid", "name has leading/trailing whitespace or is empty")
		return
	}

	list, err := h.favorites.CreateList(r.Context(), trimmed, body.Hidden, d.ID)
	if err != nil {
		h.writeFavoriteErr(w, err, "create")
		return
	}
	WriteJSON(w, http.StatusCreated, encodeListSummary(*list))
}

// ──────────────────────────────────────────────────────────────────
// §8.5 PATCH /api/v1/favorites/{id}
// ──────────────────────────────────────────────────────────────────

type favPatchRequest struct {
	// Pointers distinguish "field absent" (nil) from "field
	// present with zero value" (e.g., Hidden=false explicitly).
	Name   *string `json:"name"`
	Hidden *bool   `json:"hidden"`
}

func (h *Handler) FavoritePatch(w http.ResponseWriter, r *http.Request) {
	id, ok := h.favoriteListIDFromURL(w, r)
	if !ok {
		return
	}
	var body favPatchRequest
	if !decodeJSON(w, r, &body) {
		return
	}
	if body.Name == nil && body.Hidden == nil {
		WriteError(w, http.StatusBadRequest, "bad_request", "patch body has no effective fields")
		return
	}
	patch := favorite.ListPatch{Hidden: body.Hidden}
	if body.Name != nil {
		trimmed := strings.TrimSpace(*body.Name)
		if trimmed != *body.Name || trimmed == "" {
			WriteError(w, http.StatusBadRequest, "favorite_name_invalid", "name has leading/trailing whitespace or is empty")
			return
		}
		patch.Name = &trimmed
	}
	if err := h.favorites.UpdateList(r.Context(), id, patch); err != nil {
		h.writeFavoriteErr(w, err, "patch")
		return
	}

	// Return the updated summary (cheap re-read; same shape as POST).
	got, err := h.favorites.GetList(r.Context(), id)
	if err != nil {
		h.writeFavoriteErr(w, err, "patch read-back")
		return
	}
	WriteJSON(w, http.StatusOK, encodeListSummary(got.List))
}

// ──────────────────────────────────────────────────────────────────
// §8.6 DELETE /api/v1/favorites/{id}
// ──────────────────────────────────────────────────────────────────

func (h *Handler) FavoriteDelete(w http.ResponseWriter, r *http.Request) {
	id, ok := h.favoriteListIDFromURL(w, r)
	if !ok {
		return
	}
	if err := h.favorites.DeleteList(r.Context(), id); err != nil {
		h.writeFavoriteErr(w, err, "delete")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// ──────────────────────────────────────────────────────────────────
// §8.7 POST /api/v1/favorites/{id}/items
// ──────────────────────────────────────────────────────────────────

type favItemAddRequest struct {
	Path string `json:"path"`
}

func (h *Handler) FavoriteItemAdd(w http.ResponseWriter, r *http.Request) {
	id, ok := h.favoriteListIDFromURL(w, r)
	if !ok {
		return
	}
	d := DeviceFromContext(r.Context())
	if d == nil {
		WriteError(w, http.StatusUnauthorized, "unauthorized", "missing device context")
		return
	}

	var body favItemAddRequest
	if !decodeJSON(w, r, &body) {
		return
	}
	p, ok := validatedPath(w, body.Path)
	if !ok {
		return
	}

	item, inserted, err := h.favorites.AddItem(r.Context(), id, p, d.ID)
	if err != nil {
		h.writeFavoriteErr(w, err, "add item")
		return
	}
	status := http.StatusCreated
	if !inserted {
		status = http.StatusOK // §8.7 idempotent re-add
	}
	WriteJSON(w, status, encodeItem(item))
}

// ──────────────────────────────────────────────────────────────────
// §8.8 DELETE /api/v1/favorites/{id}/items?path=<path>
// ──────────────────────────────────────────────────────────────────

func (h *Handler) FavoriteItemRemove(w http.ResponseWriter, r *http.Request) {
	id, ok := h.favoriteListIDFromURL(w, r)
	if !ok {
		return
	}
	p, ok := validatedPath(w, r.URL.Query().Get("path"))
	if !ok {
		return
	}
	if err := h.favorites.RemoveItem(r.Context(), id, p); err != nil {
		h.writeFavoriteErr(w, err, "remove item")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// ──────────────────────────────────────────────────────────────────
// error mapping
// ──────────────────────────────────────────────────────────────────

// writeFavoriteErr maps domain / usecase errors to PROTOCOL §9 codes.
// Any unmapped error is logged and surfaces as 500 internal_error.
func (h *Handler) writeFavoriteErr(w http.ResponseWriter, err error, op string) {
	switch {
	case errors.Is(err, favorite.ErrListNotFound):
		WriteError(w, http.StatusNotFound, "favorite_list_not_found", "no such favorite list")
	case errors.Is(err, favorite.ErrItemNotFound):
		WriteError(w, http.StatusNotFound, "favorite_item_not_found", "path is not in this list")
	case errors.Is(err, usecase.ErrFavoriteNameInvalid):
		WriteError(w, http.StatusBadRequest, "favorite_name_invalid", "name fails §8.4 validation")
	case errors.Is(err, usecase.ErrFavoriteEmptyPatch):
		WriteError(w, http.StatusBadRequest, "bad_request", "patch body has no effective fields")
	default:
		h.log.Error("favorite op", slog.String("op", op), slog.String("err", err.Error()))
		WriteError(w, http.StatusInternalServerError, "internal_error", "favorite op failed")
	}
}
