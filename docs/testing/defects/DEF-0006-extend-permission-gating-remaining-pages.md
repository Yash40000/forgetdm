# DEF-0006 - Extend Permission Gating to Remaining Feature Pages

| Field | Value |
|---|---|
| Severity | LOW |
| Status | **CLOSED** |
| Found by story | RBAC-001 - case RBAC-001-04 (follow-up to DEF-0005) |
| Component | `frontend/src/features/**`, `AccessControlFilter` permission exceptions |
| Reported | 2026-07-17 |
| Fixed and verified | 2026-07-19 |
| Fix commit | `6614e22` |

## Original deviation

The frontend permission layer initially covered navigation and six core pages, but secondary feature areas could still offer an action that the backend would reject. RBAC-001-04 requires zero UI/API mismatch, so the remaining pages blocked story completion even though the API already prevented privilege escalation.

## Resolution

Permission-aware rendering and handler guards now cover:

- Synthetic design, profiling, direct run, saved-job run/manage/approve/export, job cancel, partition cancel, and partition retry.
- DataScope create/map/manage, relationship, version, preview, provision, approval, cancellation, and saved-job controls.
- Business Entity model, identity, freshness, flow, Micro-DB, package, governance, and delivery operations.
- Mainframe copybook, file, connection, transfer, masking, and generation operations.
- Mapping Designer, mapping run, and Auto Provision operations.
- Self-Service request, approval, and catalog administration as separate permission families.
- Masking policies/scripts, PII patterns, unstructured jobs/profiles, virtualization, validation, and Forge Data Store stewardship.
- Automation using `integration.read`/`integration.manage` instead of role-name checks.

The closure pass also aligned action-specific backend contracts:

- Audit re-anchor requires `admin.all` in filter and service.
- Synthetic partition cancel requires `synthetic.cancel`; retry requires `synthetic.run`.
- Mapping run, retry, workflow run, load, and multi-load require `mapping.run`.

## Verification

- Microsoft Edge Playwright: 21/21 PASS, 0 skipped/flaky/unexpected.
- Maven regression: 370 tests, 0 failures/errors, 1 intentional skip.
- Frontend typecheck, lint, and production build: PASS.
- Compiled controller inventory and exact role/route matrices: PASS.
- Independent read-only acceptance review: PASS, no concrete UI/API mismatch found.

Evidence: `docs/testing/evidence/RBAC-001-EVIDENCE.md` and the `RBAC-001-PLAYWRIGHT-FINAL-2026-07-19.*` artifacts.

## Residual note

Personal API-token create/revoke is intentionally authenticated owner-scoped identity self-service, not feature administration. The exception is explicit in the route inventory test and service ownership checks.
