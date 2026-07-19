# DEF-0026 - Canceled session-expiry navigation leaves the redirect latch set

| Field | Value |
|---|---|
| Severity | MEDIUM |
| Status | CLOSED |
| Found by story | AUTH-003 - case AUTH-003-05 independent review |
| Component | `frontend/src/lib/api.ts` |
| Verified | 2026-07-19 |

## Problem

`apiFetch` sets the module-level `authRedirectStarted` latch before navigating to login so concurrent 401 responses produce only one redirect. When a dirty-draft `beforeunload` handler canceled that navigation, the current document remained alive and the latch stayed `true`. Every later 401 was then ignored for redirect purposes, so the user would never receive another expiry prompt.

## Resolution

After starting login navigation, the handler now schedules a bounded 1.5-second latch reset. Concurrent 401 responses drain while the latch is held. Successful navigation unloads the module; canceled navigation leaves the page alive long enough for the reset, allowing a later protected request to offer login again.

## Verification

AUTH-003-05 now performs two complete expiry attempts in one browser session:

1. The first 401 raises `beforeunload`; the test cancels it and verifies the dirty draft remains.
2. After the latch-reset interval, a second 401 raises `beforeunload` again and the draft still remains.

The focused case passed, the full seven-case browser contract passed, and the final typecheck passed.

Evidence:

- `docs/testing/evidence/artifacts/AUTH-003-05-RETRY-2026-07-19.txt`
- `docs/testing/evidence/artifacts/AUTH-003-BROWSER-FULL-2026-07-19.txt`
- `docs/testing/evidence/artifacts/AUTH-003-TYPECHECK-2026-07-19.txt`
