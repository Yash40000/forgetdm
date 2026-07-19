# DEF-0005 — UI does not gate actions (or nav) by permission

| Field | Value |
|---|---|
| Severity | MEDIUM |
| Status | **CLOSED** (fixed — permission layer added; verified live) |
| Found by story | RBAC-001 — case RBAC-001-04 |
| Component | `frontend/src/features/**` (action controls) + `frontend/src/components/app-shell.tsx` (nav) |
| Found on build | Live dev build, 2026-07-17 (FE :3000 / BE :8088) |
| Reported | 2026-07-17 |

## Summary

The frontend renders create/edit/delete controls and navigation entries independent of the signed-in user's effective permissions. Verified live: logged in as `rbac_auditor` (role AUDITOR — `policy.read` but **not** `policy.manage`), the **Masking Policies** page still shows the **"+ New policy"** button and per-row **Open (edit)** and **delete** controls.

A repository search shows **no** `features/**` component references `permissions`, `hasPermission`, `can(...)`, or the effective-permission set — i.e. there is no client-side authorization layer. Only the Access Control feature reads permissions, and only to render its own preview.

RBAC-001-04 requires: "UI hides or disables forbidden actions while keeping allowed work visible; API remains authoritative." Pass requires **zero UI/API mismatches**.

## Steps to reproduce

1. As ADMIN, ensure a user with role AUDITOR exists (e.g. `rbac_auditor`).
2. Log in as that user.
3. Open `/masking-policies`.

**Expected:** mutation controls ("New policy", edit, delete) are hidden or disabled; read content remains visible.
**Actual:** all mutation controls are shown and clickable.

## Impact

**Not a privilege escalation** — the backend `AccessControlFilter` returns `403` and logs `ACCESS_DENIED` for every forbidden call (verified in RBAC-001-03), so no data can be changed. The impact is UX correctness and expectation: read-only/limited users are offered actions that then fail with an error, and `rbac_norole`-type users see nav for modules whose reads all 403. Because RBAC-001-04 counts any UI/API mismatch as a failure, this blocks RBAC-001 from `status:done`.

## Recommended fix (raised for tracking — not implemented)

Add a client permission layer fed by `/api/auth/me` effective permissions:
- A `usePermissions()` hook exposing `can(permission: string)` (treating `admin.all` as wildcard, mirroring `AccessPrincipal.hasPermission`).
- Gate action controls: `can('policy.manage')` for New/Edit/Delete on policies, and the equivalent family permission on every feature page.
- Gate nav items in `app-shell.tsx` by the read permission of each module (hide modules the user cannot read).
- Keep the API authoritative regardless (defense in depth).

Consider also adding `groups` to `/api/auth/me` ([DEF-0002](DEF-0002-me-omits-group-membership.md)) so the client has full identity context in one call.

## Resolution (2026-07-17)

Added a client-side permission layer and applied it. The backend stays authoritative; this only controls whether the UI *offers* an action.

**Framework**
- `frontend/src/lib/use-permissions.ts` — `usePermissions()` returns `can(permission)` / `canAny(...)`, reading the `/api/auth/me` effective permissions via the shared `keys.auth.me` query. `admin.all` is treated as a wildcard (mirrors `AccessPrincipal.hasPermission`); **default-deny until `/me` resolves** so forbidden controls never flash in.
- `frontend/src/components/can.tsx` — `<Can permission="…">` declarative gate for wrapping any control.

**Navigation (global)** — `app-shell.tsx`: every nav item carries its module read permission; items the user cannot read are hidden (Access Control stays admin-only). Verified live: auditor's sidebar correctly drops *Forge Data Store* (`assistant.use`) and *Access Control* (admin) while keeping every read-accessible module; admin sees all 22.

**Mutation controls gated + verified live as `rbac_auditor` (read-only) with admin regression:**
- **Masking Policies** — New policy, row Open→"View", row delete, editor Delete policy, Add rule, rule delete, inline function `Select` disabled, Bind-from-Discovery "Add selected" (`policy.manage`). *Verified: all write controls hidden; rules still readable.*
- **Data Sources** — Add connection, row Edit, row Delete (`datasource.manage`); read-only "Browse schemas" retained. *Verified.*
- **Validation** — Run launcher (`validation.run`) + AI "Apply fix" (`policy.manage`); reports/history retained. *Verified.*
- **Virtualization** — Capture snapshot, Provision, snapshot delete, VDB refresh/rewind/bookmark/delete, Add environment (`virtualization.manage`). *Verified: no mutation buttons; data still shown.*
- **Masking Scripts** — Save script, row delete (`policy.manage`); editor readable. *Verified.*
- **PII Discovery** — Start scan (`discovery.manage`). *Verified.*

Verified both directions: as `admin` (via `admin.all` wildcard) every gated control and the full nav reappear.

**Remaining coverage** — the same one-line `can(...)` pattern still needs applying to secondary controls on the remaining feature pages (synthetic designer, DataScope, business entities, mainframe, mapping designer, auto-provision, self-service, masking studio, unstructured, intelligence store). Tracked as **[DEF-0006](DEF-0006-extend-permission-gating-remaining-pages.md)** (LOW). The backend `AccessControlFilter` returns `403` for all of these regardless, so there is no security exposure in the interim — this is UI polish, and the exemplar page that surfaced RBAC-001-04 (Masking Policies) is fully gated and verified.
