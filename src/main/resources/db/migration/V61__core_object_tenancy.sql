-- V61 — Core object tenancy (DEF-0007 / RBAC-002)
--
-- Before this migration the core catalog (data sources, masking policies, DataScope
-- blueprints, reservations) had no owner and no group, so any caller holding the resource
-- permission could list, read and DELETE another group's objects. RBAC-002 proved this
-- live: beta_user deleted alpha_user's policy (audited, but not prevented).
--
-- The ownership pattern already used by synthetic_saved_jobs / datascope_saved_jobs /
-- pii_pattern / ri_* is applied here to the core catalog.
--
-- visibility semantics (matches pii_pattern / ri_* conventions):
--   PRIVATE — owner only (plus admin.all)
--   GROUP   — owner + members of owner_group_id (plus admin.all)   <- default for new rows
--   SHARED  — every authenticated caller holding the resource permission
--
-- BACKFILL POLICY: every pre-existing row becomes SHARED so this migration is
-- non-breaking for the running install — nothing disappears from anyone's UI on upgrade.
-- New objects are created GROUP-scoped by the application layer. Operators can re-scope
-- legacy rows to GROUP/PRIVATE from Access Control once ownership has been assigned.

-- ── data_sources ──────────────────────────────────────────────────────────────
ALTER TABLE data_sources ADD COLUMN IF NOT EXISTS owner_user_id  BIGINT REFERENCES forge_users(id)  ON DELETE SET NULL;
ALTER TABLE data_sources ADD COLUMN IF NOT EXISTS owner_username VARCHAR(120);
ALTER TABLE data_sources ADD COLUMN IF NOT EXISTS owner_group_id BIGINT REFERENCES forge_groups(id) ON DELETE SET NULL;
ALTER TABLE data_sources ADD COLUMN IF NOT EXISTS visibility     VARCHAR(16) NOT NULL DEFAULT 'GROUP';
UPDATE data_sources SET visibility = 'SHARED' WHERE owner_user_id IS NULL;
CREATE INDEX IF NOT EXISTS idx_data_sources_tenancy ON data_sources (visibility, owner_group_id, owner_user_id);

-- ── masking_policies ──────────────────────────────────────────────────────────
ALTER TABLE masking_policies ADD COLUMN IF NOT EXISTS owner_user_id  BIGINT REFERENCES forge_users(id)  ON DELETE SET NULL;
ALTER TABLE masking_policies ADD COLUMN IF NOT EXISTS owner_username VARCHAR(120);
ALTER TABLE masking_policies ADD COLUMN IF NOT EXISTS owner_group_id BIGINT REFERENCES forge_groups(id) ON DELETE SET NULL;
ALTER TABLE masking_policies ADD COLUMN IF NOT EXISTS visibility     VARCHAR(16) NOT NULL DEFAULT 'GROUP';
UPDATE masking_policies SET visibility = 'SHARED' WHERE owner_user_id IS NULL;
CREATE INDEX IF NOT EXISTS idx_masking_policies_tenancy ON masking_policies (visibility, owner_group_id, owner_user_id);

-- ── dataset_definitions (DataScope blueprints) ────────────────────────────────
ALTER TABLE dataset_definitions ADD COLUMN IF NOT EXISTS owner_user_id  BIGINT REFERENCES forge_users(id)  ON DELETE SET NULL;
ALTER TABLE dataset_definitions ADD COLUMN IF NOT EXISTS owner_username VARCHAR(120);
ALTER TABLE dataset_definitions ADD COLUMN IF NOT EXISTS owner_group_id BIGINT REFERENCES forge_groups(id) ON DELETE SET NULL;
ALTER TABLE dataset_definitions ADD COLUMN IF NOT EXISTS visibility     VARCHAR(16) NOT NULL DEFAULT 'GROUP';
UPDATE dataset_definitions SET visibility = 'SHARED' WHERE owner_user_id IS NULL;
CREATE INDEX IF NOT EXISTS idx_dataset_definitions_tenancy ON dataset_definitions (visibility, owner_group_id, owner_user_id);

-- ── reservations ──────────────────────────────────────────────────────────────
-- reserved_by already records a username but was never enforced; add the structured owner.
ALTER TABLE reservations ADD COLUMN IF NOT EXISTS owner_user_id  BIGINT REFERENCES forge_users(id)  ON DELETE SET NULL;
ALTER TABLE reservations ADD COLUMN IF NOT EXISTS owner_group_id BIGINT REFERENCES forge_groups(id) ON DELETE SET NULL;
ALTER TABLE reservations ADD COLUMN IF NOT EXISTS visibility     VARCHAR(16) NOT NULL DEFAULT 'GROUP';
UPDATE reservations SET visibility = 'SHARED' WHERE owner_user_id IS NULL;
-- Adopt the existing free-text reserved_by as the structured owner where it matches a user.
UPDATE reservations r SET owner_user_id = u.id
  FROM forge_users u
 WHERE r.owner_user_id IS NULL AND LOWER(u.username) = LOWER(r.reserved_by);
CREATE INDEX IF NOT EXISTS idx_reservations_tenancy ON reservations (visibility, owner_group_id, owner_user_id);
