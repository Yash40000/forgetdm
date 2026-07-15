package io.forgetdm.provision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.core.synth.Generators;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.datasource.SqlDialect;
import io.forgetdm.provision.loader.NativeLoadRegistry;
import io.forgetdm.provision.loader.NativeLoadRequest;
import io.forgetdm.provision.loader.NativeLoadResult;
import io.forgetdm.provision.loader.NativeLoadStrategy;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessPrincipal;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiFunction;

/**
 * Professional, GenRocket/K2View-style synthetic generation:
 *
 *  - DATASET of multiple TABLES (domains), each with COLUMNS (attributes) bound to a generator.
 *  - REFERENTIAL INTEGRITY: foreign-key columns draw their value from a parent table's already
 *    generated key pool, so parent/child relationships are consistent. Tables are generated in
 *    FK-dependency order (parents first).
 *  - RECEIVERS (output targets): DB load (into existing or auto-created tables), CSV, JSON, and a
 *    portable SQL script (optional CREATE TABLE + INSERTs) for standing up a brand-new environment.
 *
 * One engine drives all three TDM scenarios: source->target DB load, file hand-off, and new-env.
 */
@Service
public class SyntheticGenService {

    private final DataSourceService dataSources;
    private final ConnectionFactory connections;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ValueListService valueLists;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private NativeLoadRegistry nativeLoaders;
    private static final long STREAMING_DB_ROW_THRESHOLD = 500_000L;
    private static final int MAX_STREAMING_FK_POOL_VALUES = 200_000;
    private static final int API_GENERATOR_MAX_CONCURRENCY = 8;
    private static final int API_GENERATOR_RETRIES = 2;
    private static final int MIN_SAVED_JOB_NAME_LENGTH = 8;
    private static final Duration TARGET_LEASE_STALE_AFTER = Duration.ofSeconds(90);
    private final Semaphore apiGeneratorThrottle = new Semaphore(API_GENERATOR_MAX_CONCURRENCY);
    private final ExecutorService syntheticExecutor;
    private final ExecutorService partitionExecutor;
    private final ConcurrentMap<String, SyntheticJob> syntheticJobs = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Future<?>> partitionFutures = new ConcurrentHashMap<>();
    private final String workerInstanceId = "forgetdm-" + UUID.randomUUID();
    /** One in-flight DB load per target (dataSource + schema). Prevents concurrent loads from interleaving
     *  truncate/insert/FK work against the same schema and corrupting the target. */
    private final ConcurrentMap<String, ActiveTarget> activeTargets = new ConcurrentHashMap<>();
    private final int lineageRetentionDays;

    /** Identifies the job currently holding a target lock, for a clear rejection message. */
    private record ActiveTarget(String jobId, String owner, Instant since, String label) {}

    public SyntheticGenService(DataSourceService dataSources, ConnectionFactory connections, JdbcTemplate jdbc, AuditService audit,
                               @Value("${forgetdm.synthetic.workers:2}") int syntheticWorkers,
                               @Value("${forgetdm.synthetic.lineage-retention-days:2555}") int lineageRetentionDays) {
        this.dataSources = dataSources;
        this.connections = connections;
        this.jdbc = jdbc;
        this.audit = audit;
        this.lineageRetentionDays = Math.max(1, lineageRetentionDays);
        int workers = Math.max(1, Math.min(16, syntheticWorkers));
        this.syntheticExecutor = Executors.newFixedThreadPool(workers, r -> {
            Thread t = new Thread(r, "forgetdm-synthetic-generator");
            t.setDaemon(true);
            return t;
        });
        this.partitionExecutor = Executors.newFixedThreadPool(SyntheticPartitioning.MAX_WORKERS, r -> {
            Thread t = new Thread(r, "forgetdm-synthetic-partition");
            t.setDaemon(true);
            return t;
        });
    }

    private interface ProgressSink {
        void update(int percent, String stage, String message);
        default void updateRows(int percent, String stage, String message, String table,
                                long tableRowsDone, long tableRowsTotal, long rowsDone, long rowsTotal) {
            update(percent, stage, message);
        }
        default void checkCancelled() {}
        default void activeStatement(Statement statement) {}
        default String jobId() { return null; }
    }

    private static final ProgressSink NO_PROGRESS = (percent, stage, message) -> { };

    private static final class SyntheticJob {
        final String id = UUID.randomUUID().toString();
        final Instant startedAt = Instant.now();
        volatile Future<?> future;
        volatile Instant finishedAt;
        volatile Instant updatedAt = Instant.now();
        volatile Instant lastPersistedAt = Instant.EPOCH;
        volatile String status = "PENDING";
        volatile boolean cancelRequested;
        volatile long lastCancelProbeNanos;
        volatile Statement activeStatement;
        final Set<Statement> activeStatements = ConcurrentHashMap.newKeySet();
        volatile Long ownerUserId;
        volatile String ownerUsername = "system";
        volatile String targetKey;        // display-friendly lock key summary for DB loads (null for file receivers)
        volatile List<String> targetKeys = List.of(); // actual per-target lock keys held while this run executes
        volatile String dataset;
        volatile String receiver;
        volatile String executionMode = "SINGLE";
        volatile String loadAction;
        volatile int tableCount;
        volatile long plannedRows;
        volatile int percent = 0;
        volatile String stage = "Queued";
        volatile String message = "Queued";
        volatile String streamingBanner;
        volatile String detail;
        volatile String currentTable;
        volatile long tableRowsDone;
        volatile long tableRowsTotal;
        volatile long rowsDone;
        volatile long rowsTotal;
        volatile String error;
        volatile String planJson;
        volatile String planHash;
        volatile String lineageJson;
        volatile String constraintSnapshotJson;
        volatile String approvalSnapshotJson;
        volatile Map<String, Object> result;
    }

    private static final class SyntheticJobCancelledException extends RuntimeException {
        SyntheticJobCancelledException() { super("Synthetic generation cancelled"); }
    }

    private static final class SyntheticPartialFailureException extends ApiException {
        private final Map<String, Object> partialResult;
        SyntheticPartialFailureException(String message, Map<String, Object> partialResult) {
            super(HttpStatus.BAD_REQUEST, message);
            this.partialResult = partialResult == null ? Map.of() : partialResult;
        }
        Map<String, Object> partialResult() { return partialResult; }
    }

    // ----------------------------------------------------------------- model

    public record GenColumn(String name, String generator, String param1, String param2,
                            Boolean primaryKey, String fkTable, String fkColumn, String sqlType,
                            Integer fkMin, Integer fkMax) {}   // fkMin/fkMax = children-per-parent cardinality
    public record GenTable(String name, Long rowCount, List<GenColumn> columns) {}
    public record ColumnProjection(String logicalColumn, String physicalColumn, String sqlType) {}
    public record TableProjection(String logicalTable, String physicalTable, List<ColumnProjection> columns) {}
    public record TargetSystem(String name, Long targetDataSourceId, String targetSchema,
                               Boolean createTable, Boolean dropTable, String prepMode,
                               String loadAction, String targetPrep, List<String> keyColumns,
                               Integer batchSize, Integer commitEveryRows, Boolean continueOnError,
                               Integer maxRejects, Boolean fastLoad, List<TableProjection> tables) {}
    public record GenPlan(String dataset, List<GenTable> tables, Long seed, String receiver,
                          Long targetDataSourceId, String targetSchema, Boolean createTable, Boolean dropTable,
                          String prepMode, String loadAction, String targetPrep, List<String> keyColumns,
                          Integer batchSize,
                          Integer commitEveryRows, Boolean continueOnError, Integer maxRejects, Boolean fastLoad,
                          String executionMode, Integer partitionCount, Long partitionSize,
                          List<TargetSystem> targetSystems) {
        /** Source-compatible constructor for existing integrations and tests created before multi-system targets. */
        public GenPlan(String dataset, List<GenTable> tables, Long seed, String receiver,
                       Long targetDataSourceId, String targetSchema, Boolean createTable, Boolean dropTable,
                       String prepMode, String loadAction, String targetPrep, List<String> keyColumns,
                       Integer batchSize, Integer commitEveryRows, Boolean continueOnError, Integer maxRejects,
                       Boolean fastLoad, String executionMode, Integer partitionCount, Long partitionSize) {
            this(dataset, tables, seed, receiver, targetDataSourceId, targetSchema, createTable, dropTable,
                    prepMode, loadAction, targetPrep, keyColumns, batchSize, commitEveryRows, continueOnError,
                    maxRejects, fastLoad, executionMode, partitionCount, partitionSize, null);
        }

        /** Source-compatible constructor for existing integrations and tests created before partition execution. */
        public GenPlan(String dataset, List<GenTable> tables, Long seed, String receiver,
                       Long targetDataSourceId, String targetSchema, Boolean createTable, Boolean dropTable,
                       String prepMode, String loadAction, String targetPrep, List<String> keyColumns,
                       Integer batchSize, Integer commitEveryRows, Boolean continueOnError, Integer maxRejects,
                       Boolean fastLoad) {
            this(dataset, tables, seed, receiver, targetDataSourceId, targetSchema, createTable, dropTable,
                    prepMode, loadAction, targetPrep, keyColumns, batchSize, commitEveryRows, continueOnError,
                    maxRejects, fastLoad, "SINGLE", null, null, null);
        }
    }
    public record SavedSyntheticJobRequest(String name, String description, GenPlan plan) {}
    public record ApprovalRequest(String note) {}

    /** A multi-column foreign key: (childCols) → parentTable(parentCols), kept consistent as a tuple. */
    private record CompositeFk(List<String> childCols, String parentTable, List<String> parentCols, String tupleKey) {}
    private record SavedJobRunContext(String id, String name, String approvalStatus, String approvedBy,
                                      Instant approvedAt, String approvalNote) {}
    private record RawConstraint(String table, String constraintName, String expression,
                                 String dialect, String captureSource) {
        RawConstraint(String table, String constraintName, String expression) {
            this(table, constraintName, expression, "GENERIC", "information_schema");
        }
    }
    private record ConstraintCapture(List<RawConstraint> raw, List<SyntheticConstraintRules.Rule> rules, String warning) {
        static ConstraintCapture empty() { return new ConstraintCapture(List.of(), List.of(), null); }
    }

    @PostConstruct
    public void markInterruptedSyntheticJobs() {
        Instant now = Instant.now();
        List<Map<String, Object>> active = jdbc.query("SELECT id, plan_json FROM synthetic_generation_jobs " +
                        "WHERE status IN ('PENDING','RUNNING','CANCELLING')",
                (rs, rowNum) -> Map.of("id", rs.getString(1), "plan", rs.getString(2) == null ? "" : rs.getString(2)));
        for (Map<String, Object> row : active) {
            GenPlan plan = fromJsonPlan(String.valueOf(row.get("plan")));
            String id = String.valueOf(row.get("id"));
            if (plan != null && "DISTRIBUTED".equals(SyntheticPartitioning.mode(plan.executionMode()))) {
                jdbc.update("UPDATE synthetic_job_partitions SET status = 'QUEUED', worker_id = NULL, lease_expires_at = NULL, updated_at = ? " +
                        "WHERE job_id = ? AND status IN ('RUNNING','CLAIMED','RETRYING') AND cancel_requested = FALSE", ts(now), id);
            } else {
                jdbc.update("UPDATE synthetic_generation_jobs SET status = 'FAILED', stage = 'Interrupted', " +
                                "message = 'Server restarted before this job finished', error = 'Server restarted before this job finished', " +
                                "finished_at = COALESCE(finished_at, ?), updated_at = ? WHERE id = ?",
                        ts(now), ts(now), id);
            }
        }
        cleanupFinishedTargetLeases();
        purgeStaleTargetLeases();
    }

    // --------------------------------------------------------------- entry point

    public Map<String, Object> generate(GenPlan plan) {
        enforceControlledTargetGovernance(plan, null);
        List<String> locks = acquireTargetLocks(plan, "(direct run)", requesterName());
        try {
            return generate(plan, NO_PROGRESS);
        } finally {
            releaseTargetLocks(locks);
        }
    }

    /** Username of the caller, or "system" when there is no security context. */
    private String requesterName() {
        return AccessContext.current().map(p -> p.username()).orElse("system");
    }

    /** Lock key for a DB target: dataSource + schema. Null for file receivers or when no target is set. */
    private static String targetLockKey(GenPlan plan) {
        if (plan == null || plan.targetDataSourceId() == null) return null;
        String receiver = plan.receiver() == null ? "DB" : plan.receiver().trim().toUpperCase(Locale.ROOT);
        if (!"DB".equals(receiver)) return null;
        String schema = plan.targetSchema() == null || plan.targetSchema().isBlank()
                ? "(default)" : plan.targetSchema().trim().toLowerCase(Locale.ROOT);
        return plan.targetDataSourceId() + "|" + schema;
    }

    private static String targetLabel(GenPlan plan) {
        String schema = plan.targetSchema() == null || plan.targetSchema().isBlank()
                ? "(default schema)" : plan.targetSchema().trim();
        return "data source " + plan.targetDataSourceId() + " / schema " + schema;
    }

    private List<String> acquireTargetLocks(GenPlan plan, String jobId, String owner) {
        List<String> locks = new ArrayList<>();
        try {
            if (hasTargetSystems(plan)) {
                for (TargetSystem target : targetSystems(plan)) {
                    String lock = acquireTargetLock(lockPlan(plan, target), jobId, owner);
                    if (lock != null) locks.add(lock);
                }
            } else {
                String lock = acquireTargetLock(plan, jobId, owner);
                if (lock != null) locks.add(lock);
            }
            return locks;
        } catch (RuntimeException e) {
            releaseTargetLocks(locks);
            throw e;
        }
    }

    private void releaseTargetLocks(List<String> keys) {
        for (String key : safe(keys)) releaseTargetLock(key);
    }

    private GenPlan lockPlan(GenPlan plan, TargetSystem target) {
        return new GenPlan(plan == null ? null : plan.dataset(), plan == null ? List.of() : plan.tables(), plan == null ? null : plan.seed(),
                "DB", target == null ? null : target.targetDataSourceId(),
                firstNonBlank(target == null ? null : target.targetSchema(), plan == null ? null : plan.targetSchema()),
                plan == null ? null : plan.createTable(), plan == null ? null : plan.dropTable(),
                plan == null ? null : plan.prepMode(), plan == null ? null : plan.loadAction(),
                plan == null ? null : plan.targetPrep(), plan == null ? null : plan.keyColumns(),
                plan == null ? null : plan.batchSize(), plan == null ? null : plan.commitEveryRows(),
                plan == null ? null : plan.continueOnError(), plan == null ? null : plan.maxRejects(),
                plan == null ? null : plan.fastLoad(), "SINGLE", null, null, null);
    }

    /**
     * Take the per-target lock for a DB load. Returns the key (to release later), or null when no lock is needed
     * (file receivers). If another load already holds the target, fails fast with a clear message naming the
     * running job and its owner — concurrent loads to the same schema are not allowed.
     */
    private String acquireTargetLock(GenPlan plan, String jobId, String owner) {
        String key = targetLockKey(plan);
        if (key == null) return null;
        purgeStaleTargetLeases();
        ActiveTarget prev = activeTargets.putIfAbsent(key,
                new ActiveTarget(jobId, owner, Instant.now(), targetLabel(plan)));
        if (prev != null) {
            throw ApiException.bad("A synthetic load is already running against " + prev.label()
                    + " (job " + prev.jobId() + ", started by " + prev.owner() + " at " + prev.since()
                    + "). Wait for it to finish or cancel it before starting another load on the same target.");
        }
        try {
            insertTargetLease(key, jobId, owner, targetLabel(plan));
        } catch (DuplicateKeyException e) {
            purgeStaleTargetLeases();
            try {
                insertTargetLease(key, jobId, owner, targetLabel(plan));
                return key;
            } catch (DuplicateKeyException stillHeld) {
                activeTargets.remove(key);
            }
            List<String> holder = jdbc.query("SELECT job_id || ' by ' || owner_username FROM synthetic_target_leases WHERE target_key = ?",
                    (rs, rowNum) -> rs.getString(1), key);
            throw ApiException.bad("A synthetic load is already running against " + targetLabel(plan)
                    + (holder.isEmpty() ? "." : " (" + holder.get(0) + ").")
                    + " Wait for it to finish or cancel it before starting another load.");
        } catch (DataAccessException e) {
            String message = (msgOf(e) + " " + msgOf(e.getMostSpecificCause())).toLowerCase(Locale.ROOT);
            if (!message.contains("synthetic_target_leases")
                    || !(message.contains("does not exist") || message.contains("not found") || message.contains("unknown table"))) {
                activeTargets.remove(key);
                throw e;
            }
            // Isolated service tests may intentionally construct the service without running Flyway.
            // The in-process lock still protects them; production receives the cross-node lease from V29.
        }
        return key;
    }

    private void insertTargetLease(String key, String jobId, String owner, String label) {
        jdbc.update("INSERT INTO synthetic_target_leases(target_key, job_id, owner_username, target_label, acquired_at) VALUES (?, ?, ?, ?, ?)",
                key, jobId, owner, label, ts(Instant.now()));
    }

    private void releaseTargetLock(String key) {
        if (key != null) {
            activeTargets.remove(key);
            try { jdbc.update("DELETE FROM synthetic_target_leases WHERE target_key = ?", key); }
            catch (Exception ignore) { }
        }
    }

    private void cleanupFinishedTargetLeases() {
        try {
            jdbc.update("DELETE FROM synthetic_target_leases WHERE job_id IN " +
                    "(SELECT id FROM synthetic_generation_jobs WHERE status IN ('COMPLETED','FAILED','CANCELLED'))");
        } catch (DataAccessException ignore) { }
    }

    private void purgeStaleTargetLeases() {
        try {
            Instant cutoff = Instant.now().minus(TARGET_LEASE_STALE_AFTER);
            jdbc.update("DELETE FROM synthetic_target_leases l WHERE l.acquired_at < ? AND NOT EXISTS (" +
                    "SELECT 1 FROM synthetic_generation_jobs j WHERE j.id = l.job_id " +
                    "AND j.status IN ('PENDING','RUNNING','CANCELLING'))", ts(cutoff));
        } catch (DataAccessException ignore) { }
    }

    @Scheduled(fixedDelayString = "${forgetdm.synthetic.target-lease-heartbeat-ms:20000}")
    public void heartbeatTargetLeases() {
        if (activeTargets.isEmpty()) {
            cleanupFinishedTargetLeases();
            purgeStaleTargetLeases();
            return;
        }
        Instant now = Instant.now();
        activeTargets.forEach((key, active) -> {
            try {
                jdbc.update("UPDATE synthetic_target_leases SET acquired_at = ? WHERE target_key = ?",
                        ts(now), key);
            } catch (DataAccessException ignore) { }
        });
        cleanupFinishedTargetLeases();
        purgeStaleTargetLeases();
    }

    public Map<String, Object> planSummary(GenPlan plan) {
        if (plan == null || plan.tables() == null || plan.tables().isEmpty())
            throw ApiException.bad("Add at least one table");
        plan = hasTargetSystems(plan) ? resolveValueLists(plan) : resolveValueLists(enrichForeignKeys(plan));
        if (hasTargetSystems(plan)) return multiTargetPlanSummary(plan);
        String receiver = plan.receiver() == null ? "DB" : plan.receiver().trim().toUpperCase(Locale.ROOT);
        long maxRows = receiver.equals("DB") ? 100_000_000L : 200_000L;
        List<GenTable> ordered = topoSort(plan.tables());
        long plannedRows = totalRows(ordered, maxRows);
        LoadPlan load = "DB".equals(receiver) ? loadPlan(plan) : null;
        boolean truncateOnly = load != null && load.truncateOnly();
        boolean streaming = "DB".equals(receiver) && !truncateOnly && plannedRows > STREAMING_DB_ROW_THRESHOLD;
        String targetKind = targetKind(plan);
        ConstraintCapture constraints = "DB".equals(receiver) ? captureConstraintRules(plan) : ConstraintCapture.empty();
        Map<String, List<SyntheticConstraintRules.Rule>> rulesByTable = rulesByTable(constraints.rules());

        List<Map<String, Object>> tables = new ArrayList<>();
        for (GenTable table : ordered) {
            long rows = truncateOnly ? 0L : rowCount(table, maxRows);
            List<SyntheticConstraintRules.Rule> tableRules =
                    rulesByTable.getOrDefault(table.name().toLowerCase(Locale.ROOT), List.of());
            boolean hasLookupGenerator = safe(table.columns()).stream()
                    .anyMatch(c -> "LOOKUP".equalsIgnoreCase(blankOr(c.generator(), "")));
            boolean hasFk = safe(table.columns()).stream()
                    .anyMatch(c -> notBlank(c.fkTable()) && notBlank(c.fkColumn()));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("table", table.name());
            entry.put("rows", rows);
            entry.put("memoryMode", truncateOnly ? "NO_ROW_GENERATION" : streaming ? "STREAMING" : "IN_MEMORY");
            entry.put("writeMode", writeMode(receiver, load, streaming, targetKind));
            entry.put("loadAction", load == null ? receiver : load.action());
            entry.put("targetPrep", load == null ? "N/A" : load.targetPrep());
            entry.put("hasApiGenerator", safe(table.columns()).stream().anyMatch(SyntheticGenService::isApi));
            entry.put("hasLookupGenerator", hasLookupGenerator);
            entry.put("foreignKeyColumns", safe(table.columns()).stream()
                    .filter(c -> notBlank(c.fkTable()) && notBlank(c.fkColumn()))
                    .map(c -> c.name() + " -> " + c.fkTable() + "." + c.fkColumn())
                    .toList());
            if (hasFk || hasLookupGenerator) {
                entry.put("parentIndexMode", streaming ? "BOUNDED_RESERVOIR_PARENT_INDEX" : "EXACT_IN_MEMORY_PARENT_INDEX");
                entry.put("parentIndexCap", streaming ? MAX_STREAMING_FK_POOL_VALUES : "not capped");
                entry.put("parentIndexDistribution", streaming
                        ? "Exact until the cap, reservoir-sampled beyond it; FK values remain valid because children draw from retained parent keys/tuples"
                        : "Exact for generated parent rows held in memory");
            }
            entry.put("constraintCount", constraints.raw().stream()
                    .filter(c -> c.table().equalsIgnoreCase(table.name()))
                    .count());
            entry.put("enforcedConstraintCount", tableRules.size());
            if (!tableRules.isEmpty()) {
                entry.put("enforcedConstraints", tableRules.stream()
                        .map(r -> r.constraintName() + ": " + r.column() + " " + r.ruleType())
                        .toList());
            }
            tables.add(entry);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("receiver", receiver);
        out.put("targetKind", targetKind);
        out.put("plannedRows", plannedRows);
        out.put("memoryMode", truncateOnly ? "NO_ROW_GENERATION" : streaming ? "STREAMING" : "IN_MEMORY");
        if (load != null) {
            out.put("loadAction", load.action());
            out.put("targetPrep", load.targetPrep());
            out.put("batchSize", load.batchSize());
            out.put("commitEveryRows", load.commitEveryRows());
            out.put("continueOnError", load.continueOnError());
            out.put("fastLoad", load.fastLoad());
        }
        out.put("constraintsCaptured", constraints.raw().size());
        out.put("constraintsEnforced", constraints.rules().size());
        out.put("constraintCapture", constraintCaptureSummary(targetKind, constraints));
        if (constraints.warning() != null) out.put("constraintCaptureWarning", constraints.warning());
        if (!constraints.raw().isEmpty()) out.put("constraintSnapshot", constraintEvidence(constraints));
        out.put("parentSampling", parentSamplingPolicy(streaming, ordered));
        out.put("bulkLoadCapability", bulkLoadCapability(receiver, load, streaming, targetKind));
        String executionMode = SyntheticPartitioning.mode(plan.executionMode());
        int partitionWorkers = SyntheticPartitioning.workers(plan.partitionCount());
        out.put("executionMode", executionMode);
        out.put("partitionWorkers", "SINGLE".equals(executionMode) ? 1 : partitionWorkers);
        out.put("partitionSize", plan.partitionSize() == null ? "AUTO" : plan.partitionSize());
        if (!"SINGLE".equals(executionMode)) {
            int partitionTotal = 0;
            try {
                for (GenTable table : ordered) {
                    partitionTotal += SyntheticPartitioning.ranges(rowCount(table, maxRows), partitionWorkers, plan.partitionSize()).size();
                }
            } catch (IllegalArgumentException e) {
                throw ApiException.bad(e.getMessage());
            }
            out.put("partitionTotal", partitionTotal);
        }
        out.put("dataPath", syntheticDataPathSummary());
        out.put("governance", governancePolicy());
        out.put("bankingReadiness", SyntheticBankingReadiness.evaluate(plan));
        out.put("tables", tables);
        return out;
    }

    public Map<String, Object> startGenerate(GenPlan plan) {
        return startGenerate(plan, null);
    }

    private Map<String, Object> startGenerate(GenPlan plan, SavedJobRunContext savedJob) {
        if (plan == null) throw ApiException.bad("Synthetic plan is required");
        enforceControlledTargetGovernance(plan, savedJob);
        cleanupSyntheticJobs();
        ConstraintCapture constraints = captureConstraintRules(plan);
        Map<String, Object> summarySnapshot = safePlanSummary(plan);
        SyntheticJob job = new SyntheticJob();
        AccessContext.current().ifPresentOrElse(p -> {
            job.ownerUserId = p.userId();
            job.ownerUsername = p.username();
        }, () -> job.ownerUsername = "system");
        // Reject up front if another load already owns any requested target (before persisting/queuing anything).
        List<String> locks = acquireTargetLocks(plan, job.id, job.ownerUsername);
        job.targetKeys = List.copyOf(locks);
        job.targetKey = locks.isEmpty() ? null : String.join(",", locks);
        boolean handedOff = false;
        try {
        job.dataset = plan.dataset() == null || plan.dataset().isBlank() ? "synthetic" : plan.dataset();
        job.receiver = plan.receiver() == null || plan.receiver().isBlank() ? "DB" : plan.receiver().trim().toUpperCase(Locale.ROOT);
        job.executionMode = SyntheticPartitioning.mode(plan.executionMode());
        job.loadAction = plan.loadAction() == null || plan.loadAction().isBlank() ? "" : plan.loadAction().trim().toUpperCase(Locale.ROOT);
        job.tableCount = plan.tables() == null ? 0 : plan.tables().size();
        job.plannedRows = requestedRows(plan);
        job.planJson = toJson(plan);
        job.planHash = sha256Hex(job.planJson);
        job.constraintSnapshotJson = toJson(constraintEvidence(constraints));
        job.approvalSnapshotJson = toJson(approvalSnapshot(savedJob));
        job.lineageJson = toJson(lineageSnapshot(job, plan, savedJob, constraints, summarySnapshot));
        syntheticJobs.put(job.id, job);
        insertSyntheticJob(job);
        insertGenerationLineage(job, plan, savedJob, constraints, summarySnapshot);
        audit.log("system", "SYNTHETIC_JOB_STARTED",
                "run=" + job.id + " savedJob=" + (savedJob == null ? "DIRECT" : savedJob.id())
                        + " receiver=" + job.receiver + " rows=" + job.plannedRows + " planHash=" + job.planHash);
        Future<?> future = syntheticExecutor.submit(() -> {
            job.status = "RUNNING";
            updateJob(job, 1, "Starting", "Starting synthetic generation");
            // Distributed workers claim only partitions whose parent is visibly RUNNING in the shared DB.
            // The regular progress throttle would otherwise leave this row PENDING for the first 900 ms.
            persistSyntheticJob(job, true);
            try {
                if (syntheticCancelRequested(job)) throw new SyntheticJobCancelledException();
                ProgressSink sink = new ProgressSink() {
                    private void abortIfCancelled() {
                        if (syntheticCancelRequested(job) || Thread.currentThread().isInterrupted())
                            throw new SyntheticJobCancelledException();
                    }
                    @Override public void update(int percent, String stage, String message) {
                        abortIfCancelled();
                        updateJob(job, percent, stage, message);
                    }
                    @Override public void updateRows(int percent, String stage, String message, String table,
                                                     long tableRowsDone, long tableRowsTotal, long rowsDone, long rowsTotal) {
                        abortIfCancelled();
                        updateJobRows(job, percent, stage, message, table, tableRowsDone, tableRowsTotal, rowsDone, rowsTotal);
                    }
                    @Override public void checkCancelled() {
                        abortIfCancelled();
                    }
                    @Override public void activeStatement(Statement statement) {
                        job.activeStatement = statement;
                    }
                    @Override public String jobId() { return job.id; }
                };
                Map<String, Object> result = generate(plan, sink);
                sink.checkCancelled();
                job.result = result;
                updateJob(job, 100, "Completed", "Generation completed");
                job.status = "COMPLETED";
                persistSyntheticJob(job, true);
            } catch (SyntheticJobCancelledException | CancellationException e) {
                markCancelled(job);
            } catch (Throwable e) {
                if (syntheticCancelRequested(job) || Thread.currentThread().isInterrupted()) {
                    markCancelled(job);
                } else {
                    if (e instanceof SyntheticPartialFailureException partial) {
                        job.result = partial.partialResult();
                    }
                    job.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    job.message = job.error;
                    job.stage = "Failed";
                    job.status = "FAILED";
                    job.percent = Math.max(1, job.percent);
                    persistSyntheticJob(job, true);
                }
            } finally {
                job.activeStatement = null;
                job.activeStatements.clear();
                job.finishedAt = Instant.now();
                releaseTargetLocks(job.targetKeys);   // free the targets for the next load
                persistSyntheticJob(job, true);
            }
        });
        job.future = future;
        handedOff = true;
        return snapshot(job);
        } finally {
            if (!handedOff) releaseTargetLocks(locks);   // setup failed before the worker took over -> don't leak the locks
        }
    }

    public List<Map<String, Object>> generateJobs() {
        cleanupSyntheticJobs();
        return querySyntheticJobs(false);
    }

    public Map<String, Object> generateJob(String id) {
        SyntheticJob job = syntheticJobs.get(id);
        if (job != null) {
            ensureCanSee(job.ownerUserId, job.ownerUsername);
            return snapshot(job);
        }
        return querySyntheticJob(id, true);
    }

    public Map<String, Object> cancelGenerate(String id) {
        SyntheticJob job = syntheticJobs.get(id);
        if (job == null) {
            Map<String, Object> saved = querySyntheticJob(id, false);
            String status = String.valueOf(saved.getOrDefault("status", ""));
            if (!isTerminal(status)) {
                jdbc.update("UPDATE synthetic_generation_jobs SET status = 'CANCELLED', cancel_requested = TRUE, " +
                                "stage = 'Cancelled', message = 'Saved job was not active in this server process', " +
                                "finished_at = COALESCE(finished_at, ?), updated_at = ? WHERE id = ?",
                        ts(Instant.now()), ts(Instant.now()), id);
                cancelPersistedPartitions(id);
            }
            audit.log("system", "SYNTHETIC_JOB_CANCEL_REQUESTED", "run=" + id + " persistedOnly=true");
            return querySyntheticJob(id, false);
        }
        ensureCanSee(job.ownerUserId, job.ownerUsername);
        if (isTerminal(job.status)) return snapshot(job, false);

        String previousStatus = job.status;
        job.cancelRequested = true;
        job.status = "CANCELLING";
        job.stage = "Cancelling";
        job.message = "Cancel requested";
        Statement statement = job.activeStatement;
        if (statement != null) {
            try { statement.cancel(); } catch (Exception ignore) { }
        }
        for (Statement active : List.copyOf(job.activeStatements)) {
            try { active.cancel(); } catch (Exception ignore) { }
        }
        cancelPersistedPartitions(id);
        partitionFutures.forEach((partitionId, partitionFuture) -> {
            if (partitionBelongsToJob(partitionId, id)) partitionFuture.cancel(true);
        });
        Future<?> future = job.future;
        if (future != null) future.cancel(true);
        if ("PENDING".equals(previousStatus)) markCancelled(job);
        persistSyntheticJob(job, true);
        audit.log("system", "SYNTHETIC_JOB_CANCEL_REQUESTED", "run=" + id + " status=" + previousStatus);
        return snapshot(job, false);
    }

    /** Observe cancellation written by another HA replica without querying the config DB on every generated row. */
    private boolean syntheticCancelRequested(SyntheticJob job) {
        if (job.cancelRequested) return true;
        long now = System.nanoTime();
        if (now - job.lastCancelProbeNanos < java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(500)) return false;
        job.lastCancelProbeNanos = now;
        try {
            List<Boolean> flags = jdbc.query("SELECT cancel_requested FROM synthetic_generation_jobs WHERE id=?",
                    (rs, rowNum) -> rs.getBoolean(1), job.id);
            if (!flags.isEmpty() && Boolean.TRUE.equals(flags.get(0))) job.cancelRequested = true;
        } catch (Exception ignored) { /* keep running through a transient control-plane read failure */ }
        return job.cancelRequested;
    }

    public Map<String, Object> cancelPartition(String jobId, String partitionId) {
        Map<String, Object> job = querySyntheticJob(jobId, false);
        ensurePartitionBelongs(partitionId, jobId);
        markPartitionCancelled(partitionId, "Cancelled by user");
        Future<?> future = partitionFutures.get(jobId + ":" + partitionId);
        if (future != null) future.cancel(true);
        audit.log(requesterName(), "SYNTHETIC_PARTITION_CANCELLED", "run=" + jobId + " partition=" + partitionId);
        return generateJob(jobId);
    }

    public Map<String, Object> retryPartition(String jobId, String partitionId) {
        Map<String, Object> job = querySyntheticJob(jobId, false);
        ensurePartitionBelongs(partitionId, jobId);
        String status = partitionStatus(partitionId);
        if (!Set.of("FAILED", "CANCELLED", "CANCELED").contains(status)) {
            throw ApiException.bad("Only failed or cancelled partitions can be retried");
        }
        GenPlan plan = partitionPlan(jobId);
        if (plan == null) throw ApiException.notFound("Synthetic plan for job " + jobId + " was not found");
        String lock = acquireTargetLock(plan, jobId + "-retry", requesterName());
        Instant now = Instant.now();
        jdbc.update("UPDATE synthetic_job_partitions SET status = 'QUEUED', cancel_requested = FALSE, rows_completed = 0, " +
                        "worker_id = NULL, error = NULL, lease_expires_at = NULL, finished_at = NULL, updated_at = ? WHERE id = ?",
                ts(now), partitionId);
        jdbc.update("UPDATE synthetic_generation_jobs SET status = 'RUNNING', cancel_requested = FALSE, stage = 'Partition retry', " +
                        "message = 'Retrying failed partition', error = NULL, finished_at = NULL, updated_at = ? WHERE id = ?",
                ts(now), jobId);
        syntheticExecutor.submit(() -> resumePartitionedJob(jobId, plan, lock));
        audit.log(requesterName(), "SYNTHETIC_PARTITION_RETRIED", "run=" + jobId + " partition=" + partitionId);
        return querySyntheticJob(jobId, false);
    }

    public List<Map<String, Object>> savedJobs() {
        AccessPrincipal p = requirePrincipal();
        if (p.hasPermission("admin.all")) {
            return jdbc.query("SELECT * FROM synthetic_saved_jobs ORDER BY updated_at DESC LIMIT 200",
                    (rs, rowNum) -> mapSavedJobRow(rs, true));
        }
        return jdbc.query("SELECT * FROM synthetic_saved_jobs WHERE owner_user_id = ? OR LOWER(owner_username) = LOWER(?) " +
                        "ORDER BY updated_at DESC LIMIT 200",
                (rs, rowNum) -> mapSavedJobRow(rs, true), p.userId(), p.username());
    }

    public Map<String, Object> savedJob(String id) {
        return querySavedJob(id, true);
    }

    public Map<String, Object> saveSyntheticJob(SavedSyntheticJobRequest request) {
        AccessPrincipal p = requirePrincipal();
        String name = cleanSavedJobName(request == null ? null : request.name());
        GenPlan plan = request == null ? null : request.plan();
        validateSavedPlan(plan);
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        try {
            jdbc.update("INSERT INTO synthetic_saved_jobs(id, owner_user_id, owner_username, name, description, plan_json, approval_status, created_at, updated_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?)",
                    id, p.userId(), p.username(), name, blankNull(request.description()), toJson(plan), ts(now), ts(now));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("You already have a saved synthetic job named " + name);
        }
        audit.log(p.username(), "SYNTHETIC_JOB_SAVED", "savedJob=" + id + " name=" + name + " rows=" + requestedRows(plan));
        return querySavedJob(id, true);
    }

    public Map<String, Object> updateSavedJob(String id, SavedSyntheticJobRequest request) {
        Map<String, Object> existing = querySavedJob(id, false);
        String existingName = String.valueOf(existing.get("name"));
        String requestedName = request == null || request.name() == null ? "" : request.name().trim();
        String name = requestedName.isBlank() || requestedName.equals(existingName)
                ? existingName : cleanSavedJobName(requestedName);
        GenPlan plan = request == null || request.plan() == null ? readSavedPlan(id) : request.plan();
        validateSavedPlan(plan);
        try {
            jdbc.update("UPDATE synthetic_saved_jobs SET name = ?, description = ?, plan_json = ?, " +
                            "approval_status = 'DRAFT', approval_requested_at = NULL, approved_at = NULL, approved_by = NULL, " +
                            "approval_note = NULL, updated_at = ? WHERE id = ?",
                    name, request == null ? null : blankNull(request.description()), toJson(plan), ts(Instant.now()), id);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("You already have a saved synthetic job named " + name);
        }
        audit.log("system", "SYNTHETIC_JOB_UPDATED", "savedJob=" + id + " name=" + name + " approval reset to DRAFT");
        return querySavedJob(id, true);
    }

    public void deleteSavedJob(String id) {
        Map<String, Object> existing = querySavedJob(id, false);
        jdbc.update("DELETE FROM synthetic_saved_jobs WHERE id = ?", id);
        audit.log("system", "SYNTHETIC_JOB_DELETED", "savedJob=" + id + " name=" + existing.get("name"));
    }

    public Map<String, Object> runSavedJob(String id) {
        Map<String, Object> saved = querySavedJob(id, true);
        String approvalStatus = String.valueOf(saved.getOrDefault("approvalStatus", "DRAFT"));
        GenPlan plan = (GenPlan) saved.get("plan");
        String receiver = plan == null || plan.receiver() == null ? "DB" : plan.receiver().trim().toUpperCase(Locale.ROOT);
        if ("DB".equals(receiver) && !"APPROVED".equalsIgnoreCase(approvalStatus)) {
            throw ApiException.bad("Database saved jobs require approval before run. Request approval, then have a different user approve it.");
        }
        if ("PENDING_APPROVAL".equalsIgnoreCase(approvalStatus)) {
            throw ApiException.bad("Saved job is pending approval. Approve it or move it back to draft before running.");
        }
        if ("REJECTED".equalsIgnoreCase(approvalStatus)) {
            throw ApiException.bad("Saved job was rejected and cannot run until it is updated.");
        }
        SavedJobRunContext context = savedJobRunContext(saved);
        Map<String, Object> started = startGenerate(plan, context);
        Object runId = started.get("id");
        if (runId != null) {
            jdbc.update("UPDATE synthetic_saved_jobs SET last_run_job_id = ?, updated_at = ? WHERE id = ?",
                    String.valueOf(runId), ts(Instant.now()), id);
        }
        audit.log("system", "SYNTHETIC_JOB_RUN", "savedJob=" + id + " run=" + runId + " approval=" + approvalStatus);
        return started;
    }

    /** Execute a catalog-published synthetic case for the current self-service requester.
     *  Catalog publication is checked by the self-service service; this method deliberately
     *  bypasses artifact ownership while retaining approval and normal generation safeguards. */
    public Map<String, Object> runSelfServiceSavedJob(String id) {
        List<Map<String, Object>> rows = jdbc.query("SELECT plan_json,approval_status,name FROM synthetic_saved_jobs WHERE id = ?",
                (rs, rowNum) -> Map.of("plan", rs.getString(1), "approval", Objects.toString(rs.getString(2), "DRAFT"),
                        "name", Objects.toString(rs.getString(3), "Synthetic case")), id);
        if (rows.isEmpty()) throw ApiException.notFound("Published synthetic case " + id + " not found");
        Map<String, Object> row = rows.get(0);
        if (!"APPROVED".equalsIgnoreCase(String.valueOf(row.get("approval"))))
            throw ApiException.bad("Synthetic self-service cases must be approved before publication");
        GenPlan plan = fromJsonPlan(String.valueOf(row.get("plan")));
        Map<String, Object> started = startGenerate(plan);
        audit.log(requesterName(), "SYNTHETIC_SELF_SERVICE_RUN", "savedJob=" + id + " run=" + started.get("id"));
        return started;
    }

    public Map<String, Object> requestSavedJobApproval(String id, ApprovalRequest request) {
        Map<String, Object> saved = querySavedJob(id, false);
        String note = request == null ? null : blankNull(request.note());
        jdbc.update("UPDATE synthetic_saved_jobs SET approval_status = 'PENDING_APPROVAL', " +
                        "approval_requested_at = ?, approved_at = NULL, approved_by = NULL, approval_note = ?, updated_at = ? WHERE id = ?",
                ts(Instant.now()), note, ts(Instant.now()), id);
        audit.log("system", "SYNTHETIC_JOB_APPROVAL_REQUESTED", "savedJob=" + id + " name=" + saved.get("name"));
        return querySavedJob(id, true);
    }

    public Map<String, Object> approveSavedJob(String id, ApprovalRequest request) {
        Map<String, Object> saved = querySavedJob(id, false);
        AccessPrincipal p = requirePrincipal();
        String owner = String.valueOf(saved.getOrDefault("ownerUsername", ""));
        if (owner.equalsIgnoreCase(p.username())) {
            throw ApiException.bad("Maker-checker approval requires a different user than the saved job owner.");
        }
        String note = request == null ? null : blankNull(request.note());
        if (note == null) {
            throw ApiException.bad("Approval note / e-signature reason is required.");
        }
        jdbc.update("UPDATE synthetic_saved_jobs SET approval_status = 'APPROVED', approved_at = ?, approved_by = ?, " +
                        "approval_note = ?, updated_at = ? WHERE id = ?",
                ts(Instant.now()), p.username(), note, ts(Instant.now()), id);
        audit.log(p.username(), "SYNTHETIC_JOB_APPROVED", "savedJob=" + id + " name=" + saved.get("name"));
        return querySavedJob(id, true);
    }

    public Map<String, Object> rejectSavedJob(String id, ApprovalRequest request) {
        Map<String, Object> saved = querySavedJob(id, false);
        AccessPrincipal p = requirePrincipal();
        String note = request == null ? null : blankNull(request.note());
        if (note == null) {
            throw ApiException.bad("Rejection note is required.");
        }
        jdbc.update("UPDATE synthetic_saved_jobs SET approval_status = 'REJECTED', approved_at = NULL, approved_by = ?, " +
                        "approval_note = ?, updated_at = ? WHERE id = ?",
                p.username(), note, ts(Instant.now()), id);
        audit.log(p.username(), "SYNTHETIC_JOB_REJECTED", "savedJob=" + id + " name=" + saved.get("name"));
        return querySavedJob(id, true);
    }

    public Map<String, Object> exportSavedJobRunner(String id, Map<String, Object> body) {
        Map<String, Object> saved = querySavedJob(id, false);
        String kind = body == null ? "" : String.valueOf(body.getOrDefault("kind", ""));
        kind = kind.equalsIgnoreCase("sh") ? "sh" : "ps1";
        audit.log("system", "SYNTHETIC_JOB_RUNNER_EXPORTED",
                "savedJob=" + id + " name=" + saved.get("name") + " kind=" + kind);
        return Map.of("ok", true, "id", id, "kind", kind);
    }

    private Map<String, Object> generate(GenPlan plan, ProgressSink progress) {
        if (plan.tables() == null || plan.tables().isEmpty()) throw ApiException.bad("Add at least one table");
        for (GenTable t : plan.tables())
            if (t.name() == null || t.name().isBlank()) throw ApiException.bad("Every table needs a name");
        progress.update(2, "Validate plan", "Validating synthetic plan");

        // Auto-wire FK relationships from the target DB so children always reference real parent keys,
        // even if the user didn't fill the FK field. (Only when loading into a real database.)
        plan = hasTargetSystems(plan) ? resolveValueLists(plan) : resolveValueLists(enrichForeignKeys(plan));
        progress.update(5, "Resolve metadata", "Resolved table relationships");

        String receiver = plan.receiver() == null ? "DB" : plan.receiver().trim().toUpperCase(Locale.ROOT);
        long seed = plan.seed() == null ? 42L : plan.seed();
        long maxRows = receiver.equals("DB") ? 100_000_000L : 200_000L;

        List<GenTable> ordered = topoSort(plan.tables());
        for (GenTable t : ordered) validateApiRowLimit(t, rowCount(t, maxRows));

        // which (table.column) are referenced by some FK — only those need a value pool kept
        Set<String> referenced = new HashSet<>();
        for (GenTable t : plan.tables())
            for (GenColumn c : safe(t.columns()))
                if (notBlank(c.fkTable()) && notBlank(c.fkColumn()))
                    referenced.add(key(c.fkTable(), c.fkColumn()));

        if (hasTargetSystems(plan)) {
            return withBankingReadiness(generateMultiTarget(plan, ordered, seed, maxRows, referenced, progress), plan);
        }

        LoadPlan load = "DB".equals(receiver) ? loadPlan(plan) : null;
        String executionMode = SyntheticPartitioning.mode(plan.executionMode());
        if (!"SINGLE".equals(executionMode)) {
            if (!"DB".equals(receiver)) {
                throw ApiException.bad("Partitioned execution currently requires the Database receiver; use Single worker for file output.");
            }
            if (load != null && !load.truncateOnly()) {
                Map<String, Set<String>> partitionUnique = uniqueColumnsByTable(plan);
                ConstraintCapture partitionConstraints = captureConstraintRules(plan);
                return withBankingReadiness(dbLoadPartitioned(plan, ordered, seed, maxRows, referenced,
                        partitionUnique, rulesByTable(partitionConstraints.rules()), plannedSummaryForPlan(ordered, maxRows), progress), plan);
            }
        }
        // Referential integrity to EXISTING target rows: seed FK pools with real parent keys already in
        // the database, so generated children can reference parents that aren't (re)generated in this run.
        Map<String, List<String>> existingFk = "DB".equals(receiver)
                ? loadExistingFkValues(plan, load, referenced) : Map.of();
        // Multi-column foreign keys are kept consistent as tuples (in-memory path).
        Map<String, List<CompositeFk>> composite = "DB".equals(receiver) ? compositeFkGroups(plan) : Map.of();
        // Single-column UNIQUE constraints/indexes on the target — enforced during generation, not just the PK.
        Map<String, Set<String>> uniqueCols = "DB".equals(receiver) ? uniqueColumnsByTable(plan) : Map.of();
        ConstraintCapture constraintCapture = "DB".equals(receiver) ? captureConstraintRules(plan) : ConstraintCapture.empty();
        Map<String, List<SyntheticConstraintRules.Rule>> constraintRules = rulesByTable(constraintCapture.rules());
        long plannedRows = totalRows(ordered, maxRows);
        if ("DB".equals(receiver) && load != null
                && (load.truncateOnly() || plannedRows > STREAMING_DB_ROW_THRESHOLD)) {
            List<Map<String, Object>> summary = load.truncateOnly()
                    ? plannedSummary(ordered, 0L)
                    : plannedSummaryForPlan(ordered, maxRows);
            return withBankingReadiness(
                    dbLoadStreaming(plan, ordered, seed, maxRows, referenced, existingFk, uniqueCols, constraintRules, summary, progress),
                    plan);
        }

        Map<String, List<LinkedHashMap<String, String>>> data;
        List<Map<String, Object>> summary;
        if (load != null && load.truncateOnly()) {
            progress.update(12, "Prepare target", "Skipping row generation for truncate-only");
            data = new LinkedHashMap<>();
            summary = plannedSummary(ordered, 0L);
        } else {
            data = generateData(ordered, seed, maxRows, referenced, existingFk, composite, uniqueCols, constraintRules, progress, 8, 48);
            summary = generatedSummary(ordered, data);
        }

        Map<String, Object> result = switch (receiver) {
            case "CSV":
                progress.update(68, "Build CSV files", "Building CSV files");
                yield fileResult("CSV", summary, csvFiles(ordered, data));
            case "JSON":
                progress.update(68, "Build JSON files", "Building JSON files");
                yield fileResult("JSON", summary, jsonFiles(ordered, data));
            case "SQL":
                progress.update(68, "Build SQL script", "Building SQL script");
                yield fileResult("SQL", summary, sqlFiles(plan, ordered, data));
            case "DB":   yield dbLoad(plan, ordered, data, summary, referenced, progress);
            default:     throw ApiException.bad("receiver must be DB, CSV, JSON, or SQL");
        };
        return withBankingReadiness(result, plan);
    }

    private Map<String, Object> generateMultiTarget(GenPlan plan, List<GenTable> ordered, long seed, long maxRows,
                                                    Set<String> referenced, ProgressSink progress) {
        String receiver = plan.receiver() == null ? "DB" : plan.receiver().trim().toUpperCase(Locale.ROOT);
        if (!"DB".equals(receiver)) {
            throw ApiException.bad("Multi-system generation uses the Database receiver so each target can be loaded through its own connection.");
        }
        String executionMode = SyntheticPartitioning.mode(plan.executionMode());
        List<TargetSystem> targets = targetSystems(plan);
        if (targets.isEmpty()) throw ApiException.bad("Add at least one target system");

        List<GenPlan> targetPlans = new ArrayList<>();
        List<List<GenTable>> targetOrders = new ArrayList<>();
        boolean allTruncateOnly = true;
        for (TargetSystem target : targets) {
            if (target.targetDataSourceId() == null) {
                throw ApiException.bad("Every target system needs a target data source");
            }
            GenPlan targetPlan = projectTargetPlan(plan, target, ordered);
            List<GenTable> targetOrdered = topoSort(targetPlan.tables());
            if (targetOrdered.isEmpty()) {
                throw ApiException.bad("Target system '" + targetDisplayName(target) + "' does not include any mapped tables");
            }
            targetPlans.add(targetPlan);
            targetOrders.add(targetOrdered);
            allTruncateOnly &= loadPlan(targetPlan).truncateOnly();
        }

        long plannedRows = totalRows(ordered, maxRows);
        boolean streamingReplay = !allTruncateOnly && plannedRows > STREAMING_DB_ROW_THRESHOLD;
        if (!"SINGLE".equals(executionMode)) {
            streamingReplay = true;
            progress.update(7, "Multi-target execution",
                    "Multi-system jobs replay the same deterministic stream per target; partition workers remain available for single-target loads.");
        }

        Map<String, List<LinkedHashMap<String, String>>> logicalData;
        if (streamingReplay) {
            logicalData = Map.of();
            progress.update(8, "Streaming replay", "Streaming " + plannedRows + " logical rows per target without holding them in heap");
        } else if (allTruncateOnly) {
            progress.update(12, "Prepare targets", "Skipping row generation for truncate-only multi-system run");
            logicalData = new LinkedHashMap<>();
        } else {
            progress.update(8, "Generate logical rows", "Generating one logical dataset for " + targets.size() + " target system(s)");
            Map<String, List<SyntheticConstraintRules.Rule>> logicalRules =
                    rulesByTable(logicalTargetConstraintRules(plan, targets, ordered));
            logicalData = generateData(ordered, seed, maxRows, referenced, Map.of(), Map.of(), Map.of(), logicalRules,
                    progress, 8, 45);
        }

        List<Map<String, Object>> targetResults = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            TargetSystem target = targets.get(i);
            GenPlan targetPlan = targetPlans.get(i);
            List<GenTable> targetOrdered = targetOrders.get(i);
            LoadPlan load = loadPlan(targetPlan);
            progress.update(percent(48, 95, i, targets.size()), "Load target",
                    "Loading " + targetDisplayName(target));
            ConstraintCapture constraints = captureConstraintRules(targetPlan);
            Set<String> targetReferenced = referencedColumns(targetOrdered);
            List<Map<String, Object>> summary;
            Map<String, Object> loaded;
            try {
                if (streamingReplay) {
                    summary = plannedSummaryForPlan(targetOrdered, maxRows);
                    LoadPlan targetLoad = loadPlan(targetPlan);
                    Map<String, List<String>> existingFk = loadExistingFkValues(targetPlan, targetLoad, targetReferenced);
                    loaded = dbLoadStreaming(targetPlan, targetOrdered, seed, maxRows, targetReferenced, existingFk,
                            uniqueColumnsByTable(targetPlan), rulesByTable(constraints.rules()), summary, progress);
                } else {
                    Map<String, List<LinkedHashMap<String, String>>> projectedData =
                            load.truncateOnly() ? new LinkedHashMap<>() : projectData(logicalData, target, targetOrdered);
                    summary = load.truncateOnly()
                            ? plannedSummary(targetOrdered, 0L)
                            : generatedSummary(targetOrdered, projectedData);
                    loaded = dbLoad(targetPlan, targetOrdered, projectedData, summary, targetReferenced, progress);
                }
            } catch (RuntimeException e) {
                LinkedHashMap<String, Object> failed = targetResult(target, targetPlan, List.of(), Map.of(), constraints);
                failed.put("status", "FAILED");
                failed.put("error", msgOf(e));
                targetResults.add(failed);
                List<String> completed = targetResults.stream()
                        .filter(r -> "COMPLETED".equals(String.valueOf(r.get("status"))))
                        .map(r -> String.valueOf(r.get("name")))
                        .toList();
                String message = "Multi-target load failed on " + targetDisplayName(target)
                        + (completed.isEmpty() ? "" : ". Completed before failure: " + String.join(", ", completed))
                        + ". Error: " + msgOf(e);
                LinkedHashMap<String, Object> partial = multiTargetResult(ordered, logicalData, allTruncateOnly, maxRows,
                        streamingReplay, targets.size(), targetResults);
                partial.put("status", "FAILED");
                throw new SyntheticPartialFailureException(message, partial);
            }

            LinkedHashMap<String, Object> targetResult = targetResult(target, targetPlan, summary, loaded, constraints);
            targetResult.put("status", "COMPLETED");
            targetResults.add(targetResult);
        }
        progress.update(98, "Validate", "Validated " + targets.size() + " target system(s)");

        LinkedHashMap<String, Object> out = multiTargetResult(ordered, logicalData, allTruncateOnly, maxRows,
                streamingReplay, targets.size(), targetResults);
        out.put("status", "COMPLETED");
        return out;
    }

    private LinkedHashMap<String, Object> multiTargetResult(List<GenTable> ordered,
                                                            Map<String, List<LinkedHashMap<String, String>>> logicalData,
                                                            boolean allTruncateOnly, long maxRows, boolean streamingReplay,
                                                            int targetCount, List<Map<String, Object>> targetResults) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("receiver", "DB");
        out.put("multiSystem", true);
        out.put("executionMode", streamingReplay ? "STREAMING_REPLAY" : "IN_MEMORY_SHARED");
        out.put("failurePolicy", multiTargetFailurePolicy());
        out.put("targetCount", targetCount);
        out.put("logicalTables", generatedSummaryForLogical(ordered, logicalData, allTruncateOnly, maxRows));
        out.put("targets", targetResults);
        return out;
    }

    private Map<String, Object> multiTargetPlanSummary(GenPlan plan) {
        String receiver = plan.receiver() == null ? "DB" : plan.receiver().trim().toUpperCase(Locale.ROOT);
        if (!"DB".equals(receiver)) {
            throw ApiException.bad("Multi-system plans require the Database receiver.");
        }
        List<GenTable> ordered = topoSort(plan.tables());
        long maxRows = 100_000_000L;
        long plannedRows = totalRows(ordered, maxRows);
        boolean streamingSized = plannedRows > STREAMING_DB_ROW_THRESHOLD;

        List<Map<String, Object>> logicalTables = new ArrayList<>();
        for (GenTable table : ordered) {
            LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
            entry.put("table", table.name());
            entry.put("rows", rowCount(table, maxRows));
            entry.put("memoryMode", streamingSized ? "STREAMING_REPLAY" : "IN_MEMORY_SHARED");
            entry.put("foreignKeyColumns", safe(table.columns()).stream()
                    .filter(c -> notBlank(c.fkTable()) && notBlank(c.fkColumn()))
                    .map(c -> c.name() + " -> " + c.fkTable() + "." + c.fkColumn())
                    .toList());
            logicalTables.add(entry);
        }

        List<Map<String, Object>> targets = new ArrayList<>();
        for (TargetSystem target : targetSystems(plan)) {
            GenPlan targetPlan = projectTargetPlan(plan, target, ordered);
            LoadPlan load = loadPlan(targetPlan);
            ConstraintCapture constraints = captureConstraintRules(targetPlan);
            LinkedHashMap<String, Object> t = new LinkedHashMap<>();
            t.put("name", targetDisplayName(target));
            t.put("targetDataSourceId", target.targetDataSourceId());
            putIfNotBlank(t, "targetSchema", targetPlan.targetSchema());
            t.put("targetKind", targetKind(targetPlan));
            t.put("loadAction", load.action());
            t.put("targetPrep", load.targetPrep());
            t.put("batchSize", load.batchSize());
            t.put("constraintsCaptured", constraints.raw().size());
            t.put("constraintsEnforced", constraints.rules().size());
            t.put("constraintCapture", constraintCaptureSummary(targetKind(targetPlan), constraints));
            t.put("mappedTables", targetPlan.tables().stream().map(table -> {
                LinkedHashMap<String, Object> m = new LinkedHashMap<>();
                m.put("physicalTable", table.name());
                m.put("columns", safe(table.columns()).stream().map(GenColumn::name).toList());
                return m;
            }).toList());
            targets.add(t);
        }

        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("receiver", "DB");
        out.put("multiSystem", true);
        out.put("targetCount", targets.size());
        out.put("plannedRows", plannedRows);
        out.put("memoryMode", streamingSized ? "STREAMING_REPLAY" : "IN_MEMORY_SHARED");
        out.put("executionMode", SyntheticPartitioning.mode(plan.executionMode()));
        out.put("writeMode", streamingSized ? "STREAMING_REPLAY_PER_TARGET" : "BATCH_PROJECTED_PER_TARGET");
        out.put("failurePolicy", multiTargetFailurePolicy());
        out.put("logicalTables", logicalTables);
        out.put("targets", targets);
        out.put("dataPath", syntheticDataPathSummary());
        out.put("governance", governancePolicy());
        out.put("bankingReadiness", SyntheticBankingReadiness.evaluate(plan));
        return out;
    }

    private GenPlan projectTargetPlan(GenPlan plan, TargetSystem target, List<GenTable> ordered) {
        Map<String, TableProjection> tableMappings = tableProjectionMap(target);
        boolean explicitTables = !tableMappings.isEmpty();
        List<GenTable> projectedTables = new ArrayList<>();
        for (GenTable logical : safe(ordered)) {
            TableProjection tableProjection = tableMappings.get(logical.name().toLowerCase(Locale.ROOT));
            if (explicitTables && tableProjection == null) continue;
            String physicalTable = firstNonBlank(tableProjection == null ? null : tableProjection.physicalTable(), logical.name());
            Map<String, ColumnProjection> columnMappings = columnProjectionMap(tableProjection);
            boolean explicitColumns = !columnMappings.isEmpty();
            List<GenColumn> projectedColumns = new ArrayList<>();
            for (GenColumn column : safe(logical.columns())) {
                ColumnProjection columnProjection = columnMappings.get(column.name().toLowerCase(Locale.ROOT));
                if (explicitColumns && columnProjection == null) continue;
                String physicalColumn = firstNonBlank(columnProjection == null ? null : columnProjection.physicalColumn(), column.name());
                String sqlType = firstNonBlank(columnProjection == null ? null : columnProjection.sqlType(), column.sqlType());
                String fkTable = notBlank(column.fkTable()) ? projectedTableName(target, column.fkTable()) : column.fkTable();
                String fkColumn = notBlank(column.fkColumn()) ? projectedColumnName(target, column.fkTable(), column.fkColumn()) : column.fkColumn();
                String param1 = projectGeneratorParam1(target, logical, column);
                String param2 = projectGeneratorParam2(target, logical, column);
                projectedColumns.add(new GenColumn(physicalColumn, column.generator(), param1, param2,
                        column.primaryKey(), fkTable, fkColumn, sqlType, column.fkMin(), column.fkMax()));
            }
            projectedTables.add(new GenTable(physicalTable, logical.rowCount(), projectedColumns));
        }
        return new GenPlan(plan.dataset(), projectedTables, plan.seed(), "DB",
                target.targetDataSourceId(),
                firstNonBlank(target.targetSchema(), plan.targetSchema()),
                firstNonNull(target.createTable(), plan.createTable()),
                firstNonNull(target.dropTable(), plan.dropTable()),
                firstNonBlank(target.prepMode(), plan.prepMode()),
                firstNonBlank(target.loadAction(), plan.loadAction()),
                firstNonBlank(target.targetPrep(), plan.targetPrep()),
                !safe(target.keyColumns()).isEmpty() ? target.keyColumns() : plan.keyColumns(),
                firstNonNull(target.batchSize(), plan.batchSize()),
                firstNonNull(target.commitEveryRows(), plan.commitEveryRows()),
                firstNonNull(target.continueOnError(), plan.continueOnError()),
                firstNonNull(target.maxRejects(), plan.maxRejects()),
                firstNonNull(target.fastLoad(), plan.fastLoad()),
                "SINGLE", null, null, null);
    }

    private Map<String, List<LinkedHashMap<String, String>>> projectData(
            Map<String, List<LinkedHashMap<String, String>>> logicalData, TargetSystem target, List<GenTable> targetOrdered) {
        Map<String, TableProjection> tableMappings = tableProjectionMap(target);
        Map<String, List<LinkedHashMap<String, String>>> out = new LinkedHashMap<>();
        for (GenTable targetTable : safe(targetOrdered)) {
            String logicalTable = logicalTableForPhysical(target, targetTable.name());
            List<LinkedHashMap<String, String>> sourceRows = logicalData.getOrDefault(logicalTable.toLowerCase(Locale.ROOT), List.of());
            TableProjection tableProjection = tableMappings.get(logicalTable.toLowerCase(Locale.ROOT));
            Map<String, String> physicalToLogical = physicalToLogicalColumns(tableProjection);
            List<LinkedHashMap<String, String>> targetRows = new ArrayList<>();
            for (LinkedHashMap<String, String> sourceRow : sourceRows) {
                LinkedHashMap<String, String> targetRow = new LinkedHashMap<>();
                for (GenColumn targetColumn : safe(targetTable.columns())) {
                    String logicalColumn = physicalToLogical.getOrDefault(targetColumn.name().toLowerCase(Locale.ROOT), targetColumn.name());
                    targetRow.put(targetColumn.name(), sourceRow.get(matchKey(sourceRow, logicalColumn)));
                }
                targetRows.add(targetRow);
            }
            out.put(targetTable.name().toLowerCase(Locale.ROOT), targetRows);
        }
        return out;
    }

    private List<SyntheticConstraintRules.Rule> logicalTargetConstraintRules(GenPlan plan, List<TargetSystem> targets,
                                                                            List<GenTable> ordered) {
        List<SyntheticConstraintRules.Rule> rules = new ArrayList<>();
        for (TargetSystem target : safe(targets)) {
            GenPlan targetPlan = projectTargetPlan(plan, target, ordered);
            ConstraintCapture capture = captureConstraintRules(targetPlan);
            for (SyntheticConstraintRules.Rule rule : capture.rules()) {
                String logicalTable = logicalTableForPhysical(target, rule.table());
                String logicalColumn = logicalColumnForPhysical(target, logicalTable, rule.column());
                if (logicalTable == null || logicalColumn == null) continue;
                rules.add(new SyntheticConstraintRules.Rule(
                        logicalTable,
                        targetDisplayName(target) + ":" + rule.constraintName(),
                        rule.expression(),
                        logicalColumn,
                        rule.ruleType(),
                        rule.allowedValues(),
                        rule.min(),
                        rule.max()));
            }
        }
        return rules;
    }

    private LinkedHashMap<String, Object> targetResult(TargetSystem target, GenPlan targetPlan,
                                                       List<Map<String, Object>> summary,
                                                       Map<String, Object> loaded,
                                                       ConstraintCapture constraints) {
        LinkedHashMap<String, Object> targetResult = new LinkedHashMap<>();
        targetResult.put("name", targetDisplayName(target));
        targetResult.put("targetDataSourceId", target.targetDataSourceId());
        putIfNotBlank(targetResult, "targetSchema", targetPlan.targetSchema());
        targetResult.put("targetKind", targetKind(targetPlan));
        targetResult.put("tables", summary == null ? List.of() : summary);
        targetResult.put("constraints", constraintEvidence(constraints));
        if (loaded != null && !loaded.isEmpty()) {
            if (loaded.get("validation") != null) targetResult.put("validation", loaded.get("validation"));
            if (loaded.get("rangeWarnings") != null) targetResult.put("rangeWarnings", loaded.get("rangeWarnings"));
            if (loaded.get("rejects") != null) targetResult.put("rejects", loaded.get("rejects"));
            if (loaded.get("streamed") != null) targetResult.put("streamed", loaded.get("streamed"));
            targetResult.put("result", loaded);
        }
        return targetResult;
    }

    private static Map<String, Object> multiTargetFailurePolicy() {
        return Map.of(
                "transactionScope", "per-target database transaction",
                "onFailure", "stop at the failed target and report already completed targets",
                "resume", "rerun the saved job for the failed target or adjust the target mapping and run again",
                "distributedAtomicity", "no XA/two-phase commit across heterogeneous databases");
    }

    private static String projectGeneratorParam1(TargetSystem target, GenTable logicalTable, GenColumn column) {
        String generator = column.generator() == null ? "" : column.generator().trim().toUpperCase(Locale.ROOT);
        String p1 = column.param1();
        if (p1 == null || p1.isBlank()) return p1;
        return switch (generator) {
            case "TEMPLATE" -> projectTemplate(target, logicalTable, p1);
            case "COPY", "CASE", "DATE_AFTER" -> projectColumnSpec(target, logicalTable.name(), p1, false);
            case "LOOKUP" -> projectColumnSpec(target, column.fkTable(), p1, true);
            default -> p1;
        };
    }

    private static String projectGeneratorParam2(TargetSystem target, GenTable logicalTable, GenColumn column) {
        String generator = column.generator() == null ? "" : column.generator().trim().toUpperCase(Locale.ROOT);
        String p2 = column.param2();
        if (p2 == null || p2.isBlank()) return p2;
        if ("LOOKUP".equals(generator)) return projectColumnSpec(target, logicalTable.name(), p2, false);
        return p2;
    }

    private static String projectColumnSpec(TargetSystem target, String logicalTable, String spec, boolean allowModifier) {
        if (spec == null || spec.isBlank() || logicalTable == null || logicalTable.isBlank()) return spec;
        int colon = allowModifier ? spec.indexOf(':') : -1;
        String col = colon < 0 ? spec : spec.substring(0, colon);
        String suffix = colon < 0 ? "" : spec.substring(colon);
        return projectedColumnName(target, logicalTable, col) + suffix;
    }

    private static String projectTemplate(TargetSystem target, GenTable logicalTable, String template) {
        if (template == null || template.isBlank() || logicalTable == null) return template;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\$\\{([^}]+)}").matcher(template);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String token = matcher.group(1);
            String replacement = token;
            if (!"domain".equalsIgnoreCase(token) && !token.toLowerCase(Locale.ROOT).startsWith("rand:")) {
                int colon = token.indexOf(':');
                String col = colon < 0 ? token : token.substring(0, colon);
                String suffix = colon < 0 ? "" : token.substring(colon);
                if (logicalColumnExists(logicalTable, col)) {
                    replacement = projectedColumnName(target, logicalTable.name(), col) + suffix;
                }
            }
            matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement("${" + replacement + "}"));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static boolean logicalColumnExists(GenTable table, String name) {
        if (table == null || name == null) return false;
        for (GenColumn column : safe(table.columns())) {
            if (column.name() != null && column.name().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private List<Map<String, Object>> generatedSummaryForLogical(List<GenTable> ordered,
            Map<String, List<LinkedHashMap<String, String>>> data, boolean truncateOnly, long maxRows) {
        if (truncateOnly) return plannedSummary(ordered, 0L);
        if (data == null || data.isEmpty()) return plannedSummaryForPlan(ordered, maxRows);
        return generatedSummary(ordered, data);
    }

    private Set<String> referencedColumns(List<GenTable> tables) {
        Set<String> referenced = new HashSet<>();
        for (GenTable table : safe(tables)) {
            for (GenColumn column : safe(table.columns())) {
                if (notBlank(column.fkTable()) && notBlank(column.fkColumn())) {
                    referenced.add(key(column.fkTable(), column.fkColumn()));
                }
            }
        }
        return referenced;
    }

    private static boolean hasTargetSystems(GenPlan plan) {
        return plan != null && !safe(plan.targetSystems()).isEmpty();
    }

    private static List<TargetSystem> targetSystems(GenPlan plan) {
        return plan == null ? List.of() : safe(plan.targetSystems()).stream()
                .filter(Objects::nonNull)
                .toList();
    }

    private static Map<String, TableProjection> tableProjectionMap(TargetSystem target) {
        Map<String, TableProjection> out = new LinkedHashMap<>();
        if (target == null) return out;
        for (TableProjection projection : safe(target.tables())) {
            String logical = logicalTableName(projection);
            if (notBlank(logical)) out.put(logical.toLowerCase(Locale.ROOT), projection);
        }
        return out;
    }

    private static Map<String, ColumnProjection> columnProjectionMap(TableProjection projection) {
        Map<String, ColumnProjection> out = new LinkedHashMap<>();
        if (projection == null) return out;
        for (ColumnProjection column : safe(projection.columns())) {
            String logical = logicalColumnName(column);
            if (notBlank(logical)) out.put(logical.toLowerCase(Locale.ROOT), column);
        }
        return out;
    }

    private static Map<String, String> physicalToLogicalColumns(TableProjection projection) {
        Map<String, String> out = new LinkedHashMap<>();
        if (projection == null) return out;
        for (ColumnProjection column : safe(projection.columns())) {
            String logical = logicalColumnName(column);
            String physical = firstNonBlank(column.physicalColumn(), logical);
            if (notBlank(logical) && notBlank(physical)) out.put(physical.toLowerCase(Locale.ROOT), logical);
        }
        return out;
    }

    private static String logicalTableForPhysical(TargetSystem target, String physicalTable) {
        if (target != null) {
            for (TableProjection projection : safe(target.tables())) {
                String physical = firstNonBlank(projection.physicalTable(), logicalTableName(projection));
                if (physical != null && physical.equalsIgnoreCase(physicalTable)) return logicalTableName(projection);
            }
        }
        return physicalTable;
    }

    private static String logicalColumnForPhysical(TargetSystem target, String logicalTable, String physicalColumn) {
        if (physicalColumn == null) return null;
        TableProjection table = logicalTable == null ? null : tableProjectionMap(target).get(logicalTable.toLowerCase(Locale.ROOT));
        Map<String, String> physicalToLogical = physicalToLogicalColumns(table);
        return physicalToLogical.getOrDefault(physicalColumn.toLowerCase(Locale.ROOT), physicalColumn);
    }

    private static String projectedTableName(TargetSystem target, String logicalTable) {
        if (logicalTable == null) return null;
        TableProjection projection = tableProjectionMap(target).get(logicalTable.toLowerCase(Locale.ROOT));
        return firstNonBlank(projection == null ? null : projection.physicalTable(), logicalTable);
    }

    private static String projectedColumnName(TargetSystem target, String logicalTable, String logicalColumn) {
        if (logicalColumn == null) return null;
        TableProjection table = logicalTable == null ? null : tableProjectionMap(target).get(logicalTable.toLowerCase(Locale.ROOT));
        ColumnProjection column = columnProjectionMap(table).get(logicalColumn.toLowerCase(Locale.ROOT));
        return firstNonBlank(column == null ? null : column.physicalColumn(), logicalColumn);
    }

    private static String logicalTableName(TableProjection projection) {
        return projection == null ? null : firstNonBlank(projection.logicalTable(), projection.physicalTable());
    }

    private static String logicalColumnName(ColumnProjection projection) {
        return projection == null ? null : firstNonBlank(projection.logicalColumn(), projection.physicalColumn());
    }

    private static String targetDisplayName(TargetSystem target) {
        String name = target == null ? null : blankNull(target.name());
        if (name != null) return name;
        return target == null || target.targetDataSourceId() == null
                ? "(target)"
                : "data source " + target.targetDataSourceId();
    }

    private static String firstNonBlank(String first, String fallback) {
        String clean = blankNull(first);
        return clean == null ? blankNull(fallback) : clean;
    }

    private static <T> T firstNonNull(T first, T fallback) {
        return first != null ? first : fallback;
    }

    private void updateJob(SyntheticJob job, int percent, String stage, String message) {
        job.percent = Math.max(job.percent, Math.max(0, Math.min(100, percent)));
        job.stage = stage == null || stage.isBlank() ? job.stage : stage;
        job.message = message == null || message.isBlank() ? job.stage : message;
        if (message != null && message.startsWith("Streaming ") && message.contains(" DB rows")) job.streamingBanner = message;
        if (stage == null || !Set.of("Load rows", "Update rows", "Upsert rows").contains(stage)) {
            job.detail = null;
            job.currentTable = null;
            job.tableRowsDone = 0;
            job.tableRowsTotal = 0;
        }
        persistSyntheticJob(job, false);
    }

    private void updateJobRows(SyntheticJob job, int percent, String stage, String message, String table,
                               long tableRowsDone, long tableRowsTotal, long rowsDone, long rowsTotal) {
        job.percent = Math.max(job.percent, Math.max(0, Math.min(100, percent)));
        job.stage = stage == null || stage.isBlank() ? job.stage : stage;
        job.message = job.streamingBanner != null ? job.streamingBanner : (message == null || message.isBlank() ? job.stage : message);
        job.currentTable = table;
        job.tableRowsDone = Math.max(0, tableRowsDone);
        job.tableRowsTotal = Math.max(0, tableRowsTotal);
        job.rowsDone = Math.max(0, rowsDone);
        job.rowsTotal = Math.max(0, rowsTotal);
        String rowVerb = switch (job.stage) {
            case "Update rows" -> "rows updated";
            case "Upsert rows" -> "rows upserted";
            default -> job.streamingBanner != null ? "rows streamed" : "rows loaded";
        };
        job.detail = (table == null || table.isBlank() ? "Rows" : table)
                + ": " + job.tableRowsDone + " of " + job.tableRowsTotal
                + " " + rowVerb + "; total " + job.rowsDone + " of " + job.rowsTotal;
        persistSyntheticJob(job, false);
    }

    private Map<String, Object> snapshot(SyntheticJob job) {
        return snapshot(job, true);
    }

    private Map<String, Object> snapshot(SyntheticJob job, boolean includeResult) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("id", job.id);
        out.put("status", job.status);
        out.put("cancelRequested", job.cancelRequested);
        if (job.dataset != null) out.put("dataset", job.dataset);
        if (job.ownerUsername != null) out.put("ownerUsername", job.ownerUsername);
        if (job.receiver != null) out.put("receiver", job.receiver);
        out.put("executionMode", job.executionMode);
        if (job.loadAction != null && !job.loadAction.isBlank()) out.put("loadAction", job.loadAction);
        out.put("tableCount", job.tableCount);
        out.put("plannedRows", job.plannedRows);
        out.put("percent", job.percent);
        out.put("stage", job.stage);
        out.put("message", job.message);
        out.put("startedAt", job.startedAt.toString());
        if (job.finishedAt != null) out.put("finishedAt", job.finishedAt.toString());
        if (job.detail != null) out.put("detail", job.detail);
        if (job.currentTable != null) out.put("currentTable", job.currentTable);
        if (job.tableRowsTotal > 0) {
            out.put("tableRowsDone", job.tableRowsDone);
            out.put("tableRowsTotal", job.tableRowsTotal);
        }
        if (job.rowsTotal > 0) {
            out.put("rowsDone", job.rowsDone);
            out.put("rowsTotal", job.rowsTotal);
        }
        if (job.error != null) out.put("error", job.error);
        if (job.planHash != null) out.put("planHash", job.planHash);
        if (job.lineageJson != null) out.put("lineage", fromJsonMap(job.lineageJson));
        if (job.constraintSnapshotJson != null) out.put("constraintSnapshot", fromJsonMap(job.constraintSnapshotJson));
        if (job.approvalSnapshotJson != null) out.put("approvalSnapshot", fromJsonMap(job.approvalSnapshotJson));
        if (includeResult && job.result != null) out.put("result", job.result);
        List<Map<String, Object>> partitions = queryPartitionSnapshots(job.id);
        if (!partitions.isEmpty()) out.put("partitions", partitions);
        return out;
    }

    private void cleanupSyntheticJobs() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(2));
        syntheticJobs.entrySet().removeIf(e -> e.getValue().finishedAt != null && e.getValue().finishedAt.isBefore(cutoff));
    }

    private void markCancelled(SyntheticJob job) {
        job.cancelRequested = true;
        job.status = "CANCELLED";
        job.stage = "Cancelled";
        job.message = "Synthetic generation cancelled";
        job.error = null;
        job.result = null;
        job.activeStatement = null;
        if (job.finishedAt == null) job.finishedAt = Instant.now();
        job.percent = Math.max(1, job.percent);
        persistSyntheticJob(job, true);
    }

    private static boolean isTerminal(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status)
                || "CANCELLED".equals(status) || "CANCELED".equals(status);
    }

    private void insertSyntheticJob(SyntheticJob job) {
        Instant now = Instant.now();
        job.updatedAt = now;
        job.lastPersistedAt = now;
        jdbc.update("INSERT INTO synthetic_generation_jobs(" +
                        "id, owner_user_id, owner_username, dataset, receiver, load_action, table_count, planned_rows, " +
                        "status, cancel_requested, percent, stage, message, detail, current_table, table_rows_done, " +
                        "table_rows_total, rows_done, rows_total, error, plan_json, plan_hash, lineage_json, " +
                        "constraint_snapshot_json, approval_snapshot_json, result_json, started_at, finished_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                job.id, job.ownerUserId, job.ownerUsername, job.dataset, job.receiver, job.loadAction,
                job.tableCount, job.plannedRows, job.status, job.cancelRequested, job.percent, job.stage,
                job.message, job.detail, job.currentTable, job.tableRowsDone, job.tableRowsTotal,
                job.rowsDone, job.rowsTotal, job.error, job.planJson, job.planHash, job.lineageJson,
                job.constraintSnapshotJson, job.approvalSnapshotJson, null, ts(job.startedAt), tsOrNull(job.finishedAt), ts(now));
    }

    private void persistSyntheticJob(SyntheticJob job, boolean force) {
        Instant now = Instant.now();
        if (!force && job.lastPersistedAt != null && now.toEpochMilli() - job.lastPersistedAt.toEpochMilli() < 900) return;
        job.updatedAt = now;
        job.lastPersistedAt = now;
        jdbc.update("UPDATE synthetic_generation_jobs SET owner_user_id = ?, owner_username = ?, dataset = ?, receiver = ?, " +
                        "load_action = ?, table_count = ?, planned_rows = ?, status = ?, cancel_requested = ?, percent = ?, " +
                        "stage = ?, message = ?, detail = ?, current_table = ?, table_rows_done = ?, table_rows_total = ?, " +
                        "rows_done = ?, rows_total = ?, error = ?, plan_hash = ?, lineage_json = ?, constraint_snapshot_json = ?, " +
                        "approval_snapshot_json = ?, result_json = ?, finished_at = ?, updated_at = ? WHERE id = ?",
                job.ownerUserId, job.ownerUsername, job.dataset, job.receiver, job.loadAction, job.tableCount,
                job.plannedRows, job.status, job.cancelRequested, job.percent, job.stage, job.message, job.detail,
                job.currentTable, job.tableRowsDone, job.tableRowsTotal, job.rowsDone, job.rowsTotal, job.error,
                job.planHash, job.lineageJson, job.constraintSnapshotJson, job.approvalSnapshotJson,
                job.result == null ? null : toJson(job.result), tsOrNull(job.finishedAt), ts(now), job.id);
    }

    private List<Map<String, Object>> querySyntheticJobs(boolean includeResult) {
        Optional<AccessPrincipal> principal = AccessContext.current();
        if (principal.isPresent() && principal.get().hasPermission("admin.all")) {
            List<Map<String, Object>> rows = jdbc.query("SELECT * FROM synthetic_generation_jobs ORDER BY started_at DESC LIMIT 100",
                    (rs, rowNum) -> mapSyntheticJobRow(rs, includeResult));
            enrichPartitionSnapshots(rows);
            return rows;
        }
        if (principal.isPresent()) {
            AccessPrincipal p = principal.get();
            List<Map<String, Object>> rows = jdbc.query("SELECT * FROM synthetic_generation_jobs " +
                            "WHERE owner_user_id = ? OR LOWER(owner_username) = LOWER(?) " +
                            "ORDER BY started_at DESC LIMIT 100",
                    (rs, rowNum) -> mapSyntheticJobRow(rs, includeResult), p.userId(), p.username());
            enrichPartitionSnapshots(rows);
            return rows;
        }
        List<Map<String, Object>> rows = jdbc.query("SELECT * FROM synthetic_generation_jobs WHERE owner_username = 'system' ORDER BY started_at DESC LIMIT 100",
                (rs, rowNum) -> mapSyntheticJobRow(rs, includeResult));
        enrichPartitionSnapshots(rows);
        return rows;
    }

    private Map<String, Object> querySyntheticJob(String id, boolean includeResult) {
        List<Map<String, Object>> rows = jdbc.query("SELECT * FROM synthetic_generation_jobs WHERE id = ?",
                (rs, rowNum) -> mapSyntheticJobRow(rs, includeResult), id);
        if (rows.isEmpty()) throw ApiException.notFound("Synthetic job " + id + " not found");
        Map<String, Object> row = rows.get(0);
        ensureCanSee((Long) row.get("ownerUserId"), (String) row.get("ownerUsername"));
        if (!includeResult) row.remove("result");
        List<Map<String, Object>> partitions = queryPartitionSnapshots(id);
        if (!partitions.isEmpty()) row.put("partitions", partitions);
        return row;
    }

    private Map<String, Object> querySavedJob(String id, boolean includePlan) {
        List<Map<String, Object>> rows = jdbc.query("SELECT * FROM synthetic_saved_jobs WHERE id = ?",
                (rs, rowNum) -> mapSavedJobRow(rs, includePlan), id);
        if (rows.isEmpty()) throw ApiException.notFound("Saved synthetic job " + id + " not found");
        Map<String, Object> row = rows.get(0);
        ensureCanSee((Long) row.get("ownerUserId"), (String) row.get("ownerUsername"));
        return row;
    }

    private Map<String, Object> mapSavedJobRow(ResultSet rs, boolean includePlan) throws SQLException {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("id", rs.getString("id"));
        Long ownerUserId = rs.getObject("owner_user_id") == null ? null : rs.getLong("owner_user_id");
        out.put("ownerUserId", ownerUserId);
        out.put("ownerUsername", rs.getString("owner_username"));
        out.put("name", rs.getString("name"));
        putIfNotBlank(out, "description", rs.getString("description"));
        putIfNotBlank(out, "lastRunJobId", rs.getString("last_run_job_id"));
        putIfNotBlank(out, "approvalStatus", safeColumn(rs, "approval_status"));
        putIfNotBlank(out, "approvalRequestedAt", instantString(safeTimestamp(rs, "approval_requested_at")));
        putIfNotBlank(out, "approvedAt", instantString(safeTimestamp(rs, "approved_at")));
        putIfNotBlank(out, "approvedBy", safeColumn(rs, "approved_by"));
        putIfNotBlank(out, "approvalNote", safeColumn(rs, "approval_note"));
        out.put("createdAt", instantString(rs.getTimestamp("created_at")));
        out.put("updatedAt", instantString(rs.getTimestamp("updated_at")));
        GenPlan plan = fromJsonPlan(rs.getString("plan_json"));
        out.put("dataset", plan.dataset());
        out.put("receiver", plan.receiver());
        out.put("tableCount", safe(plan.tables()).size());
        out.put("plannedRows", requestedRows(plan));
        if (includePlan) out.put("plan", plan);
        return out;
    }

    private Map<String, Object> mapSyntheticJobRow(ResultSet rs, boolean includeResult) throws SQLException {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("id", rs.getString("id"));
        Long ownerUserId = rs.getObject("owner_user_id") == null ? null : rs.getLong("owner_user_id");
        out.put("ownerUserId", ownerUserId);
        out.put("ownerUsername", rs.getString("owner_username"));
        out.put("status", rs.getString("status"));
        out.put("cancelRequested", rs.getBoolean("cancel_requested"));
        out.put("dataset", rs.getString("dataset"));
        out.put("receiver", rs.getString("receiver"));
        String persistedPlanJson = rs.getString("plan_json");
        GenPlan persistedPlan = persistedPlanJson == null || persistedPlanJson.isBlank() ? null : fromJsonPlan(persistedPlanJson);
        out.put("executionMode", SyntheticPartitioning.mode(persistedPlan == null ? null : persistedPlan.executionMode()));
        out.put("loadAction", rs.getString("load_action"));
        out.put("tableCount", rs.getInt("table_count"));
        out.put("plannedRows", rs.getLong("planned_rows"));
        out.put("percent", rs.getInt("percent"));
        out.put("stage", rs.getString("stage"));
        out.put("message", rs.getString("message"));
        putIfNotBlank(out, "detail", rs.getString("detail"));
        putIfNotBlank(out, "currentTable", rs.getString("current_table"));
        long tableRowsTotal = rs.getLong("table_rows_total");
        if (tableRowsTotal > 0) {
            out.put("tableRowsDone", rs.getLong("table_rows_done"));
            out.put("tableRowsTotal", tableRowsTotal);
        }
        long rowsTotal = rs.getLong("rows_total");
        if (rowsTotal > 0) {
            out.put("rowsDone", rs.getLong("rows_done"));
            out.put("rowsTotal", rowsTotal);
        }
        putIfNotBlank(out, "error", rs.getString("error"));
        putIfNotBlank(out, "planHash", safeColumn(rs, "plan_hash"));
        String lineage = safeColumn(rs, "lineage_json");
        if (lineage != null) out.put("lineage", fromJsonMap(lineage));
        String constraints = safeColumn(rs, "constraint_snapshot_json");
        if (constraints != null) out.put("constraintSnapshot", fromJsonMap(constraints));
        String approval = safeColumn(rs, "approval_snapshot_json");
        if (approval != null) out.put("approvalSnapshot", fromJsonMap(approval));
        out.put("startedAt", instantString(rs.getTimestamp("started_at")));
        putIfNotBlank(out, "finishedAt", instantString(rs.getTimestamp("finished_at")));
        putIfNotBlank(out, "updatedAt", instantString(rs.getTimestamp("updated_at")));
        if (includeResult && rs.getString("result_json") != null) out.put("result", fromJsonMap(rs.getString("result_json")));
        return out;
    }

    private GenPlan readSavedPlan(String id) {
        List<String> rows = jdbc.query("SELECT plan_json FROM synthetic_saved_jobs WHERE id = ?",
                (rs, rowNum) -> rs.getString(1), id);
        if (rows.isEmpty()) throw ApiException.notFound("Saved synthetic job " + id + " not found");
        Map<String, Object> row = querySavedJob(id, false);
        ensureCanSee((Long) row.get("ownerUserId"), (String) row.get("ownerUsername"));
        return fromJsonPlan(rows.get(0));
    }

    private void ensureCanSee(Long ownerUserId, String ownerUsername) {
        AccessPrincipal p = requirePrincipal();
        if (p.hasPermission("admin.all")) return;
        if (ownerUserId != null && Objects.equals(ownerUserId, p.userId())) return;
        if (ownerUsername != null && ownerUsername.equalsIgnoreCase(p.username())) return;
        throw new ApiException(HttpStatus.FORBIDDEN, "Synthetic job belongs to another user");
    }

    private AccessPrincipal requirePrincipal() {
        return AccessContext.current()
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Login required"));
    }

    static String cleanSavedJobName(String name) {
        if (name == null || name.isBlank()) throw ApiException.bad("Saved job name is required");
        String clean = name.trim();
        if (clean.length() < MIN_SAVED_JOB_NAME_LENGTH) {
            throw ApiException.bad("Saved job name must be at least " + MIN_SAVED_JOB_NAME_LENGTH + " characters");
        }
        if (clean.length() > 200) throw ApiException.bad("Saved job name must be 200 characters or fewer");
        return clean;
    }

    private void validateSavedPlan(GenPlan plan) {
        if (plan == null) throw ApiException.bad("Saved job plan is required");
        if (safe(plan.tables()).isEmpty()) throw ApiException.bad("Saved job must include at least one table");
        String receiver = plan.receiver() == null ? "DB" : plan.receiver().trim().toUpperCase(Locale.ROOT);
        if ("DB".equals(receiver) && plan.targetDataSourceId() == null && !hasTargetSystems(plan)) {
            throw ApiException.bad("Database saved jobs need a target data source so they can run later");
        }
    }

    private void enforceControlledTargetGovernance(GenPlan plan, SavedJobRunContext savedJob) {
        String receiver = plan.receiver() == null ? "DB" : plan.receiver().trim().toUpperCase(Locale.ROOT);
        if ("DB".equals(receiver) && hasTargetSystems(plan)) {
            boolean controlled = false;
            for (TargetSystem target : targetSystems(plan)) {
                if (controlledTarget(lockPlan(plan, target))) {
                    controlled = true;
                    break;
                }
            }
            if (!controlled) return;
            if (savedJob == null) {
                throw ApiException.bad("Controlled database targets require a saved job with approval. Save the design, request approval, then run the approved job.");
            }
            if (!"APPROVED".equalsIgnoreCase(blankOr(savedJob.approvalStatus(), ""))) {
                throw ApiException.bad("Controlled database target requires an approved saved job.");
            }
            return;
        }
        if (!"DB".equals(receiver) || plan.targetDataSourceId() == null || !controlledTarget(plan)) return;
        if (savedJob == null) {
            throw ApiException.bad("Controlled database targets require a saved job with approval. Save the design, request approval, then run the approved job.");
        }
        if (!"APPROVED".equalsIgnoreCase(blankOr(savedJob.approvalStatus(), ""))) {
            throw ApiException.bad("Controlled database target requires an approved saved job.");
        }
    }

    private boolean controlledTarget(GenPlan plan) {
        try {
            DataSourceEntity ds = dataSources.get(plan.targetDataSourceId());
            String env = ds.getEnvironment() == null ? "" : ds.getEnvironment().trim().toUpperCase(Locale.ROOT);
            String tags = ds.getTags() == null ? "" : ds.getTags().trim().toLowerCase(Locale.ROOT);
            return Set.of("PROD", "PRODUCTION", "CONTROLLED", "REGULATED").contains(env)
                    || tags.contains("controlled") || tags.contains("regulated") || tags.contains("banking");
        } catch (Exception e) {
            return false;
        }
    }

    private Map<String, Object> safePlanSummary(GenPlan plan) {
        try {
            return planSummary(plan);
        } catch (Exception e) {
            return Map.of("summaryWarning", msgOf(e));
        }
    }

    private SavedJobRunContext savedJobRunContext(Map<String, Object> saved) {
        if (saved == null || saved.isEmpty()) return null;
        return new SavedJobRunContext(
                String.valueOf(saved.get("id")),
                String.valueOf(saved.get("name")),
                String.valueOf(saved.getOrDefault("approvalStatus", "DRAFT")),
                saved.get("approvedBy") == null ? null : String.valueOf(saved.get("approvedBy")),
                instantOrNull(saved.get("approvedAt")),
                saved.get("approvalNote") == null ? null : String.valueOf(saved.get("approvalNote")));
    }

    private Map<String, Object> approvalSnapshot(SavedJobRunContext savedJob) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("savedJobId", savedJob == null ? null : savedJob.id());
        out.put("savedJobName", savedJob == null ? null : savedJob.name());
        out.put("status", savedJob == null ? "DIRECT_RUN" : blankOr(savedJob.approvalStatus(), "DRAFT"));
        if (savedJob != null && savedJob.approvedBy() != null) out.put("approvedBy", savedJob.approvedBy());
        if (savedJob != null && savedJob.approvedAt() != null) out.put("approvedAt", savedJob.approvedAt().toString());
        if (savedJob != null && savedJob.approvalNote() != null) out.put("note", savedJob.approvalNote());
        return out;
    }

    private Map<String, Object> lineageSnapshot(SyntheticJob job, GenPlan plan, SavedJobRunContext savedJob,
                                                ConstraintCapture constraints, Map<String, Object> summary) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("jobId", job.id);
        out.put("savedJobId", savedJob == null ? null : savedJob.id());
        out.put("savedJobName", savedJob == null ? null : savedJob.name());
        out.put("ownerUsername", job.ownerUsername);
        out.put("dataset", job.dataset);
        out.put("receiver", job.receiver);
        out.put("targetDataSourceId", plan.targetDataSourceId());
        out.put("targetSchema", plan.targetSchema());
        if (hasTargetSystems(plan)) {
            out.put("targetSystems", targetSystems(plan).stream().map(target -> {
                LinkedHashMap<String, Object> t = new LinkedHashMap<>();
                t.put("name", targetDisplayName(target));
                t.put("targetDataSourceId", target.targetDataSourceId());
                t.put("targetSchema", target.targetSchema());
                t.put("tables", safe(target.tables()).stream().map(table -> {
                    LinkedHashMap<String, Object> m = new LinkedHashMap<>();
                    m.put("logicalTable", table.logicalTable());
                    m.put("physicalTable", table.physicalTable());
                    m.put("columnCount", safe(table.columns()).size());
                    return m;
                }).toList());
                return t;
            }).toList());
        }
        out.put("tableCount", job.tableCount);
        out.put("plannedRows", job.plannedRows);
        out.put("seed", plan.seed() == null ? 42L : plan.seed());
        out.put("planHash", job.planHash);
        out.put("approval", approvalSnapshot(savedJob));
        out.put("constraints", constraintEvidence(constraints));
        out.put("planSummary", summary);
        out.put("dataPath", syntheticDataPathSummary());
        out.put("governance", governancePolicy());
        out.put("retention", lineageRetentionPolicy(job.startedAt));
        out.put("createdAt", job.startedAt.toString());
        return out;
    }

    /**
     * Resolve @list-name generator params against the central Value Lists registry at run time, so
     * shared reference domains can be edited once and reused across many saved plans.
     */
    private GenPlan resolveValueLists(GenPlan plan) {
        if (plan == null || plan.tables() == null) return plan;
        boolean any = plan.tables().stream().anyMatch(t -> safe(t.columns()).stream()
                .anyMatch(c -> c != null && c.param1() != null && c.param1().trim().startsWith("@")));
        if (!any) return plan;
        if (valueLists == null) {
            throw ApiException.bad("Value list references (@name) are not available in this runtime");
        }
        List<GenTable> tables = new ArrayList<>();
        for (GenTable t : plan.tables()) {
            List<GenColumn> cols = new ArrayList<>();
            for (GenColumn c : safe(t.columns())) {
                String p1 = c.param1();
                if (p1 != null && p1.trim().startsWith("@")) {
                    String resolved = valueLists.resolveForGenerator(p1.trim().substring(1), c.generator());
                    cols.add(new GenColumn(c.name(), c.generator(), resolved, c.param2(), c.primaryKey(),
                            c.fkTable(), c.fkColumn(), c.sqlType(), c.fkMin(), c.fkMax()));
                } else {
                    cols.add(c);
                }
            }
            tables.add(new GenTable(t.name(), t.rowCount(), cols));
        }
        return new GenPlan(plan.dataset(), tables, plan.seed(), plan.receiver(),
                plan.targetDataSourceId(), plan.targetSchema(), plan.createTable(), plan.dropTable(), plan.prepMode(),
                plan.loadAction(), plan.targetPrep(), plan.keyColumns(), plan.batchSize(),
                plan.commitEveryRows(), plan.continueOnError(), plan.maxRejects(), plan.fastLoad(),
                plan.executionMode(), plan.partitionCount(), plan.partitionSize(), plan.targetSystems());
    }

    private Map<String, Object> constraintEvidence(ConstraintCapture capture) {
        ConstraintCapture c = capture == null ? ConstraintCapture.empty() : capture;
        Set<String> enforcedKeys = c.rules().stream()
                .map(r -> constraintKey(r.table(), r.constraintName()))
                .collect(java.util.stream.Collectors.toSet());
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("captured", c.raw().stream().map(r -> {
            LinkedHashMap<String, Object> m = new LinkedHashMap<>();
            m.put("table", r.table());
            m.put("constraintName", r.constraintName());
            m.put("expression", r.expression());
            m.put("dialect", r.dialect());
            m.put("captureSource", r.captureSource());
            boolean enforced = enforcedKeys.contains(constraintKey(r.table(), r.constraintName()));
            m.put("enforcedByGenerator", enforced);
            if (!enforced) {
                m.put("handling", "captured_for_evidence_db_validation_on_insert");
            }
            return m;
        }).toList());
        out.put("enforced", c.rules().stream().map(SyntheticConstraintRules.Rule::evidence).toList());
        if (c.warning() != null) out.put("warning", c.warning());
        return out;
    }

    private Map<String, Object> constraintCaptureSummary(String targetKind, ConstraintCapture capture) {
        ConstraintCapture c = capture == null ? ConstraintCapture.empty() : capture;
        SqlDialect dialect = dialectFromTargetKind(targetKind);
        List<String> notes = new ArrayList<>();
        notes.add("Generator enforcement is intentionally limited to safe single-column IN, BETWEEN, and numeric bound checks.");
        notes.add("Composite, cross-column, function, regex, and OR-based CHECKs are captured as evidence and left to database validation.");
        if (dialect == SqlDialect.MYSQL) {
            notes.add("MySQL exposes and enforces CHECK constraints only on modern 8.0.16+ compatible engines; older versions may return none.");
        }
        if (dialect == SqlDialect.ORACLE) {
            notes.add("Oracle CHECK metadata is captured from ALL_CONSTRAINTS.SEARCH_CONDITION; NOT NULL constraints may appear as evidence-only checks.");
        }
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("dialect", dialect.name());
        out.put("metadataSource", constraintMetadataSource(dialect));
        out.put("captured", c.raw().size());
        out.put("enforcedByGenerator", c.rules().size());
        out.put("unsupportedHandling", "CAPTURED_FOR_EVIDENCE_DB_VALIDATION_ON_INSERT");
        out.put("notes", notes);
        return out;
    }

    private Map<String, Object> parentSamplingPolicy(boolean streaming, List<GenTable> ordered) {
        long relationshipTables = safe(ordered).stream()
                .filter(t -> safe(t.columns()).stream().anyMatch(c -> notBlank(c.fkTable()) && notBlank(c.fkColumn())
                        || "LOOKUP".equalsIgnoreCase(blankOr(c.generator(), ""))))
                .count();
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("mode", streaming ? "EXACT_UNTIL_CAP_THEN_RESERVOIR" : "EXACT_IN_MEMORY");
        out.put("relationshipTableCount", relationshipTables);
        out.put("capPerParentIndex", streaming ? MAX_STREAMING_FK_POOL_VALUES : "not capped");
        out.put("appliesTo", List.of("single-column FK pools", "composite FK tuple pools", "LOOKUP parent-row indexes"));
        out.put("fkValidity", "Child values are drawn only from retained/generated parent keys or tuples.");
        out.put("distribution", streaming
                ? "Exact up to the cap; beyond that the linkage distribution is reservoir-sampled and approximate."
                : "Exact for the generated rows kept in memory.");
        return out;
    }

    private Map<String, Object> bulkLoadCapability(String receiver, LoadPlan load, boolean streaming, String targetKind) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        String currentMode = writeMode(receiver, load, streaming, targetKind);
        out.put("currentMode", currentMode);
        if (!"DB".equals(receiver)) {
            out.put("status", "FILE_RECEIVER_NOT_DB_LOAD");
            return out;
        }
        SqlDialect dialect = dialectFromTargetKind(targetKind);
        String nativeLoader = nativeBulkLoader(dialect);
        out.put("dialect", dialect.name());
        out.put("nativeLoader", nativeLoader == null ? "N/A" : nativeLoader);
        out.put("portableFallback", "JDBC batch or portable multi-row INSERT");
        if (load == null || load.truncateOnly()) {
            out.put("status", "NO_ROW_LOAD");
        } else if ("POSTGRES_COPY_FAST_LOAD".equals(currentMode)) {
            out.put("status", "NATIVE_LOADER_ACTIVE");
        } else if (nativeLoader != null && dialect != SqlDialect.POSTGRES) {
            out.put("status", "NATIVE_LOADER_EXTENSION_POINT_NOT_ACTIVE");
            out.put("note", "Vendor-native bulk APIs are tracked explicitly; current execution stays on the portable JDBC path.");
        } else {
            out.put("status", "PORTABLE_JDBC_PATH");
        }
        return out;
    }

    private Map<String, Object> syntheticDataPathSummary() {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("mode", "SYNTHETIC_FROM_SCRATCH");
        out.put("formatPreservingMaskingInThisPath", false);
        out.put("maskedRealSubsetPath", "Use DataScope/provisioning flows for subset-from-source plus masking policies.");
        out.put("note", "Synthetic generation does not read production rows; masked real subsets are a separate TDM path.");
        return out;
    }

    private Map<String, Object> governancePolicy() {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("rbac", "Endpoint permissions separate synthetic.manage, synthetic.run, synthetic.direct.run, synthetic.approve, cancel, and export.");
        out.put("makerChecker", "Database saved jobs require approval by a different user before run.");
        out.put("eSignature", "Approval and rejection require a reason/note recorded with the saved job, audit log, and run lineage.");
        out.put("lineageStore", "synthetic_generation_lineage");
        out.put("lineageRetentionDays", lineageRetentionDays);
        out.put("cleanupScope", "In-memory monitor entries are trimmed after completion; persisted lineage remains for the retention policy.");
        return out;
    }

    private Map<String, Object> lineageRetentionPolicy(Instant startedAt) {
        Instant base = startedAt == null ? Instant.now() : startedAt;
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("retentionDays", lineageRetentionDays);
        out.put("retainUntil", base.plus(Duration.ofDays(lineageRetentionDays)).toString());
        out.put("policy", "Do not purge persisted synthetic_generation_lineage before retainUntil.");
        return out;
    }

    private static String constraintKey(String table, String constraintName) {
        return (String.valueOf(table).toLowerCase(Locale.ROOT) + "|" +
                String.valueOf(constraintName).toLowerCase(Locale.ROOT));
    }

    private static String constraintMetadataSource(SqlDialect dialect) {
        return switch (dialect) {
            case ORACLE -> "ALL_CONSTRAINTS.SEARCH_CONDITION";
            case SQLSERVER -> "sys.check_constraints.definition";
            case DB2 -> "SYSCAT.CHECKS.TEXT";
            default -> "information_schema.check_constraints.check_clause";
        };
    }

    private static String nativeBulkLoader(SqlDialect dialect) {
        return switch (dialect) {
            case POSTGRES -> "PostgreSQL COPY";
            case ORACLE -> "Oracle direct-path / SQL*Loader";
            case SQLSERVER -> "SQLServerBulkCopy";
            case DB2 -> "DB2 LOAD";
            case MYSQL -> "LOAD DATA INFILE";
            default -> null;
        };
    }

    private static SqlDialect dialectFromTargetKind(String targetKind) {
        String k = targetKind == null ? "" : targetKind.trim().toUpperCase(Locale.ROOT);
        return switch (k) {
            case "POSTGRES", "POSTGRESQL" -> SqlDialect.POSTGRES;
            case "H2" -> SqlDialect.H2;
            case "MYSQL", "MARIADB" -> SqlDialect.MYSQL;
            case "DB2", "DB2UDB", "DB2_UDB", "DB2LUW", "DB2ZOS" -> SqlDialect.DB2;
            case "TERADATA", "VANTAGE" -> SqlDialect.TERADATA;
            case "ORACLE" -> SqlDialect.ORACLE;
            case "SQLSERVER", "SQL_SERVER", "MSSQL" -> SqlDialect.SQLSERVER;
            default -> SqlDialect.GENERIC;
        };
    }

    private Map<String, Object> withBankingReadiness(Map<String, Object> result, GenPlan plan) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        if (result != null) out.putAll(result);
        out.put("bankingReadiness", SyntheticBankingReadiness.evaluate(plan));
        return out;
    }

    private void insertGenerationLineage(SyntheticJob job, GenPlan plan, SavedJobRunContext savedJob,
                                         ConstraintCapture constraints, Map<String, Object> summary) {
        try {
            jdbc.update("INSERT INTO synthetic_generation_lineage(" +
                            "job_id, saved_job_id, saved_job_name, owner_username, plan_hash, dataset, receiver, " +
                            "target_data_source_id, target_schema, row_count, table_count, seed_value, approval_status, " +
                            "approved_by, approved_at, constraint_snapshot_json, plan_summary_json, created_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    job.id,
                    savedJob == null ? null : savedJob.id(),
                    savedJob == null ? null : savedJob.name(),
                    job.ownerUsername,
                    job.planHash,
                    job.dataset,
                    job.receiver,
                    plan.targetDataSourceId(),
                    plan.targetSchema(),
                    job.plannedRows,
                    job.tableCount,
                    plan.seed() == null ? 42L : plan.seed(),
                    savedJob == null ? "DIRECT_RUN" : blankOr(savedJob.approvalStatus(), "DRAFT"),
                    savedJob == null ? null : savedJob.approvedBy(),
                    savedJob == null ? null : tsOrNull(savedJob.approvedAt()),
                    job.constraintSnapshotJson,
                    toJson(summary),
                    ts(job.startedAt));
        } catch (Exception e) {
            audit.log("system", "SYNTHETIC_LINEAGE_WRITE_FAILED", "job=" + job.id + " error=" + msgOf(e));
        }
    }

    private ConstraintCapture captureConstraintRules(GenPlan plan) {
        if (plan == null) return ConstraintCapture.empty();
        String receiver = plan.receiver() == null ? "DB" : plan.receiver().trim().toUpperCase(Locale.ROOT);
        if (!"DB".equals(receiver) || plan.targetDataSourceId() == null || safe(plan.tables()).isEmpty()) {
            return ConstraintCapture.empty();
        }
        List<RawConstraint> raw = new ArrayList<>();
        List<SyntheticConstraintRules.Rule> rules = new ArrayList<>();
        try (Connection c = connections.open(dataSources.get(plan.targetDataSourceId()))) {
            String schema = DataSourceService.normalizeSchema(c, plan.targetSchema());
            for (GenTable table : safe(plan.tables())) {
                List<RawConstraint> tableRaw = readCheckConstraints(c, schema, table.name());
                raw.addAll(tableRaw);
                for (RawConstraint r : tableRaw) {
                    rules.addAll(SyntheticConstraintRules.parseAll(r.table(), r.constraintName(), r.expression()));
                }
            }
            return new ConstraintCapture(raw, rules, null);
        } catch (Exception e) {
            return new ConstraintCapture(raw, rules, "Could not capture target CHECK constraints: " + msgOf(e));
        }
    }

    private List<RawConstraint> readCheckConstraints(Connection c, String schema, String table) {
        SqlDialect dialect = SqlDialect.fromConnection(c);
        return switch (dialect) {
            case ORACLE -> queryCheckConstraints(c, dialect, "ALL_CONSTRAINTS.SEARCH_CONDITION", """
                    SELECT table_name, constraint_name, search_condition
                    FROM all_constraints
                    WHERE owner = UPPER(?)
                      AND table_name = UPPER(?)
                      AND constraint_type = 'C'
                    ORDER BY constraint_name
                    """, schema, table);
            case SQLSERVER -> queryCheckConstraints(c, dialect, "sys.check_constraints.definition", """
                    SELECT t.name, cc.name, cc.definition
                    FROM sys.check_constraints cc
                    JOIN sys.tables t ON cc.parent_object_id = t.object_id
                    JOIN sys.schemas s ON t.schema_id = s.schema_id
                    WHERE LOWER(s.name) = LOWER(?)
                      AND LOWER(t.name) = LOWER(?)
                    ORDER BY cc.name
                    """, schema, table);
            case DB2 -> {
                List<RawConstraint> db2 = queryCheckConstraints(c, dialect, "SYSCAT.CHECKS.TEXT", """
                        SELECT tabname, constname, text
                        FROM syscat.checks
                        WHERE LOWER(tabschema) = LOWER(?)
                          AND LOWER(tabname) = LOWER(?)
                        ORDER BY constname
                        """, schema, table);
                yield db2.isEmpty() ? queryInformationSchemaChecks(c, dialect, schema, table) : db2;
            }
            default -> queryInformationSchemaChecks(c, dialect, schema, table);
        };
    }

    private List<RawConstraint> queryInformationSchemaChecks(Connection c, SqlDialect dialect, String schema, String table) {
        String sql = """
                SELECT tc.table_name, cc.constraint_name, cc.check_clause
                FROM information_schema.table_constraints tc
                JOIN information_schema.check_constraints cc
                  ON tc.constraint_name = cc.constraint_name
                 AND tc.constraint_schema = cc.constraint_schema
                WHERE UPPER(tc.constraint_type) = 'CHECK'
                  AND LOWER(tc.table_schema) = LOWER(?)
                  AND LOWER(tc.table_name) = LOWER(?)
                ORDER BY cc.constraint_name
                """;
        return queryCheckConstraints(c, dialect, "information_schema.check_constraints.check_clause", sql, schema, table);
    }

    private List<RawConstraint> queryCheckConstraints(Connection c, SqlDialect dialect, String source,
                                                      String sql, String schema, String table) {
        List<RawConstraint> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String expression = rs.getString(3);
                    if (expression != null && !expression.isBlank()) {
                        out.add(new RawConstraint(rs.getString(1), rs.getString(2), expression, dialect.name(), source));
                    }
                }
            }
        } catch (Exception ignore) {
            // Some engines expose CHECK metadata differently. We still keep generation running.
        }
        return out;
    }

    private Map<String, List<SyntheticConstraintRules.Rule>> rulesByTable(List<SyntheticConstraintRules.Rule> rules) {
        Map<String, List<SyntheticConstraintRules.Rule>> out = new HashMap<>();
        for (SyntheticConstraintRules.Rule rule : safe(rules)) {
            out.computeIfAbsent(rule.table().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(rule);
        }
        return out;
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(String.valueOf(value).hashCode());
        }
    }

    private String toJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Unable to serialize synthetic job", e); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJsonMap(String value) {
        try { return json.readValue(value, Map.class); }
        catch (Exception e) { return Map.of("error", "Unable to read saved result: " + e.getMessage()); }
    }

    private GenPlan fromJsonPlan(String value) {
        try { return json.readValue(value, GenPlan.class); }
        catch (Exception e) { throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to read saved synthetic job plan"); }
    }

    private void putIfNotBlank(Map<String, Object> out, String key, Object value) {
        if (value == null) return;
        if (value instanceof String s && s.isBlank()) return;
        out.put(key, value);
    }

    private String instantString(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }

    private Instant instantOrNull(Object value) {
        if (value == null) return null;
        try {
            if (value instanceof Instant instant) return instant;
            if (value instanceof Timestamp timestamp) return timestamp.toInstant();
            String s = String.valueOf(value);
            return s.isBlank() ? null : Instant.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private String safeColumn(ResultSet rs, String column) {
        try {
            String value = rs.getString(column);
            return value == null || value.isBlank() ? null : value;
        } catch (SQLException e) {
            return null;
        }
    }

    private Timestamp safeTimestamp(ResultSet rs, String column) {
        try { return rs.getTimestamp(column); }
        catch (SQLException e) { return null; }
    }

    private Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    private Timestamp tsOrNull(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static long requestedRows(GenPlan plan) {
        long total = 0;
        for (GenTable t : safe(plan.tables())) {
            long rows = t.rowCount() == null ? 100L : t.rowCount();
            total += Math.max(0, rows);
        }
        return total;
    }

    private Map<String, List<LinkedHashMap<String, String>>> generateData(List<GenTable> ordered, long seed, long maxRows,
            Set<String> referenced, Map<String, List<String>> existingFk, Map<String, List<CompositeFk>> composite,
            Map<String, Set<String>> uniqueCols, Map<String, List<SyntheticConstraintRules.Rule>> constraintRules,
            ProgressSink progress, int startPct, int endPct) {
        Map<String, List<LinkedHashMap<String, String>>> data = new LinkedHashMap<>();
        Map<String, List<String>> pools = new HashMap<>();        // "table.col" -> values (existing + generated)
        Map<String, List<String[]>> tuplePools = new HashMap<>(); // composite-FK parent tuples (parentTable#cols)
        Map<String, Map<String, LinkedHashMap<String, String>>> rowIndex = new HashMap<>(); // parent rows by key, for LOOKUP
        if (existingFk != null) existingFk.forEach((k, v) -> pools.computeIfAbsent(k, x -> new ArrayList<>()).addAll(v));
        if (composite == null) composite = Map.of();
        long totalRows = 0;
        for (GenTable t : ordered)
            totalRows += Math.max(0, Math.min(t.rowCount() == null ? 100 : t.rowCount(), maxRows));
        GenerationProgress genProgress = new GenerationProgress(totalRows, progress, startPct, endPct);

        int tIndex = 0;
        for (GenTable t : ordered) {
            long rows = Math.max(0, Math.min(t.rowCount() == null ? 100 : t.rowCount(), maxRows));
            Random rng = new Random(seed * 1000003L + (tIndex++));
            progress.update(percent(startPct, endPct, genProgress.done(), Math.max(1, totalRows)),
                    "Generate rows", "Generating " + rows + " rows for " + t.name());
            data.put(t.name().toLowerCase(Locale.ROOT), genTableRows(t, rows, rng, pools, referenced, composite, tuplePools, rowIndex,
                    uniqueCols == null ? Set.of() : uniqueCols.getOrDefault(t.name().toLowerCase(Locale.ROOT), Set.of()),
                    constraintRules == null ? List.of() : constraintRules.getOrDefault(t.name().toLowerCase(Locale.ROOT), List.of()),
                    genProgress));
        }
        progress.update(endPct, "Generate rows", "Generated " + totalRows + " rows");
        return data;
    }

    private List<Map<String, Object>> generatedSummary(List<GenTable> ordered, Map<String, List<LinkedHashMap<String, String>>> data) {
        List<Map<String, Object>> summary = new ArrayList<>();
        for (GenTable t : ordered)
            summary.add(Map.of("name", t.name(), "rows", (long) data.get(t.name().toLowerCase(Locale.ROOT)).size()));
        return summary;
    }

    private List<Map<String, Object>> plannedSummary(List<GenTable> ordered, long rows) {
        List<Map<String, Object>> summary = new ArrayList<>();
        for (GenTable t : ordered) summary.add(Map.of("name", t.name(), "rows", rows));
        return summary;
    }

    private List<Map<String, Object>> plannedSummaryForPlan(List<GenTable> ordered, long maxRows) {
        List<Map<String, Object>> summary = new ArrayList<>();
        for (GenTable t : ordered) summary.add(Map.of("name", t.name(), "rows", rowCount(t, maxRows)));
        return summary;
    }

    private long totalRows(List<GenTable> tables, long maxRows) {
        long total = 0;
        for (GenTable t : safe(tables)) total += rowCount(t, maxRows);
        return total;
    }

    private long rowCount(GenTable t, long maxRows) {
        long requested = t.rowCount() == null ? 100L : t.rowCount();
        return Math.max(0, Math.min(requested, maxRows));
    }

    // ----------------------------------------------------------- per-table rows

    /** Generate one table's rows. FK columns draw from {@code pools}; referenced columns are captured. */
    private static final class GenerationProgress {
        private final long total;
        private final ProgressSink progress;
        private final int startPct;
        private final int endPct;
        private long done;

        GenerationProgress(long total, ProgressSink progress, int startPct, int endPct) {
            this.total = Math.max(1, total);
            this.progress = progress == null ? NO_PROGRESS : progress;
            this.startPct = startPct;
            this.endPct = endPct;
        }

        long done() { return done; }

        void add(long rows, String table) {
            if (rows <= 0) return;
            done += rows;
            progress.update(percent(startPct, endPct, done, total),
                    "Generate rows", "Generated " + done + " of " + total + " rows" + (table == null ? "" : " (" + table + ")"));
        }
    }

    private List<LinkedHashMap<String, String>> genTableRows(GenTable t, long rows, Random rng,
            Map<String, List<String>> pools, Set<String> referenced,
            Map<String, List<CompositeFk>> composite, Map<String, List<String[]>> tuplePools,
            Map<String, Map<String, LinkedHashMap<String, String>>> rowIndex,
            Set<String> uniqueCols, List<SyntheticConstraintRules.Rule> constraintRules,
            GenerationProgress progress) {
        if (uniqueCols == null) uniqueCols = Set.of();
        if (rowIndex == null) rowIndex = new HashMap<>();
        List<GenColumn> cols = safe(t.columns());
        boolean hasApi = false;
        for (GenColumn c : cols) if (isApi(c)) hasApi = true;
        if (hasApi && rows > 5000)
            throw ApiException.bad("Table '" + t.name() + "' uses an API generator (one HTTP call per row); "
                    + "cap API-backed tables at 5000 rows, or use a batch endpoint.");

        // composite (multi-column) FK handling — these collapse to one tuple drawn per row. Empty for the
        // common single-column-FK case, so the path below is byte-for-byte the old behavior when unused.
        if (composite == null) composite = Map.of();
        if (tuplePools == null) tuplePools = new HashMap<>();
        List<CompositeFk> myGroups = composite.getOrDefault(t.name().toLowerCase(Locale.ROOT), List.of());
        Set<String> compositeCols = new HashSet<>();
        for (CompositeFk g : myGroups) for (String cc : g.childCols()) compositeCols.add(cc.toLowerCase(Locale.ROOT));
        List<CompositeFk> asParent = new ArrayList<>();   // groups (anywhere) whose parent is THIS table → capture tuples
        for (List<CompositeFk> gs : composite.values())
            for (CompositeFk g : gs) if (g.parentTable().equalsIgnoreCase(t.name())) asParent.add(g);

        // precompile non-FK, non-API, non-derived generators once per column (API + derived resolved live)
        Map<String, BiFunction<Long, Random, String>> gens = new HashMap<>();
        for (GenColumn c : cols)
            if (!notBlank(c.fkTable()) && !isApi(c) && !isDerived(c))
                gens.put(c.name(), Generators.of(blankOr(c.generator(), "SEQUENCE"), blankNull(c.param1()), blankNull(c.param2())));

        List<GenColumn> derived = cols.stream().filter(SyntheticGenService::isDerived).toList();
        UniqueGuards uniqueSets = new UniqueGuards(false, rows);   // exact uniqueness within this (bounded) table
        Map<String, CardinalityCursor> cardCursors = new HashMap<>(); // FK col -> bounded parent allocator

        List<LinkedHashMap<String, String>> tableRows = new ArrayList<>();
        String tl = t.name().toLowerCase(Locale.ROOT);
        long reported = 0;
        for (long i = 1; i <= rows; i++) {
            LinkedHashMap<String, String> row = new LinkedHashMap<>();
            // 1) base columns (FK draw / API / generator) with primary-key uniqueness; composite-FK cols filled in step 1b
            for (GenColumn c : cols) {
                if (isDerived(c) || compositeCols.contains(c.name().toLowerCase(Locale.ROOT))) { row.put(c.name(), null); continue; }
                String val;
                if (notBlank(c.fkTable()) && notBlank(c.fkColumn())) {
                    List<String> pool = pools.get(key(c.fkTable(), c.fkColumn()));
                    if (hasCardinality(c) && pool != null && !pool.isEmpty()) {
                        String fkKey = key(c.fkTable(), c.fkColumn());
                        val = cardCursors.computeIfAbsent(c.name(),
                                k -> new CardinalityCursor(pool, rows, c, rng, t.name(), fkKey)).next(rng);
                    } else {
                        val = (pool == null || pool.isEmpty()) ? null : pool.get(rng.nextInt(pool.size()));
                    }
                } else if (isApi(c)) {
                    val = apiValue(blankNull(c.param1()), blankNull(c.param2()), i);
                } else {
                    BiFunction<Long, Random, String> g = gens.get(c.name());
                    val = g.apply(i, rng);
                    if (mustBeUnique(c, uniqueCols) && !isInherentlyUnique(c))
                        val = ensureUnique(uniqueSets.get(c.name()), val, g, i, rng);
                }
                row.put(c.name(), val);
            }
            // 1b) composite FK: draw ONE parent tuple per group and assign all its child columns together
            for (CompositeFk g : myGroups) {
                List<String[]> tuples = tuplePools.get(g.tupleKey());
                String[] tuple = (tuples == null || tuples.isEmpty()) ? null : tuples.get(rng.nextInt(tuples.size()));
                for (int ci = 0; ci < g.childCols().size(); ci++)
                    row.put(g.childCols().get(ci), tuple == null ? null : tuple[ci]);
            }
            // 2) derived columns reference already-generated columns (two passes so they can chain)
            for (int pass = 0; pass < 2 && !derived.isEmpty(); pass++)
                for (GenColumn c : derived) row.put(c.name(), derivedValue(c, row, rng, cols, rowIndex));
            // 2b) enforce single-column UNIQUE on derived columns too (e.g. an email built from a non-unique name)
            for (GenColumn c : derived)
                if (mustBeUnique(c, uniqueCols)) row.put(c.name(), ensureUniqueValue(uniqueSets.get(c.name()), row.get(c.name()), i));
            SyntheticConstraintRules.apply(row, constraintRules, i);
            // 3) capture referenced values into FK pools, and index the whole row by its key for cross-table LOOKUP
            for (GenColumn c : cols) {
                String k = tl + "." + c.name().toLowerCase(Locale.ROOT);
                if (referenced.contains(k)) {
                    String kv = row.get(c.name());
                    pools.computeIfAbsent(k, x -> new ArrayList<>()).add(kv);
                    if (kv != null) rowIndex.computeIfAbsent(k, x -> new HashMap<>()).putIfAbsent(kv, row);
                }
            }
            // 3b) when THIS table is a composite parent, capture its key tuples for children to draw
            for (CompositeFk g : asParent) {
                String[] tuple = new String[g.parentCols().size()];
                for (int ci = 0; ci < g.parentCols().size(); ci++) tuple[ci] = row.get(matchKey(row, g.parentCols().get(ci)));
                tuplePools.computeIfAbsent(g.tupleKey(), x -> new ArrayList<>()).add(tuple);
            }
            tableRows.add(row);
            if (progress != null && (i - reported >= 500 || i == rows)) {
                progress.add(i - reported, t.name());
                reported = i;
            }
        }
        return tableRows;
    }

    // ---- row-aware derived generators + uniqueness (within-row consistency) ----

    private static boolean isDerived(GenColumn c) {
        String g = c.generator() == null ? "" : c.generator().trim().toUpperCase(Locale.ROOT);
        return g.equals("TEMPLATE") || g.equals("COPY") || g.equals("CASE") || g.equals("DATE_AFTER") || g.equals("LOOKUP");
    }

    /** A foreign key with a children-per-parent range set (cardinality control). */
    private static boolean hasCardinality(GenColumn c) {
        return (c.fkMin() != null && c.fkMin() > 0) || (c.fkMax() != null && c.fkMax() > 0);
    }

    private static String derivedValue(GenColumn c, LinkedHashMap<String, String> row, Random rng,
                                       List<GenColumn> cols, Map<String, Map<String, LinkedHashMap<String, String>>> rowIndex) {
        String g = c.generator().trim().toUpperCase(Locale.ROOT);
        String p1 = blankNull(c.param1()), p2 = blankNull(c.param2());
        switch (g) {
            case "TEMPLATE": return applyTemplate(p1, row, rng);
            case "COPY":     return p1 == null ? null : row.get(matchKey(row, p1));
            case "CASE":     return mapCase(p1 == null ? null : row.get(matchKey(row, p1)), p2);
            case "DATE_AFTER": return dateAfter(p1 == null ? null : row.get(matchKey(row, p1)), p2, rng);
            case "LOOKUP":   return lookupValue(c, row, cols, rowIndex);
            default:         return null;
        }
    }

    /**
     * Cross-table lookup: follow this row's foreign key to the matching PARENT row and copy a parent column.
     * param1 = parent column to copy (optionally "col:mod", e.g. full_name:first); param2 = which FK column
     * to follow (optional — auto-detected when the table has exactly one FK). Keeps non-key attributes
     * (name, phone, address, …) identical across referentially-linked tables.
     */
    private static String lookupValue(GenColumn c, LinkedHashMap<String, String> row, List<GenColumn> cols,
                                      Map<String, Map<String, LinkedHashMap<String, String>>> rowIndex) {
        if (rowIndex == null) return null;
        String spec = blankNull(c.param1());
        if (spec == null) return null;
        String parentCol = spec, mod = null;
        int colon = spec.indexOf(':');
        if (colon >= 0) { parentCol = spec.substring(0, colon).trim(); mod = spec.substring(colon + 1).trim().toLowerCase(Locale.ROOT); }

        // pick the FK column to follow (param2 names it, else the single FK)
        GenColumn fk = resolveLookupFk(c, cols);
        if (fk == null) return null;

        String keyValue = row.get(matchKey(row, fk.name()));
        if (keyValue == null) return null;
        Map<String, LinkedHashMap<String, String>> idx = rowIndex.get(key(fk.fkTable(), fk.fkColumn()));
        LinkedHashMap<String, String> parent = idx == null ? null : idx.get(keyValue);
        if (parent == null) return null;
        return applyMod(parent.get(matchKey(parent, parentCol)), mod);
    }

    private static String matchKey(LinkedHashMap<String, String> row, String name) {
        if (name == null) return null;
        if (row.containsKey(name)) return name;
        for (String k : row.keySet()) if (k.equalsIgnoreCase(name)) return k;
        return name;
    }

    private static String applyTemplate(String tmpl, LinkedHashMap<String, String> row, Random rng) {
        if (tmpl == null) return null;
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < tmpl.length()) {
            int s = tmpl.indexOf("${", i);
            if (s < 0) { sb.append(tmpl.substring(i)); break; }
            sb.append(tmpl, i, s);
            int e = tmpl.indexOf('}', s + 2);
            if (e < 0) { sb.append(tmpl.substring(s)); break; }
            String token = tmpl.substring(s + 2, e).trim();
            String v;
            String lower = token.toLowerCase(Locale.ROOT);
            if (lower.startsWith("rand:") || lower.startsWith("pick:")) {
                // ${rand:a|b|c} → random choice (varied values without a fixed literal)
                String[] opts = token.substring(token.indexOf(':') + 1).split("\\|");
                v = opts.length == 0 ? "" : opts[rng.nextInt(opts.length)].trim();
            } else if (lower.equals("domain")) {
                // ${domain} → a realistic, varied email domain from the seeded list
                v = Generators.of("DOMAIN", null, null).apply(1L, rng);
            } else {
                // ${column} or ${column:modifier} (lower / upper / slug / nospace / initial)
                String col = token, mod = null;
                int colon = token.indexOf(':');
                if (colon >= 0) { col = token.substring(0, colon).trim(); mod = token.substring(colon + 1).trim().toLowerCase(Locale.ROOT); }
                v = applyMod(row.get(matchKey(row, col)), mod);
            }
            sb.append(v == null ? "" : v);
            i = e + 1;
        }
        return sb.toString();
    }

    private static String applyMod(String v, String mod) {
        if (v == null || mod == null) return v;
        switch (mod) {
            case "lower":   return v.toLowerCase(Locale.ROOT);
            case "upper":   return v.toUpperCase(Locale.ROOT);
            case "slug":    return v.toLowerCase(Locale.ROOT).trim().replaceAll("[^a-z0-9]+", ".").replaceAll("^\\.|\\.$", "");
            case "nospace": return v.replaceAll("\\s+", "");
            case "initial": return v.isBlank() ? v : v.trim().substring(0, 1).toUpperCase(Locale.ROOT);
            case "first": { String tt = v.trim(); int sp = tt.indexOf(' '); return sp < 0 ? tt : tt.substring(0, sp); }                 // first word
            case "rest":  { String tt = v.trim(); int sp = tt.indexOf(' '); return sp < 0 ? "" : tt.substring(sp + 1).trim(); }          // everything after the first word
            case "last":  { String tt = v.trim(); int sp = tt.lastIndexOf(' '); return sp < 0 ? tt : tt.substring(sp + 1); }             // last word
            default:        return v;
        }
    }

    private static String mapCase(String src, String spec) {
        if (spec == null) return src;
        String def = null;
        for (String part : spec.split("\\|")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            String key = part.substring(0, eq).trim(), val = part.substring(eq + 1);
            if (key.equals("*")) { def = val; continue; }
            if (key.equalsIgnoreCase(src == null ? "" : src.trim())) return val;
        }
        return def != null ? def : src;
    }

    private static String dateAfter(String base, String maxDaysStr, Random rng) {
        try {
            String b = base == null ? "" : base.trim();
            java.time.LocalDate d = b.isEmpty() ? java.time.LocalDate.now()
                    : java.time.LocalDate.parse(b.substring(0, Math.min(10, b.length())));
            int max = 30;
            try { if (maxDaysStr != null && !maxDaysStr.isBlank()) max = Integer.parseInt(maxDaysStr.trim()); } catch (Exception ignore) { }
            return d.plusDays(rng.nextInt(Math.max(1, max) + 1)).toString();
        } catch (Exception e) { return base; }
    }

    /** Keep a generated column unique within a table: retry the generator, then force-suffix the row number. */
    private static String ensureUnique(UniqueGuard guard, String val,
                                       BiFunction<Long, Random, String> gen, long i, Random rng) {
        if (val != null && guard.add(val)) return val;
        for (int a = 0; a < 25 && gen != null; a++) {
            String v = gen.apply(i, rng);
            if (v != null && guard.add(v)) return v;
        }
        String forced = (val == null ? "" : val) + "-" + i;   // row number is unique → forced value is unique
        guard.add(forced);
        return forced;
    }

    /** A column that must hold unique values: the declared primary key, or any single-column UNIQUE constraint/index. */
    private static boolean mustBeUnique(GenColumn c, Set<String> uniqueCols) {
        return Boolean.TRUE.equals(c.primaryKey())
                || (uniqueCols != null && uniqueCols.contains(c.name().toLowerCase(Locale.ROOT)));
    }

    /** Generators whose output is unique by construction — no need to track seen values (saves memory at scale). */
    private static boolean isInherentlyUnique(GenColumn c) {
        String g = c.generator() == null ? "" : c.generator().trim().toUpperCase(Locale.ROOT);
        return switch (g) {
            case "SEQUENCE", "PADDED_SEQUENCE", "UUID", "GUID", "ROW_NUMBER", "AUTOINCREMENT" -> true;
            default -> false;
        };
    }

    /**
     * Make an already-resolved value unique within its column (used for DERIVED unique columns, e.g. an email
     * built from a non-unique name that nonetheless has a UNIQUE constraint). Keeps the natural value when it
     * is already unique, otherwise disambiguates with the (unique) row number — preserving email shape.
     */
    private static String ensureUniqueValue(UniqueGuard guard, String val, long i) {
        if (val != null && guard.add(val)) return val;
        String cand = disambiguate(val, i);   // embeds the unique row number → unique by construction
        guard.add(cand);
        return cand;
    }

    /** Partition workers cannot share an in-memory uniqueness set, so global row numbers disambiguate values. */
    private static String globallyUniqueValue(GenColumn column, String value, long globalRow) {
        String type = blankOr(column.sqlType(), "VARCHAR").toUpperCase(Locale.ROOT);
        if (type.contains("INT") || type.contains("NUMERIC") || type.contains("DECIMAL")
                || type.contains("DOUBLE") || type.contains("FLOAT") || type.contains("REAL")) {
            return Long.toString(globalRow);
        }
        if (type.equals("DATE")) return java.time.LocalDate.of(2000, 1, 1).plusDays(globalRow - 1).toString();
        if (type.contains("TIMESTAMP") || type.contains("DATETIME")) {
            return java.time.LocalDateTime.of(2000, 1, 1, 0, 0).plusNanos(globalRow).toString();
        }
        if (type.contains("BOOL") || type.equals("BIT")) {
            if (globalRow > 2) throw ApiException.bad("Unique boolean column '" + column.name() + "' cannot hold " + globalRow + " rows");
            return globalRow == 1 ? "false" : "true";
        }
        return disambiguate(value, globalRow);
    }

    private static void validateCardinalityBounds(String table, String column, String fkKey,
                                                   long childRows, int parents, int min, int max) {
        if (parents <= 0) return;
        long minimum = parents * (long) min;
        long maximum = max == Integer.MAX_VALUE ? Long.MAX_VALUE : parents * (long) max;
        if (childRows < minimum || childRows > maximum) {
            throw ApiException.bad("Cardinality for " + table + "." + column + " -> " + fkKey
                    + " cannot allocate " + childRows + " rows across " + parents
                    + " parent keys (allowed " + minimum + " to "
                    + (maximum == Long.MAX_VALUE ? "unbounded" : maximum) + ").");
        }
    }

    /** Append the row number as a disambiguator; for emails insert it before '@' so the result stays a valid email. */
    private static String disambiguate(String val, long i) {
        String base = val == null ? "" : val;
        int at = base.indexOf('@');
        if (at > 0) return base.substring(0, at) + "+" + i + base.substring(at);   // john.doe+7@bank.test
        return base + "-" + i;
    }

    // ---- uniqueness guards: exact for the in-memory path, bounded (Bloom) for the streaming path ----

    private static final double BLOOM_FPP = 0.01;            // target false-positive rate
    private static final long MAX_BLOOM_BITS = 1L << 29;     // hard cap ≈ 64 MB per unique column

    /** Tracks emitted values for one column. {@code add} returns true only if the value is (probably) new. */
    private interface UniqueGuard { boolean add(String value); }

    /** Exact, in-heap set — used in-memory where row counts are bounded (< streaming threshold). */
    private static final class ExactGuard implements UniqueGuard {
        private final HashSet<String> set = new HashSet<>();
        public boolean add(String value) { return value != null && set.add(value); }
    }

    /**
     * Bounded Bloom-filter guard for the streaming path. Memory is fixed regardless of row count, so a
     * billion-row load can't OOM. It has NO false negatives (a real duplicate is always caught), only false
     * positives (a unique value is occasionally flagged → disambiguated with the unique row number, which is
     * harmless). At extreme scale the filter saturates and simply disambiguates more rows — never duplicates.
     */
    private static final class BloomGuard implements UniqueGuard {
        private final long[] words;
        private final int numBits;
        private final int numHashes;

        BloomGuard(long expectedItems, double fpp) {
            long n = Math.max(1024, expectedItems);
            double ln2 = Math.log(2);
            long m = (long) Math.ceil(-(n * Math.log(fpp)) / (ln2 * ln2));
            m = Math.max(1024, Math.min(m, MAX_BLOOM_BITS));
            this.numBits = (int) Math.min(m, (long) (Integer.MAX_VALUE - 64));
            this.words = new long[(numBits >>> 6) + 1];
            int k = (int) Math.round(((double) numBits / n) * ln2);
            this.numHashes = Math.max(1, Math.min(8, k));
        }

        public boolean add(String value) {
            if (value == null) return true;
            long h1 = fnv1a(value, 0x9E3779B97F4A7C15L);
            long h2 = fnv1a(value, 0xC2B2AE3D27D4EB4FL);
            boolean isNew = false;
            for (int i = 0; i < numHashes; i++) {
                int bit = (int) Math.floorMod(h1 + (long) i * h2, numBits);
                int word = bit >>> 6;
                long mask = 1L << (bit & 63);
                if ((words[word] & mask) == 0L) { words[word] |= mask; isNew = true; }
            }
            return isNew;   // some bit was unset → definitely not seen before
        }
    }

    private static long fnv1a(String s, long seed) {
        long h = 0xcbf29ce484222325L ^ seed;
        for (int i = 0, n = s.length(); i < n; i++) { h ^= s.charAt(i); h *= 0x100000001b3L; }
        return h;
    }

    /** Per-table map of column → guard. {@code bounded} selects Bloom (streaming) vs exact (in-memory). */
    private static final class UniqueGuards {
        private final Map<String, UniqueGuard> byCol = new HashMap<>();
        private final boolean bounded;
        private final long expectedItems;
        UniqueGuards(boolean bounded, long expectedItems) { this.bounded = bounded; this.expectedItems = expectedItems; }
        UniqueGuard get(String col) {
            return byCol.computeIfAbsent(col.toLowerCase(Locale.ROOT),
                    k -> bounded ? new BloomGuard(expectedItems, BLOOM_FPP) : new ExactGuard());
        }
    }

    /** Public single-table generation (no cross-table FK), reused by the mainframe file generator. */
    public List<LinkedHashMap<String, String>> generateRows(GenTable t, long rows, long seed) {
        return genTableRows(t, Math.max(0, rows), new Random(seed), new HashMap<>(), Set.of(), Map.of(), new HashMap<>(), new HashMap<>(), Set.of(), List.of(), null);
    }

    // --------------------------------------------------------- FK auto-detection

    /**
     * Read the target database's real foreign keys and fill in fkTable/fkColumn on any child column the
     * user left blank — but only when the referenced parent table is also part of this generation plan.
     * This guarantees referential integrity (children draw values from generated parent keys) and a correct
     * parent-first load order, without the user wiring every FK by hand. Best-effort: on any error or for
     * non-DB receivers, the plan is returned unchanged and the user's own FK fields still apply.
     */
    private GenPlan enrichForeignKeys(GenPlan plan) {
        String receiver = plan.receiver() == null ? "DB" : plan.receiver().trim().toUpperCase(Locale.ROOT);
        if (!"DB".equals(receiver) || plan.targetDataSourceId() == null) return plan;

        Set<String> inPlan = new HashSet<>();
        for (GenTable t : plan.tables()) inPlan.add(t.name().toLowerCase(Locale.ROOT));

        // childTable.childCol (lower) -> { parentTable, parentCol } (DB case preserved)
        Map<String, String[]> fk = new HashMap<>();
        try (Connection c = connections.open(dataSources.get(plan.targetDataSourceId()))) {
            String schema = DataSourceService.normalizeSchema(c, plan.targetSchema());
            for (GenTable t : plan.tables()) {
                try (ResultSet rs = c.getMetaData().getImportedKeys(null, schema, t.name())) {
                    while (rs.next()) {
                        String parentTable = rs.getString("PKTABLE_NAME");
                        String parentCol   = rs.getString("PKCOLUMN_NAME");
                        String childCol    = rs.getString("FKCOLUMN_NAME");
                        if (parentTable == null || parentCol == null || childCol == null) continue;
                        if (!inPlan.contains(parentTable.toLowerCase(Locale.ROOT))) continue; // parent must be generated too
                        fk.put(t.name().toLowerCase(Locale.ROOT) + "." + childCol.toLowerCase(Locale.ROOT),
                                new String[]{ parentTable, parentCol });
                    }
                } catch (SQLException ignore) { /* table may not exist yet, or no FKs */ }
            }
        } catch (Exception e) {
            return plan;   // best-effort
        }
        if (fk.isEmpty()) return plan;

        List<GenTable> tables = new ArrayList<>();
        for (GenTable t : plan.tables()) {
            List<GenColumn> cols = new ArrayList<>();
            for (GenColumn col : safe(t.columns())) {
                String[] ref = fk.get(t.name().toLowerCase(Locale.ROOT) + "." + col.name().toLowerCase(Locale.ROOT));
                if (ref != null && !notBlank(col.fkTable())) {
                    cols.add(new GenColumn(col.name(), col.generator(), col.param1(), col.param2(),
                            col.primaryKey(), ref[0], ref[1], col.sqlType(), col.fkMin(), col.fkMax()));
                } else {
                    cols.add(col);
                }
            }
            tables.add(new GenTable(t.name(), t.rowCount(), cols));
        }
        return new GenPlan(plan.dataset(), tables, plan.seed(), plan.receiver(),
                plan.targetDataSourceId(), plan.targetSchema(), plan.createTable(), plan.dropTable(), plan.prepMode(),
                plan.loadAction(), plan.targetPrep(), plan.keyColumns(), plan.batchSize(),
                plan.commitEveryRows(), plan.continueOnError(), plan.maxRejects(), plan.fastLoad(),
                plan.executionMode(), plan.partitionCount(), plan.partitionSize(), plan.targetSystems());
    }

    /**
     * Referential integrity to EXISTING data: load real parent-key values already in the target so a
     * generated child can reference rows that aren't (re)generated in this run (e.g. new transactions for
     * existing accounts). Skips parents that this run will clear/replace, and is best-effort (any failure
     * just leaves that pool to be filled by generated values). Capped to keep memory bounded.
     */
    private Map<String, List<String>> loadExistingFkValues(GenPlan plan, LoadPlan load, Set<String> referenced) {
        Map<String, List<String>> out = new HashMap<>();
        if (referenced == null || referenced.isEmpty() || plan.targetDataSourceId() == null) return out;
        Set<String> inPlan = new HashSet<>();
        for (GenTable t : safe(plan.tables())) inPlan.add(t.name().toLowerCase(Locale.ROOT));
        String prep = plan.prepMode() == null ? "" : plan.prepMode().trim().toUpperCase(Locale.ROOT);
        boolean drop = "DROP_RECREATE".equals(prep) || Boolean.TRUE.equals(plan.dropTable());
        boolean clears = drop || (load != null && load.clearsTarget());
        try (Connection c = connections.open(dataSources.get(plan.targetDataSourceId()))) {
            String schema = DataSourceService.normalizeSchema(c, plan.targetSchema());
            for (String refKey : referenced) {
                int dot = refKey.indexOf('.');
                if (dot <= 0) continue;
                String parentTable = refKey.substring(0, dot), col = refKey.substring(dot + 1);
                if (inPlan.contains(parentTable) && clears) continue;   // those existing rows get wiped this run
                String from = (schema == null || schema.isBlank()) ? parentTable : schema + "." + parentTable;
                try (Statement st = c.createStatement()) {
                    st.setMaxRows(MAX_STREAMING_FK_POOL_VALUES);
                    try (ResultSet rs = st.executeQuery("SELECT DISTINCT " + col + " FROM " + from)) {
                        List<String> vals = new ArrayList<>();
                        while (rs.next() && vals.size() < MAX_STREAMING_FK_POOL_VALUES) {
                            String v = rs.getString(1);
                            if (v != null) vals.add(v);
                        }
                        if (!vals.isEmpty()) out.put(refKey, vals);
                    }
                } catch (SQLException ignore) { /* parent may not exist yet — skip */ }
            }
        } catch (Exception e) { /* best-effort */ }
        return out;
    }

    /**
     * Detect MULTI-column foreign keys from the target DB, grouped by constraint (FK_NAME, ordered by
     * KEY_SEQ). Returns childTable → its composite FKs. Single-column FKs are handled the normal way and
     * are not returned here. Best-effort; empty for non-DB receivers so the common path is unchanged.
     */
    private Map<String, List<CompositeFk>> compositeFkGroups(GenPlan plan) {
        Map<String, List<CompositeFk>> out = new HashMap<>();
        String receiver = plan.receiver() == null ? "DB" : plan.receiver().trim().toUpperCase(Locale.ROOT);
        if (!"DB".equals(receiver) || plan.targetDataSourceId() == null) return out;
        Set<String> inPlan = new HashSet<>();
        for (GenTable t : safe(plan.tables())) inPlan.add(t.name().toLowerCase(Locale.ROOT));
        try (Connection c = connections.open(dataSources.get(plan.targetDataSourceId()))) {
            String schema = DataSourceService.normalizeSchema(c, plan.targetSchema());
            for (GenTable t : safe(plan.tables())) {
                Map<String, TreeMap<Integer, String[]>> byName = new LinkedHashMap<>();   // FK_NAME -> seq -> [parentTable,parentCol,childCol]
                try (ResultSet rs = c.getMetaData().getImportedKeys(null, schema, t.name())) {
                    while (rs.next()) {
                        String fkName = rs.getString("FK_NAME");
                        int seq = rs.getInt("KEY_SEQ");
                        String pt = rs.getString("PKTABLE_NAME"), pc = rs.getString("PKCOLUMN_NAME"), cc = rs.getString("FKCOLUMN_NAME");
                        if (fkName == null || pt == null || pc == null || cc == null) continue;
                        if (!inPlan.contains(pt.toLowerCase(Locale.ROOT))) continue;
                        byName.computeIfAbsent(fkName, k -> new TreeMap<>()).put(seq, new String[]{pt, pc, cc});
                    }
                } catch (SQLException ignore) { /* table may not exist yet */ }
                for (TreeMap<Integer, String[]> seqMap : byName.values()) {
                    if (seqMap.size() < 2) continue;   // composite keys only
                    List<String> childCols = new ArrayList<>(), parentCols = new ArrayList<>();
                    String parentTable = null;
                    for (String[] v : seqMap.values()) { parentTable = v[0]; parentCols.add(v[1]); childCols.add(v[2]); }
                    String tupleKey = parentTable.toLowerCase(Locale.ROOT) + "#"
                            + String.join(",", parentCols.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList());
                    out.computeIfAbsent(t.name().toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                            .add(new CompositeFk(childCols, parentTable, parentCols, tupleKey));
                }
            }
        } catch (Exception e) { return new HashMap<>(); }
        return out;
    }

    /**
     * Read the target's single-column UNIQUE constraints/indexes per table (lower-cased), so generation enforces
     * them — not just the primary key. Composite (multi-column) unique keys are skipped here: a value-level suffix
     * can't safely satisfy a tuple-uniqueness rule, so those are left to the load's reject handling.
     */
    private Map<String, Set<String>> uniqueColumnsByTable(GenPlan plan) {
        Map<String, Set<String>> out = new HashMap<>();
        String receiver = plan.receiver() == null ? "DB" : plan.receiver().trim().toUpperCase(Locale.ROOT);
        if (!"DB".equals(receiver) || plan.targetDataSourceId() == null) return out;
        try (Connection c = connections.open(dataSources.get(plan.targetDataSourceId()))) {
            String schema = DataSourceService.normalizeSchema(c, plan.targetSchema());
            for (GenTable t : safe(plan.tables())) {
                Set<String> uniq = singleColumnUniqueColumns(c, schema, t.name());
                if (!uniq.isEmpty()) out.put(t.name().toLowerCase(Locale.ROOT), uniq);
            }
        } catch (Exception e) { return new HashMap<>(); }
        return out;
    }

    private Set<String> singleColumnUniqueColumns(Connection c, String schema, String table) {
        Map<String, List<String>> byIndex = new LinkedHashMap<>();   // unique index name -> its columns
        try (ResultSet rs = c.getMetaData().getIndexInfo(null, schema, table, true, false)) {
            while (rs.next()) {
                String idx = rs.getString("INDEX_NAME");
                String col = rs.getString("COLUMN_NAME");
                if (idx == null || col == null) continue;
                byIndex.computeIfAbsent(idx, k -> new ArrayList<>()).add(col.toLowerCase(Locale.ROOT));
            }
        } catch (SQLException ignore) { /* table may not exist yet */ }
        Set<String> single = new HashSet<>();
        for (List<String> cols : byIndex.values()) if (cols.size() == 1) single.add(cols.get(0));
        return single;
    }

    // --------------------------------------------------------- FK-dependency order

    private List<GenTable> topoSort(List<GenTable> tables) {
        Map<String, Set<String>> deps = new LinkedHashMap<>();
        Set<String> known = new HashSet<>();
        for (GenTable t : tables) known.add(t.name().toLowerCase(Locale.ROOT));
        for (GenTable t : tables) {
            Set<String> d = new LinkedHashSet<>();
            String self = t.name().toLowerCase(Locale.ROOT);
            for (GenColumn c : safe(t.columns()))
                if (notBlank(c.fkTable())) {
                    String dep = c.fkTable().toLowerCase(Locale.ROOT);
                    if (known.contains(dep) && !dep.equals(self)) d.add(dep);
                }
            deps.put(self, d);
        }
        List<GenTable> out = new ArrayList<>();
        Set<String> done = new HashSet<>();
        boolean progress = true;
        while (out.size() < tables.size() && progress) {
            progress = false;
            for (GenTable t : tables) {
                String n = t.name().toLowerCase(Locale.ROOT);
                if (done.contains(n)) continue;
                if (done.containsAll(deps.get(n))) { out.add(t); done.add(n); progress = true; }
            }
        }
        // leftover (cycle) → append in declaration order; their FK pools may be empty (null FKs)
        for (GenTable t : tables) if (!done.contains(t.name().toLowerCase(Locale.ROOT))) out.add(t);
        return out;
    }

    // --------------------------------------------------------------- CSV receiver

    private List<Map<String, String>> csvFiles(List<GenTable> ordered, Map<String, List<LinkedHashMap<String, String>>> data) {
        List<Map<String, String>> files = new ArrayList<>();
        for (GenTable t : ordered) {
            List<GenColumn> cols = safe(t.columns());
            StringBuilder sb = new StringBuilder();
            sb.append(join(cols, c -> csv(c.name()))).append("\n");
            for (LinkedHashMap<String, String> row : data.get(t.name().toLowerCase(Locale.ROOT)))
                sb.append(join(cols, c -> csv(row.get(c.name())))).append("\n");
            files.add(Map.of("name", t.name() + ".csv", "content", sb.toString()));
        }
        return files;
    }

    private static String csv(String v) {
        if (v == null) return "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r"))
            return "\"" + v.replace("\"", "\"\"") + "\"";
        return v;
    }

    // -------------------------------------------------------------- JSON receiver

    private List<Map<String, String>> jsonFiles(List<GenTable> ordered, Map<String, List<LinkedHashMap<String, String>>> data) {
        List<Map<String, String>> files = new ArrayList<>();
        for (GenTable t : ordered) {
            List<GenColumn> cols = safe(t.columns());
            List<Map<String, Object>> rows = new ArrayList<>();
            for (LinkedHashMap<String, String> row : data.get(t.name().toLowerCase(Locale.ROOT))) {
                LinkedHashMap<String, Object> obj = new LinkedHashMap<>();
                for (GenColumn c : cols) obj.put(c.name(), jsonValue(row.get(c.name()), c.sqlType()));
                rows.add(obj);
            }
            try {
                files.add(Map.of("name", t.name() + ".json", "content",
                        json.writerWithDefaultPrettyPrinter().writeValueAsString(rows)));
            } catch (Exception e) {
                throw ApiException.bad("JSON serialization failed for " + t.name() + ": " + e.getMessage());
            }
        }
        return files;
    }

    private static Object jsonValue(String v, String sqlType) {
        if (v == null) return null;
        if (isNumeric(sqlType)) { try { return new BigDecimal(v); } catch (Exception e) { return v; } }
        if (isBoolean(sqlType)) return parseBool(v);
        return v;
    }

    // --------------------------------------------------------------- SQL receiver

    private List<Map<String, String>> sqlFiles(GenPlan plan, List<GenTable> ordered, Map<String, List<LinkedHashMap<String, String>>> data) {
        String dataset = blankOr(plan.dataset(), "synthetic");
        boolean drop = Boolean.TRUE.equals(plan.dropTable());
        boolean create = Boolean.TRUE.equals(plan.createTable()) || drop;  // "drop & recreate" implies create
        StringBuilder sb = new StringBuilder("-- ForgeTDM synthetic dataset: " + dataset + "\n\n");
        for (GenTable t : ordered) {
            List<GenColumn> cols = safe(t.columns());
            String qt = q(t.name());
            if (drop)   sb.append("DROP TABLE IF EXISTS ").append(qt).append(";\n");
            if (create) sb.append(createDdl(t.name(), null, cols)).append("\n");
            String colList = join(cols, c -> q(c.name()));
            for (LinkedHashMap<String, String> row : data.get(t.name().toLowerCase(Locale.ROOT))) {
                String vals = join(cols, c -> sqlLiteral(row.get(c.name()), c.sqlType()));
                sb.append("INSERT INTO ").append(qt).append(" (").append(colList).append(") VALUES (").append(vals).append(");\n");
            }
            sb.append("\n");
        }
        return List.of(Map.of("name", safeName(dataset) + ".sql", "content", sb.toString()));
    }

    private static String sqlLiteral(String v, String sqlType) {
        if (v == null) return "NULL";
        if (isNumeric(sqlType)) { try { new BigDecimal(v); return v; } catch (Exception e) { /* fall through to quoted */ } }
        if (isBoolean(sqlType)) return parseBool(v) ? "TRUE" : "FALSE";
        return "'" + v.replace("'", "''") + "'";
    }

    // ---------------------------------------------------------------- DB receiver

    private Map<String, Object> dbLoad(GenPlan plan, List<GenTable> ordered,
                                       Map<String, List<LinkedHashMap<String, String>>> data,
                                       List<Map<String, Object>> summary,
                                       Set<String> referenced,
                                       ProgressSink progress) {
        if (plan.targetDataSourceId() == null) throw ApiException.bad("DB receiver needs a target data source");
        DataSourceEntity ds = dataSources.get(plan.targetDataSourceId());
        String prep = plan.prepMode() == null ? null : plan.prepMode().trim().toUpperCase(Locale.ROOT);
        LoadPlan load = loadPlan(plan);
        boolean drop = "DROP_RECREATE".equals(prep) || Boolean.TRUE.equals(plan.dropTable());
        boolean create = Boolean.TRUE.equals(plan.createTable()) || drop;  // "drop & recreate" implies create
        List<Map<String, Object>> validation = new ArrayList<>();
        List<String> rangeWarnings = new ArrayList<>();
        progress.update(50, "Prepare target", "Connecting to target database");
        try (Connection out = connections.openForBulk(ds)) {
            String schema = DataSourceService.normalizeSchema(out, plan.targetSchema());
            SqlDialect dialect = SqlDialect.fromConnection(out);
            out.setAutoCommit(false);

            // Snapshot which tables ALREADY exist — prep (delete/truncate) applies ONLY to these,
            // never to brand-new tables created in this run.
            Set<String> existing = new HashSet<>();
            for (GenTable t : ordered) if (!columnTypes(out, schema, t.name()).isEmpty()) existing.add(t.name().toLowerCase(Locale.ROOT));
            if (!drop) {
                validateTargetGeneratorCompatibility(out, schema, ordered,
                        table -> Optional.ofNullable(data.get(table.name().toLowerCase(Locale.ROOT))).map(List::size).orElse(0));
            }

            List<GenTable> childFirst = new ArrayList<>(ordered);
            Collections.reverse(childFirst);   // children before parents (FK-safe for drop/delete)

            int prepOps = 0;
            if (drop) prepOps += existing.size();
            if (create) prepOps += ordered.size();
            if (!drop && load.clearsTarget()) {
                for (GenTable t : childFirst)
                    if (existing.contains(t.name().toLowerCase(Locale.ROOT))) prepOps++;
            }
            int prepTotal = Math.max(1, prepOps);
            int[] prepDone = {0};

            if (drop) {
                for (GenTable t : childFirst)
                    if (existing.contains(t.name().toLowerCase(Locale.ROOT))) {
                        execQuiet(out, "DROP TABLE " + q(schema, t.name()));
                        progress.update(percent(50, 64, ++prepDone[0], prepTotal), "Prepare target", "Dropped " + t.name());
                    }
                existing.clear();   // everything is gone now; nothing pre-existing left to clear
            }
            if (create) for (GenTable t : ordered) {
                execQuiet(out, createDdl(t.name(), schema, safe(t.columns())));
                progress.update(percent(50, 64, ++prepDone[0], prepTotal), "Prepare target", "Created or verified " + t.name());
            }
            if (drop) {
                validateTargetGeneratorCompatibility(out, schema, ordered,
                        table -> Optional.ofNullable(data.get(table.name().toLowerCase(Locale.ROOT))).map(List::size).orElse(0));
            }

            if (!drop && load.clearsTarget()) {
                String clearPrep = "TRUNCATE".equals(load.targetPrep()) ? "TRUNCATE_CASCADE" : "DELETE";
                String stage = "TRUNCATE_CASCADE".equals(clearPrep) ? "Truncate target" : "Delete target rows";
                List<GenTable> toClear = childFirst.stream()
                        .filter(t -> existing.contains(t.name().toLowerCase(Locale.ROOT)))
                        .toList();
                if ("TRUNCATE_CASCADE".equals(clearPrep) && dialect.supportsMultiTableTruncate()) {
                    clearTables(out, dialect, schema, toClear, clearPrep);
                    prepDone[0] += toClear.size();
                    progress.update(percent(50, 64, prepDone[0], prepTotal), stage,
                            stage + " completed for " + toClear.size() + " table(s)");
                } else {
                    for (GenTable t : toClear) {
                        clearTable(out, dialect, schema, t.name(), clearPrep);
                        progress.update(percent(50, 64, ++prepDone[0], prepTotal), stage,
                                stage + " completed for " + t.name());
                    }
                }
            }
            if (prepOps == 0) progress.update(64, "Prepare target", "Target ready");

            if (load.truncateOnly()) {
                progress.update(96, "Target cleared", "Target tables cleared");
                out.commit();
                return Map.of("receiver", "DB", "tables", summary, "loadAction", load.action());
            }

            int tableIndex = 0;
            int tableCount = Math.max(1, ordered.size());
            long loadRowsTotal = 0;
            for (GenTable t : ordered) {
                List<LinkedHashMap<String, String>> tableRows = data.get(t.name().toLowerCase(Locale.ROOT));
                if (tableRows != null) loadRowsTotal += tableRows.size();
            }
            long loadedBefore = 0;
            for (GenTable t : ordered) {
                progress.checkCancelled();
                String table = t.name();
                Map<String, Integer> types = columnTypes(out, schema, table);
                if (types.isEmpty())
                    throw ApiException.bad("Target table " + table + " does not exist. Enable 'Create missing tables' to build it from your plan.");
                Map<String, Integer> sizes = columnSizes(out, schema, table);
                Map<String, Integer> scales = columnScales(out, schema, table);
                List<LinkedHashMap<String, String>> rows = data.get(table.toLowerCase(Locale.ROOT));
                for (SyntheticRangeChecks.RangeIssue issue : SyntheticRangeChecks.check(t,
                        rows == null ? 0 : rows.size(), types, sizes, scales)) {
                    if (issue.fatal()) throw ApiException.bad(issue.message());
                    rangeWarnings.add(issue.message());
                    progress.update(64, "Range check", issue.message());
                }
                int from = 65 + (tableIndex * 30 / tableCount);
                int to = 65 + ((tableIndex + 1) * 30 / tableCount);
                switch (load.action()) {
                    case "UPDATE" -> updateRows(out, schema, t, rows, types, sizes, scales, load, progress, from, to, loadedBefore, loadRowsTotal);
                    case "INSERT_UPDATE" -> insertUpdateRows(out, schema, t, rows, types, sizes, scales, load, progress, from, to, loadedBefore, loadRowsTotal);
                    default -> insertRows(out, schema, t, rows, types, sizes, scales, load.batchSize(), referenced, progress, from, to, loadedBefore, loadRowsTotal);
                }
                loadedBefore += rows == null ? 0 : rows.size();
                tableIndex++;
            }
            progress.update(98, "Commit", "Committing target load");
            out.commit();
            progress.update(99, "Validate", "Validating loaded data");
            try { validation = validateLoad(out, schema, ordered, true); } catch (Exception ignore) { }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.bad("DB load failed: " + sqlDetail(e));
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("receiver", "DB");
        result.put("tables", summary);
        if (!validation.isEmpty()) result.put("validation", validation);
        if (!rangeWarnings.isEmpty()) result.put("rangeWarnings", rangeWarnings);
        return result;
    }

    private record PartitionRecord(String id, String jobId, int number, int wave, String table,
                                   long rowStart, long rowEnd, long plannedRows, int attempts) {}

    private record PartitionReferenceSnapshot(
            Map<String, List<String>> values,
            Map<String, Map<String, LinkedHashMap<String, String>>> rows,
            Map<String, List<String[]>> tuples) {
        static PartitionReferenceSnapshot empty() {
            return new PartitionReferenceSnapshot(Map.of(), Map.of(), Map.of());
        }
    }

    /** Partition coordinator: prepare once, run dependency waves, and commit every range atomically. */
    private Map<String, Object> dbLoadPartitioned(GenPlan plan, List<GenTable> ordered, long seed, long maxRows,
                                                   Set<String> referenced, Map<String, Set<String>> uniqueCols,
                                                   Map<String, List<SyntheticConstraintRules.Rule>> constraintRules,
                                                   List<Map<String, Object>> summary, ProgressSink progress) {
        if (plan.targetDataSourceId() == null) throw ApiException.bad("DB receiver needs a target data source");
        String jobId = progress.jobId();
        if (jobId == null) throw ApiException.bad("Partitioned execution requires /api/synthetic/generate/start so worker progress can be persisted");
        String mode = SyntheticPartitioning.mode(plan.executionMode());
        int workers = SyntheticPartitioning.workers(plan.partitionCount());
        LoadPlan requestedLoad = loadPlan(plan);
        // A partition is the retry/commit boundary. Intermediate commits would make an insert retry non-idempotent.
        LoadPlan partitionLoad = new LoadPlan(requestedLoad.action(), requestedLoad.targetPrep(), requestedLoad.keyColumns(),
                requestedLoad.batchSize(), 0, requestedLoad.continueOnError(), requestedLoad.maxRejects(), requestedLoad.fastLoad());
        List<List<GenTable>> waves = dependencyWaves(ordered);
        DataSourceEntity ds = dataSources.get(plan.targetDataSourceId());
        String schema;

        progress.update(7, "Prepare target", "Preparing the target once for all partitions");
        try (Connection coordinator = connections.openForBulk(ds)) {
            schema = DataSourceService.normalizeSchema(coordinator, plan.targetSchema());
            coordinator.setAutoCommit(false);
            preparePartitionTarget(coordinator, schema, ordered, plan, requestedLoad, maxRows, progress);
            coordinator.commit();
        } catch (Exception e) {
            throw ApiException.bad("Partition target preparation failed: " + sqlDetail(e));
        }

        List<List<PartitionRecord>> plannedWaves = new ArrayList<>();
        for (int wave = 0; wave < waves.size(); wave++) {
            List<PartitionRecord> records = new ArrayList<>();
            for (GenTable table : waves.get(wave)) {
                long rows = rowCount(table, maxRows);
                List<SyntheticPartitioning.RowRange> ranges;
                try {
                    ranges = SyntheticPartitioning.ranges(rows, workers, plan.partitionSize());
                } catch (IllegalArgumentException e) {
                    throw ApiException.bad(e.getMessage());
                }
                for (SyntheticPartitioning.RowRange range : ranges) {
                    PartitionRecord record = new PartitionRecord(UUID.randomUUID().toString(), jobId, range.number(), wave,
                            table.name(), range.startInclusive(), range.endExclusive(), range.size(), 0);
                    insertPartition(record, wave == 0 ? "QUEUED" : "BLOCKED");
                    records.add(record);
                }
            }
            plannedWaves.add(records);
        }

        progress.update(20, "Partition queue", "Queued " + plannedWaves.stream().mapToInt(List::size).sum()
                + " partitions across " + waves.size() + " dependency wave(s)");
        Semaphore workerLimit = new Semaphore(workers);
        for (int wave = 0; wave < plannedWaves.size(); wave++) {
            progress.checkCancelled();
            List<PartitionRecord> records = plannedWaves.get(wave);
            jdbc.update("UPDATE synthetic_job_partitions SET status = 'QUEUED', updated_at = ? " +
                            "WHERE job_id = ? AND dependency_wave = ? AND status = 'BLOCKED'",
                    ts(Instant.now()), jobId, wave);
            progress.update(22 + wave * 70 / Math.max(1, waves.size()), "Partition wave",
                    "Running dependency wave " + (wave + 1) + " of " + waves.size());
            if ("DISTRIBUTED".equals(mode)) {
                waitForDistributedWave(jobId, wave, workers, progress);
            } else {
                runLocalPartitionWave(records, plan, ordered, seed, referenced, uniqueCols, constraintRules,
                        schema, partitionLoad, workerLimit, progress);
            }
            ensureWaveSucceeded(jobId, wave);
        }

        List<Map<String, Object>> validation = new ArrayList<>();
        try (Connection out = connections.openForBulk(ds)) {
            progress.update(97, "Validate", "Validating partitioned load");
            validation = validateLoad(out, schema, ordered, false);
        } catch (Exception ignore) { }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("receiver", "DB");
        result.put("tables", summary);
        result.put("partitioned", true);
        result.put("executionMode", mode);
        result.put("workers", workers);
        result.put("partitions", queryPartitionSnapshots(jobId));
        if (!validation.isEmpty()) result.put("validation", validation);
        return result;
    }

    private List<List<GenTable>> dependencyWaves(List<GenTable> ordered) {
        Map<String, GenTable> byName = new LinkedHashMap<>();
        for (GenTable table : ordered) byName.put(table.name().toLowerCase(Locale.ROOT), table);
        Map<String, Set<String>> deps = new LinkedHashMap<>();
        for (GenTable table : ordered) {
            Set<String> tableDeps = new LinkedHashSet<>();
            String self = table.name().toLowerCase(Locale.ROOT);
            for (GenColumn column : safe(table.columns())) {
                String parent = blankNull(column.fkTable());
                if (parent != null && byName.containsKey(parent.toLowerCase(Locale.ROOT)) && !parent.equalsIgnoreCase(self)) {
                    tableDeps.add(parent.toLowerCase(Locale.ROOT));
                }
            }
            deps.put(self, tableDeps);
        }
        List<List<GenTable>> waves = new ArrayList<>();
        Set<String> done = new HashSet<>();
        while (done.size() < ordered.size()) {
            List<GenTable> wave = new ArrayList<>();
            for (GenTable table : ordered) {
                String name = table.name().toLowerCase(Locale.ROOT);
                if (!done.contains(name) && done.containsAll(deps.getOrDefault(name, Set.of()))) wave.add(table);
            }
            if (wave.isEmpty()) {
                throw ApiException.bad("Partitioned generation requires an acyclic foreign-key graph; break the cycle or use Single worker mode");
            }
            waves.add(wave);
            wave.forEach(t -> done.add(t.name().toLowerCase(Locale.ROOT)));
        }
        return waves;
    }

    private void preparePartitionTarget(Connection out, String schema, List<GenTable> ordered, GenPlan plan,
                                        LoadPlan load, long maxRows, ProgressSink progress) throws SQLException {
        SqlDialect dialect = SqlDialect.fromConnection(out);
        String prep = plan.prepMode() == null ? "" : plan.prepMode().trim().toUpperCase(Locale.ROOT);
        boolean drop = "DROP_RECREATE".equals(prep) || Boolean.TRUE.equals(plan.dropTable());
        boolean create = Boolean.TRUE.equals(plan.createTable()) || drop;
        Set<String> existing = new HashSet<>();
        for (GenTable table : ordered) {
            if (!columnTypes(out, schema, table.name()).isEmpty()) existing.add(table.name().toLowerCase(Locale.ROOT));
        }
        if (!drop) {
            validateTargetGeneratorCompatibility(out, schema, ordered, table -> rowCount(table, maxRows));
        }
        List<GenTable> childFirst = new ArrayList<>(ordered);
        Collections.reverse(childFirst);
        if (drop) {
            for (GenTable table : childFirst) {
                if (existing.contains(table.name().toLowerCase(Locale.ROOT))) execQuiet(out, "DROP TABLE " + q(schema, table.name()));
            }
            existing.clear();
        }
        if (create) {
            for (GenTable table : ordered) execQuiet(out, createDdl(table.name(), schema, safe(table.columns())));
        }
        if (drop) {
            validateTargetGeneratorCompatibility(out, schema, ordered, table -> rowCount(table, maxRows));
        }
        if (!drop && load.clearsTarget()) {
            String clearPrep = "TRUNCATE".equals(load.targetPrep()) ? "TRUNCATE_CASCADE" : "DELETE";
            List<GenTable> toClear = childFirst.stream()
                    .filter(t -> existing.contains(t.name().toLowerCase(Locale.ROOT))).toList();
            if ("TRUNCATE_CASCADE".equals(clearPrep) && dialect.supportsMultiTableTruncate()) {
                clearTables(out, dialect, schema, toClear, clearPrep);
            } else {
                for (GenTable table : toClear) clearTable(out, dialect, schema, table.name(), clearPrep);
            }
        }
        for (GenTable table : ordered) {
            if (columnTypes(out, schema, table.name()).isEmpty()) {
                throw ApiException.bad("Target table " + table.name() + " does not exist. Enable 'Create missing tables'.");
            }
        }
        progress.update(18, "Prepare target", "Target prepared once; partition workers will only write rows");
    }

    private void validateTargetGeneratorCompatibility(Connection out, String schema, List<GenTable> tables,
                                                        java.util.function.ToLongFunction<GenTable> rows) throws SQLException {
        for (GenTable table : tables) {
            Map<String, Integer> types = columnTypes(out, schema, table.name());
            if (types.isEmpty()) continue;
            Map<String, Integer> sizes = columnSizes(out, schema, table.name());
            Map<String, Integer> scales = columnScales(out, schema, table.name());
            for (SyntheticRangeChecks.RangeIssue issue : SyntheticRangeChecks.check(
                    table, Math.max(0, rows.applyAsLong(table)), types, sizes, scales)) {
                if (issue.fatal()) throw ApiException.bad("Target compatibility check failed before target preparation: " + issue.message());
            }
        }
    }

    private void runLocalPartitionWave(List<PartitionRecord> records, GenPlan plan, List<GenTable> ordered, long seed,
                                       Set<String> referenced, Map<String, Set<String>> uniqueCols,
                                       Map<String, List<SyntheticConstraintRules.Rule>> constraintRules,
                                       String schema, LoadPlan load, Semaphore workerLimit, ProgressSink parentProgress) {
        List<Future<?>> futures = new ArrayList<>();
        for (PartitionRecord record : records) {
            Future<?> future = partitionExecutor.submit(() -> {
                boolean acquired = false;
                try {
                    workerLimit.acquire();
                    acquired = true;
                    executePartitionWithRetries(record, plan, ordered, seed, referenced, uniqueCols, constraintRules,
                            schema, load, parentProgress);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    markPartitionCancelled(record.id(), "Partition interrupted");
                    throw new SyntheticJobCancelledException();
                } finally {
                    if (acquired) workerLimit.release();
                    partitionFutures.remove(record.jobId() + ":" + record.id());
                }
            });
            partitionFutures.put(record.jobId() + ":" + record.id(), future);
            futures.add(future);
        }
        for (Future<?> future : futures) {
            try { future.get(); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SyntheticJobCancelledException();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof SyntheticJobCancelledException cancelled) throw cancelled;
                throw ApiException.bad(cause == null ? "Partition failed" : msgOf(cause));
            } catch (CancellationException e) {
                throw new SyntheticJobCancelledException();
            }
        }
    }

    private void executePartitionWithRetries(PartitionRecord record, GenPlan plan, List<GenTable> ordered, long seed,
                                             Set<String> referenced, Map<String, Set<String>> uniqueCols,
                                             Map<String, List<SyntheticConstraintRules.Rule>> constraintRules,
                                             String schema, LoadPlan load, ProgressSink parentProgress) {
        Throwable last = null;
        for (int attempt = Math.max(1, record.attempts() + 1); attempt <= 3; attempt++) {
            try {
                executePartition(record, plan, ordered, seed, referenced, uniqueCols, constraintRules, schema, load, parentProgress, attempt);
                return;
            } catch (SyntheticJobCancelledException e) {
                markPartitionCancelled(record.id(), "Cancelled");
                throw e;
            } catch (Throwable e) {
                last = e;
                if (attempt < 3 && !partitionCancelled(record.id(), record.jobId())) {
                    markPartitionRetry(record.id(), attempt, e);
                    try { Thread.sleep(250L * attempt); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new SyntheticJobCancelledException();
                    }
                }
            }
        }
        markPartitionFailed(record.id(), last);
        throw ApiException.bad("Partition " + record.table() + " #" + record.number() + " failed after 3 attempts: " + msgOf(last));
    }

    private void executePartition(PartitionRecord record, GenPlan plan, List<GenTable> ordered, long seed,
                                  Set<String> referenced, Map<String, Set<String>> uniqueCols,
                                  Map<String, List<SyntheticConstraintRules.Rule>> constraintRules,
                                  String schema, LoadPlan load, ProgressSink parentProgress, int attempt) throws Exception {
        GenTable table = ordered.stream().filter(t -> t.name().equalsIgnoreCase(record.table())).findFirst()
                .orElseThrow(() -> ApiException.bad("Partition table " + record.table() + " is no longer in the plan"));
        markPartitionRunning(record, attempt);
        DataSourceEntity ds = dataSources.get(plan.targetDataSourceId());
        try (Connection out = connections.openForBulk(ds)) {
            out.setAutoCommit(false);
            ProgressSink partitionProgress = partitionProgress(record, parentProgress);
            partitionProgress.checkCancelled();
            PartitionReferenceSnapshot snapshot = loadPartitionReferences(out, schema, table, plan);
            StreamCtx ctx = partitionStreamContext(plan, ordered, referenced, uniqueCols, constraintRules, seed, snapshot);
            ctx.partitionRange(record.rowStart(), rowCount(table, 100_000_000L));
            Map<String, Integer> types = columnTypes(out, schema, table.name());
            Map<String, Integer> sizes = columnSizes(out, schema, table.name());
            Map<String, Integer> scales = columnScales(out, schema, table.name());
            SqlDialect dialect = SqlDialect.fromConnection(out);
            RejectCollector rejects = new RejectCollector(load.continueOnError(), load.maxRejects());
            Random partitionRng = new Random(SyntheticPartitioning.partitionSeed(seed, table.name(), record.number()));
            long rows = record.plannedRows();
            for (SyntheticRangeChecks.RangeIssue issue : SyntheticRangeChecks.check(table, rows, types, sizes, scales)) {
                if (issue.fatal()) throw ApiException.bad(issue.message());
                partitionProgress.update(22, "Range check", issue.message());
            }
            switch (load.action()) {
                case "UPDATE" -> streamUpdateRows(out, schema, table, rows, partitionRng, ctx, types, sizes, scales,
                        load, partitionProgress, 22, 96, 0, rows);
                case "INSERT_UPDATE" -> streamInsertUpdateRows(out, schema, table, rows, partitionRng, ctx, types, sizes, scales,
                        load, partitionProgress, 22, 96, 0, rows);
                default -> {
                    if (load.fastLoad() && isPostgres(out)) {
                        copyInsertRows(out, schema, table, rows, partitionRng, ctx, types, sizes, scales, load,
                                partitionProgress, 22, 96, 0, rows);
                    } else if (load.fastLoad() && dialect.supportsMultiRowInsert()) {
                        multiRowInsertRows(out, schema, table, rows, partitionRng, ctx, types, sizes, scales, dialect,
                                load, rejects, partitionProgress, 22, 96, 0, rows);
                    } else {
                        streamInsertRows(out, schema, table, rows, partitionRng, ctx, types, sizes, scales,
                                load, rejects, partitionProgress, 22, 96, 0, rows);
                    }
                }
            }
            partitionProgress.checkCancelled();
            out.commit();
            markPartitionCompleted(record.id());
            updatePartitionAggregate(record.jobId(), record.table(), parentProgress);
        }
    }

    private StreamCtx partitionStreamContext(GenPlan plan, List<GenTable> ordered, Set<String> referenced,
                                             Map<String, Set<String>> uniqueCols,
                                             Map<String, List<SyntheticConstraintRules.Rule>> constraintRules,
                                             long seed, PartitionReferenceSnapshot snapshot) {
        Map<String, ValuePool> pools = new HashMap<>();
        Set<String> indexed = lookupTargetKeys(ordered);
        StreamCtx ctx = new StreamCtx(pools, referenced, compositeFkGroups(plan), indexed, uniqueCols, constraintRules, seed, true);
        snapshot.values().forEach((key, values) -> {
            ValuePool pool = pools.computeIfAbsent(key, k -> new ValuePool(Objects.hash(k, seed)));
            values.forEach(pool::add);
        });
        snapshot.rows().forEach((key, rows) -> {
            IndexPool pool = ctx.rowIndex.computeIfAbsent(key, k -> new IndexPool(Objects.hash(k, seed)));
            rows.forEach(pool::add);
        });
        snapshot.tuples().forEach((key, tuples) -> {
            TuplePool pool = ctx.tuplePools.computeIfAbsent(key, k -> new TuplePool(Objects.hash(k, seed)));
            tuples.forEach(pool::add);
        });
        return ctx;
    }

    private PartitionReferenceSnapshot loadPartitionReferences(Connection connection, String schema, GenTable child, GenPlan plan) {
        Map<String, List<String>> values = new HashMap<>();
        Map<String, Map<String, LinkedHashMap<String, String>>> rows = new HashMap<>();
        Map<String, List<String[]>> tuples = new HashMap<>();
        Set<String> indexed = lookupTargetKeys(safe(plan.tables()));
        Set<String> loaded = new HashSet<>();
        for (GenColumn column : safe(child.columns())) {
            if (!notBlank(column.fkTable()) || !notBlank(column.fkColumn())) continue;
            String key = key(column.fkTable(), column.fkColumn());
            if (!loaded.add(key)) continue;
            boolean fullRow = indexed.contains(key);
            String sql = "SELECT " + (fullRow ? "*" : q(column.fkColumn())) + " FROM " + q(schema, column.fkTable());
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setMaxRows(MAX_STREAMING_FK_POOL_VALUES);
                try (ResultSet rs = ps.executeQuery()) {
                    List<String> keyValues = new ArrayList<>();
                    Map<String, LinkedHashMap<String, String>> rowMap = new LinkedHashMap<>();
                    ResultSetMetaData md = rs.getMetaData();
                    while (rs.next()) {
                        String value = rs.getString(column.fkColumn());
                        if (value == null) continue;
                        keyValues.add(value);
                        if (fullRow) {
                            LinkedHashMap<String, String> row = new LinkedHashMap<>();
                            for (int i = 1; i <= md.getColumnCount(); i++) row.put(md.getColumnLabel(i), rs.getString(i));
                            rowMap.putIfAbsent(value, row);
                        }
                    }
                    if (fullRow) rows.put(key, rowMap); else values.put(key, keyValues);
                }
            } catch (SQLException e) {
                throw ApiException.bad("Unable to load parent keys for " + child.name() + "." + column.name() + ": " + sqlDetail(e));
            }
        }
        for (CompositeFk group : compositeFkGroups(plan).getOrDefault(child.name().toLowerCase(Locale.ROOT), List.of())) {
            String sql = "SELECT " + String.join(",", group.parentCols().stream().map(SyntheticGenService::q).toList())
                    + " FROM " + q(schema, group.parentTable());
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setMaxRows(MAX_STREAMING_FK_POOL_VALUES);
                try (ResultSet rs = ps.executeQuery()) {
                    List<String[]> groupTuples = new ArrayList<>();
                    while (rs.next()) {
                        String[] tuple = new String[group.parentCols().size()];
                        for (int i = 0; i < tuple.length; i++) tuple[i] = rs.getString(i + 1);
                        groupTuples.add(tuple);
                    }
                    tuples.put(group.tupleKey(), groupTuples);
                }
            } catch (SQLException e) {
                throw ApiException.bad("Unable to load composite parent keys for " + child.name() + ": " + sqlDetail(e));
            }
        }
        return new PartitionReferenceSnapshot(values, rows, tuples);
    }

    private void insertPartition(PartitionRecord record, String status) {
        jdbc.update("INSERT INTO synthetic_job_partitions(id, job_id, partition_number, dependency_wave, table_name, " +
                        "row_start, row_end, planned_rows, rows_completed, status, attempt_count, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, 0, ?, ?)",
                record.id(), record.jobId(), record.number(), record.wave(), record.table(), record.rowStart(), record.rowEnd(),
                record.plannedRows(), status, ts(Instant.now()), ts(Instant.now()));
    }

    private ProgressSink partitionProgress(PartitionRecord record, ProgressSink parentProgress) {
        return new ProgressSink() {
            private Statement held;

            @Override public void update(int percent, String stage, String message) {
                checkCancelled();
                heartbeatPartition(record.id(), null);
            }

            @Override public void updateRows(int percent, String stage, String message, String table,
                                             long tableRowsDone, long tableRowsTotal, long rowsDone, long rowsTotal) {
                checkCancelled();
                heartbeatPartition(record.id(), Math.min(record.plannedRows(), Math.max(0, tableRowsDone)));
                updatePartitionAggregate(record.jobId(), record.table(), parentProgress);
            }

            @Override public void checkCancelled() {
                if (Thread.currentThread().isInterrupted() || partitionCancelled(record.id(), record.jobId())) {
                    throw new SyntheticJobCancelledException();
                }
            }

            @Override public void activeStatement(Statement statement) {
                SyntheticJob job = syntheticJobs.get(record.jobId());
                if (job != null && held != null) job.activeStatements.remove(held);
                held = statement;
                if (job != null && statement != null) job.activeStatements.add(statement);
            }

            @Override public String jobId() { return record.jobId(); }
        };
    }

    private void heartbeatPartition(String partitionId, Long rowsCompleted) {
        Instant now = Instant.now();
        Instant lease = now.plus(Duration.ofMinutes(5));
        if (rowsCompleted == null) {
            jdbc.update("UPDATE synthetic_job_partitions SET heartbeat_at = ?, lease_expires_at = ?, updated_at = ? WHERE id = ?",
                    ts(now), ts(lease), ts(now), partitionId);
        } else {
            jdbc.update("UPDATE synthetic_job_partitions SET rows_completed = ?, heartbeat_at = ?, lease_expires_at = ?, updated_at = ? WHERE id = ?",
                    rowsCompleted, ts(now), ts(lease), ts(now), partitionId);
        }
    }

    private void markPartitionRunning(PartitionRecord record, int attempt) {
        Instant now = Instant.now();
        jdbc.update("UPDATE synthetic_job_partitions SET status = 'RUNNING', worker_id = ?, attempt_count = ?, " +
                        "heartbeat_at = ?, lease_expires_at = ?, started_at = COALESCE(started_at, ?), finished_at = NULL, " +
                        "error = NULL, updated_at = ? WHERE id = ?",
                currentWorkerId(), attempt, ts(now), ts(now.plus(Duration.ofMinutes(5))),
                ts(now), ts(now), record.id());
    }

    private void markPartitionCompleted(String partitionId) {
        Instant now = Instant.now();
        jdbc.update("UPDATE synthetic_job_partitions SET status = 'COMPLETED', worker_id = ?, heartbeat_at = ?, " +
                        "lease_expires_at = NULL, finished_at = ?, updated_at = ? WHERE id = ?",
                currentWorkerId(), ts(now), ts(now), ts(now), partitionId);
    }

    @SuppressWarnings("deprecation")
    private String currentWorkerId() {
        Thread thread = Thread.currentThread();
        return workerInstanceId + ":" + thread.getName() + "-" + thread.getId();
    }

    private void markPartitionRetry(String partitionId, int attempt, Throwable error) {
        jdbc.update("UPDATE synthetic_job_partitions SET status = 'RETRYING', attempt_count = ?, error = ?, " +
                        "lease_expires_at = NULL, updated_at = ? WHERE id = ?",
                attempt, clip(msgOf(error), 4000), ts(Instant.now()), partitionId);
    }

    private void markPartitionFailed(String partitionId, Throwable error) {
        Instant now = Instant.now();
        jdbc.update("UPDATE synthetic_job_partitions SET status = 'FAILED', error = ?, lease_expires_at = NULL, " +
                        "finished_at = ?, updated_at = ? WHERE id = ?",
                clip(msgOf(error), 4000), ts(now), ts(now), partitionId);
    }

    private void markPartitionCancelled(String partitionId, String reason) {
        Instant now = Instant.now();
        jdbc.update("UPDATE synthetic_job_partitions SET status = 'CANCELLED', cancel_requested = TRUE, error = ?, " +
                        "lease_expires_at = NULL, finished_at = ?, updated_at = ? WHERE id = ?",
                reason, ts(now), ts(now), partitionId);
    }

    private boolean partitionCancelled(String partitionId, String jobId) {
        SyntheticJob live = syntheticJobs.get(jobId);
        if (live != null && live.cancelRequested) return true;
        List<Boolean> rows = jdbc.query("SELECT p.cancel_requested OR j.cancel_requested FROM synthetic_job_partitions p " +
                        "JOIN synthetic_generation_jobs j ON j.id = p.job_id WHERE p.id = ?",
                (rs, rowNum) -> rs.getBoolean(1), partitionId);
        return rows.isEmpty() || Boolean.TRUE.equals(rows.get(0));
    }

    private void cancelPersistedPartitions(String jobId) {
        Instant now = Instant.now();
        jdbc.update("UPDATE synthetic_job_partitions SET cancel_requested = TRUE, " +
                        "status = CASE WHEN status IN ('QUEUED','BLOCKED','RETRYING','CLAIMED') THEN 'CANCELLED' ELSE status END, " +
                        "finished_at = CASE WHEN status IN ('QUEUED','BLOCKED','RETRYING','CLAIMED') THEN ? ELSE finished_at END, " +
                        "updated_at = ? WHERE job_id = ? AND status NOT IN ('COMPLETED','FAILED','CANCELLED')",
                ts(now), ts(now), jobId);
    }

    private boolean partitionBelongsToJob(String futureKey, String jobId) {
        return futureKey != null && futureKey.startsWith(jobId + ":");
    }

    private void updatePartitionAggregate(String jobId, String table, ProgressSink parentProgress) {
        Map<String, Object> totals = jdbc.queryForMap("SELECT COALESCE(SUM(rows_completed),0) rows_done, " +
                "COALESCE(SUM(planned_rows),0) rows_total FROM synthetic_job_partitions WHERE job_id = ?", jobId);
        Map<String, Object> tableTotals = jdbc.queryForMap("SELECT COALESCE(SUM(rows_completed),0) rows_done, " +
                "COALESCE(SUM(planned_rows),0) rows_total FROM synthetic_job_partitions WHERE job_id = ? AND LOWER(table_name) = LOWER(?)",
                jobId, table);
        long done = number(totals.get("rows_done"));
        long total = number(totals.get("rows_total"));
        long tableDone = number(tableTotals.get("rows_done"));
        long tableTotal = number(tableTotals.get("rows_total"));
        int percent = percent(22, 96, done, Math.max(1, total));
        String message = "Partitioned load: " + done + " of " + total + " rows committed";
        SyntheticJob live = syntheticJobs.get(jobId);
        if (live != null) {
            updateJobRows(live, percent, "Partition load", message, table, tableDone, tableTotal, done, total);
        } else {
            jdbc.update("UPDATE synthetic_generation_jobs SET percent = ?, stage = 'Partition load', message = ?, detail = ?, " +
                            "current_table = ?, table_rows_done = ?, table_rows_total = ?, rows_done = ?, rows_total = ?, updated_at = ? WHERE id = ?",
                    percent, message, table + ": " + tableDone + " of " + tableTotal + " rows committed; total " + done + " of " + total,
                    table, tableDone, tableTotal, done, total, ts(Instant.now()), jobId);
        }
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static String clip(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private void ensureWaveSucceeded(String jobId, int wave) {
        List<String> failed = jdbc.query("SELECT table_name || ' #' || partition_number || ': ' || COALESCE(error,'failed') " +
                        "FROM synthetic_job_partitions WHERE job_id = ? AND dependency_wave = ? AND status = 'FAILED'",
                (rs, rowNum) -> rs.getString(1), jobId, wave);
        if (!failed.isEmpty()) throw ApiException.bad("Partition wave failed: " + String.join("; ", failed));
    }

    private void waitForDistributedWave(String jobId, int wave, int workers, ProgressSink progress) {
        while (true) {
            progress.checkCancelled();
            dispatchDistributedWave(jobId, wave, Math.max(1, workers));
            Map<String, Object> state = jdbc.queryForMap("SELECT COUNT(*) total, " +
                            "SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END) completed, " +
                            "SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) failed " +
                            "FROM synthetic_job_partitions WHERE job_id = ? AND dependency_wave = ?",
                    jobId, wave);
            long total = number(state.get("total"));
            long completed = number(state.get("completed"));
            if (number(state.get("failed")) > 0) return;
            if (completed >= total) return;
            try { Thread.sleep(500); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SyntheticJobCancelledException();
            }
        }
    }

    private void dispatchDistributedWave(String jobId, int wave, int workers) {
        long activeForJob = partitionFutures.entrySet().stream()
                .filter(e -> e.getKey().startsWith(jobId + ":") && !e.getValue().isDone()).count();
        int slots = Math.max(0, workers - (int) activeForJob);
        if (slots == 0) return;
        List<String> candidates = jdbc.query("SELECT p.id FROM synthetic_job_partitions p " +
                        "JOIN synthetic_generation_jobs j ON j.id = p.job_id " +
                        "WHERE p.job_id = ? AND p.dependency_wave = ? AND p.status = 'QUEUED' AND j.status = 'RUNNING' " +
                        "ORDER BY p.partition_number LIMIT ?",
                (rs, rowNum) -> rs.getString(1), jobId, wave, slots);
        for (String id : candidates) {
            PartitionRecord record = claimDistributedPartition(id);
            if (record == null) continue;
            GenPlan plan = partitionPlan(record.jobId());
            if (plan == null) {
                markPartitionFailed(record.id(), new IllegalStateException("Synthetic plan is missing"));
                continue;
            }
            Future<?> future = partitionExecutor.submit(() -> runClaimedDistributedPartition(record, plan));
            partitionFutures.put(record.jobId() + ":" + record.id(), future);
        }
    }

    private List<Map<String, Object>> queryPartitionSnapshots(String jobId) {
        try {
            return jdbc.query("SELECT * FROM synthetic_job_partitions WHERE job_id = ? ORDER BY dependency_wave, table_name, partition_number",
                    (rs, rowNum) -> {
                        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
                        out.put("id", rs.getString("id"));
                        out.put("number", rs.getInt("partition_number"));
                        out.put("wave", rs.getInt("dependency_wave"));
                        out.put("table", rs.getString("table_name"));
                        out.put("rowStart", rs.getLong("row_start"));
                        out.put("rowEnd", rs.getLong("row_end"));
                        out.put("plannedRows", rs.getLong("planned_rows"));
                        out.put("rowsCompleted", rs.getLong("rows_completed"));
                        out.put("status", rs.getString("status"));
                        putIfNotBlank(out, "workerId", rs.getString("worker_id"));
                        out.put("attemptCount", rs.getInt("attempt_count"));
                        out.put("cancelRequested", rs.getBoolean("cancel_requested"));
                        putIfNotBlank(out, "error", rs.getString("error"));
                        putIfNotBlank(out, "startedAt", instantString(rs.getTimestamp("started_at")));
                        putIfNotBlank(out, "finishedAt", instantString(rs.getTimestamp("finished_at")));
                        return out;
                    }, jobId);
        } catch (Exception e) {
            return List.of();
        }
    }

    private void enrichPartitionSnapshots(List<Map<String, Object>> jobs) {
        for (Map<String, Object> job : jobs) {
            List<Map<String, Object>> partitions = queryPartitionSnapshots(String.valueOf(job.get("id")));
            if (!partitions.isEmpty()) job.put("partitions", partitions);
        }
    }

    @Scheduled(fixedDelayString = "${forgetdm.synthetic.partition.poll-ms:750}")
    public void pollDistributedPartitions() {
        recoverExpiredPartitions();
        dispatchDistributedPartitions(null, null, SyntheticPartitioning.MAX_WORKERS);
    }

    private void dispatchDistributedPartitions(String onlyJobId, Integer onlyWave, int maxWorkers) {
        long active = partitionFutures.values().stream().filter(f -> !f.isDone()).count();
        if (active >= maxWorkers) return;
        List<String> candidates;
        try {
            StringBuilder sql = new StringBuilder("SELECT p.id FROM synthetic_job_partitions p JOIN synthetic_generation_jobs j ON j.id = p.job_id " +
                    "WHERE p.status = 'QUEUED' AND j.status = 'RUNNING'");
            List<Object> args = new ArrayList<>();
            if (onlyJobId != null) { sql.append(" AND p.job_id = ?"); args.add(onlyJobId); }
            if (onlyWave != null) { sql.append(" AND p.dependency_wave = ?"); args.add(onlyWave); }
            sql.append(" ORDER BY p.created_at LIMIT 32");
            candidates = jdbc.query(sql.toString(), (rs, rowNum) -> rs.getString(1), args.toArray());
        } catch (Exception e) { return; }
        for (String id : candidates) {
            long running = partitionFutures.values().stream().filter(f -> !f.isDone()).count();
            if (running >= maxWorkers) break;
            PartitionRecord record = claimDistributedPartition(id);
            if (record == null) continue;
            GenPlan plan = partitionPlan(record.jobId());
            if (plan == null || !"DISTRIBUTED".equals(SyntheticPartitioning.mode(plan.executionMode()))) {
                jdbc.update("UPDATE synthetic_job_partitions SET status = 'QUEUED', worker_id = NULL WHERE id = ?", id);
                continue;
            }
            Future<?> future = partitionExecutor.submit(() -> runClaimedDistributedPartition(record, plan));
            partitionFutures.put(record.jobId() + ":" + record.id(), future);
        }
    }

    private PartitionRecord claimDistributedPartition(String id) {
        int claimed = jdbc.update("UPDATE synthetic_job_partitions SET status = 'CLAIMED', worker_id = ?, updated_at = ? " +
                "WHERE id = ? AND status = 'QUEUED'", workerInstanceId, ts(Instant.now()), id);
        if (claimed != 1) return null;
        List<PartitionRecord> rows = jdbc.query("SELECT * FROM synthetic_job_partitions WHERE id = ?", (rs, rowNum) ->
                new PartitionRecord(rs.getString("id"), rs.getString("job_id"), rs.getInt("partition_number"),
                        rs.getInt("dependency_wave"), rs.getString("table_name"), rs.getLong("row_start"),
                        rs.getLong("row_end"), rs.getLong("planned_rows"), rs.getInt("attempt_count")), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void runClaimedDistributedPartition(PartitionRecord record, GenPlan persistedPlan) {
        try {
            GenPlan plan = resolveValueLists(enrichForeignKeys(persistedPlan));
            List<GenTable> ordered = topoSort(plan.tables());
            Set<String> referenced = new HashSet<>();
            for (GenTable table : ordered) for (GenColumn column : safe(table.columns())) {
                if (notBlank(column.fkTable()) && notBlank(column.fkColumn())) referenced.add(key(column.fkTable(), column.fkColumn()));
            }
            String schema;
            try (Connection connection = connections.openForBulk(dataSources.get(plan.targetDataSourceId()))) {
                schema = DataSourceService.normalizeSchema(connection, plan.targetSchema());
            }
            LoadPlan requested = loadPlan(plan);
            LoadPlan atomic = new LoadPlan(requested.action(), requested.targetPrep(), requested.keyColumns(), requested.batchSize(),
                    0, requested.continueOnError(), requested.maxRejects(), requested.fastLoad());
            executePartitionWithRetries(record, plan, ordered, plan.seed() == null ? 42L : plan.seed(), referenced,
                    uniqueColumnsByTable(plan), rulesByTable(captureConstraintRules(plan).rules()), schema, atomic, NO_PROGRESS);
        } catch (Throwable e) {
            if (!partitionCancelled(record.id(), record.jobId())) markPartitionFailed(record.id(), e);
        } finally {
            partitionFutures.remove(record.jobId() + ":" + record.id());
        }
    }

    private GenPlan partitionPlan(String jobId) {
        List<String> rows = jdbc.query("SELECT plan_json FROM synthetic_generation_jobs WHERE id = ?",
                (rs, rowNum) -> rs.getString(1), jobId);
        return rows.isEmpty() ? null : fromJsonPlan(rows.get(0));
    }

    private void recoverExpiredPartitions() {
        try {
            Instant now = Instant.now();
            jdbc.update("UPDATE synthetic_job_partitions SET status = CASE WHEN attempt_count >= 3 THEN 'FAILED' ELSE 'QUEUED' END, " +
                            "worker_id = NULL, error = CASE WHEN attempt_count >= 3 THEN 'Worker lease expired after 3 attempts' ELSE error END, " +
                            "lease_expires_at = NULL, updated_at = ? WHERE status = 'RUNNING' AND lease_expires_at < ? AND cancel_requested = FALSE",
                    ts(now), ts(now));
        } catch (Exception ignore) { }
    }

    private void ensurePartitionBelongs(String partitionId, String jobId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM synthetic_job_partitions WHERE id = ? AND job_id = ?",
                Integer.class, partitionId, jobId);
        if (count == null || count == 0) throw ApiException.notFound("Synthetic partition " + partitionId + " not found");
    }

    private String partitionStatus(String partitionId) {
        List<String> rows = jdbc.query("SELECT status FROM synthetic_job_partitions WHERE id = ?",
                (rs, rowNum) -> rs.getString(1), partitionId);
        if (rows.isEmpty()) throw ApiException.notFound("Synthetic partition " + partitionId + " not found");
        return rows.get(0).toUpperCase(Locale.ROOT);
    }

    private List<PartitionRecord> partitionRecords(String jobId, int wave) {
        return jdbc.query("SELECT * FROM synthetic_job_partitions WHERE job_id = ? AND dependency_wave = ? " +
                        "AND status <> 'COMPLETED' ORDER BY table_name, partition_number",
                (rs, rowNum) -> new PartitionRecord(rs.getString("id"), rs.getString("job_id"), rs.getInt("partition_number"),
                        rs.getInt("dependency_wave"), rs.getString("table_name"), rs.getLong("row_start"),
                        rs.getLong("row_end"), rs.getLong("planned_rows"), rs.getInt("attempt_count")), jobId, wave);
    }

    private void resumePartitionedJob(String jobId, GenPlan persistedPlan, String lock) {
        try {
            GenPlan plan = resolveValueLists(enrichForeignKeys(persistedPlan));
            List<GenTable> ordered = topoSort(plan.tables());
            List<List<GenTable>> waves = dependencyWaves(ordered);
            Set<String> referenced = new HashSet<>();
            for (GenTable table : ordered) for (GenColumn column : safe(table.columns())) {
                if (notBlank(column.fkTable()) && notBlank(column.fkColumn())) referenced.add(key(column.fkTable(), column.fkColumn()));
            }
            Map<String, Set<String>> unique = uniqueColumnsByTable(plan);
            Map<String, List<SyntheticConstraintRules.Rule>> constraints = rulesByTable(captureConstraintRules(plan).rules());
            String schema;
            try (Connection connection = connections.openForBulk(dataSources.get(plan.targetDataSourceId()))) {
                schema = DataSourceService.normalizeSchema(connection, plan.targetSchema());
            }
            LoadPlan requested = loadPlan(plan);
            LoadPlan atomic = new LoadPlan(requested.action(), requested.targetPrep(), requested.keyColumns(), requested.batchSize(),
                    0, requested.continueOnError(), requested.maxRejects(), requested.fastLoad());
            Semaphore limit = new Semaphore(SyntheticPartitioning.workers(plan.partitionCount()));
            for (int wave = 0; wave < waves.size(); wave++) {
                Integer incompleteBefore = jdbc.queryForObject("SELECT COUNT(*) FROM synthetic_job_partitions WHERE job_id = ? " +
                        "AND dependency_wave < ? AND status <> 'COMPLETED'", Integer.class, jobId, wave);
                if (incompleteBefore != null && incompleteBefore > 0) break;
                jdbc.update("UPDATE synthetic_job_partitions SET status = 'QUEUED', cancel_requested = FALSE, updated_at = ? " +
                                "WHERE job_id = ? AND dependency_wave = ? AND status IN ('BLOCKED','CANCELLED','FAILED','RETRYING')",
                        ts(Instant.now()), jobId, wave);
                List<PartitionRecord> records = partitionRecords(jobId, wave);
                if (records.isEmpty()) continue;
                if ("DISTRIBUTED".equals(SyntheticPartitioning.mode(plan.executionMode()))) {
                    waitForDistributedWave(jobId, wave, SyntheticPartitioning.workers(plan.partitionCount()), NO_PROGRESS);
                } else {
                    runLocalPartitionWave(records, plan, ordered, plan.seed() == null ? 42L : plan.seed(), referenced,
                            unique, constraints, schema, atomic, limit, NO_PROGRESS);
                }
                ensureWaveSucceeded(jobId, wave);
            }
            Integer remaining = jdbc.queryForObject("SELECT COUNT(*) FROM synthetic_job_partitions WHERE job_id = ? AND status <> 'COMPLETED'",
                    Integer.class, jobId);
            if (remaining == null || remaining == 0) {
                Instant now = Instant.now();
                jdbc.update("UPDATE synthetic_generation_jobs SET status = 'COMPLETED', percent = 100, stage = 'Completed', " +
                                "message = 'Partition retry completed', error = NULL, finished_at = ?, updated_at = ? WHERE id = ?",
                        ts(now), ts(now), jobId);
            }
        } catch (Throwable e) {
            Instant now = Instant.now();
            jdbc.update("UPDATE synthetic_generation_jobs SET status = 'FAILED', stage = 'Failed', message = ?, error = ?, " +
                            "finished_at = ?, updated_at = ? WHERE id = ?",
                    msgOf(e), msgOf(e), ts(now), ts(now), jobId);
        } finally {
            releaseTargetLock(lock);
        }
    }

    @Scheduled(fixedDelayString = "${forgetdm.synthetic.partition.reconcile-ms:1500}")
    public void reconcileDistributedJobs() {
        List<Map<String, String>> jobs;
        try {
            jobs = jdbc.query("SELECT id, plan_json FROM synthetic_generation_jobs WHERE status = 'RUNNING'",
                    (rs, rowNum) -> Map.of("id", rs.getString(1), "plan", rs.getString(2)));
        } catch (Exception e) { return; }
        for (Map<String, String> row : jobs) {
            GenPlan plan;
            try { plan = fromJsonPlan(row.get("plan")); }
            catch (Exception e) { continue; }
            if (!"DISTRIBUTED".equals(SyntheticPartitioning.mode(plan.executionMode()))) continue;
            String jobId = row.get("id");
            Integer failed = jdbc.queryForObject("SELECT COUNT(*) FROM synthetic_job_partitions WHERE job_id = ? AND status = 'FAILED'",
                    Integer.class, jobId);
            if (failed != null && failed > 0) {
                Instant now = Instant.now();
                jdbc.update("UPDATE synthetic_generation_jobs SET status = 'FAILED', stage = 'Failed', " +
                                "message = 'One or more distributed partitions failed', error = 'One or more distributed partitions failed', " +
                                "finished_at = ?, updated_at = ? WHERE id = ?",
                        ts(now), ts(now), jobId);
                releaseTargetLock(targetLockKey(plan));
                continue;
            }
            List<Integer> openWaves = jdbc.query("SELECT DISTINCT dependency_wave FROM synthetic_job_partitions " +
                            "WHERE job_id = ? AND status <> 'COMPLETED' ORDER BY dependency_wave",
                    (rs, rowNum) -> rs.getInt(1), jobId);
            if (openWaves.isEmpty()) {
                Instant now = Instant.now();
                jdbc.update("UPDATE synthetic_generation_jobs SET status = 'COMPLETED', percent = 100, stage = 'Completed', " +
                                "message = 'Distributed generation completed', finished_at = COALESCE(finished_at, ?), updated_at = ? WHERE id = ?",
                        ts(now), ts(now), jobId);
                releaseTargetLock(targetLockKey(plan));
                continue;
            }
            int wave = openWaves.get(0);
            Integer earlier = jdbc.queryForObject("SELECT COUNT(*) FROM synthetic_job_partitions WHERE job_id = ? " +
                    "AND dependency_wave < ? AND status <> 'COMPLETED'", Integer.class, jobId, wave);
            if (earlier == null || earlier == 0) {
                jdbc.update("UPDATE synthetic_job_partitions SET status = 'QUEUED', updated_at = ? WHERE job_id = ? " +
                                "AND dependency_wave = ? AND status = 'BLOCKED'",
                        ts(Instant.now()), jobId, wave);
            }
        }
    }

    private Map<String, Object> dbLoadStreaming(GenPlan plan, List<GenTable> ordered, long seed, long maxRows,
                                                Set<String> referenced, Map<String, List<String>> existingFk,
                                                Map<String, Set<String>> uniqueCols,
                                                Map<String, List<SyntheticConstraintRules.Rule>> constraintRules,
                                                List<Map<String, Object>> summary, ProgressSink progress) {
        if (plan.targetDataSourceId() == null) throw ApiException.bad("DB receiver needs a target data source");
        DataSourceEntity ds = dataSources.get(plan.targetDataSourceId());
        String prep = plan.prepMode() == null ? null : plan.prepMode().trim().toUpperCase(Locale.ROOT);
        LoadPlan load = loadPlan(plan);
        boolean drop = "DROP_RECREATE".equals(prep) || Boolean.TRUE.equals(plan.dropTable());
        boolean create = Boolean.TRUE.equals(plan.createTable()) || drop;
        long totalRows = totalRows(ordered, maxRows);
        List<Map<String, Object>> validation = new ArrayList<>();
        List<String> rangeWarnings = new ArrayList<>();
        List<Map<String, Object>> nativeLoadResults = new ArrayList<>();
        Map<String, Object> rejectSummary = null;
        progress.update(6, "Streaming mode", "Streaming " + totalRows + " DB rows without holding them in heap");

        try (Connection out = connections.openForBulk(ds)) {
            String schema = DataSourceService.normalizeSchema(out, plan.targetSchema());
            SqlDialect dialect = SqlDialect.fromConnection(out);
            out.setAutoCommit(false);

            Set<String> existing = new HashSet<>();
            for (GenTable t : ordered) if (!columnTypes(out, schema, t.name()).isEmpty()) existing.add(t.name().toLowerCase(Locale.ROOT));
            if (!drop) {
                validateTargetGeneratorCompatibility(out, schema, ordered, table -> rowCount(table, maxRows));
            }

            List<GenTable> childFirst = new ArrayList<>(ordered);
            Collections.reverse(childFirst);

            int prepOps = 0;
            if (drop) prepOps += existing.size();
            if (create) prepOps += ordered.size();
            if (!drop && load.clearsTarget()) {
                for (GenTable t : childFirst)
                    if (existing.contains(t.name().toLowerCase(Locale.ROOT))) prepOps++;
            }
            int prepTotal = Math.max(1, prepOps);
            int[] prepDone = {0};

            if (drop) {
                for (GenTable t : childFirst)
                    if (existing.contains(t.name().toLowerCase(Locale.ROOT))) {
                        execQuiet(out, "DROP TABLE " + q(schema, t.name()));
                        progress.update(percent(8, 20, ++prepDone[0], prepTotal), "Prepare target", "Dropped " + t.name());
                    }
                existing.clear();
            }
            if (create) for (GenTable t : ordered) {
                execQuiet(out, createDdl(t.name(), schema, safe(t.columns())));
                progress.update(percent(8, 20, ++prepDone[0], prepTotal), "Prepare target", "Created or verified " + t.name());
            }
            if (drop) {
                validateTargetGeneratorCompatibility(out, schema, ordered, table -> rowCount(table, maxRows));
            }

            if (!drop && load.clearsTarget()) {
                String clearPrep = "TRUNCATE".equals(load.targetPrep()) ? "TRUNCATE_CASCADE" : "DELETE";
                String stage = "TRUNCATE_CASCADE".equals(clearPrep) ? "Truncate target" : "Delete target rows";
                List<GenTable> toClear = childFirst.stream()
                        .filter(t -> existing.contains(t.name().toLowerCase(Locale.ROOT)))
                        .toList();
                if ("TRUNCATE_CASCADE".equals(clearPrep) && dialect.supportsMultiTableTruncate()) {
                    clearTables(out, dialect, schema, toClear, clearPrep);
                    prepDone[0] += toClear.size();
                    progress.update(percent(8, 20, prepDone[0], prepTotal), stage,
                            stage + " completed for " + toClear.size() + " table(s)");
                } else {
                    for (GenTable t : toClear) {
                        clearTable(out, dialect, schema, t.name(), clearPrep);
                        progress.update(percent(8, 20, ++prepDone[0], prepTotal), stage,
                                stage + " completed for " + t.name());
                    }
                }
            }
            if (prepOps == 0) progress.update(20, "Prepare target", "Target ready");

            if (load.truncateOnly()) {
                progress.update(96, "Target cleared", "Target tables cleared");
                out.commit();
                return Map.of("receiver", "DB", "tables", summary, "loadAction", load.action(), "streamed", true);
            }

            Map<String, ValuePool> pools = new HashMap<>();
            // streaming context: bounded parent indexes + per-table run state so cardinality, composite FKs and
            // cross-table LOOKUP work above the in-memory threshold (previously these were in-memory-only)
            StreamCtx ctx = new StreamCtx(pools, referenced, compositeFkGroups(plan), lookupTargetKeys(ordered), uniqueCols, constraintRules, seed);
            // seed FK pools with existing parent keys already in the target (RI to existing data)
            if (existingFk != null) existingFk.forEach((k, v) -> {
                if (ctx.indexedKeys.contains(k)) {   // LOOKUP target: keep keys (rows unknown for pre-existing data)
                    IndexPool ip = ctx.rowIndex.computeIfAbsent(k, kk -> new IndexPool(Objects.hash(kk, seed)));
                    v.forEach(val -> ip.add(val, null));
                } else {
                    ValuePool p = pools.computeIfAbsent(k, kk -> new ValuePool(Objects.hash(kk, "existing")));
                    v.forEach(p::add);
                }
            });
            RejectCollector rejects = new RejectCollector(load.continueOnError(), load.maxRejects());
            int tableIndex = 0;
            int tableCount = Math.max(1, ordered.size());
            long streamedBefore = 0;
            for (GenTable t : ordered) {
                progress.checkCancelled();
                long rows = rowCount(t, maxRows);
                validateApiRowLimit(t, rows);
                String table = t.name();
                Map<String, Integer> types = columnTypes(out, schema, table);
                if (types.isEmpty())
                    throw ApiException.bad("Target table " + table + " does not exist. Enable 'Create missing tables' to build it from your plan.");
                Map<String, Integer> sizes = columnSizes(out, schema, table);
                Map<String, Integer> scales = columnScales(out, schema, table);
                for (SyntheticRangeChecks.RangeIssue issue : SyntheticRangeChecks.check(t, rows, types, sizes, scales)) {
                    if (issue.fatal()) throw ApiException.bad(issue.message());
                    rangeWarnings.add(issue.message());
                    progress.update(22, "Range check", issue.message());
                }
                Random rng = new Random(seed * 1000003L + (tableIndex + 1));
                int from = 22 + (tableIndex * 74 / tableCount);
                int to = 22 + ((tableIndex + 1) * 74 / tableCount);
                switch (load.action()) {
                    case "UPDATE" -> streamUpdateRows(out, schema, t, rows, rng, ctx, types, sizes, scales, load, progress, from, to, streamedBefore, totalRows);
                    case "INSERT_UPDATE" -> streamInsertUpdateRows(out, schema, t, rows, rng, ctx, types, sizes, scales, load, progress, from, to, streamedBefore, totalRows);
                    default -> {
                        if (load.fastLoad() && isPostgres(out))   // fastest path: Postgres COPY (no per-row reject capture)
                            copyInsertRows(out, schema, t, rows, rng, ctx, types, sizes, scales, load, progress, from, to, streamedBefore, totalRows);
                        else if (load.fastLoad() && nativeExternalLoaderAvailable(ds, dialect, load)) {
                            NativeLoadResult nativeResult = nativeFileInsertRows(out, ds, schema, t, rows, rng, ctx,
                                    types, sizes, load, progress, from, to, streamedBefore, totalRows);
                            nativeLoadResults.add(nativeResult(nativeResult));
                            if (!nativeResult.success()) {
                                throw ApiException.bad("Native loader failed for " + t.name() + ": " + nativeResult.message());
                            }
                        }
                        else if (load.fastLoad() && dialect.supportsMultiRowInsert())   // portable bulk: multi-row VALUES
                            multiRowInsertRows(out, schema, t, rows, rng, ctx, types, sizes, scales, dialect, load, rejects, progress, from, to, streamedBefore, totalRows);
                        else                                       // Oracle/GENERIC (or fast load off): JDBC array batching
                            streamInsertRows(out, schema, t, rows, rng, ctx, types, sizes, scales, load, rejects, progress, from, to, streamedBefore, totalRows);
                    }
                }
                streamedBefore += rows;
                tableIndex++;
            }
            progress.update(98, "Commit", "Committing target load");
            out.commit();
            progress.update(99, "Validate", "Validating loaded data");
            try { validation = validateLoad(out, schema, ordered, false); } catch (Exception ignore) { }   // shallow: row counts only (volume)
            rejectSummary = rejectSummary(rejects);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.bad("DB streaming load failed: " + sqlDetail(e));
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("receiver", "DB");
        result.put("tables", summary);
        result.put("streamed", true);
        if (!validation.isEmpty()) result.put("validation", validation);
        if (!rangeWarnings.isEmpty()) result.put("rangeWarnings", rangeWarnings);
        if (!nativeLoadResults.isEmpty()) result.put("nativeLoads", nativeLoadResults);
        if (rejectSummary != null) result.put("rejects", rejectSummary);
        return result;
    }

    private boolean nativeExternalLoaderAvailable(DataSourceEntity ds, SqlDialect dialect, LoadPlan load) {
        if (nativeLoaders == null || ds == null || load == null || load.continueOnError()) return false;
        if (!Set.of("INSERT", "REPLACE").contains(load.action())) return false;
        if (dialect == SqlDialect.POSTGRES || dialect == SqlDialect.H2 || dialect == SqlDialect.GENERIC) return false;
        NativeLoadStrategy strategy = nativeLoaders.strategyFor(ds);
        return strategy.nativeAvailable() && !"JDBC_MULTI_ROW".equals(strategy.strategy()) && !"JDBC_BATCH".equals(strategy.strategy());
    }

    private NativeLoadResult nativeFileInsertRows(Connection out, DataSourceEntity ds, String schema, GenTable t,
                                                  long rows, Random rng, StreamCtx ctx,
                                                  Map<String, Integer> types, Map<String, Integer> sizes,
                                                  LoadPlan load, ProgressSink progress, int startPct, int endPct,
                                                  long rowsDoneBeforeTable, long rowsTotal) throws Exception {
        List<GenColumn> allCols = safe(t.columns());
        Map<String, BiFunction<Long, Random, String>> gens = compiledGenerators(allCols);
        ctx.newTable(t, rows);
        Set<String> auto = autoIncrementColumns(out, schema, t.name());
        Set<String> generated = generatedColumns(out, schema, t.name());
        List<GenColumn> cols = insertableColumns(t, types, auto, generated, ctx.referenced);
        if (cols.isEmpty()) throw ApiException.bad("None of the generated columns match target table " + t.name() + ".");
        int[] jdbc = cols.stream().mapToInt(c -> types.getOrDefault(c.name().toLowerCase(Locale.ROOT), Types.VARCHAR)).toArray();
        int[] len = cols.stream().mapToInt(c -> sizes.getOrDefault(c.name().toLowerCase(Locale.ROOT), 0)).toArray();
        Path dir = Files.createTempDirectory("forgetdm-native-load-");
        Path dataFile = dir.resolve(t.name() + ".tsv");
        long done = 0;
        progress.update(startPct, "Stage native load", "Writing native load file for " + t.name());
        progress.updateRows(startPct, "Stage native load", "Writing native load file for " + t.name(),
                t.name(), 0, rows, rowsDoneBeforeTable, rowsTotal);
        try (var writer = Files.newBufferedWriter(dataFile, StandardCharsets.UTF_8)) {
            for (long i = 1; i <= rows; i++) {
                LinkedHashMap<String, String> row = streamRow(t, allCols, gens, i, rng, ctx);
                for (int c = 0; c < cols.size(); c++) {
                    if (c > 0) writer.write('\t');
                    String v = fit(row.get(cols.get(c).name()), jdbc[c], len[c]);
                    writer.write(nativeField(v));
                }
                writer.write('\n');
                done++;
                if (done % Math.max(1000, load.batchSize()) == 0 || done == rows) {
                    progress.updateRows(percent(startPct, Math.max(startPct, endPct - 5), done, rows),
                            "Stage native load", "Staged " + done + " of " + rows + " rows for " + t.name(),
                            t.name(), done, rows, rowsDoneBeforeTable + done, rowsTotal);
                }
            }
        }
        progress.update(Math.max(startPct, endPct - 4), "Native load", "Running native loader for " + t.name());
        out.commit(); // external native clients use their own connection and must see committed target prep/DDL.
        NativeLoadRequest request = new NativeLoadRequest(ds, schema, t.name(), cols.stream().map(GenColumn::name).toList(),
                dataFile, "\t", false, load.action(), Map.of("batchSize", String.valueOf(load.batchSize())));
        NativeLoadResult result = nativeLoaders.execute(request);
        progress.updateRows(endPct, "Native load", result.success()
                        ? "Native load completed for " + t.name()
                        : "Native load failed for " + t.name(),
                t.name(), rows, rows, rowsDoneBeforeTable + rows, rowsTotal);
        if (!NativeLoadSupportKeepFiles()) deleteNativeWorkDir(dir);
        return result;
    }

    private static String nativeField(String value) {
        if (value == null) return "";
        return value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    private static boolean NativeLoadSupportKeepFiles() {
        String v = System.getenv("FORGETDM_NATIVE_LOAD_KEEP_FILES");
        if (v == null) return false;
        String s = v.trim().toLowerCase(Locale.ROOT);
        return s.equals("true") || s.equals("1") || s.equals("yes");
    }

    private static void deleteNativeWorkDir(Path dir) {
        if (dir == null) return;
        try (var files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignore) { }
            });
        } catch (Exception ignore) { }
    }

    private Map<String, Object> nativeResult(NativeLoadResult result) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("strategy", result.strategy());
        out.put("engine", result.engine());
        out.put("nativeUsed", result.nativeUsed());
        out.put("success", result.success());
        out.put("status", result.status());
        out.put("exitCode", result.exitCode());
        out.put("message", result.message());
        out.put("command", result.redactedCommand());
        out.put("startedAt", result.startedAt());
        out.put("finishedAt", result.finishedAt());
        out.put("details", result.details());
        return out;
    }

    private record LoadPlan(String action, String targetPrep, List<String> keyColumns, int batchSize,
                            int commitEveryRows, boolean continueOnError, int maxRejects, boolean fastLoad) {
        boolean truncateOnly() { return "TRUNCATE_ONLY".equals(action); }
        boolean needsKeys() { return "UPDATE".equals(action) || "INSERT_UPDATE".equals(action); }
        boolean clearsTarget() { return truncateOnly() || !"NONE".equals(targetPrep); }
    }

    private LoadPlan loadPlan(GenPlan plan) {
        String legacy = plan.prepMode() == null ? "" : plan.prepMode().trim().toUpperCase(Locale.ROOT);
        String action = plan.loadAction();
        if (action == null || action.isBlank()) {
            action = switch (legacy) {
                case "DELETE", "TRUNCATE_CASCADE", "DROP_RECREATE" -> "REPLACE";
                default -> "INSERT";
            };
        }
        action = normalizeSyntheticLoadAction(action);

        String prep = plan.targetPrep();
        if (prep == null || prep.isBlank()) {
            if ("TRUNCATE_CASCADE".equals(legacy)) prep = "TRUNCATE";
            else if ("DELETE".equals(legacy) || "REPLACE".equals(action)) prep = "DELETE";
            else if ("TRUNCATE_ONLY".equals(action)) prep = "TRUNCATE";
            else prep = "NONE";
        }
        prep = normalizeSyntheticTargetPrep(prep);
        if ("TRUNCATE_ONLY".equals(action)) prep = "TRUNCATE";

        int batch = plan.batchSize() == null ? 5000 : plan.batchSize();
        batch = batch <= 0 ? 5000 : Math.min(batch, 50_000);

        List<String> keys = new ArrayList<>();
        for (String key : safe(plan.keyColumns()))
            if (key != null && !key.isBlank()) keys.add(key.trim());

        int commitEvery = plan.commitEveryRows() == null ? 0 : Math.max(0, plan.commitEveryRows());
        boolean continueOnError = Boolean.TRUE.equals(plan.continueOnError());
        int maxRejects = plan.maxRejects() == null ? 1000 : Math.max(0, plan.maxRejects());
        boolean fastLoad = Boolean.TRUE.equals(plan.fastLoad());
        return new LoadPlan(action, prep, keys, batch, commitEvery, continueOnError, maxRejects, fastLoad);
    }

    private String targetKind(GenPlan plan) {
        if (plan == null || plan.targetDataSourceId() == null) return "";
        try {
            DataSourceEntity ds = dataSources.get(plan.targetDataSourceId());
            return ds.getKind() == null ? "" : ds.getKind().trim().toUpperCase(Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    private static String writeMode(String receiver, LoadPlan load, boolean streaming, String targetKind) {
        if (!"DB".equals(receiver)) return receiver;
        if (load == null) return "DB";
        if (load.truncateOnly()) return "TRUNCATE_ONLY";
        String action = load.action();
        if ("UPDATE".equals(action)) return streaming ? "STREAMING_UPDATE" : "BATCH_UPDATE";
        if ("INSERT_UPDATE".equals(action)) return streaming ? "STREAMING_UPSERT" : "BATCH_UPSERT";
        if (streaming && load.fastLoad() && "POSTGRES".equalsIgnoreCase(targetKind)
                && ("INSERT".equals(action) || "REPLACE".equals(action))) {
            return "POSTGRES_COPY_FAST_LOAD";
        }
        if (streaming) return "STREAMING_INSERT";
        return "BATCH_INSERT";
    }

    /** Collects rows the database rejected (continue-on-error), with a hard ceiling that fails the load. */
    private static final class RejectCollector {
        private final boolean continueOnError;
        private final int max;
        private final List<Map<String, Object>> samples = new ArrayList<>();
        private long count;
        RejectCollector(boolean continueOnError, int max) { this.continueOnError = continueOnError; this.max = max; }
        boolean continueOnError() { return continueOnError; }
        long count() { return count; }
        List<Map<String, Object>> samples() { return samples; }
        void add(String table, Map<String, String> row, String error) {
            count++;
            if (samples.size() < 50)
                samples.add(Map.of("table", table, "error", error == null ? "" : error, "row", new LinkedHashMap<>(row)));
            if (count > max)
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Reject threshold exceeded: " + count + " rows rejected (limit " + max + ")");
        }
    }

    private static String normalizeSyntheticLoadAction(String action) {
        String a = action == null ? "INSERT" : action.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (a) {
            case "APPEND", "INSERT_ONLY" -> "INSERT";
            case "LOAD_REPLACE", "RELOAD", "REPLACE_ALL" -> "REPLACE";
            case "MERGE", "UPSERT", "INSERT_UPDATE", "INSERT_OR_UPDATE" -> "INSERT_UPDATE";
            case "TRUNCATE", "TRUNCATE_TABLE", "CLEAR_ONLY" -> "TRUNCATE_ONLY";
            case "REPLACE", "INSERT", "UPDATE", "TRUNCATE_ONLY" -> a;
            default -> throw ApiException.bad("loadAction must be REPLACE, INSERT, UPDATE, INSERT_UPDATE, or TRUNCATE_ONLY");
        };
    }

    private static String normalizeSyntheticTargetPrep(String prep) {
        String p = prep == null ? "NONE" : prep.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (p) {
            case "", "NONE", "NO_PREP" -> "NONE";
            case "DELETE", "DELETE_ROWS", "CLEAR_ROWS" -> "DELETE";
            case "TRUNCATE", "TRUNCATE_TABLE", "TRUNCATE_CASCADE" -> "TRUNCATE";
            default -> throw ApiException.bad("targetPrep must be NONE, DELETE, or TRUNCATE");
        };
    }

    private void insertRows(Connection out, String schema, GenTable t,
                            List<LinkedHashMap<String, String>> rows, Map<String, Integer> types,
                            Map<String, Integer> sizes, int batchSize) throws SQLException {
        insertRows(out, schema, t, rows, types, sizes, Map.of(), batchSize, Set.of(), NO_PROGRESS, 65, 95,
                0, rows == null ? 0 : rows.size());
    }

    private void insertRows(Connection out, String schema, GenTable t,
                            List<LinkedHashMap<String, String>> rows, Map<String, Integer> types,
                            Map<String, Integer> sizes, Map<String, Integer> scales, int batchSize, Set<String> referenced, ProgressSink progress,
                            int startPct, int endPct, long rowsDoneBeforeTable, long rowsTotal) throws SQLException {
        if (rows == null || rows.isEmpty()) return;
        Set<String> auto = autoIncrementColumns(out, schema, t.name());
        Set<String> generated = generatedColumns(out, schema, t.name());
        List<GenColumn> cols = insertableColumns(t, types, auto, generated, referenced);
        if (cols.isEmpty())
            throw ApiException.bad("None of the generated columns match target table " + t.name()
                    + " (its columns are " + types.keySet() + "). Use 'Add all' from this schema to match the real columns,"
                    + " or enable 'Drop & recreate first' to build the table from your plan.");
        String sql = insertSql(out, schema, t.name(), cols, auto);
        int[] jdbc = cols.stream().mapToInt(c -> types.getOrDefault(c.name().toLowerCase(Locale.ROOT), Types.VARCHAR)).toArray();
        int[] len = cols.stream().mapToInt(c -> sizes.getOrDefault(c.name().toLowerCase(Locale.ROOT), 0)).toArray();
        int[] scale = cols.stream().mapToInt(c -> scales.getOrDefault(c.name().toLowerCase(Locale.ROOT), 0)).toArray();
        int batch = Math.max(1, batchSize), pending = 0;
        long done = 0, total = rows.size();
        progress.update(startPct, "Load rows", "Loading " + total + " rows into " + t.name());
        progress.updateRows(startPct, "Load rows", "Loading " + total + " rows into " + t.name(),
                t.name(), 0, total, rowsDoneBeforeTable, rowsTotal);
        try (PreparedStatement ps = out.prepareStatement(sql)) {
            progress.activeStatement(ps);
            for (LinkedHashMap<String, String> row : rows) {
                for (int i = 0; i < cols.size(); i++)
                    bind(ps, i + 1, fit(row.get(cols.get(i).name()), jdbc[i], len[i]), jdbc[i], cols.get(i).name(), len[i], scale[i]);
                ps.addBatch();
                if (++pending >= batch) {
                    ps.executeBatch();
                    done += pending;
                    progress.update(percent(startPct, endPct, done, total),
                            "Load rows", "Loaded " + done + " of " + total + " rows into " + t.name());
                    progress.updateRows(percent(startPct, endPct, done, total),
                            "Load rows", "Loaded " + done + " of " + total + " rows into " + t.name(),
                            t.name(), done, total, rowsDoneBeforeTable + done, rowsTotal);
                    pending = 0;
                }
            }
            if (pending > 0) {
                ps.executeBatch();
                done += pending;
                progress.update(percent(startPct, endPct, done, total),
                        "Load rows", "Loaded " + done + " of " + total + " rows into " + t.name());
                progress.updateRows(percent(startPct, endPct, done, total),
                        "Load rows", "Loaded " + done + " of " + total + " rows into " + t.name(),
                        t.name(), done, total, rowsDoneBeforeTable + done, rowsTotal);
            }
        } finally {
            progress.activeStatement(null);
        }
    }

    private void updateRows(Connection out, String schema, GenTable t,
                            List<LinkedHashMap<String, String>> rows, Map<String, Integer> types,
                            Map<String, Integer> sizes, LoadPlan load) throws SQLException {
        updateRows(out, schema, t, rows, types, sizes, Map.of(), load, NO_PROGRESS, 65, 95,
                0, rows == null ? 0 : rows.size());
    }

    private void updateRows(Connection out, String schema, GenTable t,
                            List<LinkedHashMap<String, String>> rows, Map<String, Integer> types,
                            Map<String, Integer> sizes, Map<String, Integer> scales, LoadPlan load, ProgressSink progress,
                            int startPct, int endPct, long rowsDoneBeforeTable, long rowsTotal) throws SQLException {
        if (rows == null || rows.isEmpty()) return;
        List<GenColumn> cols = writableColumns(t, types);
        List<GenColumn> keys = keyColumnsFor(out, schema, t, cols, load);
        List<GenColumn> nonKeys = nonKeyColumns(cols, keys);
        if (nonKeys.isEmpty())
            throw ApiException.bad("Update-style load needs at least one non-key column for " + t.name());

        String sql = updateSql(schema, t.name(), nonKeys, keys);
        int pending = 0;
        long done = 0, total = rows.size();
        progress.update(startPct, "Update rows", "Updating " + total + " rows in " + t.name());
        progress.updateRows(startPct, "Update rows", "Updating " + total + " rows in " + t.name(),
                t.name(), 0, total, rowsDoneBeforeTable, rowsTotal);
        try (PreparedStatement ps = out.prepareStatement(sql)) {
            progress.activeStatement(ps);
            for (LinkedHashMap<String, String> row : rows) {
                bindUpdate(ps, nonKeys, keys, row, types, sizes, scales);
                ps.addBatch();
                if (++pending >= load.batchSize()) {
                    ps.executeBatch();
                    done += pending;
                    progress.update(percent(startPct, endPct, done, total),
                            "Update rows", "Updated " + done + " of " + total + " rows in " + t.name());
                    progress.updateRows(percent(startPct, endPct, done, total),
                            "Update rows", "Updated " + done + " of " + total + " rows in " + t.name(),
                            t.name(), done, total, rowsDoneBeforeTable + done, rowsTotal);
                    pending = 0;
                }
            }
            if (pending > 0) {
                ps.executeBatch();
                done += pending;
                progress.update(percent(startPct, endPct, done, total),
                        "Update rows", "Updated " + done + " of " + total + " rows in " + t.name());
                progress.updateRows(percent(startPct, endPct, done, total),
                        "Update rows", "Updated " + done + " of " + total + " rows in " + t.name(),
                        t.name(), done, total, rowsDoneBeforeTable + done, rowsTotal);
            }
        } finally {
            progress.activeStatement(null);
        }
    }

    private void insertUpdateRows(Connection out, String schema, GenTable t,
                                  List<LinkedHashMap<String, String>> rows, Map<String, Integer> types,
                                  Map<String, Integer> sizes, LoadPlan load) throws SQLException {
        insertUpdateRows(out, schema, t, rows, types, sizes, Map.of(), load, NO_PROGRESS, 65, 95,
                0, rows == null ? 0 : rows.size());
    }

    private void insertUpdateRows(Connection out, String schema, GenTable t,
                                  List<LinkedHashMap<String, String>> rows, Map<String, Integer> types,
                                  Map<String, Integer> sizes, Map<String, Integer> scales, LoadPlan load, ProgressSink progress,
                                  int startPct, int endPct, long rowsDoneBeforeTable, long rowsTotal) throws SQLException {
        if (rows == null || rows.isEmpty()) return;
        List<GenColumn> cols = writableColumns(t, types);
        List<GenColumn> keys = keyColumnsFor(out, schema, t, cols, load);
        List<GenColumn> nonKeys = nonKeyColumns(cols, keys);
        if (nonKeys.isEmpty())
            throw ApiException.bad("Insert-update load needs at least one non-key column for " + t.name());

        String updateSql = updateSql(schema, t.name(), nonKeys, keys);
        String insertSql = insertSql(out, schema, t.name(), cols, autoIncrementColumns(out, schema, t.name()));
        int[] jdbc = cols.stream().mapToInt(c -> types.getOrDefault(c.name().toLowerCase(Locale.ROOT), Types.VARCHAR)).toArray();
        int[] len = cols.stream().mapToInt(c -> sizes.getOrDefault(c.name().toLowerCase(Locale.ROOT), 0)).toArray();
        int[] scale = cols.stream().mapToInt(c -> scales.getOrDefault(c.name().toLowerCase(Locale.ROOT), 0)).toArray();
        int pendingInserts = 0;
        int sinceProgress = 0;
        long done = 0, total = rows.size();
        progress.update(startPct, "Upsert rows", "Upserting " + total + " rows into " + t.name());
        progress.updateRows(startPct, "Upsert rows", "Upserting " + total + " rows into " + t.name(),
                t.name(), 0, total, rowsDoneBeforeTable, rowsTotal);
        try (PreparedStatement upd = out.prepareStatement(updateSql);
             PreparedStatement ins = out.prepareStatement(insertSql)) {
            progress.activeStatement(upd);
            for (LinkedHashMap<String, String> row : rows) {
                bindUpdate(upd, nonKeys, keys, row, types, sizes, scales);
                if (upd.executeUpdate() == 0) {
                    progress.activeStatement(ins);
                    for (int i = 0; i < cols.size(); i++)
                        bind(ins, i + 1, fit(row.get(cols.get(i).name()), jdbc[i], len[i]), jdbc[i], cols.get(i).name(), len[i], scale[i]);
                    ins.addBatch();
                    if (++pendingInserts >= load.batchSize()) { ins.executeBatch(); pendingInserts = 0; }
                    progress.activeStatement(upd);
                }
                done++;
                if (++sinceProgress >= load.batchSize()) {
                    progress.update(percent(startPct, endPct, done, total),
                            "Upsert rows", "Upserted " + done + " of " + total + " rows into " + t.name());
                    progress.updateRows(percent(startPct, endPct, done, total),
                            "Upsert rows", "Upserted " + done + " of " + total + " rows into " + t.name(),
                            t.name(), done, total, rowsDoneBeforeTable + done, rowsTotal);
                    sinceProgress = 0;
                }
            }
            if (pendingInserts > 0) {
                progress.activeStatement(ins);
                ins.executeBatch();
                progress.activeStatement(upd);
            }
            progress.update(endPct, "Upsert rows", "Upserted " + done + " of " + total + " rows into " + t.name());
            progress.updateRows(endPct, "Upsert rows", "Upserted " + done + " of " + total + " rows into " + t.name(),
                    t.name(), done, total, rowsDoneBeforeTable + done, rowsTotal);
        } finally {
            progress.activeStatement(null);
        }
    }

    /**
     * Emits parent keys for a cardinality-controlled FK while keeping each retained parent within fkMin/fkMax.
     * It stores counts per parent, not per child row, so it stays bounded for streaming jobs.
     */
    private static final class CardinalityCursor {
        private final List<String> keys;
        private final int min;
        private final int max;
        private final int[] counts;
        private final List<Integer> minOrder;
        private final List<Integer> extraOrder = new ArrayList<>();
        private int minRound;
        private int minPos;
        private int extraPos;
        private long emitted;

        CardinalityCursor(List<String> parentKeys, long childRows, GenColumn column, Random rng,
                          String tableName, String fkKey) {
            this.keys = parentKeys == null ? List.of() : new ArrayList<>(parentKeys);
            this.min = Math.max(0, column.fkMin() == null ? 0 : column.fkMin());
            int requestedMax = column.fkMax() == null || column.fkMax() <= 0 ? Integer.MAX_VALUE : column.fkMax();
            this.max = Math.max(min, requestedMax);
            this.counts = new int[keys.size()];
            this.minOrder = new ArrayList<>();
            for (int i = 0; i < keys.size(); i++) minOrder.add(i);
            Collections.shuffle(minOrder, rng);
            validateFeasible(childRows, tableName, column.name(), fkKey);
        }

        String next(Random rng) {
            if (keys.isEmpty()) return null;
            if (minRound < min) {
                if (minPos >= minOrder.size()) {
                    minPos = 0;
                    minRound++;
                    if (minRound < min) Collections.shuffle(minOrder, rng);
                }
                if (minRound < min) {
                    int idx = minOrder.get(minPos++);
                    counts[idx]++;
                    emitted++;
                    return keys.get(idx);
                }
            }
            int idx = nextExtraIndex(rng);
            counts[idx]++;
            emitted++;
            return keys.get(idx);
        }

        private int nextExtraIndex(Random rng) {
            while (true) {
                if (extraPos >= extraOrder.size()) {
                    extraOrder.clear();
                    for (int i = 0; i < counts.length; i++) if (counts[i] < max) extraOrder.add(i);
                    if (extraOrder.isEmpty()) return rng.nextInt(keys.size());
                    Collections.shuffle(extraOrder, rng);
                    extraPos = 0;
                }
                int idx = extraOrder.get(extraPos++);
                if (counts[idx] < max) return idx;
            }
        }

        private void validateFeasible(long childRows, String tableName, String columnName, String fkKey) {
            long parents = keys.size();
            long minRows = parents * (long) min;
            if (childRows < minRows) {
                throw ApiException.bad("Cardinality for " + tableName + "." + columnName + " -> " + fkKey
                        + " requires at least " + minRows + " child rows for " + parents
                        + " parent keys (fkMin=" + min + "), but plan has " + childRows + ".");
            }
            if (max != Integer.MAX_VALUE) {
                long maxRows = parents * (long) max;
                if (childRows > maxRows) {
                    throw ApiException.bad("Cardinality for " + tableName + "." + columnName + " -> " + fkKey
                            + " allows at most " + maxRows + " child rows for " + parents
                            + " parent keys (fkMax=" + max + "), but plan has " + childRows + ".");
                }
            }
        }
    }

    private static final class ValuePool {
        private final List<String> values = new ArrayList<>();
        private final Random rng;
        private long seen;

        ValuePool(long seed) { this.rng = new Random(seed); }

        void add(String value) {
            if (value == null) return;
            seen++;
            if (values.size() < MAX_STREAMING_FK_POOL_VALUES) {
                values.add(value);
                return;
            }
            long slot = (long) (rng.nextDouble() * seen);
            if (slot < MAX_STREAMING_FK_POOL_VALUES) values.set((int) slot, value);
        }

        String sample(Random rowRng) {
            return values.isEmpty() ? null : values.get(rowRng.nextInt(values.size()));
        }
    }

    /**
     * Bounded parent-row index for the streaming path: keeps up to MAX_STREAMING_FK_POOL_VALUES parent keys
     * (reservoir-sampled) AND the parent rows behind them, so cross-table LOOKUP can resolve at scale. Children
     * draw their FK from {@link #sampleKey} so the drawn key is always present in {@link #rows}.
     */
    private static final class IndexPool {
        final List<String> keys = new ArrayList<>();
        final Map<String, LinkedHashMap<String, String>> rows = new HashMap<>();
        private final Random rng;
        private long seen;

        IndexPool(long seed) { this.rng = new Random(seed); }

        void add(String key, LinkedHashMap<String, String> row) {
            if (key == null) return;
            seen++;
            if (keys.size() < MAX_STREAMING_FK_POOL_VALUES) {
                keys.add(key);
                if (row != null) rows.putIfAbsent(key, row);
                return;
            }
            long slot = (long) (rng.nextDouble() * seen);
            if (slot < MAX_STREAMING_FK_POOL_VALUES) {
                String old = keys.get((int) slot);
                keys.set((int) slot, key);
                rows.remove(old);
                if (row != null) rows.putIfAbsent(key, row);
            }
        }

        String sampleKey(Random rowRng) { return keys.isEmpty() ? null : keys.get(rowRng.nextInt(keys.size())); }
    }

    /** Bounded parent-tuple pool for composite (multi-column) FKs on the streaming path. */
    private static final class TuplePool {
        private final List<String[]> tuples = new ArrayList<>();
        private final Random rng;
        private long seen;

        TuplePool(long seed) { this.rng = new Random(seed); }

        void add(String[] tuple) {
            if (tuple == null) return;
            seen++;
            if (tuples.size() < MAX_STREAMING_FK_POOL_VALUES) { tuples.add(tuple); return; }
            long slot = (long) (rng.nextDouble() * seen);
            if (slot < MAX_STREAMING_FK_POOL_VALUES) tuples.set((int) slot, tuple);
        }

        String[] sample(Random rowRng) { return tuples.isEmpty() ? null : tuples.get(rowRng.nextInt(tuples.size())); }
    }

    /**
     * Per-run streaming generation context. Holds the shared, memory-bounded parent indexes (single-column FK
     * value pools, composite-FK tuple pools, and LOOKUP row indexes) plus the per-table run state (PK
     * uniqueness sets and cardinality run counters). {@link #newTable} resets the per-table state and caches
     * this table's composite groups. This brings cardinality, composite FKs and cross-table LOOKUP — previously
     * in-memory-only — to the streaming (>500k row) path.
     */
    private static final class StreamCtx {
        final Map<String, ValuePool> pools;
        final Set<String> referenced;
        final Map<String, List<CompositeFk>> composite;
        final Set<String> indexedKeys;                       // FK targets followed by some LOOKUP → keep full rows
        final Map<String, Set<String>> uniqueByTable;        // table -> single-column UNIQUE columns to enforce
        final Map<String, List<SyntheticConstraintRules.Rule>> constraintRulesByTable;
        final Map<String, IndexPool> rowIndex = new HashMap<>();
        final Map<String, TuplePool> tuplePools = new HashMap<>();
        final long seed;
        final boolean partitioned;
        long partitionRowStart = 1;
        long partitionTableRows;
        long curRows;
        // per-table run state (reset by newTable)
        UniqueGuards uniqueSets = new UniqueGuards(true, 1024);   // bounded (Bloom) uniqueness — can't OOM at scale
        Map<String, CardinalityCursor> cardCursors = new HashMap<>();
        List<CompositeFk> curMyGroups = List.of();           // composite groups where the current table is the child
        List<CompositeFk> curAsParent = List.of();           // composite groups whose parent is the current table
        Set<String> curCompositeCols = Set.of();
        Set<String> curUniqueCols = Set.of();                // UNIQUE columns to enforce for the current table
        List<SyntheticConstraintRules.Rule> curConstraintRules = List.of();

        StreamCtx(Map<String, ValuePool> pools, Set<String> referenced,
                  Map<String, List<CompositeFk>> composite, Set<String> indexedKeys,
                  Map<String, Set<String>> uniqueByTable,
                  Map<String, List<SyntheticConstraintRules.Rule>> constraintRulesByTable,
                   long seed) {
            this(pools, referenced, composite, indexedKeys, uniqueByTable, constraintRulesByTable, seed, false);
        }

        StreamCtx(Map<String, ValuePool> pools, Set<String> referenced,
                  Map<String, List<CompositeFk>> composite, Set<String> indexedKeys,
                  Map<String, Set<String>> uniqueByTable,
                  Map<String, List<SyntheticConstraintRules.Rule>> constraintRulesByTable,
                  long seed, boolean partitioned) {
            this.pools = pools;
            this.referenced = referenced;
            this.composite = composite == null ? Map.of() : composite;
            this.indexedKeys = indexedKeys == null ? Set.of() : indexedKeys;
            this.uniqueByTable = uniqueByTable == null ? Map.of() : uniqueByTable;
            this.constraintRulesByTable = constraintRulesByTable == null ? Map.of() : constraintRulesByTable;
            this.seed = seed;
            this.partitioned = partitioned;
        }

        void partitionRange(long rowStart, long tableRows) {
            this.partitionRowStart = Math.max(1, rowStart);
            this.partitionTableRows = Math.max(0, tableRows);
        }

        void newTable(GenTable t, long rows) {
            curRows = partitioned && partitionTableRows > 0 ? partitionTableRows : rows;
            uniqueSets = new UniqueGuards(true, rows);   // size the Bloom filters to this table's planned row count
            cardCursors = new HashMap<>();
            String tl = t.name().toLowerCase(Locale.ROOT);
            curMyGroups = composite.getOrDefault(tl, List.of());
            curUniqueCols = uniqueByTable.getOrDefault(tl, Set.of());
            curConstraintRules = constraintRulesByTable.getOrDefault(tl, List.of());
            Set<String> cc = new HashSet<>();
            for (CompositeFk g : curMyGroups) for (String c : g.childCols()) cc.add(c.toLowerCase(Locale.ROOT));
            curCompositeCols = cc;
            List<CompositeFk> ap = new ArrayList<>();
            for (List<CompositeFk> gs : composite.values())
                for (CompositeFk g : gs) if (g.parentTable().equalsIgnoreCase(t.name())) ap.add(g);
            curAsParent = ap;
        }

        /** Draw a parent key for a single-column FK — from the row index when LOOKUP-followed, else the value pool. */
        String sampleFk(String fkKey, Random rng) {
            if (indexedKeys.contains(fkKey)) {
                IndexPool ip = rowIndex.get(fkKey);
                return ip == null ? null : ip.sampleKey(rng);
            }
            ValuePool p = pools.get(fkKey);
            return p == null ? null : p.sample(rng);
        }

        String sampleCardinalityFk(GenTable table, GenColumn column, String fkKey, long globalRow, Random rng) {
            List<String> keys;
            if (indexedKeys.contains(fkKey)) {
                IndexPool ip = rowIndex.get(fkKey);
                keys = ip == null ? List.of() : ip.keys;
            } else {
                ValuePool p = pools.get(fkKey);
                keys = p == null ? List.of() : p.values;
            }
            if (keys.isEmpty()) return null;
            if (partitioned) {
                int min = column.fkMin() == null ? 0 : Math.max(0, column.fkMin());
                int max = column.fkMax() == null || column.fkMax() <= 0 ? Integer.MAX_VALUE : column.fkMax();
                validateCardinalityBounds(table.name(), column.name(), fkKey, curRows, keys.size(), min, max);
                long offset = Math.floorMod(SyntheticPartitioning.partitionSeed(seed, fkKey, 1), keys.size());
                int index = (int) Math.floorMod((globalRow - 1) + offset, keys.size());
                return keys.get(index);
            }
            return cardCursors.computeIfAbsent(column.name(),
                    k -> new CardinalityCursor(keys, curRows, column, rng, table.name(), fkKey)).next(rng);
        }

        /** Capture a referenced parent value (and its whole row when LOOKUP-followed) for children to draw. */
        void capture(String fkKey, String value, LinkedHashMap<String, String> row) {
            if (indexedKeys.contains(fkKey))
                rowIndex.computeIfAbsent(fkKey, k -> new IndexPool(Objects.hash(k, seed))).add(value, row);
            else
                pools.computeIfAbsent(fkKey, k -> new ValuePool(Objects.hash(k, seed))).add(value);
        }

        String[] sampleTuple(String tupleKey, Random rng) {
            TuplePool tp = tuplePools.get(tupleKey);
            return tp == null ? null : tp.sample(rng);
        }

        void captureTuple(String tupleKey, String[] tuple) {
            tuplePools.computeIfAbsent(tupleKey, k -> new TuplePool(Objects.hash(k, seed))).add(tuple);
        }

        /** A light view {key -> (keyValue -> parentRow)} over the row indexes, for {@code derivedValue} LOOKUP. */
        Map<String, Map<String, LinkedHashMap<String, String>>> rowIndexView() {
            Map<String, Map<String, LinkedHashMap<String, String>>> view = new HashMap<>();
            for (Map.Entry<String, IndexPool> e : rowIndex.entrySet()) view.put(e.getKey(), e.getValue().rows);
            return view;
        }
    }

    /** FK-target keys (table.col) that some LOOKUP column follows — these need full parent rows kept in streaming. */
    private Set<String> lookupTargetKeys(List<GenTable> ordered) {
        Set<String> out = new HashSet<>();
        for (GenTable t : ordered) {
            List<GenColumn> cols = safe(t.columns());
            for (GenColumn c : cols) {
                if (!"LOOKUP".equalsIgnoreCase(c.generator() == null ? "" : c.generator().trim())) continue;
                GenColumn fk = resolveLookupFk(c, cols);
                if (fk != null) out.add(key(fk.fkTable(), fk.fkColumn()));
            }
        }
        return out;
    }

    /** Pick the FK column a LOOKUP follows: param2 names it, else the table's single FK (ambiguous → none). */
    private static GenColumn resolveLookupFk(GenColumn c, List<GenColumn> cols) {
        String want = blankNull(c.param2());
        if (want != null) {
            for (GenColumn x : cols)
                if (x.name().equalsIgnoreCase(want) && notBlank(x.fkTable()) && notBlank(x.fkColumn())) return x;
            return null;
        }
        GenColumn fk = null;
        for (GenColumn x : cols)
            if (notBlank(x.fkTable()) && notBlank(x.fkColumn())) {
                if (fk != null) return null;   // ambiguous: more than one FK and none named
                fk = x;
            }
        return fk;
    }

    private void validateApiRowLimit(GenTable t, long rows) {
        boolean hasApi = false;
        for (GenColumn c : safe(t.columns())) if (isApi(c)) hasApi = true;
        if (hasApi && rows > 5000)
            throw ApiException.bad("Table '" + t.name() + "' uses an API generator (one HTTP call per row); "
                    + "cap API-backed tables at 5000 rows, or use a batch endpoint.");
    }

    private Map<String, BiFunction<Long, Random, String>> compiledGenerators(List<GenColumn> cols) {
        Map<String, BiFunction<Long, Random, String>> gens = new HashMap<>();
        for (GenColumn c : cols)
            if (!notBlank(c.fkTable()) && !isApi(c))
                gens.put(c.name(), Generators.of(blankOr(c.generator(), "SEQUENCE"), blankNull(c.param1()), blankNull(c.param2())));
        return gens;
    }

    private LinkedHashMap<String, String> streamRow(GenTable t, List<GenColumn> cols,
            Map<String, BiFunction<Long, Random, String>> gens, long rowNum, Random rng, StreamCtx ctx) {
        long globalRow = ctx.partitioned ? ctx.partitionRowStart + rowNum - 1 : rowNum;
        if (ctx.partitioned) rng = SyntheticPartitioning.rowRandom(ctx.seed, t.name(), globalRow);
        LinkedHashMap<String, String> row = new LinkedHashMap<>();
        String tl = t.name().toLowerCase(Locale.ROOT);
        boolean anyDerived = false;
        // 1) base columns (PK uniqueness + FK draw with cardinality). Composite-FK cols are filled in step 1b.
        for (GenColumn c : cols) {
            if (isDerived(c)) { row.put(c.name(), null); anyDerived = true; continue; }
            if (ctx.curCompositeCols.contains(c.name().toLowerCase(Locale.ROOT))) { row.put(c.name(), null); continue; }
            String val;
            if (notBlank(c.fkTable()) && notBlank(c.fkColumn())) {
                String fkKey = key(c.fkTable(), c.fkColumn());
                if (hasCardinality(c)) {
                    val = ctx.sampleCardinalityFk(t, c, fkKey, globalRow, rng);
                } else {
                    val = ctx.sampleFk(fkKey, rng);
                }
            } else if (isApi(c)) {
                val = apiValue(blankNull(c.param1()), blankNull(c.param2()), globalRow);
            } else {
                BiFunction<Long, Random, String> g = gens.get(c.name());
                val = g.apply(globalRow, rng);
                if (mustBeUnique(c, ctx.curUniqueCols) && !isInherentlyUnique(c)) {
                    val = ctx.partitioned ? globallyUniqueValue(c, val, globalRow)
                            : ensureUnique(ctx.uniqueSets.get(c.name()), val, g, rowNum, rng);
                }
            }
            row.put(c.name(), val);
        }
        // 1b) composite FK: draw ONE parent tuple per group and assign all its child columns together
        for (CompositeFk g : ctx.curMyGroups) {
            String[] tuple = ctx.sampleTuple(g.tupleKey(), rng);
            for (int ci = 0; ci < g.childCols().size(); ci++)
                row.put(matchKey(row, g.childCols().get(ci)), tuple == null ? null : tuple[ci]);
        }
        // 2) derived columns (two passes so they can chain); cross-table LOOKUP resolves via the bounded row index
        if (anyDerived) {
            Map<String, Map<String, LinkedHashMap<String, String>>> idxView = ctx.rowIndexView();
            for (int pass = 0; pass < 2; pass++)
                for (GenColumn c : cols) if (isDerived(c)) row.put(c.name(), derivedValue(c, row, rng, cols, idxView));
            // enforce single-column UNIQUE on derived columns too (before capture, so FK pools hold the final value)
            for (GenColumn c : cols)
                if (isDerived(c) && mustBeUnique(c, ctx.curUniqueCols))
                    row.put(c.name(), ctx.partitioned ? globallyUniqueValue(c, row.get(c.name()), globalRow)
                            : ensureUniqueValue(ctx.uniqueSets.get(c.name()), row.get(c.name()), rowNum));
        }
        SyntheticConstraintRules.apply(row, ctx.curConstraintRules, globalRow);
        // 3) capture referenced values into FK pools (+ whole rows for LOOKUP targets)
        for (GenColumn c : cols) {
            String k = tl + "." + c.name().toLowerCase(Locale.ROOT);
            if (ctx.referenced.contains(k)) ctx.capture(k, row.get(c.name()), row);
        }
        // 3b) when THIS table is a composite parent, capture its key tuples for children to draw
        for (CompositeFk g : ctx.curAsParent) {
            String[] tuple = new String[g.parentCols().size()];
            for (int ci = 0; ci < g.parentCols().size(); ci++) tuple[ci] = row.get(matchKey(row, g.parentCols().get(ci)));
            ctx.captureTuple(g.tupleKey(), tuple);
        }
        return row;
    }

    private void streamInsertRows(Connection out, String schema, GenTable t, long rows, Random rng,
                                  StreamCtx ctx,
                                  Map<String, Integer> types, Map<String, Integer> sizes, Map<String, Integer> scales,
                                  LoadPlan load, RejectCollector rejects,
                                  ProgressSink progress, int startPct, int endPct,
                                  long rowsDoneBeforeTable, long rowsTotal) throws SQLException {
        if (rows <= 0) return;
        List<GenColumn> allCols = safe(t.columns());
        Map<String, BiFunction<Long, Random, String>> gens = compiledGenerators(allCols);
        ctx.newTable(t, rows);   // reset per-table run state (PK uniqueness, cardinality) + cache composite groups
        Set<String> auto = autoIncrementColumns(out, schema, t.name());
        Set<String> generated = generatedColumns(out, schema, t.name());
        List<GenColumn> cols = insertableColumns(t, types, auto, generated, ctx.referenced);
        if (cols.isEmpty())
            throw ApiException.bad("None of the generated columns match target table " + t.name()
                    + " (its columns are " + types.keySet() + "). Use 'Add all' from this schema to match the real columns,"
                    + " or enable 'Drop & recreate first' to build the table from your plan.");
        String sql = insertSql(out, schema, t.name(), cols, auto);
        int[] jdbc = cols.stream().mapToInt(c -> types.getOrDefault(c.name().toLowerCase(Locale.ROOT), Types.VARCHAR)).toArray();
        int[] len = cols.stream().mapToInt(c -> sizes.getOrDefault(c.name().toLowerCase(Locale.ROOT), 0)).toArray();
        int[] scale = cols.stream().mapToInt(c -> scales.getOrDefault(c.name().toLowerCase(Locale.ROOT), 0)).toArray();
        int batch = Math.max(1, load.batchSize());
        int commitEvery = load.commitEveryRows();
        long done = 0, sinceCommit = 0;
        List<LinkedHashMap<String, String>> batchRows = new ArrayList<>();
        progress.update(startPct, "Load rows", "Streaming " + rows + " rows into " + t.name());
        progress.updateRows(startPct, "Load rows", "Streaming " + rows + " rows into " + t.name(),
                t.name(), 0, rows, rowsDoneBeforeTable, rowsTotal);
        try (PreparedStatement ps = out.prepareStatement(sql)) {
            progress.activeStatement(ps);
            for (long i = 1; i <= rows; i++) {
                LinkedHashMap<String, String> row = streamRow(t, allCols, gens, i, rng, ctx);
                for (int c = 0; c < cols.size(); c++)
                    bind(ps, c + 1, fit(row.get(cols.get(c).name()), jdbc[c], len[c]), jdbc[c], cols.get(c).name(), len[c], scale[c]);
                ps.addBatch();
                batchRows.add(row);
                if (batchRows.size() >= batch) {
                    done += flushInsertBatch(out, ps, cols, batchRows, jdbc, len, scale, load, rejects, t.name());
                    sinceCommit += batchRows.size();
                    batchRows.clear();
                    progress.updateRows(percent(startPct, endPct, done, rows),
                            "Load rows", "Loaded " + done + " of " + rows + " rows into " + t.name(),
                            t.name(), done, rows, rowsDoneBeforeTable + done, rowsTotal);
                    if (commitEvery > 0 && sinceCommit >= commitEvery) { out.commit(); sinceCommit = 0; }   // checkpoint
                }
            }
            if (!batchRows.isEmpty()) {
                done += flushInsertBatch(out, ps, cols, batchRows, jdbc, len, scale, load, rejects, t.name());
                batchRows.clear();
                progress.updateRows(percent(startPct, endPct, done, rows),
                        "Load rows", "Loaded " + done + " of " + rows + " rows into " + t.name(),
                        t.name(), done, rows, rowsDoneBeforeTable + done, rowsTotal);
            }
        } finally {
            progress.activeStatement(null);
        }
    }

    /**
     * Execute one batch. On a batch failure with continue-on-error enabled, retry the batch row-by-row
     * under per-row savepoints, recording each failure as a reject instead of aborting the whole load.
     * Returns the number of rows actually inserted.
     */
    private int flushInsertBatch(Connection out, PreparedStatement ps, List<GenColumn> cols,
                                 List<LinkedHashMap<String, String>> batchRows, int[] jdbc, int[] len, int[] scale,
                                 LoadPlan load, RejectCollector rejects, String table) throws SQLException {
        try {
            ps.executeBatch();
            return batchRows.size();
        } catch (SQLException batchEx) {
            if (!load.continueOnError()) throw batchEx;
            ps.clearBatch();
            int ok = 0;
            for (LinkedHashMap<String, String> row : batchRows) {
                Savepoint sp = null;
                try { sp = out.setSavepoint(); } catch (SQLException ignore) { }
                try {
                    for (int c = 0; c < cols.size(); c++)
                        bind(ps, c + 1, fit(row.get(cols.get(c).name()), jdbc[c], len[c]), jdbc[c], cols.get(c).name(), len[c], scale[c]);
                    ps.executeUpdate();
                    ok++;
                    if (sp != null) try { out.releaseSavepoint(sp); } catch (SQLException ignore) { }
                } catch (SQLException rowEx) {
                    if (sp != null) try { out.rollback(sp); } catch (SQLException ignore) { }
                    rejects.add(table, row, rowEx.getMessage());   // throws if the reject ceiling is exceeded
                }
            }
            return ok;
        }
    }

    private Map<String, Object> rejectSummary(RejectCollector rejects) {
        if (rejects == null || rejects.count() == 0) return null;
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("rejected", rejects.count());
        out.put("samples", rejects.samples());
        return out;
    }

    /**
     * Postgres fast path: stream generated rows into the target with COPY ... FROM STDIN (CSV). Far faster
     * than per-row INSERT for bulk loads, at the cost of per-row reject capture (COPY aborts on the first
     * bad row). Non-referenced identity columns are skipped; referenced parent keys are kept FK-safe.
     */
    private void copyInsertRows(Connection out, String schema, GenTable t, long rows, Random rng,
                                StreamCtx ctx,
                                Map<String, Integer> types, Map<String, Integer> sizes,
                                Map<String, Integer> scales, LoadPlan load,
                                ProgressSink progress, int startPct, int endPct,
                                long rowsDoneBeforeTable, long rowsTotal) throws Exception {
        if (rows <= 0) return;
        List<GenColumn> allCols = safe(t.columns());
        Map<String, BiFunction<Long, Random, String>> gens = compiledGenerators(allCols);
        ctx.newTable(t, rows);   // reset per-table run state (PK uniqueness, cardinality) + cache composite groups
        Set<String> auto = autoIncrementColumns(out, schema, t.name());
        Set<String> generated = generatedColumns(out, schema, t.name());
        List<GenColumn> cols = insertableColumns(t, types, auto, generated, ctx.referenced);
        if (cols.isEmpty())
            throw ApiException.bad("None of the generated columns match target table " + t.name() + ".");
        int[] jdbc = cols.stream().mapToInt(c -> types.getOrDefault(c.name().toLowerCase(Locale.ROOT), Types.VARCHAR)).toArray();
        int[] len = cols.stream().mapToInt(c -> sizes.getOrDefault(c.name().toLowerCase(Locale.ROOT), 0)).toArray();
        int[] scale = cols.stream().mapToInt(c -> scales.getOrDefault(c.name().toLowerCase(Locale.ROOT), 0)).toArray();
        String copySql = "COPY " + q(schema, t.name()) + " (" + join(cols, c -> q(c.name())) + ") FROM STDIN WITH (FORMAT csv, NULL '\\N')";
        int batch = Math.max(1000, load.batchSize());
        int commitEvery = load.commitEveryRows();
        long done = 0, sinceCommit = 0;
        int pending = 0;
        StringBuilder sb = new StringBuilder();
        progress.update(startPct, "Load rows", "COPY " + rows + " rows into " + t.name());
        progress.updateRows(startPct, "Load rows", "COPY " + rows + " rows into " + t.name(),
                t.name(), 0, rows, rowsDoneBeforeTable, rowsTotal);
        for (long i = 1; i <= rows; i++) {
            LinkedHashMap<String, String> row = streamRow(t, allCols, gens, i, rng, ctx);
            for (int c = 0; c < cols.size(); c++) {
                if (c > 0) sb.append(',');
                String v = fitForCopy(row.get(cols.get(c).name()), jdbc[c], cols.get(c).name(), len[c], scale[c]);
                sb.append(v == null ? "\\N" : csvCopy(v));
            }
            sb.append('\n');
            if (++pending >= batch) {
                pgCopyIn(out, copySql, sb.toString());
                done += pending; pending = 0; sb.setLength(0);
                progress.updateRows(percent(startPct, endPct, done, rows), "Load rows",
                        "Loaded " + done + " of " + rows + " rows into " + t.name(),
                        t.name(), done, rows, rowsDoneBeforeTable + done, rowsTotal);
                sinceCommit += batch;
                if (commitEvery > 0 && sinceCommit >= commitEvery) { out.commit(); sinceCommit = 0; }
            }
        }
        if (pending > 0) {
            pgCopyIn(out, copySql, sb.toString());
            done += pending;
            progress.updateRows(percent(startPct, endPct, done, rows), "Load rows",
                    "Loaded " + done + " of " + rows + " rows into " + t.name(),
                    t.name(), done, rows, rowsDoneBeforeTable + done, rowsTotal);
        }
    }

    private static String csvCopy(String v) {
        boolean quote = v.isEmpty() || v.equals("\\N")
                || v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0;
        return quote ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    private static String fitForCopy(String value, int jdbcType, String column, int precision, int scale) {
        if (value == null) return null;
        try {
            return switch (jdbcType) {
                case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                        Types.DECIMAL, Types.NUMERIC, Types.FLOAT, Types.REAL, Types.DOUBLE -> {
                    BigDecimal number = asNumber(value);
                    if (number == null) {
                        throw ApiException.bad("Column '" + column + "' is numeric, but the generated value '"
                                + clipVal(value) + "' contains non-numeric characters. Choose a numeric generator.");
                    }
                    yield fitNumber(number, jdbcType, precision, scale).toPlainString();
                }
                case Types.BOOLEAN, Types.BIT -> Boolean.toString(parseBool(value));
                case Types.DATE -> java.sql.Date.valueOf(datePart(value)).toString();
                case Types.TIME -> Time.valueOf(value.substring(0, Math.min(8, value.length()))).toString();
                case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> Timestamp.valueOf(normalizeTs(value)).toString();
                default -> fit(value, jdbcType, precision);
            };
        } catch (ApiException e) {
            throw e;
        } catch (IllegalArgumentException badValue) {
            throw ApiException.bad("Column '" + column + "' could not accept the generated value '" + clipVal(value)
                    + "' for its data type. Adjust this field's generator or its parameters.");
        }
    }

    private static boolean isPostgres(Connection c) {
        try { return c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgre"); }
        catch (Exception e) { return false; }
    }

    /** Run one COPY chunk via reflection so we never compile-couple to the Postgres driver. */
    private long pgCopyIn(Connection out, String copySql, String csv) throws SQLException {
        try {
            Class<?> pgConn = Class.forName("org.postgresql.PGConnection");
            Object pg = out.unwrap(pgConn);
            Object copyApi = pgConn.getMethod("getCopyAPI").invoke(pg);
            Class<?> copyMgr = Class.forName("org.postgresql.copy.CopyManager");
            try (java.io.Reader reader = new java.io.StringReader(csv)) {
                Object res = copyMgr.getMethod("copyIn", String.class, java.io.Reader.class).invoke(copyApi, copySql, reader);
                return res instanceof Long ? (Long) res : 0L;
            }
        } catch (java.lang.reflect.InvocationTargetException ite) {            // unwrap the REAL COPY error
            Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
            if (cause instanceof SQLException sqlEx) throw sqlEx;
            throw new SQLException("COPY failed: " + msgOf(cause), cause);
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {                                               // driver/reflection problem
            throw new SQLException("COPY (fast load) unavailable on this driver: " + msgOf(e), e);
        }
    }

    private static String msgOf(Throwable t) {
        return t == null ? "unknown" : (t.getMessage() != null ? t.getMessage() : t.getClass().getName());
    }

    /**
     * Build a useful message from a (possibly Postgres) failure. The Postgres driver reports a COPY/stream abort
     * as the generic "An I/O error occurred while sending to the backend", and hides the actual server error
     * (constraint violation, bad value, type error, server timeout/OOM) in {@link SQLException#getNextException()}
     * and/or the cause chain. This walks both so the real reason surfaces instead of the opaque I/O text.
     */
    private static String sqlDetail(Throwable t) {
        if (t == null) return "unknown";
        StringBuilder sb = new StringBuilder(msgOf(t));
        for (SQLException next = (t instanceof SQLException se) ? se.getNextException() : null;
             next != null && sb.length() < 2000; next = next.getNextException()) {
            String m = next.getMessage();
            if (m != null && sb.indexOf(m) < 0) sb.append(" | server: ").append(m);
        }
        int guard = 0;
        for (Throwable cause = t.getCause(); cause != null && guard++ < 6; cause = cause.getCause()) {
            String m = cause.getMessage();
            if (m != null && sb.indexOf(m) < 0) sb.append(" | cause: ").append(m);
            if (cause instanceof SQLException cs && cs.getNextException() != null) {
                String nm = cs.getNextException().getMessage();
                if (nm != null && sb.indexOf(nm) < 0) sb.append(" | server: ").append(nm);
            }
        }
        return sb.toString();
    }

    /**
     * Portable bulk fast path for non-Postgres engines: load with multi-row VALUES INSERTs
     * (INSERT INTO t (..) VALUES (..),(..),(..)) instead of one INSERT per row — far fewer round-trips.
     * Chunk size is bounded by the dialect's row and bind-parameter limits. Unlike COPY, it keeps per-row
     * reject handling: if a chunk fails and continue-on-error is on, the chunk is retried row-by-row under
     * savepoints. Row generation goes through {@link #streamRow}, so cardinality, composite FKs, cross-table
     * LOOKUP and uniqueness all apply here too.
     */
    private void multiRowInsertRows(Connection out, String schema, GenTable t, long rows, Random rng,
                                    StreamCtx ctx, Map<String, Integer> types, Map<String, Integer> sizes, Map<String, Integer> scales,
                                    SqlDialect dialect, LoadPlan load, RejectCollector rejects,
                                    ProgressSink progress, int startPct, int endPct,
                                    long rowsDoneBeforeTable, long rowsTotal) throws SQLException {
        if (rows <= 0) return;
        List<GenColumn> allCols = safe(t.columns());
        Map<String, BiFunction<Long, Random, String>> gens = compiledGenerators(allCols);
        ctx.newTable(t, rows);
        Set<String> auto = autoIncrementColumns(out, schema, t.name());
        Set<String> generated = generatedColumns(out, schema, t.name());
        List<GenColumn> cols = insertableColumns(t, types, auto, generated, ctx.referenced);
        if (cols.isEmpty())
            throw ApiException.bad("None of the generated columns match target table " + t.name() + ".");
        int colCount = cols.size();
        int[] jdbc = cols.stream().mapToInt(c -> types.getOrDefault(c.name().toLowerCase(Locale.ROOT), Types.VARCHAR)).toArray();
        int[] len = cols.stream().mapToInt(c -> sizes.getOrDefault(c.name().toLowerCase(Locale.ROOT), 0)).toArray();
        int[] scale = cols.stream().mapToInt(c -> scales.getOrDefault(c.name().toLowerCase(Locale.ROOT), 0)).toArray();
        int chunkRows = Math.max(1, Math.min(Math.min(Math.max(1, load.batchSize()), dialect.maxRowsPerInsert()),
                Math.max(1, dialect.bindParamLimit() / colCount)));
        String prefix = "INSERT INTO " + q(schema, t.name()) + " (" + join(cols, c -> q(c.name())) + ") VALUES ";
        String oneTuple = "(" + repeatJoin("?", colCount, ",") + ")";
        String fullSql = prefix + repeatJoin(oneTuple, chunkRows, ",");
        String oneRowSql = insertSql(out, schema, t.name(), cols, auto);   // single-row, for reject fallback
        int commitEvery = load.commitEveryRows();
        long done = 0, sinceCommit = 0;
        List<LinkedHashMap<String, String>> buffer = new ArrayList<>();
        progress.update(startPct, "Load rows", "Bulk insert (multi-row) of " + rows + " rows into " + t.name());
        progress.updateRows(startPct, "Load rows", "Bulk insert of " + rows + " rows into " + t.name(),
                t.name(), 0, rows, rowsDoneBeforeTable, rowsTotal);
        try (PreparedStatement psFull = out.prepareStatement(fullSql);
             PreparedStatement psOne = out.prepareStatement(oneRowSql)) {
            progress.activeStatement(psFull);
            for (long i = 1; i <= rows; i++) {
                buffer.add(streamRow(t, allCols, gens, i, rng, ctx));
                if (buffer.size() >= chunkRows) {
                    done += flushMultiRow(out, psFull, psOne, cols, buffer, jdbc, len, scale, load, rejects, t.name());
                    sinceCommit += buffer.size();
                    buffer.clear();
                    progress.updateRows(percent(startPct, endPct, done, rows), "Load rows",
                            "Loaded " + done + " of " + rows + " rows into " + t.name(),
                            t.name(), done, rows, rowsDoneBeforeTable + done, rowsTotal);
                    if (commitEvery > 0 && sinceCommit >= commitEvery) { out.commit(); sinceCommit = 0; }
                }
            }
            if (!buffer.isEmpty()) {   // remainder: one statement sized to what's left
                try (PreparedStatement psRem = out.prepareStatement(prefix + repeatJoin(oneTuple, buffer.size(), ","))) {
                    done += flushMultiRow(out, psRem, psOne, cols, buffer, jdbc, len, scale, load, rejects, t.name());
                }
                buffer.clear();
                progress.updateRows(percent(startPct, endPct, done, rows), "Load rows",
                        "Loaded " + done + " of " + rows + " rows into " + t.name(),
                        t.name(), done, rows, rowsDoneBeforeTable + done, rowsTotal);
            }
        } finally {
            progress.activeStatement(null);
        }
    }

    /** Bind a buffer of rows into one multi-row INSERT; on failure, retry row-by-row under savepoints. */
    private int flushMultiRow(Connection out, PreparedStatement psChunk, PreparedStatement psOne, List<GenColumn> cols,
                              List<LinkedHashMap<String, String>> buffer, int[] jdbc, int[] len, int[] scale,
                              LoadPlan load, RejectCollector rejects, String table) throws SQLException {
        int p = 1;
        for (LinkedHashMap<String, String> row : buffer)
            for (int c = 0; c < cols.size(); c++)
                bind(psChunk, p++, fit(row.get(cols.get(c).name()), jdbc[c], len[c]), jdbc[c], cols.get(c).name(), len[c], scale[c]);
        try {
            psChunk.executeUpdate();
            return buffer.size();
        } catch (SQLException chunkEx) {
            if (!load.continueOnError()) throw chunkEx;
            int ok = 0;
            for (LinkedHashMap<String, String> row : buffer) {
                Savepoint sp = null;
                try { sp = out.setSavepoint(); } catch (SQLException ignore) { }
                try {
                    for (int c = 0; c < cols.size(); c++)
                        bind(psOne, c + 1, fit(row.get(cols.get(c).name()), jdbc[c], len[c]), jdbc[c], cols.get(c).name(), len[c], scale[c]);
                    psOne.executeUpdate();
                    ok++;
                    if (sp != null) try { out.releaseSavepoint(sp); } catch (SQLException ignore) { }
                } catch (SQLException rowEx) {
                    if (sp != null) try { out.rollback(sp); } catch (SQLException ignore) { }
                    rejects.add(table, row, rowEx.getMessage());
                }
            }
            return ok;
        }
    }

    /** Join {@code n} copies of {@code unit} with {@code sep} (e.g. "?,?,?"). */
    private static String repeatJoin(String unit, int n, String sep) {
        StringBuilder sb = new StringBuilder(unit.length() * n + sep.length() * Math.max(0, n - 1));
        for (int i = 0; i < n; i++) { if (i > 0) sb.append(sep); sb.append(unit); }
        return sb.toString();
    }

    private void streamUpdateRows(Connection out, String schema, GenTable t, long rows, Random rng,
                                  StreamCtx ctx,
                                  Map<String, Integer> types, Map<String, Integer> sizes, Map<String, Integer> scales, LoadPlan load,
                                  ProgressSink progress, int startPct, int endPct,
                                  long rowsDoneBeforeTable, long rowsTotal) throws SQLException {
        if (rows <= 0) return;
        List<GenColumn> allCols = safe(t.columns());
        Map<String, BiFunction<Long, Random, String>> gens = compiledGenerators(allCols);
        ctx.newTable(t, rows);   // reset per-table run state (PK uniqueness, cardinality) + cache composite groups
        List<GenColumn> cols = writableColumns(t, types);
        List<GenColumn> keys = keyColumnsFor(out, schema, t, cols, load);
        List<GenColumn> nonKeys = nonKeyColumns(cols, keys);
        if (nonKeys.isEmpty())
            throw ApiException.bad("Update-style load needs at least one non-key column for " + t.name());
        String sql = updateSql(schema, t.name(), nonKeys, keys);
        int pending = 0;
        long done = 0;
        progress.update(startPct, "Update rows", "Streaming " + rows + " updates into " + t.name());
        progress.updateRows(startPct, "Update rows", "Streaming " + rows + " updates into " + t.name(),
                t.name(), 0, rows, rowsDoneBeforeTable, rowsTotal);
        try (PreparedStatement ps = out.prepareStatement(sql)) {
            progress.activeStatement(ps);
            for (long i = 1; i <= rows; i++) {
                LinkedHashMap<String, String> row = streamRow(t, allCols, gens, i, rng, ctx);
                bindUpdate(ps, nonKeys, keys, row, types, sizes, scales);
                ps.addBatch();
                if (++pending >= load.batchSize()) {
                    ps.executeBatch();
                    done += pending;
                    progress.updateRows(percent(startPct, endPct, done, rows),
                            "Update rows", "Updated " + done + " of " + rows + " rows in " + t.name(),
                            t.name(), done, rows, rowsDoneBeforeTable + done, rowsTotal);
                    pending = 0;
                }
            }
            if (pending > 0) {
                ps.executeBatch();
                done += pending;
                progress.updateRows(percent(startPct, endPct, done, rows),
                        "Update rows", "Updated " + done + " of " + rows + " rows in " + t.name(),
                        t.name(), done, rows, rowsDoneBeforeTable + done, rowsTotal);
            }
        } finally {
            progress.activeStatement(null);
        }
    }

    private void streamInsertUpdateRows(Connection out, String schema, GenTable t, long rows, Random rng,
                                        StreamCtx ctx,
                                        Map<String, Integer> types, Map<String, Integer> sizes, Map<String, Integer> scales, LoadPlan load,
                                        ProgressSink progress, int startPct, int endPct,
                                        long rowsDoneBeforeTable, long rowsTotal) throws SQLException {
        if (rows <= 0) return;
        List<GenColumn> allCols = safe(t.columns());
        Map<String, BiFunction<Long, Random, String>> gens = compiledGenerators(allCols);
        ctx.newTable(t, rows);   // reset per-table run state (PK uniqueness, cardinality) + cache composite groups
        List<GenColumn> cols = writableColumns(t, types);
        List<GenColumn> keys = keyColumnsFor(out, schema, t, cols, load);
        List<GenColumn> nonKeys = nonKeyColumns(cols, keys);
        if (nonKeys.isEmpty())
            throw ApiException.bad("Insert-update load needs at least one non-key column for " + t.name());

        String updateSql = updateSql(schema, t.name(), nonKeys, keys);
        String insertSql = insertSql(out, schema, t.name(), cols, autoIncrementColumns(out, schema, t.name()));
        int[] jdbc = cols.stream().mapToInt(c -> types.getOrDefault(c.name().toLowerCase(Locale.ROOT), Types.VARCHAR)).toArray();
        int[] len = cols.stream().mapToInt(c -> sizes.getOrDefault(c.name().toLowerCase(Locale.ROOT), 0)).toArray();
        int[] scale = cols.stream().mapToInt(c -> scales.getOrDefault(c.name().toLowerCase(Locale.ROOT), 0)).toArray();
        int pendingInserts = 0, sinceProgress = 0;
        long done = 0;
        progress.update(startPct, "Upsert rows", "Streaming " + rows + " upserts into " + t.name());
        progress.updateRows(startPct, "Upsert rows", "Streaming " + rows + " upserts into " + t.name(),
                t.name(), 0, rows, rowsDoneBeforeTable, rowsTotal);
        try (PreparedStatement upd = out.prepareStatement(updateSql);
             PreparedStatement ins = out.prepareStatement(insertSql)) {
            progress.activeStatement(upd);
            for (long i = 1; i <= rows; i++) {
                LinkedHashMap<String, String> row = streamRow(t, allCols, gens, i, rng, ctx);
                bindUpdate(upd, nonKeys, keys, row, types, sizes, scales);
                if (upd.executeUpdate() == 0) {
                    progress.activeStatement(ins);
                    for (int c = 0; c < cols.size(); c++)
                        bind(ins, c + 1, fit(row.get(cols.get(c).name()), jdbc[c], len[c]), jdbc[c], cols.get(c).name(), len[c], scale[c]);
                    ins.addBatch();
                    if (++pendingInserts >= load.batchSize()) { ins.executeBatch(); pendingInserts = 0; }
                    progress.activeStatement(upd);
                }
                done++;
                if (++sinceProgress >= load.batchSize()) {
                    progress.updateRows(percent(startPct, endPct, done, rows),
                            "Upsert rows", "Upserted " + done + " of " + rows + " rows into " + t.name(),
                            t.name(), done, rows, rowsDoneBeforeTable + done, rowsTotal);
                    sinceProgress = 0;
                }
            }
            if (pendingInserts > 0) {
                progress.activeStatement(ins);
                ins.executeBatch();
                progress.activeStatement(upd);
            }
            progress.updateRows(endPct, "Upsert rows", "Upserted " + done + " of " + rows + " rows into " + t.name(),
                    t.name(), done, rows, rowsDoneBeforeTable + done, rowsTotal);
        } finally {
            progress.activeStatement(null);
        }
    }

    private List<GenColumn> writableColumns(GenTable t, Map<String, Integer> types) {
        List<GenColumn> cols = safe(t.columns()).stream()
                .filter(c -> c.name() != null && types.containsKey(c.name().toLowerCase(Locale.ROOT)))
                .toList();
        if (cols.isEmpty())
            throw ApiException.bad("None of the generated columns match target table " + t.name()
                    + " (its columns are " + types.keySet() + "). Use 'Add all' from this schema to match the real columns,"
                    + " or enable 'Drop & recreate first' to build the table from your plan.");
        return cols;
    }

    private List<GenColumn> keyColumnsFor(Connection out, String schema, GenTable t,
                                          List<GenColumn> cols, LoadPlan load) throws SQLException {
        if (!load.needsKeys()) return List.of();
        if (!load.keyColumns().isEmpty()) return resolveKeyColumns(load.keyColumns(), cols, t.name());

        List<GenColumn> planned = cols.stream()
                .filter(c -> Boolean.TRUE.equals(c.primaryKey()))
                .toList();
        if (!planned.isEmpty()) return planned;

        List<String> dbKeys = primaryKeyColumns(out, schema, t.name());
        if (!dbKeys.isEmpty()) return resolveKeyColumns(dbKeys, cols, t.name());

        throw ApiException.bad("Load action " + load.action()
                + " requires key columns for " + t.name()
                + ". Enter key columns or mark a generated/target primary key.");
    }

    private List<GenColumn> resolveKeyColumns(List<String> keys, List<GenColumn> cols, String table) {
        Map<String, GenColumn> byName = new LinkedHashMap<>();
        for (GenColumn c : cols) byName.put(c.name().toLowerCase(Locale.ROOT), c);
        List<GenColumn> out = new ArrayList<>();
        for (String key : keys) {
            if (key == null || key.isBlank()) continue;
            GenColumn c = byName.get(key.trim().toLowerCase(Locale.ROOT));
            if (c == null) throw ApiException.bad("Key column " + key + " is not generated for target table " + table);
            out.add(c);
        }
        if (out.isEmpty()) throw ApiException.bad("No usable key columns found for " + table);
        return out;
    }

    private List<GenColumn> nonKeyColumns(List<GenColumn> cols, List<GenColumn> keys) {
        Set<String> keyNames = new HashSet<>();
        for (GenColumn key : keys) keyNames.add(key.name().toLowerCase(Locale.ROOT));
        return cols.stream()
                .filter(c -> !keyNames.contains(c.name().toLowerCase(Locale.ROOT)))
                .toList();
    }

    private String updateSql(String schema, String table, List<GenColumn> nonKeys, List<GenColumn> keys) {
        String set = String.join(",", nonKeys.stream().map(c -> q(c.name()) + "=?").toList());
        String where = String.join(" AND ", keys.stream().map(c -> q(c.name()) + "=?").toList());
        return "UPDATE " + q(schema, table) + " SET " + set + " WHERE " + where;
    }

    private void bindUpdate(PreparedStatement ps, List<GenColumn> nonKeys, List<GenColumn> keys,
                            LinkedHashMap<String, String> row, Map<String, Integer> types,
                            Map<String, Integer> sizes, Map<String, Integer> scales) throws SQLException {
        int idx = 1;
        for (GenColumn c : nonKeys) idx = bindColumn(ps, idx, c, row, types, sizes, scales);
        for (GenColumn c : keys) idx = bindColumn(ps, idx, c, row, types, sizes, scales);
    }

    private int bindColumn(PreparedStatement ps, int idx, GenColumn c, LinkedHashMap<String, String> row,
                           Map<String, Integer> types, Map<String, Integer> sizes, Map<String, Integer> scales) throws SQLException {
        String lc = c.name().toLowerCase(Locale.ROOT);
        int jdbc = types.getOrDefault(lc, Types.VARCHAR);
        int len = sizes.getOrDefault(lc, 0);
        int scale = scales.getOrDefault(lc, 0);
        bind(ps, idx, fit(row.get(c.name()), jdbc, len), jdbc, c.name(), len, scale);
        return idx + 1;
    }

    private List<String> primaryKeyColumns(Connection c, String schema, String table) throws SQLException {
        Map<Short, String> ordered = new TreeMap<>();
        try (ResultSet rs = c.getMetaData().getPrimaryKeys(null, schema, table)) {
            while (rs.next()) ordered.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
        }
        return new ArrayList<>(ordered.values());
    }

    private static final java.util.regex.Pattern TRAILING_NUMBER =
            java.util.regex.Pattern.compile("[0-9]+(?:\\.[0-9]+)?$");
    private static final java.util.regex.Pattern ISO_DATE =
            java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final java.util.regex.Pattern DATE_WITH_OFFSET_ONLY =
            java.util.regex.Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})\\s+[+-]\\d{2}(?::?\\d{2})?$");

    /**
     * Coerce a generated string into a number for numeric columns, tolerating SEQUENCE-style prefixes
     * (e.g. "C-1" → 1) so a typed column never receives a character value. Returns null if there is
     * no numeric content, which surfaces as a clear per-column error instead of a cryptic JDBC batch error.
     */
    private static BigDecimal asNumber(String v) {
        try { return new BigDecimal(v.trim()); } catch (NumberFormatException ignore) { }
        java.util.regex.Matcher m = TRAILING_NUMBER.matcher(v.trim());
        if (m.find()) { try { return new BigDecimal(m.group()); } catch (NumberFormatException ignore) { } }
        return null;
    }

    private static void bind(PreparedStatement ps, int idx, String v, int jdbcType, String col,
                             int precision, int scale) throws SQLException {
        if (v == null) { ps.setNull(idx, jdbcType); return; }
        try {
            switch (jdbcType) {
                case Types.TINYINT: case Types.SMALLINT: case Types.INTEGER: case Types.BIGINT:
                case Types.DECIMAL: case Types.NUMERIC: case Types.FLOAT: case Types.REAL: case Types.DOUBLE: {
                    BigDecimal n = asNumber(v);
                    if (n == null) throw ApiException.bad("Column '" + col + "' is numeric, but the generated value '"
                            + clipVal(v) + "' contains non-numeric characters. Remove the Param1/Param2 prefix on this"
                            + " field, or choose a numeric generator (e.g. SEQUENCE without a prefix, INT_RANGE, DECIMAL_RANGE).");
                    ps.setBigDecimal(idx, fitNumber(n, jdbcType, precision, scale));
                    break;
                }
                case Types.BOOLEAN: case Types.BIT:
                    ps.setBoolean(idx, parseBool(v)); break;
                case Types.DATE:
                    ps.setDate(idx, java.sql.Date.valueOf(datePart(v))); break;
                case Types.TIME:
                    ps.setTime(idx, Time.valueOf(v.substring(0, Math.min(8, v.length())))); break;
                case Types.TIMESTAMP: case Types.TIMESTAMP_WITH_TIMEZONE:
                    ps.setTimestamp(idx, Timestamp.valueOf(normalizeTs(v))); break;
                default:
                    ps.setString(idx, v);
            }
        } catch (ApiException e) {
            throw e;
        } catch (IllegalArgumentException badValue) {
            throw ApiException.bad("Column '" + col + "' could not accept the generated value '" + clipVal(v)
                    + "' for its data type. Adjust this field's generator or its parameters.");
        }
    }

    private static String clipVal(String v) { return v != null && v.length() > 40 ? v.substring(0, 40) + "…" : v; }

    private static BigDecimal fitNumber(BigDecimal n, int jdbcType, int precision, int scale) {
        return switch (jdbcType) {
            case Types.TINYINT -> clampWhole(n, BigDecimal.valueOf(Byte.MIN_VALUE), BigDecimal.valueOf(Byte.MAX_VALUE));
            case Types.SMALLINT -> clampWhole(n, BigDecimal.valueOf(Short.MIN_VALUE), BigDecimal.valueOf(Short.MAX_VALUE));
            case Types.INTEGER -> clampWhole(n, BigDecimal.valueOf(Integer.MIN_VALUE), BigDecimal.valueOf(Integer.MAX_VALUE));
            case Types.BIGINT -> clampWhole(n, BigDecimal.valueOf(Long.MIN_VALUE), BigDecimal.valueOf(Long.MAX_VALUE));
            case Types.DECIMAL, Types.NUMERIC -> fitDecimal(n, precision, scale);
            default -> n;
        };
    }

    private static BigDecimal clampWhole(BigDecimal n, BigDecimal min, BigDecimal max) {
        BigDecimal whole = n.setScale(0, RoundingMode.DOWN);
        if (whole.compareTo(max) > 0) return max;
        if (whole.compareTo(min) < 0) return min;
        return whole;
    }

    private static BigDecimal fitDecimal(BigDecimal n, int precision, int scale) {
        if (precision <= 0 || precision > 100) return n;
        int effectiveScale = Math.max(0, Math.min(scale, precision));
        BigDecimal scaled = n.setScale(effectiveScale, RoundingMode.DOWN);
        int integerDigits = Math.max(0, precision - effectiveScale);
        BigDecimal step = BigDecimal.ONE.movePointLeft(effectiveScale);
        BigDecimal max = BigDecimal.TEN.pow(integerDigits).subtract(step);
        if (scaled.compareTo(max) > 0) return max;
        BigDecimal min = max.negate();
        if (scaled.compareTo(min) < 0) return min;
        return scaled;
    }

    /** Truncate a generated string to the target character column's max length so it always fits. */
    private static String fit(String v, int jdbcType, int maxLen) {
        if (v == null || maxLen <= 0 || v.length() <= maxLen) return v;
        switch (jdbcType) {
            case Types.CHAR: case Types.VARCHAR: case Types.LONGVARCHAR:
            case Types.NCHAR: case Types.NVARCHAR: case Types.LONGNVARCHAR:
                return v.substring(0, maxLen);
            default:
                return v;
        }
    }

    private static String normalizeTs(String v) {
        String s = v.trim().replace('T', ' ');
        java.util.regex.Matcher offsetOnly = DATE_WITH_OFFSET_ONLY.matcher(s);
        if (offsetOnly.matches()) return offsetOnly.group(1) + " 00:00:00";
        s = s.replaceFirst("\\s*(Z|[+-]\\d{2}:?\\d{2})$", "").trim();
        if (s.length() == 10) s += " 00:00:00";
        if (s.length() == 16) s += ":00";
        if (s.length() > 19 && s.charAt(19) != '.') s = s.substring(0, 19);
        return s;
    }

    private static String datePart(String v) {
        java.util.regex.Matcher m = ISO_DATE.matcher(v.trim());
        if (m.find()) return m.group();
        return v.substring(0, Math.min(10, v.length()));
    }

    // ------------------------------------------------------------------ DDL/meta

    private String createDdl(String table, String schema, List<GenColumn> cols) {
        StringBuilder sb = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(q(schema, table)).append(" (");
        List<String> pk = new ArrayList<>();
        for (int i = 0; i < cols.size(); i++) {
            GenColumn c = cols.get(i);
            if (i > 0) sb.append(", ");
            sb.append(q(c.name())).append(' ').append(ddlType(c.sqlType()));
            if (Boolean.TRUE.equals(c.primaryKey())) pk.add(q(c.name()));
        }
        if (!pk.isEmpty()) sb.append(", PRIMARY KEY (").append(String.join(", ", pk)).append(')');
        return sb.append(");").toString();
    }

    private static String ddlType(String sqlType) {
        String s = sqlType == null ? "" : sqlType.trim().toUpperCase(Locale.ROOT);
        if (s.startsWith("INT") || s.equals("BIGINT") || s.equals("SMALLINT")) return "BIGINT";
        if (s.startsWith("DEC") || s.startsWith("NUM") || s.equals("MONEY") || s.equals("DOUBLE") || s.equals("FLOAT") || s.equals("REAL"))
            return "DECIMAL(18,4)";
        if (s.equals("DATE")) return "DATE";
        if (s.startsWith("TIMESTAMP") || s.equals("DATETIME")) return "TIMESTAMP";
        if (s.equals("BOOLEAN") || s.equals("BOOL") || s.equals("BIT")) return "BOOLEAN";
        return "VARCHAR(255)";
    }

    private Map<String, Integer> columnTypes(Connection c, String schema, String table) throws SQLException {
        Map<String, Integer> out = new LinkedHashMap<>();
        try (ResultSet rs = c.getMetaData().getColumns(null, schema, table, "%")) {
            while (rs.next()) out.put(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT), rs.getInt("DATA_TYPE"));
        }
        return out;
    }

    private List<GenColumn> insertableColumns(GenTable table, Map<String, Integer> types,
                                              Set<String> auto, Set<String> generated, Set<String> referenced) {
        String tableName = table.name().toLowerCase(Locale.ROOT);
        return safe(table.columns()).stream()
                .filter(c -> types.containsKey(c.name().toLowerCase(Locale.ROOT)))
                .filter(c -> !generated.contains(c.name().toLowerCase(Locale.ROOT)))
                .filter(c -> {
                    String col = c.name().toLowerCase(Locale.ROOT);
                    return !auto.contains(col) || referenced.contains(tableName + "." + col);
                })
                .toList();
    }

    private String insertSql(Connection c, String schema, String table, List<GenColumn> cols,
                             Set<String> auto) throws SQLException {
        boolean writesAuto = cols.stream().anyMatch(col -> auto.contains(col.name().toLowerCase(Locale.ROOT)));
        String override = writesAuto && isPostgres(c) ? " OVERRIDING SYSTEM VALUE" : "";
        return "INSERT INTO " + q(schema, table) + " ("
                + join(cols, col -> q(col.name())) + ")" + override + " VALUES ("
                + join(cols, col -> "?") + ")";
    }

    /** Identity / auto-increment columns. Referenced generated keys are written explicitly for FK safety. */
    private Set<String> autoIncrementColumns(Connection c, String schema, String table) {
        Set<String> auto = new HashSet<>();
        try (ResultSet rs = c.getMetaData().getColumns(null, schema, table, "%")) {
            while (rs.next()) {
                String ai = safeGet(rs, "IS_AUTOINCREMENT");
                if ("YES".equalsIgnoreCase(ai))
                    auto.add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
        } catch (Exception ignore) { /* best-effort: if the driver can't tell us, write all columns as before */ }
        return auto;
    }

    /** Computed/generated columns are never written by the loader. */
    private Set<String> generatedColumns(Connection c, String schema, String table) {
        Set<String> generated = new HashSet<>();
        try (ResultSet rs = c.getMetaData().getColumns(null, schema, table, "%")) {
            while (rs.next()) {
                String gen = safeGet(rs, "IS_GENERATEDCOLUMN");
                if ("YES".equalsIgnoreCase(gen))
                    generated.add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
        } catch (Exception ignore) { }
        return generated;
    }

    private static String safeGet(ResultSet rs, String column) {
        try { return rs.getString(column); } catch (Exception e) { return null; }
    }

    /**
     * Best-effort post-load validation (Tier-4 audit). Reports the actual committed row count per table
     * and, when {@code deep}, the number of orphan foreign-key rows (children that don't match a parent).
     * Never throws — a validation failure must not fail an otherwise-successful load.
     */
    private List<Map<String, Object>> validateLoad(Connection c, String schema, List<GenTable> ordered, boolean deep) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (GenTable t : ordered) {
            LinkedHashMap<String, Object> v = new LinkedHashMap<>();
            v.put("table", t.name());
            long rowCount = -1L;
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + q(schema, t.name()))) {
                rowCount = rs.next() ? rs.getLong(1) : 0L;
                v.put("rowCount", rowCount);
            } catch (Exception e) { v.put("rowCountError", e.getMessage()); }
            boolean runIntegrity = deep;
            if (runIntegrity) {
                List<Map<String, Object>> fkChecks = new ArrayList<>();
                for (GenColumn col : safe(t.columns())) {
                    if (!notBlank(col.fkTable()) || !notBlank(col.fkColumn())) continue;
                    String sql = "SELECT COUNT(*) FROM " + q(schema, t.name()) + " ch WHERE ch." + q(col.name())
                            + " IS NOT NULL AND NOT EXISTS (SELECT 1 FROM " + q(schema, col.fkTable())
                            + " pa WHERE pa." + q(col.fkColumn()) + " = ch." + q(col.name()) + ")";
                    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                        long orphans = rs.next() ? rs.getLong(1) : 0L;
                        fkChecks.add(Map.of("column", col.name(),
                                "references", col.fkTable() + "." + col.fkColumn(), "orphans", orphans));
                    } catch (Exception ignore) { /* skip column on error */ }
                }
                if (!fkChecks.isEmpty()) v.put("foreignKeys", fkChecks);

                List<Map<String, Object>> nullChecks = new ArrayList<>();
                for (String col : notNullColumns(c, schema, t.name())) {
                    String sql = "SELECT COUNT(*) FROM " + q(schema, t.name()) + " WHERE " + q(col) + " IS NULL";
                    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                        nullChecks.add(Map.of("column", col, "nulls", rs.next() ? rs.getLong(1) : 0L));
                    } catch (Exception ignore) { }
                }
                if (!nullChecks.isEmpty()) v.put("notNull", nullChecks);

                List<Map<String, Object>> uniqueChecks = new ArrayList<>();
                for (String col : singleColumnUniqueColumns(c, schema, t.name())) {
                    String sql = "SELECT COUNT(*) FROM (SELECT " + q(col) + " FROM " + q(schema, t.name())
                            + " WHERE " + q(col) + " IS NOT NULL GROUP BY " + q(col) + " HAVING COUNT(*) > 1) d";
                    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                        uniqueChecks.add(Map.of("column", col, "duplicateValues", rs.next() ? rs.getLong(1) : 0L));
                    } catch (Exception ignore) { }
                }
                if (!uniqueChecks.isEmpty()) v.put("unique", uniqueChecks);
            } else if (rowCount >= 0) {
                v.put("integrityValidation", "row-count-only; run a dedicated deep validation job for FK, NOT NULL, and UNIQUE evidence");
            }
            out.add(v);
        }
        return out;
    }

    private Set<String> notNullColumns(Connection c, String schema, String table) {
        Set<String> cols = new LinkedHashSet<>();
        try (ResultSet rs = c.getMetaData().getColumns(null, schema, table, "%")) {
            while (rs.next()) {
                String nullable = safeGet(rs, "IS_NULLABLE");
                String generated = safeGet(rs, "IS_GENERATEDCOLUMN");
                if ("NO".equalsIgnoreCase(nullable) && !"YES".equalsIgnoreCase(generated)) {
                    cols.add(rs.getString("COLUMN_NAME"));
                }
            }
        } catch (Exception ignore) { }
        return cols;
    }

    /** Max character length per column (COLUMN_SIZE), used to truncate generated strings so they fit. */
    private Map<String, Integer> columnSizes(Connection c, String schema, String table) throws SQLException {
        Map<String, Integer> out = new LinkedHashMap<>();
        try (ResultSet rs = c.getMetaData().getColumns(null, schema, table, "%")) {
            while (rs.next()) out.put(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT), rs.getInt("COLUMN_SIZE"));
        }
        return out;
    }

    /** Decimal scale per column (DECIMAL_DIGITS), used with COLUMN_SIZE to keep generated numerics in range. */
    private Map<String, Integer> columnScales(Connection c, String schema, String table) throws SQLException {
        Map<String, Integer> out = new LinkedHashMap<>();
        try (ResultSet rs = c.getMetaData().getColumns(null, schema, table, "%")) {
            while (rs.next()) out.put(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT), Math.max(0, rs.getInt("DECIMAL_DIGITS")));
        }
        return out;
    }

    private void execQuiet(Connection c, String sql) {
        try (Statement st = c.createStatement()) { st.execute(sql); } catch (SQLException ignored) { }
    }

    /**
     * Clear an existing target table before load. DELETE is FK-safe (tables are cleared child-first);
     * TRUNCATE_CASCADE uses Postgres' fast path and falls back to DELETE elsewhere. Chosen up front by
     * dialect so a failed TRUNCATE can't poison the transaction.
     */
    private void clearTable(Connection out, SqlDialect dialect, String schema, String table, String prep) throws SQLException {
        String qt = q(schema, table);
        String sql = ("TRUNCATE_CASCADE".equals(prep) && dialect == SqlDialect.POSTGRES)
                ? "TRUNCATE TABLE " + qt + " CASCADE"
                : "DELETE FROM " + qt;
        try (Statement st = out.createStatement()) { st.executeUpdate(sql); }
        catch (SQLException e) {
            throw new SQLException("Could not clear " + table + " (" + e.getMessage()
                    + "). Include its child tables in the dataset, or use Append.", e);
        }
    }

    private void clearTables(Connection out, SqlDialect dialect, String schema, List<GenTable> tables, String prep) throws SQLException {
        if (tables == null || tables.isEmpty()) return;
        if ("TRUNCATE_CASCADE".equals(prep) && dialect.supportsMultiTableTruncate()) {
            String targets = String.join(", ", tables.stream().map(t -> q(schema, t.name())).toList());
            try (Statement st = out.createStatement()) { st.executeUpdate("TRUNCATE TABLE " + targets + " CASCADE"); }
            catch (SQLException e) {
                throw new SQLException("Could not truncate selected target tables (" + e.getMessage()
                        + "). Include related child tables in the dataset, or use Delete rows first.", e);
            }
            return;
        }
        for (GenTable t : tables) clearTable(out, dialect, schema, t.name(), prep);
    }

    // ----------------------------------------------------------------- helpers

    private static boolean isNumeric(String sqlType) {
        String s = sqlType == null ? "" : sqlType.trim().toUpperCase(Locale.ROOT);
        return s.startsWith("INT") || s.equals("BIGINT") || s.equals("SMALLINT")
                || s.startsWith("DEC") || s.startsWith("NUM") || s.equals("MONEY")
                || s.equals("DOUBLE") || s.equals("FLOAT") || s.equals("REAL");
    }

    private static boolean isBoolean(String sqlType) {
        String s = sqlType == null ? "" : sqlType.trim().toUpperCase(Locale.ROOT);
        return s.equals("BOOLEAN") || s.equals("BOOL") || s.equals("BIT");
    }

    private static boolean parseBool(String v) {
        String s = v.trim().toLowerCase(Locale.ROOT);
        return s.equals("true") || s.equals("t") || s.equals("y") || s.equals("yes") || s.equals("1");
    }

    private Map<String, Object> fileResult(String receiver, List<Map<String, Object>> summary, List<Map<String, String>> files) {
        return Map.of("receiver", receiver, "tables", summary, "files", files);
    }

    private static int percent(int start, int end, long done, long total) {
        if (total <= 0) return end;
        long clamped = Math.max(0, Math.min(done, total));
        return start + (int) Math.round((end - start) * (clamped / (double) total));
    }

    private static <T> String join(List<T> items, java.util.function.Function<T, String> f) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) { if (i > 0) sb.append(","); sb.append(f.apply(items.get(i))); }
        return sb.toString();
    }

    private static String q(String ident) {
        if (ident == null || !ident.matches("[A-Za-z0-9_]+")) throw ApiException.bad("Illegal identifier: " + ident);
        return "\"" + ident + "\"";
    }

    private static String q(String schema, String table) {
        return schema == null || schema.isBlank() ? q(table) : q(schema) + "." + q(table);
    }

    private static String safeName(String s) {
        String out = s == null ? "synthetic" : s.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-");
        return out.isBlank() ? "synthetic" : out;
    }

    private static <T> List<T> safe(List<T> l) { return l == null ? List.of() : l; }
    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    private static String blankNull(String s) { return s == null || s.isBlank() ? null : s; }
    private static String blankOr(String s, String def) { return s == null || s.isBlank() ? def : s; }
    private static String key(String t, String c) { return t.toLowerCase(Locale.ROOT) + "." + c.toLowerCase(Locale.ROOT); }

    private static boolean isApi(GenColumn c) {
        return c.generator() != null && c.generator().trim().equalsIgnoreCase("API");
    }

    /**
     * Resolve a value from a REST/microservice. {@code url} (param1) may contain {row}/{seq}
     * placeholders; {@code path} (param2) is a dot path into the JSON response (blank = whole body).
     * One GET per row.
     */
    private String apiValue(String url, String path, long row) {
        if (url == null) throw ApiException.bad("API generator needs a URL in param1");
        String u = url.replace("{row}", String.valueOf(row)).replace("{seq}", String.valueOf(row));
        URI uri = SyntheticApiSafety.validate(u, SyntheticApiSafety.configuredAllowlist());
        boolean acquired = false;
        try {
            apiGeneratorThrottle.acquire();
            acquired = true;
            Exception last = null;
            for (int attempt = 0; attempt <= API_GENERATOR_RETRIES; attempt++) {
                try {
                    HttpResponse<String> resp = http.send(
                            HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
                    int family = resp.statusCode() / 100;
                    if (family == 2) {
                        String body = resp.body();
                        if (path == null || path.isBlank()) return body == null ? null : body.trim();
                        JsonNode node = json.readTree(body);
                        for (String seg : path.split("\\.")) node = node.path(seg.trim());
                        return node.isMissingNode() || node.isNull() ? null : node.asText();
                    }
                    if (resp.statusCode() < 500 || attempt == API_GENERATOR_RETRIES) {
                        throw ApiException.bad("API " + u + " returned HTTP " + resp.statusCode());
                    }
                } catch (ApiException e) {
                    throw e;
                } catch (Exception e) {
                    last = e;
                    if (attempt == API_GENERATOR_RETRIES) break;
                }
                try { Thread.sleep(150L * (attempt + 1)); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new SyntheticJobCancelledException();
                }
            }
            throw ApiException.bad("API call failed for " + u + ": " + (last == null ? "unknown" : last.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SyntheticJobCancelledException();
        } catch (ApiException e) {
            throw e;
        } finally {
            if (acquired) apiGeneratorThrottle.release();
        }
    }
}
