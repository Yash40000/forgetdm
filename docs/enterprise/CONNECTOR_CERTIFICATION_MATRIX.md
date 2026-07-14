# ForgeTDM connector certification matrix

Document owner: Product Engineering and Quality Engineering
Last reviewed: 2026-07-12
Applies to: ForgeTDM 1.0.0 on Java 17
Review cadence: Every release and every JDBC/native-client upgrade

This matrix records product evidence. It does not treat a successful JDBC connection as vendor
certification. A connector is certified only for the exact server family, version lane, driver,
deployment platform, direction, and ForgeTDM capabilities listed in a completed evidence record.

## Status language

| Status | Meaning | Permitted external wording |
| --- | --- | --- |
| Implemented | Code and a driver/adapter exist; automated unit tests may use mocks or H2. | "Available for validation" |
| Lab validated | The required suite passed on a recorded real vendor environment. | "Validated on the listed configuration" |
| Production certified | Lab evidence, security review, scale run, recovery run, release approval, and a named support owner are complete. | "Certified" only for the listed lane |
| Customer qualified | A production-certified lane also passed the customer's representative schema and infrastructure acceptance pack. | "Qualified for Customer X" only within that engagement |
| Experimental | Generic JDBC or an incomplete adapter; no production support commitment. | "Experimental" |
| Not supported | Known incompatible, end-of-life, or outside the tested feature scope. | "Not supported" |

`Implemented` is not a weaker spelling of `Certified`. These statuses answer different questions.

## Current certification register

No vendor lane is production certified yet. This is the correct baseline until signed real-engine
evidence is checked into `docs/enterprise/connector-certification-evidence/`.

| Connector lane | Initial server versions to test | Shipped driver or transport | Product implementation | Evidence currently present | Current status | Next promotion gate |
| --- | --- | --- | --- | --- | --- | --- |
| PostgreSQL source/target | 14, 15, 16, 17, 18; current minor release in each lane | PostgreSQL JDBC 42.7.4 | Dedicated dialect, discovery, DataScope, synthetic, masking, subset, LOB streaming, multi-row load, in-process COPY | Unit/regression suite, opt-in PostgreSQL scenario pack, and local smoke evidence; exact server version was not captured | Implemented | Run and retain the full pack on each chosen major; include TLS, scale, cancel/restart, COPY and JDBC fallback |
| MySQL source/target | 8.4 LTS | MySQL Connector/J 8.3.0 | Dedicated dialect, catalog handling, discovery, masking, subset, synthetic, multi-row load, LOAD DATA adapter | Unit-level strategy/fallback coverage only | Implemented | Upgrade/pin an approved driver for the server lane, then complete real-engine suite and LOAD DATA tests |
| MariaDB source/target | 10.11, 11.4, 11.8 LTS | MariaDB JDBC 3.3.3 | MySQL-family dialect, discovery, masking, subset, synthetic, multi-row load, LOAD DATA adapter | No recorded real-engine run | Implemented | Run separate MariaDB lanes; do not inherit MySQL certification |
| Oracle source/target | 19c first; 21c and 26ai as separate lanes | Oracle JDBC 23.5.0.24.07 | Dedicated dialect, Oracle metadata handling, masking, subset, synthetic, LOB streaming, JDBC batch, SQL*Loader adapter, partition exchange path | Unit-level SQL/adapter coverage; no recorded Oracle environment | Implemented | Certify 19c first with wallet/TLS, quoted objects, NUMBER/date/timestamp, LOBs, partitions, SQL*Loader and fallback |
| SQL Server source/target | 2019 and 2022 as separate lanes | Microsoft JDBC 12.8.1.jre11 | Dedicated dialect, 2,100-parameter handling, adaptive buffering, masking, subset, synthetic, LOB streaming, multi-row load, bcp adapter | Unit/regression coverage for parameter limits and adapter behavior; no recorded SQL Server environment | Implemented | Complete Windows and/or Linux lane with TLS, collations, identity/computed columns, max LOBs, bcp and fallback |
| IBM Db2 LUW source/target | 11.5 current mod pack | IBM JCC 11.5.9.0 | Dedicated dialect, metadata/system-schema handling, masking, subset, synthetic, progressive streaming, MERGE, LOAD adapter | Unit-level dialect and adapter coverage; client-reported SQL fixes are not a certification run | Implemented | Run LUW suite against representative banking schema, code page, partitioning, composite RI, LOAD and JDBC fallback |
| IBM Db2 for z/OS source/target | 12 and 13 as separate lanes and function levels | IBM JCC 11.5.9.0 over DDF/type 4 | Db2 dialect, DB2ZOS registration, JDBC operations, copybook/file path separate from JDBC | Copybook codec/parser automated tests; no recorded Db2 z/OS DDF suite | Implemented | Capture exact subsystem/function level, APARs, DDF security, EBCDIC/Unicode behavior, LOBs, utilities and restart evidence |
| H2 development database | 2.2.224 in PostgreSQL mode | H2 2.2.224 | Test/development dialect and in-memory fixtures | Broad automated test use | Lab validated for tests only | Keep explicitly non-production; H2 evidence cannot certify another database |
| Snowflake target | Current service release, account edition and region captured per run | Administrator-supplied approved JDBC driver and SnowSQL client | Generic JDBC plus staged COPY native adapter | Adapter/unit evidence only; driver not shipped | Experimental | Pin JDBC/SnowSQL versions; validate auth, stages, warehouse sizing, COPY errors, cancellation, cost and retry behavior |
| Amazon Redshift | Exact provisioned or serverless release captured per run | Administrator-supplied approved JDBC driver | Generic JDBC only | No real-engine evidence | Experimental | Build a Redshift dialect/load adapter or qualify a deliberately limited generic-JDBC feature set |
| Google BigQuery | Service/API and JDBC driver versions captured per run | Administrator-supplied approved JDBC driver | Generic JDBC only | No real-engine evidence | Experimental | Define supported read/write semantics, job cancellation, quotas, nested types and load path before certification |
| Teradata | Customer-supported release selected with customer | Administrator-supplied approved JDBC driver | Generic JDBC only | No real-engine evidence | Experimental | Add dialect/catalog tests, volatile/partitioned table coverage, character sets, spool limits and supported bulk path |
| SAP HANA | Customer-supported HANA 2.0 revision selected with customer | Administrator-supplied approved JDBC driver | Generic JDBC only | No real-engine evidence | Experimental | Add dialect/catalog adapter and validate schemas, generated columns, LOBs, partitions and bulk loading |
| SAP/Sybase ASE | Customer-supported ASE 16 lane selected with customer | Administrator-supplied approved JDBC driver | Generic JDBC only | No real-engine evidence | Experimental | Add ASE dialect/catalog adapter, transaction/batch limits, text/image, identity and bulk-copy coverage |
| Mainframe files through copybook/Zowe | z/OS and z/OSMF/Zowe versions captured per run | HTTPS/Zowe plus copybook codecs; not JDBC | Copybook parsing, fixed/variable record codecs, masking/generation and Zowe transport UI | Automated parser/codec tests; no retained client TLS/transport acceptance run | Implemented | Validate TLS chain, RACF permissions, record formats, EBCDIC code pages, restart, large files and transfer integrity |

Version selection is a test plan, not a support promise. End-of-life lanes are accepted only through an
explicit exception signed by Product, Security, Support, and the customer; they cannot become generally
certified.

## Recommended execution order

1. PostgreSQL 16 or 17 as the reference source/target lane and regression benchmark.
2. Db2 LUW 11.5 as the first customer-driven banking lane.
3. SQL Server 2022 and Oracle 19c as the next enterprise target lanes.
4. MySQL 8.4 and MariaDB 11.4 as separate products and separate evidence runs.
5. Db2 for z/OS 13 with a client or IBM-hosted lab because DDF, RACF, function levels, APARs, and utilities
   cannot be represented honestly by a local container.
6. Snowflake after the JDBC and SnowSQL client versions, authentication model, stage design, and cost envelope
   are pinned.
7. Generic JDBC engines only when a customer opportunity supplies a supported lab and a deliberately bounded
   feature scope.

## Capability scope recorded per lane

Certification is directional. A database can be certified as a read-only source without being certified as
a write target. Each evidence record must mark every item `PASS`, `FAIL`, `NOT TESTED`, or `NOT APPLICABLE`:

| Area | Required evidence |
| --- | --- |
| Connection and security | Password and approved enterprise auth path; TLS with hostname verification; trust-store/wallet handling; least-privilege failure; connect/read timeout; secret redaction |
| Catalog and discovery | Catalog/schema/table browse; 4,000-table bounded behavior; views; quoted/mixed-case names; generated columns; selected PII type and table scope; permission failures |
| Data types | Integer/decimal boundaries; floating values; boolean; date/time/time zone; UUID; binary; maximum BLOB/CLOB/NCLOB/XML/JSON; null/default; non-ASCII and legacy code page |
| Schema complexity | No-PK tables; composite PK/FK; self-reference; circular RI; cross-schema RI; unique constraints; partitions; identity/sequence; case collisions |
| Masking | Determinism; format and referential consistency; direct/hash lookup; scripts; row context; null handling; reject evidence; no clear PII in logs |
| Subsetting | Driver filters; parent/child traversal; composite and circular relationships; custom PK/FK; row limits; orphan validation; cross-source execution where applicable |
| Synthetic generation | CHECK/unique/FK enforcement; parent-first order; multi-target consistency; large streaming run; per-table/partition progress; cancel; retry; seed replay |
| Provisioning | Insert/append/update/replace; delete/truncate behavior; transaction boundaries; in-place mode; native loader; JDBC fallback; target validation; partial-failure recovery |
| Operations | Concurrent jobs; restart after process loss; shared cancellation; audit/lineage; metrics; bounded errors; throughput and memory evidence at agreed scale |

## Required schema pack

Every vendor lab must include, at minimum:

1. Clean banking tables with parent/child/grandchild relationships.
2. Composite keys, circular keys, self-references, missing PKs, and cross-schema relationships.
3. Reserved words, spaces, mixed case, long identifiers, generated columns, defaults, and identities.
4. Precision/scale boundaries, maximum strings, Unicode, time zones, malformed input, BLOB/CLOB/XML/JSON,
   and vendor-specific complex types.
5. Partitioned tables and at least one table large enough to force streaming and multiple batches.
6. Restricted source and target users to prove the least-privilege contract and error messages.

The clean demo schema alone is insufficient for certification.

## Promotion rules

1. Create an evidence file from `connector-certification-evidence/TEMPLATE.md` before the run.
2. Record immutable ForgeTDM commit/image digest, server edition and full patch, JDBC/native-client version,
   OS/container, encoding/collation, topology, authentication, TLS, and enabled capabilities.
3. Attach machine-readable test output, connector Diagnose export, benchmark results, rejected-row report,
   relevant sanitized logs, and recovery evidence. Never attach client data or secrets.
4. Quality Engineering signs functional and regression results. Security signs connection/TLS/secret handling.
   Product signs feature scope. Support accepts ownership and known limitations.
5. Update only the exact tested lane to `Lab validated`. Production certification additionally requires scale,
   fail/recovery, security, documentation, and support gates to pass.
6. Any server-major, driver-major, native-client-major, Java-major, or material dialect/loader change returns the
   lane to `Implemented` until regression evidence is refreshed. Patch upgrades require a documented risk review
   and targeted rerun.
7. A customer qualification never broadens the general product claim; it adds the customer's schema,
   infrastructure and acceptance evidence to an already precise lane.

## Control database boundary

This matrix covers source and target connectors. ForgeTDM's configuration/control database remains
PostgreSQL. H2 is for development/tests. Db2 as an alternative control database is a separate roadmap item
and must not be inferred from Db2 source/target implementation.

## Vendor lifecycle references

Version candidates must be rechecked at each review against the vendor's current policy:

- PostgreSQL versioning: https://www.postgresql.org/support/versioning/
- Oracle lifetime support: https://www.oracle.com/assets/lsp-tech-chart-069290.pdf
- SQL Server lifecycle: https://learn.microsoft.com/en-us/lifecycle/products/sql-server-2022
- IBM Db2 LUW end of support: https://www.ibm.com/docs/en/db2/11.5.x?topic=database-db2-eos-dates
- IBM Db2 13 for z/OS documentation: https://www.ibm.com/docs/en/db2-for-zos/13.0.0
- MySQL LTS model: https://dev.mysql.com/doc/refman/8.4/en/mysql-releases.html
- MariaDB maintenance policy: https://mariadb.org/about/#maintenance-policy
