CREATE TABLE synthetic_generation_jobs (
  id               VARCHAR(80) PRIMARY KEY,
  owner_user_id    BIGINT REFERENCES forge_users(id) ON DELETE SET NULL,
  owner_username   VARCHAR(120) NOT NULL,
  dataset          VARCHAR(200),
  receiver         VARCHAR(30),
  load_action      VARCHAR(40),
  table_count      INTEGER NOT NULL DEFAULT 0,
  planned_rows     BIGINT NOT NULL DEFAULT 0,
  status           VARCHAR(30) NOT NULL,
  cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
  percent          INTEGER NOT NULL DEFAULT 0,
  stage            VARCHAR(160),
  message          TEXT,
  detail           TEXT,
  current_table    VARCHAR(240),
  table_rows_done  BIGINT NOT NULL DEFAULT 0,
  table_rows_total BIGINT NOT NULL DEFAULT 0,
  rows_done        BIGINT NOT NULL DEFAULT 0,
  rows_total       BIGINT NOT NULL DEFAULT 0,
  error            TEXT,
  plan_json        TEXT,
  result_json      TEXT,
  started_at       TIMESTAMP NOT NULL,
  finished_at      TIMESTAMP,
  updated_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_synthetic_jobs_owner_started ON synthetic_generation_jobs(owner_user_id, started_at DESC);
CREATE INDEX idx_synthetic_jobs_status ON synthetic_generation_jobs(status);
