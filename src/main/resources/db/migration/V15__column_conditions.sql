-- Conditional masking: per-column override applies only when a condition is true for the row.
ALTER TABLE column_overrides ADD COLUMN cond_column          VARCHAR(200);
ALTER TABLE column_overrides ADD COLUMN cond_operator        VARCHAR(20);
ALTER TABLE column_overrides ADD COLUMN cond_value           TEXT;
ALTER TABLE column_overrides ADD COLUMN cond_join_table      VARCHAR(200);
ALTER TABLE column_overrides ADD COLUMN cond_join_source_col VARCHAR(200);
ALTER TABLE column_overrides ADD COLUMN cond_join_target_col VARCHAR(200);
