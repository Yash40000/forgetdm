# AUD-001 V62 First-Boot and Regression Record

**Executed:** 2026-07-18  
**Scope:** AUD-001-08 / DEF-0009 / DEF-0015  
**Environment:** Local ForgeTDM backend with PostgreSQL metadata database on port 5433  
**Data handling:** Sanitized metadata counts and identifiers only; no credentials or event payloads

## Preflight

Flyway was at V61. `audit_events` contained 3,012 rows, zero null sequence values, and exactly two
duplicate sequence groups:

| Sequence | Copies | Row IDs |
|---:|---:|---|
| 702 | 2 | 702, 704 |
| 703 | 2 | 703, 705 |

Maximum sequence was 3049. No other duplicate was present.

## First boot result

ForgeTDM reached HTTP 200 and Flyway recorded V62 `audit sequence integrity` with `success=true`.

| Row ID | Sequence before | Sequence after |
|---:|---:|---:|
| 702 | 702 | 702 |
| 703 | 703 | 703 |
| 704 | 702 | 3050 |
| 705 | 703 | 3051 |

- Total rows remained 3,012: no audit row was deleted.
- Distinct sequences became 3,012 and duplicate groups became zero.
- Null sequence rows remained zero.
- Unique index `uq_audit_events_seq` was created on `audit_events(seq)`.
- PostgreSQL sequence `audit_event_seq` was ready to allocate 3052.

## Continued-write check

After normal application activity, the ledger reached 3,081 rows and maximum sequence 3120.
`audit_event_seq` also reported 3120 and duplicate sequence groups remained zero. This confirms that
post-migration writes use the database allocator without reintroducing the multi-instance collision.

## Automated regression

Focused Maven execution covered:

- `AuditHashChainTest`: hash determinism, field tampering, parent changes, timestamp round-trip,
  sparse rows, and re-anchoring after a historical link break.
- `FlywayMigrationVersionTest`: rejects duplicate Flyway version numbers.

**Result:** 7 tests run, 7 passed, 0 failures, 0 errors, 0 skipped.

## Status impact

- DEF-0009 remains **CLOSED**.
- DEF-0015 remains **CLOSED**.
- AUD-001 remains **6 PASS / 2 PARTIAL / 2 NOT EXECUTED** because complete material-action coverage,
  job/governance lifecycle execution, and operator-approved historical re-anchoring are still open.
