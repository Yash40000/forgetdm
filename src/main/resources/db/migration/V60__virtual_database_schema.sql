ALTER TABLE virtual_databases ADD COLUMN schema_name VARCHAR(200);

UPDATE virtual_databases v
SET schema_name = (
    SELECT s.schema_name
    FROM virtual_snapshots s
    WHERE s.id = v.current_snapshot_id
)
WHERE schema_name IS NULL;
