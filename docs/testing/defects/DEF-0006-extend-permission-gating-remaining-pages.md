# DEF-0006 — Extend permission gating to the remaining feature pages

| Field | Value |
|---|---|
| Severity | LOW |
| Status | **OPEN** |
| Found by story | RBAC-001 — case RBAC-001-04 (follow-up to [DEF-0005](DEF-0005-ui-does-not-gate-actions-by-permission.md)) |
| Component | `frontend/src/features/**` (remaining pages) |
| Reported | 2026-07-17 |

## Summary

[DEF-0005](DEF-0005-ui-does-not-gate-actions-by-permission.md) added the client permission layer (`usePermissions`/`<Can>`), gated all navigation, and gated the mutation controls on the core pages (Masking Policies, Data Sources, Validation, Virtualization, Masking Scripts, PII Discovery — all verified live). The same one-line `can(...)` pattern still needs to be applied to the write/run controls on the remaining pages so RBAC-001-04 reaches **zero UI/API mismatches** everywhere:

- Synthetic Data (designer: generate/run/save, saved-jobs)
- DataScope (create blueprint, run, saved jobs)
- Business Entities (create/model/govern/deliver actions)
- Mainframe (Copybook Studio parse/load/mask, Mainframe Files, File Generator)
- Mapping Designer + Auto Provision
- Self-Service (request is `provision.run` — testers legitimately have it; gate the manage/approve controls)
- Masking Studio, Unstructured Masking, Forge Data Store

## Impact

**Low, no security exposure.** The backend `AccessControlFilter` returns `403` and logs `ACCESS_DENIED` for every forbidden call regardless of UI (verified in RBAC-001-03). This is UI correctness/polish only — a read-only user may still be *offered* an action on these pages that then fails with an error.

## Recommended fix

For each page: `const { can } = usePermissions();` then wrap the create/run/delete controls in `can('<family>.manage' | '<family>.run')` (or the declarative `<Can permission="…">`). Permission families per route are defined in `io.forgetdm.security.AccessControlFilter.requiredPermission`.
