package integration

import (
	"crypto/ed25519"
	"encoding/json"
	"net/url"
	"testing"

	icrypto "github.com/synctuary/synctuary-server/internal/adapter/infrastructure/crypto"
)

// mustEntropy is GenerateRandomBytes with t.Fatal on error — used for
// fixed-size identifiers in tests where allocation failure is fatal.
func mustEntropy(t *testing.T, n int) ([]byte, error) {
	t.Helper()
	b, err := icrypto.GenerateRandomBytes(n)
	if err != nil {
		t.Fatalf("entropy(%d): %v", n, err)
	}
	return b, nil
}

func mustDeriveDevice(t *testing.T, masterKey, deviceID []byte) (ed25519.PublicKey, ed25519.PrivateKey, error) {
	t.Helper()
	pub, priv, err := icrypto.DeriveDeviceKeypair(masterKey, deviceID)
	if err != nil {
		t.Fatalf("derive device keypair: %v", err)
	}
	return pub, priv, nil
}

func mustBuildPayload(t *testing.T, devID, pub, fp, nonce []byte) ([]byte, error) {
	t.Helper()
	p, err := icrypto.BuildPairPayload(devID, pub, fp, nonce)
	if err != nil {
		t.Fatalf("build payload: %v", err)
	}
	return p, nil
}

func mustSign(priv ed25519.PrivateKey, payload []byte) []byte {
	return ed25519.Sign(priv, payload)
}

func jsonMarshal(v any) ([]byte, error) { return json.Marshal(v) }

// urlEscape percent-encodes a query parameter value. Avoids tests
// silently using net/url import in every scenario file.
func urlEscape(s string) string { return url.QueryEscape(s) }
