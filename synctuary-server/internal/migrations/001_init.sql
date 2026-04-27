-- Synctuary initial schema — PROTOCOL v0.2.2 compliant
-- Target: modernc.org/sqlite (pure Go), SQLite 3.8.0+ for partial index support
-- Managed by: pressly/goose/v3

-- +goose Up
-- +goose StatementBegin

-- devices: authenticated device registry (§4 Pairing / §5 Auth)
--   device_id    = 128-bit random identifier (client-generated during pairing)
--   device_pub   = Ed25519 public key (32 bytes raw, PROTOCOL §3.2)
--   token_hash   = SHA-256 of device_token (PROTOCOL §4.3 — never store raw token)
--   revoked / revoked_at = soft-delete for audit trail (PROTOCOL §5.2 token revocation)
CREATE TABLE devices (
    device_id     BLOB NOT NULL PRIMARY KEY CHECK (length(device_id) = 16),
    device_pub    BLOB NOT NULL             CHECK (length(device_pub) = 32),
    token_hash    BLOB NOT NULL UNIQUE      CHECK (length(token_hash) = 32),
    device_name   TEXT,
    platform      TEXT,
    created_at    INTEGER NOT NULL,
    last_seen_at  INTEGER NOT NULL DEFAULT 0,
    revoked       INTEGER NOT NULL DEFAULT 0 CHECK (revoked IN (0, 1)),
    revoked_at    INTEGER
) STRICT;

CREATE INDEX idx_devices_revoked ON devices(revoked) WHERE revoked = 0;

-- pair_nonces: single-use server-issued nonces for replay protection (§4.2)
--   nonce      = 256-bit CSPRNG output (PROTOCOL v0.2.2 §4.2 CSPRNG mandate)
--   source_ip  = audit / rate-limit observation (rate-limit itself lives in RateLimiter, not DB)
--   consumed   = flipped to 1 on VerifyAndConsume; expired rows GC'd periodically
CREATE TABLE pair_nonces (
    nonce       BLOB NOT NULL PRIMARY KEY CHECK (length(nonce) = 32),
    issued_at   INTEGER NOT NULL,
    expires_at  INTEGER NOT NULL,
    consumed    INTEGER NOT NULL DEFAULT 0 CHECK (consumed IN (0, 1)),
    source_ip   TEXT
) STRICT;

CREATE INDEX idx_pair_nonces_expires ON pair_nonces(expires_at);

-- uploads: chunked upload session state (§6.3)
--   upload_id       = opaque server-issued string (PROTOCOL §6.3.1, suggest 128+ bits base64url)
--   path            = target path relative to server root (§1 path conventions)
--   sha256_expected = final content hash, verified at completion (§6.3.2 → 422 on mismatch)
--   staging_path    = temporary file; atomic rename to `path` on success
--   completed       = 1 once final rename succeeds (inputs to §6.3.5 partial unique index, migration 002)
--   device_id       = owning device (for GC scoping and DELETE authorization)
--   overwrite       = preserved from init request for completion-time conflict check
CREATE TABLE uploads (
    upload_id       TEXT    NOT NULL PRIMARY KEY,
    path            TEXT    NOT NULL,
    size            INTEGER NOT NULL CHECK (size >= 0),
    sha256_expected BLOB    NOT NULL CHECK (length(sha256_expected) = 32),
    uploaded_bytes  INTEGER NOT NULL DEFAULT 0 CHECK (uploaded_bytes >= 0),
    staging_path    TEXT    NOT NULL,
    device_id       BLOB    NOT NULL CHECK (length(device_id) = 16),
    overwrite       INTEGER NOT NULL DEFAULT 0 CHECK (overwrite IN (0, 1)),
    completed       INTEGER NOT NULL DEFAULT 0 CHECK (completed IN (0, 1)),
    created_at      INTEGER NOT NULL,
    last_write_at   INTEGER NOT NULL,
    expires_at      INTEGER NOT NULL,
    FOREIGN KEY (device_id) REFERENCES devices(device_id) ON DELETE CASCADE
) STRICT;

CREATE INDEX idx_uploads_expires   ON uploads(expires_at) WHERE completed = 0;
CREATE INDEX idx_uploads_device_id ON uploads(device_id);

-- server_meta: key-value for singletons (master_key, schema version banner, etc.)
--   master_key stored here with file-level 0600 permission as baseline;
--   v0.4+ will migrate to OS secret store (see PROTOCOL §3.1 / arch v3 §9 TODO).
CREATE TABLE server_meta (
    key   TEXT NOT NULL PRIMARY KEY,
    value BLOB
) STRICT;

-- +goose StatementEnd

-- +goose Down
-- +goose StatementBegin
DROP TABLE IF EXISTS uploads;
DROP TABLE IF EXISTS pair_nonces;
DROP TABLE IF EXISTS devices;
DROP TABLE IF EXISTS server_meta;
-- +goose StatementEnd
