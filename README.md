# ForgeTDM — Enterprise Test Data Management Platform

A full-stack TDM tool synthesizing the best ideas from K2View (entity-based subsetting,
in-flight masking), CA TDM / Broadcom (deterministic seedlist masking, Find & Reserve),
IBM Optim (semantic coherence, compare/validate), GenRocket (intelligent synthetic
generators) and Delphix (self-service provisioning workflow) — in one Java 17 /
Spring Boot 3 application with a PostgreSQL config database and an embedded web console.

```
 Discover PII  ->  Review & Approve  ->  Generate Policy  ->  Provision (mask-copy /
 subset-mask / synthetic)  ->  Validate (leak / format / RI)  ->  Reserve for testing
                                  ... all audited.
```

## Capability map

Detailed masking explanation: [How Masking Works in ForgeTDM](docs/HOW_MASKING_WORKS.md).

| Capability | How ForgeTDM does it |
|---|---|
| **PII discovery** | Dual-signal scan: column-name regex (60%) + sampled-value regex & Luhn analysis (40%) → confidence-scored review queue → one-click policy generation |
| **Masking** | Deterministic HMAC-SHA256 keyed on a project secret. Same input ⇒ same output across every table, DB, and run — with **zero shared state** (no XREF tables). Irreversible by construction. |
| Names | Seedlist substitution; FULL_NAME composes the same masked first/last as split columns |
| Email | Rebuilt from masked names + hash suffix @ reserved `.test` domains (never deliverable) |
| SSN | Keeps area number (first 3), regenerates group/serial with valid ranges (never 00/0000, never emits 666) |
| Credit card | Preserves BIN(6) + length + separators, regenerates middle, **repairs Luhn check digit** |
| DOB | Age-band preservation (eligibility logic keeps working) or deterministic date shift |
| Address/Geo | Street seedlists; **coherent city/state/zip triplets** (semantic integrity) |
| Phone | Format-preserving, **keeps country code** |
| Generic | FORMAT_PRESERVE (FPE-style), HASH_LOV, REDACT_KEEP_LAST4, FIXED, NULLIFY, SEQUENCE |
| **Subsetting** | Entity-based: driver table + optional WHERE filter + first-class row limit. FK-graph closure can be enabled for related rows, or disabled for simple driver-table samples. Keyless driver tables now produce a safe driver-only row-limited plan with warnings instead of a dead end. SQL-injection-guarded filters. |
| **Synthetic data** | 50+ categorized generators across person, location, finance, date/time, technical, business, and network data. Includes Luhn-valid cards, ABA-style routing numbers, account numbers, coherent row-indexed geo, API/network values, statuses, ranges, and seeded reproducibility. |
| **Provisioning** | Async job engine: MASK_COPY, SUBSET_MASK, SYNTHETIC_LOAD. Extract → mask in flight → batched inserts with live progress |
| **Reservation** | Find & Reserve: locks matching rows per tester with TTL + conflict prevention + scheduled expiry |
| **Validation** | Post-mask evidence: LEAK (masked == live source), FORMAT (Luhn/regex contracts), RI (consistent mapping across tables), DOMAIN (deliverable emails) → PASS/WARN/FAIL reports |
| **Virtualization** | Delphix-style logical TimeFlow engine: content-addressed storage pool (SHA-256 chunks, compression, optional AES-256-GCM encryption, dedup), incremental snapshots that store only changed blocks, TimeFlow lineage with branching, bookmarks, refresh/rewind, thin VDB provisioning into embedded H2 or real target DBs |
| **Audit** | Every consequential action recorded |

## Virtualization: the TimeFlow engine

Modeled on the Delphix architecture, one level up from the block device:

| Delphix concept | ForgeTDM analog |
|---|---|
| Dedicated storage pool (ZFS) | `ChunkStore`: pool directory of SHA-256-addressed, compressed chunks with optional authenticated AES-256-GCM encryption |
| Backup/log ingestion per DB type | JDBC ingestion from any registered source (Postgres, Oracle, DB2, SQL Server, H2), rows streamed in PK order |
| Block/page-level indexing | Snapshot manifest: per-table ordered list of row-batch chunk hashes |
| Dedup + compression | Content addressing — identical row batches across snapshots are stored once; all chunks gzip'd |
| TimeFlow metadata store | `timeflows` + `virtual_snapshots` tables: dSource timeflows, per-VDB timeflows branched from a parent snapshot |
| Copy-on-write writable layer | VDB materialized from shared pool chunks into its own H2 file (or a real target DB); base chunks are immutable |
| Mount/export to target server | VDB auto-registered as a ForgeTDM data source, usable by masking/subsetting/validation |
| Snapshots/bookmarks = metadata + changed blocks | Re-ingesting a VDB stores only chunks whose content changed; bookmark = named snapshot on the VDB timeflow |
| DB-specific recovery logic | Dialect-aware DDL type mapping + batched inserts on materialize (Postgres / Oracle / DB2 / SQL Server / H2) |

### Container CoW provider (true runtime thinness, PostgreSQL)

The CONTAINER provider steps outside the JVM and uses Docker's overlay2 layer store as
the copy-on-write storage pool:

| Delphix | Container provider |
|---|---|
| ZFS pool + TimeFlow | Docker image layers; each dSource snapshot builds `FROM` the previous one, copying only files that changed (computed by tree diff against the prior `pg_basebackup`) |
| Backup ingestion | `pg_basebackup` — exact physical datafiles: indexes, sequences, views, procedures, stats |
| Thin VDB, minutes, ~no space | `docker run` from the snapshot image — seconds at any size; reads come from shared image layers at runtime |
| Per-VDB writable layer | the container's overlay2 writable layer — holds only blocks the VDB changes |
| Bookmark = metadata + changed blocks | clean stop → `docker commit` → restart; the commit layer holds only changed files |
| Mount/export | published Postgres port, auto-registered as a ForgeTDM data source |

Requirements: Docker Desktop/Engine running where ForgeTDM runs; the Postgres source
must accept replication connections (`pg_hba.conf`: `host replication all <net> scram-sha-256`)
from a user with the `REPLICATION` role. CoW granularity is per file (Postgres segments
tables at 1 GB), so first-write copy-up is bounded at 1 GB per touched segment — the same
tradeoff postgres.ai's Database Lab accepts.

Still not Delphix: no continuous WAL shipping (snapshots are discrete), Postgres only,
and no point-in-time provisioning between snapshots.

### ZFS engine provider (real block-level storage pool — all five DB types)

The ZFS provider mirrors the Delphix architecture 1:1 — including the part Delphix
ships as a separate appliance. ForgeTDM is the management plane; a Linux **engine
host** (reached over SSH, or local when ForgeTDM runs on it) owns a real ZFS pool:

| Delphix | ZFS provider |
|---|---|
| Engine appliance + dedicated storage pool | Linux host with an OpenZFS pool (`tank/forgetdm`), driven over SSH |
| Incremental backup ingestion | `pg_basebackup` to staging, then `rsync --inplace --no-whole-file` into the dSource dataset — only changed blocks are rewritten |
| TimeFlow snapshot = metadata + changed blocks | `zfs snapshot` — instant, block-level; per-snapshot delta reported from `written@prev` |
| Thin VDB, minutes, ~no space at any size | `zfs clone` — instant; the VDB's PostgreSQL container serves datafiles directly off the clone mountpoint; reads hit shared pool blocks |
| Per-VDB copy-on-write writable layer | the clone itself — ZFS block-level CoW |
| Bookmark | `zfs snapshot` on the clone |
| Rewind | branch: new `zfs clone` from the bookmark, new timeflow; earlier bookmarks all stay rewindable |
| Refresh | branch: new `zfs clone` from the chosen dSource snapshot, new timeflow |
| Compression / dedup | `compression=lz4` per dataset; pool-wide block dedup if you enable it |
| Mount/export | clone mounted into the VDB container; published Postgres port auto-registered as a data source |

**Supported databases in the ZFS provider**

| Kind | Snapshot mechanism | VDB container | Notes |
|---|---|---|---|
| `POSTGRES` | `pg_basebackup` → `rsync --inplace` → ZFS snapshot | `postgres:<major>` | LogSync (WAL streaming) + PITR supported |
| `SQLSERVER` | `BACKUP DATABASE TO DISK` → RESTORE into ZFS → detach | `mcr.microsoft.com/mssql/server:2022-latest` | Backup mount required (see config) |
| `DB2` / `DB2UDB` | JDBC logical ingest → ChunkStore → marker on ZFS dataset | `icr.io/db2_community/db2` | Real DB2 LUW engine; ~5 min start-up |
| `DB2ZOS` | Same JDBC logical ingest as DB2 LUW | `icr.io/db2_community/db2` | VDB runs on DB2 LUW (cross-platform for dev/test) |
| `ORACLE` | JDBC logical ingest → ChunkStore → marker on ZFS dataset | `gvenzl/oracle-free:23-slim` | Oracle Free 23c; health-check based readiness |

**Logical snapshot** (DB2 / DB2 z/OS / Oracle): all rows are streamed from the source
via JDBC into the central ChunkStore (SHA-256 dedup + gzip). A tiny marker file in the
ZFS dataset records the dialect and manifest hash. ZFS snapshot of the marker file is
instant. VDB provisioning clones the dataset, starts the appropriate DB container, and
materialises the data via JDBC — VDB writes land in the container's storage on the ZFS
clone (ZFS block-level CoW). Advantage over the POOL provider: real DB engine in the
VDB, instant ZFS thin clones, ZFS block compression.

**Engine host setup** (Ubuntu 22.04+, e.g. a Hyper-V/Multipass VM or any Linux box):

```bash
sudo apt install -y openzfs-zfsutils docker.io rsync
# dedicate a disk (or a file for testing):
sudo truncate -s 50G /var/lib/forgetdm-pool.img
sudo zpool create tank /var/lib/forgetdm-pool.img
sudo zfs create -o compression=lz4 tank/forgetdm
```

Then key-based SSH from the ForgeTDM machine (`ssh-copy-id root@engine-host`) and configure:

```yaml
forgetdm:
  virtualization:
    zfs:
      host: engine-host          # empty = ForgeTDM runs on the engine itself
      ssh-user: root
      pool: tank/forgetdm
      localhost-alias: 192.168.1.50   # this machine's address as the engine sees it
```

(env vars: `FORGETDM_ZFS_HOST`, `FORGETDM_ZFS_SSH_USER`, `FORGETDM_ZFS_POOL`,
`FORGETDM_ZFS_LOCALHOST_ALIAS`)

DB2 and Oracle images are pulled on demand by the engine host's Docker daemon. Override if
needed:
```yaml
forgetdm:
  virtualization:
    zfs:
      db2-image: icr.io/db2_community/db2:11.5.9.0  # FORGETDM_ZFS_DB2_IMAGE
      db2-instance-password: Forgetdm!Str0ng1        # FORGETDM_ZFS_DB2_INSTANCE_PASSWORD
      oracle-image: gvenzl/oracle-free:23-slim        # FORGETDM_ZFS_ORACLE_IMAGE
      oracle-sys-password: Forgetdm1Oracle            # FORGETDM_ZFS_ORACLE_SYS_PASSWORD
```

**DB2 VDB prerequisites** — the engine host must be able to pull from `icr.io` (IBM
Container Registry; free for DB2 Community Edition). DB2 containers require `--privileged`
and take 3–5 minutes to complete database initialisation. Once started, they're re-used
across restarts. DB2 z/OS sources (`DB2ZOS`) provision into the same DB2 LUW container,
which is suitable for dev/test cross-platform usage.

**Oracle VDB prerequisites** — `gvenzl/oracle-free` is an Oracle-approved free tier image.
ForgeTDM uses Oracle Free 23c (XE-compatible). The container signals readiness via Docker
health check. Connect as `system/<oracle-sys-password>` to the VDB.

Refresh and rewind follow Delphix timeflow semantics: each one branches a new timeflow
(a fresh clone from the chosen snapshot) and keeps the old clone, so every bookmark on
every branch remains rewindable forever.

#### LogSync — continuous WAL streaming (Postgres + ZFS)

Enable LogSync on a ZFS dSource to ship WAL continuously between snapshots. ForgeTDM
starts a `pg_receivewal` container with a dedicated replication slot on the source
Postgres:

```
POST /api/virtualization/datasources/{id}/logsync/enable
GET  /api/virtualization/datasources/{id}/logsync
POST /api/virtualization/datasources/{id}/logsync/disable
```

**Postgres prerequisites:** the source must have `wal_level = replica` and allow
replication connections from the engine host. Add to `pg_hba.conf`:
```
host  replication  all  <engine-ip>/32  md5
```

Once LogSync is running, provision a VDB with a `pointInTime` field
(`2024-06-01T14:30:00`) and ForgeTDM will clone the nearest snapshot ≤ the target
time, write `recovery.signal` + `recovery_target_time` into the clone, and start
Postgres — it replays WAL to the exact second then promotes.

#### NFS export + target environments

Register SSH-reachable Linux hosts where VDB containers should run instead of on the
engine:

```
GET    /api/virtualization/environments
POST   /api/virtualization/environments   {"name":"qa-host","host":"192.168.1.50","sshUser":"root","sshPort":22,"mountBase":"/mnt/forgetdm"}
DELETE /api/virtualization/environments/{id}
```

**Target host prerequisites:**
```bash
sudo apt install -y nfs-common docker.io
```
Engine host must export via `zfs set sharenfs='rw,no_root_squash,insecure'` (done
automatically at provision time). Add `environmentId` to the provision request to land
the VDB on that host.

#### SQL Server on the ZFS engine

Supported for ZFS provider when the source is a SQL Server instance. ForgeTDM:
1. Issues `BACKUP DATABASE ... TO DISK` via JDBC (file lands in `mssqlSourceBackupDir`).
2. An ephemeral mssql container on the engine restores it into the dSource ZFS dataset.
3. `sp_detach_db` leaves the `.mdf`/`.ldf` files on ZFS.
4. Snapshots and clones work identically to Postgres.
5. VDB provisioning does `CREATE DATABASE ... FOR ATTACH` against the clone datafiles.

Configure the backup share path:
```yaml
forgetdm:
  virtualization:
    zfs:
      mssql-source-backup-dir: /var/opt/mssql/backup   # path on the SQL Server host
      mssql-backup-mount: /mnt/mssql-backup             # same dir as engine sees it (NFS/CIFS)
      mssql-image: mcr.microsoft.com/mssql/server:2022-latest
```

#### Delete operations

Delete VDBs and snapshots safely from the UI or API:

```
DELETE /api/virtualization/vdbs/{id}       # destroys container/clone + deregisters datasource
DELETE /api/virtualization/snapshots/{id}  # refused if any VDB depends on it
```

ZFS VDB delete destroys all branch clones. Snapshot delete refuses if another VDB is
at or branched from that snapshot. POOL snapshots share chunks — the chunk store is
not modified; only the manifest record is removed.

## Quick start (zero-install demo, H2 config DB)

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=demo
# open http://localhost:8088
```
(Uses an in-memory H2 config DB so you can explore the console instantly. Register any
reachable JDBC databases as sources/targets.)

## Supported source/target databases

| Kind | Driver (bundled) | JDBC URL example |
|---|---|---|
| `POSTGRES` | org.postgresql | `jdbc:postgresql://host:5432/db` |
| `H2` | com.h2database | `jdbc:h2:file:/path/to/db` |
| `DB2` / `DB2UDB` | com.ibm.db2 jcc (type 4, LUW + z/OS) | `jdbc:db2://host:50000/db` |
| `ORACLE` | ojdbc11 | `jdbc:oracle:thin:@//host:1521/service` |
| `SQLSERVER` | mssql-jdbc | `jdbc:sqlserver://host:1433;databaseName=db;encrypt=false` |
| `GENERIC` | any driver on the classpath | dialect inferred from the URL |

Identifier quoting, system-schema filtering, and TRUNCATE semantics are dialect-aware
(`SqlDialect`); everything else uses portable JDBC (metadata, `setMaxRows`, batched
prepared statements).

## Full stack with Docker (recommended)

```bash
docker compose up --build
# open http://localhost:8088
```
This starts: ForgeTDM (8088) + its Postgres config DB (5432) + a demo **source** bank DB
with realistic PII (5433) + an empty **target** QA DB (5434).

### 5-minute demo walkthrough
1. **Data Sources** → register both demo DBs:
   - `bank-demo` · SOURCE · `jdbc:postgresql://bank-demo:5432/bankdemo` · demo / demo
     (use `jdbc:postgresql://localhost:5433/bankdemo` if running ForgeTDM outside Docker)
   - `bank-qa` · TARGET · `jdbc:postgresql://bank-qa:5432/bankqa` · demo / demo
     (or `localhost:5434` outside Docker)
   Click **Test** on each.
2. **PII Discovery** → select `bank-demo` → **Scan for PII**. Watch it find ssn, email,
   card_number, dob, names, address… with confidence bars. **Approve** the findings.
3. Enter a policy name → **Generate Policy from Approved**.
4. **Provisioning Jobs** → MASK_COPY, source `bank-demo`, target `bank-qa`, pick the policy,
   tables blank (= all) → **Launch**. Status flips PENDING → RUNNING → COMPLETED.
5. **Validation** → source `bank-demo`, target `bank-qa`, the policy → **Run Validation** →
   expect **PASS** with leak/format/RI checks documented.
6. **Masking Studio** → try `123-45-6789` with SSN, `4111 1111 1111 1111` with CREDIT_CARD —
   note BIN/area preservation and re-run determinism.
7. **Subsetting** → driver `customers`, optional filter `state = 'TX'`, row limit `1000`,
   and related-row toggle → **Plan Subset** → see warnings, load order, closure counts,
   or a simple driver-only row-limited slice; execute via a SUBSET_MASK job.
8. **Find & Reserve** → table `customers`, criteria `vip = true`, count 2 → reserve; try
   reserving again to see conflict prevention.

## Running against your own databases
Any JDBC-reachable PostgreSQL works out of the box (drivers for Postgres + H2 ship in the
jar; add other vendors' drivers to the pom as needed). Targets must contain the destination
tables (provisioning copies data, not DDL).

## Configuration
| Env var | Default | Purpose |
|---|---|---|
| `FORGETDM_DB_URL` | `jdbc:postgresql://localhost:5432/forgetdm` | Config DB |
| `FORGETDM_DB_USER` / `FORGETDM_DB_PASS` | `forgetdm` | Config DB credentials |
| `FORGETDM_MASKING_SECRET` | `change-me-in-production` | **The determinism key.** Same secret ⇒ same masked values forever. Rotating it re-keys every masked output. Guard it like a password. |

## Tests
```bash
mvn test
```
Covers: masking determinism & normalization, SSN/CCN/email/DOB/geo/phone contracts,
secret rotation, Luhn validity of generated cards, generator bounds & reproducibility,
subset topological ordering, filter injection guard.

## Architecture
- `io.forgetdm.core.*` — **pure-Java engines** (masking, synth, Luhn, HMAC determinism,
  PII patterns). Zero framework dependencies: portable, unit-testable, reusable in Spark/CLI.
- `io.forgetdm.{datasource,discovery,policy,subset,provision,reservation,validation,audit}` —
  Spring services + REST controllers over the engines, JPA persistence to the config DB
  (Flyway-migrated schema in `db/migration/V1__init.sql`).
- `resources/static` — dependency-free single-page console (vanilla JS), served from the jar.

### Key design decisions
- **Keyed-HMAC determinism instead of XREF tables**: referential integrity across tables and
  databases without shared mutable state → stateless, horizontally scalable masking.
- **Canonical salts for identity functions** (`ssn`, `ccn`, `name.first`, `geo`, …): the same
  person masks identically everywhere; non-identity columns stay column-scoped to avoid
  accidental collisions.
- **Masking ≠ encryption**: there is intentionally no decrypt path.
- Identifier whitelisting (`[A-Za-z0-9_]+`) + filter guards on every dynamic SQL surface.

## Production hardening checklist (next milestones)
Authentication/RBAC (Spring Security + OIDC), encrypted credential storage (Vault),
target schema auto-DDL, additional locale seedpacks, CDC-based incremental refresh,
coverage analysis, re-identification risk scoring, REST hooks for CI pipelines.
