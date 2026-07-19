# DISC-006 - Reject a Zero-Table Discovery Scope

**Priority:** P0

**Lane:** Each connector
**Execution status:** COMPLETE WITH HARD-PASS EXCEPTION - 7/8 acceptance cases proven on isolated H2; DISC-006-06 requires a safely provisioned metadata-restricted vendor account and is not live-certified.

## Objective

Prove that discovery rejects an empty or inaccessible schema before creating a misleading completed scan job.

## Preconditions

- Existing empty schema, non-existent schema, schema containing only excluded Flyway tables, and populated schema hidden by permissions.
- Authorized discovery user.

## Cases

| Case | Type | Action | Expected result and evidence |
|---|---|---|---|
| DISC-006-01 | API sync | `POST /api/discovery/scan/{id}?schema=<empty>`. | Request fails with a clear `No scannable tables` validation response; no classifications or success audit are created. |
| DISC-006-02 | API async | `POST /api/discovery/scan-jobs/{id}?schema=<empty>`. | Preflight rejects before a runnable job is accepted, or the accepted job ends FAILED rather than COMPLETED 100%; product contract must choose and document one behavior. |
| DISC-006-03 | UI | Select the empty schema and press Start scan. | UI blocks launch or shows one actionable failure; it never displays `COMPLETED`, `0/0`, and `100%`. |
| DISC-006-04 | Missing | Scan a non-existent schema. | Error identifies the invalid schema without exposing connection internals or falling back to a different default schema. |
| DISC-006-05 | Excluded | Scan a schema containing only `flyway_*` tables. | Scope is treated as having zero scannable tables and is rejected. |
| DISC-006-06 | Permission | Scan a populated schema through an account without metadata/table visibility. | Result distinguishes insufficient privilege from a genuinely empty schema where the driver permits it. |
| DISC-006-07 | Focus | Select only missing tables in an otherwise populated schema. | Preflight lists missing focused tables and no worker starts. |
| DISC-006-08 | History | Inspect recent scans and audit after every rejected attempt. | No false successful run appears; rejected validation is traceable with sanitized source/schema identity. |

## Automation and Exit

- Add service/controller tests for sync and async paths plus UI coverage for the `0/0` regression.
- Pass requires no success state for a scope containing zero scannable tables on any certified connector.

## 2026-07-19 Execution Result

| Case | Result | Evidence |
|---|---|---|
| DISC-006-01 | PASS | Isolated H2 sync scan rejects an empty schema before classifications are read or written. |
| DISC-006-02 | PASS | Async preflight rejects before a job is queued; history remains empty. |
| DISC-006-03 | PASS | Frontend presentation converts any legacy `COMPLETED 0/0 100%` payload to rejected/failed at 0%. |
| DISC-006-04 | PASS | Explicit missing schema reports `does not exist or is not visible` without a JDBC URL. |
| DISC-006-05 | PASS | A Flyway-only schema is treated as having no scannable tables. |
| DISC-006-06 | HARD-PASS | No safely creatable metadata-restricted vendor account is available in this pass. This remains unproven, not certified. |
| DISC-006-07 | PASS | Missing focused table names are listed and no worker starts. |
| DISC-006-08 | PASS | Sync/async rejections write sanitized rejection audit events and create no false successful history job. |

Focused result: `DiscoveryScopeValidationTest` (2), `DiscoveryFrontendBehaviorTest` (1), and existing `DiscoveryJobServiceTest` (1): 4 tests, 0 failures, 0 errors, 0 skipped.
