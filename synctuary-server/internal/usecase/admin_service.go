package usecase

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"time"

	"golang.org/x/crypto/bcrypt"
)

// AdminService handles admin authentication: initial password setup,
// login, and JWT session validation for the admin Web UI.
//
// The admin password hash is stored in the server_meta table (key
// "admin.password_hash"). A nil/missing row means no password has
// been set yet (first-run state).
type AdminService struct {
	db        *sql.DB
	jwtSecret []byte
	now       func() int64
}

var (
	ErrAdminNotSetUp       = errors.New("admin_not_set_up")
	ErrAdminAlreadySetUp   = errors.New("admin_already_set_up")
	ErrAdminBadPassword    = errors.New("admin_bad_password")
	ErrAdminPasswordShort  = errors.New("admin_password_too_short")
	ErrAdminSessionExpired = errors.New("admin_session_expired")
)

const (
	metaKeyPasswordHash = "admin.password_hash"
	metaKeyJWTSecret    = "admin.jwt_secret"
	minPasswordLength   = 8
	sessionTTL          = 24 * time.Hour
)

func NewAdminService(db *sql.DB, now func() int64) (*AdminService, error) {
	if db == nil {
		return nil, fmt.Errorf("admin_service: missing db")
	}
	if now == nil {
		now = func() int64 { return time.Now().Unix() }
	}

	// Load or generate JWT signing secret.
	secret, err := loadOrInitMeta(db, metaKeyJWTSecret, 32)
	if err != nil {
		return nil, fmt.Errorf("admin_service: jwt secret: %w", err)
	}

	return &AdminService{db: db, jwtSecret: secret, now: now}, nil
}

// IsSetUp returns true if an admin password has been configured.
func (s *AdminService) IsSetUp(ctx context.Context) (bool, error) {
	var val []byte
	err := s.db.QueryRowContext(ctx,
		`SELECT value FROM server_meta WHERE key = ?`, metaKeyPasswordHash,
	).Scan(&val)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return false, nil
		}
		return false, fmt.Errorf("admin_service: check setup: %w", err)
	}
	return len(val) > 0, nil
}

// Setup sets the initial admin password. Fails if already configured.
func (s *AdminService) Setup(ctx context.Context, password string) error {
	if len(password) < minPasswordLength {
		return ErrAdminPasswordShort
	}

	isSetUp, err := s.IsSetUp(ctx)
	if err != nil {
		return err
	}
	if isSetUp {
		return ErrAdminAlreadySetUp
	}

	hash, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		return fmt.Errorf("admin_service: bcrypt: %w", err)
	}

	_, err = s.db.ExecContext(ctx,
		`INSERT INTO server_meta (key, value) VALUES (?, ?)`,
		metaKeyPasswordHash, hash,
	)
	if err != nil {
		return fmt.Errorf("admin_service: store password: %w", err)
	}
	return nil
}

// Login verifies the password and returns a hex-encoded session token
// with an expiry timestamp.
func (s *AdminService) Login(ctx context.Context, password string) (token string, expiresAt int64, err error) {
	var hash []byte
	dbErr := s.db.QueryRowContext(ctx,
		`SELECT value FROM server_meta WHERE key = ?`, metaKeyPasswordHash,
	).Scan(&hash)
	if dbErr != nil {
		if errors.Is(dbErr, sql.ErrNoRows) {
			return "", 0, ErrAdminNotSetUp
		}
		return "", 0, fmt.Errorf("admin_service: load hash: %w", dbErr)
	}

	if err := bcrypt.CompareHashAndPassword(hash, []byte(password)); err != nil {
		return "", 0, ErrAdminBadPassword
	}

	// Generate a simple session token (HMAC-based would be overkill
	// for a single-user home server).
	tokenBytes := make([]byte, 32)
	if _, err := rand.Read(tokenBytes); err != nil {
		return "", 0, fmt.Errorf("admin_service: token entropy: %w", err)
	}

	expires := s.now() + int64(sessionTTL.Seconds())
	tokenHex := hex.EncodeToString(tokenBytes)

	// Store session in server_meta for validation.
	_, err = s.db.ExecContext(ctx,
		`INSERT OR REPLACE INTO server_meta (key, value) VALUES (?, ?)`,
		"admin.session."+tokenHex, []byte(fmt.Sprintf("%d", expires)),
	)
	if err != nil {
		return "", 0, fmt.Errorf("admin_service: store session: %w", err)
	}

	return tokenHex, expires, nil
}

// ValidateSession checks if a session token is valid and not expired.
func (s *AdminService) ValidateSession(ctx context.Context, token string) error {
	var val []byte
	err := s.db.QueryRowContext(ctx,
		`SELECT value FROM server_meta WHERE key = ?`,
		"admin.session."+token,
	).Scan(&val)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return ErrAdminSessionExpired
		}
		return fmt.Errorf("admin_service: validate session: %w", err)
	}

	var expiresAt int64
	if _, err := fmt.Sscanf(string(val), "%d", &expiresAt); err != nil {
		return ErrAdminSessionExpired
	}
	if s.now() > expiresAt {
		// Clean up expired session.
		_, _ = s.db.ExecContext(ctx,
			`DELETE FROM server_meta WHERE key = ?`,
			"admin.session."+token,
		)
		return ErrAdminSessionExpired
	}
	return nil
}

// Logout invalidates a session token.
func (s *AdminService) Logout(ctx context.Context, token string) error {
	_, err := s.db.ExecContext(ctx,
		`DELETE FROM server_meta WHERE key = ?`,
		"admin.session."+token,
	)
	if err != nil {
		return fmt.Errorf("admin_service: logout: %w", err)
	}
	return nil
}

// JWTSecret returns the signing key for admin JWT tokens.
func (s *AdminService) JWTSecret() []byte {
	return s.jwtSecret
}

// GetSetting reads a generic key-value setting from server_meta.
// Returns empty string if the key does not exist.
func (s *AdminService) GetSetting(ctx context.Context, key string) (string, error) {
	var val []byte
	err := s.db.QueryRowContext(ctx,
		`SELECT value FROM server_meta WHERE key = ?`, "setting."+key,
	).Scan(&val)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return "", nil
		}
		return "", fmt.Errorf("admin_service: get setting %q: %w", key, err)
	}
	return string(val), nil
}

// SetSetting writes a generic key-value setting to server_meta.
func (s *AdminService) SetSetting(ctx context.Context, key, value string) error {
	_, err := s.db.ExecContext(ctx,
		`INSERT OR REPLACE INTO server_meta (key, value) VALUES (?, ?)`,
		"setting."+key, []byte(value),
	)
	if err != nil {
		return fmt.Errorf("admin_service: set setting %q: %w", key, err)
	}
	return nil
}

// loadOrInitMeta loads a value from server_meta by key, or generates
// and stores `n` random bytes if the key doesn't exist.
func loadOrInitMeta(db *sql.DB, key string, n int) ([]byte, error) {
	var val []byte
	err := db.QueryRow(`SELECT value FROM server_meta WHERE key = ?`, key).Scan(&val)
	if err == nil && len(val) > 0 {
		return val, nil
	}
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return nil, err
	}

	val = make([]byte, n)
	if _, err := rand.Read(val); err != nil {
		return nil, fmt.Errorf("generate %s: %w", key, err)
	}
	_, err = db.Exec(
		`INSERT OR REPLACE INTO server_meta (key, value) VALUES (?, ?)`,
		key, val,
	)
	if err != nil {
		return nil, fmt.Errorf("store %s: %w", key, err)
	}
	return val, nil
}
