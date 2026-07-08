CREATE TABLE be_execution_plan_runs (
  id                   BIGSERIAL PRIMARY KEY,
  entity_id            BIGINT NOT NULL REFERENCES business_entities(id) ON DELETE CASCADE,
  execution_plan_id    BIGINT NOT NULL REFERENCES be_entity_execution_plans(id) ON DELETE CASCADE,
  engine               VARCHAR(40) NOT NULL,
  engine_run_id        VARCHAR(120),
  engine_status        VARCHAR(80),
  status               VARCHAR(40) NOT NULL DEFAULT 'SUBMITTED',
  launch_request_json  TEXT NOT NULL,
  launch_result_json   TEXT NOT NULL,
  loader_strategy_json TEXT,
  created_by           VARCHAR(200),
  created_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_be_exec_run_entity ON be_execution_plan_runs(entity_id, created_at);
CREATE INDEX idx_be_exec_run_plan ON be_execution_plan_runs(execution_plan_id, created_at);
