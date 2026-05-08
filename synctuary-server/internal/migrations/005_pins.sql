-- +goose Up
-- Migration 005: Pins (Quick Access bookmarks)
--
-- A pin is a per-device shortcut to an arbitrary directory within a
-- share. Clients display pinned paths on the home screen for one-tap
-- navigation, similar to Windows Quick Access.
--
-- Pins are device-scoped: each device maintains its own set. When a
-- device is revoked, its pins cascade-delete.

CREATE TABLE pins (
    device_id   BLOB    NOT NULL,              -- 16-byte FK → devices.device_id
    share_id    BLOB    NOT NULL,              -- 16-byte FK → shares.id
    path        TEXT    NOT NULL,              -- directory path within the share
    label       TEXT    NOT NULL DEFAULT '',    -- optional user-supplied display name
    sort_order  INTEGER NOT NULL DEFAULT 0,    -- lower = higher in list
    created_at  INTEGER NOT NULL,              -- unix seconds

    PRIMARY KEY (device_id, share_id, path),

    FOREIGN KEY (device_id)
        REFERENCES devices(device_id) ON DELETE CASCADE,
    FOREIGN KEY (share_id)
        REFERENCES shares(id) ON DELETE CASCADE
);

CREATE INDEX idx_pins_device_sort ON pins(device_id, sort_order ASC);

-- +goose Down
DROP INDEX IF EXISTS idx_pins_device_sort;
DROP TABLE IF EXISTS pins;
