# DEF-0007 — No cross-group/tenant isolation for core objects

| Field | Value |
|---|---|
| Severity | HIGH (S1 per RBAC-002 exit criteria: "any cross-group data exposure is an S0/S1 release blocker") |
| Status | **CLOSED** (fixed and verified live 2026-07-18) |
| Decision | Multi-tenant isolation (chosen 2026-07-18) |
| Found by story | RBAC-002 — cases 01, 02, 03, 06 (and 05 partial) |
| Component | Data model (`data_sources`, `masking_policies`, DataScope blueprints, `reservations`) + their services/controllers; `AccessControlFilter` |
| Found on | Live stack 2026-07-17 (FE :3000 / BE :8088); source analysis + partial live |

## Summary

ForgeTDM authorizes by **permission-per-route (RBAC)**, not by object ownership or tenant. Groups grant roles only; they do not partition data. As a result, the core governed objects have **no owner/group column and no per-caller query scoping**, so any user holding the resource permission can list, read, and mutate **every** instance regardless of which user/group created it:

- `data_sources` (V1) — no owner/group column.
- `masking_policies` (V1) — no owner/group column.
- DataScope blueprints/datasets, `masking_rules`, `classifications`, `reservations` (`reserved_by` is a non-enforced string).

A minority of object types *are* correctly isolated (per-owner, enforced on list **and** by-id): `synthetic_saved_jobs` / `synthetic_generation_jobs` (`ensureCanSee` → 403), `datascope_saved_jobs`, and `pii_pattern` / `ri_*` (which additionally carry `visibility` + `owner_group_id`). This shows the pattern exists in the codebase but was never applied to the core catalog.

## Impact

A user in group BETA with `TDM_ARCHITECT` (or any role carrying `policy.manage` / `datasource.manage` / `datascope.manage`) can:
- Enumerate every other group's data sources (incl. JDBC URLs / usernames), masking policies, and blueprints.
- Delete or modify another group's policy/source/blueprint by direct API call (only the type permission is checked).

RBAC-002's exit criteria classify cross-group data exposure as an **S0/S1 release blocker**. If ForgeTDM is intended to be multi-tenant, this is release-blocking.

## Steps to reproduce (executed live 2026-07-17/18)

1. Create groups ALPHA/BETA, users `alpha_user`/`beta_user` (each `TDM_ARCHITECT` via group). ✅
2. As `alpha_user`, `POST /api/policies {name:"ALPHA-POLICY-01"}` → `200`, id 34. ✅
3. As `beta_user`: `GET /api/policies` → **ALPHA-POLICY-01 present** among all 20 policies (list leak, confirmed); `GET /api/datasources` → **all 9 sources visible**, no facet (confirmed); `DELETE /api/policies/34` → **200 OK** — the policy was deleted (cross-group mutate, confirmed).
4. Audit trail confirms the event: `POLICY_DELETED actor=beta_user detail=id=34 outcome=SUCCESS` — correctly logged, but **not prevented**.
5. Control check (owner-scoped objects correctly isolated): as `beta_user`, `GET /api/synthetic/saved-jobs` → 0 results (no leak of `admin`-owned jobs); `GET /api/synthetic/saved-jobs/{admin-owned-id}` → **403** (IDOR blocked).
6. `ALPHA-POLICY-01` restored by `alpha_user` (new id 35) to leave the system clean.

## Recommended fix (raised for tracking — decision required first)

**Decision:** Is ForgeTDM a single shared workspace or multi-tenant?
- **Single workspace (by design):** then RBAC-002 core-object cases are "won't fix / by design"; revise the story premise and document that data-source/policy/blueprint visibility is global-with-RBAC. Keep the maker-checker and owner-scoped guarantees.
- **Multi-tenant (isolation required):** add `owner_group_id` (+ `visibility`) to `data_sources`, `masking_policies`, datasets, and reservations; filter every list by the caller's group set (admin bypass); add an `ensureCanSee`-style guard to every get/update/delete/run by id (reuse the pattern already in `SyntheticGenService`); and scope approval to the artifact's group.

## Resolution (2026-07-18) — multi-tenant isolation implemented

Product decision: **multi-tenant**. The ownership pattern already proven in `SyntheticGenService`
was generalised and applied to the core catalog.

**Identity**
- `AccessPrincipal` gained `groups` + `groupIds()`; `AccessControlService.principal()` populates it.
  (This also closes [DEF-0002](DEF-0002-me-omits-group-membership.md) — `/api/auth/me` now returns groups.)
- New `AccessControlService.groupIdsForUsername()` resolves a creator's tenant for approval scoping.

**Schema** — `V61__core_object_tenancy.sql` adds `owner_user_id` / `owner_username` /
`owner_group_id` / `visibility` (+ indexes) to `data_sources`, `masking_policies`,
`dataset_definitions`, `reservations`. **Backfill: every pre-existing row becomes `SHARED`**, so the
upgrade is non-breaking — nothing vanishes from anyone's UI. New objects default to `GROUP`.

**Guard** — new `io.forgetdm.security.OwnershipGuard`:
- `canSee(owner, group, visibility)` / `assertCanSee(...)` → `403` + audited `ACCESS_DENIED`.
- `admin.all` bypasses; `PRIVATE`/`GROUP`/`SHARED` semantics.
- **System context is permissive**: with no `AccessContext` principal (async provisioning workers,
  `@Scheduled` sweeps, `@PostConstruct`) the guard allows the call. `AccessControlFilter` already
  rejects unauthenticated API traffic with 401, so failing closed here would only break background
  jobs without adding security.

**Service scoping**
- `PolicyController` — list filtered; create stamps owner; `requireVisiblePolicy()` guards delete,
  rules read, add-rule, update-rule and delete-rule (rules inherit their policy's tenancy).
- `DataSourceService` — list filtered; **`get(id)` guarded**, which transitively closes schemas,
  tables, columns, FKs, diagnostics, test-connection and update/delete (a data source carries the
  JDBC URL and credentials, so this was the highest-value choke point).
- `DataSetService` — list filtered; `get(id)` guarded (update/delete route through it).
- `ReservationService` — list filtered; `release(id)` guarded (releasing another group's reservation
  would free their test data underneath them).
- `ProvisioningService.approve()` — new `requireSameTenantAsCreator()` bounds the reviewer to the
  creator's group, closing the RBAC-002-05 partial. Unknown/legacy free-text creators fall back to
  maker-checker only.

**Compatibility** — a 5-arg `AccessPrincipal` convenience constructor keeps existing callers/tests
compiling; `DataSetDirectiveTest` updated for the new constructor arity.

## Verification (live, 2026-07-18, after rebuild)

Re-ran RBAC-002 against the rebuilt backend. Note the test had to use a **post-migration** policy:
`ALPHA-POLICY-01` predates V61 so it backfilled to `SHARED` and is legitimately visible to all.

| Check | Result |
|---|---|
| `alpha_user` creates policy → ownership stamped | **PASS** — id 36, `ownerUserId=12`, `ownerUsername=alpha_user`, `ownerGroupId=3` (ALPHA), `visibility=GROUP` |
| `beta_user` lists policies | **PASS** — policy 36 absent; count 20 (not 21) |
| `beta_user` `DELETE /api/policies/36` | **PASS** — **403** `{"error":"This policy belongs to another group"}` |
| `beta_user` `GET /api/policies/36/rules` (IDOR read) | **PASS** — **403** |
| Policy survived the delete attempt | **PASS** — still present for `alpha_user` (count 21) |
| Denial audited | **PASS** — `ACCESS_DENIED actor=beta_user detail="policy 36 belongs to another tenant" outcome=FAILURE severity=CRITICAL` |
| Owner can still delete their own policy | **PASS** — 200 (guard does not over-block) |
| Legacy rows non-breaking | **PASS** — all 20 pre-existing policies backfilled `SHARED` and remain visible to every user |
| `/api/auth/me` returns groups (DEF-0002) | **PASS** — `groups:[{id:1,name:"TDM Admins"}]` |

Audit trail shows the before/after in one view: the pre-fix exploit
(`POLICY_DELETED actor=beta_user id=34 outcome=SUCCESS`) and the post-fix block
(`ACCESS_DENIED actor=beta_user … outcome=FAILURE`).

Probe policy 36 deleted after the run; system left clean.

## Related

- Owner-scoped isolation that already works correctly: `SyntheticGenService.ensureCanSee`, `DataScopeJobService.list/get`.
- Cross-group **approval** is also unscoped (any `provision.approve` holder approves any job); self-approval is correctly blocked. Fold into the multi-tenant fix if chosen.
