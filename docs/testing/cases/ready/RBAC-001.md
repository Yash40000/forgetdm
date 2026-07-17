# RBAC-001 - Role and Group Permission Matrix

**Priority:** P0

**Lane:** All
**Execution status:** NOT RUN

## Objective

Prove that ADMIN, TDM_ARCHITECT, DATA_ENGINEER, TESTER, and AUDITOR permissions are enforced consistently by UI and API, including effective permissions inherited through groups.

## Preconditions

- One active user per built-in role, one multi-role user, one group-derived-role user, and one active user with no role.
- Representative artifacts for data sources, discovery, policies, DataScope, synthetic, mappings, audit, virtualization, mainframe, and administration.

## Cases

| Case | Type | Action | Expected result and evidence |
|---|---|---|---|
| RBAC-001-01 | Matrix | For every built-in role, compare `/api/auth/me` effective permissions with `RoleDefinition`. | Exact documented permission set is returned; duplicates and unknown permissions are absent. |
| RBAC-001-02 | API positive | Call one read and one allowed write/run endpoint for every permission family. | Allowed operations succeed without requiring unrelated permissions. |
| RBAC-001-03 | API negative | Call each forbidden write/run/admin endpoint directly, bypassing UI. | API returns `403`, changes no data, and records `ACCESS_DENIED`. |
| RBAC-001-04 | UI | Navigate to each functional area and inspect actions for each role. | UI hides or disables forbidden actions while keeping allowed work visible; API remains authoritative. |
| RBAC-001-05 | Groups | Assign a role only through a group, refresh identity, then remove the membership. | Permissions appear and disappear according to the documented refresh/session policy. |
| RBAC-001-06 | Union | Assign two roles directly and through groups. | Effective permissions are the union, never an accidental intersection or privilege beyond either role. |
| RBAC-001-07 | Admin | Exercise user/group create, update, deactivate, and delete as ADMIN and non-admin. | ADMIN succeeds via `admin.all`; every non-admin is denied `security.admin`. |
| RBAC-001-08 | Auditor | Attempt create/update/delete/run as AUDITOR across all readable modules. | Read and evidence access succeeds; every mutation is denied. |
| RBAC-001-09 | Default deny | Call an authenticated write API with no explicit route mapping. | Access requires `admin.all`; an ordinary role cannot inherit access by omission. |
| RBAC-001-10 | State change | Deactivate a currently signed-in user. | New authentication is denied and existing access follows the documented revocation policy; no indefinite privilege remains. |

## Automation and Exit

- Generate a parameterized Spring authorization matrix from the route families in `AccessControlFilter` and the roles in `RoleDefinition`.
- Pass requires zero UI/API mismatches and no undocumented privilege path.
