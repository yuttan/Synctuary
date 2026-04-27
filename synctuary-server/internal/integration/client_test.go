package integration

import (
	"bytes"
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"testing"

	icrypto "github.com/synctuary/synctuary-server/internal/adapter/infrastructure/crypto"
)

// hexLower is a tiny shim so callers don't have to repeat the lowercase
// rule explicitly — the protocol pins SHA-256 representations to lowercase
// hex (PROTOCOL §6.3.1).
func hexLower(b []byte) string { return hex.EncodeToString(b) }

// pairedClient is the test-side of a successfully paired device.
// Methods on it mint Bearer-authenticated requests against the env's URL.
type pairedClient struct {
	URL      string
	DeviceID []byte
	Token    []byte // 32 bytes, raw
	Priv     ed25519.PrivateKey
	Pub      ed25519.PublicKey
}

// pairDevice runs the full PROTOCOL §4.2 + §4.3 flow against env and
// returns a client ready to call authenticated endpoints.
func pairDevice(t *testing.T, env *testEnv, name, platform string) *pairedClient {
	t.Helper()

	devID, err := icrypto.GenerateRandomBytes(16)
	if err != nil {
		t.Fatalf("device_id entropy: %v", err)
	}
	pub, priv, err := icrypto.DeriveDeviceKeypair(env.masterKey, devID)
	if err != nil {
		t.Fatalf("device keypair: %v", err)
	}

	// §4.2: nonce
	resp, err := http.Post(env.URL+"/api/v1/pair/nonce", "application/json", nil)
	if err != nil {
		t.Fatalf("nonce request: %v", err)
	}
	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		_ = resp.Body.Close()
		t.Fatalf("nonce status %d: %s", resp.StatusCode, body)
	}
	var nb struct {
		Nonce     string `json:"nonce"`
		ExpiresAt int64  `json:"expires_at"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&nb); err != nil {
		_ = resp.Body.Close()
		t.Fatalf("nonce decode: %v", err)
	}
	_ = resp.Body.Close()

	nonce, err := b64url.DecodeString(nb.Nonce)
	if err != nil {
		t.Fatalf("nonce b64: %v", err)
	}

	// §4.1 payload + Ed25519 signature
	payload, err := icrypto.BuildPairPayload(devID, pub, env.fingerprint, nonce)
	if err != nil {
		t.Fatalf("build payload: %v", err)
	}
	sig := ed25519.Sign(priv, payload)

	// §4.3: register
	regBody := map[string]any{
		"nonce":              b64url.EncodeToString(nonce),
		"device_id":          b64url.EncodeToString(devID),
		"device_pub":         b64url.EncodeToString(pub),
		"device_name":        name,
		"platform":           platform,
		"challenge_response": b64url.EncodeToString(sig),
	}
	rawReg, _ := json.Marshal(regBody)
	resp, err = http.Post(env.URL+"/api/v1/pair/register", "application/json", bytes.NewReader(rawReg))
	if err != nil {
		t.Fatalf("register request: %v", err)
	}
	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		_ = resp.Body.Close()
		t.Fatalf("register status %d: %s", resp.StatusCode, body)
	}
	var rb struct {
		DeviceToken string `json:"device_token"`
		ServerID    string `json:"server_id"`
		TTLSeconds  int64  `json:"device_token_ttl"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&rb); err != nil {
		_ = resp.Body.Close()
		t.Fatalf("register decode: %v", err)
	}
	_ = resp.Body.Close()

	token, err := b64url.DecodeString(rb.DeviceToken)
	if err != nil {
		t.Fatalf("token b64: %v", err)
	}
	if len(token) != 32 {
		t.Fatalf("token len=%d, want 32", len(token))
	}

	return &pairedClient{
		URL:      env.URL,
		DeviceID: devID,
		Token:    token,
		Priv:     priv,
		Pub:      pub,
	}
}

// do issues an authenticated request. body may be nil. headers is
// merged in after the bearer header is set.
func (c *pairedClient) do(t *testing.T, method, path string, body []byte, headers map[string]string) *http.Response {
	t.Helper()
	var rdr io.Reader
	if body != nil {
		rdr = bytes.NewReader(body)
	}
	req, err := http.NewRequest(method, c.URL+path, rdr)
	if err != nil {
		t.Fatalf("new request: %v", err)
	}
	req.Header.Set("Authorization", "Bearer "+b64url.EncodeToString(c.Token))
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("do %s %s: %v", method, path, err)
	}
	return resp
}

// doJSON is do() with a JSON body and Content-Type set.
func (c *pairedClient) doJSON(t *testing.T, method, path string, body any) *http.Response {
	t.Helper()
	raw, err := json.Marshal(body)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	return c.do(t, method, path, raw, map[string]string{"Content-Type": "application/json"})
}

// expectStatus aborts the test if resp.StatusCode != want, dumping the
// body for context.
func expectStatus(t *testing.T, resp *http.Response, want int, label string) {
	t.Helper()
	if resp.StatusCode != want {
		body, _ := io.ReadAll(resp.Body)
		_ = resp.Body.Close()
		t.Fatalf("%s: status %d, want %d (body: %s)", label, resp.StatusCode, want, body)
	}
}

// decodeJSON pulls resp.Body into dst and closes the body. Calls
// t.Fatal on decode failure.
func decodeJSON(t *testing.T, resp *http.Response, dst any) {
	t.Helper()
	defer resp.Body.Close()
	if err := json.NewDecoder(resp.Body).Decode(dst); err != nil {
		t.Fatalf("decode: %v", err)
	}
}

// uploadOne is a convenience for the common single-chunk upload path.
// Returns the upload_id (empty on dedup) and any non-fatal error.
func uploadOne(t *testing.T, c *pairedClient, path string, content []byte, overwrite bool) (uploadID string, deduplicated bool) {
	t.Helper()
	sum := sha256.Sum256(content)

	initBody := map[string]any{
		"path":      path,
		"size":      len(content),
		"sha256":    hexLower(sum[:]),
		"overwrite": overwrite,
	}
	resp := c.doJSON(t, "POST", "/api/v1/files/upload/init", initBody)
	switch resp.StatusCode {
	case http.StatusOK:
		var ok struct {
			UploadID *string `json:"upload_id"`
			Status   string  `json:"status"`
		}
		decodeJSON(t, resp, &ok)
		if ok.Status != "deduplicated" {
			t.Fatalf("init 200 but status=%q (expected deduplicated)", ok.Status)
		}
		return "", true
	case http.StatusCreated:
		var cr struct {
			UploadID string `json:"upload_id"`
		}
		decodeJSON(t, resp, &cr)
		uploadID = cr.UploadID
	default:
		body, _ := io.ReadAll(resp.Body)
		_ = resp.Body.Close()
		t.Fatalf("init: status %d (%s)", resp.StatusCode, body)
	}

	rangeHdr := fmt.Sprintf("bytes 0-%d/%d", len(content)-1, len(content))
	resp = c.do(t, "PUT", "/api/v1/files/upload/"+uploadID, content, map[string]string{
		"Content-Type":  "application/octet-stream",
		"Content-Range": rangeHdr,
	})
	expectStatus(t, resp, http.StatusOK, "chunk PUT")
	_ = resp.Body.Close()
	return uploadID, false
}
