# DEF-0013 — Audit detail records raw JDBC URLs (credential-leak risk)

| Field | Value |
|---|---|
| Severity | LOW (hardening — no active leak observed) |
| Status | **CLOSED** — fixed and verified live 2026-07-18 |
| Found by story | AUD-001 — case AUD-001-09 |
| Component | `io.forgetdm.datasource.DataSourceService.create()` |

## Summary

`DATASOURCE_CREATED` writes the connection string verbatim:

```java
audit.log(actor, "DATASOURCE_CREATED", saved.getName() + " (" + saved.getJdbcUrl() + ")");
```

A leakage scan of all 3,010 events (1.48 MB) found **no** passwords, API tokens, bearer headers,
session cookies, SSNs or emails — AUD-001-09 passes today. But it did find **14 raw JDBC URLs**
(e.g. `jdbc:postgresql://localhost:5433/sourcedb`, `jdbc:oracle:thin:@localhost:1521:XE`), one
carrying a query string.

None currently embed credentials (`user=` 0 matches, `//user:pass@` 0 matches), so nothing is leaked
right now. The risk is structural: JDBC URLs are a standard place to put credentials
(`jdbc:postgresql://host/db?user=admin&password=secret`, or `//user:pass@host`). If any operator
saves such a source, the secret is written to the audit trail in clear — and the audit trail is
exportable to CSV and readable by every `audit.read` holder.

## Impact

Latent credential disclosure with a wide blast radius (audit is broadly readable and exportable).
Also records infrastructure topology (hosts, ports, SIDs) more widely than necessary.

## Recommended fix

Sanitise before auditing — strip userinfo and credential query params, keep the useful shape:

```java
// jdbc:postgresql://user:pw@host:5432/db?password=x  ->  jdbc:postgresql://host:5432/db
static String safeJdbc(String url) { /* strip //user:pass@ and user=/password= params */ }
```

Apply anywhere a JDBC URL reaches a log/audit/error message, and add a test asserting a
credential-bearing URL is redacted.

## Resolution (2026-07-18) — verified live

`DataSourceService.safeJdbc()` strips `//user:pass@` userinfo and masks `user` / `username` /
`password` / `pwd` / `passwd` parameters (covering `?`, `&` and the `;` separator used by
SQLServer/Oracle URLs), while preserving host, port, database and benign parameters.

**Live proof.** Created a data source with
`jdbc:postgresql://probeuser:supersecret@dbhost:5432/salesdb?user=probeuser&password=supersecret&ssl=true`
and inspected the resulting `DATASOURCE_CREATED` detail:

- username absent, redaction marker `***` present, `dbhost:5432/salesdb` and `ssl=true` preserved.
- The recorded URL measured **exactly 68 characters** —
  `jdbc:postgresql://dbhost:5432/salesdb?user=***&password=***&ssl=true` — versus 104 unredacted,
  confirming both the userinfo and both credential parameters were removed.

Covered by `SafeJdbcRedactionTest` (userinfo, query params, clean URL unchanged, null/blank).
