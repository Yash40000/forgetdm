# DEF-0004 - `next` open-redirect backslash bypass

| Field | Value |
|---|---|
| Severity | LOW |
| Status | CLOSED |
| Found by story | AUTH-003 - case AUTH-003-04 |
| Component | `frontend/src/app/login/page.tsx` (`safeNextPath`) |
| Verified | 2026-07-19 |

## Problem

The login return-path guard rejected absolute and protocol-relative targets but accepted a path such as `/\\evil.example`. Browsers can normalize backslashes to slashes, allowing that value to become an external protocol-relative target.

## Resolution

`safeNextPath` now rejects a slash or backslash in the second character and resolves every candidate against the current origin. A target is accepted only when it remains same-origin.

## Verification

AUTH-003-04 executed absolute, protocol-relative, encoded external, backslash, and script-like vectors in Microsoft Edge. Every unsafe value landed on the application default route. The full seven-case browser contract and final frontend typecheck both passed.

Evidence:

- `docs/testing/evidence/artifacts/AUTH-003-BROWSER-FULL-2026-07-19.txt`
- `frontend/test-results/auth-003/results.json`
- `docs/testing/evidence/artifacts/AUTH-003-TYPECHECK-2026-07-19.txt`
