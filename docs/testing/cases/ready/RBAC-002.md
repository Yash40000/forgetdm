# RBAC-002 - Cross-Group Object Isolation

**Priority:** P0

**Lane:** All
**Execution status:** **COMPLETE - 9/9 PASS (2026-07-21).** The original core-object exposure (DEF-0007) and the later nested/runtime exposure (DEF-0032) are fixed. Acceptance included 35 live HTTP checks, 90 focused isolation tests, the complete 503-test backend regression, and frontend typechecking. Evidence: `docs/testing/evidence/RBAC-002-EVIDENCE.md`.

## Objective

Prove that a user cannot discover, read, mutate, run, export, approve, or delete another group's governed object merely by guessing its identifier.

## Preconditions

- Groups ALPHA and BETA with separate users and similarly named data sources, policies, blueprints, saved jobs, reservations, files, and evidence.
- One explicitly shared artifact and one ADMIN user.

## Cases

| Case | Type | Action | Expected result and evidence | Result |
|---|---|---|---|---|
| RBAC-002-01 | Listing | As ALPHA, list every object collection. | BETA-private objects and sensitive counts are absent; shared objects appear only as permitted. | PASS |
| RBAC-002-02 | IDOR read | Request each BETA object by numeric ID, UUID, name, and encoded path. | Response is `403` or non-enumerating `404` according to policy; no protected metadata is returned. | PASS |
| RBAC-002-03 | IDOR write | Update/delete BETA artifacts using direct API calls. | Operation is denied, original state is unchanged, and denial is audited. | PASS |
| RBAC-002-04 | Execute | Run, cancel, export, reserve, or promote a BETA job/package as ALPHA. | Every action is denied before a worker or export is created. | PASS |
| RBAC-002-05 | Approval | Attempt to approve BETA governance work and attempt self-approval. | Cross-group and maker self-approval are denied unless an explicit policy grants the reviewer. | PASS |
| RBAC-002-06 | Search/filter | Search by BETA name/tag and use pagination/filter facets. | Search results, totals, facets, and pagination do not disclose BETA-private objects. | PASS |
| RBAC-002-07 | Shared | Access the explicitly shared artifact. | Only the granted operations succeed; sharing one object does not expose related secrets or siblings. | PASS |
| RBAC-002-08 | Admin | Repeat representative operations as ADMIN. | ADMIN access succeeds and is auditable without weakening ordinary-user isolation. | PASS |
| RBAC-002-09 | Membership change | Move a user from ALPHA to BETA and refresh/revoke session per policy. | Access changes predictably; stale cached UI data cannot be mutated after entitlement removal. | PASS |

## Automation and Exit

- Object-by-object negative tests use two tenants/groups and verify denial before side effects.
- Any cross-group data exposure remains an S0/S1 release blocker.
- Closure evidence was independently reconciled against all nine cases; no case is skipped or HARD-PASS.
