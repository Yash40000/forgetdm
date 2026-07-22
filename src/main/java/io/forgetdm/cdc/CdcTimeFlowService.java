package io.forgetdm.cdc;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.datasource.SqlDialect;
import io.forgetdm.virtualization.SnapshotManifest;
import io.forgetdm.virtualization.TimeFlowEngine;
import io.forgetdm.virtualization.TimeFlowEntity;
import io.forgetdm.virtualization.TimeFlowRepository;
import io.forgetdm.virtualization.VirtOps;
import io.forgetdm.virtualization.VirtualDatabaseEntity;
import io.forgetdm.virtualization.VirtualSnapshotEntity;
import io.forgetdm.virtualization.VirtualSnapshotRepository;
import io.forgetdm.virtualization.VirtualizationService;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Bridges durable CDC checkpoints into TimeFlow. A CDC anchor is a transactionally
 * consistent baseline paired with the exact buffer/checkpoint boundary that precedes
 * it. Point-in-time provisioning replays only later changes into an isolated workspace,
 * writes an immutable derived snapshot, and provisions a normal governed VDB from it.
 */
@Service
public class CdcTimeFlowService {
    private static final int MAX_DRAIN_POLLS = 10_000;
    private static final String H2_OPTIONS = ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";

    private final CdcService cdc;
    private final DataSourceService dataSources;
    private final ConnectionFactory connections;
    private final CdcIncrementalApplier applier;
    private final VirtualSnapshotRepository snapshots;
    private final TimeFlowRepository timeflows;
    private final TimeFlowEngine engine;
    private final VirtualizationService virtualization;
    private final VirtOps ops;
    private final AuditService audit;

    public CdcTimeFlowService(CdcService cdc, DataSourceService dataSources, ConnectionFactory connections,
                              CdcIncrementalApplier applier, VirtualSnapshotRepository snapshots,
                              TimeFlowRepository timeflows, TimeFlowEngine engine,
                              VirtualizationService virtualization, VirtOps ops, AuditService audit) {
        this.cdc = cdc;
        this.dataSources = dataSources;
        this.connections = connections;
        this.applier = applier;
        this.snapshots = snapshots;
        this.timeflows = timeflows;
        this.engine = engine;
        this.virtualization = virtualization;
        this.ops = ops;
        this.audit = audit;
    }

    public List<VirtualSnapshotEntity> anchors(Long dataSourceId) {
        dataSources.getSourceCapable(dataSourceId);
        return snapshots.findBySourceIdAndSnapshotTypeOrderByCreatedAtDesc(dataSourceId, "CDC_ANCHOR");
    }

    public Map<String, Object> startAnchor(Long dataSourceId, String schemaName, String name) {
        DataSourceEntity source = requireActiveSource(dataSourceId);
        String cleanName = optionalName(name, source.getName() + " CDC anchor");
        String label = "Create CDC anchor " + cleanName;
        String opId = ops.run("CDC_ANCHOR", label,
                () -> Map.of("snapshotId", createAnchor(dataSourceId, schemaName, cleanName).getId()));
        return operationStarted(opId, "CDC_ANCHOR", label);
    }

    public Map<String, Object> startProvision(Long dataSourceId, Long anchorSnapshotId, String vdbName,
                                               Long targetDataSourceId, Long throughChangeId,
                                               Instant throughTimestamp) {
        requireActiveSource(dataSourceId);
        VirtualSnapshotEntity anchor = requireAnchor(dataSourceId, anchorSnapshotId);
        if (throughChangeId != null && throughTimestamp != null) {
            throw ApiException.bad("Choose either an as-of change number or an as-of captured timestamp, not both.");
        }
        String cleanName = requiredName(vdbName, "VDB name");
        String label = "Provision point-in-time VDB " + cleanName;
        String opId = ops.run("CDC_PIT_PROVISION", label,
                () -> provisionAtPoint(dataSourceId, anchor, cleanName, targetDataSourceId,
                        throughChangeId, throughTimestamp));
        return operationStarted(opId, "CDC_PIT_PROVISION", label);
    }

    VirtualSnapshotEntity createAnchor(Long dataSourceId, String requestedSchema, String name) {
        DataSourceEntity source = requireActiveSource(dataSourceId);
        ops.stage("Draining captured changes to a durable checkpoint");
        drainCapture(dataSourceId);
        CdcService.CdcCheckpoint boundary = cdc.checkpoint(dataSourceId);
        Instant anchorTime = Instant.now();

        ops.stage("Capturing a transactionally consistent baseline");
        try (Connection connection = connections.openForBulk(source)) {
            SqlDialect dialect = SqlDialect.of(source);
            beginConsistentRead(connection, dialect);
            String schema = DataSourceService.normalizeSchema(connection,
                    requestedSchema == null || requestedSchema.isBlank() ? boundary.schemaName() : requestedSchema);
            TimeFlowEngine.IngestResult ingest;
            try {
                ingest = engine.ingest(connection, schema);
            } finally {
                try { connection.rollback(); } catch (Exception ignored) { }
            }
            if (ingest.tableCount() <= 0) {
                throw ApiException.bad("No tables were found in schema '" + schema
                        + "'. Select a schema containing the CDC tables and create the anchor again.");
            }

            TimeFlowEntity flow = dsourceTimeflow(source, schema);
            VirtualSnapshotEntity snapshot = new VirtualSnapshotEntity();
            snapshot.setName(name);
            snapshot.setSnapshotType("CDC_ANCHOR");
            snapshot.setSourceId(source.getId());
            snapshot.setSchemaName(schema);
            snapshot.setTimeflowId(flow.getId());
            applyIngest(snapshot, ingest);
            snapshot.setCdcCaptureId(boundary.captureId());
            snapshot.setCdcFromPosition(boundary.confirmedPosition());
            snapshot.setCdcThroughPosition(boundary.confirmedPosition());
            snapshot.setCdcThroughChangeId(boundary.throughChangeId());
            snapshot.setCdcThroughTs(anchorTime);
            snapshot.setCdcChangesApplied(0);
            snapshot.setNote("CDC anchor at " + String.valueOf(boundary.confirmedPosition())
                    + "; buffered through change " + boundary.throughChangeId());
            VirtualSnapshotEntity saved = snapshots.save(snapshot);
            audit.log("system", "CDC_TIMEFLOW_ANCHOR_CREATED",
                    "source=" + source.getName() + " snapshot=" + saved.getId()
                            + " position=" + boundary.confirmedPosition()
                            + " changeId=" + boundary.throughChangeId());
            return saved;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.bad("CDC anchor failed: " + rootMessage(e));
        }
    }

    Map<String, Object> provisionAtPoint(Long dataSourceId, VirtualSnapshotEntity anchor,
                                         String vdbName, Long targetDataSourceId,
                                         Long throughChangeId, Instant throughTimestamp) {
        ops.stage("Polling changes after the anchor");
        drainCapture(dataSourceId);
        CdcService.CdcCheckpoint latest = cdc.checkpoint(dataSourceId);
        long anchorChangeId = anchor.getCdcThroughChangeId() == null ? 0 : anchor.getCdcThroughChangeId();
        validatePoint(anchor, latest, throughChangeId, throughTimestamp, anchorChangeId);

        List<CdcChangeEntity> afterAnchor = cdc.loadBufferedChanges(latest.captureId(), anchorChangeId);
        List<CdcChangeEntity> selected = afterAnchor.stream()
                .filter(change -> throughChangeId == null || change.getId() <= throughChangeId)
                .filter(change -> throughTimestamp == null || change.getCapturedAt() == null
                        || !change.getCapturedAt().isAfter(throughTimestamp))
                .toList();

        SnapshotManifest baseline = engine.loadManifest(anchor.getManifestHash());
        validateReplayCoverage(baseline, selected);
        VirtualSnapshotEntity pointSnapshot;
        if (selected.isEmpty()) {
            ops.stage("Reusing unchanged TimeFlow chunks");
            pointSnapshot = saveUnchangedPoint(anchor, latest, vdbName, throughChangeId, throughTimestamp);
        } else {
            ops.stage("Replaying " + selected.size() + " change(s) in an isolated workspace");
            pointSnapshot = replayToSnapshot(anchor, baseline, latest, selected, vdbName,
                    throughChangeId, throughTimestamp);
        }

        checkCancelled();
        ops.stage("Materializing the writable VDB");
        VirtualDatabaseEntity vdb = virtualization.provision(
                pointSnapshot.getId(), vdbName, targetDataSourceId, null, null);
        audit.log("system", "CDC_POINT_IN_TIME_VDB_PROVISIONED",
                "source=" + dataSourceId + " anchor=" + anchor.getId() + " snapshot=" + pointSnapshot.getId()
                        + " vdb=" + vdb.getId() + " changes=" + selected.size()
                        + " throughChangeId=" + pointSnapshot.getCdcThroughChangeId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("vdbId", vdb.getId());
        result.put("snapshotId", pointSnapshot.getId());
        result.put("anchorSnapshotId", anchor.getId());
        result.put("changesReplayed", selected.size());
        result.put("throughChangeId", pointSnapshot.getCdcThroughChangeId());
        result.put("throughPosition", pointSnapshot.getCdcThroughPosition());
        result.put("asOf", pointSnapshot.getCdcThroughTs());
        result.put("pointBasis", throughChangeId != null ? "CHANGE_ID"
                : throughTimestamp != null ? "CAPTURE_TIMESTAMP" : "LATEST_CAPTURED");
        result.put("timestampSemantics", "CDC_CAPTURE_TIME");
        return result;
    }

    private VirtualSnapshotEntity replayToSnapshot(VirtualSnapshotEntity anchor, SnapshotManifest baseline,
                                                     CdcService.CdcCheckpoint latest,
                                                     List<CdcChangeEntity> selected, String vdbName,
                                                     Long throughChangeId, Instant throughTimestamp) {
        Path temp = null;
        try {
            temp = Files.createTempDirectory("forgetdm-cdc-pit-");
            Path db = temp.resolve("replay");
            String url = "jdbc:h2:file:" + db.toAbsolutePath().toString().replace('\\', '/') + H2_OPTIONS;
            TimeFlowEngine.IngestResult ingest;
            try (Connection workspace = DriverManager.getConnection(url, "sa", "")) {
                engine.materialize(workspace, SqlDialect.H2, baseline);
                checkCancelled();
                CdcIncrementalApplier.ApplyResult replay = applier.apply(workspace, SqlDialect.H2, selected);
                if (replay.skippedNoPk() > 0) {
                    throw ApiException.bad("Point-in-time replay refused " + replay.skippedNoPk()
                            + " change(s) without a primary key.");
                }
                String schema = DataSourceService.normalizeSchema(workspace, baseline.schemaName());
                ingest = engine.ingest(workspace, schema);
            }
            return saveDerivedPoint(anchor, latest, ingest, selected, vdbName, throughChangeId, throughTimestamp);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.bad("CDC point-in-time replay failed: " + rootMessage(e));
        } finally {
            deleteTree(temp);
        }
    }

    private VirtualSnapshotEntity saveDerivedPoint(VirtualSnapshotEntity anchor,
                                                     CdcService.CdcCheckpoint latest,
                                                     TimeFlowEngine.IngestResult ingest,
                                                     List<CdcChangeEntity> selected, String vdbName,
                                                     Long requestedChangeId, Instant requestedTimestamp) {
        VirtualSnapshotEntity snapshot = pointSnapshotBase(anchor, latest, selected, vdbName,
                requestedChangeId, requestedTimestamp);
        applyIngest(snapshot, ingest);
        return snapshots.save(snapshot);
    }

    private VirtualSnapshotEntity saveUnchangedPoint(VirtualSnapshotEntity anchor,
                                                       CdcService.CdcCheckpoint latest, String vdbName,
                                                       Long requestedChangeId, Instant requestedTimestamp) {
        VirtualSnapshotEntity snapshot = pointSnapshotBase(anchor, latest, List.of(), vdbName,
                requestedChangeId, requestedTimestamp);
        snapshot.setManifestHash(anchor.getManifestHash());
        snapshot.setStoragePath(anchor.getStoragePath());
        snapshot.setTableCount(anchor.getTableCount());
        snapshot.setRowCount(anchor.getRowCount());
        snapshot.setChunkCount(anchor.getChunkCount());
        snapshot.setNewChunkCount(0);
        snapshot.setLogicalBytes(anchor.getLogicalBytes());
        snapshot.setStoredBytes(0);
        return snapshots.save(snapshot);
    }

    private VirtualSnapshotEntity pointSnapshotBase(VirtualSnapshotEntity anchor,
                                                      CdcService.CdcCheckpoint latest,
                                                      List<CdcChangeEntity> selected, String vdbName,
                                                      Long requestedChangeId, Instant requestedTimestamp) {
        CdcChangeEntity last = selected.isEmpty() ? null : selected.get(selected.size() - 1);
        long throughId = requestedChangeId != null ? requestedChangeId
                : last == null ? valueOrZero(anchor.getCdcThroughChangeId()) : last.getId();
        String throughPosition = last != null && last.getLsn() != null
                ? last.getLsn() : anchor.getCdcThroughPosition();
        if (requestedChangeId == null && requestedTimestamp == null && latest.confirmedPosition() != null) {
            throughPosition = latest.confirmedPosition();
        }
        Instant asOf = requestedTimestamp != null ? requestedTimestamp
                : last != null && last.getCapturedAt() != null ? last.getCapturedAt() : anchor.getCdcThroughTs();

        VirtualSnapshotEntity snapshot = new VirtualSnapshotEntity();
        snapshot.setName(vdbName + " point-in-time snapshot");
        snapshot.setSnapshotType("CDC_POINT_IN_TIME");
        snapshot.setSourceId(anchor.getSourceId());
        snapshot.setSchemaName(anchor.getSchemaName());
        snapshot.setTimeflowId(anchor.getTimeflowId());
        snapshot.setCdcCaptureId(anchor.getCdcCaptureId());
        snapshot.setCdcBaseSnapshotId(anchor.getId());
        snapshot.setCdcFromPosition(anchor.getCdcThroughPosition());
        snapshot.setCdcThroughPosition(throughPosition);
        snapshot.setCdcThroughChangeId(throughId);
        snapshot.setCdcThroughTs(asOf);
        snapshot.setCdcChangesApplied(selected.size());
        snapshot.setNote("Derived from CDC anchor " + anchor.getId() + "; replayed "
                + selected.size() + " change(s) through " + throughId);
        return snapshot;
    }

    private void drainCapture(Long dataSourceId) {
        int idleRounds = 0;
        for (int i = 0; i < MAX_DRAIN_POLLS; i++) {
            checkCancelled();
            CdcService.PollSummary poll = cdc.poll(dataSourceId);
            if (poll.reachedEnd()) return;
            if (poll.decoded() == 0) idleRounds++; else idleRounds = 0;
            if (idleRounds >= 3) {
                throw ApiException.bad("CDC could not reach a stable checkpoint after three polls with no progress.");
            }
        }
        throw ApiException.bad("CDC backlog is too large to drain in one operation. Let continuous capture catch up and retry.");
    }

    private DataSourceEntity requireActiveSource(Long dataSourceId) {
        DataSourceEntity source = dataSources.getSourceCapable(dataSourceId);
        CdcService.CdcCheckpoint checkpoint = cdc.checkpoint(dataSourceId);
        if (!"ACTIVE".equals(checkpoint.status())) {
            throw ApiException.bad("CDC must be active before creating or provisioning a TimeFlow point.");
        }
        return source;
    }

    private VirtualSnapshotEntity requireAnchor(Long dataSourceId, Long snapshotId) {
        if (snapshotId == null) throw ApiException.bad("CDC anchor snapshot is required.");
        VirtualSnapshotEntity anchor = snapshots.findById(snapshotId)
                .orElseThrow(() -> ApiException.notFound("Snapshot " + snapshotId + " not found"));
        if (!"CDC_ANCHOR".equals(anchor.getSnapshotType()) || !dataSourceId.equals(anchor.getSourceId())) {
            throw ApiException.bad("Snapshot " + snapshotId + " is not a CDC anchor for this data source.");
        }
        CdcService.CdcCheckpoint checkpoint = cdc.checkpoint(dataSourceId);
        if (!checkpoint.captureId().equals(anchor.getCdcCaptureId())) {
            throw ApiException.bad("The CDC capture was recreated after this anchor. Create a new anchor before provisioning.");
        }
        return anchor;
    }

    private static void validatePoint(VirtualSnapshotEntity anchor, CdcService.CdcCheckpoint latest,
                                      Long throughChangeId, Instant throughTimestamp, long anchorChangeId) {
        if (throughChangeId != null && throughChangeId < anchorChangeId) {
            throw ApiException.bad("Requested change " + throughChangeId + " predates anchor boundary " + anchorChangeId + ".");
        }
        if (throughChangeId != null && throughChangeId > latest.throughChangeId()) {
            throw ApiException.bad("Requested change " + throughChangeId + " is not buffered yet; latest is "
                    + latest.throughChangeId() + ".");
        }
        if (throughTimestamp != null && anchor.getCdcThroughTs() != null
                && throughTimestamp.isBefore(anchor.getCdcThroughTs())) {
            throw ApiException.bad("Requested timestamp predates the CDC anchor. Create an earlier anchor or choose a later point.");
        }
    }

    private static void validateReplayCoverage(SnapshotManifest baseline, List<CdcChangeEntity> selected) {
        Map<String, SnapshotManifest.TableManifest> tables = new LinkedHashMap<>();
        for (SnapshotManifest.TableManifest table : baseline.tables()) {
            tables.put(table.name().toLowerCase(Locale.ROOT), table);
        }
        Set<String> missingTables = new HashSet<>();
        Set<String> missingKeys = new HashSet<>();
        for (CdcChangeEntity change : selected) {
            SnapshotManifest.TableManifest table = tables.get(change.getTableName().toLowerCase(Locale.ROOT));
            if (table == null) missingTables.add(change.getTableName());
            else if (table.primaryKey().isEmpty() || change.getPkJson() == null
                    || change.getPkJson().isBlank() || "{}".equals(change.getPkJson().trim())) {
                missingKeys.add(change.getTableName());
            }
        }
        if (!missingTables.isEmpty()) {
            throw ApiException.bad("CDC changed table(s) absent from the anchor: " + String.join(", ", missingTables)
                    + ". Create a new anchor after the schema change.");
        }
        if (!missingKeys.isEmpty()) {
            throw ApiException.bad("Exact CDC replay requires primary keys. Add a key and recreate the anchor for: "
                    + String.join(", ", missingKeys));
        }
    }

    private TimeFlowEntity dsourceTimeflow(DataSourceEntity source, String schema) {
        return timeflows.findFirstBySourceIdAndContainerTypeAndSchemaName(source.getId(), "DSOURCE", schema)
                .orElseGet(() -> {
                    TimeFlowEntity flow = new TimeFlowEntity();
                    flow.setName(source.getName() + (schema == null ? "" : "/" + schema));
                    flow.setContainerType("DSOURCE");
                    flow.setSourceId(source.getId());
                    flow.setSchemaName(schema);
                    return timeflows.save(flow);
                });
    }

    private static void beginConsistentRead(Connection connection, SqlDialect dialect) throws Exception {
        connection.setAutoCommit(false);
        if (dialect == SqlDialect.ORACLE) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET TRANSACTION READ ONLY");
            }
            return;
        }
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        try { connection.setReadOnly(true); } catch (Exception ignored) { }
        if (dialect == SqlDialect.POSTGRES) {
            // Establish the REPEATABLE READ snapshot before any table is streamed.
            try (Statement statement = connection.createStatement()) {
                statement.executeQuery("SELECT txid_current_snapshot()").close();
            }
        }
    }

    private static void applyIngest(VirtualSnapshotEntity snapshot, TimeFlowEngine.IngestResult ingest) {
        snapshot.setManifestHash(ingest.manifestHash());
        snapshot.setStoragePath("pool:" + ingest.manifestHash());
        snapshot.setTableCount(ingest.tableCount());
        snapshot.setRowCount(ingest.rowCount());
        snapshot.setChunkCount(ingest.chunkCount());
        snapshot.setNewChunkCount(ingest.newChunkCount());
        snapshot.setLogicalBytes(ingest.logicalBytes());
        snapshot.setStoredBytes(ingest.storedBytes());
    }

    private static Map<String, Object> operationStarted(String opId, String kind, String label) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("opId", opId);
        result.put("kind", kind);
        result.put("label", label);
        return result;
    }

    private static String requiredName(String value, String label) {
        if (value == null || value.isBlank()) throw ApiException.bad(label + " is required.");
        String clean = value.trim();
        if (clean.length() < 3 || clean.length() > 120) {
            throw ApiException.bad(label + " must be between 3 and 120 characters.");
        }
        return clean;
    }

    private static String optionalName(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : requiredName(value, "Anchor name");
    }

    private static long valueOrZero(Long value) { return value == null ? 0 : value; }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw ApiException.bad("CDC point-in-time operation was cancelled.");
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root.getMessage() == null ? root.toString() : root.getMessage();
    }
}
