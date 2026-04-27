package db

import (
	"context"
	"database/sql"
	"errors"
	"fmt"

	"github.com/synctuary/synctuary-server/internal/domain/nonce"
)

// NonceStore is the SQLite-backed implementation of nonce.Store.
//
// Consume is performed inside a BEGIN IMMEDIATE transaction so the
// SELECT → UPDATE sequence cannot be interleaved with another
// consumer of the same nonce. SQLite's immediate write lock makes
// this effectively an atomic compare-and-swap.
type NonceStore struct {
	db *sql.DB
}

func NewNonceStore(database *sql.DB) *NonceStore {
	return &NonceStore{db: database}
}

var _ nonce.Store = (*NonceStore)(nil)

func (s *NonceStore) Issue(ctx context.Context, n []byte, issuedAt, expiresAt int64, sourceIP string) error {
	if len(n) != nonce.Length {
		return fmt.Errorf("db: nonce length %d, expected %d", len(n), nonce.Length)
	}
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO pair_nonces (nonce, issued_at, expires_at, consumed, source_ip)
		VALUES (?, ?, ?, 0, ?)
	`, n, issuedAt, expiresAt, nullableString(sourceIP))
	if err != nil {
		if isUniqueViolation(err) {
			// A fresh 256-bit CSPRNG collision is astronomically
			// unlikely; treat it as a hard failure rather than
			// rotating silently — the underlying cause is almost
			// certainly an RNG misuse bug worth surfacing.
			return fmt.Errorf("db: nonce collision (RNG misuse?): %w", err)
		}
		return fmt.Errorf("db: insert nonce: %w", err)
	}
	return nil
}

func (s *NonceStore) VerifyAndConsume(ctx context.Context, n []byte, now int64) error {
	if len(n) != nonce.Length {
		return fmt.Errorf("db: nonce length %d, expected %d", len(n), nonce.Length)
	}

	tx, err := s.db.BeginTx(ctx, &sql.TxOptions{Isolation: sql.LevelDefault})
	if err != nil {
		return fmt.Errorf("db: begin verify-consume: %w", err)
	}
	defer func() { _ = tx.Rollback() }()

	// SQLite driver note: BeginTx does NOT map to BEGIN IMMEDIATE by
	// default. We force it via a raw statement so the write lock is
	// acquired before another connection can race us.
	if _, err := tx.ExecContext(ctx, `BEGIN IMMEDIATE`); err != nil {
		// Some drivers refuse a nested BEGIN after the implicit one
		// from BeginTx; in that case the implicit DEFERRED tx is
		// good enough because the UPDATE below will upgrade the
		// lock and the WHERE clause makes the CAS atomic anyway.
		//
		// We ignore the error and proceed.
		_ = err
	}

	var (
		expiresAt int64
		consumed  int64
	)
	err = tx.QueryRowContext(ctx,
		`SELECT expires_at, consumed FROM pair_nonces WHERE nonce = ?`, n,
	).Scan(&expiresAt, &consumed)
	if errors.Is(err, sql.ErrNoRows) {
		return nonce.ErrNotFound
	}
	if err != nil {
		return fmt.Errorf("db: select nonce: %w", err)
	}
	if expiresAt <= now {
		return nonce.ErrExpired
	}
	if consumed == 1 {
		return nonce.ErrAlreadyConsumed
	}

	res, err := tx.ExecContext(ctx,
		`UPDATE pair_nonces SET consumed = 1 WHERE nonce = ? AND consumed = 0`,
		n,
	)
	if err != nil {
		return fmt.Errorf("db: consume nonce: %w", err)
	}
	// A 0-row update here means another goroutine raced us and won;
	// the nonce has already been consumed.
	if affected, aerr := res.RowsAffected(); aerr == nil && affected == 0 {
		return nonce.ErrAlreadyConsumed
	}

	if err := tx.Commit(); err != nil {
		return fmt.Errorf("db: commit verify-consume: %w", err)
	}
	return nil
}

func (s *NonceStore) CollectExpired(ctx context.Context, now int64) (int, error) {
	res, err := s.db.ExecContext(ctx, `DELETE FROM pair_nonces WHERE expires_at <= ?`, now)
	if err != nil {
		return 0, fmt.Errorf("db: collect expired nonces: %w", err)
	}
	n, err := res.RowsAffected()
	if err != nil {
		return 0, fmt.Errorf("db: collect expired rows: %w", err)
	}
	return int(n), nil
}
