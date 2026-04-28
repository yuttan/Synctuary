-- Synctuary schema migration 003: Favorites (PROTOCOL v0.2.3 §8)
-- Managed by: pressly/goose/v3
--
-- Adds two tables for the new Favorites endpoint family:
--
--   favorite_lists  — one row per named list. Visible to every device
--                     paired with the same master_key (per §8 model).
--                     The `hidden` flag is a SOFT hide gating §8.2's
--                     default response; clients are responsible for
--                     biometric/PIN gating before passing
--                     ?include_hidden=true (see §8.9).
--
--   favorite_items  — one row per (list, path). Composite PK enforces
--                     the §8.7 idempotent-add rule at the schema level.
--                     ON DELETE CASCADE removes items when their list
--                     is dropped (§8.6).
--
-- Both tables track the originating device for audit. The FK uses
-- ON DELETE SET NULL so revoking a device (§7.2) does NOT also wipe
-- the favorites it created — the lists outlive the device.

-- +goose Up
-- +goose StatementBegin

CREATE TABLE favorite_lists (
    id                    BLOB    NOT NULL PRIMARY KEY CHECK (length(id) = 16),
    name                  TEXT    NOT NULL CHECK (length(name) BETWEEN 1 AND 256),
    hidden                INTEGER NOT NULL DEFAULT 0 CHECK (hidden IN (0, 1)),
    created_at            INTEGER NOT NULL,
    modified_at           INTEGER NOT NULL,
    created_by_device_id  BLOB CHECK (created_by_device_id IS NULL OR length(created_by_device_id) = 16),
    FOREIGN KEY (created_by_device_id) REFERENCES devices(device_id) ON DELETE SET NULL
) STRICT;

-- §8.2 default ordering is `modified_at DESC`. Index speeds the common
-- list-summary query without a sort.
CREATE INDEX idx_favorite_lists_modified ON favorite_lists(modified_at DESC);

-- The `hidden` flag is a low-cardinality boolean; partial index on
-- hidden=0 supports the default `?include_hidden=false` filter without
-- bloating the index for the rare ?include_hidden=true path.
CREATE INDEX idx_favorite_lists_visible ON favorite_lists(modified_at DESC) WHERE hidden = 0;

CREATE TABLE favorite_items (
    list_id             BLOB    NOT NULL CHECK (length(list_id) = 16),
    path                TEXT    NOT NULL,
    added_at            INTEGER NOT NULL,
    added_by_device_id  BLOB CHECK (added_by_device_id IS NULL OR length(added_by_device_id) = 16),
    PRIMARY KEY (list_id, path),
    FOREIGN KEY (list_id) REFERENCES favorite_lists(id) ON DELETE CASCADE,
    FOREIGN KEY (added_by_device_id) REFERENCES devices(device_id) ON DELETE SET NULL
) STRICT;

-- §8.3 returns items in `added_at ASC`; this index makes that an
-- index-only scan for any given list.
CREATE INDEX idx_favorite_items_list_added ON favorite_items(list_id, added_at ASC);

-- +goose StatementEnd

-- +goose Down
-- +goose StatementBegin

DROP INDEX IF EXISTS idx_favorite_items_list_added;
DROP TABLE IF EXISTS favorite_items;

DROP INDEX IF EXISTS idx_favorite_lists_visible;
DROP INDEX IF EXISTS idx_favorite_lists_modified;
DROP TABLE IF EXISTS favorite_lists;

-- +goose StatementEnd
