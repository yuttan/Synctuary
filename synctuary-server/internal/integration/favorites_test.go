// favorites_test.go covers PROTOCOL v0.2.3 §8 end-to-end:
//
//   - CRUD on lists (create / list / get / patch / delete)
//   - Item add (idempotent re-add → 200 vs 201) / remove
//   - Hidden flag default-omission and ?include_hidden=true
//   - 404 leak-avoidance for unknown ids (regardless of hidden state)
//   - Path validation on item add
//   - modified_at bump rules (bumped on real change, NOT on idempotent re-add)
package integration

import (
	"bytes"
	"io"
	"net/http"
	"strings"
	"testing"
)

// favListSummary is the wire shape of §8.2 / §8.4 / §8.5 list responses.
type favListSummary struct {
	ID                string  `json:"id"`
	Name              string  `json:"name"`
	Hidden            bool    `json:"hidden"`
	ItemCount         int     `json:"item_count"`
	CreatedAt         int64   `json:"created_at"`
	ModifiedAt        int64   `json:"modified_at"`
	CreatedByDeviceID *string `json:"created_by_device_id"`
}

type favItem struct {
	Path            string  `json:"path"`
	AddedAt         int64   `json:"added_at"`
	AddedByDeviceID *string `json:"added_by_device_id"`
}

type favListDetail struct {
	favListSummary
	Items []favItem `json:"items"`
}

// createFavoriteList is a one-liner helper used across scenarios.
func createFavoriteList(t *testing.T, c *pairedClient, name string, hidden bool) favListSummary {
	t.Helper()
	resp := c.doJSON(t, "POST", "/api/v1/favorites", map[string]any{
		"name":   name,
		"hidden": hidden,
	})
	expectStatus(t, resp, http.StatusCreated, "create favorite")
	var got favListSummary
	decodeJSON(t, resp, &got)
	return got
}

// TestFavoriteCRUD covers the happy path: create, list, get,
// patch (rename), delete, then verify 404 on the deleted id.
func TestFavoriteCRUD(t *testing.T) {
	env := newTestEnv(t)
	defer env.cleanup()
	c := pairDevice(t, env, "uploader", "linux")

	// Initially empty.
	resp := c.do(t, "GET", "/api/v1/favorites", nil, nil)
	expectStatus(t, resp, http.StatusOK, "list empty")
	var initialList struct {
		Lists []favListSummary `json:"lists"`
	}
	decodeJSON(t, resp, &initialList)
	if len(initialList.Lists) != 0 {
		t.Fatalf("initial lists=%d, want 0", len(initialList.Lists))
	}

	// Create one.
	created := createFavoriteList(t, c, "Trip 2026", false)
	if created.Name != "Trip 2026" || created.Hidden || created.ItemCount != 0 {
		t.Errorf("created summary unexpected: %+v", created)
	}
	if created.CreatedAt == 0 || created.ModifiedAt != created.CreatedAt {
		t.Errorf("created/modified timestamps off: %+v", created)
	}
	if created.CreatedByDeviceID == nil || *created.CreatedByDeviceID == "" {
		t.Errorf("created_by_device_id should be present, got %v", created.CreatedByDeviceID)
	}

	// List shows the new entry.
	resp = c.do(t, "GET", "/api/v1/favorites", nil, nil)
	expectStatus(t, resp, http.StatusOK, "list after create")
	var listResp struct {
		Lists []favListSummary `json:"lists"`
	}
	decodeJSON(t, resp, &listResp)
	if len(listResp.Lists) != 1 || listResp.Lists[0].ID != created.ID {
		t.Fatalf("list after create: %+v", listResp)
	}

	// Get returns full detail (empty items).
	resp = c.do(t, "GET", "/api/v1/favorites/"+created.ID, nil, nil)
	expectStatus(t, resp, http.StatusOK, "get detail")
	var detail favListDetail
	decodeJSON(t, resp, &detail)
	if detail.ID != created.ID || len(detail.Items) != 0 {
		t.Fatalf("detail unexpected: %+v", detail)
	}

	// PATCH name.
	resp = c.doJSON(t, "PATCH", "/api/v1/favorites/"+created.ID, map[string]any{
		"name": "Trip 2026 Spring",
	})
	expectStatus(t, resp, http.StatusOK, "patch")
	var patched favListSummary
	decodeJSON(t, resp, &patched)
	if patched.Name != "Trip 2026 Spring" {
		t.Errorf("patched name=%q", patched.Name)
	}
	if patched.ModifiedAt < created.ModifiedAt {
		t.Errorf("modified_at went backwards: %d < %d", patched.ModifiedAt, created.ModifiedAt)
	}

	// DELETE.
	resp = c.do(t, "DELETE", "/api/v1/favorites/"+created.ID, nil, nil)
	if resp.StatusCode != http.StatusNoContent {
		t.Errorf("delete status=%d, want 204", resp.StatusCode)
	}
	_ = resp.Body.Close()

	// Now 404 on subsequent GET.
	resp = c.do(t, "GET", "/api/v1/favorites/"+created.ID, nil, nil)
	if resp.StatusCode != http.StatusNotFound {
		t.Errorf("post-delete GET status=%d, want 404", resp.StatusCode)
	}
	_ = resp.Body.Close()
}

// TestFavoriteHiddenSoftHide checks §8.2 default-omission and the
// ?include_hidden=true override. Also covers the §8.3 leak-avoidance
// rule: GET on a hidden list's id still returns 200, not 404 — the
// id is not secret, the *existence* is. (Per §8.3, only an unknown
// id returns 404.)
func TestFavoriteHiddenSoftHide(t *testing.T) {
	env := newTestEnv(t)
	defer env.cleanup()
	c := pairDevice(t, env, "uploader", "linux")

	visible := createFavoriteList(t, c, "Visible", false)
	hidden := createFavoriteList(t, c, "Hidden", true)

	// Default GET omits the hidden one.
	resp := c.do(t, "GET", "/api/v1/favorites", nil, nil)
	expectStatus(t, resp, http.StatusOK, "list default")
	var def struct {
		Lists []favListSummary `json:"lists"`
	}
	decodeJSON(t, resp, &def)
	if len(def.Lists) != 1 || def.Lists[0].ID != visible.ID {
		t.Fatalf("default list: %+v (want only %s visible)", def, visible.ID)
	}

	// ?include_hidden=true returns both. Order is modified_at DESC,
	// id DESC tiebreaker — deterministic but id-dependent, so we
	// just verify the set, not the sequence.
	resp = c.do(t, "GET", "/api/v1/favorites?include_hidden=true", nil, nil)
	expectStatus(t, resp, http.StatusOK, "list with hidden")
	var withHidden struct {
		Lists []favListSummary `json:"lists"`
	}
	decodeJSON(t, resp, &withHidden)
	if len(withHidden.Lists) != 2 {
		t.Fatalf("with_hidden: got %d lists, want 2", len(withHidden.Lists))
	}
	seen := map[string]bool{}
	for _, l := range withHidden.Lists {
		seen[l.ID] = true
	}
	if !seen[hidden.ID] || !seen[visible.ID] {
		t.Errorf("missing ids: hidden=%v visible=%v in %+v",
			seen[hidden.ID], seen[visible.ID], withHidden.Lists)
	}

	// Direct GET on a hidden list still returns 200 — id-knowledge
	// is sufficient (§8.3 + §8.9 soft-hide model).
	resp = c.do(t, "GET", "/api/v1/favorites/"+hidden.ID, nil, nil)
	expectStatus(t, resp, http.StatusOK, "get hidden by id")
	var hd favListDetail
	decodeJSON(t, resp, &hd)
	if !hd.Hidden {
		t.Errorf("expected hidden=true on get")
	}
}

// TestFavoriteUnknownID404 confirms that bogus ids (whether malformed
// base64url, wrong length, or genuinely-not-found) all return
// 404 favorite_list_not_found — same code, no leak.
func TestFavoriteUnknownID404(t *testing.T) {
	env := newTestEnv(t)
	defer env.cleanup()
	c := pairDevice(t, env, "uploader", "linux")

	cases := []struct {
		name string
		id   string
	}{
		{"malformed_base64url", "not!valid!base64"},
		{"wrong_length_short", "AAAA"},
		{"wrong_length_long", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"},
		{"valid_shape_but_nonexistent", "AAAAAAAAAAAAAAAAAAAAAA"}, // 22 chars = 16 bytes of zeros
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			resp := c.do(t, "GET", "/api/v1/favorites/"+tc.id, nil, nil)
			if resp.StatusCode != http.StatusNotFound {
				t.Errorf("status=%d, want 404", resp.StatusCode)
			}
			var body struct {
				Error struct {
					Code string `json:"code"`
				} `json:"error"`
			}
			decodeJSON(t, resp, &body)
			if body.Error.Code != "favorite_list_not_found" {
				t.Errorf("code=%q, want favorite_list_not_found", body.Error.Code)
			}
		})
	}
}

// TestFavoriteItemIdempotentAdd validates §8.7's idempotent re-add
// rule: second POST of the same path returns 200 (not 201) and does
// NOT advance modified_at on the parent list.
func TestFavoriteItemIdempotentAdd(t *testing.T) {
	env := newTestEnv(t)
	defer env.cleanup()
	c := pairDevice(t, env, "uploader", "linux")

	list := createFavoriteList(t, c, "My Favorites", false)

	// First add → 201.
	resp := c.doJSON(t, "POST", "/api/v1/favorites/"+list.ID+"/items", map[string]any{
		"path": "/test.txt",
	})
	expectStatus(t, resp, http.StatusCreated, "first item add")
	var firstItem favItem
	decodeJSON(t, resp, &firstItem)

	// Capture parent's modified_at after the add.
	resp = c.do(t, "GET", "/api/v1/favorites/"+list.ID, nil, nil)
	expectStatus(t, resp, http.StatusOK, "get after first add")
	var afterFirst favListDetail
	decodeJSON(t, resp, &afterFirst)
	if afterFirst.ItemCount != 1 || len(afterFirst.Items) != 1 {
		t.Fatalf("after first add: %+v", afterFirst)
	}
	if afterFirst.ModifiedAt < list.ModifiedAt {
		t.Errorf("modified_at went backwards: %d < %d", afterFirst.ModifiedAt, list.ModifiedAt)
	}

	// Second add of same path → 200, returns existing entry.
	resp = c.doJSON(t, "POST", "/api/v1/favorites/"+list.ID+"/items", map[string]any{
		"path": "/test.txt",
	})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("second add status=%d, want 200 (idempotent)", resp.StatusCode)
	}
	var secondItem favItem
	decodeJSON(t, resp, &secondItem)
	if secondItem.AddedAt != firstItem.AddedAt {
		t.Errorf("re-add mutated added_at: first=%d second=%d", firstItem.AddedAt, secondItem.AddedAt)
	}

	// Parent's modified_at should NOT have advanced past afterFirst.
	resp = c.do(t, "GET", "/api/v1/favorites/"+list.ID, nil, nil)
	expectStatus(t, resp, http.StatusOK, "get after second add")
	var afterSecond favListDetail
	decodeJSON(t, resp, &afterSecond)
	if afterSecond.ItemCount != 1 {
		t.Errorf("item_count after re-add: %d, want 1", afterSecond.ItemCount)
	}
	if afterSecond.ModifiedAt != afterFirst.ModifiedAt {
		t.Errorf("idempotent re-add bumped modified_at: %d → %d",
			afterFirst.ModifiedAt, afterSecond.ModifiedAt)
	}
}

// TestFavoriteItemRemove covers §8.8: successful remove (204), then
// 404 favorite_item_not_found on a second remove of the same path.
// Also verifies that removing from an unknown list returns
// favorite_list_not_found, not favorite_item_not_found.
func TestFavoriteItemRemove(t *testing.T) {
	env := newTestEnv(t)
	defer env.cleanup()
	c := pairDevice(t, env, "uploader", "linux")

	list := createFavoriteList(t, c, "Remove tests", false)

	// Add then remove.
	resp := c.doJSON(t, "POST", "/api/v1/favorites/"+list.ID+"/items", map[string]any{
		"path": "/foo.txt",
	})
	expectStatus(t, resp, http.StatusCreated, "add")
	_ = resp.Body.Close()

	resp = c.do(t, "DELETE", "/api/v1/favorites/"+list.ID+"/items?path="+urlEscape("/foo.txt"), nil, nil)
	if resp.StatusCode != http.StatusNoContent {
		t.Errorf("first delete status=%d, want 204", resp.StatusCode)
	}
	_ = resp.Body.Close()

	// Second delete → 404 favorite_item_not_found.
	resp = c.do(t, "DELETE", "/api/v1/favorites/"+list.ID+"/items?path="+urlEscape("/foo.txt"), nil, nil)
	if resp.StatusCode != http.StatusNotFound {
		t.Errorf("second delete status=%d, want 404", resp.StatusCode)
	}
	var body struct {
		Error struct {
			Code string `json:"code"`
		} `json:"error"`
	}
	decodeJSON(t, resp, &body)
	if body.Error.Code != "favorite_item_not_found" {
		t.Errorf("code=%q, want favorite_item_not_found", body.Error.Code)
	}

	// Remove on unknown list → favorite_list_not_found, not item.
	resp = c.do(t, "DELETE", "/api/v1/favorites/AAAAAAAAAAAAAAAAAAAAAA/items?path="+urlEscape("/foo.txt"), nil, nil)
	if resp.StatusCode != http.StatusNotFound {
		t.Errorf("unknown list delete: status=%d, want 404", resp.StatusCode)
	}
	decodeJSON(t, resp, &body)
	if body.Error.Code != "favorite_list_not_found" {
		t.Errorf("unknown list code=%q, want favorite_list_not_found", body.Error.Code)
	}
}

// TestFavoriteNameValidation covers §8.4 / §8.5 name rules.
func TestFavoriteNameValidation(t *testing.T) {
	env := newTestEnv(t)
	defer env.cleanup()
	c := pairDevice(t, env, "uploader", "linux")

	bad := []struct {
		label string
		name  string
	}{
		{"empty", ""},
		{"only_whitespace", "   "},
		{"leading_whitespace", " name"},
		{"trailing_whitespace", "name "},
		{"control_byte", "bad\x01name"},
		{"newline", "line1\nline2"},
		{"tab", "with\ttab"},
		{"too_long", strings.Repeat("a", 257)},
	}
	for _, tc := range bad {
		t.Run("create_"+tc.label, func(t *testing.T) {
			resp := c.doJSON(t, "POST", "/api/v1/favorites", map[string]any{
				"name":   tc.name,
				"hidden": false,
			})
			if resp.StatusCode != http.StatusBadRequest {
				body, _ := io.ReadAll(resp.Body)
				_ = resp.Body.Close()
				t.Fatalf("status=%d, want 400 (body: %s)", resp.StatusCode, body)
			}
			_ = resp.Body.Close()
		})
	}

	// Boundary: 256 chars exactly should succeed.
	t.Run("create_max_length", func(t *testing.T) {
		resp := c.doJSON(t, "POST", "/api/v1/favorites", map[string]any{
			"name":   strings.Repeat("x", 256),
			"hidden": false,
		})
		expectStatus(t, resp, http.StatusCreated, "256-char name")
		_ = resp.Body.Close()
	})
}

// TestFavoritePatchEmpty rejects PATCH with no effective fields.
func TestFavoritePatchEmpty(t *testing.T) {
	env := newTestEnv(t)
	defer env.cleanup()
	c := pairDevice(t, env, "uploader", "linux")

	list := createFavoriteList(t, c, "patch test", false)

	// Empty object body.
	resp := c.do(t, "PATCH", "/api/v1/favorites/"+list.ID, []byte("{}"),
		map[string]string{"Content-Type": "application/json"})
	if resp.StatusCode != http.StatusBadRequest {
		t.Errorf("empty patch status=%d, want 400", resp.StatusCode)
	}
	_ = resp.Body.Close()
}

// TestFavoritePatchHiddenToggle: explicitly setting hidden=true via
// PATCH must work even though Go's zero-value for bool is also false.
// The pointer-typed DTO field is what makes this distinguishable.
func TestFavoritePatchHiddenToggle(t *testing.T) {
	env := newTestEnv(t)
	defer env.cleanup()
	c := pairDevice(t, env, "uploader", "linux")

	list := createFavoriteList(t, c, "togglable", false)

	// Toggle to hidden=true.
	resp := c.doJSON(t, "PATCH", "/api/v1/favorites/"+list.ID, map[string]any{
		"hidden": true,
	})
	expectStatus(t, resp, http.StatusOK, "patch hidden=true")
	var got favListSummary
	decodeJSON(t, resp, &got)
	if !got.Hidden {
		t.Errorf("expected hidden=true after patch, got %+v", got)
	}

	// Toggle back to hidden=false.
	resp = c.doJSON(t, "PATCH", "/api/v1/favorites/"+list.ID, map[string]any{
		"hidden": false,
	})
	expectStatus(t, resp, http.StatusOK, "patch hidden=false")
	decodeJSON(t, resp, &got)
	if got.Hidden {
		t.Errorf("expected hidden=false after patch, got %+v", got)
	}
}

// TestFavoriteItemPathValidation: §8.7 path rules apply.
func TestFavoriteItemPathValidation(t *testing.T) {
	env := newTestEnv(t)
	defer env.cleanup()
	c := pairDevice(t, env, "uploader", "linux")

	list := createFavoriteList(t, c, "path tests", false)

	bad := []string{
		"",
		"foo",         // no leading slash
		"/foo/../bar", // traversal
		"/foo\x00bar", // NUL
		"/foo\nbar",   // newline
		"/CON",        // windows reserved
	}
	for _, p := range bad {
		t.Run("path="+p, func(t *testing.T) {
			resp := c.doJSON(t, "POST", "/api/v1/favorites/"+list.ID+"/items",
				map[string]any{"path": p})
			if resp.StatusCode != http.StatusBadRequest {
				t.Errorf("path=%q status=%d, want 400", p, resp.StatusCode)
			}
			_ = resp.Body.Close()
		})
	}
}

// TestFavoriteCrossDeviceVisibility: a list created by alice MUST
// be visible to bob (both paired with the same master_key). This is
// the §8 "server-managed, not per-device" guarantee.
func TestFavoriteCrossDeviceVisibility(t *testing.T) {
	env := newTestEnv(t)
	defer env.cleanup()

	alice := pairDevice(t, env, "alice", "linux")
	bob := pairDevice(t, env, "bob", "android")

	created := createFavoriteList(t, alice, "Shared", false)

	// Bob can list it.
	resp := bob.do(t, "GET", "/api/v1/favorites", nil, nil)
	expectStatus(t, resp, http.StatusOK, "bob list")
	var lr struct {
		Lists []favListSummary `json:"lists"`
	}
	decodeJSON(t, resp, &lr)
	if len(lr.Lists) != 1 || lr.Lists[0].ID != created.ID {
		t.Fatalf("bob view: %+v", lr)
	}
	if lr.Lists[0].CreatedByDeviceID == nil {
		t.Errorf("created_by_device_id should be alice's, got nil")
	}

	// Bob can add an item.
	resp = bob.doJSON(t, "POST", "/api/v1/favorites/"+created.ID+"/items",
		map[string]any{"path": "/from-bob.txt"})
	expectStatus(t, resp, http.StatusCreated, "bob add item")
	var addedItem favItem
	decodeJSON(t, resp, &addedItem)
	if addedItem.AddedByDeviceID == nil {
		t.Errorf("added_by_device_id should be bob's, got nil")
	}

	// Alice sees the item.
	resp = alice.do(t, "GET", "/api/v1/favorites/"+created.ID, nil, nil)
	expectStatus(t, resp, http.StatusOK, "alice get")
	var detail favListDetail
	decodeJSON(t, resp, &detail)
	if len(detail.Items) != 1 || detail.Items[0].Path != "/from-bob.txt" {
		t.Fatalf("alice view: %+v", detail)
	}
}

// TestFavoriteEndpointsRequireAuth: all 7 favorite routes are behind
// BearerAuth.
func TestFavoriteEndpointsRequireAuth(t *testing.T) {
	env := newTestEnv(t)
	defer env.cleanup()

	cases := []struct {
		method, path string
		body         []byte
	}{
		{"GET", "/api/v1/favorites", nil},
		{"POST", "/api/v1/favorites", []byte(`{"name":"x"}`)},
		{"GET", "/api/v1/favorites/AAAAAAAAAAAAAAAAAAAAAA", nil},
		{"PATCH", "/api/v1/favorites/AAAAAAAAAAAAAAAAAAAAAA", []byte(`{"name":"y"}`)},
		{"DELETE", "/api/v1/favorites/AAAAAAAAAAAAAAAAAAAAAA", nil},
		{"POST", "/api/v1/favorites/AAAAAAAAAAAAAAAAAAAAAA/items", []byte(`{"path":"/x"}`)},
		{"DELETE", "/api/v1/favorites/AAAAAAAAAAAAAAAAAAAAAA/items?path=/x", nil},
	}
	for _, tc := range cases {
		t.Run(tc.method+"_"+tc.path, func(t *testing.T) {
			var rdr io.Reader
			if tc.body != nil {
				rdr = bytes.NewReader(tc.body)
			}
			req, _ := http.NewRequest(tc.method, env.URL+tc.path, rdr)
			if tc.body != nil {
				req.Header.Set("Content-Type", "application/json")
			}
			resp, err := http.DefaultClient.Do(req)
			if err != nil {
				t.Fatalf("do: %v", err)
			}
			defer resp.Body.Close()
			if resp.StatusCode != http.StatusUnauthorized {
				t.Errorf("status=%d, want 401", resp.StatusCode)
			}
		})
	}
}
