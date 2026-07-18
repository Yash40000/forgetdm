# DEF-0015 — Audit hash uses a timestamp that does not round-trip, causing false tamper alarms

| Field | Value |
|---|---|
| Severity | HIGH (undermines the tamper-evidence signal) |
| Status | **CLOSED** — fixed and verified live 2026-07-18 |
| Found by story | AUD-001 — case AUD-001-08 (exposed by the DEF-0009 fix) |
| Component | `io.forgetdm.audit.AuditWriter` / `AuditHash` |
| Found on | Live stack 2026-07-18, post-V62 build |

## Summary

After the [DEF-0009](DEF-0009-audit-chain-forked-and-verify-aborts.md) fix made verification walk the
whole trail instead of aborting at the first break, `/api/audit/verify` reported:

```
valid:false  tamperSuspected:true  verifiedCount:2345  hashedCount:2355
linkBreaks:7  contentBreaks:3   (seq 2538, 3050, 3051)
```

`CONTENT_MISMATCH` means a row's own hash does not match its fields — nominally the signature of
edited history. **These are false positives.** The cause is a precision mismatch in the hash input:

- `AuditHash.compute()` hashes `createdAt.toEpochMilli()`.
- `AuditWriter` hashes the in-memory `Instant.now()`, which carries **nanoseconds**.
- Postgres stores `timestamp` at **microsecond** precision, and the nano→micro conversion **rounds**.

When the sub-millisecond remainder is within half a microsecond of the next millisecond, the stored
value rounds up across the millisecond boundary. The row then re-reads as one millisecond later than
the value that was hashed, so the recomputed hash differs — with no tampering at all.

Live timestamps confirm microsecond storage: `2026-07-18T09:41:25.592811Z`, `…09:14:29.547853Z`.
Observed rate: 3 of 2,355 hashed events (~0.13%), consistent with the ~0.05% boundary probability.

## Impact

**This is the worst possible failure mode for a tamper-evidence feature: a false alarm.**
`tamperSuspected:true` on an untampered database trains operators to ignore the signal, which is
precisely the alert fatigue DEF-0009 set out to remove. It also makes genuine tampering
indistinguishable from routine rounding noise.

The bug is latent in every event ever written; it was simply masked before, because verification
aborted at seq 702 and never reached these rows.

## Steps to reproduce

1. `GET /api/audit/verify` → `contentBreaks > 0`, `tamperSuspected:true` on a database nobody edited.
2. Inspect any flagged `seq` → `createdAt` has microsecond precision.
3. Note the hash covers `toEpochMilli()`, so a micro-level round changes the hashed value.

## Fix

Truncate `createdAt` to milliseconds **before** hashing and persisting, so the hashed value and the
stored value are identical by construction:

```java
e.setCreatedAt(now.truncatedTo(ChronoUnit.MILLIS));
```

Nothing is lost: the hash already only consumed millisecond precision, so truncation removes digits
that were never protected. New events can no longer produce a spurious `CONTENT_MISMATCH`.

The three historical mismatches (seq 2538, 3050, 3051) predate the fix and remain flagged. They
should be resolved by the operator-acknowledged **re-anchor** contemplated in DEF-0009 rather than by
rewriting hashes — rewriting history to silence an integrity check would defeat the control.

## Verification (live, 2026-07-18, after rebuild)

The proof is arithmetic: every event written since the fix verified, and no new mismatch appeared.

| Metric | Before fix | After fix | Δ |
|---|---|---|---|
| Total events | 3,013 | 3,058 | **+45** |
| `verifiedCount` | 2,345 | 2,390 | **+45** |
| `contentBreaks` | 3 | 3 | **0** |
| `linkBreaks` | 7 | 7 | **0** |

All 45 events written post-fix verified cleanly — the verified count grew by exactly the number of new
events. The break list is byte-for-byte unchanged (`704, 1933, 2092, 2331, 2538, 2999, 3034, 3048,
3050, 3051`), i.e. **every remaining break predates the fix**. Millisecond truncation removed the
spurious mismatches at the source.

**Residual (expected):** `valid:false` and `tamperSuspected:true` persist because of the 10 historical
breaks. That is now an *accurate* report of real historical damage rather than a false alarm on new
data. Clearing it requires the operator-acknowledged re-anchor from
[DEF-0009](DEF-0009-audit-chain-forked-and-verify-aborts.md) — deliberately not done here, because
silently rewriting hashes to turn an integrity check green is precisely the behaviour the control
exists to prevent.
