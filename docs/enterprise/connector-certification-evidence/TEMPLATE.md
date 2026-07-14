# Connector certification evidence record

Status: DRAFT
Evidence ID: CONN-YYYY-NNN
Matrix lane:
Test started (UTC):
Test completed (UTC):

## Immutable environment

| Field | Recorded value |
| --- | --- |
| ForgeTDM version | |
| Git commit or image digest | |
| Java version | |
| Deployment OS/container/platform | |
| Database product and edition | |
| Database full version/patch/function level | |
| JDBC driver artifact and version | |
| Native loader/client and version | |
| Database topology | |
| Database encoding/collation/time zone | |
| Authentication mechanism | |
| TLS protocol/cipher and hostname verification | |
| Source certification scope | NONE / READ |
| Target certification scope | NONE / WRITE |
| ForgeTDM capabilities in scope | |

## Evidence artifacts

| Artifact | Location or SHA-256 | Contains no secret/PII |
| --- | --- | --- |
| Connector Diagnose export | | YES / NO |
| Automated test result | | YES / NO |
| Schema-pack manifest | | YES / NO |
| Benchmark report | | YES / NO |
| Cancel/restart/recovery report | | YES / NO |
| Native-loader and fallback report | | YES / NO |
| Reject/validation report | | YES / NO |
| Sanitized logs | | YES / NO |
| Security review | | YES / NO |

## Capability results

Use `PASS`, `FAIL`, `NOT TESTED`, or `NOT APPLICABLE`. A required `FAIL` or `NOT TESTED` prevents certification.

| Area | Result | Test/report reference | Notes and limits |
| --- | --- | --- | --- |
| Connection and TLS | | | |
| Least privilege and secret redaction | | | |
| Catalog/schema/table browsing | | | |
| PII discovery and selected scope | | | |
| Data-type boundary pack | | | |
| BLOB/CLOB/XML/JSON streaming | | | |
| Unicode, collation and time zones | | | |
| Quoted/generated/identity objects | | | |
| Composite/circular/missing-key schemas | | | |
| Masking and cross-table consistency | | | |
| Subsetting and orphan validation | | | |
| Synthetic generation and RI | | | |
| Insert/append/update/replace preparation | | | |
| JDBC load path | | | |
| Native load path | | | |
| Native-to-JDBC fallback | | | |
| Progress, cancellation and retry | | | |
| Process-loss/recovery behavior | | | |
| Concurrency and agreed scale | | | |
| Audit, lineage and bounded errors | | | |

## Performance envelope

| Workload | Rows/tables/data size | Concurrency | Elapsed | Peak memory | Result |
| --- | --- | --- | --- | --- | --- |
| Discovery | | | | | |
| Mask copy | | | | | |
| Referential subset | | | | | |
| Synthetic generation | | | | | |
| Native load | | | | | |
| JDBC fallback | | | | | |

Performance results describe this environment; they are not a universal product guarantee.

## Defects and accepted limitations

| ID | Severity | Description | Resolution/acceptance | Owner |
| --- | --- | --- | --- | --- |
| | | | | |

## Decision

Requested status: IMPLEMENTED / LAB VALIDATED / PRODUCTION CERTIFIED / CUSTOMER QUALIFIED
Approved feature and direction scope:
Explicit exclusions:
Expiry/review date:
Regression triggers:

| Role | Name | Decision | Date | Evidence/signature reference |
| --- | --- | --- | --- | --- |
| Quality Engineering | | APPROVE / REJECT | | |
| Security | | APPROVE / REJECT | | |
| Product Engineering | | APPROVE / REJECT | | |
| Support owner | | ACCEPT / REJECT | | |
| Customer owner, if qualified | | ACCEPT / REJECT | | |
