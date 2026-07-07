// Package tlsgen generates self-signed TLS certificates for first-run
// scenarios where the operator hasn't provided external certs.
package tlsgen

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"fmt"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"time"
)

// GenerateIfMissing creates a self-signed ECDSA P-256 certificate at
// certPath/keyPath if neither file exists yet. It embeds the host's
// LAN IPv4, IPv6, and loopback addresses as SANs so that clients on
// the same network can connect without certificate name mismatches.
//
// If both files already exist, it returns nil immediately.
func GenerateIfMissing(certPath, keyPath string, extraIPs []net.IP) error {
	certExists := fileExists(certPath)
	keyExists := fileExists(keyPath)
	if certExists && keyExists {
		return nil
	}
	if certExists != keyExists {
		return fmt.Errorf("tlsgen: partial TLS state — cert exists=%v key exists=%v; remove both to regenerate", certExists, keyExists)
	}

	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return fmt.Errorf("tlsgen: generate key: %w", err)
	}

	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return fmt.Errorf("tlsgen: serial: %w", err)
	}

	now := time.Now()
	tmpl := &x509.Certificate{
		SerialNumber: serial,
		Subject:      pkix.Name{CommonName: "Synctuary"},
		NotBefore:    now.Add(-1 * time.Hour),
		NotAfter:     now.Add(10 * 365 * 24 * time.Hour),

		KeyUsage:              x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,

		DNSNames:    []string{"localhost", "synctuary.local"},
		IPAddresses: collectSANIPs(extraIPs),
	}

	derCert, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		return fmt.Errorf("tlsgen: create cert: %w", err)
	}

	if err := os.MkdirAll(filepath.Dir(certPath), 0o700); err != nil {
		return fmt.Errorf("tlsgen: mkdir cert: %w", err)
	}
	if err := os.MkdirAll(filepath.Dir(keyPath), 0o700); err != nil {
		return fmt.Errorf("tlsgen: mkdir key: %w", err)
	}

	if err := writePEM(certPath, "CERTIFICATE", derCert); err != nil {
		return fmt.Errorf("tlsgen: write cert: %w", err)
	}

	keyDER, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		return fmt.Errorf("tlsgen: marshal key: %w", err)
	}
	if err := writePEM(keyPath, "EC PRIVATE KEY", keyDER); err != nil {
		os.Remove(certPath)
		return fmt.Errorf("tlsgen: write key: %w", err)
	}

	return nil
}

func collectSANIPs(extra []net.IP) []net.IP {
	seen := make(map[string]bool)
	var result []net.IP
	add := func(ip net.IP) {
		s := ip.String()
		if !seen[s] {
			seen[s] = true
			result = append(result, ip)
		}
	}

	add(net.IPv4(127, 0, 0, 1))
	add(net.IPv6loopback)

	for _, ip := range extra {
		add(ip)
	}

	ifaces, err := net.Interfaces()
	if err != nil {
		return result
	}
	for _, iface := range ifaces {
		if (iface.Flags&net.FlagUp) == 0 || (iface.Flags&net.FlagLoopback) != 0 {
			continue
		}
		addrs, _ := iface.Addrs()
		for _, a := range addrs {
			if inet, ok := a.(*net.IPNet); ok {
				add(inet.IP)
			}
		}
	}
	return result
}

func writePEM(path, typ string, data []byte) error {
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		return err
	}
	defer f.Close()
	return pem.Encode(f, &pem.Block{Type: typ, Bytes: data})
}

func fileExists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}
