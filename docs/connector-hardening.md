# Connector hardening and support tiers

## Support tiers

| Tier | Engines | Runtime path |
| --- | --- | --- |
| Bundled and dialect-aware | PostgreSQL, MySQL/MariaDB, Oracle, SQL Server, DB2 LUW/z/OS, H2 | Driver is shipped; SQL and bulk behavior have dedicated dialect handling. |
| Optional JDBC plugin | Teradata, Sybase, SAP HANA, Snowflake, BigQuery, Redshift, other JDBC engines | Install the approved vendor driver in `jdbc-drivers`; generic SQL is used until a dialect adapter is certified. |
| Specialized connector | Mainframe copybooks/files and Zowe | Use the Mainframe screens and transport layer, not a JDBC definition. |

An optional driver making a successful connection is not the same as a certified connector.
Certification requires the preflight and a customer-environment regression pack over discovery,
subset, masking, loading, restart, cancel, and validation.

The authoritative status, initial version lanes, promotion rules, and evidence template are in the
[connector certification matrix](enterprise/CONNECTOR_CERTIFICATION_MATRIX.md). Do not describe a
connector as certified unless that exact lane has an approved evidence record.

## Schema preflight

Open **Data Sources**, save and test a connection, then select **Diagnose**. The bounded read-only
inspection reports:

- detected database and JDBC driver versions;
- transaction, batch, savepoint, identifier, bind-limit, and multi-row capabilities;
- missing and composite primary keys, composite foreign keys, self-references, and circular graphs;
- BLOB/CLOB/long values, generated columns, vendor object types, and quoted identifiers;
- case-folding collisions that can break a cross-engine copy;
- vendor-catalog partition evidence where the current account has access;
- database character set/collation evidence where available;
- prioritized actions and a readiness score.

The scan defaults to 500 tables and is capped at 2,000 per request so a 4,000-table legacy schema
does not freeze the UI. Run one schema at a time and increase `maxTables` through the API when a
complete inventory is required.

## Large values and encoding

SQL Server connections use adaptive response buffering and Unicode string parameters. MySQL and
MariaDB use Unicode plus cursor fetch and rewritten batches. Oracle uses bounded row/LOB prefetch,
and DB2 uses progressive streaming. Provisioning binds source `BLOB`, `CLOB`, `NCLOB`, and `SQLXML`
values through JDBC streams and executes those rows while the source locator is valid.

Before banking production use, validate representative maximum-size LOBs, non-ASCII data, legacy
code pages, time-zone values, and malformed byte sequences against the exact vendor driver version.
