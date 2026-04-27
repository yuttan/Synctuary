// Package migrations carries the embedded SQL files applied by goose
// during daemon startup.
//
// Keeping the embed in its own package means the migrations travel
// with any entry point that imports it (the daemon, future admin CLI,
// tests) without each one needing to duplicate the //go:embed directive.
package migrations

import "embed"

// FS embeds every .sql file that sits next to this source file.
// goose.SetBaseFS(FS) + goose.Up(db, "migrations/...") — note that because
// goose resolves paths relative to BaseFS root, callers pass "." as the
// directory argument to goose.Up (see Dir).
//
//go:embed *.sql
var FS embed.FS

// Dir is the directory name goose.Up should be pointed at. Since FS is
// rooted at this package directory and contains the .sql files directly,
// the correct argument is ".".
const Dir = "."
