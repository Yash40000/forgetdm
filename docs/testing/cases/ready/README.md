# Ready Test Pack

This directory contains the executable specifications for the ten stories currently in `status:ready` on the ForgeTDM product-readiness board.

## Execution Rules

- `status:ready` is queue eligibility, not execution approval. Before running a story, record its authorized scope in `STORY_EXECUTION_APPROVALS.json` and pass `assert-story-approved.ps1`; retain the generated entry-gate evidence with the story evidence.
- The product owner delegated execution approval to the Codex test coordinator on 2026-07-19. The coordinator may approve a story to start after its entry criteria pass, but may not use that approval as final acceptance; a separate evidence review remains mandatory.
- Approval for local test fixtures never authorizes production, customer data, paid services, external notifications, or an unscoped destructive action. Those scopes require a separate explicit approval.
- `NOT RUN` means the specification exists but no pass claim has been made.
- Record the immutable build commit, environment, database or driver versions, operator, timestamps, and issue number before execution.
- Attach sanitized responses, screenshots, audit event IDs, database assertions, and logs to the linked issue.
- Never attach credentials, connection secrets, customer data, clear PII, or authentication tokens.
- A failed required case blocks the story; link the defect and rerun the affected regression cases after the fix.
- Move a story to `status:done` only after every required case passes, retained evidence exists, and an independent review agrees.
- A resource-dependent case may be marked `HARD-PASS` only when the missing fixture is explicit. HARD-PASS is not a functional pass or vendor certification.

## Ready Stories

| Story | Specification | Primary gate | Cases | Status (2026-07-22) |
|---|---|---:|---:|---|
| AUTH-001 | [Valid login, logout, and session identity](AUTH-001.md) | Authentication contract | 10 | COMPLETE - 10/10 pass on physical HTTP + HTTPS at `9f9ca02`; independently accepted |
| AUTH-003 | [Expired-session recovery](AUTH-003.md) | Safe UI recovery | 8 | COMPLETE - 8/8 directly proven and independently accepted |
| RBAC-001 | [Role and group permission matrix](RBAC-001.md) | Authorization coverage | 10 | COMPLETE - 10/10 pass at `6614e22`; 21/21 Edge cases; independently accepted |
| RBAC-002 | [Cross-group isolation](RBAC-002.md) | Object-level isolation | 9 | COMPLETE - 9/9 pass; DEF-0007 and DEF-0032 closed; independently reconciled |
| AUD-001 | [Material action audit](AUD-001.md) | Traceability and integrity | 10 | 6 pass / 2 partial / 2 not executed |
| DSRC-001 | [Data-source lifecycle](DSRC-001.md) | Connector configuration | 9 | COMPLETE WITH HARD-PASS EXCEPTIONS - PostgreSQL, Oracle, MySQL, and H2 live; DB2, SQL Server, and Teradata not live-certified |
| DSRC-002 | [Connection diagnostics](DSRC-002.md) | Failure handling and TLS | 10 | COMPLETE WITH HARD-PASS EXCEPTIONS - 7 pass; TLS trust, TLS identity, and low-privilege fixtures not certified |
| DSRC-003 | [Type-or-browse validation](DSRC-003.md) | Metadata correctness | 9 | NOT RUN |
| DISC-006 | [Zero-table scan rejection](DISC-006.md) | Preflight safety | 8 | COMPLETE WITH HARD-PASS EXCEPTION - 7 pass; metadata-restricted-account behavior not certified |
| DISC-007 | [Idempotent discovery rescan](DISC-007.md) | Classification durability | 10 | COMPLETE - 10/10 live PostgreSQL/API plus Edge UI/CSV pass; DEF-0033 closed |
| MASK-001 | [Built-in masking function contracts](MASK-001.md) | Function matrix | 5 | COMPLETE - 43/43 functions exercised; 73 focused tests and Edge UI pass; DEF-0034 closed |
| MASK-002 | [Boundary and datatype-fit behavior](MASK-002.md) | Unicode and target fit | 7 | COMPLETE - 7/7; 108 regression tests plus PostgreSQL, Oracle, and MySQL live SQL; DEF-0035/0036 closed |
| MASK-003 | [Seeded deterministic replay](MASK-003.md) | Cross-connector checksum | 9 | COMPLETE - 9/9; all 43 functions plus PostgreSQL, Oracle, and MySQL checksum replay pass |
| MASK-004 | [Cross-table and cross-database consistency](MASK-004.md) | Join preservation | 10 | COMPLETE - 10/10; 48/48 joins per PostgreSQL, Oracle, and MySQL; DEF-0037 closed |

`CODE-VERIFIED` is not verified. A correct-looking implementation can still fail in transaction, browser, driver, or vendor behavior. Only direct retained execution evidence supports a pass claim.

The source catalog is [FORGETDM_TEST_CASE_CATALOG.csv](../../FORGETDM_TEST_CASE_CATALOG.csv), and the execution policy is [FORGETDM_MASTER_TEST_PLAN.md](../../FORGETDM_MASTER_TEST_PLAN.md).
