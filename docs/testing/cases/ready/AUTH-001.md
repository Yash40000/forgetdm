# AUTH-001 - Valid Login, Logout, and Session Identity

**Priority:** P0

**Lane:** All
**Execution status:** READY FOR INDEPENDENT RE-REVIEW - all 10 cases passed on required physical HTTP and HTTPS lanes.

- The strengthened Edge/Playwright login creates a unique disposable user, proves protected-route return navigation, scans actual username/password/session-token values, and retains sanitized network metadata plus settled screenshots for both browser transports.
- The comprehensive disposable runner now executes every assertion in cases 02-10 against separate physical HTTP and HTTPS Spring servers, including exact identity parity, deliberate cookie replay, nonempty bounded logs, complete token canaries, and lane-correlated audit events.
- DEF-0030 fixed the stale unauthenticated client cache that the browser acceptance test exposed after an otherwise successful login.
- Production CA-chain, ingress, rotation, and HSTS checks remain an explicit deployment gate; application behavior under physical TLS is proven.
- Story status remains review-pending until an independent reviewer accepts every claim against the retained artifacts and implementation.
Evidence: `docs/testing/evidence/AUTH-001-EVIDENCE.md`

## Objective

Prove that `/api/auth/login`, `/api/auth/me`, and `/api/auth/logout` establish, identify, and terminate a session without exposing credentials or leaving a reusable session token.

## Preconditions

- One active user with a known password and one inactive user.
- HTTPS production-like lane plus local HTTP lane.
- Browser developer tools or automated cookie inspection and access to sanitized audit results.

## Cases

| Case | Type | Action | Expected result and evidence |
|---|---|---|---|
| AUTH-001-01 | Positive | Sign in through the UI with the active user. | The requested application route opens and no credential appears in URL, storage, console, or response body. Capture UI and network evidence. |
| AUTH-001-02 | API | `POST /api/auth/login` with valid credentials, then `GET /api/auth/me`. | Login returns the authenticated principal and expiry; `me` returns the same user, roles, groups, and effective permissions. |
| AUTH-001-03 | Cookie | Inspect the session cookie in HTTP and HTTPS lanes. | Cookie is HttpOnly, Path `/`, SameSite=Lax, has a positive finite lifetime, and is Secure in the HTTPS lane. Missing Secure is a release-blocking defect. |
| AUTH-001-04 | Negative | Submit a wrong password and an unknown username. | Both fail with the same sanitized response, create no session, and do not disclose which account exists. |
| AUTH-001-05 | Negative | Attempt login as the inactive user. | Authentication is denied; no cookie or bearer token is issued. |
| AUTH-001-06 | Termination | Logout, then replay the old cookie against a protected endpoint. | Logout clears the browser cookie and invalidates the server-side session; replay receives `401`. |
| AUTH-001-07 | Identity | Open two concurrent sessions for the same active user and log out one. | Behavior matches the documented session policy; one logout must not silently authenticate as another user. Record whether sessions are independently revoked. |
| AUTH-001-08 | Expiry | Advance beyond configured session TTL or expire the session fixture, then call a protected endpoint. | The request receives `401`; no server exception or stale identity is returned. |
| AUTH-001-09 | Security | Inspect persisted session material and application logs. | Only token hashes are stored; passwords, clear tokens, cookies, and secrets are absent from logs and audit details. |
| AUTH-001-10 | Audit | Query authentication audit evidence after success, failure, and logout. | Actor/outcome/timestamp/IP or correlation data are present where policy requires them, with no sensitive payload. |

## Automation and Exit

- Add Spring integration coverage for login/me/logout/replay and Playwright coverage for the UI path.
- Run the authentication and access-control regression suites.
- Pass requires all ten cases in both required deployment lanes and reviewed evidence on the GitHub story.
