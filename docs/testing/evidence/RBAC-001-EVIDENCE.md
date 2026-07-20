# RBAC-001 - Role and Group Permission Matrix - Evidence

**Story:** RBAC-001 (P0, all lanes)

**Specification:** `docs/testing/cases/ready/RBAC-001.md`

**Final status:** COMPLETE - 10/10 required cases pass

**Reviewed implementation:** `6614e22` (`test: complete RBAC-001 permission enforcement`)

**Execution dates:** 2026-07-17 (live identity/session matrix) and 2026-07-19 (closure, automation, regression, review)

## Scope and method

The final decision combines four independent evidence layers:

1. Live local API/session execution for all built-in roles, group-derived permissions, role unions, admin lifecycle, default deny, and signed-in-user deactivation.
2. A compiled-controller inventory test that discovers the actual `@RestController` surface and proves every discovered `/api/` route is public by contract, authenticated identity self-service by contract, or mapped to a documented permission.
3. A 21-case Microsoft Edge Playwright suite covering direct URL denial and action-level gating across Synthetic, DataScope, Business Entity, Mapping, Auto Provision, Self-Service, Unstructured, Mainframe, Forge Data Store, Discovery, Data Sources, Policies, Scripts, Virtualization, Validation, Automation, and Audit.
4. Full backend and frontend regression gates plus a fresh read-only independent review.

Personal API-token create/revoke routes are an explicit exception to feature permissions: they require authentication and the service scopes every operation to the current user. They are not module administration routes.

## Required-case results

| Case | Result | Direct evidence |
|---|---|---|
| RBAC-001-01 - exact role matrix | PASS | `Rbac001RoutePermissionMatrixTest` asserts the complete versioned permission set for ADMIN, TDM_ARCHITECT, DATA_ENGINEER, TESTER, and AUDITOR; the earlier live `/api/auth/me` comparison also found no missing, extra, duplicate, or unknown permission. |
| RBAC-001-02 - allowed API operations | PASS | Exact required permission and `admin.all` reach the controller for the explicit route matrix; the compiled controller inventory repeats the exact-permission check over the discovered API surface. |
| RBAC-001-03 - forbidden API operations | PASS | Every explicit and discovered protected route is denied with `403` before its controller when the permission is absent, and the tests verify an `ACCESS_DENIED` audit record. |
| RBAC-001-04 - UI action gating | PASS | Final Playwright suite: 21 expected, 0 unexpected, 0 skipped, 0 flaky. Forbidden controls are absent while permitted read/run/cancel actions remain available. DEF-0005 and DEF-0006 are closed. |
| RBAC-001-05 - group refresh | PASS | Live same-session removal and re-addition of group membership removed and restored the effective permission set without requiring a new login. |
| RBAC-001-06 - role union | PASS | Live direct-plus-group role assignment returned the exact union, never an intersection or privilege outside either role. |
| RBAC-001-07 - admin lifecycle | PASS | ADMIN completed user create/deactivate/delete; non-admin attempts were denied `security.admin`. |
| RBAC-001-08 - Auditor | PASS | AUDITOR retained read/evidence access while mutations were denied. The closure suite additionally proves Automation and Audit are readable but non-administrative for the built-in AUDITOR permission set. |
| RBAC-001-09 - default deny | PASS | An authenticated unmapped write requires `admin.all`; a non-admin cannot gain access through an omitted route rule. |
| RBAC-001-10 - deactivation | PASS | Deactivating a signed-in user invalidated the current session immediately, denied re-login while inactive, and restored access only after reactivation and authentication. |

## Closure corrections

The final source review found and corrected four permission-contract mismatches before acceptance:

- Automation now uses `integration.read` and `integration.manage`, not hard-coded role names.
- Audit re-anchoring consistently requires `admin.all` in both the filter and service.
- Synthetic partition cancellation requires `synthetic.cancel`; partition retry requires `synthetic.run`.
- Mapping retry, workflow run, run creation, load, and multi-load consistently require `mapping.run`.

## Automated evidence

| Gate | Result |
|---|---|
| Playwright Edge RBAC suite | 21 passed; 0 failed; 0 skipped; 0 flaky |
| Maven full regression | 370 tests; 0 failures; 0 errors; 1 intentional skip |
| Frontend typecheck | PASS (`next typegen` and `tsc --noEmit`) |
| Frontend lint | PASS (`eslint .`) |
| Frontend production build | PASS; 27 routes generated |
| Diff hygiene | PASS (`git diff --check`; line-ending notices only) |
| Independent final review | PASS - no concrete UI/API permission mismatch or evidence blocker found |

Final retained browser artifacts:

- `docs/testing/evidence/artifacts/RBAC-001-PLAYWRIGHT-FINAL-2026-07-19.json`

  SHA-256 `A4C9565194053BD88279E30090701BAE2BD1584B80756C7A353978ACAE9AB25C`
- `docs/testing/evidence/artifacts/RBAC-001-PLAYWRIGHT-FINAL-2026-07-19.xml`

  SHA-256 `DE34C2067AB81D57EAA3E5AA5B0C341149E479266D243F1368B7E084C539910F`
- `docs/testing/evidence/artifacts/RBAC-001-PLAYWRIGHT-FINAL-2026-07-19.last-run.json`

  SHA-256 `91D1C43004802CD49950D78EB11C8FA7D05DA8FFFFE219A8B13B2F561BC00903`
- `docs/testing/evidence/artifacts/RBAC-001-ENTRY-GATE-2026-07-19.json`
- `docs/testing/evidence/artifacts/RBAC-001-FINAL-GATE-2026-07-19.json`

The Windows Playwright process retained its temporary Next server after all tests had finished. The isolated server was stopped explicitly, after which the reporters finalized. The final JSON/JUnit artifacts and all 21 per-test traces/screenshots agree on a passing result; this was test-harness teardown latency, not an application failure.

## Defect disposition

- DEF-0005: CLOSED - initial permission-aware navigation and core-page gating.
- DEF-0006: CLOSED in `6614e22` - remaining feature actions, handler guards, permission-specific run/cancel behavior, and browser coverage completed.

## Exit checklist

- [x] All ten required cases have direct retained evidence.
- [x] Every compiled API route participates in the authorization contract.
- [x] Built-in role permission sets are asserted exactly.
- [x] UI and API permission semantics are aligned across the named feature areas.
- [x] Denials return `403`, stop before controller execution, and record `ACCESS_DENIED`.
- [x] Full backend/frontend regression is green.
- [x] Independent review returned PASS.
- [x] No required HARD-PASS exception remains for RBAC-001.
