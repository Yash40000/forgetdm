# DSRC-001 - Data-Source Lifecycle - Evidence

**Story:** DSRC-001 (P0, per bundled connector)
**Execution status:** COMPLETE WITH HARD-PASS EXCEPTIONS - live PostgreSQL, Oracle, MySQL, and H2 proof retained; remaining vendor lanes are not certified
**Backend:** ForgeTDM verification instance at `http://localhost:8099`
**Executed:** 2026-07-18 and 2026-07-19

## Acceptance Status

The complete nine-case acceptance contract is green on the PostgreSQL lane after defect fixes and live re-execution.

| Case | PostgreSQL result | Evidence summary |
|---|---|---|
| DSRC-001-01 | PASS | SOURCE, TARGET, and BOTH records were created with correct metadata, a non-secret version token, and no returned password. |
| DSRC-001-02 | PASS | Invalid and duplicate payloads were rejected with sanitized errors; no partial rows persisted. |
| DSRC-001-03 | PASS | Metadata-only edit retained the encrypted credential; explicit rotation behavior remained functional. |
| DSRC-001-04 | PASS | TARGET-only discovery and SOURCE-only target provisioning were rejected before work was created; BOTH worked in both directions. |
| DSRC-001-05 | PASS | Secret fields were absent from create, update, list, and get responses; JDBC credentials were redacted from evidence. |
| DSRC-001-06 | PASS | Unused deletion succeeded; referenced deletion returned a sanitized 409 naming dependencies. |
| DSRC-001-07 | PASS | V64 applied, current version tokens were required, and a stale editor received 409 without overwriting the saved row. |
| DSRC-001-08 | PASS | Create, update, test, and delete audit records carried actor/resource/outcome details without connector secrets. |
| DSRC-001-09 | PASS | Read-only API writes returned 403 and left data unchanged. |

PostgreSQL live retest artifact: `docs/testing/evidence/artifacts/DSRC-001-LIVE-2026-07-19.json` (9 passed, 0 failed).

## Oracle and MySQL Live Lanes

ForgeTDM opened real JDBC connections to the local Oracle and MySQL instances. Each lane passed the following nine connector-sensitive checks:

1. create with version token and secret redaction;
2. real connection test;
3. schema browse;
4. table browse;
5. column browse;
6. optimistic-lock conflict;
7. credential retention after metadata-only edit;
8. one-table source discovery; and
9. target-role preflight entering execution.

Result: **18 passed, 0 failed** across Oracle and MySQL.

Artifact: `docs/testing/evidence/artifacts/DSRC-001-VENDOR-LIVE-2026-07-19.json`.

Cross-cut validation, dependency, auditing, and authorization behavior is implemented above the JDBC dialect and was directly proven in the PostgreSQL lane. The Oracle/MySQL run adds direct vendor connection and metadata proof; it does not claim certification beyond the listed checks.

## H2 Live Lane

The reusable embedded H2 runner created a real file-backed fixture, connected through ForgeTDM, browsed APP/CUSTOMERS/columns, proved optimistic locking and credential retention, completed one-table discovery, and passed target preflight. Result: **9 passed, 0 failed**.

Artifacts:

- `docs/testing/run-dsrc-001-h2-lane.ps1`
- `docs/testing/evidence/artifacts/DSRC-001-H2-LIVE-2026-07-19.json`

## Defects Found and Closed

| Defect | Resolution evidence |
|---|---|
| DEF-0017 | Validation rejects invalid records without persistence. |
| DEF-0018 | Application errors are sanitized and do not leak SQL or row values. |
| DEF-0019 | Dependency conflicts identify blockers and connection tests are audited. |
| DEF-0024 | Live V64 migration, stale-update 409, role preflight, and BOTH behavior passed. |
| DEF-0025 | DB2 z/OS aliases cannot advertise or invoke the LUW native loader; 32 offline contract tests pass. |

## HARD-PASS Boundaries

| Lane | Status | Missing prerequisite |
|---|---|---|
| DB2 LUW / z/OS | HARD-PASS | No reachable DB2 server or approved z/OS utility adapter. Offline dialect and safety contracts pass, but this is not live certification. |
| SQL Server | HARD-PASS | No reachable SQL Server instance or `bcp` client. Offline contract checks pass, but this is not live certification. |
| Teradata / Vantage | HARD-PASS | No reachable Teradata fixture or approved test account. |
| H2 | PASS | Embedded live JDBC lifecycle runner passed 9/9 and cleaned up its disposable fixture. |

## Exit Decision

DSRC-001 is complete for this test cycle under the story's documented certification-status exit rule. PostgreSQL and H2 are directly proven, and the Oracle/MySQL connector-sensitive lanes are live-proven. DB2, SQL Server, and Teradata remain explicit HARD-PASS exceptions and are not represented as passed or certified.
