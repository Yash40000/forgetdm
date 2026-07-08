CREATE TABLE be_orchestration_flows (
  id            BIGSERIAL PRIMARY KEY,
  entity_id     BIGINT NOT NULL REFERENCES business_entities(id) ON DELETE CASCADE,
  name          VARCHAR(220) NOT NULL,
  description   TEXT,
  version_no    INTEGER NOT NULL DEFAULT 1,
  status        VARCHAR(40) NOT NULL DEFAULT 'DRAFT',
  canvas_json   TEXT NOT NULL,
  created_by    VARCHAR(200),
  created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE be_orchestration_debug_runs (
  id                BIGSERIAL PRIMARY KEY,
  entity_id         BIGINT NOT NULL REFERENCES business_entities(id) ON DELETE CASCADE,
  flow_id           BIGINT NOT NULL REFERENCES be_orchestration_flows(id) ON DELETE CASCADE,
  mode              VARCHAR(60) NOT NULL DEFAULT 'DEBUG_DRY_RUN',
  status            VARCHAR(40) NOT NULL DEFAULT 'COMPLETED',
  current_step_key  VARCHAR(160),
  input_json        TEXT NOT NULL,
  events_json       TEXT NOT NULL,
  created_by        VARCHAR(200),
  started_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at      TIMESTAMP
);

CREATE INDEX idx_be_flow_entity ON be_orchestration_flows(entity_id, updated_at);
CREATE INDEX idx_be_flow_debug_entity ON be_orchestration_debug_runs(entity_id, started_at);
CREATE INDEX idx_be_flow_debug_flow ON be_orchestration_debug_runs(flow_id, started_at);
