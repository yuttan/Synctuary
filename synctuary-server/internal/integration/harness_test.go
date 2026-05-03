// Package integration boots a fully wired daemon in-process against
// httptest.Server and exercises the PROTOCOL v0.2.2 endpoint set
// end-to-end. The harness mirrors cmd/synctuaryd/main.go's DI graph
// closely enough to catch wiring regressions; only TLS, the GC ticker,
// and signal-driven shutdown are omitted.
package integration

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"io"
	"log/slog"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/go-chi/chi/v5"
	"golang.org/x/crypto/hkdf"

	icrypto "github.com/synctuary/synctuary-server/internal/adapter/infrastructure/crypto"
	"github.com/synctuary/synctuary-server/internal/adapter/infrastructure/db"
	"github.com/synctuary/synctuary-server/internal/adapter/infrastructure/fs"
	"github.com/synctuary/synctuary-server/internal/adapter/infrastructure/rate"
	"github.com/synctuary/synctuary-server/internal/adapter/infrastructure/secret"
	httpapi "github.com/synctuary/synctuary-server/internal/adapter/interface/http"
	"github.com/synctuary/synctuary-server/internal/domain/device"
	domainfile "github.com/synctuary/synctuary-server/internal/domain/file"
	"github.com/synctuary/synctuary-server/internal/migrations"
	"github.com/synctuary/synctuary-server/internal/usecase"
)

// b64url is the no-padding codec used everywhere on the wire.
var b64url = base64.RawURLEncoding

// testEnv carries the shared state every scenario needs:
//   - URL for the in-process httptest server
//   - masterKey so the test client can derive device keypairs the
//     same way the server does (PROTOCOL §3.3)
//   - fingerprint = 32 zero bytes (matches the server's dev-plaintext
//     branch so signed payloads validate)
//
// Each test gets its own env via newTestEnv → t.TempDir, so they are
// safely parallelizable.
type testEnv struct {
	URL         string
	masterKey   []byte
	fingerprint []byte
	storeRoot   string
	cleanup     func()
}

// testEnvOpts configures the test harness. Zero value gives defaults.
type testEnvOpts struct {
	dedupFallback string // "fallthrough" (default) or "sync_copy"
}

// newTestEnv stands up a fresh in-process daemon. It mirrors
// cmd/synctuaryd/main.go's wiring; if you change the production
// graph, update this too.
func newTestEnv(t *testing.T, opts ...testEnvOpts) *testEnv {
	t.Helper()
	var o testEnvOpts
	if len(opts) > 0 {
		o = opts[0]
	}
	if o.dedupFallback == "" {
		o.dedupFallback = "fallthrough"
	}
	tmpDir := t.TempDir()

	storeRoot := filepath.Join(tmpDir, "store")
	stagingRoot := filepath.Join(tmpDir, "staging")
	secretPath := filepath.Join(tmpDir, "secret", "master_key")
	dbPath := filepath.Join(tmpDir, "synctuary.db")

	for _, d := range []string{storeRoot, stagingRoot, filepath.Dir(secretPath)} {
		if err := os.MkdirAll(d, 0o755); err != nil {
			t.Fatalf("mkdir %s: %v", d, err)
		}
	}

	// master_key — generated fresh per test, persisted so the
	// secret store interface is exercised.
	entropy, err := icrypto.GenerateRandomBytes(32)
	if err != nil {
		t.Fatalf("entropy: %v", err)
	}
	mnemonic, err := device.GenerateMnemonic(entropy)
	if err != nil {
		t.Fatalf("mnemonic: %v", err)
	}
	seed, err := device.MnemonicToSeed(mnemonic)
	if err != nil {
		t.Fatalf("seed: %v", err)
	}
	masterKey, err := icrypto.DeriveMasterKey(seed)
	if err != nil {
		t.Fatalf("derive master_key: %v", err)
	}
	if err := secret.NewFileStore(secretPath).SaveMasterKey(context.Background(), masterKey); err != nil {
		t.Fatalf("save master_key: %v", err)
	}

	// server_id — same HKDF derivation as cmd/synctuaryd/main.go.
	serverID := make([]byte, 16)
	kdf := hkdf.New(sha256.New, masterKey, []byte("synctuary-v1"), []byte("server_id"))
	if _, err := kdf.Read(serverID); err != nil {
		t.Fatalf("server_id: %v", err)
	}

	// db + migrations
	database, err := db.Open(dbPath)
	if err != nil {
		t.Fatalf("db open: %v", err)
	}
	if err := db.Migrate(database, migrations.FS, migrations.Dir); err != nil {
		t.Fatalf("migrate: %v", err)
	}

	deviceRepo := db.NewDeviceRepository(database)
	fileRepo := db.NewFileRepository(database)
	favoriteRepo := db.NewFavoriteRepository(database)
	nonceStore := db.NewNonceStore(database)

	storage, err := fs.NewFileStorage(storeRoot, stagingRoot, &shaResolver{repo: fileRepo, root: storeRoot})
	if err != nil {
		t.Fatalf("fs storage: %v", err)
	}

	uploads, err := db.NewUploadSessionStore(database, storeRoot, stagingRoot, 1<<20, 16<<20, 3600)
	if err != nil {
		t.Fatalf("upload store: %v", err)
	}

	limiter := rate.NewMemoryLimiter(1000, 60) // generous; rate limit is exercised by its own test

	fingerprint := make([]byte, 32) // dev-plaintext zero-fill

	pairingSvc, err := usecase.NewPairingService(nonceStore, deviceRepo, limiter, masterKey, fingerprint, 5*time.Minute)
	if err != nil {
		t.Fatalf("pairing svc: %v", err)
	}
	fileSvc, err := usecase.NewFileService(fileRepo, storage, uploads, o.dedupFallback, 30*time.Second)
	if err != nil {
		t.Fatalf("file svc: %v", err)
	}
	deviceSvc := usecase.NewDeviceService(deviceRepo)
	favoriteSvc, err := usecase.NewFavoriteService(favoriteRepo, nil)
	if err != nil {
		t.Fatalf("favorite svc: %v", err)
	}

	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	handler, err := httpapi.NewHandler(httpapi.HandlerConfig{
		Pairing:          pairingSvc,
		Files:            fileSvc,
		Devices:          deviceSvc,
		Favorites:        favoriteSvc,
		DeviceRepo:       deviceRepo,
		Logger:           logger,
		ServerID:         serverID,
		ServerName:       "integration-test",
		EncryptionMode:   "standard",
		TransportProfile: "dev-plaintext",
		TLSFingerprint:   fingerprint,
		ServerVersion:    "test",
		ProtocolVersion:  "0.2.3",
		Commit:           "test-commit-sha",
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
		t.Fatalf("handler: %v", err)
	}

	router := chi.NewRouter()
	handler.Register(router)
	httpSrv := httptest.NewServer(router)

	return &testEnv{
		URL:         httpSrv.URL,
		masterKey:   masterKey,
		fingerprint: fingerprint,
		storeRoot:   storeRoot,
		cleanup: func() {
			httpSrv.Close()
			_ = database.Close()
		},
	}
}

// shaResolver mirrors cmd/synctuaryd/main.go:shaResolver. Defined
// here so the integration package does not import the main command.
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
	return joinUnderRoot(s.root, meta.Path), nil
}

func joinUnderRoot(root, userPath string) string {
	for len(userPath) > 0 && userPath[0] == '/' {
		userPath = userPath[1:]
	}
	return root + string(os.PathSeparator) + userPath
}
