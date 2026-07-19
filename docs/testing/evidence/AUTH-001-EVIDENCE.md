# AUTH-001 - Valid Login, Logout, and Session Identity - Evidence

**Story:** AUTH-001 (P0, authentication contract)
**Build under test:** committed source checkpoint `9f9ca023552ed2d0a807a1119ac7c2e8ba20c916`
**Backend:** ForgeTDM verification instance at `http://localhost:8099`
**Frontend:** optimized Next.js build served locally and exercised with Microsoft Edge through Playwright
**Metadata database:** local PostgreSQL 17 fixture on port 5433
**Executed:** 2026-07-18 and 2026-07-19
**Execution status:** COMPLETE - all 10 cases pass on both required physical transports and independent review accepted the evidence

## Acceptance Results

| Case | Result | Direct retained evidence |
|---|---|---|
| AUTH-001-01 | PASS - HTTP + HTTPS | Microsoft Edge returned from sign-in to a settled DataScope workspace in both lanes. A unique disposable user made username scanning unambiguous; 57 textual bodies and 110 sanitized network events per lane were inspected for the actual username, password, and issued session value. URL, storage, console, cookie metadata, retained JSON, and screenshots passed with no secret retained. |
| AUTH-001-02 | PASS - HTTP + HTTPS | Both physical servers returned HTTP 200 for login and `/me`, exact username/display-name/role/group/permission parity, and expiry. |
| AUTH-001-03 | PASS WITH DEPLOYMENT GATE - HTTP + HTTPS | Both lanes produced HttpOnly, Path `/`, SameSite=Lax cookies with a positive finite lifetime; the physical HTTPS lane produced `Secure=true`. Production CA/trust-chain, ingress, rotation, and HSTS remain deployment certification gates. |
| AUTH-001-04 | PASS - HTTP + HTTPS | Both lanes returned the same sanitized 401 shape/error for wrong-password and unknown-user attempts, issued no cookie, and created zero sessions. Median timing ratios were 1.04 (HTTP) and 1.03 (HTTPS). |
| AUTH-001-05 | PASS - HTTP + HTTPS | Both lanes denied the inactive account with 401, no cookie/session, and guaranteed fixture reactivation. |
| AUTH-001-06 | PASS - HTTP + HTTPS | Both lanes returned a clearing cookie with Max-Age 0; deliberate replay returned 401, exposed no server detail, and the hashed session row was gone. |
| AUTH-001-07 | PASS - HTTP + HTTPS | Both lanes created two distinct concurrently valid sessions and proved that logout revoked only the selected session. |
| AUTH-001-08 | PASS - HTTP + HTTPS | Each lane's disposable session was expired by SHA-256 hash; protected replay returned a sanitized 401 derived from the actual response rather than a hard-coded evidence flag. |
| AUTH-001-09 | PASS - HTTP + HTTPS | PostgreSQL inspection covered 590 session rows and 18 user rows. Both bounded server logs were present and nonempty; 18 exact authentication/password/session canaries had zero log or audit matches. Token hashes, PBKDF2 password hashes, and clear-column absence all passed. |
| AUTH-001-10 | PASS - HTTP + HTTPS | Each physical lane retained exactly three separately correlated login-success, login-failure, and logout audit events with event ID, actor, outcome, timestamp, IP presence, AUTH category, and zero forbidden values. |

## Retained Artifacts

- `docs/testing/evidence/artifacts/AUTH-001-BROWSER-TWO-LANE-7ebd65c9841c.json` - final sanitized two-lane Edge evidence, bound to Git commit `9f9ca02`, frontend source SHA-256, optimized Next build ID, origin, transport, and the exact retained screenshot names.
- `docs/testing/evidence/artifacts/AUTH-001-BROWSER-TWO-LANE-7ebd65c9841c.junit.xml` - 2 browser tests passed, zero failures/errors/skips.
- `docs/testing/evidence/artifacts/AUTH-001-BROWSER-TWO-LANE-7ebd65c9841c-http.png` and `-https.png` - settled DataScope screenshots from each physical origin.
- `docs/testing/evidence/artifacts/AUTH-001-ACCEPTANCE-2026-07-19.json` - sanitized cases 02-10, including physical localhost TLS, exact identity parity, session replay, storage, bounded logs, and audit assertions.
- `docs/testing/run-auth-001-acceptance.ps1` - disposable comprehensive acceptance runner; credentials are accepted only through explicit parameters or `FORGETDM_TEST_*` variables.
- `docs/testing/run-auth-001-storage-expiry.ps1` - focused expiry and at-rest assertion runner.
- `frontend/e2e/auth-001/login-session.spec.ts` and `frontend/playwright.auth001.config.ts` - browser acceptance runner with traces/video disabled and no credential defaults.
- `docs/testing/evidence/artifacts/READINESS-CHECKPOINT-2026-07-19.json` - machine-readable regression and acceptance summary for source checkpoint `9f9ca02`.

## Defects Closed

| Defect | Resolution |
|---|---|
| DEF-0001 | Secure cookie behavior added for direct and forwarded TLS. |
| DEF-0002 | Explicit group membership added to login and `/me` principals. |
| DEF-0008 | Failed-login auditing moved outside the rolled-back login transaction. |
| DEF-0016 | Missing/inactive users now pay a cost-matched password verification, closing the timing oracle. |
| DEF-0030 | Successful UI login now replaces the cached unauthenticated identity before returning to the protected route, preventing a stale-cache bounce to sign-in. |

## Deployment Gate

Physical HTTPS application behavior is directly proven with a disposable local certificate and TLS listener. This does not certify a production CA chain, certificate rotation, ingress headers, HSTS, or infrastructure policy; those remain explicit deployment checks and are not represented as failed application behavior.

## Exit Decision

All ten cases satisfy the explicit physical HTTP/HTTPS requirement against committed source checkpoint `9f9ca02`. Independent review found no functional or security blocker and accepted AUTH-001 for completion. Production CA-chain, ingress, rotation, and HSTS remain deployment gates rather than application pass claims.
