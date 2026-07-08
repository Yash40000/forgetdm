CREATE TABLE synthetic_saved_jobs (
  id              VARCHAR(80) PRIMARY KEY,
  owner_user_id   BIGINT REFERENCES forge_users(id) ON DELETE SET NULL,
  owner_username  VARCHAR(120) NOT NULL,
  name            VARCHAR(200) NOT NULL,
  description     VARCHAR(500),
  plan_json       TEXT NOT NULL,
  last_run_job_id VARCHAR(80),
  created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_synthetic_saved_job_owner_name UNIQUE (owner_username, name)
);

CREATE INDEX idx_synthetic_saved_jobs_owner ON synthetic_saved_jobs(owner_user_id, updated_at DESC);
