# CDC point-in-time provisioning

Turns the two primitives we already have — TimeFlow **snapshots** and the CDC **change stream** —
into "materialise a database as of any moment", the core of Delphix-style provisioning.

## Mechanism: bounded replay

A virtual DB "as of time T" = a base snapshot **plus** the CDC changes replayed **up to T**.

`POST /api/cdc/datasources/{id}/apply` now accepts a bound:

```json
{ "targetDataSourceId": 3,
  "throughChangeId": 42,              // inclusive: replay changes with id <= 42
  "throughTimestamp": "2026-07-21T20:05:25Z" }  // or: captured at or before this instant
```

- With **no bound** → full incremental refresh (may purge the buffer).
- With a **bound** → the target is brought to the source's exact state at that point; the buffer is
  **not** purged, so the same stream can be replayed to different points (the essence of PITR).

Changes are netted per primary key before apply, so only the minimum UPSERT/DELETE set is written.
Each change in the feed (`GET …/changes`) carries its `id`, `lsn` (LSN/SCN) and `capturedAt`, so an
operator (or the UI's "as-of change #") can pick the cut precisely.

## Provisioning a VDB as of T

1. Provision a VDB from the base snapshot (existing `POST /api/virtualization/vdbs`) — this
   materialises the snapshot into a real target/H2 engine.
2. Replay CDC onto that VDB up to the chosen point:
   `POST /api/cdc/datasources/{id}/apply { targetDataSourceId: <vdb backing>, throughChangeId: N }`.

The VDB now reflects the source as of change N / time T. Rewinding to an earlier point is the same
call with a smaller bound onto a freshly re-materialised base.

## Live test (Postgres source 2 → target 3)

```sql
-- on sourcedb, with CDC already enabled on source 2:
INSERT INTO yash.cdc_demo VALUES (100,'X',10);
UPDATE yash.cdc_demo SET amount=15 WHERE id=100;   -- state A: {100:15}
INSERT INTO yash.cdc_demo VALUES (101,'Y',20);     -- state B: {100:15, 101:20}
COMMIT;
```

```
POST /api/cdc/datasources/2/poll
GET  /api/cdc/datasources/2/changes         # note the change id of the UPDATE (end of state A)

# replay to state A only:
POST /api/cdc/datasources/2/apply { "targetDataSourceId": 3, "throughChangeId": <updateId> }
#   → target has id=100 (amount 15), NOT id=101

# replay to state B (latest):
POST /api/cdc/datasources/2/apply { "targetDataSourceId": 3 }
#   → target now also has id=101
```

Verify with `POST /api/query/run { "dataSourceId": 3, "sql": "SELECT * FROM yash.cdc_demo ORDER BY id" }`.

## Live proof (2026-07-21, source 2 → target 3)

Three committed changes captured: `id 9` INSERT 200(10), `id 10` UPDATE 200→15 (end of state A),
`id 11` INSERT 201(20) (state B). Target started empty.

| Action | Response | Target after |
|---|---|---|
| Replay `throughChangeId:10` | `pointInTime:true, changesReplayed:2, upserts:1, purged:0` | `{200 → X,15}` (201 absent) |
| Replay latest (no bound) | `pointInTime:false, changesReplayed:3, upserts:2, purged:3` | `{200 → X,15; 201 → Y,20}` |

The bounded replay reconstructed the target as of **state A** and did not purge the buffer; the
unbounded replay advanced it to **state B** and purged. Same stream, two points in time — proven.

### Operational note (learned in test)

An **idle** replication slot forces the server to retain and re-scan all WAL (shared across every
database in the cluster) on the next poll. After a large unrelated workload the accumulated WAL made
a single poll exceed the 4s budget and return nothing until the slot was recreated at the current
position. The fix in production is the **continuous background poller** (keeps the slot's confirmed
position close to current, so each decode is small) and/or a larger poll budget for catch-up. Idle
slots also pin WAL — monitor and drop unused captures.

## Not yet

- One-call orchestration (`provision VDB from snapshot + replay to T` in a single endpoint) — today
  it's the two calls above.
- Timestamp bound uses the capture time (`capturedAt`); mapping a wall-clock source-commit time to a
  precise LSN/SCN is a refinement.
