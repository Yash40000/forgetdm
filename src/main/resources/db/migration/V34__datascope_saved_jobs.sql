-- Reusable, owner-private DataScope provisioning jobs (parity with synthetic_saved_jobs).
-- spec_json stores the exact /api/jobs submit payload so a saved job re-runs faithfully.
-- The RUN of a saved job still goes through the normal provisioning maker-checker gate in
-- ProvisioningService.submit(), so no separate approval workflow is duplicated here.
CREATE TABLE datascope_saved_jobs (
  id                    VARCHAR(80) PRIMARY KEY,
  owner_user_id         BIGINT REFERENCES forge_users(id) ON DELETE SET NULL,
  owner_username        VARCHAR(120) NOT NULL,
  name                  VARCHAR(200) NOT NULL,
  description           VARCHAR(500),
  spec_json             TEXT NOT NULL,
  last_run_job_id       BIGINT,
  -- In-app recurring scheduler (Spring CronExpression). schedule_enabled + next_run_at drive the sweep.
  schedule_cron         VARCHAR(120),
  schedule_zone         VARCHAR(60),
  schedule_enabled      BOOLEAN NOT NULL DEFAULT FALSE,
  next_run_at           TIMESTAMP,
  last_scheduled_run_at TIMESTAMP,
  created_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_datascope_saved_job_owner_name UNIQUE (owner_username, name)
);

CREATE INDEX idx_datascope_saved_jobs_owner ON datascope_saved_jobs(owner_user_id, updated_at DESC);
-- Sweep lookup: enabled jobs whose next_run_at is due.
CREATE INDEX idx_datascope_saved_jobs_due ON datascope_saved_jobs(schedule_enabled, next_run_at);
