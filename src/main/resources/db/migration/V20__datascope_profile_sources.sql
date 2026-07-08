-- Per-table source and policy binding for mixed-source DataScope table maps.
ALTER TABLE table_profiles ADD COLUMN source_data_source_id BIGINT;
ALTER TABLE table_profiles ADD COLUMN source_schema_name VARCHAR(200);
ALTER TABLE table_profiles ADD COLUMN policy_id BIGINT;

-- Preserve existing DataScope policy behavior for already-saved profiles.
UPDATE table_profiles
SET policy_id = (
    SELECT d.policy_id
    FROM dataset_definitions d
    WHERE d.id = table_profiles.dataset_id
)
WHERE EXISTS (
    SELECT 1
    FROM dataset_definitions d
    WHERE d.id = table_profiles.dataset_id
      AND d.policy_id IS NOT NULL
);
