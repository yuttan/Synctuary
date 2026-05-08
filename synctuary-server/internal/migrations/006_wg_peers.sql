-- +goose Up
-- Migration 006: WireGuard VPN peers
--
-- Each row represents a WireGuard peer that can connect through the
-- server's built-in userspace VPN tunnel. The server generates a
-- Curve25519 keypair per peer; only the public_key is stored here.
-- The private key is shown once to the admin for client config and
-- never persisted on the server.
--
-- assigned_ip is the virtual IP within the WireGuard subnet CIDR
-- (e.g. 10.100.0.2). It is allocated by the IPAM module and must
-- be unique among active peers.
--
-- device_id is an optional FK to devices for admin correlation.
-- A peer can exist without a paired device.

CREATE TABLE wg_peers (
    id          BLOB    NOT NULL PRIMARY KEY,   -- 16-byte identifier
    public_key  BLOB    NOT NULL UNIQUE,        -- 32-byte Curve25519 public key
    assigned_ip TEXT    NOT NULL UNIQUE,         -- virtual IP within CIDR
    name        TEXT    NOT NULL DEFAULT '',     -- admin display label
    device_id   BLOB,                           -- optional FK → devices.device_id
    created_at  INTEGER NOT NULL,               -- unix seconds
    revoked_at  INTEGER,                        -- non-null = soft-deleted

    FOREIGN KEY (device_id)
        REFERENCES devices(device_id) ON DELETE SET NULL
);

CREATE INDEX idx_wg_peers_device ON wg_peers(device_id);
CREATE INDEX idx_wg_peers_active ON wg_peers(revoked_at) WHERE revoked_at IS NULL;

-- +goose Down
DROP INDEX IF EXISTS idx_wg_peers_active;
DROP INDEX IF EXISTS idx_wg_peers_device;
DROP TABLE IF EXISTS wg_peers;
