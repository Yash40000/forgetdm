-- Free-form conditional masking: a raw SQL WHERE expression (and optional JOIN) per column.
-- Evaluated in the database as a per-row flag; supports any (a AND b) OR c logic and multi-column joins.
ALTER TABLE column_overrides ADD COLUMN cond_expr TEXT;
ALTER TABLE column_overrides ADD COLUMN cond_join TEXT;
