# Connector certification evidence

Create one immutable record per tested lane from `TEMPLATE.md`. Use this filename convention:

```text
ENGINE_SERVER-VERSION_DRIVER-VERSION_YYYY-MM-DD.md
```

Examples:

```text
POSTGRESQL_16.4_42.7.4_2026-07-20.md
DB2-LUW_11.5.9_JCC-11.5.9.0_2026-08-03.md
```

Keep sanitized reports or hashes of reports beside the record. Do not store passwords, connection strings
containing secrets, client data, PII, private certificates, wallets, or proprietary DDL in Git.

A copied template is not evidence. The record becomes valid only after all required owners sign it and the
referenced artifacts are available to the release reviewers.
