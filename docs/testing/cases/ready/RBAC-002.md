# RBAC-002 - Cross-Group Object Isolation

**Priority:** P0

**Lane:** All
**Execution status:** EXECUTED LIVE 2026-07-17/18 — failure found and **fixed**. Core objects originally had no group/tenant isolation (cross-group delete succeeded) → DEF-0007 (HIGH/S1); product decision was **multi-tenant**, implemented via the V61 tenancy migration + `OwnershipGuard`. **Re-verified live: cross-group list/read/delete now 403 + audited, owner unaffected, legacy rows non-breaking.** DEF-0007 CLOSED. Evidence: `docs/testing/evidence/RBAC-002-EVIDENCE.md`.

## Objective

Prove that a user cannot discover, read, mutate, run, export, approve, or delete another group's governed object merely by guessing its identifier.

## Preconditions

- Groups ALPHA and BETA with separate users and similarly named data sources, policies, blueprints, saved jobs, reservations, files, and evidence.
- One explicitly shared artifact and one ADMIN user.

## Cases

| Case | Type | Action | Expected result and evidence |
|---|---|---|---|
| RBAC-002-01 | Listing | As ALPHA, list every object collection. | BETA-private objects and sensitive counts are absent; shared objects appear only as permitted. |
| RBAC-002-02 | IDOR read | Request each BETA object by numeric ID, UUID, name, and encoded path. | Response is `403` or non-enumerating `404` according to policy; no metadata leaks in message or timing. |
| RBAC-002-03 | IDOR write | Update/delete BETA artifacts using direct API calls. | Operation is denied, original checksum is unchanged, and denial is audited. |
| RBAC-002-04 | Execute | Run, cancel, export, reserve, or promote a BETA job/package as ALPHA. | Every action is denied before a worker or export is created. |
| RBAC-002-05 | Approval | Attempt to approve BETA governance work and attempt self-approval. | Cross-group and maker self-approval are denied unless an explicit policy grants the reviewer. |
| RBAC-002-06 | Search/filter | Search by BETA name/tag and use pagination/filter facets. | Search results, totals, facets, and pagination do not disclose BETA-private objects. |
| RBAC-002-07 | Shared | Access the explicitly shared artifact. | Only the granted operations succeed; sharing one object does not expose related secrets or siblings. |
| RBAC-002-08 | Admin | Repeat representative operations as ADMIN. | ADMIN access succeeds and is auditable without weakening ordinary-user isolation. |
| RBAC-002-09 | Membership change | Move a user from ALPHA to BETA and refresh/revoke session per policy. | Access changes predictably; stale cached UI data cannot be mutated after entitlement removal. |

## Automation and Exit

- Add an object-by-object negative API suite using two tenants/groups and immutable before/after checksums.
- Any cross-group data exposure is an S0/S1 release blocker.
