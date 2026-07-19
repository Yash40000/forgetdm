# DEF-0002 — `/api/auth/me` omits group membership

| Field | Value |
|---|---|
| Severity | LOW |
| Status | **CLOSED** (fixed alongside DEF-0007; verified live 2026-07-18) |
| Found by story | AUTH-001 — case AUTH-001-02 |
| Component | `io.forgetdm.security.AuthController` / `AccessControlService.principal` / `AccessPrincipal` |
| Found on build | `<git rev-parse HEAD at discovery>` |
| Reported | `<ISO ts>` |

## Summary

AUTH-001-02 requires that `GET /api/auth/me` return "the same user, **roles, groups**, and effective permissions." The endpoint currently returns the `AccessPrincipal` (`userId`, `username`, `displayName`, `roles`, `permissions`). Roles already include group-granted roles (the union of directly-assigned and group roles) and permissions are the effective set, but the **explicit list of groups** the user belongs to is not present in the response.

## Steps to reproduce

1. Assign a user to a group that carries a role.
2. `POST /api/auth/login`, then `GET /api/auth/me`.
3. Inspect `user`.

**Expected:** `user` includes a `groups` array (e.g. `[{id, name}]`).
**Actual:** `user` includes `roles` and `permissions` but no `groups`.

**Live-confirmed 2026-07-17** against the running app (FE :3000 / BE :8088): `GET /api/auth/me` →
`{"authenticated":true,"user":{"userId":1,"username":"admin","displayName":"Platform Admin","roles":["ADMIN"],"permissions":["admin.all"]}}` — no `groups` field present.

## Impact

Low. The authorization surface is functionally complete — group-derived roles and their permissions are already reflected in `roles`/`permissions`, so access decisions are unaffected. The gap is that a client cannot see *which groups* granted the access from `/me` alone (it must call `/api/security/users` as an admin).

## Recommended fix

Add a `groups` field to `AccessPrincipal` and populate it in `AccessControlService.principal(long userId)` using the existing `groupsForUser(userId)` query, then include it in the login and `/me` responses. `AccessPrincipal` is constructed in a single place, so the change is localized; adding a record field is backward-compatible for existing readers (`username()`, `hasPermission()`, etc.).

## Decision needed

Fix to satisfy AUTH-001-02 verbatim, or accept as a documented deviation (roles + effective permissions deemed sufficient). Until decided, AUTH-001-02 is marked PARTIAL in the evidence and this defect remains OPEN.

## Resolution (2026-07-18)

Fixed as a by-product of the DEF-0007 tenancy work — group membership became a required part of the
principal, so it is now both enforced and exposed:

- `AccessPrincipal` gained a `groups` component (`List<AccessControlService.GroupLite>`) plus a
  `groupIds()` helper used for tenant scoping.
- `AccessControlService.principal(userId)` now populates it via the existing `groupsForUser(userId)`
  query.
- `AuthController.me()` serialises the principal directly, so `GET /api/auth/me` now returns
  `user.groups: [{id, name}]` with no controller change — satisfying AUTH-001-02 verbatim.

A 5-arg convenience constructor was kept on `AccessPrincipal` so existing callers/tests compile unchanged.

**Verified live 2026-07-18** (rebuilt backend): `GET /api/auth/me` as `admin` returns
`user.groups: [{"id":1,"name":"TDM Admins"}]`. AUTH-001-02 now satisfied verbatim.
