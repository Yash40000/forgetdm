-- Optim-style source-to-target table and column mapping for DataScope profiles.
ALTER TABLE dataset_definitions ADD COLUMN target_data_source_id BIGINT;
ALTER TABLE dataset_definitions ADD COLUMN target_schema_name VARCHAR(200);

ALTER TABLE table_profiles ADD COLUMN target_table_name VARCHAR(200);

-- column_name is the target column inside the mapped target table.
-- source_column_name is nullable so literal/null/unused target columns do not need a source.
ALTER TABLE column_overrides ADD COLUMN source_column_name VARCHAR(200);
