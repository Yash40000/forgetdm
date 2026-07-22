# RBAC-002 - Cross-Group Object Isolation - Closure Evidence

**Story:** RBAC-002 (P0, all lanes)

**Specification:** `docs/testing/cases/ready/RBAC-002.md`

**Final result:** **PASS - 9/9**

**Closed:** 2026-07-21

**Defects:** DEF-0007 and DEF-0032 fixed, retested, and closed

## Evidence layers

| Layer | Result | Retained evidence |
|---|---:|---|
| Execution authorization | PASS | `artifacts/RBAC-002-ENTRY-GATE-2026-07-21.json` |
| Focused object-isolation suite | 90 tests, 0 failures/errors/skips | `artifacts/RBAC-002-FOCUSED-2026-07-21.json` |
| Live HTTP security matrix | 35 checks, 0 failures | `artifacts/RBAC-002-LIVE-2026-07-21.txt` |
| Complete backend regression | 503 tests, 0 failures, 0 errors | `artifacts/RBAC-002-REGRESSION-2026-07-21.json` |
| Frontend compile safety | PASS (`next typegen` and `tsc --noEmit`) | `artifacts/RBAC-002-REGRESSION-2026-07-21.json` |

The backend regression contains one unrelated opt-in skip: `SyntheticScenarioPackTest.runsSyntheticScenarioPackAndWritesReport`. No RBAC-002 test was skipped, and no RBAC-002 case is classified HARD-PASS.

## Acceptance reconciliation

| Case | Result | Direct proof |
|---|---|---|
| RBAC-002-01 - collection isolation | PASS | Live ALPHA/BETA policy listing plus focused list-scope tests for data sources, policies, reservations, provisioning, mappings, mainframe, unstructured, validation, business entities, and audit. |
| RBAC-002-02 - IDOR reads | PASS | Parent-resolved child/version tests and direct runtime/file/report/job read tests deny another tenant before returning protected content. |
| RBAC-002-03 - writes and deletes | PASS | Live cross-group policy delete returned 403 and the owner object survived. Focused tests assert no child cascade, snapshot, relationship, file, or runtime mutation occurs after denial. Structured `ACCESS_DENIED` audit evidence is present. |
| RBAC-002-04 - execute/cancel/export/reserve/promote | PASS | Provisioning, mapping execution, mainframe jobs, unstructured jobs, audit export, reservations, retries, and asynchronous workers reauthorize ownership and references before side effects. |
| RBAC-002-05 - approvals | PASS | `ProvisioningTenancyTest.approvalIsTenantScopedRetainsMakerCheckerAndAllowsAdmin` proves cross-group denial, maker-checker separation, and admin control. |
| RBAC-002-06 - search/filter/facets | PASS | Tenant scope is applied before limits and aggregation. Audit search, facets, stats, CSV export, job histories, and governed collections cannot count or return another tenant's records. |
| RBAC-002-07 - explicit sharing | PASS | Shared objects are accessible while private siblings remain hidden. Mapping downloads and runtime paths honor sharing but still reauthorize linked parents/references. |
| RBAC-002-08 - administrator | PASS | Administrator access succeeds through the same guarded paths and live admin operations remain auditable. Ordinary-user tests remain denied. |
| RBAC-002-09 - membership changes | PASS | `OwnershipGuardTest.groupObjectsFollowCurrentMembershipOnEveryRequest` proves authorization uses current membership, not cached object entitlement. Subsequent mutations fail after membership removal. |

## Defect history and resolution

The first execution exposed globally visible core objects (DEF-0007). V61 added trusted ownership, group, visibility, collection scoping, and `OwnershipGuard`. The broader audit then found child-ID, runtime-job, indirect-reference, mainframe, mapping, business-entity, validation, unstructured, and audit paths that could bypass only guarding the top-level object (DEF-0032).

The completed fix resolves the parent before child access, stamps runtime ownership from the authenticated caller, scopes lists before pagination/aggregation, reauthorizes indirect references before persistence or worker dispatch, retains the initiating principal in asynchronous work, and preserves legacy rows only through explicit `SHARED` compatibility migrations.

## Independent evidence review

The closure review did not infer story completion from aggregate test counts. It enumerated each of the nine acceptance cases, matched each to named focused tests and live checks, confirmed the complete regression, and checked that the only skipped test is outside RBAC-002. The retained records contain no credentials or session values.

**Disposition:** accepted for closure. Any future cross-group exposure remains an S0/S1 release blocker.
