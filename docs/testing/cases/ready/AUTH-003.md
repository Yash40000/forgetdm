# AUTH-003 - Expired Session Redirect and Draft Recovery

**Priority:** P0

**Lane:** UI
**Execution status:** COMPLETE - 8/8 acceptance cases directly proven and independently accepted on 2026-07-19.

- The initial seven-case Edge run was green, but trace review showed that case 08 allowed a transient protected-content render and case 02 reset its notification counter during navigation.
- The direct expired-session API case returned a structured JSON 401 with no HTML or redirect.
- Fixes and stronger assertions passed a focused 2/2 run, the full 7/7 browser suite, the frontend type gate, and the 284-test Maven regression. Independent trace review accepted all eight cases.

Evidence:

- docs/testing/evidence/AUTH-003-EVIDENCE.md
- docs/testing/evidence/AUTH-003-BROWSER-2026-07-19.md
- docs/testing/evidence/artifacts/AUTH-003-BROWSER-FULL-2026-07-19.txt
- docs/testing/evidence/artifacts/AUTH-003-DIRECT-401-2026-07-19.txt
- docs/testing/evidence/artifacts/AUTH-003-TYPECHECK-2026-07-19.txt

## Objective

Prove that one expired session causes one safe login redirect, preserves an allowed same-origin return path, and does not silently destroy an unsaved design.

## Preconditions

- Active user, short-lived or administratively revocable session, and a modifiable draft in DataScope or Synthetic Data.
- Browser automation capable of counting navigation and network events.

## Cases

| Case | Type | Action | Expected result and evidence |
|---|---|---|---|
| AUTH-003-01 | Expiry | Open a protected page, expire the session, then trigger one API query. | Browser navigates once to /login?next=<same-origin-path-and-query>. |
| AUTH-003-02 | Concurrency | Expire the session while five background queries return 401. | The global redirect guard causes one navigation, no redirect loop, and no toast storm. |
| AUTH-003-03 | Return | Sign in from the expiry page. | User returns to the original same-origin route and query string. |
| AUTH-003-04 | Open redirect | Supply absolute, protocol-relative, encoded external, backslash, and script-like next values. | Login ignores unsafe targets and lands on the application default route. |
| AUTH-003-05 | Draft | Modify a draft without saving, expire the session, and choose to remain on the page. | The browser warns before leaving and the dirty draft remains unchanged; it is never silently replaced by a background refetch. |
| AUTH-003-06 | Auth page | Cause a 401 from the login flow itself and refresh /login. | No recursive redirect occurs; the page shows one actionable authentication error. |
| AUTH-003-07 | Direct API | Call the same expired endpoint outside a browser. | API returns a structured 401 and never emits HTML or a server-side redirect. |
| AUTH-003-08 | History | Use Back after reauthentication and logout. | The browser does not expose authenticated protected content from stale cache. |

## Automation and Exit

- Edge automation covers the redirect guard, same-origin return, unsafe next values, dirty-draft prompt, login error, and browser-history behavior.
- Direct API evidence covers the structured no-redirect 401 contract.
- Pass requires a single redirect under concurrent failures, explicit draft protection, retained artifacts, a green type gate, and independent review.
