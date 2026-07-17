# AUTH-003 - Expired Session Redirect and Draft Recovery

**Priority:** P0

**Lane:** UI
**Execution status:** NOT RUN

## Objective

Prove that one expired session causes one safe login redirect, preserves an allowed same-origin return path, and does not silently destroy an unsaved design.

## Preconditions

- Active user, short-lived or administratively revocable session, and a modifiable draft in DataScope or Synthetic Data.
- Browser automation capable of counting navigation and network events.

## Cases

| Case | Type | Action | Expected result and evidence |
|---|---|---|---|
| AUTH-003-01 | Expiry | Open a protected page, expire the session, then trigger one API query. | Browser navigates once to `/login?next=<same-origin-path-and-query>`. |
| AUTH-003-02 | Concurrency | Expire the session while five background queries return `401`. | The global redirect guard causes one navigation, no redirect loop, and no toast storm. |
| AUTH-003-03 | Return | Sign in from the expiry page. | User returns to the original same-origin route and query string. |
| AUTH-003-04 | Open redirect | Supply absolute, protocol-relative, encoded external, and script-like `next` values. | Login ignores/rejects unsafe targets and lands on the application default route. |
| AUTH-003-05 | Draft | Modify a draft without saving, expire the session, and sign in again. | The app restores a safe local draft or clearly asks to restore/discard it; it never silently replaces the draft with a background refetch. |
| AUTH-003-06 | Auth page | Cause a `401` from the login flow itself and refresh `/login`. | No recursive redirect occurs; the page shows one actionable authentication error. |
| AUTH-003-07 | Direct API | Call the same expired endpoint outside a browser. | API returns a structured `401` and never emits HTML or a server-side redirect. |
| AUTH-003-08 | History | Use Back after reauthentication. | The browser does not expose an authenticated protected page from stale cache to a logged-out user. |

## Automation and Exit

- Add Playwright coverage around `apiFetch`'s `authRedirectStarted` guard and login `next` validation.
- Pass requires a single redirect under concurrent failures and explicit, tested draft recovery behavior.
