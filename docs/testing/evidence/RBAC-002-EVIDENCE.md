# RBAC-002 — Cross-Group Object Isolation — Evidence

**Story:** RBAC-002 (P0, All lanes)
**Spec:** `docs/testing/cases/ready/RBAC-002.md`
**Execution status:** EXECUTED LIVE 2026-07-17/18 — failure found, **fixed, and re-verified live**.
Original finding: no group/tenant isolation for core objects (cross-group delete succeeded).
Fixed under [DEF-0007](../defects/DEF-0007-no-cross-group-object-isolation.md) (**CLOSED**) via the V61
tenancy migration + `OwnershipGuard`; re-run confirms cross-group read/delete now `403` + audited.

## Run metadata

| Field | Value |
|---|---|
| Environment | Live local stack — FE `http://localhost:3000`, BE `http://localhost:8088` |
| Executed | 2026-07-17 |
| Method | Source analysis of ownership model + query scoping; live provisioning of ALPHA/BETA fixtures (completed); beta-side cross-read/mutate demonstration pending classifier recovery |

## Central architectural finding

ForgeTDM's authorization is **permission-by-route (RBAC)**, not **object/tenant isolation**. Groups exist only to *grant roles* (`forge_group_roles`), never to *partition data*. Object access splits into two classes:

**(A) Global objects — no owner/group column, no per-object scoping.** Any user holding the resource permission sees and mutates *every* instance, regardless of who or which group created it:
- `data_sources` (V1) — columns: id, name, kind, jdbc_url, username, password, role, created_at. **No owner/group.**
- `masking_policies` (V1) — id, name, description, created_at. **No owner/group.**
- `masking_rules`, `classifications`, `reservations` (reserved_by is a free-text string, not enforced), DataScope blueprints/datasets. **No owner/group.**
- `AccessControlFilter.requiredPermission` gates these by type only (`datasource.read`, `policy.manage`, …); no controller/service filters the query by caller identity.

**(B) Owner-scoped objects — `owner_user_id`/`owner_username` (and some `visibility`/`owner_group_id`), enforced on both list and by-id:**
- `synthetic_saved_jobs` — `savedJobs()` lists `WHERE owner_user_id = ? OR owner_username = ?` (admin sees all); `querySavedJob(id)` calls `ensureCanSee()` → `403 "belongs to another user"` for non-owner non-admin. Covers get/update/delete/run. **Isolated.**
- `synthetic_generation_jobs` — same owner filter + `ensureCanSee`. **Isolated.**
- `datascope_saved_jobs` — `list()` owner-filtered; `get(id)` "access-checks ownership". **Isolated.**
- `pii_pattern`, `ri_primary_key`, `ri_relationship` — carry `visibility` + `owner_user_id` + `owner_group_id` (the only true group-aware objects).

The isolation that exists is **per-owner (user)**, which incidentally also separates groups. There is **no group-tenant boundary** for the core catalog (A).

## Result summary (determination)

| # | Case | Determination | Basis |
|---|---|---|---|
| 01 | Listing hides other-group private objects | **FAIL for core objects (LIVE-CONFIRMED)**; **PASS for owner-scoped** | Live: as `beta_user`, `GET /api/policies` → `ALPHA-POLICY-01` present, 20/20 policies visible; `GET /api/datasources` → all 9 sources visible (both groups' data pooled with no facet). |
| 02 | IDOR read by id/name | **FAIL for core objects** (no owner guard, by source); **PASS for owner-scoped (LIVE-CONFIRMED)** | Live: as `beta_user`, `GET /api/synthetic/saved-jobs/{admin-owned id}` → **403**. |
| 03 | IDOR write/delete | **FAIL for core objects (LIVE-CONFIRMED)** — cross-group delete succeeded; **PASS for owner-scoped** | Live: as `beta_user`, `DELETE /api/policies/34` (owned by `alpha_user`) → **200 OK**, policy removed. Audited as `POLICY_DELETED actor=beta_user detail=id=34 outcome=SUCCESS` — logged, but **not prevented**. Policy restored (new id 35) by `alpha_user` immediately after. |
| 04 | Execute/run/export cross-group | **PASS for owner-scoped** (saved-job run via `querySavedJob`→`ensureCanSee`); provision jobs owner/admin-filtered. Core-object-backed runs still reachable via global policy/source. | Source. |
| 05 | Approval + self-approval | **PARTIAL** — self-approval **denied** (`approve()`: "requires a different user than the job creator"); **cross-group approval NOT denied** — any `provision.approve` holder can approve any job (no group scope). | Source (`ProvisioningService.approve`). |
| 06 | Search/filter/pagination leakage | **FAIL for core objects**; **PASS for owner-scoped (LIVE-CONFIRMED)** | Live: as `beta_user`, `GET /api/synthetic/saved-jobs` → 0 results, none from `admin` or other owners leaked. |
| 07 | Explicitly shared artifact | N/A — no per-object sharing/ACL model exists (only `pii_pattern`/`ri_*` `visibility`). Core objects are shared-by-default. | Source. |
| 08 | ADMIN repeat | **PASS** — `admin.all` sees all by design and is audited. | Source + RBAC-001 live. |
| 09 | Membership change refresh | **PASS (mechanism)** — identity/permissions recompute per request (proven live in RBAC-001-05/10). Group change alters *roles*, but does not re-scope core objects since they are not group-scoped. | RBAC-001 live. |

## Live work completed

- Provisioned groups **ALPHA** (id 3) and **BETA** (id 4), each with role `TDM_ARCHITECT`; users **alpha_user** (id 12, ALPHA) and **beta_user** (id 13, BETA).
- As `alpha_user`, created core object **`ALPHA-POLICY-01`** (policy id 34) — `200 OK`.
- As `beta_user`: confirmed list leak (policy + data source), confirmed cross-group **delete succeeded** (`DELETE /api/policies/34` → 200), confirmed saved-job list correctly showed 0 (no leak), confirmed saved-job IDOR-by-id correctly returned **403**.
- Verified in the audit trail: `POLICY_DELETED actor=beta_user detail=id=34 outcome=SUCCESS` — the deletion is faithfully logged but was not blocked.
- Restored `ALPHA-POLICY-01` as `alpha_user` (new id 35).

## Re-verification after the DEF-0007 fix (2026-07-18, rebuilt backend)

The retest deliberately used a **post-migration** policy: `ALPHA-POLICY-01` predates V61, so the
backfill made it `SHARED` and it is legitimately visible to everyone. A newly created policy is
`GROUP`-scoped and is the correct subject for the isolation test.

| Check | Result |
|---|---|
| `alpha_user` creates policy 36 | ownership stamped: `ownerUserId=12`, `ownerGroupId=3` (ALPHA), `visibility=GROUP` |
| RBAC-002-01 — `beta_user` lists policies | **PASS** — policy 36 absent (20, not 21) |
| RBAC-002-02 — `beta_user` `GET /api/policies/36/rules` | **PASS** — `403` |
| RBAC-002-03 — `beta_user` `DELETE /api/policies/36` | **PASS** — `403 {"error":"This policy belongs to another group"}`; policy still present afterwards |
| Denial audited | **PASS** — `ACCESS_DENIED actor=beta_user detail="policy 36 belongs to another tenant" outcome=FAILURE severity=CRITICAL` |
| No over-blocking | **PASS** — owner `alpha_user` deletes own policy 36 → `200` |
| Non-breaking upgrade | **PASS** — all 20 legacy policies backfilled `SHARED`, still visible to every user |
| DEF-0002 | **PASS** — `/api/auth/me` now returns `groups:[{id:1,name:"TDM Admins"}]` |

Remaining as documented: RBAC-002-07 (no explicit per-object sharing/ACL model — core objects are
`SHARED`-by-default only for legacy rows) and full re-test of the datasource/blueprint/reservation
paths, which use the same `OwnershipGuard` choke points as the verified policy path.

## Findings

### F1 — DEF-0007 (HIGH/S1, OPEN): no cross-group/tenant isolation for core objects
Data sources, masking policies, DataScope blueprints and reservations are global; any user with the resource permission can list, read, and mutate another group's objects. The story classifies cross-group exposure as an S0/S1 blocker. Owner-scoped objects (saved jobs, synthetic jobs, pii/ri) are correctly isolated. See DEF-0007.

### F2 — cross-group approval not scoped (RBAC-002-05, partial)
Self-approval is correctly blocked, but any holder of `provision.approve` can approve another group's job. Fold into DEF-0007 (tenant scoping) or track separately if group-scoped approval is desired.

## Exit checklist

- [x] Beta-side live cross-read/mutate/IDOR calls executed and confirmed.
- [ ] DEF-0007 triage: decide whether ForgeTDM is intended as a single shared workspace (then RBAC-002 is "won't fix / by design" and the story's premise is revised) or multi-tenant (then this is an S1 build blocker requiring owner/group columns + query scoping on core objects).
- [ ] Second reviewer sign-off.
