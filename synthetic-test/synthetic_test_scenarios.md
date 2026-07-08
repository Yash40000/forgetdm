# ForgeTDM — Synthetic Data Generation Test Scenarios

A practical, end-to-end test pack for the synthetic generator. Run
`synthetic_test_schema_postgres.sql` first, then work through the scenarios.
Each scenario lists **what to configure**, the **expected result**, and **how to verify**
(SQL or the validation/lineage panels). "Pass" = expected holds AND the post-load
validation report shows 0 FK orphans.

Legend: 🟢 happy path · 🟡 edge/limit · 🔴 negative (should fail cleanly) · 🏦 banking-critical

---

## A. Generators & data types

| # | Scenario | Configure | Expected | Verify |
|---|----------|-----------|----------|--------|
| A1 🟢 | Sequence PK | `branches.branch_id` = SEQUENCE | Monotonic, unique, no gaps in plan order | `SELECT count(*)=count(DISTINCT branch_id) FROM branches;` |
| A2 🟢 | Padded sequence | account_number = PADDED_SEQUENCE(12) | 12-char zero-padded, unique | `SELECT min(length(account_number)), max(length(account_number)) FROM accounts;` |
| A3 🟢 | UUID | any varchar col = UUID | RFC-4122 unique values | regex check, all distinct |
| A4 🟢 | First/last/full name (locale+gender) | customers.full_name = FULL_NAME(locale=US, gender=ANY) | Realistic names; mostly real-name pool | eyeball; `SELECT full_name FROM customers LIMIT 50;` |
| A5 🟡 | Name realism bias | generate 1000 names | ~70% from real pool, rest plausible (no "Brookfisherland" triple-stacks) | manual review |
| A6 🟢 | NORMAL_INT distribution | risk_score = NORMAL_INT(mean=50, sd=15) | Bell-shaped, clamped to CHECK 0..100 | `SELECT avg(risk_score), stddev(risk_score) FROM customers;` |
| A7 🟢 | NORMAL_DECIMAL | balance = NORMAL_DECIMAL(5000, 2000) | mean≈5000; scale=2 honored | `SELECT avg(balance) FROM accounts;` |
| A8 🟢 | WEIGHTED set | kyc_status = WEIGHTED(`VERIFIED:70|PENDING:20|REJECTED:7|EXPIRED:3`) | Proportions match weights ±sampling | `SELECT kyc_status, count(*) FROM customers GROUP BY 1;` |
| A9 🟢 | INT_RANGE / DECIMAL_RANGE | expiry_month = INT_RANGE(1,12) | Within range inclusive | `SELECT min(expiry_month),max(expiry_month) FROM cards;` |
| A10 🟢 | DATE_BETWEEN | opened_on = DATE_BETWEEN(2018-01-01, 2024-12-31) | Within range | `SELECT min(opened_on),max(opened_on) FROM accounts;` |
| A11 🟢 | BOOLEAN_WEIGHTED | is_active = BOOLEAN_WEIGHTED(90) | ~90% true | `SELECT avg((is_active)::int) FROM prod_customers;` |
| A12 🟢 | Financial generators | pan=CREDIT_CARD_VISA, cards | Luhn-plausible PAN, correct length | length=16/19, prefix check |
| A13 🟡 | Decimal scale/precision fit | balance numeric(15,2) | Values fit precision; no overflow error | load succeeds |
| A14 🟡 | Varchar length truncation | description varchar(140) | Generated text fit to 140, no error | `SELECT max(length(description)) FROM transactions;` |
| A15 🟡 | char(n) padding | country char(2) | Exactly 2 chars | `SELECT DISTINCT length(country) FROM branches;` |
| A16 🔴 | API generator row cap | a column = API(...) with rows > 5000 | Rejected with clear message ("cap API-backed tables at 5000 rows") | error surfaced |
| A17 🟡 | Null-rate control | phone nullRate=0.2 | ~20% NULL | `SELECT avg((phone IS NULL)::int) FROM customers;` |

---

## B. Primary keys & uniqueness 🏦

| # | Scenario | Configure | Expected | Verify |
|---|----------|-----------|----------|--------|
| B1 🟢 | PK uniqueness (in-memory) | customers, 100k rows | No PK dup | `…GROUP BY customer_id HAVING count(*)>1` = 0 rows |
| B2 🏦 | Single-column UNIQUE (email) | email generated, 100k | No duplicate emails | `…GROUP BY email HAVING count(*)>1` = 0 |
| B3 🏦 | Derived-unique email from non-unique name | full_name not unique; email = TEMPLATE `${full_name:slug}@bank.test`, email is UNIQUE | All emails unique; collisions disambiguated as `name+7@bank.test` (still valid email) | uniqueness query = 0; spot-check disambiguated values |
| B4 🏦 | Uniqueness in STREAMING path | customers/email, **>500k rows** (e.g. 1,000,000) | Still no duplicate email (Bloom guard) | uniqueness query = 0 |
| B5 🟡 | Inherently-unique skip | account_number = SEQUENCE, 1M rows | No perf/memory blow-up (guard skipped) | job completes; memory stable |
| B6 🟡 | Bloom false-positive cosmetics at scale | non-sequence UNIQUE col, 5M rows | A few extra disambiguation suffixes, but ZERO real duplicates | uniqueness query = 0 |
| B7 🟡 | Composite UNIQUE present (uq_acct_ccy) | accounts | Detected as evidence; not value-mutated; load still valid | no error; constraint listed in lineage |
| B8 🔴 | Composite-PK tuple collision (account_balances) | (account_id, as_of_date) random, small date range | Some tuples collide → handled by reject/continue-on-error (see M) | rejects recorded, not a crash |

---

## C. Referential integrity (single FK) 🏦

| # | Scenario | Configure | Expected | Verify |
|---|----------|-----------|----------|--------|
| C1 🟢 | Basic child FK | accounts.customer_id → customers | Every account points to a real customer | FK-orphan query = 0 |
| C2 🟢 | Parent-first ordering | generate branches+customers+accounts together | Parents created/loaded before children | no FK violation during load |
| C3 🏦 | FK auto-detection | leave fkTable/fkColumn blank; both tables in plan | Generator fills FK from DB metadata | accounts.customer_id valid |
| C4 🏦 | RI to EXISTING data | load customers first; then load only accounts (customers NOT regenerated) | accounts reference pre-existing customer keys | FK-orphan query = 0 |
| C5 🟡 | Null FK when parent absent | accounts in plan, customers NOT in plan, no existing rows | customer_id left NULL (if nullable) or clear error if NOT NULL | inspect / error message |
| C6 🟢 | Deep chain | branch→customer→account→transaction in one run | All four levels referentially intact | orphan queries across all = 0 |

---

## D. Composite foreign keys 🏦

| # | Scenario | Configure | Expected | Verify |
|---|----------|-----------|----------|--------|
| D1 🏦 | Composite FK tuple consistency | fx_trades(base_ccy,quote_ccy) → exchange_rates | (base,quote) always a real existing pair | composite-FK validity query = 0 |
| D2 🟡 | Composite FK in STREAMING (>500k fx_trades) | 1M fx_trades | Tuples still consistent via bounded tuple pool | validity query = 0 |
| D3 🟡 | Parent capped index disclosure | parent > 200k | `parentIndexCap` reported; children still valid | check result/lineage JSON |

---

## E. Self-referencing FK

| # | Scenario | Configure | Expected | Verify |
|---|----------|-----------|----------|--------|
| E1 🟢 | employees.manager_id → employees | 10k employees | manager_id is NULL (roots) or a valid emp_id; no deadlock | orphan self-join = 0 |
| E2 🟢 | customers.referrer_id self-ref | 100k customers | referrer_id valid or NULL | orphan self-join = 0 |
| E3 🟡 | No-cycle / topo handling | self-ref table only | Generation completes (no infinite wait) | job finishes |

---

## F. Cardinality (children-per-parent) 🏦

| # | Scenario | Configure | Expected | Verify |
|---|----------|-----------|----------|--------|
| F1 🏦 | Accounts per customer | accounts.customer_id fkMin=1, fkMax=5 | Each customer has 1–5 accounts (runs) | `SELECT customer_id,count(*) FROM accounts GROUP BY 1;` within [1,5] |
| F2 🏦 | Transactions per account (in-memory) | <500k transactions, fkMin=10,fkMax=50 | 10–50 txns per account | group-by counts in range |
| F3 🏦 | Cardinality in STREAMING | >500k transactions, fkMin=10,fkMax=50 | Same range honored on streaming path | group-by counts in range |
| F4 🟡 | fkMin=0 | optional children | Some parents have 0 children | counts include 0 |

---

## G. Derived / within-row consistency 🏦

| # | Scenario | Configure | Expected | Verify |
|---|----------|-----------|----------|--------|
| G1 🟢 | TEMPLATE tokens | email = TEMPLATE `${full_name:slug}@${rand:gmail.com\|bank.test}` | Email derived from same row's name | spot check rows |
| G2 🟢 | COPY | cardholder = COPY(full_name) | cardholder equals the row's name | equality check |
| G3 🟢 | CASE mapping | a band col = CASE(status, `OPEN:ACTIVE\|CLOSED:INACTIVE`) | Mapped values correct | group-by check |
| G4 🟢 | DATE_AFTER ordering | value_date = DATE_AFTER(txn_ts, 3) | value_date ≥ txn_ts, within 3 days | `SELECT count(*) FROM transactions WHERE value_date < txn_ts::date;` = 0 |
| G5 🏦 | Cross-table LOOKUP (in-memory) | accounts.holder_email = LOOKUP(email via customer_id) | holder_email equals the linked customer's email | LOOKUP-consistency query = 0 |
| G6 🏦 | LOOKUP in STREAMING | >500k accounts | LOOKUP resolves via bounded parent-row index | consistency query = 0 (within indexed parents) |
| G7 🟡 | Chained derived | TEMPLATE referencing another derived col | Resolves over two passes | spot check |

---

## H. CHECK-constraint enforcement 🏦

| # | Scenario | Configure | Expected | Verify |
|---|----------|-----------|----------|--------|
| H1 🏦 | IN-list snapped | customers.kyc_status any generator | Only allowed values produced | `…WHERE kyc_status NOT IN (...)` = 0 |
| H2 🏦 | BETWEEN clamped | risk_score | All within 0..100 | range query = 0 violations |
| H3 🏦 | comparison >= (MIN) | accounts.balance | balance ≥ 0 | `…WHERE balance<0` = 0 |
| H4 🏦 | AND-combined split | transactions.amount (`>0 AND <=1000000`) | both bounds honored (exclusive min) | `…WHERE amount<=0 OR amount>1000000` = 0 |
| H5 🟡 | Exclusive `>` boundary | rate > 0 | never exactly 0 | `…WHERE rate<=0` = 0 |
| H6 🟡 | Complex/unsupported evidence-only | ck_cust_email (LIKE), ck_cust_dob (function) | Captured as evidence, NOT enforced; rows may still satisfy naturally OR be rejected at load | constraint snapshot lists them as evidence-only |
| H7 🏦 | Dialect capture (Oracle) | run on Oracle target | CHECKs read from ALL_CONSTRAINTS.SEARCH_CONDITION | lineage shows captured checks |
| H8 🏦 | Dialect capture (SQL Server) | run on SQL Server target | CHECKs read from sys.check_constraints | lineage shows captured checks |
| H9 🟡 | No CHECKs present | a plain table | No enforcement, no error | normal load |

---

## I. Profiler ("learn distributions") 🏦

| # | Scenario | Configure | Expected | Verify |
|---|----------|-----------|----------|--------|
| I1 🏦 | Representative random sampling | Profile `prod_customers` (50k, first 5k skewed VIP/US) | Reported country/segment mix ≈ TRUE mix, NOT ~100% US | compare profile output to `GROUP BY country` |
| I2 🟢 | `sampling` flag | profile any table >20k | response shows `"sampling":"random"` | inspect profile JSON / toast says "(random sample)" |
| I3 🟢 | Small table full scan | profile a <20k table | `"sampling":"all"` | inspect JSON |
| I4 🟢 | Low-cardinality → WEIGHTED | segment column | suggested generator WEIGHTED with top values | profile output |
| I5 🟢 | Numeric → NORMAL_* | credit_score | suggested NORMAL_INT mean/sd | profile output |
| I6 🟢 | Date → DATE_BETWEEN | signup_date | suggested DATE_BETWEEN with observed range | profile output |
| I7 🟢 | Boolean → BOOLEAN_WEIGHTED | is_active | ~90% true suggested | profile output |
| I8 🟢 | All-distinct → SEQUENCE | id / email | suggested SEQUENCE / PADDED_SEQUENCE | profile output |
| I9 🏦 | Banking-safe suppression | email / pan / annual_income columns | Source top-values SUPPRESSED; safe generator + warning | `"sourceDistribution":"suppressed"` |
| I10 🟡 | View profiling fallback | profile a VIEW | Falls back to capped scan (no TABLESAMPLE error) | profile succeeds |
| I11 🟢 | Profile → pre-fill plan | "+ Add & learn distributions" | Plan columns pre-filled; FK columns kept intact | inspect wizard |

---

## J. Receivers

| # | Scenario | Configure | Expected | Verify |
|---|----------|-----------|----------|--------|
| J1 🟢 | DB load | receiver=DB | Rows in target tables | row counts |
| J2 🟢 | CSV | receiver=CSV | One CSV per table, header + rows | open files |
| J3 🟢 | JSON | receiver=JSON | Valid JSON arrays | parse files |
| J4 🟢 | SQL script | receiver=SQL | Runnable INSERT script, FK-ordered | run script on empty DB |
| J5 🟡 | File receivers ignore DB-only opts | CSV + fastLoad on | fastLoad ignored gracefully | no error |

---

## K. Load actions & target prep

| # | Scenario | Configure | Expected | Verify |
|---|----------|-----------|----------|--------|
| K1 🟢 | INSERT | action=INSERT | Rows appended | counts |
| K2 🟢 | UPDATE | action=UPDATE, keyColumns set | Existing rows updated, non-keys changed | before/after compare |
| K3 🟢 | INSERT_UPDATE (upsert) | action=INSERT_UPDATE | Existing updated, missing inserted | counts + spot check |
| K4 🟡 | TRUNCATE-only | truncateOnly | Target cleared, no rows generated | count=0 |
| K5 🟢 | Drop & recreate | prepMode=DROP_RECREATE | Table rebuilt from plan | table exists, fresh |
| K6 🟢 | Create missing | createTable=true | Missing table created from plan | table exists |
| K7 🟡 | Clear target (DELETE vs TRUNCATE) | clearsTarget on FK-related tables | Cleared child-first; no FK error | counts=0, no error |
| K8 🔴 | Target table missing, create off | INSERT into non-existent table | Clear error ("does not exist. Enable Create…") | error surfaced |
| K9 🔴 | Column mismatch | plan cols ≠ table cols | Clear error listing real columns | error surfaced |

---

## L. Fast / bulk load (per dialect)

| # | Scenario | Configure | Expected | Verify |
|---|----------|-----------|----------|--------|
| L1 🏦 | Postgres COPY | fastLoad on, PG target, >500k | COPY path used; fast; progress shows "COPY" | timing + result mode `POSTGRES_COPY_FAST_LOAD` |
| L2 🟢 | Multi-row INSERT (MySQL/SQLServer/DB2/H2) | fastLoad on, non-PG | "Bulk insert (multi-row)" path | progress message |
| L3 🟡 | Param-limit chunking (SQL Server) | wide table, fastLoad | Chunk size respects 2100-param limit | load succeeds, no driver error |
| L4 🟢 | Oracle/GENERIC fallback | fastLoad on, Oracle | Falls back to JDBC array batching | load succeeds |
| L5 🏦 | Multi-row per-row reject fallback | fastLoad + continueOnError + some bad rows (composite-PK collisions) | Bad chunk retried row-by-row; good rows land; bad rows → rejects | reject summary > 0, valid rows present |
| L6 🟡 | COPY aborts on bad row | PG COPY + a guaranteed-bad row | COPY reports the REAL underlying error (not "null") | error message meaningful |
| L7 🟢 | Native loader disclosure | non-PG fastLoad | result shows `NATIVE_LOADER_EXTENSION_POINT_NOT_ACTIVE` + portable path | result JSON |

---

## M. Reject handling

| # | Scenario | Configure | Expected | Verify |
|---|----------|-----------|----------|--------|
| M1 🏦 | Continue-on-error | continueOnError on, bad rows present | Bad rows skipped (savepoint), good rows committed | reject summary + counts |
| M2 🟡 | maxRejects ceiling | maxRejects=100, many bad rows | Job stops after ceiling with clear message | error + reject count ≈ ceiling |
| M3 🟢 | Reject summary in UI | any run with rejects | Reject count + sample rows shown | results panel |
| M4 🔴 | Fail-fast (default) | continueOnError off, one bad row | Whole load aborts/rolls back | clean failure, transactional |

---

## N. Checkpoint commit

| # | Scenario | Configure | Expected | Verify |
|---|----------|-----------|----------|--------|
| N1 🟡 | commitEveryRows | commitEveryRows=100000, 1M rows | Periodic commits; partial durability on crash | observe commit cadence |
| N2 🟢 | No checkpoint | commitEveryRows=0 | Single commit at end | atomic load |

---

## O. Scale & streaming threshold 🏦

| # | Scenario | Configure | Expected | Verify |
|---|----------|-----------|----------|--------|
| O1 🟢 | In-memory path | ≤500k rows total | In-memory generation; all advanced features exact | features behave |
| O2 🏦 | Streaming path | >500k rows | Streaming engine; cardinality/composite/LOOKUP/uniqueness all still apply | feature checks pass at scale |
| O3 🏦 | 1M unique emails | customers email UNIQUE, 1,000,000 | Zero duplicate emails, no OOM | uniqueness query = 0; heap stable |
| O4 🟡 | Memory bound at extreme scale | 5–10M transactions | Bounded memory (Bloom + capped pools); no OOM | monitor heap |
| O5 🟢 | Threshold boundary | exactly 500,000 vs 500,001 | Correct path selected | result `streamed` flag |

---

## P. Validation report 🏦

| # | Scenario | Configure | Expected | Verify |
|---|----------|-----------|----------|--------|
| P1 🏦 | Row-count validation | any DB load | Reported counts match requested | validation panel |
| P2 🏦 | FK orphan validation | any multi-table load | Orphan counts = 0 | validation panel |
| P3 🟡 | Validation after partial (rejects) | continueOnError run | Counts reflect actually-loaded rows | validation panel |

---

## Q. Governance, approval & security 🏦

| # | Scenario | Configure | Expected | Verify |
|---|----------|-----------|----------|--------|
| Q1 🏦 | Controlled target needs approval | target tagged `banking`/env PROD, ad-hoc generate to DB | Blocked: "require a saved job with approval" | error surfaced |
| Q2 🏦 | Maker-checker (maker ≠ checker) | owner requests approval, SAME user approves | Rejected: "requires a different user" | error surfaced |
| Q3 🏦 | Approved run | different user approves, then run | Run permitted | job runs |
| Q4 🏦 | Pending/Rejected guards | run while PENDING_APPROVAL or REJECTED | Blocked with clear message | error surfaced |
| Q5 🏦 | RBAC: no `synthetic.approve` | user without permission hits approval endpoint | 403 / access denied | AccessControlFilter blocks |
| Q6 🏦 | RBAC: no `synthetic.direct.run` | user without permission runs ad-hoc | denied | filter blocks |
| Q7 🏦 | Lineage persisted | any DB run | `synthetic_generation_lineage` row: plan hash, seed, constraint snapshot, approval, summary | `SELECT * FROM synthetic_generation_lineage ORDER BY created_at DESC LIMIT 1;` |
| Q8 🏦 | Audit trail | any consequential action | Audit ledger entries (STARTED/SAVED/APPROVED/RUN/…) | `/api/audit` or audit table |
| Q9 🟡 | E-signature note required | approve/reject without note | Note recorded with approval; surfaced in lineage | approval snapshot |
| Q10 🟡 | Lineage retention policy | check governance report | retentionDays + retainUntil present | result/lineage JSON |

---

## R. Jobs, cancellation, resume

| # | Scenario | Configure | Expected | Verify |
|---|----------|-----------|----------|--------|
| R1 🟢 | Async job + progress | generate/start | Progress %, stage, row counts update | jobs panel |
| R2 🟢 | Cancel mid-run | cancel a long job | Stops promptly; partial/rolled-back per checkpoint | job status CANCELLED |
| R3 🟡 | Interrupted on restart | kill server mid-job | Job marked FAILED ("Server restarted…") on boot | jobs list |
| R4 🟢 | Saved job run | save → approve → run | Reuses plan; new lineage row | jobs + lineage |
| R5 🟢 | Plan summary / preview | plan-summary, preview endpoints | Per-column sample values, planned row counts | UI preview |

---

## S. Identity & generated columns 🏦

| # | Scenario | Configure | Expected | Verify |
|---|----------|-----------|----------|--------|
| S1 🟢 | Identity not written (unreferenced) | branches.branch_id GENERATED ALWAYS, not referenced | DB assigns identity; loader skips it | rows load, ids sequential |
| S2 🏦 | Identity written (referenced as FK target) | customer_id referenced by accounts | Written explicitly with OVERRIDING SYSTEM VALUE; FK stays valid | accounts.customer_id matches |
| S3 🏦 | Generated column skipped | accounts.is_overdrawn, transactions.amount_abs | Loader never writes them; DB computes | no "cannot insert into generated column" error |
| S4 🟢 | BY DEFAULT identity | transactions.txn_id | Loads with or without explicit value | rows load |

---

## T. Determinism & reproducibility

| # | Scenario | Configure | Expected | Verify |
|---|----------|-----------|----------|--------|
| T1 🟢 | Same seed → same data | run twice, same seed, CSV | Byte-identical (or value-identical) output | diff the CSVs |
| T2 🟡 | Different seed → different data | change seed | Different values, same shape | compare |
| T3 🟡 | Per-table seed independence | multi-table | Tables vary independently, still reproducible | re-run compare |

---

## U. Negative / robustness 🔴

| # | Scenario | Configure | Expected | Verify |
|---|----------|-----------|----------|--------|
| U1 🔴 | 0 rows | rowCount=0 | No rows, no error | count=0 |
| U2 🔴 | Invalid generator param | NORMAL_INT with non-numeric param | Clear validation error or safe default | message |
| U3 🔴 | FK to table not in plan & not existing | child only | NULL FK or clear error | inspect |
| U4 🔴 | Bad TEMPLATE token | `${nonexistent_col}` | Leaves token/blank, no crash | inspect output |
| U5 🔴 | Duplicate forced beyond pool | tiny name pool, huge unique requirement | Disambiguation suffixes; still unique; no crash | uniqueness = 0 |
| U6 🔴 | Wrong type to numeric col | text generator into numeric | Clear bind/convert error | message |

---

## V. Concurrency / target locking 🏦

| # | Scenario | Configure | Expected | Verify |
|---|----------|-----------|----------|--------|
| V1 🏦 | Reject 2nd load on busy target | Start a DB load (async) on data source D / schema S, then start another DB load on the SAME D/S before the first finishes | 2nd request rejected with a clear message naming the running job + owner ("A synthetic load is already running against … job … started by …") | observe error; first job continues unaffected |
| V2 🟢 | Different schema runs concurrently | Two loads on same data source but different schemas | Both allowed (lock is per data source **+** schema) | both complete |
| V3 🟢 | Lock released on completion | Run a load to D/S, wait for COMPLETED, run another to D/S | 2nd load is accepted (lock freed in the worker's finally) | 2nd job runs |
| V4 🟢 | Lock released on failure/cancel | Cancel or fail a load to D/S, then start another to D/S | 2nd load accepted (lock freed even on cancel/fail) | 2nd job runs |
| V5 🟡 | Sync vs async share the lock | One via `/generate` (sync), one via `/generate/start` (async) on same D/S | Whichever is second is rejected | error on the second |
| V6 🟢 | Restart clears stale locks | Kill server mid-load, restart, start a load to the same D/S | Allowed (in-memory lock map is fresh; interrupted job marked FAILED) | 2nd job runs |

*Manual verify (V1):* call `POST /api/synthetic/generate/start` with a large plan (e.g. transactions ≥ 600k) on the test schema, then immediately call `POST /api/synthetic/generate` (or another `/start`) on the same schema → the second returns HTTP 400 with the "already running" message. Cancel the first when done.

## How to run a typical pass

1. `psql -p 5433 -f synthetic_test_schema_postgres.sql`
2. In the wizard, **+ Add & learn distributions** from `prod_customers` → run **I1, I9**.
3. Build the banking plan (branches→customers→accounts→transactions, +fx_trades, +cards):
   set PKs, UNIQUE, FK auto-detect, cardinality (F1/F2), LOOKUP for `holder_email` (G5).
4. Small run (≤500k) → A/B/C/D/E/F/G/H/S/P. Verify with the SQL in the schema file footer.
5. Large run (1M+ on transactions/customers) → B4, O2/O3, D2, F3, G6, L1.
6. Save job, request approval, approve as a 2nd user, run → Q1–Q10, then check
   `synthetic_generation_lineage` and the audit ledger.

**Pass criteria summary:** 0 FK orphans · 0 uniqueness violations · 0 CHECK violations ·
LOOKUP/derived consistent · cardinality within configured range · governance gates enforced ·
no OOM at scale · deterministic for a fixed seed.
