-- V67 - Tenant ownership for the complete mainframe object family (RBAC-002)
--
-- Connections, copybooks, and jobs are the top-level authorization boundaries.
-- Copybook masks inherit from their copybook; job files inherit from their job.
-- Existing objects remain SHARED so an upgrade does not hide established assets.

ALTER TABLE mf_connections ADD COLUMN IF NOT EXISTS owner_user_id  BIGINT REFERENCES forge_users(id)  ON DELETE SET NULL;
ALTER TABLE mf_connections ADD COLUMN IF NOT EXISTS owner_username VARCHAR(120);
ALTER TABLE mf_connections ADD COLUMN IF NOT EXISTS owner_group_id BIGINT REFERENCES forge_groups(id) ON DELETE SET NULL;
ALTER TABLE mf_connections ADD COLUMN IF NOT EXISTS visibility     VARCHAR(16) NOT NULL DEFAULT 'GROUP';
UPDATE mf_connections
   SET visibility = 'SHARED'
 WHERE owner_user_id IS NULL AND owner_group_id IS NULL;
CREATE INDEX IF NOT EXISTS idx_mf_connections_tenancy
    ON mf_connections (visibility, owner_group_id, owner_user_id);

ALTER TABLE mf_copybooks ADD COLUMN IF NOT EXISTS owner_user_id  BIGINT REFERENCES forge_users(id)  ON DELETE SET NULL;
ALTER TABLE mf_copybooks ADD COLUMN IF NOT EXISTS owner_username VARCHAR(120);
ALTER TABLE mf_copybooks ADD COLUMN IF NOT EXISTS owner_group_id BIGINT REFERENCES forge_groups(id) ON DELETE SET NULL;
ALTER TABLE mf_copybooks ADD COLUMN IF NOT EXISTS visibility     VARCHAR(16) NOT NULL DEFAULT 'GROUP';
UPDATE mf_copybooks
   SET visibility = 'SHARED'
 WHERE owner_user_id IS NULL AND owner_group_id IS NULL;
CREATE INDEX IF NOT EXISTS idx_mf_copybooks_tenancy
    ON mf_copybooks (visibility, owner_group_id, owner_user_id);

ALTER TABLE mf_jobs ADD COLUMN IF NOT EXISTS owner_user_id  BIGINT REFERENCES forge_users(id)  ON DELETE SET NULL;
-- created_by is VARCHAR(160); preserve every schema-valid legacy actor during backfill.
ALTER TABLE mf_jobs ADD COLUMN IF NOT EXISTS owner_username VARCHAR(160);
ALTER TABLE mf_jobs ADD COLUMN IF NOT EXISTS owner_group_id BIGINT REFERENCES forge_groups(id) ON DELETE SET NULL;
ALTER TABLE mf_jobs ADD COLUMN IF NOT EXISTS visibility     VARCHAR(16) NOT NULL DEFAULT 'GROUP';

UPDATE mf_jobs
   SET owner_username = created_by
 WHERE owner_username IS NULL AND created_by IS NOT NULL;

UPDATE mf_jobs j
   SET owner_user_id = u.id
  FROM forge_users u
 WHERE j.owner_user_id IS NULL
   AND j.created_by IS NOT NULL
   AND LOWER(u.username) = LOWER(j.created_by);

UPDATE mf_jobs SET visibility = 'SHARED';
CREATE INDEX IF NOT EXISTS idx_mf_jobs_tenancy
    ON mf_jobs (visibility, owner_group_id, owner_user_id);
