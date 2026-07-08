-- Delphix-style logical TimeFlow engine:
-- timeflows group snapshots into lineages (dSource timeflows, and per-VDB timeflows
-- that branch from a parent snapshot). Snapshots become chunk-store manifests
-- (content-addressed, deduplicated, compressed) instead of full SQL scripts.

CREATE TABLE timeflows (
  id                  BIGSERIAL PRIMARY KEY,
  name                VARCHAR(200) NOT NULL,
  container_type      VARCHAR(20) NOT NULL,            -- DSOURCE | VDB
  source_id           BIGINT,                          -- data_sources.id for DSOURCE timeflows
  vdb_id              BIGINT,                          -- virtual_databases.id for VDB timeflows
  parent_snapshot_id  BIGINT,                          -- snapshot this timeflow branched from
  schema_name         VARCHAR(200),
  created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE virtual_snapshots ADD COLUMN timeflow_id BIGINT;
ALTER TABLE virtual_snapshots ADD COLUMN manifest_hash VARCHAR(64);
ALTER TABLE virtual_snapshots ADD COLUMN chunk_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE virtual_snapshots ADD COLUMN new_chunk_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE virtual_snapshots ADD COLUMN logical_bytes BIGINT NOT NULL DEFAULT 0;
ALTER TABLE virtual_snapshots ADD COLUMN stored_bytes BIGINT NOT NULL DEFAULT 0;

ALTER TABLE virtual_databases ADD COLUMN timeflow_id BIGINT;
ALTER TABLE virtual_databases ADD COLUMN target_kind VARCHAR(30) NOT NULL DEFAULT 'H2';
ALTER TABLE virtual_databases ADD COLUMN target_data_source_id BIGINT;
