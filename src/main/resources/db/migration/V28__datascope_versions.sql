-- DataScope blueprint version history (change control: snapshot, list, compare, roll back later).
-- A version is an immutable JSON snapshot of the whole blueprint (definition + profiles + overrides +
-- custom PKs + custom relationships + traversal rules) at a point in time.
CREATE TABLE datascope_versions (
  id            BIGSERIAL PRIMARY KEY,
  dataset_id    BIGINT NOT NULL,
  version_no    INTEGER NOT NULL,
  note          VARCHAR(500),
  created_by    VARCHAR(120),
  snapshot_json TEXT NOT NULL,
  created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_datascope_versions_dataset ON datascope_versions (dataset_id, version_no DESC);
