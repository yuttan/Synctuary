-- +goose Up
-- Migration 004: Shares (multi-drive sharing)
--
-- A share maps a human-readable name to a host-side directory path.
-- The admin configures shares via the Web UI; clients discover them
-- via GET /api/v1/shares.
--
-- The "default" share is auto-created at startup from the legacy
-- storage.root_path config value, preserving backward compatibility
-- with pre-v0.6 clients that omit the ?share= parameter.

CREATE TABLE shares (
    id          BLOB    NOT NULL PRIMARY KEY,  -- 16-byte CSPRNG
    name        TEXT    NOT NULL,              -- display name (1..256 NFC chars)
    host_path   TEXT    NOT NULL UNIQUE,       -- absolute path on the server host
    read_only   INTEGER NOT NULL DEFAULT 0,    -- 0 = read-write, 1 = read-only
    icon        TEXT    NOT NULL DEFAULT '',    -- optional icon hint (e.g. "folder", "hdd", "film")
    sort_order  INTEGER NOT NULL DEFAULT 0,    -- lower = higher in client list
    is_default  INTEGER NOT NULL DEFAULT 0,    -- 1 = legacy root_path share (at most one)
    created_at  INTEGER NOT NULL,              -- unix seconds
    modified_at INTEGER NOT NULL               -- unix seconds
);

CREATE INDEX idx_shares_sort ON shares(sort_order ASC, name ASC);

-- Enforce at most one default share.
CREATE UNIQUE INDEX idx_shares_default ON shares(is_default) WHERE is_default = 1;

-- +goose Down
DROP INDEX IF EXISTS idx_shares_default;
DROP INDEX IF EXISTS idx_shares_sort;
DROP TABLE IF EXISTS shares;
