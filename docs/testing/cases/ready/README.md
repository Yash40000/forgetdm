# Ready Test Pack

This directory contains the executable specifications for the ten stories currently in `status:ready` on the ForgeTDM product-readiness board.

## Execution Rules

- `NOT RUN` means the specification exists but no pass claim has been made.
- Record the immutable build commit, environment, database or driver versions, operator, timestamps, and issue number before execution.
- Attach sanitized responses, screenshots, audit event IDs, database assertions, and logs to the linked issue.
- Never attach credentials, connection secrets, customer data, clear PII, or authentication tokens.
- A failed required case blocks the story; link the defect and rerun the affected regression cases after the fix.
- Move a story to `status:done` only after every required case passes, retained evidence exists, and an independent review agrees.
- A resource-dependent case may be marked `HARD-PASS` only when the missing fixture is explicit. HARD-PASS is not a functional pass or vendor certification.

## Ready Stories

| Story | Specification | Primary gate | Cases | Status (2026-07-19) |
|---|---|---:|---:|---|
| AUTH-001 | [Valid login, logout, and session identity](AUTH-001.md) | Authentication contract | 10 | COMPLETE - 10/10 pass on physical HTTP + HTTPS at `9f9ca02`; independently accepted |
| AUTH-003 | [Expired-session recovery](AUTH-003.md) | Safe UI recovery | 8 | COMPLETE - 8/8 directly proven and independently accepted |
| RBAC-001 | [Role and group permission matrix](RBAC-001.md) | Authorization coverage | 10 | 10/10 executed; 9 passed and 1 failed then fixed |
| RBAC-002 | [Cross-group isolation](RBAC-002.md) | Object-level isolation | 9 | Executed; S1 found, fixed, and reverified |
| AUD-001 | [Material action audit](AUD-001.md) | Traceability and integrity | 10 | 6 pass / 2 partial / 2 not executed |
| DSRC-001 | [Data-source lifecycle](DSRC-001.md) | Connector configuration | 9 | COMPLETE WITH HARD-PASS EXCEPTIONS - PostgreSQL, Oracle, MySQL, and H2 live; DB2, SQL Server, and Teradata not live-certified |
| DSRC-002 | [Connection diagnostics](DSRC-002.md) | Failure handling and TLS | 10 | COMPLETE WITH HARD-PASS EXCEPTIONS - 7 pass; TLS trust, TLS identity, and low-privilege fixtures not certified |
| DSRC-003 | [Type-or-browse validation](DSRC-003.md) | Metadata correctness | 9 | NOT RUN |
| DISC-006 | [Zero-table scan rejection](DISC-006.md) | Preflight safety | 8 | COMPLETE WITH HARD-PASS EXCEPTION - 7 pass; metadata-restricted-account behavior not certified |
| DISC-007 | [Idempotent discovery rescan](DISC-007.md) | Classification durability | 10 | NOT RUN |

`CODE-VERIFIED` is not verified. A correct-looking implementation can still fail in transaction, browser, driver, or vendor behavior. Only direct retained execution evidence supports a pass claim.

The source catalog is [FORGETDM_TEST_CASE_CATALOG.csv](../../FORGETDM_TEST_CASE_CATALOG.csv), and the execution policy is [FORGETDM_MASTER_TEST_PLAN.md](../../FORGETDM_MASTER_TEST_PLAN.md).
