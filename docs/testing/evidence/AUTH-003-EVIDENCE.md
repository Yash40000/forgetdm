# AUTH-003 - Expired Session Redirect and Draft Recovery - Evidence

**Story:** AUTH-003 (P0, UI lane)
**Execution status:** COMPLETE - 8/8 acceptance cases directly proven and independently accepted
**Frontend:** Next.js 16.2.10 at `http://127.0.0.1:3103`
**Browser:** Microsoft Edge through Playwright 1.61.1
**Backend for direct API proof:** ForgeTDM verification instance at `http://127.0.0.1:8099`

## Acceptance Results

| Case | Result | Direct evidence |
|---|---|---|
| AUTH-003-01 | PASS | One expired action produced exactly one login document request; `next` preserved the complete local path and query. |
| AUTH-003-02 | PASS | Five concurrent protected requests returned 401; one login navigation occurred; the notification maximum persisted across navigation and remained zero. |
| AUTH-003-03 | PASS | The real sign-in form returned the user to the exact same-origin path and query using replacement navigation. |
| AUTH-003-04 | PASS | Absolute, protocol-relative, encoded external, backslash, and script-like `next` values all landed on the application default route. |
| AUTH-003-05 | PASS | A dirty Synthetic draft raised `beforeunload`; canceling preserved the draft. After the redirect latch reset, a second 401 raised the warning again and still preserved the draft. |
| AUTH-003-06 | PASS | Login API 401 displayed one actionable error; refresh and a second failed submission did not recurse or redirect. |
| AUTH-003-07 | PASS | A direct unauthenticated API call returned JSON HTTP 401 with no `Location` header and no HTML. |
| AUTH-003-08 | PASS | After sign-out and Back, a cross-navigation observer recorded zero transient protected renders and the auth gate returned to login. |

## Authoritative Runs

### Edge browser contract

The final hardened full run executed seven browser cases with one worker, zero retries, screenshots, and traces.

```text
7 passed (2.0m)
expected=7, unexpected=0, skipped=0, flaky=0
```

Machine-readable reports:

- `frontend/test-results/auth-003/results.json`
- `frontend/test-results/auth-003/junit.xml`
- `frontend/test-results/auth-003/artifacts/`
- `docs/testing/evidence/artifacts/AUTH-003-BROWSER-HARDENED-FULL-2026-07-19.txt`
- `docs/testing/evidence/artifacts/AUTH-003-02-08-HARDENED-2026-07-19.txt`

The amended cancellation-retry path was also executed alone before the full run:

- `docs/testing/evidence/artifacts/AUTH-003-05-RETRY-2026-07-19.txt`

### Direct API contract

`GET /api/policies` without a session returned:

```text
HTTP/1.1 401
Content-Type: application/json
Location: absent
{"error":"Login required"}
```

Artifact: `docs/testing/evidence/artifacts/AUTH-003-DIRECT-401-2026-07-19.txt`

### Type gate

`npm.cmd run typecheck` completed with exit code 0. It ran `next typegen && tsc --noEmit` after the final implementation and test edits.

Artifact: `docs/testing/evidence/artifacts/AUTH-003-TYPECHECK-2026-07-19.txt`

## Defects Found and Closed

- `DEF-0003`: session-expiry navigation could silently destroy an unsaved draft. Fixed with dirty-draft `beforeunload` guards and verified in Edge.
- `DEF-0004`: the safe return-path guard allowed a backslash normalization bypass. Fixed with normalized same-origin validation and verified against all listed vectors.
- `DEF-0026`: canceling dirty-draft navigation left the one-shot redirect latch permanently set. Fixed with a bounded latch reset after concurrent 401s drain; verified by two consecutive expiry attempts in AUTH-003-05.
- `DEF-0027`: browser Back briefly rendered protected children while auth was unresolved. Fixed by gating shell children until positive authentication; hardened case 08 passed.
- `DEF-0028`: the notification counter reset on login navigation. Fixed by persisting the maximum across same-origin documents; hardened case 02 passed.

## Independent Review Correction

The first 7/7 browser result was rejected after trace review. DEF-0027 and DEF-0028 corrected the application and evidence harness. Cases 02 and 08 then passed alone and again in the complete seven-case run. Independent trace review confirmed the notification maximum remained zero and only the neutral session-verification state appeared after Back.

## Decision

AUTH-003 is complete. All eight acceptance cases have direct retained evidence, the final type gate is explicit, the backend regression is green, and independent review found no remaining technical blocker.
