package tlsgen

import (
	"crypto/tls"
	"crypto/x509"
	"net"
	"os"
	"path/filepath"
	"testing"
)

func TestGenerateIfMissing_CreatesValidCert(t *testing.T) {
	dir := t.TempDir()
	certPath := filepath.Join(dir, "server.crt")
	keyPath := filepath.Join(dir, "server.key")

	if err := GenerateIfMissing(certPath, keyPath, nil); err != nil {
		t.Fatalf("GenerateIfMissing: %v", err)
	}

	if _, err := os.Stat(certPath); err != nil {
		t.Fatalf("cert file missing: %v", err)
	}
	if _, err := os.Stat(keyPath); err != nil {
		t.Fatalf("key file missing: %v", err)
	}

	kp, err := tls.LoadX509KeyPair(certPath, keyPath)
	if err != nil {
		t.Fatalf("LoadX509KeyPair: %v", err)
	}
	cert, err := x509.ParseCertificate(kp.Certificate[0])
	if err != nil {
		t.Fatalf("ParseCertificate: %v", err)
	}

	if cert.Subject.CommonName != "Synctuary" {
		t.Errorf("CN = %q, want Synctuary", cert.Subject.CommonName)
	}
	if len(cert.IPAddresses) < 2 {
		t.Errorf("expected at least 2 SAN IPs (127.0.0.1 + ::1), got %d", len(cert.IPAddresses))
	}

	hasLoopback := false
	for _, ip := range cert.IPAddresses {
		if ip.Equal(net.IPv4(127, 0, 0, 1)) {
			hasLoopback = true
		}
	}
	if !hasLoopback {
		t.Error("SAN IPs missing 127.0.0.1")
	}

	hasDNS := false
	for _, name := range cert.DNSNames {
		if name == "localhost" {
			hasDNS = true
		}
	}
	if !hasDNS {
		t.Error("SAN DNS missing localhost")
	}
}

func TestGenerateIfMissing_SkipsExisting(t *testing.T) {
	dir := t.TempDir()
	certPath := filepath.Join(dir, "server.crt")
	keyPath := filepath.Join(dir, "server.key")

	if err := GenerateIfMissing(certPath, keyPath, nil); err != nil {
		t.Fatalf("first call: %v", err)
	}

	certInfo, _ := os.Stat(certPath)
	origMod := certInfo.ModTime()

	if err := GenerateIfMissing(certPath, keyPath, nil); err != nil {
		t.Fatalf("second call: %v", err)
	}

	certInfo2, _ := os.Stat(certPath)
	if !certInfo2.ModTime().Equal(origMod) {
		t.Error("cert was regenerated despite already existing")
	}
}

func TestGenerateIfMissing_ExtraIPs(t *testing.T) {
	dir := t.TempDir()
	certPath := filepath.Join(dir, "server.crt")
	keyPath := filepath.Join(dir, "server.key")

	extra := []net.IP{net.ParseIP("10.99.0.1")}
	if err := GenerateIfMissing(certPath, keyPath, extra); err != nil {
		t.Fatalf("GenerateIfMissing: %v", err)
	}

	kp, _ := tls.LoadX509KeyPair(certPath, keyPath)
	cert, _ := x509.ParseCertificate(kp.Certificate[0])

	found := false
	for _, ip := range cert.IPAddresses {
		if ip.Equal(net.ParseIP("10.99.0.1")) {
			found = true
		}
	}
	if !found {
		t.Error("extra IP 10.99.0.1 not found in SAN")
	}
}

func TestGenerateIfMissing_PartialState(t *testing.T) {
	dir := t.TempDir()
	certPath := filepath.Join(dir, "server.crt")
	keyPath := filepath.Join(dir, "server.key")

	if err := os.WriteFile(certPath, []byte("dummy"), 0600); err != nil {
		t.Fatalf("seed cert file: %v", err)
	}

	err := GenerateIfMissing(certPath, keyPath, nil)
	if err == nil {
		t.Fatal("expected error for partial TLS state")
	}
}
