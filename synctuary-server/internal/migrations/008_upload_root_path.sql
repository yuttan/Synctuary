-- +goose Up
-- Upload sessions need to remember which storage root they target,
-- so that the final chunk rename lands in the correct share directory
-- (not the global root).
ALTER TABLE uploads ADD COLUMN root_path TEXT NOT NULL DEFAULT '';

-- +goose Down
ALTER TABLE uploads DROP COLUMN root_path;
