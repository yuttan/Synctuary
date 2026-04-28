package integration

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"net/http"
	"testing"
)

// TestInfo verifies the unauthenticated /info endpoint exposes the
// PROTOCOL §5.1 schema with all required fields.
func TestInfo(t *testing.T) {
	env := newTestEnv(t)
	defer env.cleanup()

	resp, err := http.Get(env.URL + "/api/v1/info")
	if err != nil {
		t.Fatalf("info: %v", err)
	}
	expectStatus(t, resp, http.StatusOK, "info")

	var body map[string]any
	decodeJSON(t, resp, &body)

	for _, k := range []string{
		"protocol_version", "server_version", "server_id",
		"server_name", "encryption_mode", "transport_profile",
		"capabilities", "tls_fingerprint",
	} {
		if _, ok := body[k]; !ok {
			t.Errorf("missing field %q in /info response: %+v", k, body)
		}
	}
	if got := body["protocol_version"]; got != "0.2.3" {
		t.Errorf("protocol_version=%v, want 0.2.3", got)
	}
	// server_id MUST be 16-byte base64url-without-padding (22 chars).
	id, ok := body["server_id"].(string)
	if !ok || len(id) != 22 {
		t.Errorf("server_id encoding suspicious: %q (len=%d)", id, len(id))
	}
	if dec, err := b64url.DecodeString(id); err != nil || len(dec) != 16 {
		t.Errorf("server_id decode: err=%v len=%d", err, len(dec))
	}
}

// TestPairingHappyPath checks a clean pair flow yields a 32-byte token
// and the device shows up in /devices with current=true.
func TestPairingHappyPath(t *testing.T) {
	env := newTestEnv(t)
	defer env.cleanup()

	c := pairDevice(t, env, "alice-laptop", "linux")
	if len(c.Token) != 32 {
		t.Errorf("token len=%d", len(c.Token))
	}

	resp := c.do(t, "GET", "/api/v1/devices", nil, nil)
	expectStatus(t, resp, http.StatusOK, "devices list")
	var lr struct {
		Devices []map[string]any
	}
	decodeJSON(t, resp, &lr)
	if len(lr.Devices) != 1 {
		t.Fatalf("expected 1 device, got %d", len(lr.Devices))
	}
	d := lr.Devices[0]
	if d["device_name"] != "alice-laptop" {
		t.Errorf("device_name=%v", d["device_name"])
	}
	if d["current"] != true {
		t.Errorf("current=%v, want true", d["current"])
	}
	if d["revoked"] != false {
		t.Errorf("revoked=%v, want false", d["revoked"])
	}
}

// TestPairingBadSignature: nonce is consumed regardless (§4.4 step 1),
// signature verification fails → 401 pair_signature_invalid.
func TestPairingBadSignature(t *testing.T) {
	env := newTestEnv(t)
	defer env.cleanup()

	devID, _ := mustEntropy(t, 16)
	pub, _, err := mustDeriveDevice(t, env.masterKey, devID)
	if err != nil {
		t.Fatal(err)
	}

	resp, err := http.Post(env.URL+"/api/v1/pair/nonce", "application/json", nil)
	if err != nil {
		t.Fatal(err)
	}
	var nb struct{ Nonce string }
	decodeJSON(t, resp, &nb)

	// 64 zero bytes — well-formed length, never a valid Ed25519 signature.
	badSig := make([]byte, 64)
	body := map[string]any{
		"nonce":              nb.Nonce,
		"device_id":          b64url.EncodeToString(devID),
		"device_pub":         b64url.EncodeToString(pub),
		"device_name":        "evil",
		"platform":           "linux",
		"challenge_response": b64url.EncodeToString(badSig),
	}
	raw, _ := jsonMarshal(body)
	resp, err = http.Post(env.URL+"/api/v1/pair/register", "application/json", bytes.NewReader(raw))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Errorf("status=%d, want 401", resp.StatusCode)
	}
}

// TestPairingNonceReplay: consuming the same nonce twice → 410 on the
// second attempt (PROTOCOL §4.4 step 1, "consume FIRST" guarantee).
func TestPairingNonceReplay(t *testing.T) {
	env := newTestEnv(t)
	defer env.cleanup()

	// First pair, succeeds.
	c := pairDevice(t, env, "first", "linux")
	_ = c

	// Now try to register a different device with a brand-new nonce
	// but reuse it twice. We need to capture a nonce, send register
	// twice with it. Both attempts will fail because we're not signing
	// — but the *first* attempt goes through nonce consumption first.
	// To exercise the replay code path cleanly, perform a second pair
	// to grab a real nonce, register validly once, then immediately
	// attempt a second register with the *same* nonce.

	devID, _ := mustEntropy(t, 16)
	pub, priv, err := mustDeriveDevice(t, env.masterKey, devID)
	if err != nil {
		t.Fatal(err)
	}

	resp, err := http.Post(env.URL+"/api/v1/pair/nonce", "application/json", nil)
	if err != nil {
		t.Fatalf("nonce: %v", err)
	}
	var nb struct{ Nonce string }
	decodeJSON(t, resp, &nb)
	nonce, _ := b64url.DecodeString(nb.Nonce)

	payload, _ := mustBuildPayload(t, devID, pub, env.fingerprint, nonce)
	sig := mustSign(priv, payload)

	body := map[string]any{
		"nonce":              nb.Nonce,
		"device_id":          b64url.EncodeToString(devID),
		"device_pub":         b64url.EncodeToString(pub),
		"device_name":        "second",
		"platform":           "linux",
		"challenge_response": b64url.EncodeToString(sig),
	}
	raw, _ := jsonMarshal(body)

	// First register: succeeds.
	resp, err = http.Post(env.URL+"/api/v1/pair/register", "application/json", bytes.NewReader(raw))
	if err != nil {
		t.Fatalf("first register: %v", err)
	}
	expectStatus(t, resp, http.StatusOK, "first register")
	_ = resp.Body.Close()

	// Second register with the SAME nonce: must be 410 pair_nonce_expired.
	resp, err = http.Post(env.URL+"/api/v1/pair/register", "application/json", bytes.NewReader(raw))
	if err != nil {
		t.Fatalf("replay register: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusGone {
		t.Errorf("replay status=%d, want 410", resp.StatusCode)
	}
}

// TestUnauthorizedAccess confirms BearerAuth blocks every protected
// endpoint when the token is missing or wrong.
func TestUnauthorizedAccess(t *testing.T) {
	env := newTestEnv(t)
	defer env.cleanup()

	cases := []struct {
		method, path string
	}{
		{"GET", "/api/v1/files?path=/"},
		{"DELETE", "/api/v1/files?path=/foo"},
		{"GET", "/api/v1/files/content?path=/foo"},
		{"POST", "/api/v1/files/upload/init"},
		{"GET", "/api/v1/devices"},
	}
	for _, tc := range cases {
		t.Run(tc.method+" "+tc.path, func(t *testing.T) {
			req, _ := http.NewRequest(tc.method, env.URL+tc.path, nil)
			resp, err := http.DefaultClient.Do(req)
			if err != nil {
				t.Fatalf("do: %v", err)
			}
			defer resp.Body.Close()
			if resp.StatusCode != http.StatusUnauthorized {
				t.Errorf("no-auth status=%d, want 401", resp.StatusCode)
			}

			req2, _ := http.NewRequest(tc.method, env.URL+tc.path, nil)
			req2.Header.Set("Authorization", "Bearer "+b64url.EncodeToString(make([]byte, 32)))
			resp2, err := http.DefaultClient.Do(req2)
			if err != nil {
				t.Fatalf("do bad-token: %v", err)
			}
			defer resp2.Body.Close()
			if resp2.StatusCode != http.StatusUnauthorized {
				t.Errorf("bad-token status=%d, want 401", resp2.StatusCode)
			}
		})
	}
}

// TestUploadSingleChunkRoundTrip exercises the smallest end-to-end flow:
// init → one chunk → list → content → range download → delete.
func TestUploadSingleChunkRoundTrip(t *testing.T) {
	env := newTestEnv(t)
	defer env.cleanup()
	c := pairDevice(t, env, "uploader", "linux")

	content := []byte("hello synctuary world\nthis is a single-chunk test\n")
	uploadOne(t, c, "/test.txt", content, false)

	// list /
	resp := c.do(t, "GET", "/api/v1/files?path=/&hash=true", nil, nil)
	expectStatus(t, resp, http.StatusOK, "list /")
	var lr struct {
		Path    string
		Entries []map[string]any
	}
	decodeJSON(t, resp, &lr)
	if len(lr.Entries) != 1 {
		t.Fatalf("entries=%d, want 1", len(lr.Entries))
	}
	e := lr.Entries[0]
	if e["name"] != "test.txt" {
		t.Errorf("name=%v", e["name"])
	}
	if e["type"] != "file" {
		t.Errorf("type=%v", e["type"])
	}
	// hash=true must surface sha256 (string OR null per scheme fix)
	if _, ok := e["sha256"]; !ok {
		t.Errorf("sha256 absent in entry; expected string or nil with hash=true")
	}

	// full download
	resp = c.do(t, "GET", "/api/v1/files/content?path=/test.txt", nil, nil)
	expectStatus(t, resp, http.StatusOK, "download")
	got, _ := io.ReadAll(resp.Body)
	_ = resp.Body.Close()
	if !bytes.Equal(got, content) {
		t.Errorf("download mismatch: got %d bytes, want %d", len(got), len(content))
	}

	// range download
	resp = c.do(t, "GET", "/api/v1/files/content?path=/test.txt", nil, map[string]string{
		"Range": "bytes=0-4",
	})
	if resp.StatusCode != http.StatusPartialContent {
		t.Errorf("range status=%d, want 206", resp.StatusCode)
	}
	partial, _ := io.ReadAll(resp.Body)
	_ = resp.Body.Close()
	if string(partial) != "hello" {
		t.Errorf("range body=%q, want 'hello'", partial)
	}

	// delete
	resp = c.do(t, "DELETE", "/api/v1/files?path=/test.txt", nil, nil)
	if resp.StatusCode != http.StatusNoContent {
		t.Errorf("delete status=%d, want 204", resp.StatusCode)
	}
	_ = resp.Body.Close()

	// gone
	resp = c.do(t, "GET", "/api/v1/files/content?path=/test.txt", nil, nil)
	if resp.StatusCode != http.StatusNotFound {
		t.Errorf("post-delete status=%d, want 404", resp.StatusCode)
	}
	_ = resp.Body.Close()
}

// TestUploadMultiChunkResume validates resumable-upload happy path:
// init, partial chunk, progress, final chunk → completed.
func TestUploadMultiChunkResume(t *testing.T) {
	env := newTestEnv(t)
	defer env.cleanup()
	c := pairDevice(t, env, "uploader", "linux")

	// 200 KiB payload, two chunks of 100 KiB each.
	content := make([]byte, 200*1024)
	for i := range content {
		content[i] = byte(i & 0xff)
	}
	sum := sha256.Sum256(content)

	initBody := map[string]any{
		"path":      "/large.bin",
		"size":      len(content),
		"sha256":    hex.EncodeToString(sum[:]),
		"overwrite": false,
	}
	resp := c.doJSON(t, "POST", "/api/v1/files/upload/init", initBody)
	expectStatus(t, resp, http.StatusCreated, "init")
	var initResp struct {
		UploadID string `json:"upload_id"`
	}
	decodeJSON(t, resp, &initResp)

	half := len(content) / 2
	chunk1 := content[:half]
	chunk2 := content[half:]

	// chunk 1
	resp = c.do(t, "PUT", "/api/v1/files/upload/"+initResp.UploadID, chunk1, map[string]string{
		"Content-Type":  "application/octet-stream",
		"Content-Range": fmt.Sprintf("bytes 0-%d/%d", half-1, len(content)),
	})
	expectStatus(t, resp, http.StatusOK, "chunk 1")
	var pr struct {
		UploadedBytes int64 `json:"uploaded_bytes"`
		Complete      bool  `json:"complete"`
	}
	decodeJSON(t, resp, &pr)
	if pr.UploadedBytes != int64(half) || pr.Complete {
		t.Errorf("after chunk1: uploaded=%d complete=%v", pr.UploadedBytes, pr.Complete)
	}

	// progress poll
	resp = c.do(t, "GET", "/api/v1/files/upload/"+initResp.UploadID, nil, nil)
	expectStatus(t, resp, http.StatusOK, "progress")
	var pg struct {
		UploadedBytes int64 `json:"uploaded_bytes"`
		Complete      bool  `json:"complete"`
	}
	decodeJSON(t, resp, &pg)
	if pg.UploadedBytes != int64(half) || pg.Complete {
		t.Errorf("progress: uploaded=%d complete=%v", pg.UploadedBytes, pg.Complete)
	}

	// chunk 2 (final)
	resp = c.do(t, "PUT", "/api/v1/files/upload/"+initResp.UploadID, chunk2, map[string]string{
		"Content-Type":  "application/octet-stream",
		"Content-Range": fmt.Sprintf("bytes %d-%d/%d", half, len(content)-1, len(content)),
	})
	expectStatus(t, resp, http.StatusOK, "chunk 2")
	decodeJSON(t, resp, &pr)
	if !pr.Complete {
		t.Errorf("final chunk: complete=%v, want true", pr.Complete)
	}

	// download and verify exact bytes
	resp = c.do(t, "GET", "/api/v1/files/content?path=/large.bin", nil, nil)
	expectStatus(t, resp, http.StatusOK, "download large")
	got, _ := io.ReadAll(resp.Body)
	_ = resp.Body.Close()
	if !bytes.Equal(got, content) {
		t.Errorf("large download mismatch (got %d bytes, want %d)", len(got), len(content))
	}
}

// TestUploadHashMismatch: declared sha256 ≠ actual content → 422 on
// the final chunk, session invalidated.
func TestUploadHashMismatch(t *testing.T) {
	env := newTestEnv(t)
	defer env.cleanup()
	c := pairDevice(t, env, "uploader", "linux")

	content := []byte("real content")
	wrongHash := make([]byte, 32) // all-zero is virtually impossible to produce naturally
	initBody := map[string]any{
		"path":      "/wrong.txt",
		"size":      len(content),
		"sha256":    hex.EncodeToString(wrongHash),
		"overwrite": false,
	}
	resp := c.doJSON(t, "POST", "/api/v1/files/upload/init", initBody)
	expectStatus(t, resp, http.StatusCreated, "init")
	var initResp struct {
		UploadID string `json:"upload_id"`
	}
	decodeJSON(t, resp, &initResp)

	resp = c.do(t, "PUT", "/api/v1/files/upload/"+initResp.UploadID, content, map[string]string{
		"Content-Type":  "application/octet-stream",
		"Content-Range": fmt.Sprintf("bytes 0-%d/%d", len(content)-1, len(content)),
	})
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusUnprocessableEntity {
		t.Errorf("status=%d, want 422", resp.StatusCode)
	}
}

// TestFileExistsConflict: re-init with the same path AND a *different*
// sha256 with overwrite=false → 409 file_exists.
func TestFileExistsConflict(t *testing.T) {
	env := newTestEnv(t)
	defer env.cleanup()
	c := pairDevice(t, env, "uploader", "linux")

	content1 := []byte("first version")
	content2 := []byte("second version, different content!")
	uploadOne(t, c, "/conflict.txt", content1, false)

	sum2 := sha256.Sum256(content2)
	initBody := map[string]any{
		"path":      "/conflict.txt",
		"size":      len(content2),
		"sha256":    hex.EncodeToString(sum2[:]),
		"overwrite": false,
	}
	resp := c.doJSON(t, "POST", "/api/v1/files/upload/init", initBody)
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusConflict {
		t.Errorf("status=%d, want 409", resp.StatusCode)
	}
}

// TestDedupSamePathSameContent: re-uploading exactly the same bytes
// to the same path is the no-op dedup branch.
func TestDedupSamePathSameContent(t *testing.T) {
	env := newTestEnv(t)
	defer env.cleanup()
	c := pairDevice(t, env, "uploader", "linux")

	content := []byte("dedup me")
	uploadOne(t, c, "/dup.txt", content, false)

	// Second init with same path + same content + overwrite=true should
	// short-circuit as deduplicated.
	sum := sha256.Sum256(content)
	initBody := map[string]any{
		"path":      "/dup.txt",
		"size":      len(content),
		"sha256":    hex.EncodeToString(sum[:]),
		"overwrite": true,
	}
	resp := c.doJSON(t, "POST", "/api/v1/files/upload/init", initBody)
	expectStatus(t, resp, http.StatusOK, "dedup init")
	var dr struct {
		UploadID *string `json:"upload_id"`
		Status   string  `json:"status"`
	}
	decodeJSON(t, resp, &dr)
	if dr.Status != "deduplicated" {
		t.Errorf("status=%q, want deduplicated", dr.Status)
	}
	if dr.UploadID != nil {
		t.Errorf("upload_id=%v, want nil for dedup", dr.UploadID)
	}
}

// TestDedupCrossPath: uploading the same content to a *different* path
// should short-circuit via the dedup branch (hardlink on the underlying
// store). This exercises FileService.InitUpload step 2 + the storage
// DeduplicateLink path, which TestDedupSamePathSameContent skips
// because that test takes the same-path no-op branch first.
//
// On Windows + NTFS the hardlink path is supported; if the test ever
// runs on a filesystem where DeduplicateLink returns ErrDedupUnsupported
// and dedup_fallback="fallthrough" is configured, the server falls back
// to a normal upload session and we'd get 201 Created. The harness
// pins fallthrough, so we accept either 200 deduplicated OR 201
// (fallback path) and verify the second file resolves to identical
// bytes either way.
func TestDedupCrossPath(t *testing.T) {
	env := newTestEnv(t)
	defer env.cleanup()
	c := pairDevice(t, env, "uploader", "linux")

	content := []byte("cross-path dedup payload — should hardlink across paths\n")
	uploadOne(t, c, "/source.txt", content, false)

	// Second init with SAME content but DIFFERENT path. With
	// overwrite=false (no existing file at /target.txt) the service
	// reaches the FindBySHA branch and tries DeduplicateLink.
	sum := sha256.Sum256(content)
	initBody := map[string]any{
		"path":      "/target.txt",
		"size":      len(content),
		"sha256":    hex.EncodeToString(sum[:]),
		"overwrite": false,
	}
	resp := c.doJSON(t, "POST", "/api/v1/files/upload/init", initBody)

	switch resp.StatusCode {
	case http.StatusOK:
		// Hardlink success path — preferred outcome on NTFS/ext4.
		var dr struct {
			UploadID *string `json:"upload_id"`
			Status   string  `json:"status"`
		}
		decodeJSON(t, resp, &dr)
		if dr.Status != "deduplicated" {
			t.Fatalf("status=%q, want deduplicated", dr.Status)
		}
		if dr.UploadID != nil {
			t.Errorf("upload_id=%v, want nil for dedup", dr.UploadID)
		}
	case http.StatusCreated:
		// Fallthrough path: storage rejected the link (e.g. ReFS or a
		// cross-device tmpfs in CI). Complete the upload normally so
		// the byte-identity check below still runs.
		var ir struct {
			UploadID string `json:"upload_id"`
		}
		decodeJSON(t, resp, &ir)
		resp = c.do(t, "PUT", "/api/v1/files/upload/"+ir.UploadID, content, map[string]string{
			"Content-Type":  "application/octet-stream",
			"Content-Range": fmt.Sprintf("bytes 0-%d/%d", len(content)-1, len(content)),
		})
		expectStatus(t, resp, http.StatusOK, "fallback chunk PUT")
		_ = resp.Body.Close()
	default:
		body, _ := io.ReadAll(resp.Body)
		_ = resp.Body.Close()
		t.Fatalf("init: status=%d, want 200 or 201 (body: %s)", resp.StatusCode, body)
	}

	// Both files must download to the *exact* same bytes regardless of
	// which dedup branch fired.
	for _, p := range []string{"/source.txt", "/target.txt"} {
		resp := c.do(t, "GET", "/api/v1/files/content?path="+urlEscape(p), nil, nil)
		expectStatus(t, resp, http.StatusOK, "download "+p)
		got, _ := io.ReadAll(resp.Body)
		_ = resp.Body.Close()
		if !bytes.Equal(got, content) {
			t.Errorf("%s download mismatch: got %d bytes, want %d", p, len(got), len(content))
		}
	}

	// And both must list with the same SHA-256 (proving they refer to
	// equal content even if the storage layer chose to copy rather than
	// link).
	resp = c.do(t, "GET", "/api/v1/files?path=/&hash=true", nil, nil)
	expectStatus(t, resp, http.StatusOK, "list /")
	var lr struct {
		Entries []map[string]any
	}
	decodeJSON(t, resp, &lr)
	if len(lr.Entries) != 2 {
		t.Fatalf("entries=%d, want 2", len(lr.Entries))
	}
	wantHex := hex.EncodeToString(sum[:])
	for _, e := range lr.Entries {
		if e["sha256"] != wantHex {
			t.Errorf("entry %v sha256=%v, want %s", e["name"], e["sha256"], wantHex)
		}
	}
}

// TestDeviceRevoke: revoking a device immediately invalidates its token.
func TestDeviceRevoke(t *testing.T) {
	env := newTestEnv(t)
	defer env.cleanup()

	// Two devices: alice (operator) and bob (target).
	alice := pairDevice(t, env, "alice", "linux")
	bob := pairDevice(t, env, "bob", "android")

	// Bob can list his /
	resp := bob.do(t, "GET", "/api/v1/files?path=/", nil, nil)
	expectStatus(t, resp, http.StatusOK, "bob pre-revoke")
	_ = resp.Body.Close()

	// Alice revokes bob.
	resp = alice.do(t, "DELETE", "/api/v1/devices/"+b64url.EncodeToString(bob.DeviceID), nil, nil)
	if resp.StatusCode != http.StatusNoContent {
		t.Errorf("revoke status=%d, want 204", resp.StatusCode)
	}
	_ = resp.Body.Close()

	// Bob now gets 401 on the same call.
	resp = bob.do(t, "GET", "/api/v1/files?path=/", nil, nil)
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Errorf("bob post-revoke status=%d, want 401", resp.StatusCode)
	}
}

// TestPathValidation hits the §1 path rules — any traversal, empty
// path, or relative path is 400 before reaching the storage layer.
func TestPathValidation(t *testing.T) {
	env := newTestEnv(t)
	defer env.cleanup()
	c := pairDevice(t, env, "tester", "linux")

	bad := []string{
		"",            // empty
		"foo",         // no leading slash
		"/foo/../bar", // traversal
		"/foo/./bar",  // dot component (path.Clean removes → mismatch)
		"/foo//bar",   // double slash
		"/CON",        // windows reserved
		"/foo\x00bar", // NUL byte
		"/foo\nbar",   // control char
	}
	for _, p := range bad {
		t.Run("path="+p, func(t *testing.T) {
			// list endpoint exercises validatedPath() cheaply.
			req, _ := http.NewRequest("GET", c.URL+"/api/v1/files?path="+urlEscape(p), nil)
			req.Header.Set("Authorization", "Bearer "+b64url.EncodeToString(c.Token))
			resp, err := http.DefaultClient.Do(req)
			if err != nil {
				t.Fatalf("do: %v", err)
			}
			defer resp.Body.Close()
			if resp.StatusCode != http.StatusBadRequest {
				t.Errorf("path=%q: status=%d, want 400", p, resp.StatusCode)
			}
		})
	}
}
