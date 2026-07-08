-- PII discovery classifications are scoped per (data source, SCHEMA, table, column), but the original
-- uniqueness (V1) omitted schema_name — so the same table.column in two schemas of one source collided.
-- Widen the constraint to include schema_name so multi-schema sources scan independently.
ALTER TABLE classifications DROP CONSTRAINT IF EXISTS uq_class;
ALTER TABLE classifications ADD CONSTRAINT uq_class UNIQUE (data_source_id, schema_name, table_name, column_name);
