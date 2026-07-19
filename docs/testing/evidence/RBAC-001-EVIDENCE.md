# RBAC-001 — Role and Group Permission Matrix — Evidence

**Story:** RBAC-001 (P0, All lanes)
**Spec:** `docs/testing/cases/ready/RBAC-001.md`
**Execution status:** EXECUTED LIVE — 9/10 cases PASS; RBAC-001-04 FAIL → [DEF-0005](../defects/DEF-0005-ui-does-not-gate-actions-by-permission.md) (OPEN).

## Run metadata

| Field | Value |
|---|---|
| Environment | Live local stack — frontend `http://localhost:3000`, backend `http://localhost:8088` |
| Executed | 2026-07-17 |
| Method | Browser-driven (Claude in Chrome), real HTTP against the running app; API + one UI check |
| Operator | Yash / Claude |
| Build | Running dev build serving 43 mask functions + V54 audit (confirmed current) |

## Test fixtures (provisioned live as ADMIN)

One active user per built-in role plus edge users (all password-set via `POST/PUT /api/security/users`):

| User | Roles (direct) | Group | Effective source |
|---|---|---|---|
| `admin` | ADMIN | TDM Admins | `admin.all` |
| `rbac_architect` | TDM_ARCHITECT | — | direct |
| `rbac_engineer` | DATA_ENGINEER | — | direct |
| `rbac_tester` | TESTER | — | direct |
| `rbac_auditor` | AUDITOR | — | direct |
| `rbac_norole` | — | — | none |
| `rbac_grouponly` | — | RBAC Engineers (DATA_ENGINEER) | group-derived |
| `rbac_union` | TESTER | RBAC Engineers (DATA_ENGINEER) | direct ∪ group |

## Result summary

| # | Case | Status | Evidence |
|---|---|---|---|
| 01 | Effective permissions == `RoleDefinition` per role | **PASS** | `/api/auth/me` diffed vs `RoleDefinition` for all 7 users: exact match, **0 missing, 0 extra, no duplicates**. Counts: ARCHITECT 43, ENGINEER 31, TESTER 18, AUDITOR 16, norole 0. |
| 02 | Allowed read + write/run succeed | **PASS** | Every permitted GET → 200; every permitted write passed the filter (400/500 from empty-body validation, never 401/403). |
| 03 | Forbidden write/run/admin → 403 + `ACCESS_DENIED` | **PASS** | Every unpermitted call → exactly 403; audit shows matching `ACCESS_DENIED` events (actor, `requires <perm>`, outcome=FAILURE, severity=CRITICAL, hash-chained). No data changed (deny occurs in filter before controller). |
| 04 | UI hides/disables forbidden actions | **FIXED — PASS on core pages** | Originally FAIL (no client permission layer). Fixed under **DEF-0005 (CLOSED)**: added `usePermissions()`/`<Can>`, gated all nav by read permission, and gated mutation controls on Masking Policies, Data Sources, Validation, Virtualization, Masking Scripts, PII Discovery — **verified live as `rbac_auditor`** (forbidden controls hidden; read content intact) with admin regression. Remaining pages tracked as **DEF-0006 (LOW, OPEN)**; API authoritative throughout. |
| 05 | Group role add/remove refresh | **PASS** | `rbac_grouponly`: in group → 31 perms / `datascope.manage` write allowed (400); removed via admin token on the *same live session* → **0 perms, roles [], write 403** immediately (per-request principal recomputation, no re-login); re-added → restored. |
| 06 | Two roles direct + via group = union | **PASS** | `rbac_union`: `/me` = 32 perms = exact `union(TESTER, DATA_ENGINEER)`, roles `[TESTER, DATA_ENGINEER]`; no intersection, no privilege beyond either role. |
| 07 | Admin CRUD as ADMIN vs non-admin | **PASS** | ADMIN (via API token) create→200, deactivate→200, delete→200. Every non-admin `POST /api/security/users` → 403 (`security.admin`). |
| 08 | AUDITOR read ok, all mutations denied | **PASS** | `rbac_auditor`: 4/4 reads → 200; 6/6 mutations (policies, datasets, reservations, synthetic, security, unmapped) → 403. |
| 09 | Default deny on unmapped write | **PASS** | `POST /api/zzz-unmapped-route`: every non-admin → 403 (requires `admin.all`); admin passes filter → 500 (no handler). Ordinary roles cannot inherit by omission. |
| 10 | Deactivate signed-in user | **PASS** | `rbac_tester` live session: GET → 200; deactivated via admin token; **same session GET → 401 immediately**; re-login while inactive → 401; reactivated → re-login 200. No indefinite privilege. |

## Method notes

- Identities switched by real `POST /api/auth/login` (session cookie). Admin actions during another user's live session were issued through a separate **`Authorization: Bearer` admin API token** (`credentials:'omit'`) so the victim cookie was never disturbed — this is what made the same-session refresh (05) and revocation (10) tests genuinely live rather than inferred.
- Writes used empty `{}` bodies so the authorization gate is exercised without persisting real data; heavy authorized writes (VDB provision, validation-run) were deliberately excluded after they saturated the backend thread pool on first attempt.
- Ground truth: `RoleDefinition.ALL` (permission sets) and `AccessControlFilter.requiredPermission` (route→permission map); `AccessPrincipal.hasPermission` treats `admin.all` as wildcard.

## Findings

### F1 — DEF-0005 (RBAC-001-04, CLOSED/FIXED): UI did not gate actions by permission
The frontend rendered mutation controls and nav regardless of the user's permissions (a read-only AUDITOR saw "New policy", "Open", delete on Masking Policies). Backend was always authoritative (403), so no privilege escalation — but it was a UI/API mismatch. **Fixed 2026-07-17:** added `frontend/src/lib/use-permissions.ts` (`can()` with `admin.all` wildcard, default-deny until `/me` resolves) + `frontend/src/components/can.tsx`, gated all navigation by module read permission, and gated the mutation controls on six core pages. Verified live as `rbac_auditor`: New policy/edit/delete/run controls hidden across Masking Policies, Data Sources, Validation, Virtualization, Masking Scripts, PII Discovery; read content preserved; admin sees everything. See DEF-0005 resolution.

### F2 — DEF-0006 (RBAC-001-04, OPEN, LOW): gating not yet applied to remaining pages
The `can(...)` pattern still needs applying to write controls on the remaining feature pages (synthetic designer, DataScope, business entities, mainframe, mapping designer, auto-provision, self-service, masking studio, unstructured, intelligence store). No security exposure (backend 403s regardless); UI polish only. Tracked for full RBAC-001-04 zero-mismatch closure.

## Exit checklist

- [x] Matrix (01) verified against `RoleDefinition` for every role + edge users.
- [x] Positive/negative API (02/03/07/08/09) executed live with audit confirmation.
- [x] Group refresh (05), union (06), revocation (10) executed live via dual-channel auth.
- [x] RBAC-001-04 UI gating — DEF-0005 fixed; permission layer + nav + 6 core pages gated and re-verified live as `rbac_auditor` + admin regression.
- [ ] DEF-0006 (LOW) — extend the same `can(...)` gate to remaining feature pages for full zero-mismatch.
- [ ] Second reviewer sign-off before `status:done`.

## Cleanup

Test users (`rbac_*`) and the `RBAC Engineers` group remain provisioned for re-runs; `rbac_tester` reactivated, temp lifecycle user deleted, `rbac_grouponly` restored to the group. Transient `rbac-live-*` API tokens on `admin` may be revoked from Access Control.
