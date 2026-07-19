# DEF-0025 - DB2 z/OS Could Be Routed to the DB2 LUW Native LOAD Adapter

**Severity:** HIGH
**Status:** CLOSED - selection defect verified by contract simulation
**Found:** 2026-07-19
**Area:** Native loader selection

## Problem

`DB2ZOS` was registered with the generic `DB2_LOAD` external executor. That executor generates a DB2 LUW command-line script:

```sql
CONNECT TO <database> USER <user> USING <password>;
LOAD FROM <file> OF DEL ... INSERT INTO <table> NONRECOVERABLE;
```

This assumes a LUW `db2` client and is not a valid substitute for a site-managed DB2 z/OS load utility/JCL workflow. A generic DB2 native-loader flag and binary path could therefore make ForgeTDM advertise or invoke the wrong physical loader path.

## Fix

The native-loader registry canonicalizes supported DB2 aliases, and `ExternalNativeLoadExecutor.describe(...)` detects canonical `DB2ZOS` for the `DB2_LOAD` strategy and always returns:

- `nativeAvailable=false`
- `launchMode=JDBC_FALLBACK`
- `fallback=JDBC_BATCH`
- an explicit message requiring a site-approved DB2 z/OS LOAD/JCL adapter

This leaves DB2 LUW/UDB behavior unchanged and prevents an unsafe z/OS native-load claim.

## Regression proof

The canonical and alias z/OS regression cases passed as part of the final 32-test DB2 simulation package recorded in:

[`DB2-CONTRACT-SIMULATION-2026-07-19.md`](../evidence/DB2-CONTRACT-SIMULATION-2026-07-19.md)

## Live retest boundary

The reported selection defect is closed: no accepted z/OS alias can advertise or invoke the LUW loader. Certification of a future site-specific z/OS physical loader remains HARD-PASS until the environment provides a DRDA endpoint and an approved utility/JCL adapter. That separate live feature gate must prove the approved adapter, sanitized evidence, and safe fallback behavior.
