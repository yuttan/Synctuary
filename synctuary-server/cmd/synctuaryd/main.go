// Command synctuaryd is the Synctuary server daemon.
//
// First-launch flow (when Storage.SecretPath does not exist): a
// BIP-39 24-word mnemonic is generated, printed ONCE to stdout, and
// the derived 32-byte master_key is persisted atomically at
// Storage.SecretPath (mode 0600). On subsequent launches the key is
// loaded from disk and the mnemonic is never re-derived.
//
// Subsystems wired here:
//
//	config  → koanf (yaml + env)
//	secret  → on-disk file store (0600), holds master_key
//	db      → modernc SQLite + goose migrations
//	rate    → in-memory sliding window (pairing endpoints)
//	fs      → content plane with hardlink dedup
//	usecase → pairing / file / device services
//	http    → chi router + bearer auth + §4..§7 endpoint set
//	gc      → periodic tickers for nonce + upload CollectExpired
//
// Build: `go build ./cmd/synctuaryd`
// Run:   `SYNCTUARY_STORAGE_ROOT_PATH=/srv/synctuary ./synctuaryd -config=/etc/synctuary.yaml`
package main

import (
	"context"
	"crypto/sha256"
	"crypto/tls"
	"encoding/hex"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"golang.org/x/crypto/hkdf"

	icrypto "github.com/synctuary/synctuary-server/internal/adapter/infrastructure/crypto"
	"github.com/synctuary/synctuary-server/internal/adapter/infrastructure/db"
	"github.com/synctuary/synctuary-server/internal/adapter/infrastructure/fs"
	"github.com/synctuary/synctuary-server/internal/adapter/infrastructure/rate"
	"github.com/synctuary/synctuary-server/internal/adapter/infrastructure/secret"
	httpapi "github.com/synctuary/synctuary-server/internal/adapter/interface/http"
	"github.com/synctuary/synctuary-server/internal/domain/device"
	domainfile "github.com/synctuary/synctuary-server/internal/domain/file"
	domainsecret "github.com/synctuary/synctuary-server/internal/domain/secret"
	"github.com/synctuary/synctuary-server/internal/migrations"
	"github.com/synctuary/synctuary-server/internal/usecase"
	"github.com/synctuary/synctuary-server/pkg/config"
)

// protocolVersion is the wire spec the server implements. It's a
// hard property of the codebase (ABI), so it stays a const — never
// override at link time.
const protocolVersion = "0.2.3"

// serverVersion and commit are advertised via /api/v1/info and are
// overridable at link time via:
//
//	go build -ldflags="-X main.serverVersion=0.4.1 -X main.commit=$(git rev-parse HEAD)"
//
// Release builds set these from CI; bare `go build` falls back to the
// compiled-in defaults below ("dev" + "unknown") which is also what
// developers see during day-to-day work.
var (
	serverVersion = "0.4.0-dev"
	commit        = "unknown"
)

func main() {
	configPath := flag.String("config", "", "path to YAML config file (optional; env-only is fine)")
	flag.Parse()

	cfg, err := config.Load(*configPath)
	if err != nil {
		fmt.Fprintf(os.Stderr, "synctuaryd: config load failed: %v\n", err)
		os.Exit(1)
	}

	logger := newLogger(cfg.Log)
	slog.SetDefault(logger)
	logger.Info("starting synctuaryd",
		"version", serverVersion,
		"commit", commit,
		"protocol", protocolVersion,
		"addr", cfg.Server.Addr,
		"storage_root", cfg.Storage.RootPath,
		"transport_profile", cfg.TransportProfile(),
	)

	// ── master_key: load or first-run generate ────────────────────
	secretStore := secret.NewFileStore(cfg.Storage.SecretPath)
	masterKey, err := loadOrInitMasterKey(secretStore, logger)
	if err != nil {
		logger.Error("master_key init failed", "err", err)
		os.Exit(1)
	}
	serverID, err := deriveServerID(masterKey)
	if err != nil {
		logger.Error("server_id derivation failed", "err", err)
		os.Exit(1)
	}

	// ── DB + migrations ───────────────────────────────────────────
	database, err := db.Open(cfg.Database.Path)
	if err != nil {
		logger.Error("database open failed", "err", err)
		os.Exit(1)
	}
	defer func() {
		if cerr := database.Close(); cerr != nil {
			logger.Warn("database close error", "err", cerr)
		}
	}()
	if err := db.Migrate(database, migrations.FS, migrations.Dir); err != nil {
		logger.Error("migrations failed", "err", err)
		os.Exit(1)
	}
	logger.Info("migrations applied")

	// ── TLS fingerprint (nil when dev-plaintext) ──────────────────
	var tlsFingerprint []byte
	if cfg.Server.TLSCertPath != "" {
		fp, ferr := loadTLSFingerprint(cfg.Server.TLSCertPath, cfg.Server.TLSKeyPath)
		if ferr != nil {
			logger.Error("tls fingerprint load failed", "err", ferr)
			os.Exit(1)
		}
		tlsFingerprint = fp
		logger.Info("tls fingerprint loaded", "sha256", hex.EncodeToString(fp))
	} else {
		// §4 pair payload still requires a 32-byte fingerprint.
		// For dev-plaintext we bind to a stable zero fingerprint
		// — clients MUST NOT pair against dev-plaintext in
		// production; this keeps local e2e running.
		tlsFingerprint = make([]byte, 32)
	}

	// ── repositories + storage ────────────────────────────────────
	deviceRepo := db.NewDeviceRepository(database)
	fileRepo := db.NewFileRepository(database)
	favoriteRepo := db.NewFavoriteRepository(database)
	nonceStore := db.NewNonceStore(database)

	storage, err := fs.NewFileStorage(cfg.Storage.RootPath, cfg.Storage.StagingPath, &shaResolver{repo: fileRepo, root: cfg.Storage.RootPath})
	if err != nil {
		logger.Error("file storage init failed", "err", err)
		os.Exit(1)
	}

	uploads, err := db.NewUploadSessionStore(
		database,
		cfg.Storage.RootPath, cfg.Storage.StagingPath,
		cfg.Upload.ChunkSize, cfg.Upload.ChunkSizeMax,
		int64(cfg.Upload.SessionTTL.Seconds()),
	)
	if err != nil {
		logger.Error("upload store init failed", "err", err)
		os.Exit(1)
	}

	limiter := rate.NewMemoryLimiter(cfg.Pairing.RateLimitMax, int64(cfg.Pairing.RateLimitWindow.Seconds()))

	// ── usecases ──────────────────────────────────────────────────
	pairingSvc, err := usecasePairing(nonceStore, deviceRepo, limiter, masterKey, tlsFingerprint, cfg.Pairing.NonceTTL)
	if err != nil {
		logger.Error("pairing service init failed", "err", err)
		os.Exit(1)
	}
	fileSvc, err := usecaseFile(fileRepo, storage, uploads, cfg.Upload.DedupFallback, cfg.Upload.DedupSyncCopyTimeout)
	if err != nil {
		logger.Error("file service init failed", "err", err)
		os.Exit(1)
	}
	deviceSvc := usecaseDevice(deviceRepo)
	favoriteSvc, err := usecase.NewFavoriteService(favoriteRepo, nil)
	if err != nil {
		logger.Error("favorite service init failed", "err", err)
		os.Exit(1)
	}

	// ── HTTP handler ──────────────────────────────────────────────
	handler, err := httpapi.NewHandler(httpapi.HandlerConfig{
		Pairing:          pairingSvc,
		Files:            fileSvc,
		Devices:          deviceSvc,
		Favorites:        favoriteSvc,
		DeviceRepo:       deviceRepo,
		Logger:           logger,
		ServerID:         serverID,
		ServerName:       cfg.Server.Name,
		EncryptionMode:   "standard",
		TransportProfile: cfg.TransportProfile(),
		TLSFingerprint:   tlsFingerprint,
		ServerVersion:    serverVersion,
		ProtocolVersion:  protocolVersion,
		Commit:           commit,
		Capabilities: map[string]bool{
			"range_download":   true,
			"resumable_upload": true,
			"photo_backup":     false,
			"private_mode":     false,
			"parallel_upload":  false,
			"if_none_match":    false,
		},
	})
	if err != nil {
		logger.Error("http handler init failed", "err", err)
		os.Exit(1)
	}

	router := newRouter(handler, logger)

	// ── background GC ─────────────────────────────────────────────
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go gcLoop(ctx, logger, nonceStore, uploads)

	// ── server ────────────────────────────────────────────────────
	server := &http.Server{
		Addr:         cfg.Server.Addr,
		Handler:      router,
		ReadTimeout:  cfg.Server.ReadTimeout,
		WriteTimeout: cfg.Server.WriteTimeout,
	}

	serverErr := make(chan error, 1)
	go func() {
		if cfg.Server.TLSCertPath != "" && cfg.Server.TLSKeyPath != "" {
			serverErr <- server.ListenAndServeTLS(cfg.Server.TLSCertPath, cfg.Server.TLSKeyPath)
		} else {
			logger.Warn("TLS disabled — transport_profile = dev-plaintext (DO NOT USE IN PRODUCTION)")
			serverErr <- server.ListenAndServe()
		}
	}()

	select {
	case <-ctx.Done():
		logger.Info("shutdown signal received, draining")
	case err := <-serverErr:
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			logger.Error("server exited with error", "err", err)
			os.Exit(1)
		}
	}

	shutdownCtx, cancel := context.WithTimeout(context.Background(), cfg.Server.ShutdownTimeout)
	defer cancel()
	if err := server.Shutdown(shutdownCtx); err != nil {
		logger.Error("graceful shutdown failed", "err", err)
		os.Exit(1)
	}
	logger.Info("shutdown complete")
}

// newLogger constructs the *slog.Logger implied by LogConfig.
func newLogger(cfg config.LogConfig) *slog.Logger {
	var level slog.Level
	switch cfg.Level {
	case "debug":
		level = slog.LevelDebug
	case "warn":
		level = slog.LevelWarn
	case "error":
		level = slog.LevelError
	default:
		level = slog.LevelInfo
	}
	opts := &slog.HandlerOptions{Level: level}
	var handler slog.Handler
	if cfg.Format == "text" {
		handler = slog.NewTextHandler(os.Stdout, opts)
	} else {
		handler = slog.NewJSONHandler(os.Stdout, opts)
	}
	return slog.New(handler)
}

// newRouter builds the chi router and registers the PROTOCOL endpoint
// set via Handler.Register.
func newRouter(h *httpapi.Handler, logger *slog.Logger) http.Handler {
	r := chi.NewRouter()
	r.Use(middleware.RequestID)
	r.Use(middleware.RealIP)
	r.Use(middleware.Recoverer)
	r.Use(middleware.Timeout(60 * time.Second))
	r.Use(httpapi.RequestLogger(logger))

	h.Register(r)
	return r
}

// loadOrInitMasterKey loads master_key from secretStore; on first run
// (domainsecret.ErrNotFound) it generates a fresh BIP-39 24-word
// mnemonic, prints it to stdout, derives master_key, and persists it.
// Any other error (e.g. permission denied) surfaces unchanged.
func loadOrInitMasterKey(store *secret.FileStore, log *slog.Logger) ([]byte, error) {
	ctx := context.Background()
	key, err := store.LoadMasterKey(ctx)
	if err == nil {
		log.Info("master_key loaded")
		return key, nil
	}
	if !errors.Is(err, domainsecret.ErrNotFound) {
		return nil, fmt.Errorf("load master_key: %w", err)
	}
	// First-run path.
	entropy, err := icrypto.GenerateRandomBytes(32)
	if err != nil {
		return nil, fmt.Errorf("entropy: %w", err)
	}
	mnemonic, err := device.GenerateMnemonic(entropy)
	if err != nil {
		return nil, fmt.Errorf("mnemonic: %w", err)
	}
	seed, err := device.MnemonicToSeed(mnemonic)
	if err != nil {
		return nil, fmt.Errorf("seed: %w", err)
	}
	key, err = icrypto.DeriveMasterKey(seed)
	if err != nil {
		return nil, fmt.Errorf("derive master_key: %w", err)
	}
	if err := store.SaveMasterKey(ctx, key); err != nil {
		return nil, fmt.Errorf("persist master_key: %w", err)
	}

	// Mnemonic is displayed once on stderr. Operators MUST copy it
	// now; it cannot be recovered from the server. stderr ensures
	// it is visible even when stdout is piped in daemon/container mode.
	fmt.Fprintln(os.Stderr, "════════════════════════════════════════════════════")
	fmt.Fprintln(os.Stderr, " FIRST LAUNCH — SYNCTUARY ROOT SEED")
	fmt.Fprintln(os.Stderr, "════════════════════════════════════════════════════")
	fmt.Fprintln(os.Stderr, " Write down the 24 words below. They cannot be")
	fmt.Fprintln(os.Stderr, " recovered from the server after this boot.")
	fmt.Fprintln(os.Stderr, "────────────────────────────────────────────────────")
	fmt.Fprintln(os.Stderr, " "+mnemonic)
	fmt.Fprintln(os.Stderr, "════════════════════════════════════════════════════")

	return key, nil
}

// deriveServerID produces a stable 16-byte ID from the master key. No
// new persisted state needed: the same master_key always yields the
// same server_id.
func deriveServerID(masterKey []byte) ([]byte, error) {
	out := make([]byte, 16)
	kdf := hkdf.New(sha256.New, masterKey, []byte("synctuary-v1"), []byte("server_id"))
	if _, err := kdf.Read(out); err != nil {
		return nil, fmt.Errorf("hkdf derive server_id: %w", err)
	}
	return out, nil
}

// loadTLSFingerprint returns SHA-256(DER(leaf)) for the configured
// cert/key pair. Used both for /info.tls_fingerprint and as the
// domain-separation input to Ed25519 pair signatures (§4).
func loadTLSFingerprint(certPath, keyPath string) ([]byte, error) {
	kp, err := tls.LoadX509KeyPair(certPath, keyPath)
	if err != nil {
		return nil, err
	}
	if len(kp.Certificate) == 0 || len(kp.Certificate[0]) == 0 {
		return nil, fmt.Errorf("empty certificate chain")
	}
	sum := sha256.Sum256(kp.Certificate[0])
	return sum[:], nil
}

// gcLoop runs periodic CollectExpired for nonces and upload sessions.
func gcLoop(ctx context.Context, log *slog.Logger, nonces gcRunner, uploads gcRunner) {
	t := time.NewTicker(1 * time.Minute)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			now := time.Now().Unix()
			if n, err := nonces.CollectExpired(ctx, now); err != nil {
				log.Warn("nonce gc", "err", err)
			} else if n > 0 {
				log.Debug("nonce gc", "removed", n)
			}
			if n, err := uploads.CollectExpired(ctx, now); err != nil {
				log.Warn("upload gc", "err", err)
			} else if n > 0 {
				log.Debug("upload gc", "removed", n)
			}
		}
	}
}

type gcRunner interface {
	CollectExpired(ctx context.Context, now int64) (int, error)
}

// shaResolver adapts a domainfile.Repository into the
// fs.SourceResolver interface. It maps sha256 → absolute path by
// resolving the repository's user-facing Path under the storage root.
type shaResolver struct {
	repo domainfile.Repository
	root string
}

func (s *shaResolver) ResolvePath(ctx context.Context, sha []byte) (string, error) {
	meta, err := s.repo.FindBySHA(ctx, sha)
	if err != nil {
		return "", err
	}
	if meta == nil {
		return "", domainfile.ErrFileNotFound
	}
	// User path → absolute under root. filepath.Join handles
	// OS-specific separators.
	return joinUnderRoot(s.root, meta.Path), nil
}

func joinUnderRoot(root, userPath string) string {
	// Strip leading slash so filepath.Join treats it relative.
	for len(userPath) > 0 && userPath[0] == '/' {
		userPath = userPath[1:]
	}
	return root + string(os.PathSeparator) + userPath
}

// ── thin usecase constructors ───────────────────────────────────────
//
// These are tiny wrappers that keep the main() body readable. They do
// not add logic — they're purely naming layers in the dependency tree.

func usecasePairing(
	nonces *db.NonceStore,
	devices device.Repository,
	limiter *rate.MemoryLimiter,
	masterKey, fingerprint []byte,
	nonceTTL time.Duration,
) (*usecase.PairingService, error) {
	return usecase.NewPairingService(nonces, devices, limiter, masterKey, fingerprint, nonceTTL)
}

func usecaseFile(
	repo domainfile.Repository,
	storage domainfile.FileStorage,
	uploads domainfile.UploadSession,
	dedupFallback string,
	dedupTimeout time.Duration,
) (*usecase.FileService, error) {
	return usecase.NewFileService(repo, storage, uploads, dedupFallback, dedupTimeout)
}

func usecaseDevice(repo device.Repository) *usecase.DeviceService {
	return usecase.NewDeviceService(repo)
}
