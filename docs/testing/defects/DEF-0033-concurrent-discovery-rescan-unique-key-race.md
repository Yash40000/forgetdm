# DEF-0033 - Concurrent direct rescans can race the physical-column unique key

| Field | Value |
|---|---|
| Severity | HIGH |
| Status | CLOSED - fixed and fully retested 2026-07-22 |
| Found by story | DISC-007 (08, 09) |
| Component | PII discovery classification refresh transaction |
| Found on | Source/transaction inspection before same-scope concurrency acceptance |

## Summary

The asynchronous discovery-job service rejects a second active scan for the same source and schema,
but the direct discovery endpoint did not share that coordination. Two concurrent direct requests
could both read the same pre-transaction classification state. When a physical column was new, both
requests could then attempt an insert against `uq_class`.

## Impact

A rescan could fail with a duplicate-key error even though the requested source data was valid. The
failed transaction rolled back, but operators saw a false failure and could not safely automate
parallel regeneration against the same scope.

## Resolution

`DiscoveryService` now acquires a fair lock keyed by data-source ID and normalized schema. Under
Spring transaction management the lock is released only in `afterCompletion`, after commit or
rollback; direct unit construction uses a method-lifetime fallback. Different schemas and data
sources remain independent and can still scan concurrently. The finite scope-lock registry is
retained for the process lifetime: removing an unlocked entry can race a queued waiter and create
two active locks for the same scope.

## Verification

- Two simultaneous authenticated direct scans returned HTTP `200,200`.
- The post-run classification set contained one row per physical column and no `uq_class` error.
- A PostgreSQL trigger injected a failure during a later classification write. The pre/post API
  snapshots were identical after rollback, and the immediate rerun succeeded.
- All ten DISC-007 acceptance cases passed.
- Focused discovery tests and the complete 503-test backend regression passed with zero failures or
  errors. The production frontend build and the Edge findings/CSV test also passed.

Evidence: `docs/testing/evidence/DISC-007-EVIDENCE.md`.
