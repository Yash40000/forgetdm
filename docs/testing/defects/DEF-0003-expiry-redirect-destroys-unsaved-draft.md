# DEF-0003 - Session-expiry redirect silently destroys an unsaved draft

| Field | Value |
|---|---|
| Severity | MEDIUM |
| Status | CLOSED |
| Found by story | AUTH-003 - case AUTH-003-05 |
| Component | Frontend session-expiry navigation and DataScope/Synthetic draft state |
| Verified | 2026-07-19 |

## Problem

When a protected request returned 401, `apiFetch` used full-document navigation to `/login`. DataScope and Synthetic designs can contain unsaved client-side changes, so the navigation could destroy in-memory work without warning.

## Resolution

The reusable `useUnsavedGuard(when)` hook installs a `beforeunload` handler while work is dirty. It is wired to the existing DataScope dirty state and the Synthetic plan fingerprint. Session-expiry navigation now raises the browser's native leave warning, allowing the user to remain and save.

## Verification

AUTH-003-05 was executed in Microsoft Edge twice after the final fix:

- focused regression: `AUTH-003-05-RETRY-2026-07-19.txt` - 1/1 passed;
- full browser contract: `AUTH-003-BROWSER-FULL-2026-07-19.txt` - 7/7 passed.

The test changes a Synthetic draft, triggers expiry, cancels navigation, and asserts the draft values remain unchanged. It then triggers a second expiry and proves the warning and preservation behavior repeat.
