-- Synctuary schema migration 002: single-active-session-per-path enforcement
-- Adds partial UNIQUE INDEX on uploads(path) for PROTOCOL v0.2.2 §6.3.5.
-- Managed by: pressly/goose/v3

-- +goose Up
-- +goose StatementBegin

-- PROTOCOL v0.2.2 §6.3.5 single-active-session-per-path rule:
--   At most one upload row per `path` may simultaneously have completed = 0.
--   A concurrent init attempt on the same path MUST be rejected with
--   409 upload_in_progress.
--
-- Implementation: SQLite partial UNIQUE INDEX over (path) filtered by
-- completed = 0. Completed sessions are not subject to the constraint
-- (their target is the final file at `path`, tracked separately by
-- filesystem state, not by this row).
--
-- ------------------------------------------------------------------
-- Important: why NOT include `expires_at > strftime('%s','now')` here
-- ------------------------------------------------------------------
-- SQLite evaluates a partial-index WHERE clause only at the moment of
-- INSERT/UPDATE — it is NOT re-evaluated for existing rows as time
-- passes (see https://www.sqlite.org/partialindex.html). An "expires_at
-- > now" predicate would therefore latch in the index at insertion time
-- and never release as the row's deadline passes, causing a later,
-- legitimate retry on the same path to collide with a stale row and
-- incorrectly fail with upload_in_progress.
--
-- Expiry is therefore handled at the application layer inside the
-- UploadSession.Init transaction:
--
--   BEGIN IMMEDIATE;
--   DELETE FROM uploads
--     WHERE path = :path AND completed = 0 AND expires_at <= :now;
--   INSERT INTO uploads (...) VALUES (...);   -- violates unique index
--                                             -- → ErrUploadInProgress
--   COMMIT;
--
-- This keeps the index predicate simple and correct, localizes expiry
-- semantics to one code path, and retains atomicity via BEGIN IMMEDIATE.
CREATE UNIQUE INDEX idx_uploads_path_active
  ON uploads(path)
  WHERE completed = 0;

-- +goose StatementEnd

-- +goose Down
-- +goose StatementBegin
DROP INDEX IF EXISTS idx_uploads_path_active;
-- +goose StatementEnd
