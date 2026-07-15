package io.forgetdm.virtualization;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceRepository;
import io.forgetdm.datasource.DataSourceService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * CONTAINER provider: true runtime copy-on-write via Docker's overlay2 layer store.
 *
 * dSource snapshot  = pg_basebackup (physical datafiles) baked into a Docker image layer;
 *                     successive snapshots build FROM the previous image and copy only
 *                     files that changed — incremental, deduplicated by the layer store.
 * Provision VDB     = docker run from the snapshot image. Seconds, regardless of size:
 *                     the container reads shared image layers at runtime; its writable
 *                     overlay layer holds only blocks the VDB actually changes.
 * Bookmark/snapshot = docker commit (clean-stop first) — a new layer of changed files.
 * Refresh / rewind  = recreate the container from another image on the same host port.
 *
 * The VDB is a real PostgreSQL server with the source's exact physical layout: indexes,
 * sequences, views, procedures, statistics — everything pg_basebackup carries.
 */
@Service
public class ContainerVdbProvider {
    private static final Pattern PG_URL = Pattern.compile("jdbc:postgresql://([^/:]+)(?::(\\d+))?/([^?]+).*");
    private static final String PGDATA = "/var/lib/postgresql/data";
    private static final int MAX_DELETE_FILES = 400;

    private final VirtualSnapshotRepository snapshots;
    private final VirtualDatabaseRepository vdbs;
    private final TimeFlowRepository timeflows;
    private final DataSourceRepository dataSourceRepo;
    private final DataSourceService dataSources;
    private final ConnectionFactory connections;
    private final AuditService audit;
    private final DockerCli docker;
    private final Path root;

    public ContainerVdbProvider(VirtualSnapshotRepository snapshots, VirtualDatabaseRepository vdbs,
                                TimeFlowRepository timeflows, DataSourceRepository dataSourceRepo,
                                DataSourceService dataSources, ConnectionFactory connections,
                                AuditService audit, DockerCli docker) {
        this.snapshots = snapshots;
        this.vdbs = vdbs;
        this.timeflows = timeflows;
        this.dataSourceRepo = dataSourceRepo;
        this.dataSources = dataSources;
        this.connections = connections;
        this.audit = audit;
        this.docker = docker;
        this.root = Path.of(System.getProperty("java.io.tmpdir")).resolve("forgetdm-virtualization").resolve("container-staging");
    }

    public Map<String, Object> dockerStatus() {
        String version = docker.serverVersion();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", version != null);
        out.put("serverVersion", version);
        return out;
    }

    // ------------------------------------------------------------ ingestion

    public VirtualSnapshotEntity snapshotDataSource(DataSourceEntity ds, String name, String note) {
        PgEndpoint src = pgEndpoint(ds);
        requireDocker();
        try {
            String major = sourceMajorVersion(ds);
            String baseImage = "postgres:" + major;

            TimeFlowEntity flow = containerTimeflow(ds);
            Path flowDir = root.resolve("tf-" + flow.getId());
            Path backupDir = flowDir.resolve("backup-" + System.currentTimeMillis());
            Files.createDirectories(backupDir);

            // 1. physical ingestion: pg_basebackup from the source into the staging dir
            docker.run(3600, "run", "--rm",
                    "--add-host=host.docker.internal:host-gateway",
                    "-e", "PGPASSWORD=" + nullToEmpty(ds.getPassword()),
                    "-v", backupDir.toAbsolutePath() + ":/staging",
                    baseImage,
                    "pg_basebackup", "-h", src.containerHost(), "-p", String.valueOf(src.port()),
                    "-U", nullToEmpty(ds.getUsername()), "-D", "/staging/data",
                    "-Fp", "-Xs", "-c", "fast");

            // 2. bake into an image layer — incremental FROM the previous snapshot when possible
            String tag = "forgetdm/tf" + flow.getId() + ":s" + System.currentTimeMillis();
            Path current = flowDir.resolve("current");
            String prevImage = latestImage(flow.getId());
            LayerStats layer;
            if (prevImage != null && Files.isDirectory(current.resolve("data"))) {
                layer = buildIncremental(backupDir.resolve("data"), current.resolve("data"), prevImage, tag, flowDir);
            } else {
                layer = buildFull(backupDir, baseImage, tag);
            }

            // 3. promote this backup to "current" for the next diff
            deleteRecursively(current);
            Files.move(backupDir, current);

            long[] stats = pgStats(ds);
            VirtualSnapshotEntity e = new VirtualSnapshotEntity();
            e.setName(name == null || name.isBlank() ? ds.getName() + " snapshot" : name.trim());
            e.setSnapshotType("DSOURCE");
            e.setProvider("CONTAINER");
            e.setSourceId(ds.getId());
            e.setSchemaName(null);
            e.setTimeflowId(flow.getId());
            e.setImageRef(tag);
            e.setStoragePath("image:" + tag);
            e.setTableCount((int) stats[0]);
            e.setRowCount(stats[1]);
            e.setChunkCount(layer.totalFiles());
            e.setNewChunkCount(layer.changedFiles());
            e.setLogicalBytes(layer.logicalBytes());
            e.setStoredBytes(layer.storedBytes());
            e.setNote(blankToNull(note));
            VirtualSnapshotEntity saved = snapshots.save(e);
            audit.log("system", "VIRT_DSOURCE_SNAPSHOT", "snapshot=" + saved.getId() + " source=" + ds.getName()
                    + " provider=CONTAINER image=" + tag + " changedFiles=" + layer.changedFiles() + "/" + layer.totalFiles());
            return saved;
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("Container snapshot failed: " + e.getMessage()); }
    }

    // ---------------------------------------------------------- provisioning

    public VirtualDatabaseEntity provision(VirtualSnapshotEntity snapshot, String cleanName) {
        requireDocker();
        DataSourceEntity origin = dataSources.get(snapshot.getSourceId());
        PgEndpoint src = pgEndpoint(origin);
        String containerName = "forgetdm-vdb-" + safeName(cleanName);
        String dsName = "vdb-" + cleanName;
        if (dataSourceRepo.findByName(dsName).isPresent())
            throw ApiException.bad("Data source '" + dsName + "' already exists");
        try {
            // thin clone: seconds regardless of size — reads come from shared image layers
            docker.run(120, "run", "-d", "--name", containerName,
                    "-p", "127.0.0.1:0:5432", snapshot.getImageRef());
            int hostPort = publishedPort(containerName);
            waitReady(containerName, 90);

            String jdbcUrl = "jdbc:postgresql://localhost:" + hostPort + "/" + src.database();
            DataSourceEntity ds = new DataSourceEntity();
            ds.setName(dsName);
            ds.setKind("POSTGRES");
            ds.setRole("BOTH");
            ds.setJdbcUrl(jdbcUrl);
            ds.setUsername(origin.getUsername());
            ds.setPassword(origin.getPassword());
            ds = dataSources.create(ds);

            VirtualDatabaseEntity vdb = new VirtualDatabaseEntity();
            vdb.setName(cleanName);
            vdb.setProvider("CONTAINER");
            vdb.setSourceSnapshotId(snapshot.getId());
            vdb.setCurrentSnapshotId(snapshot.getId());
            vdb.setDataSourceId(ds.getId());
            vdb.setJdbcUrl(jdbcUrl);
            vdb.setSchemaName(snapshot.getSchemaName());
            vdb.setUsername(origin.getUsername());
            vdb.setPassword(origin.getPassword());
            vdb.setStoragePath("container:" + containerName);
            vdb.setContainerId(containerName);
            vdb.setHostPort(hostPort);
            vdb.setTargetKind("CONTAINER_PG");
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
                    + " provider=CONTAINER port=" + hostPort);
            return saved;
        } catch (ApiException e) { cleanupContainer(containerName); throw e; }
        catch (Exception e) { cleanupContainer(containerName); throw ApiException.bad("Provision container VDB failed: " + e.getMessage()); }
    }

    public VirtualSnapshotEntity snapshotVdb(VirtualDatabaseEntity vdb, String label, boolean bookmark) {
        requireDocker();
        String container = vdb.getContainerId();
        try {
            String tag = "forgetdm/tf" + vdb.getTimeflowId() + ":s" + System.currentTimeMillis();
            // clean stop -> consistent datafiles -> commit captures only the changed overlay files
            docker.run(120, "stop", "-t", "30", container);
            docker.run(300, "commit", container, tag);
            docker.run(120, "start", container);
            waitReady(container, 90);

            long[] stats = tryVdbStats(vdb);
            VirtualSnapshotEntity e = new VirtualSnapshotEntity();
            e.setName(label);
            e.setSnapshotType(bookmark ? "BOOKMARK" : "VDB_SNAPSHOT");
            e.setProvider("CONTAINER");
            e.setSourceId(vdb.getDataSourceId());
            e.setVdbId(vdb.getId());
            e.setTimeflowId(vdb.getTimeflowId());
            e.setImageRef(tag);
            e.setStoragePath("image:" + tag);
            e.setTableCount((int) stats[0]);
            e.setRowCount(stats[1]);
            e.setStoredBytes(imageSize(tag));
            e.setNote(bookmark ? "Named rewind point" : "VDB timeflow snapshot");
            VirtualSnapshotEntity saved = snapshots.save(e);
            vdb.setCurrentSnapshotId(saved.getId());
            vdb.setUpdatedAt(Instant.now());
            vdbs.save(vdb);
            audit.log("system", bookmark ? "VIRT_VDB_BOOKMARK" : "VIRT_VDB_SNAPSHOT",
                    "vdb=" + vdb.getName() + " snapshot=" + saved.getId() + " image=" + tag);
            return saved;
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("Container VDB snapshot failed: " + e.getMessage()); }
    }

    public void restore(VirtualDatabaseEntity vdb, VirtualSnapshotEntity snapshot, String action) {
        requireDocker();
        if (!"CONTAINER".equals(snapshot.getProvider()) || snapshot.getImageRef() == null) {
            throw ApiException.bad("Snapshot " + snapshot.getId() + " is not a container-provider snapshot.");
        }
        String container = vdb.getContainerId();
        try {
            docker.run(120, "rm", "-f", container);
            docker.run(120, "run", "-d", "--name", container,
                    "-p", "127.0.0.1:" + vdb.getHostPort() + ":5432", snapshot.getImageRef());
            waitReady(container, 90);
            vdb.setCurrentSnapshotId(snapshot.getId());
            vdb.setUpdatedAt(Instant.now());
            vdbs.save(vdb);
            audit.log("system", action, "vdb=" + vdb.getName() + " snapshot=" + snapshot.getId()
                    + " image=" + snapshot.getImageRef());
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("Container VDB restore failed: " + e.getMessage()); }
    }

    // ------------------------------------------------------------- deletion

    /** Remove the VDB container and best-effort remove its timeflow images (skipped while shared). */
    public void deleteVdb(VirtualDatabaseEntity vdb, List<String> imageRefs) {
        cleanupContainer(vdb.getContainerId());
        for (String image : imageRefs) {
            try { docker.run(60, "rmi", image); } catch (Exception inUse) { /* layer shared by other tags */ }
        }
        audit.log("system", "VIRT_VDB_DELETED", "vdb=" + vdb.getName() + " provider=CONTAINER");
    }

    public void deleteSnapshot(VirtualSnapshotEntity snapshot) {
        try {
            docker.run(60, "rmi", snapshot.getImageRef());
        } catch (Exception e) {
            throw ApiException.bad("Cannot delete snapshot " + snapshot.getId()
                    + ": image in use by a container or downstream snapshot. (" + e.getMessage() + ")");
        }
        audit.log("system", "VIRT_SNAPSHOT_DELETED", "snapshot=" + snapshot.getId() + " image=" + snapshot.getImageRef());
    }

    // ---------------------------------------------------------- image builds

    private record LayerStats(int totalFiles, int changedFiles, long logicalBytes, long storedBytes) {}

    private LayerStats buildFull(Path backupDir, String baseImage, String tag) throws IOException {
        String dockerfile = "FROM " + baseImage + "\n"
                + "COPY --chown=999:999 data/ " + PGDATA + "/\n"
                + "RUN chmod 700 " + PGDATA + "\n";
        Files.writeString(backupDir.resolve("Dockerfile"), dockerfile, StandardCharsets.UTF_8);
        docker.run(3600, "build", "-t", tag, backupDir.toAbsolutePath().toString());
        long logical = dirSize(backupDir.resolve("data"));
        int files = countFiles(backupDir.resolve("data"));
        return new LayerStats(files, files, logical, imageSize(tag));
    }

    private LayerStats buildIncremental(Path newData, Path prevData, String prevImage, String tag, Path flowDir) throws IOException {
        Path ctx = flowDir.resolve("ctx-" + System.currentTimeMillis());
        Path changes = ctx.resolve("changes");
        Files.createDirectories(changes);
        List<String> deleted = new ArrayList<>();
        int total = 0, changed = 0;
        long logical = 0, changedBytes = 0;

        // changed / new files: compare against the previous basebackup tree
        try (Stream<Path> walk = Files.walk(newData)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(p)) continue;
                total++;
                long size = Files.size(p);
                logical += size;
                Path rel = newData.relativize(p);
                Path prev = prevData.resolve(rel);
                boolean same = Files.isRegularFile(prev) && Files.size(prev) == size && Files.mismatch(prev, p) == -1L;
                if (!same) {
                    changed++;
                    changedBytes += size;
                    Path dest = changes.resolve(rel);
                    Files.createDirectories(dest.getParent());
                    Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        // deleted files: present before, gone now
        try (Stream<Path> walk = Files.walk(prevData)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(p)) continue;
                Path rel = prevData.relativize(p);
                if (!Files.exists(newData.resolve(rel))) deleted.add(rel.toString().replace('\\', '/'));
            }
        }
        if (deleted.size() > MAX_DELETE_FILES) {
            deleteRecursively(ctx);
            // too much churn for a delete layer — fall back to a full rebuild
            return buildFull(newData.getParent(), baseImageOf(prevImage), tag);
        }

        StringBuilder df = new StringBuilder("FROM ").append(prevImage).append('\n')
                .append("COPY --chown=999:999 changes/ ").append(PGDATA).append("/\n");
        if (!deleted.isEmpty()) {
            df.append("RUN cd ").append(PGDATA);
            for (String d : deleted) df.append(" && rm -f '").append(d.replace("'", "'\\''")).append('\'');
            df.append('\n');
        }
        Files.writeString(ctx.resolve("Dockerfile"), df.toString(), StandardCharsets.UTF_8);
        docker.run(3600, "build", "-t", tag, ctx.toAbsolutePath().toString());
        deleteRecursively(ctx);
        return new LayerStats(total, changed, logical, changedBytes);
    }

    private String baseImageOf(String image) {
        try {
            String parents = docker.run(30, "image", "inspect", "--format", "{{json .Config.Image}}", image);
            if (parents.contains("postgres:")) return parents.replaceAll(".*?(postgres:[0-9.]+).*", "$1");
        } catch (Exception ignored) { }
        return "postgres:16";
    }

    private String latestImage(Long timeflowId) {
        return snapshots.findAll().stream()
                .filter(s -> Objects.equals(s.getTimeflowId(), timeflowId))
                .filter(s -> "CONTAINER".equals(s.getProvider()) && s.getImageRef() != null)
                .max(Comparator.comparing(VirtualSnapshotEntity::getCreatedAt))
                .map(VirtualSnapshotEntity::getImageRef)
                .orElse(null);
    }

    private long imageSize(String tag) {
        try {
            return Long.parseLong(docker.run(30, "image", "inspect", "--format", "{{.Size}}", tag).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------- runtime

    private int publishedPort(String container) {
        String out = docker.run(30, "port", container, "5432/tcp"); // e.g. 127.0.0.1:49162
        Matcher m = Pattern.compile(":(\\d+)\\s*$", Pattern.MULTILINE).matcher(out.trim());
        if (!m.find()) throw ApiException.bad("Could not determine published port for " + container + ": " + out);
        return Integer.parseInt(m.group(1));
    }

    private void waitReady(String container, int seconds) {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                docker.run(15, "exec", container, "pg_isready", "-q");
                return;
            } catch (Exception notYet) {
                try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        }
        throw ApiException.bad("VDB container '" + container + "' did not become ready within " + seconds + "s");
    }

    private void cleanupContainer(String name) {
        try { docker.run(60, "rm", "-f", name); } catch (Exception ignored) { }
    }

    private void requireDocker() {
        if (!docker.available()) {
            throw ApiException.bad("Docker is not available. Start Docker Desktop (or install docker) to use the container provider.");
        }
    }

    // -------------------------------------------------------------- queries

    private String sourceMajorVersion(DataSourceEntity ds) {
        try (Connection c = connections.open(ds); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SHOW server_version")) {
            rs.next();
            String v = rs.getString(1).trim();
            return v.contains(".") ? v.substring(0, v.indexOf('.')) : v;
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("Cannot read source version: " + e.getMessage()); }
    }

    /** Cheap stats from the planner estimates — no COUNT(*) scans on big sources. */
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

    private long[] tryVdbStats(VirtualDatabaseEntity vdb) {
        try {
            return pgStats(dataSources.get(vdb.getDataSourceId()));
        } catch (Exception e) {
            return new long[]{0, 0};
        }
    }

    private TimeFlowEntity containerTimeflow(DataSourceEntity ds) {
        return timeflows.findFirstBySourceIdAndContainerTypeAndSchemaName(ds.getId(), "DSOURCE", "__container__")
                .orElseGet(() -> {
                    TimeFlowEntity f = new TimeFlowEntity();
                    f.setName(ds.getName() + " (container)");
                    f.setContainerType("DSOURCE");
                    f.setSourceId(ds.getId());
                    f.setSchemaName("__container__");
                    return timeflows.save(f);
                });
    }

    // ---------------------------------------------------------------- utils

    record PgEndpoint(String host, int port, String database) {
        String containerHost() {
            return host.equals("localhost") || host.equals("127.0.0.1") ? "host.docker.internal" : host;
        }
    }

    static PgEndpoint pgEndpoint(DataSourceEntity ds) {
        String url = ds.getJdbcUrl() == null ? "" : ds.getJdbcUrl();
        Matcher m = PG_URL.matcher(url);
        if (!m.matches()) {
            throw ApiException.bad("Container provider supports PostgreSQL sources (jdbc:postgresql://...). '"
                    + ds.getName() + "' has URL: " + url);
        }
        return new PgEndpoint(m.group(1), m.group(2) == null ? 5432 : Integer.parseInt(m.group(2)), m.group(3));
    }

    private static long dirSize(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try { return Files.size(p); } catch (IOException e) { return 0; }
            }).sum();
        }
    }

    private static int countFiles(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            return (int) walk.filter(Files::isRegularFile).count();
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) { }
            });
        }
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
