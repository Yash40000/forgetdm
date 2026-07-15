package io.forgetdm.virtualization;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceRepository;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.datasource.SqlDialect;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.forgetdm.virtualization.ZfsCli.shq;

/**
 * ZFS provider — built around the ZFS virtualization engine architecture. A Linux engine host
 * (SSH, like the engine host) owns a real ZFS pool:
 *
 *   dSource sync     PostgreSQL: pg_basebackup + rsync --inplace (changed blocks only).
 *                    SQL Server: BACKUP DATABASE via JDBC, restored into the dSource
 *                    dataset by an ephemeral mssql container; datafiles live on ZFS.
 *   LogSync          continuous WAL sync: a pg_receivewal container streams from a
 *                    replication slot into the dSource dataset.
 *   Snapshot         zfs snapshot — instant, block-level, delta = written@prev.
 *   Provision        zfs clone — instant thin clone at any size. PostgreSQL VDBs can
 *                    recover to ANY point in time between snapshots by replaying the
 *                    shipped WAL (recovery_target_time). SQL Server VDBs attach the
 *                    cloned .mdf/.ldf directly (CREATE DATABASE ... FOR ATTACH).
 *   Environments     NFS export (zfs sharenfs) mounted on a registered SSH target host
 *                    that runs the VDB engine locally — the NFS mount/export model.
 *   Bookmark         zfs snapshot on the clone.
 *   Refresh/Rewind   branch a NEW timeflow (fresh clone); nothing is destroyed.
 *   Delete           VDB delete destroys all branch clones (refused while other VDBs
 *                    depend on them); snapshot delete = zfs destroy of that snapshot.
 */
@Service
public class ZfsVdbProvider {
    private static final Pattern MSSQL_URL =
            Pattern.compile("jdbc:sqlserver://([^:;]+)(?::(\\d+))?;.*?[dD]atabase[nN]ame=([^;]+).*");
    private static final String DEFAULT_SA_PASSWORD = "Forgetdm!Str0ng1";
    private static final String SQLCMD = "/opt/mssql-tools18/bin/sqlcmd -C -S localhost -U sa";

    private static final String FORGE_MANIFEST_FILE = "forge-manifest.txt";

    private final VirtualSnapshotRepository snapshots;
    private final VirtualDatabaseRepository vdbs;
    private final TimeFlowRepository timeflows;
    private final DataSourceRepository dataSourceRepo;
    private final DataSourceService dataSources;
    private final ConnectionFactory connections;
    private final TargetEnvironmentRepository environments;
    private final AuditService audit;
    private final ZfsCli zfs;
    private final TimeFlowEngine engine;
    private final VirtOps ops;

    public ZfsVdbProvider(VirtualSnapshotRepository snapshots, VirtualDatabaseRepository vdbs,
                          TimeFlowRepository timeflows, DataSourceRepository dataSourceRepo,
                          DataSourceService dataSources, ConnectionFactory connections,
                          TargetEnvironmentRepository environments, AuditService audit,
                          ZfsCli zfs, TimeFlowEngine engine, VirtOps ops) {
        this.snapshots = snapshots;
        this.vdbs = vdbs;
        this.timeflows = timeflows;
        this.dataSourceRepo = dataSourceRepo;
        this.dataSources = dataSources;
        this.connections = connections;
        this.environments = environments;
        this.audit = audit;
        this.zfs = zfs;
        this.engine = engine;
        this.ops = ops;
    }

    /** Extra `docker run` args for containers that must reach the SOURCE over the LAN — e.g. `--network host `
     *  on corporate engines where the default bridge can't route to the LAN. Empty (default bridge) otherwise. */
    private String dockerNet() {
        String n = zfs.config().getDockerNetwork();
        return (n == null || n.isBlank()) ? "" : "--network " + n + " ";
    }

    /** Engine-host shell vs target-environment shell, one call shape. */
    private interface Shell { String exec(int timeoutSeconds, String command); }

    private Shell engine() { return zfs::exec; }

    private Shell env(TargetEnvironmentEntity e) {
        return (t, c) -> SshExec.exec(e.getHost(), e.getSshUser(), e.getSshPort(), t, c);
    }

    private Shell shellFor(VirtualDatabaseEntity vdb) {
        return vdb.getEnvironmentId() == null ? engine() : env(environment(vdb.getEnvironmentId()));
    }

    private TargetEnvironmentEntity environment(Long id) {
        return environments.findById(id).orElseThrow(() -> ApiException.notFound("Environment " + id + " not found"));
    }

    // --------------------------------------------------------------- status

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean ok = zfs.available();
        out.put("available", ok);
        out.put("engineHost", zfs.remote() ? zfs.config().getHost() : "local");
        out.put("pool", zfs.config().getPool());
        if (ok) {
            try {
                String poolRoot = zfs.config().getPool().split("/")[0];
                String[] zp = zfs.exec(20, "zpool list -Hp -o size,allocated,free,health " + shq(poolRoot)).split("\\s+");
                out.put("sizeBytes", Long.parseLong(zp[0]));
                out.put("allocatedBytes", Long.parseLong(zp[1]));
                out.put("freeBytes", Long.parseLong(zp[2]));
                out.put("health", zp[3]);
                out.put("compressRatio", zfs.exec(20, "zfs get -Hp -o value compressratio " + shq(zfs.config().getPool())));
            } catch (Exception ignored) { }
        }
        return out;
    }

    /**
     * Pre-flight the engine before anyone tries to capture a snapshot: run zfs/docker/nfs probes over the same
     * SSH channel the real work uses, and report exactly what's reachable — so setup problems (bad SSH key,
     * missing pool, no Docker) surface here instead of mid-snapshot.
     */
    public Map<String, Object> engineTest() {
        var cfg = zfs.config();
        String pool = cfg.getPool();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", zfs.remote() ? "remote-ssh" : "local");
        out.put("host", zfs.remote() ? cfg.getHost() : "(local — ForgeTDM must be running ON a Linux ZFS host)");
        out.put("sshUser", cfg.getSshUser());
        out.put("sshPort", cfg.getSshPort());
        out.put("useSudo", cfg.isUseSudo());
        out.put("dockerNetwork", cfg.getDockerNetwork() == null || cfg.getDockerNetwork().isBlank() ? "bridge" : cfg.getDockerNetwork());
        out.put("pool", pool);
        out.put("localhostAlias", cfg.getLocalhostAlias());

        List<Map<String, Object>> checks = new ArrayList<>();
        checks.add(check("SSH connectivity", true, () -> zfs.exec(15, "uname -sr")));
        checks.add(check("ZFS installed", true, () -> zfs.exec(15, "zfs version 2>/dev/null | head -1 || zfs -V")));
        checks.add(check("Pool '" + pool + "' accessible", true, () -> zfs.exec(15, "zfs list -H -o name " + shq(pool))));
        checks.add(check("Docker available (needed for Postgres/SQL Server capture)", false,
                () -> zfs.exec(20, "docker version --format '{{.Server.Version}}' 2>/dev/null || docker --version")));
        checks.add(check("NFS server (needed to serve VDBs)", false,
                () -> zfs.exec(15, "systemctl is-active nfs-server 2>/dev/null || systemctl is-active nfs-kernel-server 2>/dev/null || echo inactive")));

        boolean ready = checks.stream()
                .filter(c -> Boolean.TRUE.equals(c.get("required")))
                .allMatch(c -> Boolean.TRUE.equals(c.get("ok")));
        out.put("checks", checks);
        out.put("ready", ready);
        out.put("message", ready
                ? "Engine reachable — SSH, ZFS and the pool are OK. You can capture snapshots."
                : "Engine not ready — fix the failing required check(s) below (see the ZFS setup steps).");
        return out;
    }

    @FunctionalInterface private interface Probe { String get() throws Exception; }

    private Map<String, Object> check(String name, boolean required, Probe probe) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("required", required);
        try {
            String r = probe.get();
            String v = r == null ? "" : r.trim();
            boolean ok = !v.isEmpty() && !"inactive".equalsIgnoreCase(v);
            m.put("ok", ok);
            m.put("detail", v.isEmpty() ? "(no output)" : (v.length() > 200 ? v.substring(0, 200) + "…" : v));
        } catch (Exception e) {
            m.put("ok", false);
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            m.put("detail", msg.length() > 240 ? msg.substring(0, 240) + "…" : msg);
        }
        return m;
    }

    // -------------------------------------------------------------- LogSync

    /** Start continuous WAL shipping from a PostgreSQL source into its dSource dataset. */
    public Map<String, Object> enableLogSync(DataSourceEntity ds) {
        ContainerVdbProvider.PgEndpoint src = ContainerVdbProvider.pgEndpoint(ds);
        requireEngine();
        try {
            String major = sourceMajorVersion(ds);
            String dataset = dsourceDataset(ds);
            ensureDataset(dataset);
            String walDir = mountpoint(dataset) + "/wal";
            String container = "forgetdm-logsync-ds" + ds.getId();
            String slot = "forgetdm_ds" + ds.getId();
            String conn = "-h " + shq(engineVisibleHost(src.host())) + " -p " + src.port()
                    + " -U " + shq(nullToEmpty(ds.getUsername()));
            zfs.exec(60, "mkdir -p " + shq(walDir) + " && docker rm -f " + shq(container) + " 2>/dev/null; true");
            zfs.exec(120, "docker run -d --name " + shq(container)
                    + " " + dockerNet() + "--restart unless-stopped --add-host=host.docker.internal:host-gateway"
                    + " -e PGPASSWORD=" + shq(nullToEmpty(ds.getPassword()))
                    + " -v " + shq(walDir) + ":/wal postgres:" + major
                    + " bash -c " + shq("pg_receivewal --create-slot --slot=" + slot + " " + conn + " 2>/dev/null;"
                            + " exec pg_receivewal --slot=" + slot + " " + conn + " -D /wal"));
            audit.log("system", "VIRT_LOGSYNC_ENABLED", "source=" + ds.getName() + " slot=" + slot);
            return logSyncStatus(ds);
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("Enable LogSync failed: " + e.getMessage()); }
    }

    public Map<String, Object> disableLogSync(DataSourceEntity ds) {
        requireEngine();
        String container = "forgetdm-logsync-ds" + ds.getId();
        String slot = "forgetdm_ds" + ds.getId();
        try {
            zfs.exec(60, "docker rm -f " + shq(container) + " 2>/dev/null; true");
            // drop the slot so the source stops retaining WAL for us
            try (Connection c = connections.open(ds); Statement st = c.createStatement()) {
                st.execute("SELECT pg_drop_replication_slot('" + slot + "')");
            } catch (Exception ignored) { }
            audit.log("system", "VIRT_LOGSYNC_DISABLED", "source=" + ds.getName());
            return logSyncStatus(ds);
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("Disable LogSync failed: " + e.getMessage()); }
    }

    public Map<String, Object> logSyncStatus(DataSourceEntity ds) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataSourceId", ds.getId());
        String container = "forgetdm-logsync-ds" + ds.getId();
        try {
            String running = zfs.exec(20, "docker inspect -f '{{.State.Running}}' " + shq(container) + " 2>/dev/null || echo false");
            out.put("running", running.trim().endsWith("true"));
            String walDir = mountpoint(dsourceDataset(ds)) + "/wal";
            out.put("lastWal", zfs.exec(20, "ls -1 " + shq(walDir) + " 2>/dev/null | tail -1 || true"));
            out.put("walBytes", parseLong(zfs.exec(20, "du -sb " + shq(walDir) + " 2>/dev/null | cut -f1 || echo 0")));
        } catch (Exception e) {
            out.put("running", false);
        }
        return out;
    }

    // ------------------------------------------------------------ ingestion

    public VirtualSnapshotEntity snapshotDataSource(DataSourceEntity ds, String schemaName, String name, String note) {
        SqlDialect dialect = SqlDialect.of(ds);
        switch (dialect) {
            case POSTGRES: return snapshotPostgres(ds, name, note);
            case SQLSERVER: return snapshotMssql(ds, name, note);
            case DB2: return snapshotJdbcLogical(ds, schemaName, name, note, isDb2Zos(ds) ? "DB2ZOS" : "DB2");
            case ORACLE: return snapshotJdbcLogical(ds, schemaName, name, note, "ORACLE");
            default: throw ApiException.bad("ZFS provider: unsupported dialect " + dialect
                    + " for source '" + ds.getName() + "'. Supported: POSTGRES, SQLSERVER, DB2, DB2ZOS, ORACLE.");
        }
    }

    private static boolean isDb2Zos(DataSourceEntity ds) {
        String kind = ds.getKind() == null ? "" : ds.getKind().trim().toUpperCase(Locale.ROOT);
        return kind.equals("DB2ZOS") || kind.equals("DB2_ZOS");
    }

    private VirtualSnapshotEntity snapshotPostgres(DataSourceEntity ds, String name, String note) {
        ContainerVdbProvider.PgEndpoint src = ContainerVdbProvider.pgEndpoint(ds);
        requireEngine();
        try {
            ops.stage("Preparing engine dataset");
            String major = sourceMajorVersion(ds);
            TimeFlowEntity flow = zfsTimeflow(ds);
            String dataset = dsourceDataset(ds);
            ensureDataset(dataset);
            ensureDataset(zfs.config().getPool() + "/staging");
            String dsMp = mountpoint(dataset);
            String staging = mountpoint(zfs.config().getPool() + "/staging") + "/sync-" + System.currentTimeMillis();

            ops.stage("Running pg_basebackup from source (this can take a while)");
            // Named + cancel hook so a runaway/slow backup can be stopped from the UI (docker kill on the engine).
            String bbContainer = "forgetdm-bb-" + safeName(ds.getName()) + "-" + System.currentTimeMillis();
            ops.onCancel(() -> { try { zfs.exec(30, "docker kill " + shq(bbContainer)); } catch (Exception ignore) { } });
            zfs.exec(3600 * 6, "docker run --rm --name " + shq(bbContainer) + " " + dockerNet() + "--add-host=host.docker.internal:host-gateway"
                    + " -e PGPASSWORD=" + shq(nullToEmpty(ds.getPassword()))
                    + " -v " + shq(staging) + ":/staging"
                    + " postgres:" + major
                    + " pg_basebackup -h " + shq(engineVisibleHost(src.host()))
                    + " -p " + src.port()
                    + " -U " + shq(nullToEmpty(ds.getUsername()))
                    + " -D /staging/data -Fp -Xs -c fast");

            ops.stage("Syncing changed blocks into the pool (rsync)");
            zfs.exec(3600 * 2, "mkdir -p " + shq(dsMp + "/data")
                    + " && rsync -a --delete --inplace --no-whole-file " + shq(staging + "/data/") + " " + shq(dsMp + "/data/")
                    + " && rm -rf " + shq(staging));

            ops.stage("Creating ZFS snapshot");
            return saveDsourceSnapshot(ds, flow, dataset, name, note, pgStats(ds));
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("ZFS snapshot failed: " + e.getMessage()); }
    }

    /**
     * SQL Server sync: BACKUP DATABASE over JDBC on the source, restore the .bak into
     * the dSource dataset with an ephemeral mssql container — the restored .mdf/.ldf
     * datafiles live on ZFS, so snapshots/clones work block-level exactly like Postgres.
     * Requires forgetdm.virtualization.zfs.mssql-backup-mount: an engine path where the
     * source's backup directory is accessible (NFS/SMB mount of the source backup share).
     */
    private VirtualSnapshotEntity snapshotMssql(DataSourceEntity ds, String name, String note) {
        MssqlEndpoint src = mssqlEndpoint(ds);
        requireEngine();
        String backupMount = zfs.config().getMssqlBackupMount();
        if (backupMount == null || backupMount.isBlank()) {
            throw ApiException.bad("Set forgetdm.virtualization.zfs.mssql-backup-mount: an engine path where the "
                    + "SQL Server backup directory (" + zfs.config().getMssqlSourceBackupDir() + ") is mounted.");
        }
        String sa = saPassword(ds);
        String helper = "forgetdm-mssql-sync-ds" + ds.getId();
        try {
            TimeFlowEntity flow = zfsTimeflow(ds);
            String dataset = dsourceDataset(ds);
            ensureDataset(dataset);
            String dsMp = mountpoint(dataset);
            String bak = "forgetdm-ds" + ds.getId() + ".bak";

            // 1. native backup on the source (its own engine writes the file)
            try (Connection c = connections.open(ds); Statement st = c.createStatement()) {
                st.execute("BACKUP DATABASE [" + src.database() + "] TO DISK = N'"
                        + zfs.config().getMssqlSourceBackupDir().replace("'", "''") + "/" + bak
                        + "' WITH INIT, COPY_ONLY, COMPRESSION");
            }

            // 2. restore into the dataset via an ephemeral mssql container on the engine
            zfs.exec(60, "test -f " + shq(backupMount + "/" + bak));
            zfs.exec(60, "docker rm -f " + shq(helper) + " 2>/dev/null; mkdir -p " + shq(dsMp + "/data"));
            zfs.exec(300, "docker run -d --name " + shq(helper)
                    + " -e ACCEPT_EULA=Y -e MSSQL_SA_PASSWORD=" + shq(sa)
                    + " -v " + shq(backupMount) + ":/backup:ro"
                    + " -v " + shq(dsMp + "/data") + ":/restore "
                    + zfs.config().getMssqlImage());
            waitReadyMssql(engine(), helper, sa, 180);

            String fileList = zfs.exec(120, "docker exec " + shq(helper) + " " + SQLCMD + " -P " + shq(sa)
                    + " -h -1 -W -s '|' -Q " + shq("SET NOCOUNT ON; RESTORE FILELISTONLY FROM DISK='/backup/" + bak + "'"));
            String moves = buildMoveClauses(fileList);
            zfs.exec(3600 * 4, "docker exec " + shq(helper) + " " + SQLCMD + " -P " + shq(sa)
                    + " -Q " + shq("RESTORE DATABASE [" + src.database() + "] FROM DISK='/backup/" + bak
                            + "' WITH REPLACE" + moves));
            // detach cleanly so the datafiles are consistent on the dataset, then drop the helper
            zfs.exec(120, "docker exec " + shq(helper) + " " + SQLCMD + " -P " + shq(sa)
                    + " -Q " + shq("ALTER DATABASE [" + src.database() + "] SET OFFLINE WITH ROLLBACK IMMEDIATE;"
                            + " EXEC sp_detach_db '" + src.database() + "'"));
            zfs.exec(60, "docker rm -f " + shq(helper));

            return saveDsourceSnapshot(ds, flow, dataset, name, note, mssqlStats(ds));
        } catch (ApiException e) { tryCleanupContainer(engine(), helper); throw e; }
        catch (Exception e) { tryCleanupContainer(engine(), helper); throw ApiException.bad("SQL Server ZFS sync failed: " + e.getMessage()); }
    }

    /**
     * DB2 LUW, DB2 z/OS, Oracle — JDBC logical snapshot stored on ZFS.
     *
     * Strategy: stream all rows from the source via JDBC into the ChunkStore (same dedup+compress
     * as the POOL provider). Write a tiny marker file (dialect + manifest hash) to the ZFS dSource
     * dataset and take a ZFS snapshot. The snapshot itself is near-zero bytes; all data is in the
     * chunk pool. VDB provisioning clones the snapshot, starts the appropriate DB container, and
     * materializes the data via TimeFlowEngine.materialize() over JDBC.
     *
     * Advantages over POOL:
     * - ZFS instant snapshots and instant thin clones (no full copy per VDB)
     * - ZFS block-level compression and dedup at the dataset level
     * - VDB runs a real DB2 / Oracle engine, not H2
     *
     * For DB2 z/OS: the snapshot is identical to DB2 LUW; the VDB container is a DB2 LUW image
     * (cross-platform — suitable for dev/test, where dialect compatibility is close enough).
     */
    private VirtualSnapshotEntity snapshotJdbcLogical(DataSourceEntity ds, String schemaName, String name, String note,
                                                       String dialectLabel) {
        requireEngine();
        SqlDialect dialect = SqlDialect.of(ds);
        try {
            TimeFlowEntity flow = zfsTimeflow(ds);
            String dataset = dsourceDataset(ds);
            ensureDataset(dataset);
            String dsMp = mountpoint(dataset);

            // Ingest all rows via JDBC → ChunkStore (dedup + gzip compressed)
            TimeFlowEngine.IngestResult r;
            String schema;
            try (Connection c = connections.open(ds)) {
                schema = DataSourceService.normalizeSchema(c, schemaName);
                r = engine.ingest(c, schema);
            }
            if (r.tableCount() <= 0) {
                throw ApiException.bad("No tables were found in schema '" + String.valueOf(schema)
                        + "' on data source '" + ds.getName() + "'. Verify the schema in Browse and capture again.");
            }

            // Write marker file so provision() knows dialect + manifest hash
            zfs.exec(30, "printf '%s\\n' "
                    + shq("DIALECT=" + dialectLabel) + " "
                    + shq("MANIFEST_HASH=" + r.manifestHash())
                    + " > " + shq(dsMp + "/" + FORGE_MANIFEST_FILE));

            return saveDsourceSnapshotLogical(ds, flow, dataset, name, note, r, dialectLabel, schema);
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("ZFS logical snapshot failed: " + e.getMessage()); }
    }

    private VirtualSnapshotEntity saveDsourceSnapshotLogical(DataSourceEntity ds, TimeFlowEntity flow,
            String dataset, String name, String note, TimeFlowEngine.IngestResult r, String dialectLabel,
            String schema) {
        String prevSnap = latestSnapName(flow.getId());
        long written = prevSnap == null ? -1
                : parseLong(zfs.exec(30, "zfs get -Hp -o value written@" + prevSnap + " " + shq(dataset)));
        String snapName = "s" + System.currentTimeMillis();
        zfs.exec(60, "zfs snapshot " + shq(dataset + "@" + snapName));
        long referenced = parseLong(zfs.exec(30, "zfs get -Hp -o value referenced " + shq(dataset)));

        VirtualSnapshotEntity e = new VirtualSnapshotEntity();
        e.setName(name == null || name.isBlank() ? ds.getName() + " snapshot" : name.trim());
        e.setSnapshotType("DSOURCE");
        e.setProvider("ZFS");
        e.setSourceId(ds.getId());
        e.setSchemaName(schema);
        e.setTimeflowId(flow.getId());
        e.setImageRef(dataset + "@" + snapName);
        e.setStoragePath("zfs:" + dataset + "@" + snapName);
        e.setManifestHash(r.manifestHash());
        e.setTableCount(r.tableCount());
        e.setRowCount(r.rowCount());
        e.setChunkCount(r.chunkCount());
        e.setNewChunkCount(r.newChunkCount());
        e.setLogicalBytes(r.logicalBytes());
        e.setStoredBytes(written >= 0 ? written : referenced);
        e.setNote(blankToNull(note));
        VirtualSnapshotEntity saved = snapshots.save(e);
        audit.log("system", "VIRT_DSOURCE_SNAPSHOT", "snapshot=" + saved.getId() + " source=" + ds.getName()
                + " provider=ZFS(" + dialectLabel + ")" + " " + dataset + "@" + snapName
                + " chunks=" + r.chunkCount() + " newChunks=" + r.newChunkCount());
        return saved;
    }

    private VirtualSnapshotEntity saveDsourceSnapshot(DataSourceEntity ds, TimeFlowEntity flow, String dataset,
                                                      String name, String note, long[] stats) {
        String prevSnap = latestSnapName(flow.getId());
        long written = prevSnap == null ? -1
                : parseLong(zfs.exec(30, "zfs get -Hp -o value written@" + prevSnap + " " + shq(dataset)));
        String snapName = "s" + System.currentTimeMillis();
        zfs.exec(60, "zfs snapshot " + shq(dataset + "@" + snapName));
        long referenced = parseLong(zfs.exec(30, "zfs get -Hp -o value referenced " + shq(dataset)));

        VirtualSnapshotEntity e = new VirtualSnapshotEntity();
        e.setName(name == null || name.isBlank() ? ds.getName() + " snapshot" : name.trim());
        e.setSnapshotType("DSOURCE");
        e.setProvider("ZFS");
        e.setSourceId(ds.getId());
        e.setTimeflowId(flow.getId());
        e.setImageRef(dataset + "@" + snapName);
        e.setStoragePath("zfs:" + dataset + "@" + snapName);
        e.setTableCount((int) stats[0]);
        e.setRowCount(stats[1]);
        e.setLogicalBytes(referenced);
        e.setStoredBytes(written >= 0 ? written : referenced);
        e.setNote(blankToNull(note));
        VirtualSnapshotEntity saved = snapshots.save(e);
        audit.log("system", "VIRT_DSOURCE_SNAPSHOT", "snapshot=" + saved.getId() + " source=" + ds.getName()
                + " provider=ZFS " + dataset + "@" + snapName
                + (written >= 0 ? " changedBytes=" + written : " (initial sync)"));
        return saved;
    }

    // ---------------------------------------------------------- provisioning

    public VirtualDatabaseEntity provision(VirtualSnapshotEntity snapshot, String cleanName,
                                           String pointInTime, Long environmentId) {
        requireEngine();
        DataSourceEntity origin = dataSources.get(snapshot.getSourceId());
        String safe = safeName(cleanName);
        String clone = zfs.config().getPool() + "/vdbs/" + safe;
        String container = "forgetdm-zvdb-" + safe;
        String dsName = "vdb-" + cleanName;
        if (dataSourceRepo.findByName(dsName).isPresent())
            throw ApiException.bad("Data source '" + dsName + "' already exists");
        TargetEnvironmentEntity environment = environmentId == null ? null : environment(environmentId);
        try {
            ops.stage("Cloning snapshot (instant thin clone)");
            zfs.exec(60, "zfs clone " + shq(snapshot.getImageRef()) + " " + shq(clone));
            String mp = mountpoint(clone);
            boolean isPostgres = isPostgresDataset(mp);
            boolean hasMssql = !isPostgres && hasMssqlDatafiles(mp);
            String logicalDialect = (!isPostgres && !hasMssql) ? readLogicalDialect(mp) : null;
            boolean isLogical = logicalDialect != null;

            if (pointInTime != null && !pointInTime.isBlank() && !isPostgres) {
                throw ApiException.bad("Point-in-time provisioning requires a PostgreSQL dSource with LogSync.");
            }
            if (environmentId != null && isLogical) {
                throw ApiException.bad("Environment-hosted VDBs are supported for PostgreSQL only.");
            }
            if (pointInTime != null && !pointInTime.isBlank()) {
                writeRecoveryConfig(mp, snapshot, pointInTime);
            }

            // --- LOGICAL dialect (DB2 LUW / DB2 z/OS / Oracle): materialize from ChunkStore ---
            if (isLogical) {
                return provisionLogicalVdb(snapshot, cleanName, dsName, clone, mp, container, logicalDialect);
            }

            ops.stage("Starting the VDB database engine");
            Shell shell = environment == null ? engine() : env(environment);
            String dataPath = mp + "/data";
            String serveHost;
            if (environment != null) {
                // NFS mount/export model: NFS-export the clone, mount it on the target environment
                if (!isPostgres) throw ApiException.bad("Environment-hosted VDBs currently support PostgreSQL.");
                zfs.exec(60, "zfs set sharenfs='rw,no_root_squash,insecure' " + shq(clone));
                String mount = environment.getMountBase() + "/" + safe;
                shell.exec(120, "mkdir -p " + shq(mount) + " && mountpoint -q " + shq(mount)
                        + " || mount -t nfs " + shq(engineAddress() + ":" + mp) + " " + shq(mount));
                dataPath = mount + "/data";
                serveHost = environment.getHost();
            } else {
                serveHost = zfs.remote() ? zfs.config().getHost() : "localhost";
            }

            int hostPort;
            String jdbcUrl, username, password;
            if (isPostgres) {
                String major = shell.exec(30, "cat " + shq(dataPath + "/PG_VERSION")).trim();
                String walMount = pointInTime != null && !pointInTime.isBlank()
                        ? " -v " + shq(dsourceWalDir(snapshot)) + ":/wal:ro" : "";
                shell.exec(120, "docker run -d --name " + shq(container)
                        + " -v " + shq(dataPath) + ":/var/lib/postgresql/data" + walMount
                        + " -p 0:5432 postgres:" + major);
                hostPort = publishedPort(shell, container, "5432/tcp");
                waitReadyPg(shell, container, pointInTime != null ? 600 : 90);
                ContainerVdbProvider.PgEndpoint src = ContainerVdbProvider.pgEndpoint(origin);
                jdbcUrl = "jdbc:postgresql://" + serveHost + ":" + hostPort + "/" + src.database();
                username = origin.getUsername(); password = origin.getPassword();
            } else {
                MssqlEndpoint src = mssqlEndpoint(origin);
                String sa = saPassword(origin);
                shell.exec(300, "docker run -d --name " + shq(container)
                        + " -e ACCEPT_EULA=Y -e MSSQL_SA_PASSWORD=" + shq(sa)
                        + " -v " + shq(dataPath) + ":/attach"
                        + " -p 0:1433 " + zfs.config().getMssqlImage());
                hostPort = publishedPort(shell, container, "1433/tcp");
                waitReadyMssql(shell, container, sa, 240);
                attachDatabase(shell, container, sa, src.database(), dataPath);
                jdbcUrl = "jdbc:sqlserver://" + serveHost + ":" + hostPort + ";databaseName=" + src.database()
                        + ";encrypt=false;trustServerCertificate=true";
                username = "sa"; password = sa;
            }

            DataSourceEntity ds = new DataSourceEntity();
            ds.setName(dsName);
            ds.setKind(isPostgres ? "POSTGRES" : "SQLSERVER");
            ds.setRole("BOTH");
            ds.setJdbcUrl(jdbcUrl);
            ds.setUsername(username);
            ds.setPassword(password);
            ds = dataSources.create(ds);

            VirtualDatabaseEntity vdb = new VirtualDatabaseEntity();
            vdb.setName(cleanName);
            vdb.setProvider("ZFS");
            vdb.setSourceSnapshotId(snapshot.getId());
            vdb.setCurrentSnapshotId(snapshot.getId());
            vdb.setDataSourceId(ds.getId());
            vdb.setJdbcUrl(jdbcUrl);
            vdb.setSchemaName(snapshot.getSchemaName());
            vdb.setUsername(username);
            vdb.setPassword(password);
            vdb.setStoragePath("zfs:" + clone);
            vdb.setContainerId(container);
            vdb.setHostPort(hostPort);
            vdb.setEnvironmentId(environmentId);
            vdb.setTargetKind(environment != null ? "ZFS_NFS" : isPostgres ? "ZFS_PG" : "ZFS_MSSQL");
            vdb.setStatus("ACTIVE");
            VirtualDatabaseEntity saved = vdbs.save(vdb);

            TimeFlowEntity flow = new TimeFlowEntity();
            flow.setName("vdb-" + cleanName);
            flow.setContainerType("VDB");
            flow.setVdbId(saved.getId());
            flow.setParentSnapshotId(snapshot.getId());
            flow = timeflows.save(flow);
            saved.setTimeflowId(flow.getId());
            saved = vdbs.save(saved);

            audit.log("system", "VIRT_VDB_PROVISIONED", "vdb=" + cleanName + " snapshot=" + snapshot.getId()
                    + " provider=ZFS clone=" + clone + " port=" + hostPort
                    + (pointInTime != null && !pointInTime.isBlank() ? " pointInTime=" + pointInTime : "")
                    + (environment != null ? " environment=" + environment.getName() : ""));
            return saved;
        } catch (ApiException e) { cleanupClone(container, clone); throw e; }
        catch (Exception e) { cleanupClone(container, clone); throw ApiException.bad("ZFS provision failed: " + e.getMessage()); }
    }

    // ------------------------------------------------- logical VDB (DB2 / Oracle)

    /**
     * Provision a DB2 or Oracle VDB from a logical (JDBC-ingested) ZFS snapshot.
     *
     * Flow: ZFS clone is already created. Read manifest hash from the marker file in the
     * clone. Start the appropriate container (DB2 Community / Oracle Free). Wait for the
     * engine to be ready, then materialize the snapshot data via TimeFlowEngine.materialize()
     * over a JDBC connection to the container. Register the container as a ForgeTDM datasource.
     */
    private VirtualDatabaseEntity provisionLogicalVdb(VirtualSnapshotEntity snapshot, String cleanName,
            String dsName, String clone, String mp, String container, String dialectLabel) {
        boolean isOracle = "ORACLE".equals(dialectLabel);

        // Read the manifest hash written during snapshot (could also use snapshot.getManifestHash())
        String manifestHash = snapshot.getManifestHash() != null
                ? snapshot.getManifestHash() : readManifestHash(mp);
        SnapshotManifest manifest = engine.loadManifest(manifestHash);

        String serveHost = zfs.remote() ? zfs.config().getHost() : "localhost";
        int hostPort;
        String jdbcUrl, username, password;
        String kind;

        if (isOracle) {
            String oraDb = "VDB";
            String oraPw = zfs.config().getOracleSysPassword();
            engine().exec(600, "docker run -d --name " + shq(container)
                    + " -e ORACLE_PASSWORD=" + shq(oraPw)
                    + " -e ORACLE_DATABASE=" + shq(oraDb)
                    + " -e ORACLE_CHARACTERSET=AL32UTF8"
                    + " -p 0:1521"
                    + " " + shq(zfs.config().getOracleImage()));
            hostPort = publishedPort(engine(), container, "1521/tcp");
            waitReadyOracle(container, oraDb, oraPw, 600);
            jdbcUrl = "jdbc:oracle:thin:@//" + serveHost + ":" + hostPort + "/" + oraDb;
            username = "system"; password = oraPw;
            kind = "ORACLE";
        } else {
            // DB2 LUW or DB2 z/OS (both materialize into DB2 LUW container)
            String db2Db = "VDB";
            String db2Pw = zfs.config().getDb2InstancePassword();
            engine().exec(600, "docker run -d --name " + shq(container)
                    + " --privileged"
                    + " -e LICENSE=accept"
                    + " -e DB2INST1_PASSWORD=" + shq(db2Pw)
                    + " -e DBNAME=" + db2Db
                    + " -p 0:50000"
                    + " " + shq(zfs.config().getDb2Image()));
            hostPort = publishedPort(engine(), container, "50000/tcp");
            waitReadyDb2(container, db2Db, db2Pw, 600);
            jdbcUrl = "jdbc:db2://" + serveHost + ":" + hostPort + "/" + db2Db;
            username = "db2inst1"; password = db2Pw;
            kind = "DB2";
        }

        // Materialize all tables from the ChunkStore into the running container
        SqlDialect dialect = isOracle ? SqlDialect.ORACLE : SqlDialect.DB2;
        try (Connection c = openJdbc(jdbcUrl, username, password)) {
            engine.materialize(c, dialect, manifest);
        } catch (Exception e) {
            tryCleanupContainer(engine(), container);
            throw ApiException.bad("Failed to materialize VDB data into " + dialectLabel + " container: " + e.getMessage());
        }

        // Register as a ForgeTDM datasource
        DataSourceEntity ds = new DataSourceEntity();
        ds.setName(dsName);
        ds.setKind(kind);
        ds.setRole("BOTH");
        ds.setJdbcUrl(jdbcUrl);
        ds.setUsername(username);
        ds.setPassword(password);
        ds = dataSources.create(ds);

        String targetKind = isOracle ? "ZFS_ORACLE" : ("DB2ZOS".equals(dialectLabel) ? "ZFS_DB2ZOS" : "ZFS_DB2");
        VirtualDatabaseEntity vdb = new VirtualDatabaseEntity();
        vdb.setName(cleanName);
        vdb.setProvider("ZFS");
        vdb.setSourceSnapshotId(snapshot.getId());
        vdb.setCurrentSnapshotId(snapshot.getId());
        vdb.setDataSourceId(ds.getId());
        vdb.setJdbcUrl(jdbcUrl);
        vdb.setSchemaName(snapshot.getSchemaName());
        vdb.setUsername(username);
        vdb.setPassword(password);
        vdb.setStoragePath("zfs:" + clone);
        vdb.setContainerId(container);
        vdb.setHostPort(hostPort);
        vdb.setTargetKind(targetKind);
        vdb.setStatus("ACTIVE");
        VirtualDatabaseEntity saved = vdbs.save(vdb);

        TimeFlowEntity flow = new TimeFlowEntity();
        flow.setName("vdb-" + cleanName);
        flow.setContainerType("VDB");
        flow.setVdbId(saved.getId());
        flow.setParentSnapshotId(snapshot.getId());
        flow = timeflows.save(flow);
        saved.setTimeflowId(flow.getId());
        saved = vdbs.save(saved);

        audit.log("system", "VIRT_VDB_PROVISIONED", "vdb=" + cleanName + " snapshot=" + snapshot.getId()
                + " provider=ZFS(" + dialectLabel + ") clone=" + clone + " port=" + hostPort);
        return saved;
    }

    /** Wait for a DB2 VDB container (takes 3-5 min to initialize the database). */
    private void waitReadyDb2(String container, String dbName, String pw, int seconds) {
        waitLoop(container, seconds, () ->
            engine().exec(30, "docker exec " + shq(container)
                    + " su - db2inst1 -c "
                    + shq("db2 connect to " + dbName + " user db2inst1 using '" + pw + "' && db2 connect reset")));
    }

    /** Wait for an Oracle Free VDB container (watches for healthy status). */
    private void waitReadyOracle(String container, String dbName, String pw, int seconds) {
        // Oracle Free container sets healthy status when the DB is open
        waitLoop(container, seconds, () -> {
            String health = engine().exec(15, "docker inspect -f '{{.State.Health.Status}}' " + shq(container)).trim();
            if (!"healthy".equals(health)) throw new RuntimeException("not healthy: " + health);
        });
    }

    private Connection openJdbc(String url, String user, String pass) {
        try {
            return java.sql.DriverManager.getConnection(url, user, pass);
        } catch (Exception e) {
            throw ApiException.bad("Cannot connect to VDB container: " + e.getMessage());
        }
    }

    /** PITR: recover the clone forward through shipped WAL to the requested time, then promote. */
    private void writeRecoveryConfig(String mp, VirtualSnapshotEntity snapshot, String pointInTime) {
        String target = pointInTime.trim().replace('T', ' ');
        String conf = mp + "/data/postgresql.auto.conf";
        zfs.exec(60, "printf '%s\\n' "
                + shq("restore_command = 'cp /wal/%f \"%p\"'") + " "
                + shq("recovery_target_time = '" + target + "'") + " "
                + shq("recovery_target_action = 'promote'")
                + " >> " + shq(conf)
                + " && touch " + shq(mp + "/data/recovery.signal"));
    }

    private String dsourceWalDir(VirtualSnapshotEntity snapshot) {
        String dataset = snapshot.getImageRef().substring(0, snapshot.getImageRef().indexOf('@'));
        return mountpoint(dataset) + "/wal";
    }

    public VirtualSnapshotEntity snapshotVdb(VirtualDatabaseEntity vdb, String label, boolean bookmark) {
        requireEngine();
        String clone = cloneOf(vdb);
        Shell shell = shellFor(vdb);
        try {
            String prevSnap = latestSnapName(vdb.getTimeflowId());
            String snapName = "b" + System.currentTimeMillis();
            shell.exec(120, "docker stop -t 30 " + shq(vdb.getContainerId()));
            long written = prevSnap == null ? -1
                    : parseLong(zfs.exec(30, "zfs get -Hp -o value written@" + prevSnap + " " + shq(clone)));
            zfs.exec(60, "zfs snapshot " + shq(clone + "@" + snapName));
            shell.exec(120, "docker start " + shq(vdb.getContainerId()));
            waitReadyFor(vdb, shell, 180);

            long[] stats = tryVdbStats(vdb);
            VirtualSnapshotEntity e = new VirtualSnapshotEntity();
            e.setName(label);
            e.setSnapshotType(bookmark ? "BOOKMARK" : "VDB_SNAPSHOT");
            e.setProvider("ZFS");
            e.setSourceId(vdb.getDataSourceId());
            e.setVdbId(vdb.getId());
            e.setTimeflowId(vdb.getTimeflowId());
            e.setImageRef(clone + "@" + snapName);
            e.setStoragePath("zfs:" + clone + "@" + snapName);
            e.setTableCount((int) stats[0]);
            e.setRowCount(stats[1]);
            e.setStoredBytes(Math.max(written, 0));
            e.setNote(bookmark ? "Named rewind point" : "VDB timeflow snapshot");
            VirtualSnapshotEntity saved = snapshots.save(e);
            vdb.setCurrentSnapshotId(saved.getId());
            vdb.setUpdatedAt(Instant.now());
            vdbs.save(vdb);
            audit.log("system", bookmark ? "VIRT_VDB_BOOKMARK" : "VIRT_VDB_SNAPSHOT",
                    "vdb=" + vdb.getName() + " snapshot=" + saved.getId() + " " + clone + "@" + snapName);
            return saved;
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("ZFS VDB snapshot failed: " + e.getMessage()); }
    }

    /** Refresh/rewind: branch a NEW timeflow from the chosen snapshot. Nothing destroyed. */
    public void restore(VirtualDatabaseEntity vdb, VirtualSnapshotEntity snapshot, String action) {
        requireEngine();
        if (!"ZFS".equals(snapshot.getProvider()) || snapshot.getImageRef() == null) {
            throw ApiException.bad("Snapshot " + snapshot.getId() + " is not a ZFS-provider snapshot.");
        }
        long ts = System.currentTimeMillis();
        String safe = safeName(vdb.getName());
        String newClone = zfs.config().getPool() + "/vdbs/" + safe + "-t" + ts;
        Shell shell = shellFor(vdb);
        TargetEnvironmentEntity environment = vdb.getEnvironmentId() == null ? null : environment(vdb.getEnvironmentId());
        try {
            shell.exec(120, "docker rm -f " + shq(vdb.getContainerId()) + " 2>/dev/null; true");
            zfs.exec(60, "zfs clone " + shq(snapshot.getImageRef()) + " " + shq(newClone));
            String mp = mountpoint(newClone);
            boolean isPostgres = isPostgresDataset(mp);

            String dataPath = mp + "/data";
            if (environment != null) {
                zfs.exec(60, "zfs set sharenfs='rw,no_root_squash,insecure' " + shq(newClone));
                String mount = environment.getMountBase() + "/" + safe;
                shell.exec(120, "umount -f " + shq(mount) + " 2>/dev/null; mkdir -p " + shq(mount)
                        + " && mount -t nfs " + shq(engineAddress() + ":" + mp) + " " + shq(mount));
                dataPath = mount + "/data";
            }

            boolean hasMssqlFiles = !isPostgres && hasMssqlDatafiles(mp);
            String logicalDial = (!isPostgres && !hasMssqlFiles) ? readLogicalDialect(mp) : null;

            if (logicalDial != null) {
                // Logical VDB (DB2/Oracle): start fresh container, re-materialize from chunk store
                boolean isOracle = "ORACLE".equals(logicalDial);
                if (isOracle) {
                    engine().exec(600, "docker run -d --name " + shq(vdb.getContainerId())
                            + " -e ORACLE_PASSWORD=" + shq(vdb.getPassword())
                            + " -e ORACLE_DATABASE=VDB -e ORACLE_CHARACTERSET=AL32UTF8"
                            + " -p " + vdb.getHostPort() + ":1521 " + shq(zfs.config().getOracleImage()));
                    waitReadyOracle(vdb.getContainerId(), "VDB", vdb.getPassword(), 600);
                } else {
                    engine().exec(600, "docker run -d --name " + shq(vdb.getContainerId())
                            + " --privileged -e LICENSE=accept"
                            + " -e DB2INST1_PASSWORD=" + shq(vdb.getPassword())
                            + " -e DBNAME=VDB"
                            + " -p " + vdb.getHostPort() + ":50000 " + shq(zfs.config().getDb2Image()));
                    waitReadyDb2(vdb.getContainerId(), "VDB", vdb.getPassword(), 600);
                }
                String manifestHash = snapshot.getManifestHash() != null ? snapshot.getManifestHash() : readManifestHash(mp);
                SnapshotManifest manifest = engine.loadManifest(manifestHash);
                SqlDialect dialect = isOracle ? SqlDialect.ORACLE : SqlDialect.DB2;
                try (Connection c = openJdbc(vdb.getJdbcUrl(), vdb.getUsername(), vdb.getPassword())) {
                    engine.materialize(c, dialect, manifest);
                } catch (Exception e) {
                    throw ApiException.bad("Re-materialize failed: " + e.getMessage());
                }
            } else if (isPostgres) {
                String major = shell.exec(30, "cat " + shq(dataPath + "/PG_VERSION")).trim();
                shell.exec(120, "docker run -d --name " + shq(vdb.getContainerId())
                        + " -v " + shq(dataPath) + ":/var/lib/postgresql/data"
                        + " -p " + vdb.getHostPort() + ":5432 postgres:" + major);
                waitReadyPg(shell, vdb.getContainerId(), 180);
            } else {
                String sa = vdb.getPassword() == null || vdb.getPassword().isBlank() ? DEFAULT_SA_PASSWORD : vdb.getPassword();
                MssqlEndpoint src = mssqlEndpoint(dataSources.get(vdb.getDataSourceId()));
                shell.exec(300, "docker run -d --name " + shq(vdb.getContainerId())
                        + " -e ACCEPT_EULA=Y -e MSSQL_SA_PASSWORD=" + shq(sa)
                        + " -v " + shq(dataPath) + ":/attach"
                        + " -p " + vdb.getHostPort() + ":1433 " + zfs.config().getMssqlImage());
                waitReadyMssql(shell, vdb.getContainerId(), sa, 240);
                attachDatabase(shell, vdb.getContainerId(), sa, src.database(), dataPath);
            }

            TimeFlowEntity branch = new TimeFlowEntity();
            branch.setName("vdb-" + vdb.getName() + "/t" + ts);
            branch.setContainerType("VDB");
            branch.setVdbId(vdb.getId());
            branch.setParentSnapshotId(snapshot.getId());
            branch = timeflows.save(branch);

            vdb.setTimeflowId(branch.getId());
            vdb.setStoragePath("zfs:" + newClone);
            vdb.setCurrentSnapshotId(snapshot.getId());
            vdb.setUpdatedAt(Instant.now());
            vdbs.save(vdb);
            audit.log("system", action, "vdb=" + vdb.getName() + " snapshot=" + snapshot.getId()
                    + " provider=ZFS branched timeflow=" + branch.getId() + " clone=" + newClone);
        } catch (ApiException e) { cleanupClone(null, newClone); throw e; }
        catch (Exception e) { cleanupClone(null, newClone); throw ApiException.bad("ZFS VDB restore failed: " + e.getMessage()); }
    }

    // ------------------------------------------------------------- deletion

    /** Destroy all of a VDB's branch clones, newest first. Refused if other VDBs depend on them. */
    public void deleteVdb(VirtualDatabaseEntity vdb) {
        requireEngine();
        Shell shell = shellFor(vdb);
        try { shell.exec(120, "docker rm -f " + shq(vdb.getContainerId()) + " 2>/dev/null; true"); } catch (Exception ignored) { }
        if (vdb.getEnvironmentId() != null) {
            try {
                TargetEnvironmentEntity environment = environment(vdb.getEnvironmentId());
                String mount = environment.getMountBase() + "/" + safeName(vdb.getName());
                env(environment).exec(120, "umount -f " + shq(mount) + " 2>/dev/null; rmdir " + shq(mount) + " 2>/dev/null; true");
            } catch (Exception ignored) { }
        }
        List<String> datasets = vdbDatasets(safeName(vdb.getName()));
        Collections.reverse(datasets); // newest branch first; original clone last
        for (String dataset : datasets) {
            try {
                zfs.exec(120, "zfs destroy -r " + shq(dataset));
            } catch (ApiException e) {
                throw ApiException.bad("Cannot delete VDB '" + vdb.getName() + "': dataset " + dataset
                        + " still has dependents (another VDB was provisioned from one of its bookmarks). "
                        + "Delete the dependent VDB first. (" + e.getMessage() + ")");
            }
        }
        audit.log("system", "VIRT_VDB_DELETED", "vdb=" + vdb.getName() + " datasets=" + datasets.size());
    }

    /** zfs destroy a single snapshot; refused by ZFS if a clone depends on it. */
    public void deleteSnapshot(VirtualSnapshotEntity snapshot) {
        requireEngine();
        try {
            zfs.exec(120, "zfs destroy " + shq(snapshot.getImageRef()));
        } catch (ApiException e) {
            throw ApiException.bad("Cannot delete snapshot " + snapshot.getId()
                    + ": a VDB clone depends on it. Delete or refresh the dependent VDB first. (" + e.getMessage() + ")");
        }
        audit.log("system", "VIRT_SNAPSHOT_DELETED", "snapshot=" + snapshot.getId() + " " + snapshot.getImageRef());
    }

    private List<String> vdbDatasets(String safe) {
        String base = zfs.config().getPool() + "/vdbs/" + safe;
        String out;
        try {
            out = zfs.exec(60, "zfs list -H -o name -r " + shq(zfs.config().getPool() + "/vdbs") + " 2>/dev/null || true");
        } catch (Exception e) { return List.of(); }
        List<String> matches = new ArrayList<>();
        for (String line : out.split("\\R")) {
            String name = line.trim();
            if (name.equals(base) || name.matches(Pattern.quote(base) + "-t[0-9]+")) matches.add(name);
        }
        Collections.sort(matches); // original first, branches in creation order
        return matches;
    }

    // ----------------------------------------------------- engine readiness

    private void waitReadyFor(VirtualDatabaseEntity vdb, Shell shell, int seconds) {
        String kind = vdb.getTargetKind() == null ? "" : vdb.getTargetKind();
        if ("ZFS_MSSQL".equals(kind)) {
            String sa = vdb.getPassword() == null || vdb.getPassword().isBlank() ? DEFAULT_SA_PASSWORD : vdb.getPassword();
            waitReadyMssql(shell, vdb.getContainerId(), sa, seconds);
        } else if ("ZFS_DB2".equals(kind) || "ZFS_DB2ZOS".equals(kind)) {
            waitReadyDb2(vdb.getContainerId(), "VDB", vdb.getPassword(), seconds);
        } else if ("ZFS_ORACLE".equals(kind)) {
            waitReadyOracle(vdb.getContainerId(), "VDB", vdb.getPassword(), seconds);
        } else {
            waitReadyPg(shell, vdb.getContainerId(), seconds);
        }
    }

    private void waitReadyPg(Shell shell, String container, int seconds) {
        waitLoop(container, seconds, () -> shell.exec(15, "docker exec " + shq(container) + " pg_isready -q"));
    }

    private void waitReadyMssql(Shell shell, String container, String sa, int seconds) {
        waitLoop(container, seconds, () -> shell.exec(20, "docker exec " + shq(container) + " " + SQLCMD
                + " -P " + shq(sa) + " -Q 'SELECT 1' -b -o /dev/null"));
    }

    private void waitLoop(String container, int seconds, Runnable probe) {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try { probe.run(); return; }
            catch (Exception notYet) {
                try { Thread.sleep(1500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        }
        throw ApiException.bad("VDB container '" + container + "' did not become ready within " + seconds + "s");
    }

    // ----------------------------------------------------------- mssql bits

    record MssqlEndpoint(String host, int port, String database) {}

    static MssqlEndpoint mssqlEndpoint(DataSourceEntity ds) {
        String url = ds.getJdbcUrl() == null ? "" : ds.getJdbcUrl();
        Matcher m = MSSQL_URL.matcher(url);
        if (!m.matches()) {
            throw ApiException.bad("Expected jdbc:sqlserver://host:port;databaseName=db — '" + ds.getName() + "' has: " + url);
        }
        return new MssqlEndpoint(m.group(1), m.group(2) == null ? 1433 : Integer.parseInt(m.group(2)), m.group(3));
    }

    private String saPassword(DataSourceEntity origin) {
        return origin.getPassword() == null || origin.getPassword().isBlank() ? DEFAULT_SA_PASSWORD : origin.getPassword();
    }

    /** Parse RESTORE FILELISTONLY pipe-separated output into WITH MOVE clauses targeting /restore. */
    static String buildMoveClauses(String fileList) {
        StringBuilder moves = new StringBuilder();
        int dataIdx = 0;
        for (String line : fileList.split("\\R")) {
            String[] parts = line.split("\\|");
            if (parts.length < 3) continue;
            String logical = parts[0].trim(), type = parts[2].trim();
            if (logical.isEmpty() || (!type.equals("D") && !type.equals("L"))) continue;
            String ext = type.equals("L") ? ".ldf" : (dataIdx++ == 0 ? ".mdf" : ".ndf");
            String file = logical.replaceAll("[^A-Za-z0-9_]", "_") + ext;
            moves.append(", MOVE '").append(logical.replace("'", "''")).append("' TO '/restore/").append(file).append("'");
        }
        if (moves.length() == 0) throw ApiException.bad("Could not parse RESTORE FILELISTONLY output: " + fileList);
        return moves.toString();
    }

    private void attachDatabase(Shell shell, String container, String sa, String database, String dataPath) {
        String files = shell.exec(30, "ls -1 " + shq(dataPath));
        StringBuilder on = new StringBuilder();
        for (String f : files.split("\\R")) {
            String name = f.trim();
            if (name.endsWith(".mdf") || name.endsWith(".ndf") || name.endsWith(".ldf")) {
                if (on.length() > 0) on.append(", ");
                on.append("(FILENAME='/attach/").append(name).append("')");
            }
        }
        if (on.length() == 0) throw ApiException.bad("No .mdf/.ldf datafiles found on the clone at " + dataPath);
        shell.exec(300, "docker exec " + shq(container) + " " + SQLCMD + " -P " + shq(sa)
                + " -Q " + shq("CREATE DATABASE [" + database + "] ON " + on + " FOR ATTACH"));
    }

    // ---------------------------------------------------------------- utils

    private String dsourceDataset(DataSourceEntity ds) {
        return zfs.config().getPool() + "/dsources/ds-" + ds.getId();
    }

    private boolean isPostgresDataset(String mp) {
        try {
            zfs.exec(20, "test -f " + shq(mp + "/data/PG_VERSION"));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasMssqlDatafiles(String mp) {
        try {
            String out = zfs.exec(20, "find " + shq(mp + "/data") + " -name '*.mdf' 2>/dev/null | head -1");
            return out != null && !out.isBlank();
        } catch (Exception e) { return false; }
    }

    private String readLogicalDialect(String mp) {
        try {
            String content = zfs.exec(20, "cat " + shq(mp + "/" + FORGE_MANIFEST_FILE) + " 2>/dev/null || true");
            for (String line : content.split("\\R")) {
                if (line.startsWith("DIALECT=")) return line.substring(8).trim();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String readManifestHash(String mp) {
        try {
            String content = zfs.exec(20, "cat " + shq(mp + "/" + FORGE_MANIFEST_FILE));
            for (String line : content.split("\\R")) {
                if (line.startsWith("MANIFEST_HASH=")) return line.substring(14).trim();
            }
        } catch (Exception ignored) {}
        throw ApiException.bad("Cannot read manifest hash from logical ZFS dataset at " + mp
                + " — the snapshot may have been created with an older version of ForgeTDM.");
    }

    private String engineAddress() {
        if (zfs.remote()) return zfs.config().getHost();
        throw ApiException.bad("NFS environments need forgetdm.virtualization.zfs.host set to the engine's address.");
    }

    private void ensureDataset(String dataset) {
        zfs.exec(60, "zfs list -H -o name " + shq(dataset)
                + " >/dev/null 2>&1 || zfs create -p -o compression=lz4 " + shq(dataset));
    }

    private String mountpoint(String dataset) {
        return zfs.exec(30, "zfs get -H -o value mountpoint " + shq(dataset)).trim();
    }

    private String latestSnapName(Long timeflowId) {
        return snapshots.findAll().stream()
                .filter(s -> Objects.equals(s.getTimeflowId(), timeflowId))
                .filter(s -> "ZFS".equals(s.getProvider()) && s.getImageRef() != null && s.getImageRef().contains("@"))
                .max(Comparator.comparing(VirtualSnapshotEntity::getCreatedAt))
                .map(s -> s.getImageRef().substring(s.getImageRef().indexOf('@') + 1))
                .orElse(null);
    }

    private String cloneOf(VirtualDatabaseEntity vdb) {
        String sp = vdb.getStoragePath() == null ? "" : vdb.getStoragePath();
        if (!sp.startsWith("zfs:")) throw ApiException.bad("VDB " + vdb.getName() + " has no ZFS clone");
        return sp.substring(4);
    }

    private int publishedPort(Shell shell, String container, String portSpec) {
        String out = shell.exec(30, "docker port " + shq(container) + " " + portSpec);
        Matcher m = Pattern.compile(":(\\d+)\\s*$", Pattern.MULTILINE).matcher(out.trim());
        if (!m.find()) throw ApiException.bad("Could not determine published port for " + container + ": " + out);
        return Integer.parseInt(m.group(1));
    }

    private void cleanupClone(String container, String clone) {
        try {
            String rmContainer = container == null ? "" : "docker rm -f " + shq(container) + " >/dev/null 2>&1; ";
            zfs.exec(60, rmContainer + "zfs destroy -r " + shq(clone) + " 2>/dev/null || true");
        } catch (Exception ignored) { }
    }

    private void tryCleanupContainer(Shell shell, String container) {
        try { shell.exec(60, "docker rm -f " + shq(container) + " 2>/dev/null; true"); } catch (Exception ignored) { }
    }

    private String engineVisibleHost(String host) {
        if (!host.equals("localhost") && !host.equals("127.0.0.1")) return host;
        String alias = zfs.config().getLocalhostAlias();
        if (alias != null && !alias.isBlank()) return alias;
        if (!zfs.remote()) return "host.docker.internal";
        throw ApiException.bad("Source is registered as localhost, which the ZFS engine host cannot reach. "
                + "Set forgetdm.virtualization.zfs.localhost-alias to this machine's address as seen from the engine.");
    }

    private void requireEngine() {
        if (!zfs.available()) {
            throw ApiException.bad("ZFS engine not reachable (host="
                    + (zfs.remote() ? zfs.config().getHost() : "local") + ", pool=" + zfs.config().getPool()
                    + "). Check SSH access and that the pool dataset exists.");
        }
    }

    private TimeFlowEntity zfsTimeflow(DataSourceEntity ds) {
        return timeflows.findFirstBySourceIdAndContainerTypeAndSchemaName(ds.getId(), "DSOURCE", "__zfs__")
                .orElseGet(() -> {
                    TimeFlowEntity f = new TimeFlowEntity();
                    f.setName(ds.getName() + " (zfs)");
                    f.setContainerType("DSOURCE");
                    f.setSourceId(ds.getId());
                    f.setSchemaName("__zfs__");
                    return timeflows.save(f);
                });
    }

    private String sourceMajorVersion(DataSourceEntity ds) {
        try (Connection c = connections.open(ds); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SHOW server_version")) {
            rs.next();
            String v = rs.getString(1).trim();
            return v.contains(".") ? v.substring(0, v.indexOf('.')) : v;
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("Cannot read source version: " + e.getMessage()); }
    }

    private long[] pgStats(DataSourceEntity ds) {
        try (Connection c = connections.open(ds); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT count(*), COALESCE(sum(GREATEST(c.reltuples,0)),0)::bigint FROM pg_class c " +
                "JOIN pg_namespace n ON n.oid = c.relnamespace " +
                "WHERE c.relkind = 'r' AND n.nspname NOT IN ('pg_catalog','information_schema')")) {
            rs.next();
            return new long[]{rs.getLong(1), rs.getLong(2)};
        } catch (Exception e) {
            return new long[]{0, 0};
        }
    }

    private long[] mssqlStats(DataSourceEntity ds) {
        try (Connection c = connections.open(ds); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT COUNT(DISTINCT t.object_id), COALESCE(SUM(p.rows),0) FROM sys.tables t " +
                "JOIN sys.partitions p ON p.object_id = t.object_id AND p.index_id IN (0,1)")) {
            rs.next();
            return new long[]{rs.getLong(1), rs.getLong(2)};
        } catch (Exception e) {
            return new long[]{0, 0};
        }
    }

    private long[] tryVdbStats(VirtualDatabaseEntity vdb) {
        try {
            DataSourceEntity ds = dataSources.get(vdb.getDataSourceId());
            String kind = vdb.getTargetKind() == null ? "" : vdb.getTargetKind();
            if ("ZFS_MSSQL".equals(kind)) return mssqlStats(ds);
            if ("ZFS_DB2".equals(kind) || "ZFS_DB2ZOS".equals(kind)) return db2Stats(ds);
            if ("ZFS_ORACLE".equals(kind)) return oracleStats(ds);
            return pgStats(ds);
        } catch (Exception e) {
            return new long[]{0, 0};
        }
    }

    private long[] db2Stats(DataSourceEntity ds) {
        try (Connection c = connections.open(ds); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT COUNT(*), COALESCE(SUM(CARD), 0) FROM SYSCAT.TABLES " +
                "WHERE TYPE = 'T' AND TABSCHEMA NOT LIKE 'SYS%' AND TABSCHEMA <> 'NULLID'")) {
            rs.next();
            return new long[]{rs.getLong(1), rs.getLong(2)};
        } catch (Exception e) { return new long[]{0, 0}; }
    }

    private long[] oracleStats(DataSourceEntity ds) {
        try (Connection c = connections.open(ds); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT COUNT(*), COALESCE(SUM(NUM_ROWS), 0) FROM ALL_TABLES " +
                "WHERE OWNER NOT IN ('SYS','SYSTEM','OUTLN','XDB','CTXSYS','MDSYS','ORDSYS'," +
                "'ORDDATA','ORDPLUGINS','OLAPSYS','LBACSYS','WMSYS','DVSYS','DBSNMP','APPQOSSYS'," +
                "'GSMADMIN_INTERNAL','GSMCATUSER','GSMUSER','OJVMSYS','ANONYMOUS','AUDSYS'," +
                "'DBSFWUSER','ORACLE_OCM','DIP','REMOTE_SCHEDULER_AGENT')")) {
            rs.next();
            return new long[]{rs.getLong(1), rs.getLong(2)};
        } catch (Exception e) { return new long[]{0, 0}; }
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; }
    }

    private static String safeName(String name) {
        String safe = String.valueOf(name == null ? "vdb" : name).trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return safe.isBlank() ? "vdb" : safe;
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
