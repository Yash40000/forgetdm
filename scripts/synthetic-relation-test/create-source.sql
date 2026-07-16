DROP SCHEMA IF EXISTS tool_rel_src CASCADE;
CREATE SCHEMA tool_rel_src;

-- Deliberately no PRIMARY KEY or FOREIGN KEY constraints. The relationship
-- and both logical keys are defined only in the ForgeTDM synthetic plan.
CREATE TABLE tool_rel_src.rel_parent (
    parent_id BIGINT,
    parent_name VARCHAR(80),
    region_code VARCHAR(10)
);

CREATE TABLE tool_rel_src.rel_child (
    child_id BIGINT,
    parent_id BIGINT,
    account_ref VARCHAR(24),
    balance NUMERIC(12,2)
);
