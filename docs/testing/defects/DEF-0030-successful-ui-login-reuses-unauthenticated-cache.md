# DEF-0030 - Successful UI login could bounce back to sign-in

| Field | Value |
|---|---|
| Severity | HIGH |
| Status | CLOSED |
| Found by story | AUTH-001-01 |
| Verified | 2026-07-19 |

## Problem

Opening a protected route before authentication cached `/api/auth/me` as `authenticated: false`. After the login API returned 200 and issued a valid session cookie, the login page navigated back to the requested route without updating that cache. Because the shared query cache had a 20-second stale window, the protected shell reused the old unauthenticated identity and redirected the user back to login.

This was visible only in the complete browser flow; API-only login tests passed because the server session was valid.

## Resolution

The login page now stores the authenticated principal returned by `/api/auth/login` under the shared `keys.auth.me` query key before returning to the requested route. The protected shell therefore receives the new identity atomically and cannot bounce on stale pre-login state.

## Verification

`frontend/e2e/auth-001/login-session.spec.ts` was rerun against an optimized Next.js build with Microsoft Edge. It proved the protected-route redirect, HTTP 200 login, return to `/datascope`, rendered DataScope workspace, session-cookie attributes, and absence of credential/session material from URL, response body, browser storage, and console.

Result: 1 passed, 0 failed, 0 skipped in `docs/testing/evidence/artifacts/AUTH-001-BROWSER-2026-07-19.junit.xml`.
