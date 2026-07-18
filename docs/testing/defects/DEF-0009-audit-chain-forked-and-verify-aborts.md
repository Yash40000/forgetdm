# DEF-0009 — Audit hash chain has forked; verification aborts leaving ~3,000 events unverified

| Field | Value |
|---|---|
| Severity | HIGH |
| Status | **CLOSED** — fixed and verified live 2026-07-18 (follow-on: [DEF-0015](DEF-0015-audit-hash-timestamp-rounding.md)) |
| Found by story | AUD-001 — case AUD-001-08 |
| Component | `io.forgetdm.audit.AuditService` (`chainAndSave`, `verifyChain`), `audit_events` schema |
| Found on | Live stack 2026-07-18 (post-V61 build) |

## Summary

The tamper-evident chain is **permanently invalid** and the integrity check covers only a fraction of
the trail. Live result from `GET /api/audit/verify`:

```json
{ "valid": false, "total": 3005, "hashedCount": 11, "legacyCount": 658,
  "verifiedThroughSeq": 702, "brokenAtSeq": 702 }
```

Two distinct problems:

**1. The chain has forked (duplicate sequence numbers).** A full scan found 3,005 events across
3,003 distinct sequences — seq **702** and seq **703** each exist **twice**, and both seq-702 rows
carry the *same* `prevHash` (`Tp4bBLlfy28C…`, the hash of seq 701). Two events were allocated the
same sequence and chained off the same parent, splitting the chain into two branches.

Root cause: `chainAndSave()` allocates the sequence in application memory —

```java
private synchronized void chainAndSave(AuditEventEntity e) {
    ensureInit();
    long seq = ++lastSeq;      // in-memory counter
```

`synchronized` only guards a single JVM, and there is **no unique constraint on `audit_events.seq`**.
Two concurrent instances (or a double start against the same database) each allocate the same number.
Rolled-back audit writes (see [DEF-0008](DEF-0008-failed-login-audit-rolled-back.md)) compound this by
advancing `lastSeq`/`lastHash` for rows that never commit — which also explains the observed gaps
(`maxSeq` 3046 vs 3,010 rows).

**2. Verification stops at the first mismatch.** `verifyChain()` does `break` on the first bad link,
so it reports `verifiedThroughSeq: 702` and never examines the remaining ~3,000 events — including
every security event generated during RBAC-001/RBAC-002 testing and all current activity.

## Impact

- **The compliance claim is unsupportable.** Only 11 of 3,005 events are actually verified.
- **Real tampering after seq 702 is undetectable** — verification never reaches it.
- **Permanent `valid: false` causes alert fatigue.** An operator cannot distinguish this known
  structural fork from genuine tampering, so the integrity signal is effectively ignored.
- The fork is reachable through ordinary operations (running two instances, or restarting during
  activity) — no attacker required.

## Steps to reproduce

1. `GET /api/audit/verify` → `valid:false`, `brokenAtSeq:702`, `hashedCount:11`.
2. Page the trail (`/api/audit?page=N&size=200`) collecting `seq` → seq 702 and 703 each appear twice.
3. Inspect those rows: both seq-702 rows share `prevHash` = seq 701's hash, but have different hashes.

## Recommended fix

**Make sequence allocation atomic and unique**
- Add a database sequence (or `SELECT … FOR UPDATE` / `INSERT … RETURNING`) so `seq` is allocated by
  Postgres, not by an in-JVM counter.
- Add `UNIQUE (seq)` on `audit_events` so a fork fails loudly at write time instead of silently
  corrupting the chain.
- Advance in-memory `lastHash` only after a confirmed commit (and fix DEF-0008's rollback path).

**Make verification useful**
- Do not `break` on the first mismatch: continue, record **every** break point, and report
  per-segment integrity (e.g. `segments: [{fromSeq, toSeq, valid}]`) plus a total verified count.
- Treat the legacy (pre-hash) prefix explicitly rather than letting it blur into "broken".
- Support an operator-acknowledged **re-anchor** so a known historical break does not mask new
  tampering, while keeping the acknowledgement itself audited.

**Remediate existing data**
- The current fork cannot be un-forked without rewriting history (which would itself defeat the
  purpose). Recommended: record a signed "chain re-anchored at seq N" marker, verify forward from
  there, and retain the pre-anchor segment as legacy-unverified.

## Resolution (2026-07-18) — verified live

**Sequence made atomic and unique.** `V62__audit_sequence_integrity.sql` repairs the duplicates
without deleting any row (later duplicates are renumbered above the maximum — append-only is the
point), creates the Postgres sequence `audit_event_seq` starting past history, and adds
`UNIQUE(seq)`. `AuditWriter` now allocates from `nextval('audit_event_seq')` instead of an in-JVM
counter, so concurrent instances can no longer collide, and a future fork fails loudly at write time.

**Verification no longer aborts.** `verifyChain()` records every break, re-anchors, and continues,
reporting per-segment integrity plus `linkBreaks` / `contentBreaks` — distinguishing a discontinuity
(fork, rolled-back write) from a content rewrite.

**Live re-verification:**

| Metric | Before | After |
|---|---|---|
| Events verified | **11** | **2,345** |
| Hashed events examined | 11 | 2,355 |
| Walked through seq | 702 | **3,052** |
| Breaks reported | 1, then abort | **10**, each with seq + reason |
| Valid segments reported | none | **10 ranges** |

Coverage went from ~0.4% to ~78% of the trail (the remainder is the pre-hardening legacy prefix).

**Follow-on found by this fix:** with the whole trail now walked, 3 `CONTENT_MISMATCH` surfaced
(seq 2538, 3050, 3051). These proved to be **false alarms** caused by a timestamp precision
round-trip, raised and fixed as [DEF-0015](DEF-0015-audit-hash-timestamp-rounding.md). The 7
`LINK_BREAK` entries are genuine historical discontinuities from the pre-fix era and are now
correctly reported rather than hidden.

### First-boot database proof

The V62 migration was observed on its first application boot on 2026-07-18:

| Assertion | Before | After |
|---|---:|---:|
| Audit rows | 3,012 | 3,012 |
| Distinct non-null sequences | 3,010 | 3,012 |
| Duplicate sequence groups | 2 (702, 703) | 0 |
| Null sequences | 0 | 0 |
| Maximum sequence | 3,049 | 3,051 |

IDs 702 and 703 retained their original sequence numbers. IDs 704 and 705 were preserved and
renumbered to 3050 and 3051. Flyway recorded V62 successful, the unique index was present, and the
database sequence was positioned so its first allocation was 3052. After subsequent normal writes
advanced the ledger to 3,081 rows / seq 3120, no duplicate sequence group had reappeared.
