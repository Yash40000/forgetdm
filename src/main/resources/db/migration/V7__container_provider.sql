-- Container CoW provider: snapshots can be Docker image layers (physical pg_basebackup
-- datafiles) instead of pool chunks; VDBs can be running containers whose writable
-- layer is the copy-on-write overlay2 layer — reads come from shared image layers.

ALTER TABLE virtual_snapshots ADD COLUMN provider VARCHAR(20) NOT NULL DEFAULT 'POOL';
ALTER TABLE virtual_snapshots ADD COLUMN image_ref VARCHAR(300);

ALTER TABLE virtual_databases ADD COLUMN provider VARCHAR(20) NOT NULL DEFAULT 'POOL';
ALTER TABLE virtual_databases ADD COLUMN container_id VARCHAR(120);
ALTER TABLE virtual_databases ADD COLUMN host_port INTEGER;
