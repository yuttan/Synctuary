// Package db wires up the SQLite backing store.
//
// We use modernc.org/sqlite (pure-Go) to keep the build CGO-free, and
// pressly/goose/v3 to apply the embedded migrations from the
// synctuary-server/migrations/ directory.
//
// The embedded file set lives in the package that imports this one
// (typically cmd/synctuaryd) and is passed in via Migrate, so that this
// package stays reusable across entry points (daemon, CLI tool, tests).
package db

import (
	"context"
	"database/sql"
	"embed"
	"fmt"
	"time"

	"github.com/pressly/goose/v3"

	// Register the modernc.org/sqlite driver under the name "sqlite".
	_ "modernc.org/sqlite"
)

// Open returns a *sql.DB pointing at the given SQLite file.
//
// Connection pragmas (set on every checked-out connection via the DSN
// query string):
//
//   - _pragma=journal_mode(WAL)  — concurrent readers + single writer
//   - _pragma=busy_timeout(5000) — 5-second wait on contention
//   - _pragma=foreign_keys(ON)   — enforce the uploads → devices FK
//   - _pragma=synchronous(NORMAL) — safe under WAL, faster than FULL
func Open(path string) (*sql.DB, error) {
	dsn := fmt.Sprintf(
		"file:%s?_pragma=journal_mode(WAL)&_pragma=busy_timeout(5000)&_pragma=foreign_keys(ON)&_pragma=synchronous(NORMAL)",
		path,
	)
	database, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, fmt.Errorf("db: sql.Open %s: %w", path, err)
	}

	// SQLite performs best with a modest connection pool under WAL. We
	// leave rooms for a small burst of concurrent readers + 1 writer.
	database.SetMaxOpenConns(8)
	database.SetMaxIdleConns(4)
	database.SetConnMaxLifetime(30 * time.Minute)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := database.PingContext(ctx); err != nil {
		_ = database.Close()
		return nil, fmt.Errorf("db: ping %s: %w", path, err)
	}
	return database, nil
}

// Migrate applies all pending goose migrations from the supplied
// embed.FS. The FS MUST be rooted at (or above) the "migrations"
// directory that goose expects — callers pass the directory name via
// dir (e.g. "migrations").
func Migrate(database *sql.DB, fs embed.FS, dir string) error {
	goose.SetBaseFS(fs)
	if err := goose.SetDialect("sqlite3"); err != nil {
		return fmt.Errorf("db: goose dialect: %w", err)
	}
	if err := goose.Up(database, dir); err != nil {
		return fmt.Errorf("db: goose up: %w", err)
	}
	return nil
}
