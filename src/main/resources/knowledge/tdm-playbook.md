# ForgeTDM TDM Playbook

Curated Test Data Management scenarios. Each entry: the scenario, the recommended approach, and how to do it
in ForgeTDM. The assistant retrieves the most relevant entries to advise users. Entries are separated by `---`.
Format per entry: a `## Title` line, an optional `keywords:` line, then the body.

---
## Masked value collides on a unique column (e.g. email, username, account no.)
keywords: unique constraint violation, duplicate key, collision, email, format preserving, FPE, uniqueness, seedlist, substitution
Scenario: a masking job fails with a duplicate-key / unique-constraint error, or you need masked values to stay one-to-one on a UNIQUE column.
Approach: substitution from a name/dictionary list is many-to-few by design and WILL collide past a few thousand rows (birthday paradox). For a column with a UNIQUE constraint, use a bijective, format-preserving transform so distinct inputs map to distinct outputs while staying deterministic.
In ForgeTDM: set the column's mask function to FORMAT_PRESERVE (FPE) instead of a seedlist mask like FULL_NAME/EMAIL name-substitution. FPE is deterministic AND unique (no XREF/storage table needed). If you must keep name-style emails, expand the dictionary but accept collisions, or add a per-row unique suffix. The Job Monitor's conflict panel shows the failed vs conflicting record to confirm whether it's an in-load masking collision or a pre-existing target row (then switch the load to REPLACE).

---
## Subset a large production database for a smaller test environment
keywords: subsetting, subset, referential integrity, driver table, slice, smaller copy, size reduction, RI, foreign key
Scenario: production is too big to copy whole; you need a small, referentially-complete slice for QA/dev.
Approach: pick a driver (anchor) table and a filter, then follow foreign keys outward so every child row's parents (and required lookups) come along — a referentially-correct subset, not a random sample.
In ForgeTDM: use a DataScope with a driver table + filter (e.g. customers created this year), include the related table profiles, and provision with SUBSET_MASK. ForgeTDM walks the FK graph in dependency order (parents first) so RI holds. Combine with masking in the same pass so the subset is also compliant.

---
## Synthetic data vs. masking — which to use when
keywords: synthetic vs masking, generate vs mask, no production data, net-new data, choose approach, greenfield
Scenario: deciding whether to mask a copy of production or generate synthetic data.
Approach: MASK production when you need realistic shape, volume and edge cases and you have a source to copy (fastest path to representative data, preserves distributions/RI). GENERATE synthetic when there is no usable source, when data can't leave a boundary at all, for net-new features/tables, for negative/rare cases, or to scale beyond production volume.
In ForgeTDM: masking = DataScope + policy + provisioning job; synthetic = the Synthetic Generation designer (typed generators, FK-aware, receivers DB/CSV/JSON/SQL). You can also top up a masked subset with synthetic rows.

---
## Discover and classify PII before masking
keywords: pii discovery, sensitive data discovery, classification, find pii, scan, data profiling, tag columns
Scenario: you don't know where sensitive data lives, or need evidence of coverage for auditors.
Approach: scan sources to classify columns by PII type (name, email, SSN, card, DOB…), review/approve findings, then derive a masking policy from the approved classifications so nothing sensitive is missed.
In ForgeTDM: run PII Discovery on a source/schema, review results (approve/reject, tweak suggested mask), then Generate Policy from approved findings. The DataScope PII-coverage check flags approved PII columns in scope that still have no masking before you provision.

---
## Meet GDPR / HIPAA / PCI-DSS with masked test data
keywords: compliance, gdpr, hipaa, pci dss, pci, regulation, privacy, audit, deidentification, phi, pii
Scenario: non-production must not contain real personal/health/cardholder data.
Approach: discover all in-scope sensitive fields, mask them irreversibly and deterministically (so referential integrity and analytics survive), keep an audit trail, and gate promotion to non-prod. Prefer irreversible masking over reversible tokenization for test data.
In ForgeTDM: PII Discovery → policy → deterministic HMAC masking (no reversible XREF), with the maker-checker approval gate and audit log for evidence. Use FORMAT_PRESERVE for cards/IDs that need validity, and NULL/redact for fields not needed in test.

---
## Keep masked values consistent across tables and systems (FK-safe)
keywords: deterministic masking, referential integrity, consistent, same value everywhere, foreign key, cross system, join preserving
Scenario: a customer id / SSN / email is masked in many tables (and systems) and joins must still work after masking.
Approach: use deterministic masking — the same input always maps to the same masked output — driven by a shared secret/seed, so foreign keys and cross-system joins remain valid without storing a mapping table.
In ForgeTDM: masking is deterministic by default (HMAC with canonical salts) and FK-consistent across tables; use the same masking seed/policy everywhere. For split fields (phone/SSN/DOB across columns) the split-field masks compose to one consistent value.

---
## In-place masking vs. mask-and-copy — safety and rollback
keywords: in place masking, mask in place, load action, replace, rollback, failure, transaction, destructive
Scenario: choosing how to load masked data and worrying about what happens if a job fails.
Approach: mask-and-copy (source→target) is safest and re-runnable. In-place masking mutates the source itself — only on a non-production copy. In-place on a keyed table updates by key in committed chunks (no row loss; a failure leaves a mixed state, re-run cleanly); keyless in-place swaps contents in one transaction that rolls back on failure.
In ForgeTDM: choose the load action (REPLACE/INSERT/UPDATE/INSERT_UPDATE/IN_PLACE). In-place + subset warns before deleting non-matching rows. Never point in-place at your only copy — clone or snapshot first.

---
## Provision test data on demand from CI/CD
keywords: ci cd, pipeline, jenkins, github actions, gitlab, automation, on demand, refresh per build, api, scheduler, runner
Scenario: each build/test run needs fresh, reproducible test data automatically.
Approach: make provisioning a repeatable, parameterized job you can trigger from a scheduler or your CI runner, with deterministic (seeded) output so tests are stable build-to-build.
In ForgeTDM: save the provision design as a DataScope Saved Job, then schedule it (in-app cron) or download its PowerShell/Bash runner to call from Jenkins/GitHub Actions/cron. Every action is also a REST API call. Seeded masking/generation makes the data identical run-to-run. (Full-lifecycle Pipelines are on the roadmap.)

---
## Fast, space-efficient environment copies (virtualization / snapshots)
keywords: virtualization, snapshot, vdb, delphix, clone, thin clone, fast copy, bookmark, rewind, refresh environment
Scenario: teams each need their own full-size copy, but full physical copies are slow and expensive.
Approach: use data virtualization — take a snapshot of a source and spin up thin, copy-on-write virtual databases that share blocks, so each is near-instant and tiny. Bookmark and rewind to known states.
In ForgeTDM: Data Virtualization creates dSource snapshots and provisions VDBs (register them as data sources), with bookmarks, refresh and rewind — Delphix-style.

---
## Repeatably refresh a QA / UAT environment
keywords: refresh environment, qa, uat, reset, nightly refresh, repeatable, known state, reload
Scenario: QA/UAT drifts and needs periodic reset to a clean, masked, known-good dataset.
Approach: define the refresh once (subset + mask + load REPLACE), make it deterministic, and schedule it. Use a VDB rewind/refresh where instant reset matters.
In ForgeTDM: a DataScope Saved Job on a schedule (e.g. nightly) with load action REPLACE, or a VDB refresh/rewind for instant reset. Governance/approval still applies for governed sources.

---
## Mask mainframe / COBOL copybook data
keywords: mainframe, copybook, cobol, ebcdic, vsam, fixed width, zos, legacy, packed decimal
Scenario: sensitive data lives in mainframe fixed-width/copybook files, not a relational DB.
Approach: parse the copybook layout, apply field-level masks respecting field types/positions (including packed/EBCDIC), and write back in the same layout.
In ForgeTDM: use the Mainframe / copybook masking feature — register the copybook, map fields to mask functions, and run masking that preserves the record layout.

---
## Governance: require approval before provisioning real data
keywords: governance, approval, maker checker, sign off, prod, guardrail, block unmasked, control
Scenario: you must prevent unmasked or unauthorized copies of production reaching non-prod.
Approach: enforce a maker-checker gate — a different user must approve jobs that touch governed/prod sources or that leave approved PII unmasked — and hard-block unmasked prod copies outright.
In ForgeTDM: provisioning governance requires approval when the source is PROD (configurable: prod-only/always/never), optionally when scope has unmasked approved PII, and can block an unmasked PROD copy entirely. Actions are audited.

---
## Age / shift dates and support legacy date formats (Optim-style)
keywords: age function, date shift, date masking, julian date, date format, dob, birthdate, aging, optim
Scenario: shift dates consistently (e.g. age everyone by an interval) while preserving format, including mainframe/Julian formats.
Approach: use an AGE/date-shift that moves a date by a signed interval and re-emits it in the original (or chosen) format; support Julian and packed formats for legacy data.
In ForgeTDM: the AGE mask takes a shift spec (e.g. +1y -2m +10d) and a date-format dropdown (ISO, dd/MM/yyyy, Julian yyyyDDD/yyDDD/CYYDDD, etc.). DATE_SHIFT and DOB_AGE_BAND cover randomized shifts and age banding.

---
## Polymorphic column — value type depends on an indicator column
keywords: polymorphic, indicator column, conditional masking, type depends, ref type, mixed content, by indicator
Scenario: one column holds different data types depending on another column (e.g. contact value is a phone when type=P, email when type=E).
Approach: choose the mask per row based on the indicator column so each value is masked correctly for its actual type.
In ForgeTDM: use the BY_INDICATOR mask (point it at the indicator column with a P=PHONE|E=EMAIL|*=FORMAT_PRESERVE map). For generation, CASE_GEN runs a different generator per indicator value.

---
## Split field masked across multiple columns (phone/SSN/DOB parts)
keywords: split field, phone area exchange line, ssn parts, dob day month year, multiple columns, compose, split masking
Scenario: one logical value is stored across several columns (area/exchange/line, or dd/mm/yyyy) and all parts must mask consistently.
Approach: compose the parts, mask the whole once deterministically, then write each column's slice back — so the parts stay consistent with each other and with any combined column.
In ForgeTDM: use the split-field masks (PHONE_SPLIT / SSN_SPLIT / DATE_SPLIT), naming this column and the sibling columns in order.

---
## Reference/lookup data domains (K2View/GenRocket-style value lists)
keywords: value lists, reference data, lookup, domains, valid values, allowed values, controlled vocabulary, seed list
Scenario: masked or generated values must come from a controlled set of valid business values (e.g. real product codes, currencies, branch names).
Approach: maintain named value lists and bind masks/generators to them so outputs are always valid domain values.
In ForgeTDM: create Value Lists (reference data domains) and use them in masking (HASH_LOV) and synthetic generation, so values stay valid and consistent.

---
## Custom / edge-case masking not covered out of the box
keywords: custom masking, user defined, lua, script, exit, special logic, bespoke, complex rule
Scenario: a masking requirement is too specific for the built-in functions.
Approach: write a small deterministic script that computes the masked value from the input and row context, sandboxed and reusable across columns.
In ForgeTDM: use the SCRIPT mask with a user-defined Lua script (Masking Scripts). Scripts get value + row and forge.* helpers (hash, pick, fpe, mask, masked); they're syntax-checked on save and selectable from any column. Sample scripts are provided.

---
## Generate very large synthetic datasets (100M+ rows) fast
keywords: scale, large volume, 100 million, billions, performance, parallel, partition, g-partition, throughput, bulk load
Scenario: you need hundreds of millions of synthetic rows loaded quickly.
Approach: generate and load in parallel partitions with deterministic seeding, keep FK pools bounded (reservoir sampling) and enforce uniqueness at scale (bloom filters), and use fast bulk-load paths.
In ForgeTDM: synthetic generation supports GenRocket G-Partition-style parallel partitioned generation and bulk DB loading; tune batch size and workers. Deterministic seeds keep partitions reproducible.
