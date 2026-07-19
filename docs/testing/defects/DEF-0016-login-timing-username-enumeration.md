# DEF-0016 — Login timing side-channel enables username enumeration

| Field | Value |
|---|---|
| Severity | MEDIUM (security — pre-auth information disclosure) |
| Status | **CLOSED** — fixed and verified live 2026-07-18 |
| Found by story | AUTH-001 — case AUTH-001-04 |
| Component | `io.forgetdm.security.AccessControlService.login()` |
| Found on | Live stack 2026-07-18 (new UI at :3000 → backend :8088) |

## Summary

`POST /api/auth/login` returns an identical status and body for a wrong password and an unknown user
(`401`, `{"error":"Invalid username or password"}`) — but the **response time** reveals which it was.

Measured live, after warming both paths, 6 samples each:

| Scenario | Samples (ms) | Median |
|---|---|---|
| Existing user, wrong password | 620, 700, 632, 631, 844, 581 | **632 ms** |
| Unknown user | 23, 21, 23, 22, 62, 20 | **23 ms** |

**27.5× difference with zero overlap** (slowest unknown-user 62 ms vs fastest existing-user 581 ms).
A single request therefore classifies a username with near-certainty.

## Root cause

`PasswordHasher` uses PBKDF2 at 160,000 iterations — correctly expensive for credential storage. The
login check short-circuits before reaching it:

```java
if (rows.isEmpty() || !rows.get(0).active || !PasswordHasher.verify(password, rows.get(0).passwordHash)) {
```

When `rows.isEmpty()` is true the expensive verification never runs, so the request returns in ~23 ms.
`!rows.get(0).active` short-circuits the same way, so **inactive accounts are also enumerable** —
they answer on the fast path too.

## Impact

Pre-authentication disclosure of which usernames exist (and which are inactive). This is the
reconnaissance step before credential stuffing or targeted phishing, and it lets an attacker
concentrate password attempts on accounts known to exist. AUTH-001-04 explicitly requires wrong
password and unknown user to produce an *identical* failure; identical text is not enough if the
timing differs.

Mitigating context: failed logins are now audited ([DEF-0008](DEF-0008-failed-login-audit-rolled-back.md)),
so enumeration sweeps are at least detectable — but the enumeration itself still succeeds.

## Steps to reproduce

1. Time `POST /api/auth/login` with an existing username and a wrong password → ~630 ms.
2. Time the same call with a username that does not exist → ~23 ms.
3. Repeat after warm-up; the distributions do not overlap.

## Fix

Perform exactly one password verification on every path, using a dummy hash when there is no
candidate, so all failure modes cost the same:

```java
UserSecret candidate = rows.isEmpty() ? null : rows.get(0);
boolean passwordOk = PasswordHasher.verify(password, candidate == null ? dummyHash : candidate.passwordHash);
if (candidate == null || !candidate.active || !passwordOk) { /* audit + 401 */ }
```

`dummyHash` is generated once at construction via `PasswordHasher.hash(UUID.randomUUID().toString())`,
so it uses the same algorithm and iteration count as real credentials and stays cost-matched if those
parameters change. This also closes the inactive-account fast path.

**Residual risk:** PBKDF2 timing still varies slightly with input length and machine load, so this
equalises rather than perfectly constant-times the endpoint. Rate limiting and lockout on repeated
failures remain worthwhile defence-in-depth and are **not** currently implemented — worth a follow-up.

## Verification (live, 2026-07-18, after rebuild)

Same method as the finding — both paths warmed, 6 samples each:

| Scenario | Samples (ms) | Median |
|---|---|---|
| Existing user, wrong password | 604, 609, 607, 603, 621, 667 | **609 ms** |
| Unknown user | 610, 696, 609, 594, 569, 687 | **610 ms** |

**Ratio 1.00** (was 27.5×), and the two distributions now fully overlap — the unknown-user path pays
the same PBKDF2 cost, so response time no longer discloses whether an account exists. The inactive
-account fast path is closed by the same change.

Asserted continuously by `run-all-tests.ps1`: *"AUTH-001-04 no timing oracle (ratio < 2x)"*.
