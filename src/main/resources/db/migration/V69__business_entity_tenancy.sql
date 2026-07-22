-- V69 - Business Entity tenant ownership (RBAC-002)
--
-- Every Business Entity child and runtime object resolves through business_entities,
-- making this row the immutable tenant trust anchor. Existing rows remain SHARED so
-- upgrades do not hide pre-migration definitions; newly created rows are stamped by
-- BusinessEntityService from the authenticated principal.

ALTER TABLE business_entities ADD COLUMN IF NOT EXISTS owner_user_id  BIGINT REFERENCES forge_users(id)  ON DELETE SET NULL;
ALTER TABLE business_entities ADD COLUMN IF NOT EXISTS owner_group_id BIGINT REFERENCES forge_groups(id) ON DELETE SET NULL;
ALTER TABLE business_entities ADD COLUMN IF NOT EXISTS visibility     VARCHAR(16) NOT NULL DEFAULT 'GROUP';

UPDATE business_entities b
   SET owner_user_id = u.id
  FROM forge_users u
 WHERE b.owner_user_id IS NULL
   AND b.owner_username IS NOT NULL
   AND LOWER(u.username) = LOWER(b.owner_username);

-- Deliberately only the rows present during migration are made globally visible.
UPDATE business_entities SET visibility = 'SHARED';

CREATE INDEX IF NOT EXISTS idx_business_entities_tenancy
    ON business_entities (visibility, owner_group_id, owner_user_id);
