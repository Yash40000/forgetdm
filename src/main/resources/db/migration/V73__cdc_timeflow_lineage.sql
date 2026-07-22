-- CDC -> point-in-time TimeFlow lineage. These fields make every CDC anchor and
-- derived snapshot independently auditable and reproducible.
ALTER TABLE virtual_snapshots ADD COLUMN IF NOT EXISTS cdc_capture_id BIGINT;
ALTER TABLE virtual_snapshots ADD COLUMN IF NOT EXISTS cdc_base_snapshot_id BIGINT;
ALTER TABLE virtual_snapshots ADD COLUMN IF NOT EXISTS cdc_from_position VARCHAR(128);
ALTER TABLE virtual_snapshots ADD COLUMN IF NOT EXISTS cdc_through_position VARCHAR(128);
ALTER TABLE virtual_snapshots ADD COLUMN IF NOT EXISTS cdc_through_change_id BIGINT;
ALTER TABLE virtual_snapshots ADD COLUMN IF NOT EXISTS cdc_through_ts TIMESTAMPTZ;
ALTER TABLE virtual_snapshots ADD COLUMN IF NOT EXISTS cdc_changes_applied BIGINT NOT NULL DEFAULT 0;

ALTER TABLE virtual_snapshots
    ADD CONSTRAINT fk_virtual_snapshot_cdc_capture
    FOREIGN KEY (cdc_capture_id) REFERENCES cdc_capture(id) ON DELETE SET NULL;

ALTER TABLE virtual_snapshots
    ADD CONSTRAINT fk_virtual_snapshot_cdc_base
    FOREIGN KEY (cdc_base_snapshot_id) REFERENCES virtual_snapshots(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_virtual_snapshot_cdc_capture
    ON virtual_snapshots(cdc_capture_id, cdc_through_change_id);

CREATE INDEX IF NOT EXISTS idx_virtual_snapshot_cdc_base
    ON virtual_snapshots(cdc_base_snapshot_id);
