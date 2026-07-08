ALTER TABLE classifications ADD COLUMN schema_name VARCHAR(200);
ALTER TABLE masking_policies ADD COLUMN data_source_id BIGINT;
ALTER TABLE masking_policies ADD COLUMN schema_name VARCHAR(200);
ALTER TABLE masking_rules ADD COLUMN schema_name VARCHAR(200);

ALTER TABLE classifications DROP CONSTRAINT uq_class;
ALTER TABLE classifications ADD CONSTRAINT uq_class UNIQUE (data_source_id, schema_name, table_name, column_name);

ALTER TABLE masking_rules DROP CONSTRAINT uq_rule;
ALTER TABLE masking_rules ADD CONSTRAINT uq_rule UNIQUE (policy_id, schema_name, table_name, column_name);
