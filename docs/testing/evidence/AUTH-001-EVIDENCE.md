# AUTH-001 — Valid Login, Logout, and Session Identity — Evidence

**Story:** AUTH-001 (P0, Authentication contract)
**Spec:** `docs/testing/cases/ready/AUTH-001.md`
**Execution status:** IN PROGRESS — code-verified; one release-blocking defect found and fixed; live capture pending.

## Run metadata (fill on execution)

| Field | Value |
|---|---|
| Build commit | `<git rev-parse HEAD>` |
| Environment | HTTPS production-like lane + local HTTP lane |
| Backend | Spring Boot 3.3.5 (Java 17+) |
| Database / driver | PostgreSQL `<version>` / `<driver>` |
| Operator | `<name>` |
| Started / finished | `<ISO ts>` / `<ISO ts>` |
| Issue | `<GitHub issue #>` |

## Implementation under test

- `AuthController` — `POST /api/auth/login`, `POST /api/auth/logout`, `GET /api/auth/me`
- `AccessControlService` — credential check, session creation (SHA-256 token hash), invalidation, principal assembly
- `AccessControlFilter` — protects every `/api/**` except `/api/auth/{login,logout,me}`; returns `401 {"error":"Login required"}` when no valid session

## Result summary

| # | Case | Status | Evidence anchor |
|---|---|---|---|
| 01 | UI login, no credential leak | CODE-VERIFIED · live UI capture pending | Cookie is HttpOnly (JS cannot read); response body returns only principal + expiry, no password/token |
| 02 | `login` then `me` identity parity | **PASS** (was PARTIAL) — [DEF-0002](../defects/DEF-0002-me-omits-group-membership.md) **CLOSED**; verified live 2026-07-18: `/api/auth/me` returns `user.groups:[{id:1,name:"TDM Admins"}]` alongside roles + permissions | `AccessPrincipal` now carries `groups`, populated in `principal()` |
| 03 | Cookie flags (HttpOnly/Path/SameSite/Secure/lifetime) | **DEFECT FIXED** — `Secure` was absent; now set on HTTPS lane ([DEF-0001](../defects/DEF-0001-session-cookie-missing-secure.md), CLOSED) | `AuthController` `ResponseCookie … .secure(isSecure(request))` |
| 04 | Wrong password / unknown user → identical failure | **FAIL → fixed → PASS** ([DEF-0016](../defects/DEF-0016-login-timing-username-enumeration.md) CLOSED; re-verified live: medians **609 ms vs 610 ms, ratio 1.00**, distributions overlapping) | Body and status *are* identical (`401`, `"Invalid username or password"`), but **timing is not**. Live, after warm-up, 6 samples each: existing-user-wrong-password median **632 ms** vs unknown-user **23 ms** — **27.5× with zero overlap**. PBKDF2 (160k iterations) is short-circuited when the user doesn't exist, giving a reliable username-enumeration oracle. The same short-circuit covers `!active`, so inactive accounts are enumerable too. → [DEF-0016](../defects/DEF-0016-login-timing-username-enumeration.md) |
| 05 | Inactive user denied, no token | CODE-VERIFIED | Same branch checks `!active`; no session row / cookie issued |
| 06 | Logout invalidates server session; replay → 401 | CODE-VERIFIED | `logout` deletes session by token hash + clears cookie; filter returns 401 |
| 07 | Two concurrent sessions, log out one | CODE-VERIFIED (independent revocation) · live capture pending | Each login inserts a distinct `forge_sessions` row; logout deletes only that token's row |
| 08 | Past-TTL session → protected endpoint 401 | CODE-VERIFIED | `principalFromToken` requires `expires_at > now`; filter returns 401 |
| 09 | Persisted material / logs contain only hashes | CODE-VERIFIED | Session stored as `token_hash` (SHA-256); password hashed; audit details carry no secrets |
| 10 | Auth audit after success/failure/logout | **CORRECTED — this CODE-VERIFIED claim was wrong** → now **PASS** | The code *called* `audit.log(…LOGIN_FAILED…)`, which is why it read as verified. Live execution under AUD-001-05 later proved **no `LOGIN_FAILED` event ever persisted**: `login()` is `@Transactional` and the following `throw` rolled the audit row back. Raised as [DEF-0008](../defects/DEF-0008-failed-login-audit-rolled-back.md) (HIGH), fixed via `AuditWriter` `REQUIRES_NEW`, re-verified live (0 → 1 event, outcome FAILURE / severity CRITICAL). |

## Live execution — 2026-07-18 (converting CODE-VERIFIED into real evidence)

| # | Now | How |
|---|---|---|
| 01 | **PASS (live)** | Login body carries only `authenticated`, `expiresAt`, `user` — no token field; `Set-Cookie` unreadable from JS and the session cookie is absent from `document.cookie` (HttpOnly holds). |
| 02 | **PASS (live)** | `/api/auth/me` returns `groups:[{id:1,name:"TDM Admins"}]` — DEF-0002 closed. |
| 04 | **FAIL (live)** | Timing oracle — see row above → DEF-0016. |
| 10 | **PASS (live)** | Failed login now persists (0 → 1 `LOGIN_FAILED`, FAILURE/CRITICAL) — DEF-0008 closed. |
| 03, 05, 06, 07 | **Automated** | Moved into `docs/testing/run-all-tests.ps1`. PowerShell can read the raw `Set-Cookie` header (case 03) and hold **two independent sessions** (case 07) — neither is possible from browser JavaScript, so this is a better instrument, not a weaker one. |
| 08 | **BLOCKED** | `sessionHours` is floored at 1 (`Math.max(1, sessionHours)`), so a live TTL expiry needs a dedicated short-TTL profile or a one-hour wait. Not fabricated as a pass. |
| 09 | **BLOCKED** | At-rest inspection of `forge_sessions` / `forge_users` needs direct DB access; the sandbox VM that would run `psql` is down. API-surface evidence (no token echoed) is captured under case 01. |

## Lesson: code-verification is not verification

AUTH-001-10 was marked CODE-VERIFIED because the source visibly called
`audit.log(..., "LOGIN_FAILED", ...)`. That reading was correct and the conclusion was still wrong —
the surrounding `@Transactional` boundary discarded the row. The defect was only found later, by
executing AUD-001-05 against a running system and counting events.

Any case in this pack still marked **CODE-VERIFIED** carries the same risk and should be treated as
*unverified* until executed live. Remaining in this story: cases 01, 04, 05, 06, 07, 08, 09.

## Findings

### F1 — Release-blocking defect (AUTH-001-03): session cookie missing `Secure` — FIXED
`AuthController` built the `FORGETDM_SESSION` cookie with `HttpOnly`, `SameSite=Lax`, `Path=/`, and a finite `Max-Age`, but **never set `Secure`**, so on the HTTPS lane the session cookie could be sent over a downgraded/plaintext channel. Per AUTH-001-03 this is release-blocking.

**Fix applied:** `.secure(isSecure(request))` added to the login and logout cookies. `isSecure()` returns true when the request is TLS-terminated directly *or* arrives behind a proxy with `X-Forwarded-Proto: https`, and false on the local HTTP lane — so the HTTPS lane now gets `Secure` while local HTTP development continues to work. Requires a backend rebuild to take effect, then re-capture AUTH-001-03 evidence in both lanes.

### F2 — Minor deviation (AUTH-001-02): `/me` omits explicit group membership
`/api/auth/me` returns the principal's **roles** (already the union of directly-assigned and group-granted roles) and **effective permissions**, but not the list of groups the user belongs to. Functionally the authorization surface is complete (group-derived roles and their permissions are present); only the literal group names are absent. Recommend adding a `groups` field to the principal returned by `/me` (and login) to satisfy the criterion verbatim. Not release-blocking.

## Reproduction / live-capture commands

Run against the target lane; capture sanitized output and attach to the issue. **Never attach the cookie value, token, or password.**

```bash
BASE=https://<host>            # HTTPS lane; also repeat with http://localhost:<port>
J=/tmp/forgetdm.cookies

# AUTH-001-02 — login + me identity parity
curl -sS -c "$J" -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"<active-user>","password":"<password>"}' | jq '{authenticated, expiresAt, user:{username:.user.username, roles:.user.roles, permissions:(.user.permissions|length)}}'
curl -sS -b "$J" "$BASE/api/auth/me" | jq '{authenticated, user:{username:.user.username, roles:.user.roles}}'

# AUTH-001-03 — cookie flags (expect HttpOnly, Path=/, SameSite=Lax, Max-Age>0, and Secure on HTTPS)
curl -sS -i -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"<active-user>","password":"<password>"}' | grep -i '^set-cookie:' | sed 's/=[^;]*/=<redacted>/'

# AUTH-001-04 — wrong password vs unknown user return the identical body/status
curl -sS -o /dev/null -w '%{http_code}\n' -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d '{"username":"<active-user>","password":"WRONG"}'
curl -sS -o /dev/null -w '%{http_code}\n' -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d '{"username":"nosuchuser","password":"WRONG"}'

# AUTH-001-05 — inactive user denied
curl -sS -o /dev/null -w '%{http_code}\n' -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d '{"username":"<inactive-user>","password":"<password>"}'

# AUTH-001-06 — logout invalidates server session; replay old cookie → 401
curl -sS -b "$J" -X POST "$BASE/api/auth/logout" > /dev/null
curl -sS -b "$J" -o /dev/null -w 'replay=%{http_code}\n' "$BASE/api/security/users"   # expect 401

# AUTH-001-10 — auth audit after the above (attach the returned event ids, not payloads)
curl -sS -b "$J2" "$BASE/api/audit?category=AUTH&size=20" | jq '.events[] | {seq, action, actor, outcome, ip:(.ipAddress!=null)}'
```

Expected results: 02 → same username/roles from login and `me`; 03 → `Set-Cookie` shows `HttpOnly; SameSite=Lax; Path=/; Max-Age=<positive>` and `Secure` on HTTPS; 04 → both `401` with identical body; 05 → `401`; 06 → `replay=401`; 10 → `LOGIN_SUCCESS`, `LOGIN_FAILED`, `LOGOUT` rows with actor/outcome, `ip:true`.

## Exit checklist

- [ ] Backend rebuilt with the `Secure` fix; AUTH-001-03 re-captured in **both** lanes.
- [ ] Cases 01–10 executed in HTTPS and local HTTP lanes; sanitized evidence attached to the issue.
- [ ] Spring integration test added for login/me/logout/replay; Playwright test for the UI path (AUTH-001-01).
- [ ] Authentication + access-control regression suites run green.
- [ ] Second reviewer signs off before moving the story to `status:done`.
- [ ] Decide F2 (add `groups` to `/me`) — fix or accept as documented deviation.
