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

| Story | Specification | Primary gate |
|---|---|---|
| AUTH-001 | [Valid login, logout, and session identity](AUTH-001.md) | Authentication contract |
| AUTH-003 | [Expired-session recovery](AUTH-003.md) | Safe UI recovery |
| RBAC-001 | [Role and group permission matrix](RBAC-001.md) | Authorization coverage |
| RBAC-002 | [Cross-group isolation](RBAC-002.md) | Object-level isolation |
| AUD-001 | [Material action audit](AUD-001.md) | Traceability and integrity |
| DSRC-001 | [Data-source lifecycle](DSRC-001.md) | Connector configuration |
| DSRC-002 | [Connection diagnostics](DSRC-002.md) | Failure handling and TLS |
| DSRC-003 | [Type-or-browse validation](DSRC-003.md) | Metadata correctness |
| DISC-006 | [Zero-table scan rejection](DISC-006.md) | Preflight safety |
| DISC-007 | [Idempotent discovery rescan](DISC-007.md) | Classification durability |

The source catalog remains [FORGETDM_TEST_CASE_CATALOG.csv](../../FORGETDM_TEST_CASE_CATALOG.csv), and the execution/evidence policy remains [FORGETDM_MASTER_TEST_PLAN.md](../../FORGETDM_MASTER_TEST_PLAN.md).
