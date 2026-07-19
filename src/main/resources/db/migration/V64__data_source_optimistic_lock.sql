-- DSRC-001-07: prevent stale connection editors from silently overwriting newer configuration.
ALTER TABLE data_sources ADD COLUMN IF NOT EXISTS lock_version BIGINT NOT NULL DEFAULT 0;
