# DSRC-001 - Concurrency and role-capability evidence - 2026-07-19

**Scope:** DSRC-001-04 and DSRC-001-07
**Result:** CLOSED; focused contract suite and live PostgreSQL verification PASS
**Defect:** [DEF-0024](../defects/DEF-0024-datasource-stale-update-and-role-capability.md)

## Implemented contract

| Criterion | Implemented behavior |
|---|---|
| Stale update | Update payload must carry the current `version`; missing or stale values return `409` before save. |
| Simultaneous update | JPA `@Version` detects a true persistence race; the API returns a sanitized `409`. |
| Secret preservation | A current-version metadata-only edit does not replace the stored password. |
| Discovery role | Only SOURCE or BOTH can enter discovery validation/scan. |
| Provision source | MASK_COPY and SUBSET_MASK require SOURCE or BOTH when a source is supplied. |
| Provision target | Any supplied target must be TARGET or BOTH before the job is saved or queued. |

## Automated execution

Command:

```text
mvn -q -Dtest=DataSourceConcurrencyContractTest,ConnectionFailureClassificationTest,SafeJdbcRedactionTest,GlobalExceptionAuditTest test
```

Result: **20 tests passed, 0 failures, 0 errors, 0 skipped**.

The focused suite covers the new contract plus adjacent connection-classification, credential
redaction, exception sanitization, and audit behavior.

## Live execution

`docs/testing/evidence/artifacts/DSRC-001-LIVE-2026-07-19.json` retains nine passing assertions:

- V64 applied and populated `lock_version`;
- a stale editor received sanitized 409 and did not overwrite the current row;
- a versionless update was rejected before save;
- TARGET-only discovery was rejected before scan creation;
- SOURCE-only target provisioning was rejected before persistence or queueing;
- TARGET-only use as a provision source was rejected before persistence or queueing; and
- BOTH completed source discovery and passed target preflight.

The independent Oracle/MySQL runner repeated optimistic locking, credential retention, source discovery, and target preflight on both live vendor connections: 18/18 checks passed.

## Exit decision

- DSRC-001-04: **PASS on PostgreSQL, Oracle, and MySQL live lanes**.
- DSRC-001-07: **PASS on PostgreSQL, Oracle, and MySQL live lanes**.
- DEF-0024 is closed.
- DSRC-001 remains open because not every bundled connector lane is available or executed.
- Non-PostgreSQL connector lanes remain explicit HARD-PASS candidates until their vendor fixtures
  are available; they are not reported as passed.
