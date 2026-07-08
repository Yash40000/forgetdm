CREATE TABLE virtual_snapshots (
  id             BIGSERIAL PRIMARY KEY,
  name           VARCHAR(160) NOT NULL,
  snapshot_type  VARCHAR(30) NOT NULL,
  source_id      BIGINT REFERENCES data_sources(id),
  vdb_id         BIGINT,
  schema_name    VARCHAR(200),
  storage_path   VARCHAR(700) NOT NULL,
  table_count    INTEGER NOT NULL DEFAULT 0,
  row_count      BIGINT NOT NULL DEFAULT 0,
  note           VARCHAR(600),
  created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE virtual_databases (
  id                 BIGSERIAL PRIMARY KEY,
  name               VARCHAR(160) NOT NULL UNIQUE,
  source_snapshot_id BIGINT NOT NULL REFERENCES virtual_snapshots(id),
  current_snapshot_id BIGINT REFERENCES virtual_snapshots(id),
  data_source_id     BIGINT REFERENCES data_sources(id),
  jdbc_url           VARCHAR(700) NOT NULL,
  username           VARCHAR(120),
  password           VARCHAR(255),
  storage_path       VARCHAR(700) NOT NULL,
  status             VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
  created_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at         TIMESTAMP
);

ALTER TABLE virtual_snapshots
  ADD CONSTRAINT fk_virtual_snapshots_vdb
  FOREIGN KEY (vdb_id) REFERENCES virtual_databases(id) ON DELETE CASCADE;
