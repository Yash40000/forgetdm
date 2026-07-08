package io.forgetdm.virtualization;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceRepository;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.datasource.SqlDialect;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Snapshot-based logical TimeFlow engine.
 *
 * dSource ingestion pulls any registered database (Postgres, Oracle, DB2, SQL Server, H2)
 * into the storage pool as deduplicated, compressed, content-addressed chunks. A snapshot
 * is a manifest of chunk hashes — successive snapshots physically store only changed
 * blocks. TimeFlows record lineage: each VDB gets its own timeflow branched from the
 * snapshot it was provisioned from; bookmarks are named snapshots on a VDB timeflow.
 *
 * Provisioning materializes a snapshot into a writable layer (an H2 file, or a real
 * registered target DB) and registers it as a normal ForgeTDM data source. The shared
 * base chunks in the pool are never modified — refresh and rewind simply re-materialize
 * the writable layer from a different point in the timeflow.
 */
@Service
public class VirtualizationService {
    private static final String H2_OPTIONS = ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;ACCESS_MODE_DATA=rws";

    private final VirtualSnapshotRepository snapshots;
    private final VirtualDatabaseRepository vdbs;
    private final TimeFlowRepository timeflows;
    private final DataSourceRepository dataSourceRepo;
    private final DataSourceService dataSources;
    private final ConnectionFactory connections;
    private final AuditService audit;
    private final TimeFlowEngine engine;
    private final ChunkStore pool;
    private final ContainerVdbProvider containerProvider;
    private final ZfsVdbProvider zfsProvider;
    private final TargetEnvironmentRepository environments;
    private final VirtOps ops;
    private final Path root;

    public VirtualizationService(VirtualSnapshotRepository snapshots, VirtualDatabaseRepository vdbs,
                                 TimeFlowRepository timeflows, DataSourceRepository dataSourceRepo,
                                 DataSourceService dataSources, ConnectionFactory connections,
                                 AuditService audit, TimeFlowEngine engine, ChunkStore pool,
                                 ContainerVdbProvider containerProvider, ZfsVdbProvider zfsProvider,
                                 TargetEnvironmentRepository environments, VirtOps ops) {
        this.ops = ops;
        this.snapshots = snapshots;
        this.vdbs = vdbs;
        this.timeflows = timeflows;
        this.dataSourceRepo = dataSourceRepo;
        this.dataSources = dataSources;
        this.connections = connections;
        this.audit = audit;
        this.engine = engine;
        this.pool = pool;
        this.containerProvider = containerProvider;
        this.zfsProvider = zfsProvider;
        this.environments = environments;
        this.root = Path.of(System.getProperty("java.io.tmpdir")).resolve("forgetdm-virtualization");
    }

    // ------------------------------------------------------------- listings

    public List<VirtualSnapshotEntity> listSnapshots() {
        List<VirtualSnapshotEntity> out = snapshots.findAll();
        out.sort(Comparator.comparing(VirtualSnapshotEntity::getCreatedAt).reversed());
        return out;
    }

    public List<VirtualDatabaseEntity> listVdbs() {
        List<VirtualDatabaseEntity> out = vdbs.findAll();
        out.sort(Comparator.comparing(VirtualDatabaseEntity::getCreatedAt).reversed());
        return out;
    }

    public List<TimeFlowEntity> listTimeflows() {
        return timeflows.findAllByOrderByIdDesc();
    }

    /** Storage-pool economics: physical (dedup+gzip) vs logical bytes across all snapshots. */
    public Map<String, Object> poolStats() {
        ChunkStore.PoolStats fs = pool.stats();
        long logical = snapshots.findAll().stream().mapToLong(VirtualSnapshotEntity::getLogicalBytes).sum();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("poolPath", pool.root().toAbsolutePath().toString());
        out.put("chunkCount", fs.chunkCount());
        out.put("storedBytes", fs.storedBytes());
        out.put("logicalBytes", logical);
        out.put("dedupRatio", fs.storedBytes() <= 0 ? 1.0
                : Math.round((double) logical / fs.storedBytes() * 100.0) / 100.0);
        out.put("snapshots", snapshots.count());
        out.put("timeflows", timeflows.count());
        out.put("vdbs", vdbs.count());
        return out;
    }

    public Map<String, Object> dockerStatus() {
        return containerProvider.dockerStatus();
    }

    public Map<String, Object> zfsStatus() {
        return zfsProvider.status();
    }

    /** Pre-flight the ZFS engine over SSH (zfs/docker/nfs probes) before capturing a snapshot. */
    public Map<String, Object> engineTest() {
        return zfsProvider.engineTest();
    }

    // ------------------------------------------------ async ops with live progress

    /** Kick off a dSource snapshot in the background; returns an opId to poll for live progress. */
    public Map<String, Object> startSnapshot(Long dataSourceId, String schemaName, String name, String note, String provider) {
        DataSourceEntity ds = dataSources.get(dataSourceId);   // validate now so a bad id fails fast (in the request)
        String prov = provider == null || provider.isBlank() ? "POOL" : provider.trim().toUpperCase(java.util.Locale.ROOT);
        String label = "Snapshot " + ds.getName() + " · " + prov;
        String opId = ops.run("SNAPSHOT", label,
                () -> Map.of("snapshotId", snapshotDataSource(dataSourceId, schemaName, name, note, provider).getId()));
        return Map.of("opId", opId, "kind", "SNAPSHOT", "label", label);
    }

    /** Kick off a VDB provision in the background; returns an opId to poll for live progress. */
    public Map<String, Object> startProvision(Long snapshotId, String name, Long targetDataSourceId,
                                              String pointInTime, Long environmentId) {
        String label = "Provision VDB " + (name == null ? "" : name.trim());
        String opId = ops.run("PROVISION", label,
                () -> Map.of("vdbId", provision(snapshotId, name, targetDataSourceId, pointInTime, environmentId).getId()));
        return Map.of("opId", opId, "kind", "PROVISION", "label", label);
    }

    public Map<String, Object> operation(String id) {
        Map<String, Object> v = ops.view(id);
        if (v == null) throw ApiException.notFound("Operation " + id + " not found");
        return v;
    }

    public List<Map<String, Object>> operations() { return ops.recent(); }

    /** Cancel a running snapshot/provision op (kills the engine container + interrupts the worker). */
    public Map<String, Object> cancelOperation(String id) {
        if (!ops.cancel(id)) throw ApiException.bad("Operation is not running (already finished, or unknown id).");
        return operation(id);
    }

    // ------------------------------------------------------------ ingestion

    /**
     * Snapshot a registered source into its dSource timeflow.
     * provider POOL (default): logical chunk-store ingest, any DB.
     * provider CONTAINER: physical pg_basebackup into a Docker image layer (PostgreSQL only).
     */
    public VirtualSnapshotEntity snapshotDataSource(Long dataSourceId, String schemaName, String name, String note, String provider) {
        DataSourceEntity ds = dataSources.get(dataSourceId);
        ops.stage("Capturing from " + ds.getName());   // no-op unless running inside an async op
        if ("CONTAINER".equalsIgnoreCase(provider)) {
            return containerProvider.snapshotDataSource(ds, name, note);
        }
        if ("ZFS".equalsIgnoreCase(provider)) {
            return zfsProvider.snapshotDataSource(ds, name, note);
        }
        try (Connection c = connections.open(ds)) {
            String schema = DataSourceService.normalizeSchema(c, schemaName);
            TimeFlowEntity flow = dsourceTimeflow(ds, schema);
            TimeFlowEngine.IngestResult r = engine.ingest(c, schema);

            VirtualSnapshotEntity e = new VirtualSnapshotEntity();
            e.setName(name == null || name.isBlank() ? ds.getName() + " snapshot" : name.trim());
            e.setSnapshotType("DSOURCE");
            e.setSourceId(ds.getId());
            e.setSchemaName(schema);
            e.setTimeflowId(flow.getId());
            applyIngest(e, r);
            e.setNote(blankToNull(note));
            VirtualSnapshotEntity saved = snapshots.save(e);
            audit.log("system", "VIRT_DSOURCE_SNAPSHOT", "snapshot=" + saved.getId() + " source=" + ds.getName()
                    + " newChunks=" + r.newChunkCount() + "/" + r.chunkCount());
            return saved;
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("Snapshot failed: " + e.getMessage()); }
    }

    public VirtualSnapshotEntity snapshotVdb(Long vdbId, String name, boolean bookmark) {
        VirtualDatabaseEntity vdb = getVdb(vdbId);
        if ("CONTAINER".equals(vdb.getProvider())) {
            return containerProvider.snapshotVdb(vdb,
                    requireName(name, bookmark ? "Bookmark name" : "Snapshot name"), bookmark);
        }
        if ("ZFS".equals(vdb.getProvider())) {
            return zfsProvider.snapshotVdb(vdb,
                    requireName(name, bookmark ? "Bookmark name" : "Snapshot name"), bookmark);
        }
        DataSourceEntity ds = dataSources.get(vdb.getDataSourceId());
        try (Connection c = connections.open(ds)) {
            String schema = DataSourceService.normalizeSchema(c, null);
            TimeFlowEngine.IngestResult r = engine.ingest(c, schema);

            String type = bookmark ? "BOOKMARK" : "VDB_SNAPSHOT";
            VirtualSnapshotEntity e = new VirtualSnapshotEntity();
            e.setName(requireName(name, bookmark ? "Bookmark name" : "Snapshot name"));
            e.setSnapshotType(type);
            e.setSourceId(ds.getId());
            e.setVdbId(vdb.getId());
            e.setSchemaName(schema);
            e.setTimeflowId(vdb.getTimeflowId());
            applyIngest(e, r);
            e.setNote(bookmark ? "Named rewind point" : "VDB timeflow snapshot");
            VirtualSnapshotEntity saved = snapshots.save(e);
            vdb.setCurrentSnapshotId(saved.getId());
            vdb.setUpdatedAt(Instant.now());
            vdbs.save(vdb);
            audit.log("system", bookmark ? "VIRT_VDB_BOOKMARK" : "VIRT_VDB_SNAPSHOT",
                    "vdb=" + vdb.getName() + " snapshot=" + saved.getId()
                    + " newChunks=" + r.newChunkCount() + "/" + r.chunkCount());
            return saved;
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("VDB snapshot failed: " + e.getMessage()); }
    }

    private void applyIngest(VirtualSnapshotEntity e, TimeFlowEngine.IngestResult r) {
        e.setManifestHash(r.manifestHash());
        e.setStoragePath("pool:" + r.manifestHash());
        e.setTableCount(r.tableCount());
        e.setRowCount(r.rowCount());
        e.setChunkCount(r.chunkCount());
        e.setNewChunkCount(r.newChunkCount());
        e.setLogicalBytes(r.logicalBytes());
        e.setStoredBytes(r.storedBytes());
    }

    private TimeFlowEntity dsourceTimeflow(DataSourceEntity ds, String schema) {
        return timeflows.findFirstBySourceIdAndContainerTypeAndSchemaName(ds.getId(), "DSOURCE", schema)
                .orElseGet(() -> {
                    TimeFlowEntity f = new TimeFlowEntity();
                    f.setName(ds.getName() + (schema == null ? "" : "/" + schema));
                    f.setContainerType("DSOURCE");
                    f.setSourceId(ds.getId());
                    f.setSchemaName(schema);
                    return timeflows.save(f);
                });
    }

    // ---------------------------------------------------------- provisioning

    /**
     * Provision a VDB from a snapshot. targetDataSourceId null = embedded H2 writable layer;
     * otherwise the snapshot is materialized into the given registered TARGET database.
     */
    public VirtualDatabaseEntity provision(Long snapshotId, String name, Long targetDataSourceId,
                                           String pointInTime, Long environmentId) {
        VirtualSnapshotEntity snapshot = getSnapshot(snapshotId);
        String cleanName = requireName(name, "VDB name");
        if (vdbs.findByName(cleanName).isPresent()) throw ApiException.bad("VDB '" + cleanName + "' already exists");
        if ("ZFS".equals(snapshot.getProvider())) {
            return zfsProvider.provision(snapshot, cleanName, pointInTime, environmentId); // block-level thin clone
        }
        if (pointInTime != null && !pointInTime.isBlank())
            throw ApiException.bad("Point-in-time provisioning requires a ZFS snapshot with LogSync enabled.");
        if (environmentId != null)
            throw ApiException.bad("Target environments are supported for ZFS snapshots.");
        if ("CONTAINER".equals(snapshot.getProvider())) {
            return containerProvider.provision(snapshot, cleanName); // thin clone, target selection n/a
        }
        requireTimeflowSnapshot(snapshot);

        SnapshotManifest manifest = engine.loadManifest(snapshot.getManifestHash());
        VirtualDatabaseEntity vdb = new VirtualDatabaseEntity();
        vdb.setName(cleanName);
        vdb.setSourceSnapshotId(snapshot.getId());
        vdb.setCurrentSnapshotId(snapshot.getId());
        vdb.setStatus("ACTIVE");

        try {
            if (targetDataSourceId == null) {
                String dsName = "vdb-" + cleanName;
                if (dataSourceRepo.findByName(dsName).isPresent())
                    throw ApiException.bad("Data source '" + dsName + "' already exists");
                Files.createDirectories(root.resolve("vdbs"));
                Path base = root.resolve("vdbs").resolve(safeName(cleanName) + "-" + System.currentTimeMillis());
                String jdbcUrl = h2FileUrl(base);
                try (Connection h2 = DriverManager.getConnection(jdbcUrl, "demo", "demo")) {
                    engine.materialize(h2, SqlDialect.H2, manifest);
                }
                DataSourceEntity ds = new DataSourceEntity();
                ds.setName(dsName);
                ds.setKind("H2");
                ds.setRole("BOTH");
                ds.setJdbcUrl(jdbcUrl);
                ds.setUsername("demo");
                ds.setPassword("demo");
                ds = dataSources.create(ds);
                vdb.setDataSourceId(ds.getId());
                vdb.setJdbcUrl(jdbcUrl);
                vdb.setUsername("demo");
                vdb.setPassword("demo");
                vdb.setStoragePath(base.toAbsolutePath().toString());
                vdb.setTargetKind("H2");
            } else {
                DataSourceEntity target = dataSources.get(targetDataSourceId);
                if ("SOURCE".equalsIgnoreCase(target.getRole()))
                    throw ApiException.bad("Data source '" + target.getName() + "' is registered as SOURCE only");
                SqlDialect dialect = SqlDialect.of(target);
                try (Connection c = connections.open(target)) {
                    engine.materialize(c, dialect, manifest);
                }
                vdb.setDataSourceId(target.getId());
                vdb.setTargetDataSourceId(target.getId());
                vdb.setJdbcUrl(target.getJdbcUrl());
                vdb.setUsername(target.getUsername());
                vdb.setPassword(target.getPassword());
                vdb.setStoragePath("target:" + target.getName());
                vdb.setTargetKind(dialect.name());
            }
            VirtualDatabaseEntity saved = vdbs.save(vdb);

            TimeFlowEntity flow = new TimeFlowEntity();
            flow.setName("vdb-" + cleanName);
            flow.setContainerType("VDB");
            flow.setVdbId(saved.getId());
            flow.setParentSnapshotId(snapshot.getId());
            flow.setSchemaName(snapshot.getSchemaName());
            flow = timeflows.save(flow);
            saved.setTimeflowId(flow.getId());
            saved = vdbs.save(saved);

            audit.log("system", "VIRT_VDB_PROVISIONED", "vdb=" + cleanName + " snapshot=" + snapshot.getId()
                    + " target=" + saved.getTargetKind());
            return saved;
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("Provision VDB failed: " + e.getMessage()); }
    }

    public VirtualDatabaseEntity refresh(Long vdbId, Long snapshotId) {
        VirtualDatabaseEntity vdb = getVdb(vdbId);
        VirtualSnapshotEntity snapshot = getSnapshot(snapshotId);
        if (!"DSOURCE".equals(snapshot.getSnapshotType())) {
            throw ApiException.bad("Refresh expects a parent dSource snapshot. Use Rewind for VDB snapshots or bookmarks.");
        }
        restoreIntoExisting(vdb, snapshot, "VIRT_VDB_REFRESH");
        return getVdb(vdbId);
    }

    public VirtualDatabaseEntity rewind(Long vdbId, Long snapshotId) {
        VirtualDatabaseEntity vdb = getVdb(vdbId);
        VirtualSnapshotEntity snapshot = getSnapshot(snapshotId);
        if (!Objects.equals(snapshot.getVdbId(), vdbId)) {
            throw ApiException.bad("Rewind expects a snapshot or bookmark from the selected VDB timeflow.");
        }
        restoreIntoExisting(vdb, snapshot, "VIRT_VDB_REWIND");
        return getVdb(vdbId);
    }

    private void restoreIntoExisting(VirtualDatabaseEntity vdb, VirtualSnapshotEntity snapshot, String action) {
        if ("CONTAINER".equals(vdb.getProvider())) {
            containerProvider.restore(vdb, snapshot, action);
            return;
        }
        if ("ZFS".equals(vdb.getProvider())) {
            zfsProvider.restore(vdb, snapshot, action);
            return;
        }
        requireTimeflowSnapshot(snapshot);
        try {
            SnapshotManifest manifest = engine.loadManifest(snapshot.getManifestHash());
            if (!"H2".equalsIgnoreCase(vdb.getTargetKind()) && vdb.getTargetDataSourceId() != null) {
                DataSourceEntity target = dataSources.get(vdb.getTargetDataSourceId());
                try (Connection c = connections.open(target)) {
                    dropKnownTables(c, vdb, manifest);
                    engine.materialize(c, SqlDialect.of(target), manifest);
                }
            } else {
                DataSourceEntity ds = dataSources.get(vdb.getDataSourceId());
                shutdown(ds);
                Path base = Path.of(vdb.getStoragePath());
                deleteH2Files(base);
                try (Connection h2 = DriverManager.getConnection(h2FileUrl(base), "demo", "demo")) {
                    engine.materialize(h2, SqlDialect.H2, manifest);
                }
            }
            vdb.setCurrentSnapshotId(snapshot.getId());
            vdb.setUpdatedAt(Instant.now());
            vdbs.save(vdb);
            audit.log("system", action, "vdb=" + vdb.getName() + " snapshot=" + snapshot.getId());
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("VDB restore failed: " + e.getMessage()); }
    }

    /** Drop tables from both the VDB's current state and the manifest being restored. */
    private void dropKnownTables(Connection c, VirtualDatabaseEntity vdb, SnapshotManifest restoring) {
        if (vdb.getCurrentSnapshotId() != null) {
            VirtualSnapshotEntity current = snapshots.findById(vdb.getCurrentSnapshotId()).orElse(null);
            if (current != null && current.getManifestHash() != null) {
                engine.dropTables(c, engine.loadManifest(current.getManifestHash()));
            }
        }
        engine.dropTables(c, restoring);
    }

    private void requireTimeflowSnapshot(VirtualSnapshotEntity snapshot) {
        if (snapshot.getManifestHash() == null || snapshot.getManifestHash().isBlank()) {
            throw ApiException.bad("Snapshot " + snapshot.getId()
                    + " predates the TimeFlow engine. Create a new snapshot of the source to use it.");
        }
    }

    // ---------------------------------------------------------------- utils

    private VirtualSnapshotEntity getSnapshot(Long id) {
        return snapshots.findById(id).orElseThrow(() -> ApiException.notFound("Snapshot " + id + " not found"));
    }

    private VirtualDatabaseEntity getVdb(Long id) {
        return vdbs.findById(id).orElseThrow(() -> ApiException.notFound("VDB " + id + " not found"));
    }

    private void shutdown(DataSourceEntity ds) {
        try (Connection c = connections.open(ds); Statement st = c.createStatement()) {
            st.execute("SHUTDOWN");
        } catch (Exception ignored) {
            // Best effort: closed request-scoped H2 connections normally release the file lock.
        }
    }

    private void deleteH2Files(Path base) throws Exception {
        Files.deleteIfExists(Path.of(base.toString() + ".mv.db"));
        Files.deleteIfExists(Path.of(base.toString() + ".trace.db"));
        Files.deleteIfExists(Path.of(base.toString() + ".lock.db"));
    }

    private static String h2FileUrl(Path base) {
        return "jdbc:h2:file:" + base.toAbsolutePath().toString().replace('\\', '/') + H2_OPTIONS;
    }

    private static String safeName(String name) {
        String safe = String.valueOf(name == null ? "snapshot" : name).trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return safe.isBlank() ? "snapshot" : safe;
    }

    private static String requireName(String value, String label) {
        if (value == null || value.isBlank()) throw ApiException.bad(label + " is required");
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    // ------------------------------------------------------- delete operations

    /**
     * Delete a VDB. For ZFS: destroys all branch clones (refused if another VDB depends on any).
     * For CONTAINER: removes Docker container and images. For POOL: drops the H2 file or clears
     * the target schema and removes the registered data source.
     */
    public void deleteVdb(Long vdbId) {
        VirtualDatabaseEntity vdb = getVdb(vdbId);
        if ("ZFS".equals(vdb.getProvider())) {
            zfsProvider.deleteVdb(vdb);
        } else if ("CONTAINER".equals(vdb.getProvider())) {
            // Collect Docker image refs from all snapshots on this VDB's timeflow
            List<String> imageRefs = snapshots.findAll().stream()
                    .filter(s -> vdbId.equals(s.getVdbId()) && s.getImageRef() != null)
                    .map(VirtualSnapshotEntity::getImageRef)
                    .collect(Collectors.toList());
            containerProvider.deleteVdb(vdb, imageRefs);
        } else {
            // POOL — drop H2 file or target schema, remove the registered data source
            try {
                if (vdb.getDataSourceId() != null) {
                    DataSourceEntity ds = dataSourceRepo.findById(vdb.getDataSourceId()).orElse(null);
                    if (ds != null) {
                        if ("H2".equalsIgnoreCase(ds.getKind()) && vdb.getStoragePath() != null
                                && !vdb.getStoragePath().startsWith("target:")) {
                            shutdown(ds);
                            deleteH2Files(Path.of(vdb.getStoragePath()));
                        } else if (vdb.getTargetDataSourceId() != null) {
                            DataSourceEntity target = dataSourceRepo.findById(vdb.getTargetDataSourceId()).orElse(null);
                            if (target != null) {
                                SnapshotManifest manifest = vdb.getCurrentSnapshotId() != null
                                        ? loadManifestSafe(vdb.getCurrentSnapshotId()) : null;
                                if (manifest != null) {
                                    try (Connection c = connections.open(target)) {
                                        engine.dropTables(c, manifest);
                                    }
                                }
                            }
                        }
                        if (!"H2".equalsIgnoreCase(ds.getKind()) || vdb.getStoragePath() == null
                                || vdb.getStoragePath().startsWith("target:")) {
                            // keep the registered target DS — only the data was cleaned up
                        } else {
                            dataSourceRepo.delete(ds);
                        }
                    }
                }
            } catch (Exception e) {
                throw ApiException.bad("Delete VDB failed: " + e.getMessage());
            }
        }
        // Remove timeflow if this VDB was the only user
        if (vdb.getTimeflowId() != null) {
            timeflows.findById(vdb.getTimeflowId()).ifPresent(tf -> {
                boolean otherVdbs = vdbs.findAll().stream()
                        .anyMatch(v -> !v.getId().equals(vdbId) && vdb.getTimeflowId().equals(v.getTimeflowId()));
                if (!otherVdbs) timeflows.delete(tf);
            });
        }
        vdbs.delete(vdb);
        audit.log("system", "VIRT_VDB_DELETED", "vdb=" + vdb.getName() + " provider=" + vdb.getProvider());
    }

    /**
     * Delete a snapshot. Refused if any VDB is currently at or branched from this snapshot.
     * For ZFS: zfs destroy. For CONTAINER: docker rmi. For POOL: no physical removal (chunks
     * remain in the pool and are shared — just removes the manifest record).
     */
    public void deleteSnapshot(Long snapshotId) {
        VirtualSnapshotEntity snapshot = getSnapshot(snapshotId);
        // Safety: refuse if any VDB is at this snapshot
        List<String> blocking = vdbs.findAll().stream()
                .filter(v -> snapshotId.equals(v.getSourceSnapshotId())
                          || snapshotId.equals(v.getCurrentSnapshotId()))
                .map(VirtualDatabaseEntity::getName)
                .collect(Collectors.toList());
        if (!blocking.isEmpty())
            throw ApiException.bad("Snapshot is in use by VDB(s): " + String.join(", ", blocking));

        if ("ZFS".equals(snapshot.getProvider())) {
            zfsProvider.deleteSnapshot(snapshot);
        } else if ("CONTAINER".equals(snapshot.getProvider())) {
            containerProvider.deleteSnapshot(snapshot);
        }
        // POOL: chunks are shared — just remove the manifest record
        snapshots.delete(snapshot);
        audit.log("system", "VIRT_SNAPSHOT_DELETED", "snapshot=" + snapshotId + " provider=" + snapshot.getProvider());
    }

    private SnapshotManifest loadManifestSafe(Long snapshotId) {
        try {
            VirtualSnapshotEntity s = snapshots.findById(snapshotId).orElse(null);
            return (s != null && s.getManifestHash() != null) ? engine.loadManifest(s.getManifestHash()) : null;
        } catch (Exception e) { return null; }
    }

    // --------------------------------------------------- LogSync (ZFS only)

    public Map<String, Object> enableLogSync(Long dataSourceId) {
        DataSourceEntity ds = dataSources.get(dataSourceId);
        return zfsProvider.enableLogSync(ds);
    }

    public Map<String, Object> disableLogSync(Long dataSourceId) {
        DataSourceEntity ds = dataSources.get(dataSourceId);
        return zfsProvider.disableLogSync(ds);
    }

    public Map<String, Object> getLogSyncStatus(Long dataSourceId) {
        DataSourceEntity ds = dataSources.get(dataSourceId);
        return zfsProvider.logSyncStatus(ds);
    }

    // ----------------------------------------------- target environments CRUD

    public List<TargetEnvironmentEntity> listEnvironments() {
        return environments.findAll();
    }

    public TargetEnvironmentEntity createEnvironment(TargetEnvironmentEntity req) {
        if (req.getName() == null || req.getName().isBlank())
            throw ApiException.bad("Environment name is required");
        if (req.getHost() == null || req.getHost().isBlank())
            throw ApiException.bad("Environment host is required");
        if (req.getSshUser() == null || req.getSshUser().isBlank())
            req.setSshUser("root");
        if (req.getSshPort() <= 0)
            req.setSshPort(22);
        if (req.getMountBase() == null || req.getMountBase().isBlank())
            req.setMountBase("/mnt/forgetdm");
        environments.findByName(req.getName()).ifPresent(e -> {
            throw ApiException.bad("Environment '" + req.getName() + "' already exists");
        });
        TargetEnvironmentEntity saved = environments.save(req);
        audit.log("system", "VIRT_ENV_CREATED", "env=" + saved.getName() + " host=" + saved.getHost());
        return saved;
    }

    public void deleteEnvironment(Long envId) {
        TargetEnvironmentEntity env = environments.findById(envId)
                .orElseThrow(() -> ApiException.notFound("Environment " + envId + " not found"));
        List<String> inUse = vdbs.findAll().stream()
                .filter(v -> envId.equals(v.getEnvironmentId()))
                .map(VirtualDatabaseEntity::getName)
                .collect(Collectors.toList());
        if (!inUse.isEmpty())
            throw ApiException.bad("Environment is in use by VDB(s): " + String.join(", ", inUse));
        environments.delete(env);
        audit.log("system", "VIRT_ENV_DELETED", "env=" + env.getName());
    }
}
