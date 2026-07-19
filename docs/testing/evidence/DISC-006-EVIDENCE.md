# DISC-006 Evidence - Reject Zero-Table Discovery Scope

**Date:** 2026-07-19
**Verdict:** COMPLETE WITH HARD-PASS EXCEPTION. Seven acceptance cases are proven; permission behavior is explicitly unproven because no metadata-restricted account can be safely created in this pass.

## Fixture and Safety

The backend contract uses an in-memory H2 database named per test execution. It creates only `EMPTY_SCOPE`, `FLYWAY_SCOPE`, and `POPULATED_SCOPE` schemas in that disposable database. No user connection, table, classification, audit ledger, or vendor database is modified.

## Proven Results

| Case | Result | Proof |
|---|---|---|
| 01 Sync empty | PASS | Empty scope returns `no scannable tables`; no classification save/delete occurs. |
| 02 Async empty | PASS | Preflight throws before queuing; `scan-jobs` history for the scope is empty. |
| 03 UI regression | PASS | `liveScanPresentation(COMPLETED, 0 tables, 100%)` returns `FAILED`, `0%`, and rejected state. |
| 04 Missing schema | PASS | H2 metadata reports `does not exist or is not visible`; no JDBC URL is returned. |
| 05 Flyway-only | PASS | `flyway_schema_history` is excluded and the schema is rejected as no scannable tables. |
| 07 Missing focus | PASS | The missing table name is identified and no async worker starts. |
| 08 Audit/history | PASS | Rejections record `DISCOVERY_SCAN_REJECTED` or `DISCOVERY_JOB_REJECTED` with safe schema/reason fields; no queued/completed job audit is emitted. |

## HARD-PASS

DISC-006-06 is not marked passed. Its acceptance criterion needs a connection account that can authenticate but cannot enumerate metadata or read table metadata. No safe disposable vendor account is available. The implementation distinguishes that state from an empty schema whenever JDBC metadata enumerates schemas: it returns `does not exist or is not visible to this connection`, rather than `no scannable tables`.

## Retained Artifacts

- `docs/testing/evidence/artifacts/DISC-006-FOCUSED-TEST-2026-07-19.txt`
- `docs/testing/evidence/artifacts/DISC-006-FRONTEND-TYPECHECK-2026-07-19.txt`
- Maven Surefire reports for `DiscoveryScopeValidationTest`, `DiscoveryFrontendBehaviorTest`, and `DiscoveryJobServiceTest`

Focused result: 4 tests, 0 failures, 0 errors, 0 skipped. Frontend typecheck (`next typegen && tsc --noEmit`) also passed with exit code 0.

## Defect

`DEF-0029` was found and closed in this pass.
