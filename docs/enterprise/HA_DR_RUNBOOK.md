# ForgeTDM HA and disaster recovery runbook

## Supported deployment model

The safe enterprise model is one isolated ForgeTDM deployment and configuration database per client/tenant,
with two or more stateless application replicas. This gives strong tenant isolation without pretending the
current schema is row-level multi-tenant. A future SaaS control plane can manage many isolated tenant cells.

Use `deploy/kubernetes/forgetdm.yaml` as the baseline. It includes rolling updates, two replicas, readiness,
liveness and startup probes, graceful termination, topology spread, a disruption budget, resource limits,
non-root/read-only containers, and horizontal autoscaling. Database-backed leases prevent multiple replicas
from firing the same DataScope or integration scheduler tick.

## Configuration database

Run PostgreSQL as a managed multi-zone service or a supported HA cluster. Enable encrypted storage, TLS,
automated backups, point-in-time recovery, deletion protection, and cross-region backup copy. ForgeTDM
replicas must all use the same database and masking/capsule secrets.

Recommended initial objectives:

| Tier | RPO | RTO | Backup/restore validation |
| --- | --- | --- | --- |
| Production | 5 minutes | 60 minutes | Monthly restore into an isolated namespace |
| Non-production | 24 hours | 4 hours | Quarterly restore test |

The customer must approve objectives based on regulation and workload criticality.

## Backup

1. Take a native PostgreSQL snapshot/PITR checkpoint.
2. Export a logical backup with `pg_dump --format=custom --no-owner --file=forgetdm.dump`.
3. Back up Kubernetes ConfigMaps, external secret references, ingress configuration, and approved JDBC drivers.
4. Store masking and capsule secrets in a secret manager. Never put clear secrets in the backup document or Git.
5. Record artifact hashes, database version, ForgeTDM image digest, migration version, start/end time, and operator.

## Restore drill

1. Create an isolated database and namespace; deny all routes to production source/target databases.
2. Restore with `pg_restore --clean --if-exists --no-owner --dbname=<recovery-db> forgetdm.dump`.
3. Deploy the exact recorded image digest and secret versions.
4. Confirm `/readyz`, Flyway migration validation, audit-chain verification, login, saved-job catalog, and lookup catalog.
5. Run read-only connector tests, then one synthetic file-output job. Do not provision databases during a DR drill.
6. Capture actual RPO/RTO and remediation actions; destroy the isolated recovery environment after evidence review.

## Failover cautions

Running jobs execute inside one application replica. A node loss marks the in-process execution interrupted;
the durable run record remains for investigation/retry, but an active JDBC transaction is not transferred to
another pod. Use idempotent target preparation, partition retries, and native-loader restart controls for very
large loads. HA keeps the control plane available; it does not make an individual database transaction movable.
