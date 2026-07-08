# DataScope — Bottlenecks & Roadmap

From the DataScope page review (2026-06-22). Rated ~7/10 overall (~6/10 for large-scale banking).
Items are sequenced so the masking-correctness-critical ones each get their own focused, testable pass
(can't be done safely blind in a batch).

## Done
- [x] In-place provisioning fixes: no driver required, ignore Table-Map target names, skip subset planning.
- [x] Compact searchable / filterable / sortable / paginated blueprint browser (replaces card grid).
- [x] Table Profiles moved to its own tab.
- [x] **Top-level "apply policy" with per-table override** — blueprint/job default policy applies to every table;
      a table's own policy overrides it. (`rulesForProfile` now falls back to the default; UI selector added.)
- [x] **#3 Concurrency guard** — one provisioning job per target (dataSource + schema); a second is rejected
      with a clear message (mirrors synthetic generation).

## Done (cont.)
- [x] **#8 Per-table provisioning states** — engine sets PENDING/RUNNING/DONE/FAILED per table (V27
      `table_states_json`), and the job monitor renders live per-table chips like synthetic generation.
- [x] **#4 Auto-create missing target tables** — `copyTable` builds the target from the source structure when
      it doesn't exist; `prepareTargets` skips not-yet-created targets. (Same-dialect; cross-dialect best-effort.)

## Done (cont.)
- [x] **#1 Subset-closure memory guard** — FK closure now enforces a key budget (`enforceKeyBudget`, default
      5M keys, env `FORGETDM_SUBSET_MAX_KEYS`) and fails fast with guidance instead of OOM-ing. (Full streamed/
      staged closure is still a later option for going beyond the cap.)
- [x] **#2 FK-consistent key masking** — related PK/FK key columns now share one masking salt
      (`buildKeyConsistencySalts` union-finds DB FK edges; `saltFor` uses it), so masking both sides with the
      same function produces matching values and joins survive. (Semantic functions were already consistent.)
      Follow-ups: include custom soft-FK relationships, and a plan-time warning when only one side of a
      relationship is masked or the two sides use different functions.

## Done (cont.)
- [x] **#5 Consume the tool-level RI registry (additive)** — DataScope provisioning now augments its
      relationships/PKs with the RI registry (`riRegistry.resolve` → extra FK edges + custom-PK fallbacks),
      so org-wide RI defined once applies without per-blueprint entry. Additive; blueprint defs take precedence.
      Limitation: runs on the worker thread (no user context) so it resolves **GLOBAL** definitions only;
      per-user/group consumption needs job-owner plumbing (follow-up below).
- [x] **#6 Blueprint versioning** — `datascope_versions` (V28) + `DataScopeVersionService`/Controller +
      "Version history" UI: snapshot the whole blueprint, list versions, view any snapshot. (Side-by-side diff
      / one-click rollback are easy follow-ups on top of the stored snapshots.)
- [x] **#7 Multi-start tables** — the planner no longer requires a driver when ≥1 table is marked
      **Independent** (a start table seeded from its own filter, then closure runs outward). UI help updated.

## Done (cont.)
- [x] **Keyless in-place masking (any DB)** — tables with no primary key / unique key no longer fail in-place
      masking. A universal staging-rebuild path (`maskTableInPlaceRebuild`) streams every row, masks the changed
      columns, bulk-loads the full masked rows into a private staging table, verifies the row count, then in ONE
      transaction replaces the table contents (`DELETE FROM t; INSERT INTO t SELECT … FROM staging`). No PK or
      DB-specific row-id (ctid/ROWID/RID) needed, so it works on Postgres/Oracle/DB2/SQL Server/MySQL alike and
      preserves the table's indexes/grants/constraints. Default DELETE keeps the swap rollback-safe; set
      `FORGETDM_INPLACE_KEYLESS_TRUNCATE=true` for a faster (less safe) TRUNCATE.
- [x] **In-place "transaction is aborted" fix** — `dropStaging` now uses `DROP TABLE IF EXISTS` + rollback
      recovery, so the first-run drop of a non-existent staging table no longer aborts the Postgres transaction.
- [x] **In-place masking of unique-indexed columns** — keyed (PK) tables masking a uniquely-indexed column
      (e.g. `email`) no longer error out. The chunked UPDATE path can transiently collide, so it falls back to
      `maskTableInPlaceRebuild`, which for keyed tables stages (keys + changed columns) and applies ONE set-based
      `UPDATE … FROM staging` — uniqueness is enforced only at the final state. (If the chosen function isn't 1:1
      the unique constraint fails and the txn rolls back to the original data with a clear DB error — expected.)
- [x] **In-place FK-parent fix ("violates foreign key constraint … still referenced")** — the rebuild no longer
      DELETEs a keyed table (which broke inbound FKs like `accounts.customer_id → customers`). Keyed rebuilds now
      UPDATE rows by key in place, keeping primary keys intact so child rows stay valid. DELETE+INSERT is used
      only for truly keyless tables, which by definition cannot be referenced by a foreign key.

## Done (cont.) — throughput (GenRocket parity)
- [x] **Parallel in-place masking** — independent in-place tables now mask concurrently on a worker pool
      (`maskTablesInPlace`/`maskOneTableInPlace`), each on its own JDBC connections. Thread count via
      `FORGETDM_PROVISION_PARALLELISM` (default min(4, CPUs)). FK-consistency salts are shared read-only and
      re-installed per worker (the salt ThreadLocal doesn't cross threads); progress goes through one shared
      delta sink (`ProgressSink`/`reportDelta`) so the row counter stays monotonic; per-table state + job saves
      are synchronized. First worker failure cancels the rest and fails the job. Applies to both DataScope
      (`runSubsetMask`) and Mask-copy (`runMaskCopy`) in-place paths. The INSERT/subset path stays sequential
      (it has FK load-ordering); FK-level parallelism there is a later option.
- [x] **SQL-pushdown for value-independent masks** — when every changed column on an in-place table is an
      unconditional NULL_OUT or LITERAL, the whole table is masked with a single server-side
      `UPDATE … SET col = NULL/literal` (`sqlPushdownAssignments`) — no rows leave the DB, no staging, no chunk
      loop. Keyed masks (names/email/SSN/hash/FPE) are intentionally excluded: their output depends on the HMAC
      secret and can't be reproduced in SQL without changing the masked values (breaking determinism/RI). Literals
      are rendered safely (numbers bare, everything else quoted/escaped).

## Done (cont.) — DB-side pushdown (keyed generators)
- [x] **DB-side ("pushdown") equivalents of generators** — new `DbMaskPushdown` installs parity-verified
      in-database functions so name-class masks compute entirely server-side (no rows leave the DB). Phase 1:
      **Postgres** (native `pgcrypto.hmac`), single-draw seed-list generators with default casing — FIRST_NAME,
      LAST_NAME, COMPANY, HASH_LOV — implemented as `seedlist[hashLong(secret, salt|seedlist, normalize(v)) %
      size]` via an installed `forgetdm_hashlong()` + per-seedlist lookup tables. Wired into the in-place
      pushdown path: a table whose every changed column is null/literal OR a verified generator masks in one
      server-side UPDATE; any table containing a multi-draw mask (SSN/phone/CC/FPE) stays on the parallel Java path.
      - **Parity gate (non-negotiable):** every installed function is run against samples and compared to the
        Java `MaskingEngine`; pushdown is enabled per-seedlist ONLY on exact match, else automatic Java fallback.
        So it can never break referential integrity — worst case is "no speedup."
      - **Security gate:** server-side keyed masking requires the secret in the DB, so it's OFF unless
        `FORGETDM_DB_PUSHDOWN=true`; installed objects use a random per-job suffix and are dropped on completion.
      - FPE / multi-draw masks intentionally NOT pushed down (java.util.Random can't be reproduced in SQL).

## Done (cont.) — bug fixes
- [x] **DataScope provision ignored the policy** — two causes fixed: (1) the Provision tab's policy dropdown
      (`ad-prov-policy`, the one actually submitted as the job policy) was never defaulted to the blueprint's
      saved policy, so it sent `policyId: null` unless re-selected on that tab — it now defaults to `def.policyId`;
      (2) `rulesFor` only matched `schema.table` or bare `table`, so a policy whose rules were saved under a
      different schema qualifier than the provisioned schema matched nothing and masked silently — added a
      schema-tolerant last-resort match by table name (additive: only ever finds more rules, never fewer).
- [x] **Mixed-source DataScope ignored the top-level policy entirely** — `runMixedSourceDataScopeMask` resolved
      rules with `rulesForProfile(Map.of(), …)`, i.e. an EMPTY default map, so a job/blueprint policy was dropped
      and a table masked only if it had its own per-table policy → "I set the policy but nothing gets masked."
      Now passes `rulesByTable(job.getPolicyId())` as the default, matching the single-source path. (This path
      runs whenever any included table has a per-table source/schema override.)
- [x] **Column Map policy made authoritative + safe fallback** — new `effectiveTableRules()` resolves a table's
      rules from its own (Column Map / per-table) policy first; only if that has no rule for the table does it
      fall back to the default policy. Wired into all four resolution sites (single-source in-place, single-source
      subset, mixed-source, and the FK-mask warning). Guarantees a Column-Map policy actually masks, and a
      misconfigured per-table policy can't silently leave a table unmasked when a default exists.
- [x] **Job-monitor sample misleading for in-place** — in-place masks the source table itself, so the sample's
      "Source" and "Target" both read the same (already-masked) table and looked identical, making users think
      nothing was masked. The sample endpoint now returns an `inPlace` flag + clear message, and the monitor
      shows a single "Masked result" panel instead of a pointless identical side-by-side. (To compare
      before/after for in-place, mask to a separate target first — the originals are overwritten in place.)

## Done (cont.) — bug fixes
- [x] **Row limit ignored for in-place DataScope** — in-place masking bypasses the subset plan and reads
      per-table filter/limit only from `spec.tableCriteria`, which the UI never populated, so both the blueprint
      driver-max and the Table-Map per-table row limit were dropped for in-place jobs. `runSubsetMask` now builds
      `tableCriteria` from the included profiles for in-place jobs (per-table `rowLimit` → else blueprint
      `maxDriverRows`; plus per-table `filterExpr`). The in-place masker already honors these (keyed chunk cap,
      keyless `setMaxRows`, filter WHERE). Subset-to-target jobs were unaffected (they apply limits via the plan).

## Done (cont.) — DataScope provisioning streamlining
- [x] **Row limit now a hard cap in every path** — `copyTable` stops after N rows for keyed AND keyless slices
      (not just `setMaxRows` on the keyless single-query path); the Table-Map per-table limit is passed to
      `copyTable` for keyed slices too (`rowLimitByTable`), and the in-place path already caps via tableCriteria.
      So the limit is honored for same-DB in-place AND different source→target copy.
- [x] **Progress %/ETA wired for all paths** — in-place masker now calls `updateTableRowProgress` (rowsDone
      actually moves) and `rowsTotal` is populated via `inPlaceRowCounts` (COUNT(*), capped by limit); subset
      paths already pass `plan.rowCounts`. Front-end basketball panel gains an **Overall % + ETA** summary on top
      of the per-table cards (the per-table ETA logic was already there but starved of data for in-place).
- [x] **Fast loading for DataScope** — `TableLoadWriter` gains a dialect-aware **multi-row INSERT** fast path for
      INSERT/REPLACE (Postgres/MySQL/H2/SQL Server/DB2; Oracle stays single-row + APPEND_VALUES since it has no
      multi-row VALUES). Batch size bounded by `maxRowsPerInsert` and `bindParamLimit`. Big speedup on engines
      without `reWriteBatchedInserts`; matches synthetic's multi-row loader.

## Next (each its own pass)
- [ ] **DataScope Postgres COPY fast path** — the last throughput gap vs synthetic (which uses COPY). Needs the
      text-encoding writer + INSERT/REPLACE-only guard; do with a live DB to verify masked output byte-for-byte.
- [ ] **DB pushdown: more engines** — Oracle (DBMS_CRYPTO) next; then SQL Server/MySQL/DB2 hand-rolled HMAC
      (all protected by the parity gate, so they simply fall back until verified).
- [ ] **DB pushdown: mixed-table partial pushdown** — push down the generator columns via one UPDATE AND
      Java-mask the remaining (FPE/etc.) columns in the same job, instead of all-or-nothing per table.
- [ ] **DB pushdown: case-aware generators** — replicate proper/upper/lower casing in SQL so non-default-case
      name masks also push down.
- [ ] **Intra-table parallel chunks** — split one very large in-place table into key ranges masked in parallel
      (helps when a single table dominates; the current pool is per-table).
- [ ] **FK-aware parallelism for the INSERT/subset path** — load independent tables (same FK level) concurrently
      while respecting parents-before-children ordering.
- [ ] **#1b Fully streamed/staged subset closure** (beyond the in-memory cap from #1).
- [ ] **#5b Per-user/group RI consumption** — add job-owner to provision jobs + resolve-by-owner so PRIVATE/
      GROUP registry definitions apply (not just GLOBAL).
- [x] **#2b** custom soft-FK now included in key-salt grouping (`collectKeyEdges` unions DB FKs + DataScope
      user relationships + RI-registry edges); and a plan-time warning (`keyMaskWarnings`) fires when only one
      side of a key relationship is masked, or the two sides use different functions — logged to the audit
      ledger and surfaced live in the job monitor (non-fatal).
- [ ] **#6b** version side-by-side diff + one-click rollback.
- [ ] Oracle/DB2 in-place masking validation (needs those engines to test).
- [ ] **MySQL streaming for keyless in-place rebuild** — MySQL Connector/J ignores normal fetch sizes and
      buffers the entire ResultSet, so the keyless rebuild read could OOM on large MySQL source tables. Add the
      MySQL special-case (`setFetchSize(Integer.MIN_VALUE)` on the streaming read in `maskTableInPlaceRebuild`)
      so it streams there too. Postgres/Oracle/DB2/SQL Server already honor fetchSize and stream fine.
