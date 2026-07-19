# SQL Server Contract and Dialect Simulation Evidence - 2026-07-19

**Scope:** SQL Server lane only. This is source-level contract simulation, not a live Microsoft SQL Server certification.

## Environment boundary

| Check | Result |
|---|---|
| Microsoft JDBC driver | Present in the project: `com.microsoft.sqlserver:mssql-jdbc:12.8.1.jre11` |
| Native MSSQL engine / service | Not found on this Windows host |
| `sqlservr`, `sqlcmd`, and LocalDB | Not found |
| Local SQL Server listener | No listener on TCP `1433` |
| Docker alternative | Docker Desktop is installed but stopped; WSL is unavailable |
| SQL Server data, metadata, and native client execution | Not available in this environment |

## Focused execution

```powershell
mvn.cmd -q "-Dtest=SqlServerContractTest,SqlDialectTest,SubsetDialectTest,BusinessEntitySqlTest,NativeLoadRegistryTest,ConnectionFailureClassificationTest,SafeJdbcRedactionTest" test
```

**Result:** 28 tests passed, 0 failed, 0 errors, 0 skipped.

| Test class | Tests | SQL Server contract covered |
|---|---:|---|
| `SqlServerContractTest` | 5 | 2,100-parameter and 1,000-row limits, SQL Server `MERGE` bind order/shape, native `bcp` strategy, JDBC URL parsing, password redaction, SQL Server CHECK metadata source, diagnostics connector mode, JDBC streaming/Unicode/application properties |
| `SqlDialectTest` | 3 | SQL Server parameter ceiling along with dialect-level safety regression coverage |
| `SubsetDialectTest` | 1 | SQL Server bracket qualification for subset planner SQL |
| `BusinessEntitySqlTest` | 2 | SQL Server bracket qualification and unsafe identifier rejection for cross-application entity SQL |
| `NativeLoadRegistryTest` | 4 | Native-loader registry fallback behavior and external-client safety boundary |
| `ConnectionFailureClassificationTest` | 9 | Credential-safe SQL Server host parsing and actionable connection failure categorization |
| `SafeJdbcRedactionTest` | 4 | Sanitization of credentials in JDBC URLs and diagnostic output |

## Reviewed SQL Server behavior

| Area | Contract result |
|---|---|
| Batching | `safeJdbcBatchRows` keeps SQL Server requests below the 2,100 bind limit and 1,000 `VALUES`-row limit. A 100-column load caps at 20 rows, and a 2,100-column load falls to one row. |
| Identifier handling | Subset and Business Entity SQL use SQL Server brackets. Provisioning `MERGE` uses ANSI double quotes, which requires `QUOTED_IDENTIFIER ON`; the live fixture must assert that session setting. |
| Insert/update | SQL Server uses row-by-row `MERGE` for `INSERT_UPDATE`, binding each source column exactly once and producing explicit matched/unmatched branches. |
| CHECK capture | Synthetic constraint capture targets `sys.check_constraints.definition`, not `information_schema`. |
| Driver behavior | Microsoft JDBC is configured with adaptive response buffering, Unicode string parameters, and `ForgeTDM` application identity. |
| Diagnostics | SQL Server is recognized as a bundled JDBC dialect; diagnostics use SQL Server catalog queries for partition and collation inspection. |
| Native load | The optional physical executor runs Microsoft `bcp`; otherwise the registry reports `JDBC_MULTI_ROW` fallback. Command evidence redacts the password. |

## Defect found and fixed

`SyntheticGenService` reported the SQL Server native-loader label as `SQLServerBulkCopy`, while the configured executor actually invokes the Microsoft `bcp` command-line client. The execution-evidence label now says **SQL Server bcp**, matching the implemented behavior. `SqlServerContractTest` protects that alignment.

## HARD-PASS: live SQL Server prerequisites

The following cases are **not passed or certified** until a real Microsoft SQL Server fixture is available:

| Server-dependent case | Exact prerequisite |
|---|---|
| Connection, schema browse, and diagnostics | SQL Server 2019+ or 2022 reachable over TCP, Microsoft JDBC-compatible TLS configuration, a test database, and a source account with metadata/table read grants. |
| Provisioning and in-place masking | A disposable target database/schema with ordinary tables, wide tables near the 2,100 bind boundary, 1,000-row `VALUES` batches, composite keys, CLOB-like `nvarchar(max)`/`varbinary(max)` data, and cleanup permission. |
| `MERGE` SQL execution | A fixture session where `SELECT SESSIONPROPERTY('QUOTED_IDENTIFIER')` returns `1`, plus before/after row-count and checksum evidence. |
| CHECK capture and enforcement | Disposable tables containing simple and composite `CHECK` constraints with permission to read `sys.check_constraints`. |
| Native `bcp` execution | Microsoft `bcp` installed on the ForgeTDM host, `FORGETDM_SQLSERVER_BULK_COPY_ENABLED=true`, `FORGETDM_SQLSERVER_BULK_COPY_BIN` pointing to the tested executable, and a disposable target allowing bulk insert. |
| TLS failure behavior | Trusted, untrusted, and hostname-mismatch SQL Server endpoints/certificates with retained connection-test evidence. |

## Exit decision

**SQL Server is contract-simulation verified only. It is not live-certified.** The offline checks protect the implemented dialect behavior and accurately describe the native loader, while all server-dependent cases remain explicit HARD-PASS items pending a real SQL Server fixture.
