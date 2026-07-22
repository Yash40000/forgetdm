-- V66 - Tenant ownership for the complete mapping family (RBAC-002)
--
-- Mapping versions inherit authorization from mapping_definitions. Mapping runs keep an
-- ownership snapshot so background execution and retained output evidence remain scoped even
-- if the parent definition changes later. Existing rows remain SHARED for upgrade compatibility.

ALTER TABLE mapping_definitions ADD COLUMN IF NOT EXISTS owner_user_id  BIGINT REFERENCES forge_users(id)  ON DELETE SET NULL;
ALTER TABLE mapping_definitions ADD COLUMN IF NOT EXISTS owner_username VARCHAR(120);
ALTER TABLE mapping_definitions ADD COLUMN IF NOT EXISTS owner_group_id BIGINT REFERENCES forge_groups(id) ON DELETE SET NULL;
ALTER TABLE mapping_definitions ADD COLUMN IF NOT EXISTS visibility     VARCHAR(16) NOT NULL DEFAULT 'GROUP';
UPDATE mapping_definitions SET visibility = 'SHARED';
CREATE INDEX IF NOT EXISTS idx_mapping_definitions_tenancy
    ON mapping_definitions (visibility, owner_group_id, owner_user_id);

ALTER TABLE mapping_file_assets ADD COLUMN IF NOT EXISTS owner_user_id  BIGINT REFERENCES forge_users(id)  ON DELETE SET NULL;
ALTER TABLE mapping_file_assets ADD COLUMN IF NOT EXISTS owner_username VARCHAR(120);
ALTER TABLE mapping_file_assets ADD COLUMN IF NOT EXISTS owner_group_id BIGINT REFERENCES forge_groups(id) ON DELETE SET NULL;
ALTER TABLE mapping_file_assets ADD COLUMN IF NOT EXISTS visibility     VARCHAR(16) NOT NULL DEFAULT 'GROUP';
UPDATE mapping_file_assets SET owner_username = created_by WHERE owner_username IS NULL;
UPDATE mapping_file_assets a
   SET owner_user_id = u.id
  FROM forge_users u
 WHERE a.owner_user_id IS NULL
   AND a.created_by IS NOT NULL
   AND LOWER(u.username) = LOWER(a.created_by);
UPDATE mapping_file_assets SET visibility = 'SHARED';
CREATE INDEX IF NOT EXISTS idx_mapping_file_assets_tenancy
    ON mapping_file_assets (visibility, owner_group_id, owner_user_id);

ALTER TABLE mapping_workflows ADD COLUMN IF NOT EXISTS owner_user_id  BIGINT REFERENCES forge_users(id)  ON DELETE SET NULL;
ALTER TABLE mapping_workflows ADD COLUMN IF NOT EXISTS owner_username VARCHAR(120);
ALTER TABLE mapping_workflows ADD COLUMN IF NOT EXISTS owner_group_id BIGINT REFERENCES forge_groups(id) ON DELETE SET NULL;
ALTER TABLE mapping_workflows ADD COLUMN IF NOT EXISTS visibility     VARCHAR(16) NOT NULL DEFAULT 'GROUP';
UPDATE mapping_workflows SET visibility = 'SHARED';
CREATE INDEX IF NOT EXISTS idx_mapping_workflows_tenancy
    ON mapping_workflows (visibility, owner_group_id, owner_user_id);

ALTER TABLE mapping_execution_runs ADD COLUMN IF NOT EXISTS owner_user_id  BIGINT REFERENCES forge_users(id)  ON DELETE SET NULL;
ALTER TABLE mapping_execution_runs ADD COLUMN IF NOT EXISTS owner_username VARCHAR(120);
ALTER TABLE mapping_execution_runs ADD COLUMN IF NOT EXISTS owner_group_id BIGINT REFERENCES forge_groups(id) ON DELETE SET NULL;
ALTER TABLE mapping_execution_runs ADD COLUMN IF NOT EXISTS visibility     VARCHAR(16) NOT NULL DEFAULT 'GROUP';
UPDATE mapping_execution_runs SET owner_username = created_by WHERE owner_username IS NULL;
UPDATE mapping_execution_runs r
   SET owner_user_id = u.id
  FROM forge_users u
 WHERE r.owner_user_id IS NULL
   AND r.created_by IS NOT NULL
   AND LOWER(u.username) = LOWER(r.created_by);
UPDATE mapping_execution_runs SET visibility = 'SHARED';
CREATE INDEX IF NOT EXISTS idx_mapping_execution_runs_tenancy
    ON mapping_execution_runs (visibility, owner_group_id, owner_user_id);
