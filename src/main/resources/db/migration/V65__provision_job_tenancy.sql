-- V65 - Tenant ownership for provisioning jobs (RBAC-002)
--
-- New jobs are stamped GROUP-scoped by ProvisioningService. Existing jobs remain
-- SHARED so an upgrade does not hide historical run evidence from current users.

ALTER TABLE provision_jobs ADD COLUMN IF NOT EXISTS owner_user_id  BIGINT REFERENCES forge_users(id)  ON DELETE SET NULL;
ALTER TABLE provision_jobs ADD COLUMN IF NOT EXISTS owner_username VARCHAR(120);
ALTER TABLE provision_jobs ADD COLUMN IF NOT EXISTS owner_group_id BIGINT REFERENCES forge_groups(id) ON DELETE SET NULL;
ALTER TABLE provision_jobs ADD COLUMN IF NOT EXISTS visibility     VARCHAR(16) NOT NULL DEFAULT 'GROUP';

UPDATE provision_jobs
   SET owner_username = created_by
 WHERE owner_username IS NULL
   AND created_by IS NOT NULL;

UPDATE provision_jobs j
   SET owner_user_id = u.id
  FROM forge_users u
 WHERE j.owner_user_id IS NULL
   AND j.created_by IS NOT NULL
   AND LOWER(u.username) = LOWER(j.created_by);

-- Preserve access to all pre-migration run history. New rows are stamped by the service.
UPDATE provision_jobs SET visibility = 'SHARED';

CREATE INDEX IF NOT EXISTS idx_provision_jobs_tenancy
    ON provision_jobs (visibility, owner_group_id, owner_user_id);
