// Package config loads and validates the Synctuary server configuration.
//
// Precedence (highest → lowest): environment variables (SYNCTUARY_* ), YAML
// config file (path passed to Load), compiled-in defaults. Values are merged
// via koanf; anything missing at the end of the merge falls back to the
// defaults defined here.
package config

import (
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/knadh/koanf/parsers/yaml"
	"github.com/knadh/koanf/providers/env"
	"github.com/knadh/koanf/providers/file"
	"github.com/knadh/koanf/v2"
)

// Config is the top-level settings tree. The struct tags mirror the
// dot-separated keys used by koanf; they are case-insensitive for env
// vars (SYNCTUARY_SERVER_ADDR → server.addr).
type Config struct {
	Server   ServerConfig   `koanf:"server"`
	Storage  StorageConfig  `koanf:"storage"`
	Database DatabaseConfig `koanf:"database"`
	Upload   UploadConfig   `koanf:"upload"`
	Pairing  PairingConfig  `koanf:"pairing"`
	Log      LogConfig      `koanf:"log"`
}

type ServerConfig struct {
	Addr            string        `koanf:"addr"`             // ":8443"
	Name            string        `koanf:"name"`             // advertised in /info.server_name
	TLSCertPath     string        `koanf:"tls_cert_path"`    // PEM cert; empty → dev-plaintext
	TLSKeyPath      string        `koanf:"tls_key_path"`     // PEM key;  empty → dev-plaintext
	ReadTimeout     time.Duration `koanf:"read_timeout"`     // default 30s
	WriteTimeout    time.Duration `koanf:"write_timeout"`    // default 5m  (large chunk uploads)
	ShutdownTimeout time.Duration `koanf:"shutdown_timeout"` // graceful drain on SIGTERM
}

type StorageConfig struct {
	RootPath    string `koanf:"root_path"`    // where user content lives
	StagingPath string `koanf:"staging_path"` // in-progress upload staging (same FS as RootPath for atomic rename)
	SecretPath  string `koanf:"secret_path"`  // master_key file (0600)
}

type DatabaseConfig struct {
	Path string `koanf:"path"` // SQLite file path, e.g. /var/lib/synctuary/meta.db
}

// UploadConfig exposes the values advertised in PROTOCOL §6.3.1
// (chunk_size / chunk_size_max) and the server-side dedup policy from
// PROTOCOL v0.2.2 §6.3.1.
type UploadConfig struct {
	ChunkSize            int64         `koanf:"chunk_size"`              // default 8 MiB  (advertised as "chunk_size")
	ChunkSizeMax         int64         `koanf:"chunk_size_max"`          // default 32 MiB (advertised as "chunk_size_max")
	SessionTTL           time.Duration `koanf:"session_ttl"`             // default 24h    (uploads.expires_at)
	DedupFallback        string        `koanf:"dedup_fallback"`          // "fallthrough" (default) | "sync_copy"
	DedupSyncCopyTimeout time.Duration `koanf:"dedup_sync_copy_timeout"` // default 30s    (when DedupFallback == "sync_copy")
}

// PairingConfig carries the nonce TTL (§4.2) and the rate-limit window
// documented as RECOMMENDED "5 requests per minute per source IP".
type PairingConfig struct {
	NonceTTL        time.Duration `koanf:"nonce_ttl"`         // default 300s
	RateLimitMax    int           `koanf:"rate_limit_max"`    // default 5
	RateLimitWindow time.Duration `koanf:"rate_limit_window"` // default 1m
}

type LogConfig struct {
	Level  string `koanf:"level"`  // "debug" | "info" | "warn" | "error"
	Format string `koanf:"format"` // "json" | "text"
}

// Defaults returns a Config populated with the compiled-in baseline used
// when no file / env overrides are supplied. Safe to use as the initial
// value fed to koanf.
func Defaults() *Config {
	return &Config{
		Server: ServerConfig{
			Addr:            ":8443",
			Name:            "Synctuary",
			ReadTimeout:     30 * time.Second,
			WriteTimeout:    5 * time.Minute,
			ShutdownTimeout: 30 * time.Second,
		},
		Storage: StorageConfig{
			RootPath:    "./data/files",
			StagingPath: "./data/staging",
			SecretPath:  "./data/secret/master_key",
		},
		Database: DatabaseConfig{
			Path: "./data/meta.db",
		},
		Upload: UploadConfig{
			ChunkSize:            8 * 1024 * 1024,
			ChunkSizeMax:         32 * 1024 * 1024,
			SessionTTL:           24 * time.Hour,
			DedupFallback:        "fallthrough",
			DedupSyncCopyTimeout: 30 * time.Second,
		},
		Pairing: PairingConfig{
			NonceTTL:        300 * time.Second,
			RateLimitMax:    5,
			RateLimitWindow: 1 * time.Minute,
		},
		Log: LogConfig{
			Level:  "info",
			Format: "json",
		},
	}
}

// Load builds a Config starting from compiled-in defaults, then layers
// (in order) an optional YAML file and SYNCTUARY_* environment
// variables. A missing config path is not an error — env-only
// configuration is supported.
func Load(path string) (*Config, error) {
	k := koanf.New(".")
	cfg := Defaults()

	// Layer 1: YAML file, if supplied and present.
	if path != "" {
		if err := k.Load(file.Provider(path), yaml.Parser()); err != nil {
			return nil, fmt.Errorf("config: load %s: %w", path, err)
		}
	}

	// Layer 2: environment variables. SYNCTUARY_SERVER_ADDR → server.addr
	if err := k.Load(env.Provider("SYNCTUARY_", ".", func(s string) string {
		s = strings.TrimPrefix(s, "SYNCTUARY_")
		s = strings.ToLower(s)
		return strings.ReplaceAll(s, "_", ".")
	}), nil); err != nil {
		return nil, fmt.Errorf("config: load env: %w", err)
	}

	// Unmarshal on top of the defaults struct so any keys not supplied
	// by file or env retain their default values.
	if err := k.UnmarshalWithConf("", cfg, koanf.UnmarshalConf{
		Tag:           "koanf",
		FlatPaths:     false,
		DecoderConfig: nil,
	}); err != nil {
		return nil, fmt.Errorf("config: unmarshal: %w", err)
	}
	if err := cfg.Validate(); err != nil {
		return nil, err
	}
	return cfg, nil
}

// Validate performs cross-field sanity checks that koanf cannot express.
func (c *Config) Validate() error {
	if c.Upload.ChunkSize <= 0 {
		return errors.New("config: upload.chunk_size must be positive")
	}
	if c.Upload.ChunkSizeMax < c.Upload.ChunkSize {
		return errors.New("config: upload.chunk_size_max must be ≥ upload.chunk_size")
	}
	switch c.Upload.DedupFallback {
	case "fallthrough", "sync_copy":
	default:
		return fmt.Errorf("config: upload.dedup_fallback %q: expected \"fallthrough\" or \"sync_copy\"", c.Upload.DedupFallback)
	}
	if c.Server.TLSCertPath == "" && c.Server.TLSKeyPath != "" {
		return errors.New("config: server.tls_key_path set without server.tls_cert_path")
	}
	if c.Server.TLSCertPath != "" && c.Server.TLSKeyPath == "" {
		return errors.New("config: server.tls_cert_path set without server.tls_key_path")
	}
	if c.Storage.RootPath == "" || c.Storage.StagingPath == "" {
		return errors.New("config: storage.root_path and storage.staging_path are required")
	}
	if c.Storage.SecretPath == "" {
		return errors.New("config: storage.secret_path is required")
	}
	return nil
}

// TransportProfile returns the PROTOCOL §9 transport_profile string
// implied by the current Server config. dev-plaintext is used when no
// TLS cert is configured; distinguishing tls-ca-verified from
// tls-self-signed is left to the operator (advertised explicitly in a
// future config key) — for v0.2.2 we advertise tls-self-signed by
// default when TLS is enabled.
func (c *Config) TransportProfile() string {
	if c.Server.TLSCertPath == "" {
		return "dev-plaintext"
	}
	return "tls-self-signed"
}
