// Package usecase contains the application-layer orchestrators that
// compose domain interfaces into the behaviour exposed by the HTTP
// endpoints. pairing.go implements the §4.2 / §4.3 bring-up flow.
package usecase

import (
	"context"
	"crypto/ed25519"
	"errors"
	"fmt"
	"time"

	icrypto "github.com/synctuary/synctuary-server/internal/adapter/infrastructure/crypto"
	"github.com/synctuary/synctuary-server/internal/domain/device"
	"github.com/synctuary/synctuary-server/internal/domain/nonce"
	"github.com/synctuary/synctuary-server/internal/domain/rate"
)

// Pairing-flow error set (PROTOCOL §4.3 errors). The handler maps
// these to concrete HTTP codes:
//
//	ErrBadRequest             → 400 bad_request
//	ErrSignatureInvalid       → 401 pair_signature_invalid
//	ErrNonceExpiredOrInvalid  → 410 pair_nonce_expired
//	ErrDeviceIDCollision      → 409 pair_device_id_collision
//	ErrRateLimited            → 429 rate_limited (RECOMMENDED per §4.2)
var (
	ErrBadRequest            = errors.New("pairing_bad_request")
	ErrSignatureInvalid      = errors.New("pair_signature_invalid")
	ErrNonceExpiredOrInvalid = errors.New("pair_nonce_expired")
	ErrDeviceIDCollision     = errors.New("pair_device_id_collision")
	ErrRateLimited           = errors.New("pair_rate_limited")
)

// PairingService orchestrates §4.2 (/pair/nonce) and §4.3
// (/pair/register). All CSPRNG draws (nonce, device_token) flow
// through infrastructure/crypto.GenerateRandomBytes — never
// math/rand, never an unchecked Read.
type PairingService struct {
	nonces      nonce.Store
	devices     device.Repository
	rate        rate.Limiter
	masterKey   []byte // 32 bytes, loaded at startup from SecretStore
	fingerprint []byte // 32 bytes, SHA-256 of TLS DER cert
	nonceTTL    time.Duration
}

// NewPairingService validates its dependencies up-front to surface
// misconfiguration at startup rather than on the first request.
func NewPairingService(
	nonces nonce.Store,
	devices device.Repository,
	rateLimiter rate.Limiter,
	masterKey []byte,
	fingerprint []byte,
	nonceTTL time.Duration,
) (*PairingService, error) {
	if nonces == nil || devices == nil || rateLimiter == nil {
		return nil, fmt.Errorf("pairing: missing dependency")
	}
	if len(masterKey) != 32 {
		return nil, fmt.Errorf("pairing: master_key length %d, expected 32", len(masterKey))
	}
	if len(fingerprint) != 32 {
		return nil, fmt.Errorf("pairing: fingerprint length %d, expected 32", len(fingerprint))
	}
	if nonceTTL <= 0 {
		return nil, fmt.Errorf("pairing: nonce_ttl must be positive")
	}
	return &PairingService{
		nonces:      nonces,
		devices:     devices,
		rate:        rateLimiter,
		masterKey:   masterKey,
		fingerprint: fingerprint,
		nonceTTL:    nonceTTL,
	}, nil
}

// NonceResponse is the §4.2 success payload, pre-handler-encoding.
type NonceResponse struct {
	Nonce     []byte // 32 bytes, caller encodes base64url
	ExpiresAt int64  // unix seconds
}

// IssueNonce is unauthenticated and rate-limited per source IP
// (§4.2). The limiter is consulted first so we do not burn DB
// writes on abusive clients.
func (s *PairingService) IssueNonce(ctx context.Context, sourceIP string) (*NonceResponse, int64, error) {
	now := time.Now().Unix()
	if allowed, retryAfter := s.rate.Allow(ctx, sourceIP, now); !allowed {
		return nil, retryAfter, ErrRateLimited
	}

	raw, err := icrypto.GenerateRandomBytes(nonce.Length)
	if err != nil {
		return nil, 0, fmt.Errorf("pairing: nonce entropy: %w", err)
	}
	expiresAt := now + int64(s.nonceTTL.Seconds())
	if err := s.nonces.Issue(ctx, raw, now, expiresAt, sourceIP); err != nil {
		return nil, 0, fmt.Errorf("pairing: nonce issue: %w", err)
	}
	return &NonceResponse{Nonce: raw, ExpiresAt: expiresAt}, 0, nil
}

// RegisterRequest captures the §4.3 register body after handler
// decoding. All binary fields are the raw byte values — base64url
// decoding happens in the handler layer.
type RegisterRequest struct {
	Nonce             []byte            // 32 bytes
	DeviceID          []byte            // 16 bytes
	DevicePub         ed25519.PublicKey // 32 bytes
	DeviceName        string            // 1..64 UTF-8
	Platform          string            // enum (§4.3)
	ChallengeResponse []byte            // 64 bytes (Ed25519 signature)
}

// RegisterResult returns the new device's credentials. Token is the
// one-and-only plaintext copy the caller will ever see; it is NOT
// persisted server-side (only its SHA-256 is).
type RegisterResult struct {
	DeviceToken []byte // 32 bytes, caller encodes base64url
	DeviceID    []byte
	TTLSeconds  int64 // 0 == no expiration (v0.2 behaviour)
}

// Register performs §4.4 verification in the specified order.
func (s *PairingService) Register(ctx context.Context, req *RegisterRequest) (*RegisterResult, error) {
	if err := validateRegister(req); err != nil {
		return nil, err
	}

	// 1. Consume the nonce FIRST so a misbehaving peer burning through
	//    signatures also burns through nonces. §4.4 step 1 + step 6
	//    collapsed — the nonce is proof the request was issued against
	//    a server-fresh challenge, whether the signature passes or not.
	now := time.Now().Unix()
	if err := s.nonces.VerifyAndConsume(ctx, req.Nonce, now); err != nil {
		if errors.Is(err, nonce.ErrNotFound) ||
			errors.Is(err, nonce.ErrExpired) ||
			errors.Is(err, nonce.ErrAlreadyConsumed) {
			return nil, ErrNonceExpiredOrInvalid
		}
		return nil, fmt.Errorf("pairing: verify nonce: %w", err)
	}

	// 2. Derive expected device_pub and cross-check (§4.4 step 2–3).
	expectedPub, _, err := icrypto.DeriveDeviceKeypair(s.masterKey, req.DeviceID)
	if err != nil {
		return nil, fmt.Errorf("pairing: derive expected pub: %w", err)
	}
	if !ed25519.PublicKey(expectedPub).Equal(req.DevicePub) {
		return nil, ErrSignatureInvalid
	}

	// 3. Verify challenge_response (§4.4 step 4).
	payload, err := icrypto.BuildPairPayload(req.DeviceID, req.DevicePub, s.fingerprint, req.Nonce)
	if err != nil {
		return nil, fmt.Errorf("pairing: build payload: %w", err)
	}
	if !icrypto.VerifyPairSignature(req.DevicePub, payload, req.ChallengeResponse) {
		return nil, ErrSignatureInvalid
	}

	// 4. Generate device_token, persist (device_id, ...). §4.4 step 7.
	tokenRaw, err := icrypto.GenerateRandomBytes(32)
	if err != nil {
		return nil, fmt.Errorf("pairing: token entropy: %w", err)
	}
	d := &device.Device{
		ID:         req.DeviceID,
		PublicKey:  req.DevicePub,
		TokenHash:  icrypto.HashToken(tokenRaw),
		Name:       req.DeviceName,
		Platform:   req.Platform,
		CreatedAt:  now,
		LastSeenAt: now,
	}
	if err := s.devices.Create(ctx, d); err != nil {
		if errors.Is(err, device.ErrDuplicate) {
			return nil, ErrDeviceIDCollision
		}
		return nil, fmt.Errorf("pairing: create device: %w", err)
	}

	return &RegisterResult{
		DeviceToken: tokenRaw,
		DeviceID:    req.DeviceID,
		TTLSeconds:  0,
	}, nil
}

// validateRegister rejects malformed inputs before any crypto work.
func validateRegister(r *RegisterRequest) error {
	if r == nil {
		return ErrBadRequest
	}
	if len(r.Nonce) != nonce.Length {
		return ErrBadRequest
	}
	if len(r.DeviceID) != 16 {
		return ErrBadRequest
	}
	if len(r.DevicePub) != ed25519.PublicKeySize {
		return ErrBadRequest
	}
	if len(r.ChallengeResponse) != ed25519.SignatureSize {
		return ErrBadRequest
	}
	if n := len(r.DeviceName); n < 1 || n > 64 {
		return ErrBadRequest
	}
	switch r.Platform {
	case "android", "ios", "windows", "macos", "linux", "other":
	default:
		return ErrBadRequest
	}
	return nil
}
