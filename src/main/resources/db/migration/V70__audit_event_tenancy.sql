-- V70 - Tenant ownership for audit events (RBAC-002)
--
-- Existing ledger rows retain hash version 1 and become SHARED. Their historical
-- hashes therefore remain verifiable. New application writes use hash version 2,
-- which includes ownership and visibility in the tamper-evident payload.

ALTER TABLE audit_events ADD COLUMN IF NOT EXISTS owner_user_id  BIGINT REFERENCES forge_users(id)  ON DELETE SET NULL;
ALTER TABLE audit_events ADD COLUMN IF NOT EXISTS owner_username VARCHAR(120);
ALTER TABLE audit_events ADD COLUMN IF NOT EXISTS owner_group_id BIGINT REFERENCES forge_groups(id) ON DELETE SET NULL;
ALTER TABLE audit_events ADD COLUMN IF NOT EXISTS visibility     VARCHAR(16) NOT NULL DEFAULT 'GROUP';
ALTER TABLE audit_events ADD COLUMN IF NOT EXISTS hash_version   INTEGER NOT NULL DEFAULT 2;

UPDATE audit_events
   SET owner_username = actor,
       visibility = 'SHARED',
       hash_version = 1;

CREATE INDEX IF NOT EXISTS idx_audit_events_tenancy
    ON audit_events (visibility, owner_group_id, owner_user_id, id);
