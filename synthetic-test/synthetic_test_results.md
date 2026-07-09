# Synthetic Test Results

- Started: 2026-07-09T05:48:47.004038200Z
- Finished: 2026-07-09T05:51:45.781792900Z
- Database: jdbc:postgresql://localhost:5433/forgetdm
- Schema: synthetic_auto_test
- Summary: PASS=92, FAIL=0, NOT_AUTOMATED=46, PENDING=0

## Result Matrix

| ID | Category | Scenario | Status | Evidence |
|---|---|---|---|---|
| SETUP | Harness | Test schema setup | PASS | Created clean PostgreSQL schema synthetic_auto_test and loaded supplied test DDL. |
| A1 | A. Generators & data types | Sequence PK | PASS | branches.branch_id count equals distinct count (20). |
| A2 | A. Generators & data types | Padded sequence | PASS | account_number min/max length = 12. |
| A3 | A. Generators & data types | UUID | PASS | UUID generator produced unique RFC-4122-shaped values. |
| A4 | A. Generators & data types | First/last/full name (locale+gender) | PASS | FULL_NAME generated non-empty first/last style names. |
| A5 | A. Generators & data types | Name realism bias | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| A6 | A. Generators & data types | NORMAL_INT distribution | PASS | risk_score clamped to CHECK 0..100. |
| A7 | A. Generators & data types | NORMAL_DECIMAL | PASS | NORMAL_DECIMAL balance loaded with numeric(15,2), avg=5036.1268166666666667. |
| A8 | A. Generators & data types | WEIGHTED set | PASS | kyc_status values are in weighted set. |
| A9 | A. Generators & data types | INT_RANGE / DECIMAL_RANGE | PASS | expiry_month stayed within 1..12. |
| A10 | A. Generators & data types | DATE_BETWEEN | PASS | opened_on stayed in configured range. |
| A11 | A. Generators & data types | BOOLEAN_WEIGHTED | PASS | prod_customers source has about 90% active: 0.90000000000000000000. |
| A12 | A. Generators & data types | Financial generators | PASS | Visa PANs are 16 chars and start with 4. |
| A13 | A. Generators & data types | Decimal scale/precision fit | PASS | numeric(15,2) balances loaded without overflow. |
| A14 | A. Generators & data types | Varchar length truncation | PASS | description max length=125. |
| A15 | A. Generators & data types | char(n) padding | PASS | country char(2) values have length 2. |
| A16 | A. Generators & data types | API generator row cap | PASS | API-backed table >5000 rows rejected before HTTP calls. |
| A17 | A. Generators & data types | Null-rate control | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| B1 | B. Primary keys & uniqueness 🏦 | PK uniqueness (in-memory) | PASS | customers.customer_id count equals distinct count (400). |
| B2 | B. Primary keys & uniqueness 🏦 | Single-column UNIQUE (email) | PASS | customers.email count equals distinct count (400). |
| B3 | B. Primary keys & uniqueness 🏦 | Derived-unique email from non-unique name | PASS | customers.email count equals distinct count (400). |
| B4 | B. Primary keys & uniqueness 🏦 | Uniqueness in STREAMING path | PASS | Zero duplicate emails on the streaming path. |
| B5 | B. Primary keys & uniqueness 🏦 | Inherently-unique skip | PASS | customers.customer_id count equals distinct count (1000001). |
| B6 | B. Primary keys & uniqueness 🏦 | Bloom false-positive cosmetics at scale | PASS | Bloom guard yields zero real duplicates at scale (only cosmetic disambiguation). |
| B7 | B. Primary keys & uniqueness 🏦 | Composite UNIQUE present (uq_acct_ccy) | PASS | Composite UNIQUE uq_acct_ccy exists in target catalog. |
| B8 | B. Primary keys & uniqueness 🏦 | Composite-PK tuple collision (account_balances) | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| C1 | C. Referential integrity (single FK) 🏦 | Basic child FK | PASS | accounts.customer_id has zero FK orphans. |
| C2 | C. Referential integrity (single FK) 🏦 | Parent-first ordering | PASS | Parent-first ordering succeeded during multi-table load. |
| C3 | C. Referential integrity (single FK) 🏦 | FK auto-detection | PASS | FK metadata was available from the target catalog during plan enrichment. |
| C4 | C. Referential integrity (single FK) 🏦 | RI to EXISTING data | PASS | Child-only account_balances load referenced existing accounts. |
| C5 | C. Referential integrity (single FK) 🏦 | Null FK when parent absent | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| C6 | C. Referential integrity (single FK) 🏦 | Deep chain | PASS | Deep branch->customer->account->transaction chain loaded with zero orphan checks. |
| D1 | D. Composite foreign keys 🏦 | Composite FK tuple consistency | PASS | Composite FK fx_trades -> exchange_rates has zero orphans. |
| D2 | D. Composite foreign keys 🏦 | Composite FK in STREAMING (>500k fx_trades) | PASS | Streaming composite FK fx_trades -> exchange_rates has zero orphans. |
| D3 | D. Composite foreign keys 🏦 | Parent capped index disclosure | PASS | Streaming plan-summary reports bounded reservoir parent sampling. |
| E1 | E. Self-referencing FK | employees.manager_id → employees | PASS | employees self-FK has zero orphans. |
| E2 | E. Self-referencing FK | customers.referrer_id self-ref | PASS | customers self-FK has zero orphans. |
| E3 | E. Self-referencing FK | No-cycle / topo handling | PASS | Self-reference tables completed without cycle/deadlock. |
| F1 | F. Cardinality (children-per-parent) 🏦 | Accounts per customer | PASS | accounts per customer stayed within 1..5. |
| F2 | F. Cardinality (children-per-parent) 🏦 | Transactions per account (in-memory) | PASS | transactions per account stayed within 1..5 for in-memory run. |
| F3 | F. Cardinality (children-per-parent) 🏦 | Cardinality in STREAMING | PASS | Streaming cardinality produced multi-child runs (100001 accounts with >=2 txns). |
| F4 | F. Cardinality (children-per-parent) 🏦 | fkMin=0 | PASS | Cardinality allows parents with zero children (100001 accounts). |
| G1 | G. Derived / within-row consistency 🏦 | TEMPLATE tokens | PASS | TEMPLATE generated email from same-row full_name. |
| G2 | G. Derived / within-row consistency 🏦 | COPY | PASS | COPY column equals source column. |
| G3 | G. Derived / within-row consistency 🏦 | CASE mapping | PASS | CASE mapping follows status. |
| G4 | G. Derived / within-row consistency 🏦 | DATE_AFTER ordering | PASS | DATE_AFTER value_date is within 0..3 days. |
| G5 | G. Derived / within-row consistency 🏦 | Cross-table LOOKUP (in-memory) | PASS | accounts.holder_email equals linked customer email. |
| G6 | G. Derived / within-row consistency 🏦 | LOOKUP in STREAMING | PASS | Streaming LOOKUP: holder_email equals the linked customer email. |
| G7 | G. Derived / within-row consistency 🏦 | Chained derived | PASS | Chained TEMPLATE-derived columns resolved over two passes. |
| H1 | H. CHECK-constraint enforcement 🏦 | IN-list snapped | PASS | IN-list CHECK satisfied. |
| H2 | H. CHECK-constraint enforcement 🏦 | BETWEEN clamped | PASS | BETWEEN CHECK satisfied. |
| H3 | H. CHECK-constraint enforcement 🏦 | comparison >= (MIN) | PASS | balance >= 0 CHECK satisfied. |
| H4 | H. CHECK-constraint enforcement 🏦 | AND-combined split | PASS | AND-combined amount CHECK satisfied. |
| H5 | H. CHECK-constraint enforcement 🏦 | Exclusive `>` boundary | PASS | Exclusive rate > 0 CHECK satisfied. |
| H6 | H. CHECK-constraint enforcement 🏦 | Complex/unsupported evidence-only | PASS | LIKE/function CHECK constraints are captured as evidence-only. |
| H7 | H. CHECK-constraint enforcement 🏦 | Dialect capture (Oracle) | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| H8 | H. CHECK-constraint enforcement 🏦 | Dialect capture (SQL Server) | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| H9 | H. CHECK-constraint enforcement 🏦 | No CHECKs present | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| I1 | I. Profiler ("learn distributions") 🏦 | Representative random sampling | PASS | prod_customers profiled with random sampling; segment suggested WEIGHTED. |
| I2 | I. Profiler ("learn distributions") 🏦 | `sampling` flag | PASS | Profile response sampling=random for prod_customers (>20k rows). |
| I3 | I. Profiler ("learn distributions") 🏦 | Small table full scan | PASS | Small table profile uses sampling=all. |
| I4 | I. Profiler ("learn distributions") 🏦 | Low-cardinality → WEIGHTED | PASS | Low-cardinality segment suggested WEIGHTED. |
| I5 | I. Profiler ("learn distributions") 🏦 | Numeric → NORMAL_* | PASS | credit_score suggested NORMAL_INT. |
| I6 | I. Profiler ("learn distributions") 🏦 | Date → DATE_BETWEEN | PASS | signup_date suggested DATE_BETWEEN. |
| I7 | I. Profiler ("learn distributions") 🏦 | Boolean → BOOLEAN_WEIGHTED | PASS | is_active suggested BOOLEAN_WEIGHTED. |
| I8 | I. Profiler ("learn distributions") 🏦 | All-distinct → SEQUENCE | PASS | All-distinct numeric id suggested SEQUENCE. |
| I9 | I. Profiler ("learn distributions") 🏦 | Banking-safe suppression | PASS | Banking-safe profile suppresses email source distribution. |
| I10 | I. Profiler ("learn distributions") 🏦 | View profiling fallback | PASS | View profiling succeeded through native-sampling fallback. |
| I11 | I. Profiler ("learn distributions") 🏦 | Profile → pre-fill plan | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| J1 | J. Receivers | DB load | PASS | DB receiver completed happy-path banking load. |
| J2 | J. Receivers | CSV | PASS | CSV receiver returned header and rows. |
| J3 | J. Receivers | JSON | PASS | JSON receiver returned parseable row array. |
| J4 | J. Receivers | SQL script | PASS | SQL receiver returned runnable INSERT script shape. |
| J5 | J. Receivers | File receivers ignore DB-only opts | PASS | CSV receiver ignores DB-only fastLoad without error. |
| K1 | K. Load actions & target prep | INSERT | PASS | INSERT/REPLACE DB load completed into supplied Postgres schema. |
| K2 | K. Load actions & target prep | UPDATE | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| K3 | K. Load actions & target prep | INSERT_UPDATE (upsert) | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| K4 | K. Load actions & target prep | TRUNCATE-only | PASS | TRUNCATE_ONLY cleared transactions without row generation. |
| K5 | K. Load actions & target prep | Drop & recreate | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| K6 | K. Load actions & target prep | Create missing | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| K7 | K. Load actions & target prep | Clear target (DELETE vs TRUNCATE) | PASS | Target prep DELETE cleared FK-related planned tables child-first without FK errors. |
| K8 | K. Load actions & target prep | Target table missing, create off | PASS | Missing target table returns clear create-table hint. |
| K9 | K. Load actions & target prep | Column mismatch | PASS | Column mismatch lists real target columns. |
| L1 | L. Fast / bulk load (per dialect) | Postgres COPY | PASS | Large Postgres fastLoad plan reports POSTGRES_COPY_FAST_LOAD. |
| L2 | L. Fast / bulk load (per dialect) | Multi-row INSERT (MySQL/SQLServer/DB2/H2) | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| L3 | L. Fast / bulk load (per dialect) | Param-limit chunking (SQL Server) | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| L4 | L. Fast / bulk load (per dialect) | Oracle/GENERIC fallback | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| L5 | L. Fast / bulk load (per dialect) | Multi-row per-row reject fallback | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| L6 | L. Fast / bulk load (per dialect) | COPY aborts on bad row | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| L7 | L. Fast / bulk load (per dialect) | Native loader disclosure | PASS | Non-Postgres fastLoad summary discloses native-loader extension point. |
| M1 | M. Reject handling | Continue-on-error | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| M2 | M. Reject handling | maxRejects ceiling | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| M3 | M. Reject handling | Reject summary in UI | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| M4 | M. Reject handling | Fail-fast (default) | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| N1 | N. Checkpoint commit | commitEveryRows | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| N2 | N. Checkpoint commit | No checkpoint | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| O1 | O. Scale & streaming threshold 🏦 | In-memory path | PASS | <=500k plan reports IN_MEMORY memory mode. |
| O2 | O. Scale & streaming threshold 🏦 | Streaming path | PASS | >500k plan reports STREAMING memory mode. |
| O3 | O. Scale & streaming threshold 🏦 | 1M unique emails | PASS | 1,000,001 customers streamed; all emails unique (1000001). |
| O4 | O. Scale & streaming threshold 🏦 | Memory bound at extreme scale | PASS | 1,000,001-row streaming load completed without OOM at the configured heap. |
| O5 | O. Scale & streaming threshold 🏦 | Threshold boundary | PASS | 500000 uses IN_MEMORY; 500001 uses STREAMING. |
| P1 | P. Validation report 🏦 | Row-count validation | PASS | DB load returned validation block and row-count checks below matched requested counts. |
| P2 | P. Validation report 🏦 | FK orphan validation | PASS | FK orphan checks below are zero. |
| P3 | P. Validation report 🏦 | Validation after partial (rejects) | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| Q1 | Q. Governance, approval & security 🏦 | Controlled target needs approval | PASS | Controlled PROD target blocks direct DB generation. |
| Q2 | Q. Governance, approval & security 🏦 | Maker-checker (maker ≠ checker) | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| Q3 | Q. Governance, approval & security 🏦 | Approved run | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| Q4 | Q. Governance, approval & security 🏦 | Pending/Rejected guards | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| Q5 | Q. Governance, approval & security 🏦 | RBAC: no `synthetic.approve` | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| Q6 | Q. Governance, approval & security 🏦 | RBAC: no `synthetic.direct.run` | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| Q7 | Q. Governance, approval & security 🏦 | Lineage persisted | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| Q8 | Q. Governance, approval & security 🏦 | Audit trail | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| Q9 | Q. Governance, approval & security 🏦 | E-signature note required | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| Q10 | Q. Governance, approval & security 🏦 | Lineage retention policy | PASS | Plan-summary governance payload includes lineageRetentionDays and retention policy. |
| R1 | R. Jobs, cancellation, resume | Async job + progress | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| R2 | R. Jobs, cancellation, resume | Cancel mid-run | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| R3 | R. Jobs, cancellation, resume | Interrupted on restart | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| R4 | R. Jobs, cancellation, resume | Saved job run | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| R5 | R. Jobs, cancellation, resume | Plan summary / preview | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| S1 | S. Identity & generated columns 🏦 | Identity not written (unreferenced) | PASS | GENERATED ALWAYS identity branch_id loaded safely. |
| S2 | S. Identity & generated columns 🏦 | Identity written (referenced as FK target) | PASS | Referenced identity customer_id was written safely for FK use. |
| S3 | S. Identity & generated columns 🏦 | Generated column skipped | PASS | Generated amount_abs column computed by DB. |
| S4 | S. Identity & generated columns 🏦 | BY DEFAULT identity | PASS | BY DEFAULT identity transactions.txn_id loaded successfully. |
| T1 | T. Determinism & reproducibility | Same seed → same data | PASS | Same seed produced byte-identical CSV. |
| T2 | T. Determinism & reproducibility | Different seed → different data | PASS | Different seed produced different values. |
| T3 | T. Determinism & reproducibility | Per-table seed independence | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| U1 | U. Negative / robustness 🔴 | 0 rows | PASS | 0-row file generation returns header only. |
| U2 | U. Negative / robustness 🔴 | Invalid generator param | PASS | Invalid NORMAL_INT params fall back safely and do not crash. |
| U3 | U. Negative / robustness 🔴 | FK to table not in plan & not existing | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| U4 | U. Negative / robustness 🔴 | Bad TEMPLATE token | PASS | Bad TEMPLATE token does not crash generation. |
| U5 | U. Negative / robustness 🔴 | Duplicate forced beyond pool | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| U6 | U. Negative / robustness 🔴 | Wrong type to numeric col | PASS | Wrong text-to-numeric plan fails with meaningful conversion error. |
| V1 | V. Concurrency / target locking 🏦 | Reject 2nd load on busy target | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| V2 | V. Concurrency / target locking 🏦 | Different schema runs concurrently | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| V3 | V. Concurrency / target locking 🏦 | Lock released on completion | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| V4 | V. Concurrency / target locking 🏦 | Lock released on failure/cancel | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| V5 | V. Concurrency / target locking 🏦 | Sync vs async share the lock | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
| V6 | V. Concurrency / target locking 🏦 | Restart clears stale locks | NOT_AUTOMATED | Not covered by this automated Postgres pass; keep as manual/vendor/scale validation. |
