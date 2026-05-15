-- +goose Up
CREATE TABLE IF NOT EXISTS thumbnails (
    path         TEXT    NOT NULL,
    format       TEXT    NOT NULL DEFAULT 'jpeg',
    width        INTEGER NOT NULL,
    height       INTEGER NOT NULL,
    data         BLOB    NOT NULL,
    source_hash  TEXT,
    generated_at INTEGER NOT NULL,
    PRIMARY KEY (path, width, height)
);

-- +goose Down
DROP TABLE IF EXISTS thumbnails;
