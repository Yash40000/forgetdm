-- Access Definitions: ForgeTDM's Optim-inspired model for defining a referentially intact
-- data extract. An Access Definition captures WHAT to pull (driver table + filter), HOW to
-- traverse the FK graph (per-table Q1/Q2 + referential strategy), WHAT to exclude (included=false),
-- and HOW to mask specific columns (literal value, null-out, suppress, or policy-driven).

-- ─── Definition ────────────────────────────────────────────────────────────────────────────
CREATE TABLE dataset_definitions (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(200) NOT NULL UNIQUE,
    description     TEXT,
    data_source_id  BIGINT NOT NULL,
    schema_name     VARCHAR(200),
    driver_table    VARCHAR(200),
    driver_filter   TEXT,
    -- Global Q1/Q2 defaults: each table can override these via table_profiles
    global_q1       BOOLEAN NOT NULL DEFAULT TRUE,   -- include parent rows (referential integrity)
    global_q2       BOOLEAN NOT NULL DEFAULT TRUE,   -- include child rows (entity completeness)
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ─── Per-table profile ─────────────────────────────────────────────────────────────────────
-- Controls inclusion, filter, traversal strategy, and Q1/Q2 at each table in the hierarchy.
CREATE TABLE table_profiles (
    id                    BIGSERIAL PRIMARY KEY,
    dataset_id            BIGINT NOT NULL REFERENCES dataset_definitions(id) ON DELETE CASCADE,
    table_name            VARCHAR(200) NOT NULL,
    -- Inclusion: FALSE = completely excluded from extract and FK traversal
    included              BOOLEAN NOT NULL DEFAULT TRUE,
    -- Row filter (SQL WHERE expression) applied when selecting rows for this table
    filter_expr           TEXT,
    -- Referential strategy:
    --   INHERIT       use the global Q1/Q2 setting (default)
    --   FOLLOW_PARENT rows are constrained by FK join to parent PK set (default FK behaviour)
    --   INDEPENDENT   rows selected by this table's own filter_expr, not via FK join
    referential_strategy  VARCHAR(30) NOT NULL DEFAULT 'INHERIT',
    -- Per-table Q1/Q2 overrides (NULL = use global; TRUE/FALSE = explicit override)
    q1_override           BOOLEAN,   -- include this table's parents?
    q2_override           BOOLEAN,   -- include this table's children?
    -- Optional explicit load-order hint (lower = earlier); NULL = auto topological
    load_priority         INT,
    note                  TEXT,
    UNIQUE (dataset_id, table_name)
);

-- ─── Per-column masking overrides ──────────────────────────────────────────────────────────
-- Overrides the policy-driven masking at the column level, inside an Access Definition.
-- USE_POLICY  follow whatever masking rule the attached policy provides (default)
-- LITERAL     write a hardcoded literal value (literalValue) — no masking function runs
-- NULL_OUT    always write SQL NULL regardless of source value
-- SUPPRESS    exclude this column from SELECT/INSERT entirely (column will not appear in target)
CREATE TABLE column_overrides (
    id              BIGSERIAL PRIMARY KEY,
    dataset_id      BIGINT NOT NULL REFERENCES dataset_definitions(id) ON DELETE CASCADE,
    table_name      VARCHAR(200) NOT NULL,
    column_name     VARCHAR(200) NOT NULL,
    override_type   VARCHAR(30) NOT NULL DEFAULT 'USE_POLICY',
    literal_value   TEXT,
    note            TEXT,
    UNIQUE (dataset_id, table_name, column_name)
);

-- Link provision jobs to an Access Definition (optional; if set, overrides spec-level
-- tableCriteria and applies all profile + column-override logic from the definition)
ALTER TABLE provision_jobs ADD COLUMN dataset_id BIGINT;
