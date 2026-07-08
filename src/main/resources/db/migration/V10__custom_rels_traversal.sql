-- V10: User-defined PKs, user-defined FK relationships, and per-relationship traversal rules.
--
-- These three tables extend the DataScope (formerly "Access Definition") model to allow:
--   1. Tool-level primary key overrides for tables that have no PK in the database catalog
--      (common on mainframe/legacy schemas, DB2 z/OS unload files, etc.)
--   2. Tool-level FK relationships that are not enforced in the database but are known
--      to the team (soft relationships, implicit FKs on mainframe, cross-schema rels).
--   3. Per-relationship traversal control — replaces the coarser per-table Q1/Q2 overrides
--      with fine-grained direction control at each FK edge.

-- ─── User-defined primary keys ─────────────────────────────────────────────────────────────
-- Defines which column(s) are the logical PK for a table within the tool's scope.
-- Overrides catalog-sourced PK when present; lets the tool join/subset correctly even
-- when no PK or unique index exists in the database.
CREATE TABLE user_defined_pks (
    id              BIGSERIAL PRIMARY KEY,
    dataset_id      BIGINT NOT NULL REFERENCES dataset_definitions(id) ON DELETE CASCADE,
    table_name      VARCHAR(200) NOT NULL,
    column_names    VARCHAR(1000) NOT NULL,  -- comma-separated ordered list of logical PK column(s)
    note            TEXT,
    UNIQUE (dataset_id, table_name)
);

-- ─── User-defined FK relationships ─────────────────────────────────────────────────────────
-- Captures implicit or soft foreign key relationships not enforced in the database catalog.
-- Stored at the DataScope level so they only apply within the scope of this blueprint.
-- parent_columns and child_columns are comma-separated parallel arrays (positional match).
-- Example: parent_table=CUSTOMERS, parent_columns=CUST_ID
--          child_table=ORDERS,    child_columns=CUSTOMER_NUMBER
CREATE TABLE user_defined_relationships (
    id              BIGSERIAL PRIMARY KEY,
    dataset_id      BIGINT NOT NULL REFERENCES dataset_definitions(id) ON DELETE CASCADE,
    rel_name        VARCHAR(300),               -- optional human label
    parent_table    VARCHAR(200) NOT NULL,
    parent_columns  VARCHAR(1000) NOT NULL,     -- comma-separated
    child_table     VARCHAR(200) NOT NULL,
    child_columns   VARCHAR(1000) NOT NULL,     -- comma-separated, parallel with parent_columns
    note            TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ─── Per-relationship traversal rules ──────────────────────────────────────────────────────
-- Controls whether a specific FK edge (DB-catalog-sourced or user-defined) is followed
-- during the FK closure traversal, and in which direction(s).
--
-- traverse_direction values (matches IBM Optim "Access Definition Relationship" semantics):
--   BOTH     — follow in both Q1 (child→parent) and Q2 (parent→child) directions
--   Q1_ONLY  — follow only in Q1 direction (child → parent lookup)
--   Q2_ONLY  — follow only in Q2 direction (parent → child lookup)
--   NONE     — do not traverse this relationship in either direction
--
-- rel_source = 'DB'   means this rule controls a catalog-level FK edge
-- rel_source = 'USER' means this rule controls a user_defined_relationships row (rel_ref_id ≠ null)
--
-- priority: lower number = earlier in traversal order within the same table (future use)
CREATE TABLE relationship_traversal_rules (
    id                  BIGSERIAL PRIMARY KEY,
    dataset_id          BIGINT NOT NULL REFERENCES dataset_definitions(id) ON DELETE CASCADE,
    parent_table        VARCHAR(200) NOT NULL,
    child_table         VARCHAR(200) NOT NULL,
    rel_source          VARCHAR(10)  NOT NULL DEFAULT 'DB',    -- 'DB' | 'USER'
    rel_ref_id          BIGINT,       -- user_defined_relationships.id when rel_source='USER'
    traverse_direction  VARCHAR(10)  NOT NULL DEFAULT 'BOTH',  -- BOTH | Q1_ONLY | Q2_ONLY | NONE
    priority            INT          NOT NULL DEFAULT 0,
    note                TEXT
);
