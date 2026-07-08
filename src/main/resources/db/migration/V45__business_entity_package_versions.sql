CREATE TABLE be_operational_package_versions (
  id                      BIGSERIAL PRIMARY KEY,
  package_id              BIGINT NOT NULL REFERENCES be_operational_packages(id) ON DELETE CASCADE,
  entity_id               BIGINT NOT NULL REFERENCES business_entities(id) ON DELETE CASCADE,
  version_number          INTEGER NOT NULL,
  status                  VARCHAR(40) NOT NULL DEFAULT 'IMMUTABLE',
  artifact_hash           VARCHAR(128) NOT NULL,
  manifest_json           TEXT NOT NULL,
  shell_script            TEXT NOT NULL,
  health_check_json       TEXT,
  promotion_json          TEXT,
  immutable_manifest_json TEXT NOT NULL,
  retention_policy        VARCHAR(80) NOT NULL DEFAULT 'STANDARD_7_YEAR',
  retention_until         TIMESTAMP NOT NULL,
  created_by              VARCHAR(200),
  created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(package_id, version_number),
  UNIQUE(package_id, artifact_hash)
);

CREATE TABLE be_operational_package_promotions (
  id                    BIGSERIAL PRIMARY KEY,
  package_id            BIGINT NOT NULL REFERENCES be_operational_packages(id) ON DELETE CASCADE,
  entity_id             BIGINT NOT NULL REFERENCES business_entities(id) ON DELETE CASCADE,
  version_id            BIGINT NOT NULL REFERENCES be_operational_package_versions(id) ON DELETE CASCADE,
  from_environment      VARCHAR(120) NOT NULL,
  to_environment        VARCHAR(120) NOT NULL,
  status                VARCHAR(40) NOT NULL DEFAULT 'READY_FOR_APPROVAL',
  approved_request_id   BIGINT REFERENCES be_governance_requests(id) ON DELETE SET NULL,
  evidence_json         TEXT NOT NULL,
  requested_by          VARCHAR(200),
  approved_by           VARCHAR(200),
  promoted_at           TIMESTAMP,
  created_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_be_pkg_versions_entity ON be_operational_package_versions(entity_id, created_at);
CREATE INDEX idx_be_pkg_versions_pkg ON be_operational_package_versions(package_id, version_number);
CREATE INDEX idx_be_pkg_promotions_entity ON be_operational_package_promotions(entity_id, created_at);
CREATE INDEX idx_be_pkg_promotions_pkg ON be_operational_package_promotions(package_id, created_at);
