-- Preferred masking policy for DataScope-driven provisioning.
-- Column maps still store per-target-column actions; USE_POLICY resolves through this policy at launch.
ALTER TABLE dataset_definitions ADD COLUMN policy_id BIGINT;
