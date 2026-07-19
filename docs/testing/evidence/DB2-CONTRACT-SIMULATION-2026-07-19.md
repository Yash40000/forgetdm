# DB2 Contract and Dialect Simulation Evidence - 2026-07-19

**Scope:** DB2 lane only. This is a source-level contract simulation, not a live DB2 certification.

## Environment boundary

| Check | Result |
|---|---|
| IBM JCC driver | Present: `com.ibm.db2:jcc:11.5.9.0` |
| DB2 LUW listener | No listener on local TCP `50000` |
| Live DB2 database | Not available in this environment |
| DB2 native client | Not configured or executed |

## Focused execution

```powershell
mvn -q "-Dtest=SqlDialectTest,SubsetDialectTest,TimeFlowSchemaTest,NativeLoadRegistryTest,Db2ProvisioningSqlContractTest,ConnectorDiagnosticsServiceTest,ProvisioningLobBindTest,SyntheticConstraintRulesTest" test
```

**Result:** 32 tests passed, 0 failed, 0 errors, 0 skipped after the final alias hardening.

| Test class | Tests | DB2 contract covered |
|---|---:|---|
| `SqlDialectTest` | 3 | DB2 aliases, JDBC URL recognition, `TRUNCATE ... IMMEDIATE`, DDL transaction safety, catalog-schema filtering, bind limit |
| `SubsetDialectTest` | 1 | DB2 uppercase ANSI identifier qualification |
| `TimeFlowSchemaTest` | 3 | Uppercase DB2-style schema canonicalization and VDB materialization outside `PUBLIC` using H2 simulation |
| `NativeLoadRegistryTest` | 7 | DB2 LUW URL parsing, DB2 UDB/LUW aliases, DB2 z/OS aliases and native-loader safety boundary, accepted PostgreSQL/SQL Server alias routing |
| `Db2ProvisioningSqlContractTest` | 2 | DB2 in-place masking `MERGE` and insert/update `MERGE` SQL shape and bind order |
| `ConnectorDiagnosticsServiceTest` | 1 | Non-mutating messy-schema diagnostics: composite keys, cycles, LOBs, quoted identifiers |
| `ProvisioningLobBindTest` | 8 | Portable CLOB/BLOB stream binding and LOB-safe execution behavior |
| `SyntheticConstraintRulesTest` | 7 | Simple check-constraint enforcement boundaries used by generated-data workflows |

## Defect found and fixed

[DEF-0025](../defects/DEF-0025-db2-zos-native-loader-misroute.md) found that `DB2ZOS` was registered with the DB2 LUW command-line `LOAD` adapter. If a generic DB2 native-loader environment flag had been configured, ForgeTDM could have attempted a LUW `db2` command/script for a z/OS source or target.

The registry now canonicalizes accepted engine aliases, routes `DB2UDB`, `DB2_UDB`, and `DB2LUW` to the LUW strategy, and forces `DB2ZOS` aliases to `JDBC_BATCH` with a clear site-adapter prerequisite. The regression tests prove a z/OS alias cannot advertise the LUW loader as native-ready.

Retained command output: `docs/testing/evidence/artifacts/DB2-CONTRACT-RETEST-2026-07-19.txt`.

## HARD-PASS: live DB2 prerequisites

The following cases are **not passed or certified** until a real vendor fixture is available:

| Server-dependent case | Exact prerequisite |
|---|---|
| DSRC-001 DB2 connection lifecycle | A reachable DB2 LUW 11.5.x database, a source account, a target account, and a read-only account with known schema/table grants |
| DSRC-002 DB2 connection diagnostics and failure categories | The same DB2 fixture plus wrong-password, low-privilege, trusted-TLS, untrusted-TLS, and hostname-mismatch test endpoints/accounts |
| DSP-025 DB2 SQL syntax and in-place provision | A disposable DB2 LUW schema with ordinary, composite-key, circular-FK, CLOB/BLOB, and partitioned tables; before/after row-count and checksum capture |
| DB2 LUW native `LOAD` | DB2 LUW client installed, `FORGETDM_DB2_LOAD_ENABLED=true`, `FORGETDM_DB2_LOAD_BIN` pointing to the tested `db2` executable, and a disposable target with permissions for `LOAD ... NONRECOVERABLE` |
| DB2 z/OS native load | A reachable DRDA location plus a site-approved z/OS `LOAD` utility/JCL adapter. The LUW `db2` client must not be used as a substitute. |
| DB2 VDB materialization | Docker daemon, approved DB2 Community image access, adequate memory, and a disposable snapshot schema. |

## Exit decision

**DB2 is simulation-verified only. It is not live-certified.** The test package protects DB2 dialect behavior and prevents a proven z/OS loader misroute, but live engine behavior remains HARD-PASS pending the prerequisites above.
