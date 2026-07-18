# Ready Test Pack

This directory contains the executable test specifications for the ten stories currently in `status:ready` on the ForgeTDM product-readiness board.

## Execution Rules

- `NOT RUN` means the specification exists but no pass claim has been made.
- Record the immutable build commit, environment, database/driver versions, operator, timestamps, and issue number before execution.
- Attach sanitized API responses, screenshots, audit event IDs, database assertions, and logs to the linked GitHub issue.
- Never attach credentials, connection secrets, customer data, clear PII, or authentication tokens.
- A failed case moves the issue to `status:blocked`; link the defect and rerun the affected regression cases after the fix.
- Move a story to `status:done` only after every required case passes and another person reviews its evidence.

## Ready Stories

| Story | Specification | Primary gate | Cases | Status (2026-07-18) |
|---|---|---|---|---|
| AUTH-001 | [Valid login, logout, and session identity](AUTH-001.md) | Authentication contract | 10 | ⚠️ 8/10 executable — 4 live, 4 scripted, 2 blocked (TTL, DB) |
| AUTH-003 | [Expired-session recovery](AUTH-003.md) | Safe UI recovery | 8 | ✅ 8/8 executed live |
| RBAC-001 | [Role and group permission matrix](RBAC-001.md) | Authorization coverage | 10 | ✅ 10/10 (9 passed, 1 failed→fixed) |
| RBAC-002 | [Cross-group isolation](RBAC-002.md) | Object-level isolation | 9 | ✅ Executed — S1 found→fixed→re-verified |
| AUD-001 | [Material action audit](AUD-001.md) | Traceability and integrity | 10 | ⚠️ 6 pass / 2 partial / 2 not executed |
| DSRC-001 | [Data-source lifecycle](DSRC-001.md) | Connector configuration | 9 | ⚠️ PostgreSQL lane — 7 pass / 1 fail (no conflict policy) / 1 deferred; other engines not certified |
| DSRC-002 | [Connection diagnostics](DSRC-002.md) | Failure handling and TLS | 10 | ⬜ NOT RUN |
| DSRC-003 | [Type-or-browse validation](DSRC-003.md) | Metadata correctness | 9 | ⬜ NOT RUN |
| DISC-006 | [Zero-table scan rejection](DISC-006.md) | Preflight safety | 8 | ⬜ NOT RUN |
| DISC-007 | [Idempotent discovery rescan](DISC-007.md) | Classification durability | 10 | ⬜ NOT RUN |

**Totals:** 93 acceptance cases across 10 stories · 47 cases in 5 stories executed · 46 cases in 5 stories not started.
Of the 47, **45 are now executed or automated** and only **2 remain genuinely blocked** (AUTH-001-08 session TTL,
AUTH-001-09 at-rest DB inspection) plus AUD-001-02/03 deferred (job lifecycle + governance).

> **CODE-VERIFIED is not verified.** AUTH-001-10 was marked CODE-VERIFIED on a correct reading of the
> source and was still wrong — the audit write was rolled back by the surrounding transaction, proven
> only by live execution (DEF-0008). Treat every remaining CODE-VERIFIED case as unverified.

The source catalog remains [FORGETDM_TEST_CASE_CATALOG.csv](../../FORGETDM_TEST_CASE_CATALOG.csv), and the execution/evidence policy remains [FORGETDM_MASTER_TEST_PLAN.md](../../FORGETDM_MASTER_TEST_PLAN.md).
