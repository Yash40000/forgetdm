CREATE TABLE synthetic_job_partitions (
  id                 VARCHAR(80) PRIMARY KEY,
  job_id             VARCHAR(80) NOT NULL REFERENCES synthetic_generation_jobs(id) ON DELETE CASCADE,
  partition_number   INTEGER NOT NULL,
  dependency_wave    INTEGER NOT NULL DEFAULT 0,
  table_name         VARCHAR(240) NOT NULL,
  row_start          BIGINT NOT NULL,
  row_end            BIGINT NOT NULL,
  planned_rows       BIGINT NOT NULL,
  rows_completed     BIGINT NOT NULL DEFAULT 0,
  status             VARCHAR(30) NOT NULL,
  worker_id          VARCHAR(240),
  cancel_requested   BOOLEAN NOT NULL DEFAULT FALSE,
  attempt_count      INTEGER NOT NULL DEFAULT 0,
  heartbeat_at       TIMESTAMP,
  lease_expires_at   TIMESTAMP,
  started_at         TIMESTAMP,
  finished_at        TIMESTAMP,
  error              TEXT,
  created_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_synthetic_job_partition UNIQUE(job_id, table_name, partition_number)
);

CREATE INDEX idx_synthetic_partitions_job ON synthetic_job_partitions(job_id, dependency_wave, table_name, partition_number);
CREATE INDEX idx_synthetic_partitions_queue ON synthetic_job_partitions(status, lease_expires_at);

CREATE TABLE synthetic_target_leases (
  target_key      VARCHAR(320) PRIMARY KEY,
  job_id          VARCHAR(80) NOT NULL,
  owner_username  VARCHAR(120) NOT NULL,
  target_label    VARCHAR(320),
  acquired_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
