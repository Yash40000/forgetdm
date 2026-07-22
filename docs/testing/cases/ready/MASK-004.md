# MASK-004 - Cross-table and cross-database masking consistency

**Area:** Masking
**Priority:** P0
**Lane:** Unit, provisioning salt strategy, and live cross-connector joins
**Execution status:** COMPLETE - 10/10 PASS

## Objective

Prove that one logical value receives the same masked representation wherever a governed relationship
requires it, including parent/child keys and separate target databases, while unrelated values remain
column-scoped and do not become accidentally correlated.

## Acceptance Cases

| Case | Expected result | Result |
|---|---|---|
| MASK-004-01 | Canonical semantic functions return the same output for the same value across unrelated tables and physical column names | PASS |
| MASK-004-02 | A parent key and child foreign key using a non-semantic deterministic function share one salt and preserve the join | PASS |
| MASK-004-03 | Transitive and branching relationships assign one stable salt to every member of the connected key component | PASS |
| MASK-004-04 | Reordering tables or relationship edges does not change any shared key salt or masked value | PASS - DEF-0037 fixed |
| MASK-004-05 | Composite relationships preserve each aligned key pair without merging unrelated component columns | PASS |
| MASK-004-06 | DB-catalog and tool-defined relationships produce the same consistency behavior | PASS |
| MASK-004-07 | One-sided or function-mismatched key masking produces a clear pre-run warning | PASS |
| MASK-004-08 | Unrelated non-semantic columns retain distinct column salts and do not become correlated | PASS |
| MASK-004-09 | PostgreSQL, Oracle, and MySQL retain identical masked key checksums and full parent/child join counts | PASS - 48/48 joins on each engine |
| MASK-004-10 | Focused consistency tests and the masking/provisioning compatibility regression remain green | PASS - 125/125 regression |

## Result

- Eight focused consistency tests passed after the DEF-0037 fix.
- PostgreSQL, Oracle, and MySQL each retained 48 of 48 parent/child joins.
- All three connectors produced canonical checksum `1bf131c89c1d97fc342c7e5d1b63005e758b817e0b527828a81b4c77e74c92cb`.
- Compatibility regression passed 125 tests with no failures, errors, or skips.
- No HARD-PASS was required.

## Execution Rules

- Use synthetic identifiers only.
- Database fixtures must be disposable or rollback-only.
- Compare canonical values and join counts after JDBC round trips; do not rely on source inspection alone.
- Open a defect for every failed case and rerun the affected cases after the fix.
