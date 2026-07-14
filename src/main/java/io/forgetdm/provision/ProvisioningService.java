package io.forgetdm.provision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.config.ForgeProps;
import io.forgetdm.core.mask.MaskContext;
import io.forgetdm.core.mask.MaskFunction;
import io.forgetdm.core.mask.MaskingEngine;
import io.forgetdm.core.synth.Generators;
import io.forgetdm.dataset.ColumnOverrideEntity;
import io.forgetdm.dataset.DataSetDefinitionEntity;
import io.forgetdm.dataset.DataSetService;
import io.forgetdm.dataset.RelationshipTraversalRuleEntity;
import io.forgetdm.dataset.TableProfileEntity;
import io.forgetdm.dataset.UserDefinedRelationshipEntity;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.datasource.SqlDialect;
import io.forgetdm.policy.MaskingRuleEntity;
import io.forgetdm.policy.MaskingRuleRepository;
import io.forgetdm.subset.SubsetService;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

/**
 * The provisioning pipeline: extract -> mask in flight -> batch load.
 * Job types:
 *   MASK_COPY      copy selected tables source->target, masking per policy (in-flight masking)
 *   SUBSET_MASK    referentially intact subset (driver + filter), masked in flight, parents-first load
 *   SYNTHETIC_LOAD generate N rows per generator bindings into a target table (configurable row-generator)
 * Load actions:
 *   REPLACE        clear the selected target tables, then insert the incoming rows
 *   INSERT         append rows and fail on duplicate target keys
 *   UPDATE         update only matching target rows; missing keys are skipped
 *   INSERT_UPDATE  update matching rows and insert missing rows
 *   TRUNCATE_ONLY  clear selected target tables without loading rows
 * Jobs run async on a worker pool; status/rows are polled by the UI.
 */
@Service
public class ProvisioningService {

    private static final int KEY_CHUNK_SIZE = 5_000;

    private final ProvisionJobRepository jobs;
    private final MaskingRuleRepository rules;
    private final DataSourceService dataSources;
    private final ConnectionFactory connections;
    private final MaskingEngine engine;
    private final SubsetService subsets;
    private final DataSetService datasets;
    private final AuditService audit;
    private final ForgeProps props;
    private final ExecutorService executor;
    private final io.forgetdm.ri.RiRegistryService riRegistry;
    private final DbMaskPushdown dbPushdown;
    private final ObjectMapper json = new ObjectMapper();
    private final ConcurrentMap<Long, Future<?>> runningJobs = new ConcurrentHashMap<>();
    /** One in-flight provisioning job per target (dataSource + schema) — prevents concurrent loads from
     *  interleaving truncate/insert/in-place against the same schema and corrupting the target. */
    private final ConcurrentMap<String, Long> activeProvisionTargets = new ConcurrentHashMap<>();
    /** Per-run (per worker thread) shared salts for FK-related key columns, so the same source value masks to
     *  the same output on a PK and every FK that references it — preserving joins. See buildKeyConsistencySalts. */
    private final ThreadLocal<Map<String, String>> keySaltTL = ThreadLocal.withInitial(Map::of);

    /** Receives row-count deltas, so in-place masking reports progress correctly whether tables run serially or
     *  in parallel (the raw {@code progress(job, absolute)} can't be shared safely across worker threads). */
    private interface ProgressSink { void add(long rowDelta); }
    private final ThreadLocal<ProgressSink> progressSinkTL = new ThreadLocal<>();

    /** Report a batch of newly-processed rows through the active sink, or fall back to a direct (synchronized) update. */
    private void reportDelta(ProvisionJobEntity job, long delta) {
        ProgressSink s = progressSinkTL.get();
        if (s != null) { s.add(delta); return; }
        synchronized (job) { job.setRowsProcessed(job.getRowsProcessed() + delta); jobs.save(job); }
    }

    /** A sink that accumulates into one shared counter and pushes the running total to the job (serialized save). */
    private ProgressSink sharedSink(ProvisionJobEntity job, AtomicLong counter) {
        return delta -> {
            long t = counter.addAndGet(delta);
            synchronized (job) { job.setRowsProcessed(t); jobs.save(job); }
        };
    }

    /** How many tables to mask in parallel. Env FORGETDM_PROVISION_PARALLELISM overrides; default = min(4, CPUs). */
    private int provisionParallelism() {
        try {
            String v = System.getenv("FORGETDM_PROVISION_PARALLELISM");
            if (v != null && !v.isBlank()) return Math.max(1, Integer.parseInt(v.trim()));
        } catch (Exception ignore) { /* fall through to default */ }
        return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
    }

    public ProvisioningService(ProvisionJobRepository jobs, MaskingRuleRepository rules,
                               DataSourceService dataSources, ConnectionFactory connections,
                               MaskingEngine engine, SubsetService subsets, DataSetService datasets,
                               AuditService audit, ForgeProps props, ExecutorService provisioningExecutor,
                               io.forgetdm.ri.RiRegistryService riRegistry, DbMaskPushdown dbPushdown) {
        this.jobs = jobs; this.rules = rules; this.dataSources = dataSources;
        this.connections = connections; this.engine = engine; this.subsets = subsets;
        this.datasets = datasets; this.audit = audit; this.props = props; this.executor = provisioningExecutor;
        this.riRegistry = riRegistry; this.dbPushdown = dbPushdown;
    }

    @PostConstruct
    void markInterruptedJobs() {
        List<ProvisionJobEntity> interrupted = jobs.findAll().stream()
                .filter(j -> "RUNNING".equals(j.getStatus()) || "PENDING".equals(j.getStatus()) || "CANCEL_REQUESTED".equals(j.getStatus()))
                .toList();
        if (interrupted.isEmpty()) return;
        Instant now = Instant.now();
        for (ProvisionJobEntity job : interrupted) {
            if ("CANCEL_REQUESTED".equals(job.getStatus())) {
                job.setStatus("CANCELED");
                job.setMessage("Canceled before server restart");
            } else {
                job.setStatus("FAILED");
                job.setMessage("Interrupted by server restart before completion");
            }
            job.setFinishedAt(now);
        }
        jobs.saveAll(interrupted);
    }

    public ProvisionJobEntity submit(ProvisionJobEntity job) {
        if (!Set.of("MASK_COPY", "SUBSET_MASK", "SYNTHETIC_LOAD").contains(job.getJobType()))
            throw ApiException.bad("jobType must be MASK_COPY, SUBSET_MASK or SYNTHETIC_LOAD");
        job.setCreatedBy(io.forgetdm.security.AccessContext.current()
                .map(io.forgetdm.security.AccessPrincipal::username).orElse("system"));
        enforceGovernance(job);   // hard block: unmasked PROD copy
        if (approvalRequired(job)) {
            job.setStatus("AWAITING_APPROVAL");
            job.setApprovalStatus("PENDING_APPROVAL");
            job.setApprovalRequestedAt(Instant.now());
            job.setMessage("Awaiting maker-checker approval (source is governed)");
            ProvisionJobEntity saved = jobs.save(job);
            audit.log(job.getCreatedBy(), "PROVISION_APPROVAL_REQUESTED",
                    saved.getJobType() + " '" + saved.getName() + "' id=" + saved.getId());
            return saved;
        }
        job.setStatus("PENDING");
        ProvisionJobEntity saved = jobs.save(job);
        audit.log("system", "JOB_SUBMITTED", saved.getJobType() + " '" + saved.getName() + "' id=" + saved.getId());
        Future<?> future = executor.submit(() -> run(saved.getId()));
        runningJobs.put(saved.getId(), future);
        return saved;
    }

    // ─── Maker-checker approval (SUBSET_MASK / MASK_COPY; synthetic has its own saved-job gate) ───

    private boolean approvalRequired(ProvisionJobEntity job) {
        if (!Set.of("MASK_COPY", "SUBSET_MASK").contains(job.getJobType())) return false;
        String mode = props.getGovernance().getRequireProvisionApproval();
        if ("never".equalsIgnoreCase(mode)) return false;
        if ("always".equalsIgnoreCase(mode)) return true;
        if (isProdSource(job)) return true;   // prod-only (default)
        if (props.getGovernance().isRequireApprovalOnUnmaskedPii() && job.getDatasetId() != null) {
            try {
                Object un = datasets.piiCoverage(job.getDatasetId(), job.getPolicyId()).get("unmaskedApproved");
                return un instanceof List<?> list && !list.isEmpty();
            } catch (Exception ignore) { /* coverage is advisory — never block submission on its failure */ }
        }
        return false;
    }

    private boolean isProdSource(ProvisionJobEntity job) {
        if (job.getSourceId() == null) return false;
        try { return "PROD".equalsIgnoreCase(dataSources.get(job.getSourceId()).getEnvironment()); }
        catch (Exception e) { return false; }
    }

    /** Environment enforcement: a PROD-tagged source with no masking policy anywhere is refused outright. */
    private void enforceGovernance(ProvisionJobEntity job) {
        if (!props.getGovernance().isBlockUnmaskedProdCopy()) return;
        if (!Set.of("MASK_COPY", "SUBSET_MASK").contains(job.getJobType())) return;
        if (!isProdSource(job)) return;
        boolean hasPolicy = job.getPolicyId() != null;
        if (!hasPolicy && job.getDatasetId() != null) {
            try {
                hasPolicy = datasets.listProfiles(job.getDatasetId()).stream()
                        .anyMatch(p -> p.isIncluded() && p.getPolicyId() != null);
            } catch (Exception ignore) { /* fall through to block */ }
        }
        if (!hasPolicy) {
            throw ApiException.bad("Blocked: the source data source is tagged PROD and this job has no masking "
                    + "policy anywhere (no job default, no per-table policy). Assign a policy or clear the PROD tag. "
                    + "(forgetdm.governance.block-unmasked-prod-copy)");
        }
    }

    public ProvisionJobEntity approve(Long jobId, String note) {
        ProvisionJobEntity job = get(jobId);
        if (!"AWAITING_APPROVAL".equals(job.getStatus()))
            throw ApiException.bad("Job " + jobId + " is not awaiting approval (status " + job.getStatus() + ")");
        io.forgetdm.security.AccessPrincipal p = io.forgetdm.security.AccessContext.current()
                .orElseThrow(() -> ApiException.bad("Login required to approve"));
        if (p.username().equalsIgnoreCase(String.valueOf(job.getCreatedBy())))
            throw ApiException.bad("Maker-checker approval requires a different user than the job creator.");
        if (note == null || note.isBlank())
            throw ApiException.bad("Approval note / e-signature reason is required.");
        job.setApprovalStatus("APPROVED");
        job.setApprovedAt(Instant.now());
        job.setApprovedBy(p.username());
        job.setApprovalNote(note.trim());
        job.setStatus("PENDING");
        job.setMessage("Approved by " + p.username());
        ProvisionJobEntity saved = jobs.save(job);
        audit.log(p.username(), "PROVISION_APPROVED", saved.getJobType() + " '" + saved.getName() + "' id=" + saved.getId());
        Future<?> future = executor.submit(() -> run(saved.getId()));
        runningJobs.put(saved.getId(), future);
        return saved;
    }

    /** Rejection is terminal: submit a fresh job to retry after fixing the blueprint. */
    public ProvisionJobEntity reject(Long jobId, String note) {
        ProvisionJobEntity job = get(jobId);
        if (!"AWAITING_APPROVAL".equals(job.getStatus()))
            throw ApiException.bad("Job " + jobId + " is not awaiting approval (status " + job.getStatus() + ")");
        io.forgetdm.security.AccessPrincipal p = io.forgetdm.security.AccessContext.current()
                .orElseThrow(() -> ApiException.bad("Login required to reject"));
        if (note == null || note.isBlank())
            throw ApiException.bad("Rejection note / e-signature reason is required.");
        job.setApprovalStatus("REJECTED");
        job.setApprovalNote(note.trim());
        job.setStatus("REJECTED");
        job.setMessage("Rejected by " + p.username() + ": " + note.trim());
        job.setFinishedAt(Instant.now());
        ProvisionJobEntity saved = jobs.save(job);
        audit.log(p.username(), "PROVISION_REJECTED", saved.getJobType() + " '" + saved.getName() + "' id=" + saved.getId());
        return saved;
    }

    public ProvisionJobEntity cancel(Long jobId) {
        ProvisionJobEntity job = get(jobId);
        if ("AWAITING_APPROVAL".equals(job.getStatus())) {   // withdrawing an approval request — nothing enqueued yet
            job.setStatus("CANCELED");
            job.setApprovalStatus("NOT_REQUIRED");
            job.setMessage("Approval request withdrawn");
            job.setFinishedAt(Instant.now());
            jobs.save(job);
            audit.log("system", "JOB_CANCEL_REQUESTED", "id=" + jobId + " (awaiting approval)");
            return job;
        }
        if (!Set.of("PENDING", "RUNNING", "CANCEL_REQUESTED").contains(job.getStatus())) return job;
        String originalStatus = job.getStatus();
        job.setStatus("CANCEL_REQUESTED");
        job.setMessage("Cancel requested");
        Future<?> future = runningJobs.get(jobId);
        boolean futureCanceled = future != null && future.cancel(true);
        if ("PENDING".equals(originalStatus) && futureCanceled) {
            job.setStatus("CANCELED");
            job.setMessage("Canceled before start");
            job.setFinishedAt(Instant.now());
            runningJobs.remove(jobId);
        }
        jobs.save(job);
        audit.log("system", "JOB_CANCEL_REQUESTED", "id=" + jobId);
        return job;
    }

    public List<ProvisionJobEntity> list() {
        List<ProvisionJobEntity> all = jobs.findAll();
        all.sort(Comparator.comparing(ProvisionJobEntity::getId).reversed());
        return all;
    }

    public ProvisionJobEntity get(Long id) {
        return jobs.findById(id).orElseThrow(() -> ApiException.notFound("Job " + id + " not found"));
    }

    public void delete(Long id) {
        ProvisionJobEntity job = get(id);
        String s = job.getStatus();
        if ("RUNNING".equals(s) || "PENDING".equals(s) || "CANCEL_REQUESTED".equals(s))
            throw ApiException.bad("Cannot delete a job that is currently " + s + ". Cancel it first.");
        jobs.deleteById(id);
        audit.log("system", "JOB_DELETED", "id=" + id + " name='" + job.getName() + "'");
    }

    /** Daily auto-purge: remove terminal jobs whose finishedAt exceeds the configured retention window.
     *  Controlled by forgetdm.provisioning.jobRetentionDays (0 = disabled, default 90). */
    @org.springframework.scheduling.annotation.Scheduled(cron = "0 0 2 * * *")   // 2 AM every day
    public void purgeOldJobs() {
        int days = props.getProvisioning().getJobRetentionDays();
        if (days <= 0) return;
        Instant cutoff = Instant.now().minusSeconds((long) days * 86_400);
        int removed = jobs.deleteFinishedBefore(cutoff);
        if (removed > 0)
            audit.log("system", "JOB_AUTO_PURGE", removed + " job(s) older than " + days + " days removed");
    }

    public Map<String, Object> sample(Long jobId, String table, int limit) {
        ProvisionJobEntity job = get(jobId);
        if (table == null || table.isBlank()) throw ApiException.bad("table is required");
        int max = Math.max(1, Math.min(limit <= 0 ? 5 : limit, 25));
        try {
            JsonNode spec = spec(job);
            String sourceSchema = textOrNull(spec, "sourceSchema");
            String targetSchema = textOrNull(spec, "targetSchema");
            List<String> columns = new ArrayList<>();
            List<Map<String, Object>> sourceRows = List.of();
            List<Map<String, Object>> targetRows = List.of();
            if (job.getSourceId() != null) {
                DataSourceEntity src = dataSources.get(job.getSourceId());
                try (Connection in = connections.openPooled(src)) {
                    sourceSchema = DataSourceService.normalizeSchema(in, sourceSchema);
                    columns = columnsOf(in, sourceSchema, table);
                    sourceRows = sampleRows(in, sourceSchema, table, columns, max);
                }
            }
            if (job.getTargetId() != null) {
                DataSourceEntity tgt = dataSources.get(job.getTargetId());
                try (Connection out = connections.openPooled(tgt)) {
                    targetSchema = DataSourceService.normalizeSchema(out, targetSchema);
                    if (columns.isEmpty()) columns = columnsOf(out, targetSchema, table);
                    targetRows = sampleRows(out, targetSchema, table, columns, max);
                }
            }
            // In-place masks the source table itself, so "source" and "target" are the SAME physical table:
            // the originals were overwritten and there is no separate unmasked side to compare. Flag it so the
            // UI shows a single "masked result" instead of a misleading identical side-by-side.
            boolean inPlace = "IN_PLACE".equalsIgnoreCase(textOrNull(spec, "loadAction"))
                    || (job.getSourceId() != null && job.getSourceId().equals(job.getTargetId())
                        && sameIgnoreCase(sourceSchema, targetSchema));
            String message = inPlace
                    ? "In-place masking overwrote the original rows — there is no separate unmasked source. "
                      + "The rows below are the masked result."
                    : "Masking applied. Compare source (original) vs target (masked) sample rows below.";
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("jobId", jobId); out.put("table", table); out.put("columns", columns);
            out.put("sourceRows", sourceRows); out.put("targetRows", targetRows);
            out.put("inPlace", inPlace); out.put("message", message);
            return out;
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("Sample failed: " + e.getMessage()); }
    }

    // -------------------- execution --------------------

    void run(Long jobId) {
        ProvisionJobEntity job = jobs.findById(jobId).orElse(null);
        if (job == null) return;
        if ("CANCEL_REQUESTED".equals(job.getStatus())) {
            job.setStatus("CANCELED");
            job.setFinishedAt(Instant.now());
            jobs.save(job);
            runningJobs.remove(jobId);
            return;
        }
        String lockKey = provisionLockKey(job);
        if (lockKey != null) {
            Long holder = activeProvisionTargets.putIfAbsent(lockKey, jobId);
            if (holder != null) {
                job.setStatus("FAILED");
                job.setMessage("Another provisioning job (#" + holder + ") is already running against this target ("
                        + lockKey + "). Wait for it to finish or cancel it before starting another load on the same target.");
                job.setFinishedAt(Instant.now());
                jobs.save(job);
                runningJobs.remove(jobId);
                audit.log("system", "JOB_FAILED", "id=" + jobId + " reason=target-busy target=" + lockKey);
                return;
            }
        }
        job.setStatus("RUNNING"); job.setStartedAt(Instant.now()); jobs.save(job);
        try {
            boolean exchange = !"SYNTHETIC_LOAD".equals(job.getJobType()) && spec(job).hasNonNull("exchangePartition");
            long rows = exchange ? runPartitionExchange(job) : switch (job.getJobType()) {
                case "MASK_COPY" -> runMaskCopy(job);
                case "SUBSET_MASK" -> runSubsetMask(job);
                case "SYNTHETIC_LOAD" -> runSyntheticLoad(job);
                default -> throw ApiException.bad("Unknown job type");
            };
            checkCancelled(job);
            job.setRowsProcessed(rows);
            job.setStatus("COMPLETED");
            job.setMessage("OK — " + rows + " rows provisioned");
        } catch (CancellationException e) {
            job.setStatus("CANCELED");
            job.setMessage(e.getMessage() == null ? "Canceled" : e.getMessage());
            Thread.interrupted();
        } catch (Exception e) {
            if (isCancelRequested(job) || e instanceof InterruptedException) {
                job.setStatus("CANCELED");
                job.setMessage("Canceled");
                Thread.interrupted();
            } else {
                job.setStatus("FAILED");
                job.setMessage(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                markRunningTablesFailed(job);
            }
        } finally {
            runningJobs.remove(jobId);
            if (lockKey != null) activeProvisionTargets.remove(lockKey, jobId);
            jobTableStates.remove(jobId);
            tableRowProgress.remove(jobId);
            jobPhaseMap.remove(jobId);
            tableStateLastWrite.remove(jobId);
            keySaltTL.remove();
        }
        job.setFinishedAt(Instant.now());
        jobs.save(job);
        audit.log("system", "JOB_" + job.getStatus(), "id=" + job.getId() + " rows=" + job.getRowsProcessed());
    }

    // -------------------- per-table provisioning states (live progress) --------------------
    private final ConcurrentMap<Long, LinkedHashMap<String, String>> jobTableStates = new ConcurrentHashMap<>();
    /** jobId -> tableLower -> [rowsDone, rowsTotal, startedAtMs] */
    private final ConcurrentMap<Long, Map<String, long[]>> tableRowProgress = new ConcurrentHashMap<>();
    /** jobId -> current pipeline phase: EXTRACT | PROVISION */
    private final ConcurrentMap<Long, String> jobPhaseMap = new ConcurrentHashMap<>();
    /** jobId -> last time (ms) we persisted table-state JSON (throttle writes) */
    private final ConcurrentMap<Long, Long> tableStateLastWrite = new ConcurrentHashMap<>();

    private void initTableStates(ProvisionJobEntity job, List<String> tables) {
        initTableStates(job, tables, Map.of());
    }

    private void initTableStates(ProvisionJobEntity job, List<String> tables, Map<String, Integer> rowCounts) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        Map<String, long[]> rp = new LinkedHashMap<>();
        for (String t : tables) {
            m.putIfAbsent(t, "PENDING");
            long total = rowCounts.getOrDefault(t.toLowerCase(Locale.ROOT), rowCounts.getOrDefault(t, 0));
            rp.put(t.toLowerCase(Locale.ROOT), new long[]{0, total, 0});
        }
        jobTableStates.put(job.getId(), m);
        tableRowProgress.put(job.getId(), rp);
        persistTableStates(job);
    }

    private void setTableState(ProvisionJobEntity job, String table, String state) {
        LinkedHashMap<String, String> m = jobTableStates.computeIfAbsent(job.getId(), k -> new LinkedHashMap<>());
        synchronized (m) { m.put(table, state); }   // workers may update different tables concurrently
        if ("RUNNING".equals(state)) {
            // Record when this table started (for ETA calculation in the UI)
            Map<String, long[]> rp = tableRowProgress.get(job.getId());
            if (rp != null) {
                long[] arr = rp.computeIfAbsent(table.toLowerCase(Locale.ROOT), k -> new long[3]);
                if (arr[2] == 0) arr[2] = System.currentTimeMillis();
            }
        }
        persistTableStates(job);
    }

    /** Set the job-level pipeline phase (EXTRACT | PROVISION) visible in the live progress panel. */
    private void setJobPhase(ProvisionJobEntity job, String phase) {
        jobPhaseMap.put(job.getId(), phase);
        persistTableStates(job);
    }

    /** Update per-table row progress from inside copyTable; throttled to avoid flooding the DB. */
    private void updateTableRowProgress(ProvisionJobEntity job, String table, long rowsDone) {
        Map<String, long[]> rp = tableRowProgress.get(job.getId());
        if (rp == null) return;
        long[] arr = rp.get(table.toLowerCase(Locale.ROOT));
        if (arr != null) {
            arr[0] = rowsDone;
            if (arr[2] == 0) arr[2] = System.currentTimeMillis();
        }
        // Throttle: persist at most every 2 seconds to avoid a DB write on every batch
        long now = System.currentTimeMillis();
        Long last = tableStateLastWrite.get(job.getId());
        if (last == null || now - last >= 2_000) {
            tableStateLastWrite.put(job.getId(), now);
            persistTableStates(job);
        }
    }

    /** On job failure, any table left RUNNING is marked FAILED so the UI doesn't show a stuck spinner. */
    private void markRunningTablesFailed(ProvisionJobEntity job) {
        LinkedHashMap<String, String> m = jobTableStates.get(job.getId());
        if (m == null) return;
        synchronized (m) { m.replaceAll((k, v) -> "RUNNING".equals(v) ? "FAILED" : v); }
        persistTableStates(job);
    }

    private void persistTableStates(ProvisionJobEntity job) {
        LinkedHashMap<String, String> m = jobTableStates.get(job.getId());
        Map<String, long[]> rp = tableRowProgress.get(job.getId());
        boolean hasPhase = jobPhaseMap.containsKey(job.getId());
        // Persist even when tables aren't initialised yet (e.g. during EXTRACT planning phase)
        // so the UI can render the basketball court with the correct active lane right away.
        if (m == null && rp == null && !hasPhase) return;
        String phase = jobPhaseMap.getOrDefault(job.getId(), "EXTRACT");
        try {
            List<Map<String, Object>> list = new ArrayList<>();
            // First entry: job-level phase meta (consumed by the UI basketball panel)
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("table", "__meta__");
            meta.put("jobPhase", phase);
            list.add(meta);
            if (m != null) {
                synchronized (m) {   // snapshot under lock so a concurrent put can't corrupt the iteration
                    for (Map.Entry<String, String> e : m.entrySet()) {
                        Map<String, Object> o = new LinkedHashMap<>();
                        o.put("table", e.getKey());
                        o.put("state", e.getValue());
                        if (rp != null) {
                            long[] arr = rp.get(e.getKey().toLowerCase(Locale.ROOT));
                            if (arr != null) {
                                o.put("rowsDone", arr[0]);
                                o.put("rowsTotal", arr[1]);
                                o.put("startedAtMs", arr[2]);
                            }
                        }
                        list.add(o);
                    }
                }
            }
            String jsonStr = json.writeValueAsString(list);
            synchronized (job) { job.setTableStatesJson(jsonStr); jobs.save(job); }
        } catch (Exception ignore) { /* progress is best-effort; never fail the load over it */ }
    }

    /** Per-target lock key (target data source + target schema) for a job; null when no target applies. */
    private String provisionLockKey(ProvisionJobEntity job) {
        if (job.getTargetId() == null) return null;
        String schema = "";
        try { schema = spec(job).path("targetSchema").asText(""); } catch (Exception ignore) { }
        return job.getTargetId() + "|" + (schema == null ? "" : schema.toLowerCase(Locale.ROOT));
    }

    private long runMaskCopy(ProvisionJobEntity job) throws Exception {
        JsonNode spec = spec(job);
        List<String> tables = new ArrayList<>();
        if (spec.has("tables")) spec.get("tables").forEach(n -> tables.add(n.asText()));
        String sourceSchema = textOrNull(spec, "sourceSchema");
        String targetSchema = textOrNull(spec, "targetSchema");
        DataSourceEntity src = dataSources.get(req(job.getSourceId(), "sourceId"));
        DataSourceEntity tgt = dataSources.get(req(job.getTargetId(), "targetId"));
        Map<String, List<MaskingRuleEntity>> ruleMap = rulesByTable(job.getPolicyId());
        LoadOptions load = loadOptions(spec, "REPLACE");
        MaskingEngine eng = engineFor(spec, job);
        // Column overrides from Access Definition (if one is attached)
        List<TableProfileEntity> tableProfiles =
                job.getDatasetId() != null ? datasets.listProfiles(job.getDatasetId()) : List.of();
        Map<String, String> targetTableMap = targetTableMap(tableProfiles);
        Map<String, Map<String, ColumnOverrideEntity>> colOverrideMap =
                job.getDatasetId() != null ? datasets.overrideMap(job.getDatasetId()) : Map.of();
        Map<String, String> customPkMap =
                job.getDatasetId() != null ? datasets.customPkMap(job.getDatasetId()) : Map.of();

        setJobPhase(job, "EXTRACT");
        long total = 0;
        try (Connection in = connections.open(src); Connection out = connections.open(tgt)) {
            sourceSchema = DataSourceService.normalizeSchema(in, sourceSchema);
            targetSchema = DataSourceService.normalizeSchema(out, targetSchema);
            out.setAutoCommit(false);
            tuneBulkConnection(out);
            checkCancelled(job);
            List<String> toCopy = referentialLoadOrder(in, sourceSchema, tables.isEmpty() ? allTables(in, sourceSchema) : tables);
            Map<String, String> sharedSalts = buildKeyConsistencySalts(collectKeyEdges(in, sourceSchema, toCopy, null));   // FK-consistent key masking
            keySaltTL.set(sharedSalts);
            if (load.isInPlace()) {
                requireInPlaceSameTable(src, tgt, sourceSchema, targetSchema, toCopy, targetTableMap);
                // In-place tables are independent of each other → mask them in parallel.
                initTableStates(job, toCopy);
                setJobPhase(job, "PROVISION");
                Map<String, List<MaskingRuleEntity>> resolvedRules = new HashMap<>();
                for (String table : toCopy) {
                    List<MaskingRuleEntity> rr = rulesFor(ruleMap, sourceSchema, table);
                    resolvedRules.put(table.toLowerCase(Locale.ROOT), rr == null ? List.of() : rr);
                }
                DbMaskPushdown.Session pushdown = prepareDbPushdown(out, sourceSchema, eng, toCopy, resolvedRules);
                try {
                    total += maskTablesInPlace(toCopy, src, tgt, sourceSchema, eng, job, spec,
                            colOverrideMap, resolvedRules, customPkMap, sharedSalts, pushdown);
                } finally {
                    if (pushdown != null) pushdown.cleanup();   // drop the secret-bearing function/tables
                }
                checkCancelled(job);
                return total;
            } else {
                rejectSelfMappedTables(src, tgt, sourceSchema, targetSchema, toCopy, targetTableMap);
                rejectDuplicateTargets(toCopy, targetSchema, targetTableMap);
                prepareTargets(out, targetSchema, targetTablesFor(toCopy, targetTableMap), load);
                initTableStates(job, toCopy);
                setJobPhase(job, "PROVISION");
            }
            for (String table : toCopy) {
                checkCancelled(job);
                if (load.truncateOnly()) {
                    // Truncation was already done by prepareTargets; mark each table done so the UI reflects it.
                    setTableState(job, table, "DONE");
                    continue;
                }
                setTableState(job, table, "RUNNING");
                Map<String, ColumnOverrideEntity> tableOverrides = colOverrideMap.getOrDefault(table.toLowerCase(Locale.ROOT), Map.of());
                String targetTable = targetTableFor(table, targetTableMap);
                total += copyTable(eng, in, sourceSchema, out, targetSchema, table, targetTable, null, null,
                        rulesFor(ruleMap, sourceSchema, table), job, null, null, load, tableOverrides, customPkMap);
                out.commit();   // commit per table to bound the transaction on big multi-table loads
                setTableState(job, table, "DONE");
            }
            checkCancelled(job);
            out.commit();
        }
        return total;
    }

    /**
     * Partition EXCHANGE load (Phase 2). Loads + masks one partition's worth of source rows into a standalone
     * staging table, then swaps it into the target partition as a metadata-only operation. Oracle first
     * (EXCHANGE PARTITION); SQL Server SWITCH / Postgres ATTACH to follow. Triggered by spec.exchangePartition.
     *
     * spec: exchangePartition (partition name), exchangeTable (target table; or the dataset's single table),
     *       filter (scopes the source rows to that partition's range — caller's responsibility), exchangeValidate
     *       (default false → WITHOUT VALIDATION for speed; the partition key column must NOT be masked).
     */
    private long runPartitionExchange(ProvisionJobEntity job) throws Exception {
        JsonNode spec = spec(job);
        String partition = textOrNull(spec, "exchangePartition");
        if (partition == null) throw ApiException.bad("exchangePartition is required");
        if (!partition.matches("[A-Za-z0-9_$#]+")) throw ApiException.bad("Invalid partition name: " + partition);
        DataSourceEntity src = dataSources.get(req(job.getSourceId(), "sourceId"));
        DataSourceEntity tgt = dataSources.get(req(job.getTargetId(), "targetId"));
        String sourceSchema = textOrNull(spec, "sourceSchema");
        String targetSchema = textOrNull(spec, "targetSchema");
        String filter = textOrNull(spec, "filter");
        if (filter != null) SubsetService.guardFilter(filter);
        String table = textOrNull(spec, "exchangeTable");
        if (table == null && job.getDatasetId() != null) {
            List<String> inc = datasets.listProfiles(job.getDatasetId()).stream()
                    .filter(TableProfileEntity::isIncluded).map(TableProfileEntity::getTableName).toList();
            if (inc.size() == 1) table = inc.get(0);
            else throw ApiException.bad("Partition exchange targets one table; set 'exchangeTable' (included: " + inc + ")");
        }
        if (table == null) throw ApiException.bad("exchangeTable is required for partition exchange");
        final String tableF = table;

        Map<String, List<MaskingRuleEntity>> ruleMap = rulesByTable(job.getPolicyId());
        MaskingEngine eng = engineFor(spec, job);
        LoadOptions load = loadOptions(spec, "REPLACE");
        boolean validate = spec.path("exchangeValidate").asBoolean(false);
        setJobPhase(job, "PROVISION");

        try (Connection in = connections.open(src); Connection out = connections.open(tgt)) {
            sourceSchema = DataSourceService.normalizeSchema(in, sourceSchema);
            targetSchema = DataSourceService.normalizeSchema(out, targetSchema);
            out.setAutoCommit(false);
            tuneBulkConnection(out);
            if (SqlDialect.fromConnection(out) != SqlDialect.ORACLE)
                throw ApiException.bad("Partition exchange is implemented for Oracle first; SQL Server SWITCH / Postgres ATTACH are next.");
            if (!isPartitionedTarget(out, targetSchema, tableF))
                throw ApiException.bad("Target table " + tableF + " is not partitioned.");

            String stg = "fdm_xchg_" + sanitize(tableF) + "_" + job.getId();
            try (Statement st = out.createStatement()) { st.execute("DROP TABLE " + q(targetSchema, stg) + " PURGE"); }
            catch (SQLException ignore) { /* may not exist */ }
            try (Statement st = out.createStatement()) {
                st.execute("CREATE TABLE " + q(targetSchema, stg) + " AS SELECT * FROM " + q(targetSchema, tableF) + " WHERE 1=0");
            }
            out.commit();

            initTableStates(job, List.of(tableF));
            setTableState(job, tableF, "RUNNING");
            long rows;
            try {
                keySaltTL.set(buildKeyConsistencySalts(collectKeyEdges(in, sourceSchema, List.of(tableF), null)));
                Map<String, ColumnOverrideEntity> overrides = job.getDatasetId() != null
                        ? datasets.overrideMap(job.getDatasetId()).getOrDefault(tableF.toLowerCase(Locale.ROOT), Map.of()) : Map.of();
                Map<String, String> customPk = job.getDatasetId() != null ? datasets.customPkMap(job.getDatasetId()) : Map.of();
                progressMessage(job, "Loading + masking partition '" + partition + "' of " + tableF + " into staging");
                rows = copyTable(eng, in, sourceSchema, out, targetSchema, tableF, stg, null, null,
                        rulesFor(ruleMap, sourceSchema, tableF), job, filter, null, load, overrides, customPk);
                out.commit();
                progressMessage(job, "Exchanging partition '" + partition + "' (" + rows + " rows)");
                try (Statement st = out.createStatement()) {
                    st.execute("ALTER TABLE " + q(targetSchema, tableF) + " EXCHANGE PARTITION " + partition
                            + " WITH TABLE " + q(targetSchema, stg) + " EXCLUDING INDEXES "
                            + (validate ? "WITH VALIDATION" : "WITHOUT VALIDATION"));
                    st.execute("ALTER TABLE " + q(targetSchema, tableF) + " MODIFY PARTITION " + partition
                            + " REBUILD UNUSABLE LOCAL INDEXES");
                }
                out.commit();
                setTableState(job, tableF, "DONE");
                audit.log("system", "PARTITION_EXCHANGE", "table=" + tableF + " partition=" + partition + " rows=" + rows);
            } catch (SQLException | RuntimeException e) {
                try { out.rollback(); } catch (SQLException ignore) { }
                markRunningTablesFailed(job);
                throw e;
            } finally {
                keySaltTL.remove();
                try (Statement st = out.createStatement()) { st.execute("DROP TABLE " + q(targetSchema, stg) + " PURGE"); }
                catch (SQLException ignore) { }
                try { out.commit(); } catch (SQLException ignore) { }
            }
            return rows;
        }
    }

    private long runSubsetMask(ProvisionJobEntity job) throws Exception {
        JsonNode spec = spec(job);
        String sourceSchema = textOrNull(spec, "sourceSchema");
        String targetSchema = textOrNull(spec, "targetSchema");
        LoadOptions load = loadOptions(spec, "REPLACE");
        DataSourceEntity src = dataSources.get(req(job.getSourceId(), "sourceId"));
        DataSourceEntity tgt = dataSources.get(req(job.getTargetId(), "targetId"));
        Map<String, List<MaskingRuleEntity>> ruleMap = rulesByTable(job.getPolicyId());
        MaskingEngine eng = engineFor(spec, job);

        SubsetService.SubsetPlan plan;
        Map<String, Map<String, ColumnOverrideEntity>> colOverrideMap = Map.of();
        Map<String, String> customPkMap = Map.of();
        Map<String, String> targetTableMap = Map.of();
        Map<String, TableProfileEntity> profileByTable = Map.of();
        Map<Long, Map<String, List<MaskingRuleEntity>>> profileRuleCache = new HashMap<>();
        List<String> includedTableNames = null;   // all included profiles (used for in-place, which ignores the subset closure)
        List<SubsetService.UserRelEdge> softEdges = List.of();   // custom + RI-registry relationships, for key-salt grouping
        Map<String, Integer> rowLimitByTable = new HashMap<>();   // table-map per-table row caps (Table Map level)

        if (job.getDatasetId() != null) {
            // ── Access Definition path ──────────────────────────────────────
            DataSetDefinitionEntity def = datasets.get(job.getDatasetId());
            // Driver and filter can come from the definition, but spec can override
            String driver = spec.path("driverTable").asText(null);
            if (driver == null || driver.isBlank()) driver = def.getDriverTable();
            String driverFilter = spec.path("filter").asText(null);
            if (driverFilter == null || driverFilter.isBlank()) driverFilter = def.getDriverFilter();
            String defSchema = sourceSchema != null ? sourceSchema : def.getSchemaName();
            sourceSchema = defSchema;
            if (targetSchema == null || targetSchema.isBlank()) targetSchema = def.getTargetSchemaName();
            int maxRows = spec.path("maxDriverRows").asInt(0);
            List<TableProfileEntity>                          tableProfiles  = datasets.listProfiles(job.getDatasetId());
            List<UserDefinedRelationshipEntity>              userRels       = datasets.listUserRels(job.getDatasetId());
            List<RelationshipTraversalRuleEntity>            tRules         = datasets.listTraversalRules(job.getDatasetId());
            List<SubsetService.TableDirective>               directives     = datasets.toDirectives(tableProfiles);
            List<SubsetService.UserRelEdge>                  userEdges      = datasets.toUserEdges(userRels);
            Map<String, String>                              travDirections  = datasets.toTraversalDirectionMap(tRules);
            targetTableMap = targetTableMap(tableProfiles);
            profileByTable = profileByTable(tableProfiles);
            includedTableNames = tableProfiles.stream().filter(TableProfileEntity::isIncluded)
                    .map(TableProfileEntity::getTableName).toList();
            // Table-Map per-table row caps (hard cap applied at copy time, in addition to the plan's capKeys).
            for (TableProfileEntity p : tableProfiles)
                if (p.isIncluded() && p.getRowLimit() != null && p.getRowLimit() > 0)
                    rowLimitByTable.put(p.getTableName().toLowerCase(Locale.ROOT), p.getRowLimit());
            // In-place masking bypasses the subset plan, so the per-table filter / row limit (table-map level,
            // falling back to the blueprint driver max) wouldn't otherwise apply. Carry them into the spec as
            // tableCriteria, which the in-place masker reads per table.
            if (load.isInPlace() && spec instanceof com.fasterxml.jackson.databind.node.ObjectNode specObj
                    && !specObj.has("tableCriteria")) {
                com.fasterxml.jackson.databind.node.ArrayNode tcArr = specObj.putArray("tableCriteria");
                for (TableProfileEntity p : tableProfiles) {
                    if (!p.isIncluded()) continue;
                    Integer lim = (p.getRowLimit() != null && p.getRowLimit() > 0) ? p.getRowLimit()
                            : (maxRows > 0 ? maxRows : null);
                    String f = p.getFilterExpr();
                    boolean hasFilter = f != null && !f.isBlank();
                    if (lim == null && !hasFilter) continue;
                    com.fasterxml.jackson.databind.node.ObjectNode tcRow = tcArr.addObject();
                    tcRow.put("table", p.getTableName());
                    if (hasFilter) tcRow.put("filter", f);
                    if (lim != null) tcRow.put("rowLimit", lim);
                }
            }
            if (hasProfileSourceOverrides(def, defSchema, tableProfiles)) {
                return runMixedSourceDataScopeMask(job, spec, def, tableProfiles, tgt,
                        defSchema, targetSchema, load, eng);
            }
            colOverrideMap = datasets.overrideMap(job.getDatasetId());
            customPkMap = datasets.customPkMap(job.getDatasetId());
            // #5: augment with the tool-level RI registry so relationships/PKs defined once (org-wide) apply
            // here without re-entering them per blueprint. Additive only — blueprint definitions take precedence.
            // (Runs on the worker thread with no user context, so this resolves GLOBAL definitions; per-user/
            // group consumption needs job-owner plumbing and is a follow-up.)
            try {
                Map<String, Object> ri = riRegistry.resolve(def.getDataSourceId(), defSchema);
                List<SubsetService.UserRelEdge> withRi = new ArrayList<>(userEdges);
                Object relsObj = ri.get("relationships");
                if (relsObj instanceof List<?> rels) {
                    for (Object o : rels) {
                        if (!(o instanceof Map<?, ?> r)) continue;
                        String ct = asStr(r.get("childTable")), cc = asStr(r.get("childColumns"));
                        String pt = asStr(r.get("parentTable")), pc = asStr(r.get("parentColumns"));
                        if (ct == null || cc == null || pt == null || pc == null) continue;
                        String[] ccs = cc.split(","), pcs = pc.split(",");
                        for (int i = 0; i < Math.min(ccs.length, pcs.length); i++)
                            withRi.add(new SubsetService.UserRelEdge(pt.trim(), pcs[i].trim(), ct.trim(), ccs[i].trim(), "ri-registry"));
                    }
                }
                userEdges = withRi;
                Object pksObj = ri.get("primaryKeys");
                if (pksObj instanceof List<?> pks && !pks.isEmpty()) {
                    Map<String, String> withPk = new HashMap<>(customPkMap);
                    for (Object o : pks) {
                        if (!(o instanceof Map<?, ?> k)) continue;
                        String t = asStr(k.get("tableName")), cols = asStr(k.get("keyColumns"));
                        if (t != null && cols != null) withPk.putIfAbsent(t.toLowerCase(Locale.ROOT), cols);
                    }
                    customPkMap = withPk;
                }
            } catch (Exception ignore) { /* registry is optional; never block a load over it */ }
            softEdges = userEdges;   // expose to the shared load section below (key-salt grouping)
            if (load.isInPlace()) {
                // In-place masks every INCLUDED table on the source itself — no separate target, no driver,
                // and no FK subset closure. So skip planning entirely (the load loop uses includedTableNames).
                plan = new SubsetService.SubsetPlan();
            } else {
                boolean hasIndependentSeed = directives.stream()
                        .anyMatch(d -> d.included() && "INDEPENDENT".equalsIgnoreCase(d.referentialStrategy()));
                if ((driver == null || driver.isBlank()) && !hasIndependentSeed) {
                    throw ApiException.bad("Set a driver table, or mark at least one table's strategy as "
                            + "Independent (a start table) for a multi-start extraction.");
                }
                long includedTables = directives.stream().filter(SubsetService.TableDirective::included).count();
                setJobPhase(job, "EXTRACT");
                progressMessage(job, "Planning DataScope subset for " + includedTables + " included table(s)");
                checkCancelled(job);
                plan = subsets.planWithDirectives(src.getId(), defSchema, driver, driverFilter,
                        maxRows, def.isGlobalQ1(), def.isGlobalQ2(),
                        directives, userEdges, travDirections);
            }
        } else {
            // ── Legacy path (no Access Definition) ──────────────────────────
            String driver = spec.path("driverTable").asText(null);
            if (driver == null) throw ApiException.bad("spec.driverTable required for SUBSET_MASK");
            String filter = spec.path("filter").asText(null);
            int maxRows = spec.path("maxDriverRows").asInt(0);
            boolean includeRelated = spec.path("includeRelated").asBoolean(true);
            boolean includeParents = spec.has("includeParents") ? spec.path("includeParents").asBoolean() : includeRelated;
            boolean includeChildren = spec.has("includeChildren") ? spec.path("includeChildren").asBoolean() : includeRelated;
            setJobPhase(job, "EXTRACT");
            progressMessage(job, "Planning subset");
            checkCancelled(job);
            plan = subsets.plan(src.getId(), sourceSchema, driver, filter, maxRows,
                    includeRelated, includeParents, includeChildren, tableCriteria(spec));
        }

        long total = 0;
        checkCancelled(job);
        progressMessage(job, "Loading " + plan.loadOrder.size() + " planned table(s)");
        try (Connection in = connections.open(src); Connection out = connections.open(tgt)) {
            sourceSchema = DataSourceService.normalizeSchema(in, sourceSchema);
            targetSchema = DataSourceService.normalizeSchema(out, targetSchema);
            out.setAutoCommit(false);
            tuneBulkConnection(out);
            // FK-consistent key masking: related PK/FK key columns (DB FKs + soft FKs) share one salt so masking
            // both sides with the same function preserves joins; warn when the two sides are masked inconsistently.
            Set<String> riTables = new LinkedHashSet<>(plan.loadOrder);
            if (includedTableNames != null) riTables.addAll(includedTableNames);
            List<KeyEdge> keyEdges = collectKeyEdges(in, sourceSchema, riTables, softEdges);
            Map<String, String> sharedSalts = buildKeyConsistencySalts(keyEdges);
            keySaltTL.set(sharedSalts);
            for (String w : keyMaskWarnings(keyEdges, ruleMap, profileRuleCache, profileByTable, colOverrideMap, sourceSchema)) {
                audit.log("system", "DATASCOPE_RI_MASK_WARNING", "job=" + job.getId() + " " + w);
                progressMessage(job, "⚠ " + w);
            }
            Map<String, String> tableMapForLoad = targetTableMap;
            if (load.isInPlace()) {
                // In-place masks the source tables themselves (source == target). When a SUBSET is in effect
                // (driver limit/filter or any per-table limit/filter) we also reduce each table to its
                // referentially-correct subset — i.e. delete the non-subset rows in place (Optim-style),
                // child→parent, so the table ends up as the masked subset. This is destructive and irreversible
                // on that database, so it requires explicit user confirmation.
                List<String> tables = (includedTableNames != null && !includedTableNames.isEmpty())
                        ? distinctTables(includedTableNames) : plan.loadOrder;
                requireInPlaceSameTable(src, tgt, sourceSchema, targetSchema, tables, tableMapForLoad);
                boolean inPlaceSubset = spec.path("maxDriverRows").asInt(0) > 0
                        || !rowLimitByTable.isEmpty()
                        || textOrNull(spec, "filter") != null
                        || profileByTable.values().stream()
                                .anyMatch(p -> p.getFilterExpr() != null && !p.getFilterExpr().isBlank());
                if (inPlaceSubset && !spec.path("confirmInPlaceSubsetDelete").asBoolean(false)) {
                    throw ApiException.bad("In-place + subset will DELETE the non-matching rows from the selected "
                            + "table(s) in '" + src.getName() + "'. This permanently removes data from that database "
                            + "and cannot be undone. Re-run with confirmation to proceed.");
                }
                if (inPlaceSubset) {
                    setJobPhase(job, "EXTRACT");
                    deleteNonSubsetRowsInPlace(out, sourceSchema, plan, job);   // reduce each table to its subset, FK-safe
                }
                // Count rows AFTER any subset delete so the progress % reflects what will actually be masked.
                initTableStates(job, tables, inPlaceRowCounts(in, sourceSchema, tables, rowLimitByTable,
                        spec.path("maxDriverRows").asInt(0)));
                setJobPhase(job, "PROVISION");
                Map<String, List<MaskingRuleEntity>> resolvedRules = new HashMap<>();
                for (String table : tables) {
                    String key = table.toLowerCase(Locale.ROOT);
                    List<MaskingRuleEntity> rr = effectiveTableRules(ruleMap, profileRuleCache, profileByTable.get(key), sourceSchema, table);
                    resolvedRules.put(key, rr == null ? List.of() : rr);
                }
                DbMaskPushdown.Session pushdown = prepareDbPushdown(out, sourceSchema, eng, tables, resolvedRules);
                try {
                    total += maskTablesInPlace(tables, src, tgt, sourceSchema, eng, job, spec,
                            colOverrideMap, resolvedRules, customPkMap, sharedSalts, pushdown);
                } finally {
                    if (pushdown != null) pushdown.cleanup();   // drop the secret-bearing function/tables
                }
            } else {
                rejectSelfMappedTables(src, tgt, sourceSchema, targetSchema, plan.loadOrder, tableMapForLoad);
                rejectDuplicateTargets(plan.loadOrder.stream().filter(t -> plan.slices.get(t) != null).toList(),
                        targetSchema, tableMapForLoad);
                prepareTargets(out, targetSchema, plan.loadOrder.stream()
                        .filter(table -> plan.slices.get(table) != null)
                        .map(table -> targetTableFor(table, tableMapForLoad))
                        .toList(), load);
                initTableStates(job, plan.loadOrder.stream().filter(t -> plan.slices.get(t) != null).toList(),
                        plan.rowCounts != null ? plan.rowCounts : Map.of());
                setJobPhase(job, "PROVISION");
                List<String> copyTables = plan.loadOrder.stream().filter(t -> plan.slices.get(t) != null).toList();
                List<String> disabledIndexes = maybeDisableIndexesForLoad(out, targetSchema, copyTables, targetTableMap);
                try {
                int copyParallel = copyParallelism();
                if (copyParallel > 1 && !load.truncateOnly() && copyTables.size() > 1) {
                    Map<String, List<MaskingRuleEntity>> resolvedRules = new HashMap<>();
                    for (String t : copyTables) {
                        List<MaskingRuleEntity> rr = effectiveTableRules(ruleMap, profileRuleCache,
                                profileByTable.get(t.toLowerCase(Locale.ROOT)), sourceSchema, t);
                        resolvedRules.put(t.toLowerCase(Locale.ROOT), rr == null ? List.of() : rr);
                    }
                    List<KeyEdge> copyEdges = collectKeyEdges(in, sourceSchema, copyTables, softEdges);
                    total += copyTablesParallel(copyTables, plan, copyEdges, Math.min(copyParallel, copyTables.size()),
                            src, tgt, sourceSchema, targetSchema, eng, job, resolvedRules, colOverrideMap,
                            targetTableMap, rowLimitByTable, customPkMap, load, sharedSalts);
                } else {
                for (String table : plan.loadOrder) {
                    checkCancelled(job);
                    SubsetService.TableSlice slice = plan.slices.get(table);
                    if (slice == null) continue;
                    if (load.truncateOnly()) {
                        // Truncation was already done by prepareTargets; mark done so the UI reflects it.
                        setTableState(job, table, "DONE");
                        continue;
                    }
                    setTableState(job, table, "RUNNING");
                    Map<String, ColumnOverrideEntity> tableOverrides =
                            colOverrideMap.getOrDefault(table.toLowerCase(Locale.ROOT), Map.of());
                    List<MaskingRuleEntity> tableRules =
                            effectiveTableRules(ruleMap, profileRuleCache, profileByTable.get(table.toLowerCase(Locale.ROOT)), sourceSchema, table);
                    String targetTable = targetTableFor(table, targetTableMap);
                    Integer perTableLimit = rowLimitByTable.get(table.toLowerCase(Locale.ROOT));
                    if (slice.keyless()) {
                        Integer lim = perTableLimit != null ? perTableLimit : slice.rowLimit();
                        total += copyTable(eng, in, sourceSchema, out, targetSchema, table, targetTable, null, null,
                                tableRules, job, slice.filter(), lim, load, tableOverrides, customPkMap);
                    } else if (!slice.pkValues().isEmpty()) {
                        total += copyTable(eng, in, sourceSchema, out, targetSchema, table, targetTable, slice.pkColumn(), slice.pkValues(),
                                tableRules, job, null, perTableLimit, load, tableOverrides, customPkMap);
                    }
                    out.commit();   // commit each table so a late failure doesn't roll back a huge multi-table load
                    setTableState(job, table, "DONE");
                }
                }
                } finally {
                    rebuildIndexesAfterLoad(out, disabledIndexes);
                }
            }
            checkCancelled(job);
            out.commit();
        }
        return total;
    }

    private long runMixedSourceDataScopeMask(ProvisionJobEntity job, JsonNode spec, DataSetDefinitionEntity def,
                                             List<TableProfileEntity> tableProfiles, DataSourceEntity tgt,
                                             String defaultSourceSchema, String targetSchema, LoadOptions load,
                                             MaskingEngine eng) throws Exception {
        if (load.isInPlace()) {
            throw ApiException.bad("Mixed-source DataScope table maps cannot use In-place masking. "
                    + "Use Replace/Insert/Update into a target, or keep all rows on the same source for In-place.");
        }
        List<TableProfileEntity> included = tableProfiles.stream()
                .filter(TableProfileEntity::isIncluded)
                .toList();
        if (included.isEmpty()) return 0;

        Map<String, Map<String, ColumnOverrideEntity>> colOverrideMap = datasets.overrideMap(job.getDatasetId());
        Map<String, String> customPkMap = datasets.customPkMap(job.getDatasetId());
        Map<String, String> targetTableMap = targetTableMap(tableProfiles);
        // Top-level job/blueprint policy = the default for every table (per-table policies override it),
        // exactly like the single-source path. (Previously this path passed an empty map → nothing masked.)
        Map<String, List<MaskingRuleEntity>> ruleMap = rulesByTable(job.getPolicyId());
        Map<Long, Map<String, List<MaskingRuleEntity>>> profileRuleCache = new HashMap<>();
        Map<String, String> sourceSchemaByTable = new HashMap<>();
        Map<String, DataSourceEntity> sourceByTable = new HashMap<>();

        long total = 0;
        progressMessage(job, "Loading mixed-source DataScope table map (" + included.size() + " table(s))");
        checkCancelled(job);
        try (Connection out = connections.open(tgt)) {
            targetSchema = DataSourceService.normalizeSchema(out, targetSchema);
            out.setAutoCommit(false);
            tuneBulkConnection(out);

            for (TableProfileEntity profile : included) {
                checkCancelled(job);
                    String table = profile.getTableName();
                    DataSourceEntity src = dataSources.get(profileSourceDataSourceId(def, profile));
                    try (Connection in = connections.open(src)) {
                        String sourceSchema = DataSourceService.normalizeSchema(in,
                            profileSourceSchemaName(def, defaultSourceSchema, profile));
                    rejectSelfMappedTables(src, tgt, sourceSchema, targetSchema,
                            List.of(table), targetTableMap);
                    sourceSchemaByTable.put(table.toLowerCase(Locale.ROOT), sourceSchema);
                    sourceByTable.put(table.toLowerCase(Locale.ROOT), src);
                }
            }

            List<String> allTableNames = included.stream().map(TableProfileEntity::getTableName).toList();
            rejectDuplicateTargets(allTableNames, targetSchema, targetTableMap);
            prepareTargets(out, targetSchema, targetTablesFor(allTableNames, targetTableMap), load);
            if (load.truncateOnly()) {
                // Surface the truncation in the basketball panel before returning.
                initTableStates(job, allTableNames);
                setJobPhase(job, "PROVISION");
                allTableNames.forEach(t -> setTableState(job, t, "DONE"));
                out.commit();
                return 0;
            }

            initTableStates(job, allTableNames);
            setJobPhase(job, "PROVISION");
            for (TableProfileEntity profile : included) {
                checkCancelled(job);
                String table = profile.getTableName();
                String tableKey = table.toLowerCase(Locale.ROOT);
                DataSourceEntity src = sourceByTable.get(tableKey);
                String sourceSchema = sourceSchemaByTable.get(tableKey);
                String targetTable = targetTableFor(table, targetTableMap);
                Map<String, ColumnOverrideEntity> tableOverrides =
                        colOverrideMap.getOrDefault(tableKey, Map.of());
                List<MaskingRuleEntity> tableRules =
                        effectiveTableRules(ruleMap, profileRuleCache, profile, sourceSchema, table);
                progressMessage(job, "Loading " + table + " from " + src.getName()
                        + (sourceSchema == null || sourceSchema.isBlank() ? "" : " / " + sourceSchema)
                        + " -> " + targetTable);
                setTableState(job, table, "RUNNING");
                try (Connection in = connections.open(src)) {
                    total += copyTable(eng, in, sourceSchema, out, targetSchema, table, targetTable, null, null,
                            tableRules, job,
                            profile.getFilterExpr(), profile.getRowLimit(), load, tableOverrides, customPkMap);
                }
                out.commit();
                setTableState(job, table, "DONE");
            }
            checkCancelled(job);
            out.commit();
        }
        return total;
    }

    /**
     * Per-job masking engine. spec.maskingSeed (optional) re-keys determinism:
     * same seed -> repeatable results, different seed -> a different masked universe.
     * Omitted/blank -> the project-default engine.
     */
    private MaskingEngine engineFor(JsonNode spec, ProvisionJobEntity job) {
        String seed = textOrNull(spec, "maskingSeed");
        if (seed == null) return engine;
        audit.log("system", "MASKING_SEED_USED", "job=" + job.getId() + " seed=" + seed);
        return engine.withSeed(seed);
    }

    private long runSyntheticLoad(ProvisionJobEntity job) throws Exception {
        JsonNode spec = spec(job);
        String table = spec.path("table").asText(null);
        long count = spec.path("rowCount").asLong(100);
        if (table == null || !spec.has("columns")) throw ApiException.bad("spec needs table + columns[] for SYNTHETIC_LOAD");
        if (count > 5_000_000) throw ApiException.bad("rowCount capped at 5,000,000");
        String targetSchema = textOrNull(spec, "targetSchema");

        record Col(String name, BiFunction<Long, Random, String> gen) {}
        List<Col> cols = new ArrayList<>();
        for (JsonNode cn : spec.get("columns")) {
            cols.add(new Col(cn.path("name").asText(),
                    Generators.of(cn.path("generator").asText("SEQUENCE"),
                            cn.path("param1").asText(null), cn.path("param2").asText(null))));
        }
        DataSourceEntity tgt = dataSources.get(req(job.getTargetId(), "targetId"));
        long seed = spec.path("seed").asLong(42);
        Random rng = new Random(seed);
        LoadOptions load = loadOptions(spec, spec.path("truncateTarget").asBoolean(false) ? "REPLACE" : "INSERT");
        int batch = load.batchSize();

        long written = 0;
        try (Connection out = connections.open(tgt)) {
            targetSchema = DataSourceService.normalizeSchema(out, targetSchema);
            out.setAutoCommit(false);
            tuneBulkConnection(out);
            prepareTarget(out, targetSchema, table, load);
            if (!load.truncateOnly()) {
                List<String> names = cols.stream().map(Col::name).toList();
                Map<String, Integer> targetTypes = columnTypes(out, targetSchema, table);
                int[] sqlTypes = names.stream()
                        .mapToInt(name -> targetTypes.getOrDefault(name.toLowerCase(Locale.ROOT), Types.VARCHAR))
                        .toArray();
                List<String> keys = keyColumns(out, targetSchema, table, names, load);
                SqlDialect synthDialect = SqlDialect.fromConnection(out);
                try (TableLoadWriter writer = new TableLoadWriter(out, targetSchema, table, names, sqlTypes, load, keys, synthDialect)) {
                    for (long i = 1; i <= count; i++) {
                        if (i == 1 || i % batch == 0) checkCancelled(job);
                        Object[] values = new Object[cols.size()];
                        for (int cIdx = 0; cIdx < cols.size(); cIdx++)
                            values[cIdx] = coerce(cols.get(cIdx).gen().apply(i, rng), sqlTypes[cIdx]);
                        writer.write(values);
                        written = i;
                        if (i % batch == 0) { writer.flush(); progress(job, written); }
                    }
                    writer.flush();
                }
            }
            out.commit();
        }
        progress(job, written);
        return written;
    }

    // -------------------- shared copy/mask plumbing --------------------

    private record ColumnPlan(String targetColumn, String sourceColumn,
                              ColumnOverrideEntity override, int sqlType) {}

    /** Parallelism for the copy/subset load path. Env FORGETDM_COPY_PARALLELISM; default 1 (sequential, unchanged). */
    private int copyParallelism() {
        try {
            String v = System.getenv("FORGETDM_COPY_PARALLELISM");
            if (v != null && !v.isBlank()) return Math.max(1, Integer.parseInt(v.trim()));
        } catch (Exception ignore) { /* default */ }
        return 1;
    }

    /** Topological FK levels among the given tables: a referenced parent gets a lower level than its child. */
    private Map<String, Integer> fkLevels(List<String> tables, List<KeyEdge> edges) {
        Set<String> inSet = new HashSet<>();
        for (String t : tables) inSet.add(t.toLowerCase(Locale.ROOT));
        Map<String, List<String>> parents = new HashMap<>();   // childLower -> [parentLower]
        if (edges != null) for (KeyEdge e : edges) {
            String p = e.parentTable(), c = e.childTable();
            if (p == null || c == null || p.equalsIgnoreCase(c)) continue;
            if (!inSet.contains(p) || !inSet.contains(c)) continue;
            parents.computeIfAbsent(c, k -> new ArrayList<>()).add(p);
        }
        Map<String, Integer> level = new HashMap<>();
        for (String t : tables) computeFkLevel(t.toLowerCase(Locale.ROOT), parents, level, new HashSet<>());
        return level;
    }
    private int computeFkLevel(String t, Map<String, List<String>> parents, Map<String, Integer> memo, Set<String> visiting) {
        Integer cached = memo.get(t);
        if (cached != null) return cached;
        if (!visiting.add(t)) return 0;   // FK cycle guard
        int lvl = 0;
        for (String p : parents.getOrDefault(t, List.of()))
            lvl = Math.max(lvl, computeFkLevel(p, parents, memo, visiting) + 1);
        visiting.remove(t);
        memo.put(t, lvl);
        return lvl;
    }

    /**
     * Load the subset tables in parallel, FK-level by FK-level: all tables at the same level run concurrently,
     * levels run in order so a child is never inserted before its parents. Each worker uses its own connections,
     * the shared FK-consistency salts, and one shared progress sink. Off unless FORGETDM_COPY_PARALLELISM &gt; 1.
     */
    private long copyTablesParallel(List<String> tables, SubsetService.SubsetPlan plan, List<KeyEdge> edges,
            int parallelism, DataSourceEntity src, DataSourceEntity tgt, String sourceSchema, String targetSchema,
            MaskingEngine eng, ProvisionJobEntity job,
            Map<String, List<MaskingRuleEntity>> resolvedRules,
            Map<String, Map<String, ColumnOverrideEntity>> colOverrideMap,
            Map<String, String> targetTableMap, Map<String, Integer> rowLimitByTable,
            Map<String, String> customPkMap, LoadOptions load, Map<String, String> sharedSalts) throws Exception {
        Map<String, Integer> levels = fkLevels(tables, edges);
        java.util.TreeMap<Integer, List<String>> byLevel = new java.util.TreeMap<>();
        for (String t : tables)
            byLevel.computeIfAbsent(levels.getOrDefault(t.toLowerCase(Locale.ROOT), 0), k -> new ArrayList<>()).add(t);

        AtomicLong counter = new AtomicLong(job.getRowsProcessed());
        ProgressSink sink = sharedSink(job, counter);
        AtomicLong total = new AtomicLong();
        progressMessage(job, "Loading " + tables.size() + " table(s), up to " + parallelism + " in parallel (FK-level)");
        ExecutorService pool = Executors.newFixedThreadPool(parallelism, r -> {
            Thread th = new Thread(r, "fdm-copy"); th.setDaemon(true); return th;
        });
        Exception failure = null;
        try {
            for (List<String> group : byLevel.values()) {   // ascending level → parents before children
                if (failure != null) break;
                List<Future<?>> futures = new ArrayList<>();
                for (String table : group) {
                    futures.add(pool.submit(() -> {
                        progressSinkTL.set(sink); keySaltTL.set(sharedSalts);
                        try {
                            copyOneSubsetTable(table, plan, src, tgt, sourceSchema, targetSchema, eng, job,
                                    resolvedRules, colOverrideMap, targetTableMap, rowLimitByTable, customPkMap, load, total);
                        } finally { progressSinkTL.remove(); keySaltTL.remove(); }
                        return null;
                    }));
                }
                for (Future<?> f : futures) {
                    try { f.get(); }
                    catch (CancellationException ce) { /* cancelled after an earlier failure */ }
                    catch (ExecutionException ee) { if (failure == null) { failure = unwrap(ee); for (Future<?> g : futures) g.cancel(true); } }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); if (failure == null) { failure = ie; for (Future<?> g : futures) g.cancel(true); } }
                }
            }
        } finally {
            pool.shutdownNow();
        }
        if (failure != null) { markRunningTablesFailed(job); throw failure; }
        return total.get();
    }

    /** Copy one subset table on its own connections (used by the parallel copy path). */
    private void copyOneSubsetTable(String table, SubsetService.SubsetPlan plan,
            DataSourceEntity src, DataSourceEntity tgt, String sourceSchema, String targetSchema,
            MaskingEngine eng, ProvisionJobEntity job,
            Map<String, List<MaskingRuleEntity>> resolvedRules,
            Map<String, Map<String, ColumnOverrideEntity>> colOverrideMap,
            Map<String, String> targetTableMap, Map<String, Integer> rowLimitByTable,
            Map<String, String> customPkMap, LoadOptions load, AtomicLong total) throws SQLException {
        SubsetService.TableSlice slice = plan.slices.get(table);
        if (slice == null) return;
        checkCancelled(job);
        setTableState(job, table, "RUNNING");
        String key = table.toLowerCase(Locale.ROOT);
        try (Connection in = connections.openForBulk(src); Connection out = connections.openForBulk(tgt)) {
            String inSchema = DataSourceService.normalizeSchema(in, sourceSchema);
            String outSchema = DataSourceService.normalizeSchema(out, targetSchema);
            out.setAutoCommit(false);
            tuneBulkConnection(out);
            List<MaskingRuleEntity> tableRules = resolvedRules.get(key);
            Map<String, ColumnOverrideEntity> tableOverrides = colOverrideMap.getOrDefault(key, Map.of());
            String targetTable = targetTableFor(table, targetTableMap);
            Integer perTableLimit = rowLimitByTable.get(key);
            long n = 0;
            if (slice.keyless()) {
                Integer lim = perTableLimit != null ? perTableLimit : slice.rowLimit();
                n = copyTable(eng, in, inSchema, out, outSchema, table, targetTable, null, null,
                        tableRules, job, slice.filter(), lim, load, tableOverrides, customPkMap);
            } else if (!slice.pkValues().isEmpty()) {
                n = copyTable(eng, in, inSchema, out, outSchema, table, targetTable, slice.pkColumn(), slice.pkValues(),
                        tableRules, job, null, perTableLimit, load, tableOverrides, customPkMap);
            }
            out.commit();
            total.addAndGet(n);
        }
        setTableState(job, table, "DONE");
    }

    /**
     * Extract rows from source and write them (masked + overridden) to target.
     *
     * @param colOverrides  per-target-column DataScope mappings/overrides.
     *                      SUPPRESS columns are filtered out of the INSERT before the query runs.
     */
    private long copyTable(MaskingEngine eng, Connection in, String sourceSchema, Connection out, String targetSchema,
                           String sourceTable, String targetTable, String pkColumn, Set<String> pkValues,
                           List<MaskingRuleEntity> tableRules, ProvisionJobEntity job,
                           String rowFilter, Integer rowLimit, LoadOptions load,
                           Map<String, ColumnOverrideEntity> colOverrides,
                           Map<String, String> customPkMap) throws SQLException {
        SubsetService.guardFilter(rowFilter);
        List<String> sourceCols = columnsOf(in, sourceSchema, sourceTable);
        if (sourceCols.isEmpty()) return 0;

        List<String> targetCols = columnsOf(out, targetSchema, targetTable);
        if (targetCols.isEmpty()) {
            // Target table doesn't exist yet — auto-create it from the source structure, then re-read.
            createTargetLikeSource(in, sourceSchema, sourceTable, out, targetSchema, targetTable);
            targetCols = columnsOf(out, targetSchema, targetTable);
            if (targetCols.isEmpty()) {
                targetCols = colOverrides.isEmpty()
                        ? sourceCols
                        : colOverrides.values().stream().map(ColumnOverrideEntity::getColumnName).toList();
            }
        }
        Map<String, Integer> targetTypes = columnTypes(out, targetSchema, targetTable);
        List<ColumnPlan> columnPlans = buildColumnPlans(sourceTable, targetTable, sourceCols, targetCols,
                targetTypes, colOverrides);
        if (columnPlans.isEmpty()) return 0;

        List<String> writerCols = columnPlans.stream().map(ColumnPlan::targetColumn).toList();
        List<String> selectCols = distinctTables(columnPlans.stream()
                .map(ColumnPlan::sourceColumn)
                .filter(Objects::nonNull)
                .toList());
        if (selectCols.isEmpty()) selectCols = List.of(sourceCols.get(0));

        // conditional masking. Two paths:
        //  - free-form (recommended): a raw SQL expression + optional JOIN, evaluated in the DB as a flag
        //  - structured (legacy): cond_json / cond_* columns, evaluated in Java
        Map<Integer, CondSet> condByPlan = new HashMap<>();          // structured (legacy) per plan index
        List<String> condExprs = new ArrayList<>();                  // distinct free-form expressions (one flag each)
        Map<Integer, Integer> planToFlag = new HashMap<>();          // plan index -> flag column index
        java.util.LinkedHashSet<String> condJoins = new java.util.LinkedHashSet<>();
        for (int pi = 0; pi < columnPlans.size(); pi++) {
            ColumnOverrideEntity ov = columnPlans.get(pi).override();
            if (ov == null) continue;
            if (condNotBlank(ov.getCondExpr())) {
                guardCondition(ov.getCondExpr());
                planToFlag.put(pi, condExprs.size());
                condExprs.add(ov.getCondExpr().trim());
                if (condNotBlank(ov.getCondJoin())) { guardCondition(ov.getCondJoin()); condJoins.add(ov.getCondJoin().trim()); }
            } else {
                CondSet cs = condSetOf(ov);
                if (cs != null) condByPlan.put(pi, cs);
            }
        }
        String condAlias = (!condByPlan.isEmpty() || !condExprs.isEmpty()) ? "t" : null;
        Map<String, Map<String, String>> condJoinMaps = new HashMap<>();
        if (!condByPlan.isEmpty()) {
            Map<String, String> srcCanon = canonicalColumns(sourceCols);
            List<String> augmented = new ArrayList<>(selectCols);
            for (CondSet cs : condByPlan.values()) {
                for (Cond c : cs.clauses()) {
                    String need = condNotBlank(c.joinTable()) ? c.joinSourceCol() : c.column();
                    String actual = need == null ? null : srcCanon.get(need.toLowerCase(Locale.ROOT));
                    if (actual != null && augmented.stream().noneMatch(s -> s.equalsIgnoreCase(actual))) augmented.add(actual);
                    if (condNotBlank(c.joinTable())) {
                        String jk = joinKey(c);
                        if (!condJoinMaps.containsKey(jk))
                            condJoinMaps.put(jk, loadJoinMap(in, sourceSchema, c.joinTable(), c.joinTargetCol(), c.column()));
                    }
                }
            }
            selectCols = distinctTables(augmented);
        }

        Map<String, MaskingRuleEntity> ruleByCol = new HashMap<>();
        if (tableRules != null) for (MaskingRuleEntity r : tableRules) ruleByCol.put(r.getColumnName().toLowerCase(Locale.ROOT), r);
        SqlDialect sourceDialect = SqlDialect.fromConnection(in);
        SqlDialect targetDialect = SqlDialect.fromConnection(out);
        List<String> keys = keyColumns(out, targetSchema, targetTable, writerCols, load, customPkMap);
        int batch = safeJdbcBatchRows(targetDialect, Math.max(1, writerCols.size()), load.batchSize());
        long rows = 0;

        int keyChunkSize = safeJdbcBatchRows(sourceDialect, 1, KEY_CHUNK_SIZE);
        List<List<String>> keyChunks = pkValues == null ? nullableChunk() : chunks(new ArrayList<>(pkValues), keyChunkSize);
        int[] writerSqlTypes = columnPlans.stream().mapToInt(ColumnPlan::sqlType).toArray();
        // Rolling buffer of the current uncommitted batch (masked row + its original source values), cleared on
        // each flush. On a constraint failure it lets us show the failed record original-vs-masked (and, for a
        // within-batch collision, the conflicting record too). Bounded by batchSize, so memory stays small.
        List<RowDiag> batchDiag = new ArrayList<>();
        try (TableLoadWriter writer = new TableLoadWriter(out, targetSchema, targetTable, writerCols, writerSqlTypes, load, keys, targetDialect)) {
            for (List<String> chunk : keyChunks) {
                checkCancelled(job);
                String dataList = String.join(",", selectCols.stream()
                        .map(c -> condAlias == null ? q(c) : condAlias + "." + q(c)).toList());
                StringBuilder selSb = new StringBuilder("SELECT ").append(dataList);
                for (int f = 0; f < condExprs.size(); f++)
                    selSb.append(", CASE WHEN (").append(condExprs.get(f)).append(") THEN 1 ELSE 0 END AS ").append(q("__cond_" + f));
                selSb.append(" FROM ").append(q(sourceSchema, sourceTable));
                if (condAlias != null) selSb.append(" ").append(condAlias);
                for (String jf : condJoins) selSb.append(" ").append(jf);
                selSb.append(selectWhere(pkColumn, chunk, rowFilter, condAlias));
                String select = selSb.toString();
                try (PreparedStatement sel = in.prepareStatement(select)) {
                    sel.setFetchSize(Math.max(batch, 1_000));
                    if (chunk != null) for (int i = 0; i < chunk.size(); i++) bindValue(sel, i + 1, chunk.get(i));
                    if (chunk == null && rowLimit != null && rowLimit > 0) sel.setMaxRows(rowLimit);
                    try (ResultSet rs = sel.executeQuery()) {
                        while (rs.next()) {
                            if (rowLimit != null && rowLimit > 0 && rows >= rowLimit) break;  // hard cap (keyed/keyless alike)
                            rows++;
                            MaskContext ctx = new MaskContext(rows);
                            Object[] values = new Object[columnPlans.size()];
                            Map<String, Object> rawBySource = new HashMap<>();
                            Map<String, String> textBySource = new HashMap<>();
                            for (int i = 1; i <= selectCols.size(); i++) {
                                String sourceCol = selectCols.get(i - 1);
                                String key = sourceCol.toLowerCase(Locale.ROOT);
                                Object raw = rs.getObject(i);
                                String text = rs.getString(i);
                                rawBySource.put(key, raw);
                                textBySource.put(key, text);
                                ctx.row.put(key, text);
                            }
                            boolean[] condFlags = new boolean[condExprs.size()];
                            for (int f = 0; f < condExprs.size(); f++)
                                condFlags[f] = rs.getInt(selectCols.size() + 1 + f) == 1;
                            for (int i = 0; i < columnPlans.size(); i++) {
                                ColumnPlan plan = columnPlans.get(i);
                                ColumnOverrideEntity override = plan.override();
                                String overrideType = override == null ? "USE_POLICY" : override.getOverrideType();
                                Integer condFlag = planToFlag.get(i);
                                boolean applyHere = condFlag != null ? condFlags[condFlag]
                                        : (condByPlan.get(i) == null || evalCondSet(condByPlan.get(i), textBySource, condJoinMaps));
                                if (!applyHere) {
                                    // condition is false for this row → pass the original value through unchanged
                                    String sk = plan.sourceColumn() == null ? null : plan.sourceColumn().toLowerCase(Locale.ROOT);
                                    values[i] = sk == null ? null : rawBySource.get(sk);
                                } else if ("LITERAL".equals(overrideType)) {
                                    values[i] = coerce(override.getLiteralValue(), plan.sqlType());
                                } else if ("NULL_OUT".equals(overrideType)) {
                                    values[i] = null;
                                } else if (plan.sourceColumn() == null) {
                                    values[i] = null;
                                } else {
                                    String sourceKey = plan.sourceColumn().toLowerCase(Locale.ROOT);
                                    values[i] = applyPolicyMask(eng, sourceTable, plan.sourceColumn(), plan.targetColumn(),
                                            rawBySource.get(sourceKey), textBySource.get(sourceKey), plan.sqlType(),
                                            ruleByCol, ctx);
                                }
                                if (values[i] instanceof String masked)
                                    ctx.masked.put(plan.targetColumn().toLowerCase(Locale.ROOT), masked);
                            }
                            String[] orig = new String[columnPlans.size()];
                            for (int oi = 0; oi < columnPlans.size(); oi++) {
                                String sc = columnPlans.get(oi).sourceColumn();
                                orig[oi] = sc == null ? null : textBySource.get(sc.toLowerCase(Locale.ROOT));
                            }
                            batchDiag.add(new RowDiag(values, orig));
                            writer.write(values);
                            if (rows % batch == 0) {
                                writer.flush();
                                batchDiag.clear();          // this batch committed to the driver; drop its diag rows
                                reportDelta(job, batch);   // sink-aware: correct under parallel copy
                                updateTableRowProgress(job, sourceTable, rows);
                                checkCancelled(job);
                            }
                        }
                    }
                }
                if (rowLimit != null && rowLimit > 0 && rows >= rowLimit) break;  // stop scanning further key chunks
            }
            writer.flush();
            batchDiag.clear();
            updateTableRowProgress(job, sourceTable, rows);
        } catch (SQLException e) {
            // A constraint/unique violation (or similar) blew up a batch. Capture what collided, in business terms,
            // before the exception propagates and the transaction is rolled back.
            if (job.getConflictJson() == null) {
                String diag = buildConflictDiagnostic(e, out, targetSchema, targetTable, writerCols, batchDiag, load.action());
                if (diag != null) job.setConflictJson(diag);
            }
            throw e;
        }
        return rows;
    }

    // -------------------- constraint-failure diagnostics (original vs masked) --------------------

    /** One buffered row for conflict diagnostics: the masked values written and the matching original source values. */
    private record RowDiag(Object[] masked, String[] original) {}
    /** Parsed unique/constraint violation: constraint name + the offending key columns and values. */
    private record ConflictKey(String constraint, List<String> columns, List<String> values) {}

    /**
     * Build a business-readable diagnostic for a constraint failure during load: the failed record (original vs
     * masked) and the conflicting record (an existing target row, or the earlier row in the same batch). Returns a
     * JSON string, or null if the error isn't a parseable constraint violation. Never throws.
     */
    private String buildConflictDiagnostic(SQLException e, Connection out, String targetSchema, String targetTable,
                                           List<String> writerCols, List<RowDiag> batchDiag, String action) {
        try {
            String text = collectSqlText(e);
            ConflictKey key = parseConflict(text);
            if (key == null) return null;

            Map<String, Object> diag = new LinkedHashMap<>();
            diag.put("type", "CONSTRAINT_VIOLATION");
            diag.put("table", targetTable);
            diag.put("action", action);
            if (key.constraint() != null) diag.put("constraint", key.constraint());
            diag.put("keyColumns", key.columns());
            diag.put("keyValues", key.values());
            diag.put("message", firstLine(text));

            // Index the key columns within the writer's column order (case-insensitive).
            List<Integer> keyIdx = new ArrayList<>();
            for (String kc : key.columns()) {
                int idx = -1;
                for (int i = 0; i < writerCols.size(); i++) if (writerCols.get(i).equalsIgnoreCase(kc)) { idx = i; break; }
                keyIdx.add(idx);
            }

            // Failed record: the buffered row whose masked key column(s) equal the offending value(s).
            RowDiag failed = findRowByKey(batchDiag, keyIdx, key.values(), null);
            if (failed != null) diag.put("failedRecord", recordColumns(writerCols, failed.original(), failed.masked()));

            // Conflicting record: prefer an earlier row in the SAME batch (within-load collision — both originals
            // known); otherwise look it up in the target (an already-loaded / pre-existing row).
            RowDiag sameBatch = findRowByKey(batchDiag, keyIdx, key.values(), failed);
            if (sameBatch != null) {
                Map<String, Object> rec = recordColumns(writerCols, sameBatch.original(), sameBatch.masked());
                rec.put("source", "same load — an earlier source row masked to the same value");
                diag.put("conflictingRecord", rec);
            } else {
                Map<String, Object> existing = lookupConflictingRow(out, targetSchema, targetTable, key.columns(), key.values());
                if (existing != null) {
                    existing.put("source", "already in the target table");
                    diag.put("conflictingRecord", existing);
                }
            }
            return json.writeValueAsString(diag);
        } catch (Exception ignore) {
            return null;   // diagnostics must never mask the real failure
        }
    }

    /** Flatten the SQLException chain (including the Postgres server DETAIL harvested reflectively) into one string. */
    private String collectSqlText(SQLException e) {
        StringBuilder sb = new StringBuilder();
        SQLException se = e;
        int guard = 0;
        while (se != null && guard++ < 40) {
            if (se.getMessage() != null) sb.append(se.getMessage()).append('\n');
            harvestServerDetail(se, sb);   // pull "Detail: Key (col)=(val) already exists." and the constraint name
            SQLException next = se.getNextException();
            se = (next != null && next != se) ? next : null;
        }
        Throwable c = e.getCause();
        int g2 = 0;
        while (c != null && g2++ < 20) {
            if (c != e && c.getMessage() != null) sb.append(c.getMessage()).append('\n');
            if (c instanceof SQLException cse) harvestServerDetail(cse, sb);
            c = c.getCause() == c ? null : c.getCause();
        }
        return sb.toString();
    }

    /** Reflectively read the Postgres ServerErrorMessage (detail/constraint/etc.) without a compile-time driver dep. */
    private void harvestServerDetail(SQLException se, StringBuilder sb) {
        try {
            Object sem = se.getClass().getMethod("getServerErrorMessage").invoke(se);
            if (sem == null) return;
            for (String m : new String[]{"getDetail", "getMessage", "getConstraint", "getTable", "getColumn"}) {
                try {
                    Object v = sem.getClass().getMethod(m).invoke(sem);
                    if (v != null) sb.append(v).append('\n');
                } catch (Exception ignore) { /* method not present on this driver */ }
            }
        } catch (Exception ignore) { /* not a Postgres exception */ }
    }

    private ConflictKey parseConflict(String text) {
        if (text == null || text.isBlank()) return null;
        String constraint = null;
        java.util.regex.Matcher cm = java.util.regex.Pattern.compile("constraint \"([^\"]+)\"").matcher(text);
        if (cm.find()) constraint = cm.group(1);
        // Postgres detail form: Key (col[, col2])=(val[, val2]) already exists.
        java.util.regex.Matcher km = java.util.regex.Pattern
                .compile("Key \\(([^)]+)\\)=\\((.*?)\\)\\s+already exists", java.util.regex.Pattern.DOTALL).matcher(text);
        if (km.find()) {
            List<String> cols = splitKeyList(km.group(1));
            List<String> vals = splitKeyList(km.group(2));
            if (!cols.isEmpty() && cols.size() == vals.size()) return new ConflictKey(constraint, cols, vals);
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("duplicate key") || lower.contains("unique constraint") || lower.contains("unique index"))
            return new ConflictKey(constraint, List.of(), List.of());   // seen a unique violation but couldn't parse the key
        return null;
    }

    private static List<String> splitKeyList(String s) {
        List<String> out = new ArrayList<>();
        for (String part : s.split(",\\s*")) out.add(part.trim());
        return out;
    }

    private RowDiag findRowByKey(List<RowDiag> rows, List<Integer> keyIdx, List<String> values, RowDiag exclude) {
        if (keyIdx.isEmpty() || keyIdx.contains(-1) || values.size() != keyIdx.size()) return null;
        for (RowDiag r : rows) {
            if (r == exclude) continue;
            boolean all = true;
            for (int k = 0; k < keyIdx.size(); k++) {
                Object mv = r.masked()[keyIdx.get(k)];
                if (!Objects.equals(mv == null ? null : String.valueOf(mv), values.get(k))) { all = false; break; }
            }
            if (all) return r;
        }
        return null;
    }

    private Map<String, Object> recordColumns(List<String> cols, String[] original, Object[] masked) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < cols.size(); i++) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("column", cols.get(i));
            c.put("original", original != null && i < original.length ? original[i] : null);
            c.put("masked", masked != null && i < masked.length && masked[i] != null ? String.valueOf(masked[i]) : null);
            list.add(c);
        }
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("columns", list);
        return rec;
    }

    /** SELECT the existing conflicting row from the target (after rolling back the aborted batch transaction). */
    private Map<String, Object> lookupConflictingRow(Connection out, String schema, String table,
                                                     List<String> cols, List<String> values) {
        if (cols.isEmpty() || cols.size() != values.size()) return null;
        try { out.rollback(); } catch (SQLException ignore) { return null; }   // clear the aborted-transaction state
        StringBuilder where = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) { if (i > 0) where.append(" AND "); where.append(q(cols.get(i))).append(" = ?"); }
        String base = "SELECT * FROM " + q(schema, table) + " WHERE " + where;
        for (String sql : new String[]{ base + " FETCH FIRST 1 ROWS ONLY", base }) {   // LIMIT-agnostic; fall back if unsupported
            try (PreparedStatement ps = out.prepareStatement(sql)) {
                for (int i = 0; i < values.size(); i++) ps.setString(i + 1, values.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    ResultSetMetaData md = rs.getMetaData();
                    List<Map<String, Object>> list = new ArrayList<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        Map<String, Object> c = new LinkedHashMap<>();
                        c.put("column", md.getColumnLabel(i));
                        c.put("original", null);          // the existing target row is already masked; no original on hand
                        c.put("masked", rs.getString(i));
                        list.add(c);
                    }
                    Map<String, Object> rec = new LinkedHashMap<>();
                    rec.put("columns", list);
                    return rec;
                }
            } catch (SQLException ex) { /* try the next SQL variant, else give up */ }
        }
        return null;
    }

    private static String firstLine(String text) {
        for (String line : text.split("\n")) if (!line.isBlank()) return line.trim();
        return text == null ? "" : text.trim();
    }

    // -------------------- in-place masking (source == target table) --------------------

    /**
     * Mask a set of tables in place, optionally in parallel. In-place tables are independent of each other
     * (rows keep their keys, so there is no cross-table load ordering as there is for the INSERT path), which
     * makes per-table parallelism safe. Each table is masked on its own JDBC connections; the FK-consistency
     * salts are shared read-only and re-installed on every worker thread (the salt ThreadLocal does not
     * propagate across threads), and progress is reported through one shared delta sink. With parallelism 1
     * this is just the serial loop (still via the sink, so the row counter is monotonic across tables).
     */
    /**
     * Collect the seedlists used by pushdownable rule masks across the in-place tables and install + parity-verify
     * a DB-pushdown session on the (main) out connection so parallel workers can reference its functions/tables.
     * Returns null when DB pushdown is disabled, unsupported on this engine, or nothing is eligible.
     */
    private DbMaskPushdown.Session prepareDbPushdown(Connection out, String schema, MaskingEngine eng,
                                                     List<String> tables,
                                                     Map<String, List<MaskingRuleEntity>> resolvedRules) {
        if (!dbPushdown.enabled()) return null;
        SqlDialect dialect;
        try { dialect = SqlDialect.fromConnection(out); } catch (Exception e) { return null; }
        Set<String> seedlists = new LinkedHashSet<>();
        for (String table : tables) {
            List<MaskingRuleEntity> rs = resolvedRules.get(table.toLowerCase(Locale.ROOT));
            if (rs == null) continue;
            for (MaskingRuleEntity r : rs) {
                if (r.getFunction() == null) continue;
                MaskFunction fn;
                try { fn = MaskFunction.valueOf(r.getFunction()); } catch (IllegalArgumentException e) { continue; }
                String sl = dbPushdown.seedlistFor(fn, r.getParam1(), r.getParam2());
                if (sl != null) seedlists.add(sl);
            }
        }
        if (seedlists.isEmpty()) return null;
        try { return dbPushdown.prepare(out, dialect, eng, schema, seedlists); }
        catch (Exception e) { return null; }
    }

    private long maskTablesInPlace(List<String> tables, DataSourceEntity src, DataSourceEntity tgt,
                                   String sourceSchema, MaskingEngine eng, ProvisionJobEntity job, JsonNode spec,
                                   Map<String, Map<String, ColumnOverrideEntity>> colOverrideMap,
                                   Map<String, List<MaskingRuleEntity>> resolvedRules,
                                   Map<String, String> customPkMap, Map<String, String> sharedSalts,
                                   DbMaskPushdown.Session pushdown) throws Exception {
        long base = job.getRowsProcessed();
        AtomicLong counter = new AtomicLong(base);   // running total (incl. earlier phases) for monotonic progress
        ProgressSink sink = sharedSink(job, counter);
        int parallelism = Math.min(provisionParallelism(), Math.max(1, tables.size()));

        if (parallelism <= 1) {
            progressSinkTL.set(sink);
            try {
                for (String table : tables) maskOneTableInPlace(table, src, tgt, sourceSchema, eng, job, spec,
                        colOverrideMap, resolvedRules, customPkMap, sharedSalts, pushdown);
            } finally { progressSinkTL.remove(); }
            return counter.get() - base;   // rows masked by this call
        }

        progressMessage(job, "Masking " + tables.size() + " table(s) in place, " + parallelism + " in parallel");
        ExecutorService pool = Executors.newFixedThreadPool(parallelism, r -> {
            Thread t = new Thread(r, "fdm-mask"); t.setDaemon(true); return t;
        });
        List<Future<?>> futures = new ArrayList<>();
        Exception failure = null;
        try {
            for (String table : tables) {
                futures.add(pool.submit(() -> {
                    progressSinkTL.set(sink);
                    try {
                        maskOneTableInPlace(table, src, tgt, sourceSchema, eng, job, spec,
                                colOverrideMap, resolvedRules, customPkMap, sharedSalts, pushdown);
                    } finally { progressSinkTL.remove(); }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                try { f.get(); }
                catch (CancellationException ce) { /* cancelled after an earlier failure — ignore */ }
                catch (ExecutionException ee) {
                    if (failure == null) { failure = unwrap(ee); for (Future<?> g : futures) g.cancel(true); }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    if (failure == null) { failure = ie; for (Future<?> g : futures) g.cancel(true); }
                }
            }
        } finally {
            pool.shutdownNow();
        }
        if (failure != null) { markRunningTablesFailed(job); throw failure; }
        return counter.get() - base;   // rows masked by this call
    }

    /** Mask a single table in place on its own connections, with the shared salts and the active progress sink. */
    private void maskOneTableInPlace(String table, DataSourceEntity src, DataSourceEntity tgt, String sourceSchema,
                                     MaskingEngine eng, ProvisionJobEntity job, JsonNode spec,
                                     Map<String, Map<String, ColumnOverrideEntity>> colOverrideMap,
                                     Map<String, List<MaskingRuleEntity>> resolvedRules,
                                     Map<String, String> customPkMap, Map<String, String> sharedSalts,
                                     DbMaskPushdown.Session pushdown) throws SQLException {
        checkCancelled(job);
        setTableState(job, table, "RUNNING");
        keySaltTL.set(sharedSalts);
        try (Connection in = connections.openForBulk(src); Connection out = connections.openForBulk(tgt)) {
            String inSchema = DataSourceService.normalizeSchema(in, sourceSchema);   // in-place: source == target DB
            out.setAutoCommit(false);
            tuneBulkConnection(out);
            String key = table.toLowerCase(Locale.ROOT);
            maskTableInPlace(eng, in, out, inSchema, table,
                    resolvedRules.get(key), job, colOverrideMap.getOrDefault(key, Map.of()), customPkMap, spec, pushdown);
            out.commit();
        } finally {
            keySaltTL.remove();
        }
        setTableState(job, table, "DONE");
    }

    private Exception unwrap(ExecutionException ee) {
        Throwable c = ee.getCause();
        return (c instanceof Exception ex) ? ex : new RuntimeException(c == null ? ee : c);
    }

    /**
     * Mask a table in place (source and target are the same physical table) without a full reload.
     *
     * Strategy: keyset-paginate the table by a unique orderable chunk key; for each chunk, mask the
     * changed columns in Java, bulk-load (key + masked columns) into a private staging table, then
     * apply one set-based UPDATE/MERGE join and COMMIT. This bounds undo/WAL/locks per chunk, is
     * restartable (deterministic masking ⇒ idempotent), and avoids the dead-tuple bloat of a single
     * billion-row UPDATE. Masking a uniquely-indexed column this way is refused (chunked updates can
     * transiently collide across chunks — that case needs a shadow-table rebuild instead).
     */
    private long maskTableInPlace(MaskingEngine eng, Connection in, Connection out, String schema,
                                  String table, List<MaskingRuleEntity> tableRules, ProvisionJobEntity job,
                                  Map<String, ColumnOverrideEntity> colOverrides, Map<String, String> customPkMap,
                                  JsonNode spec, DbMaskPushdown.Session pushdown) throws SQLException {
        SqlDialect dialect = SqlDialect.fromConnection(out);
        List<String> sourceCols = columnsOf(in, schema, table);
        if (sourceCols.isEmpty()) return 0;
        Map<String, Integer> types = columnTypes(in, schema, table);

        List<ColumnPlan> plans = buildColumnPlans(table, table, sourceCols, sourceCols, types, colOverrides);
        if (plans.isEmpty()) return 0;

        List<String> joinKeys = inPlaceJoinKeysOrEmpty(out, schema, table, sourceCols, customPkMap, spec);
        if (joinKeys.isEmpty())   // no PK / unique key → keyset pagination impossible; use the universal staging rebuild
            return maskTableInPlaceRebuild(eng, in, out, dialect, schema, table, tableRules, job, colOverrides, spec, sourceCols, types, plans, joinKeys);
        Set<String> joinKeyLc = lower(joinKeys);

        Map<String, MaskingRuleEntity> ruleByCol = new HashMap<>();
        if (tableRules != null) for (MaskingRuleEntity r : tableRules)
            ruleByCol.put(r.getColumnName().toLowerCase(Locale.ROOT), r);

        // Only columns that actually change get staged/updated; key columns are never masked.
        List<ColumnPlan> updatePlans = new ArrayList<>();
        for (ColumnPlan p : plans) {
            if (joinKeyLc.contains(p.targetColumn().toLowerCase(Locale.ROOT))) continue;
            if (isTransformed(p, ruleByCol)) updatePlans.add(p);
        }
        if (updatePlans.isEmpty()) { progressMessage(job, "In-place: nothing to mask on " + table); return 0; }
        List<String> updateCols = updatePlans.stream().map(ColumnPlan::targetColumn).toList();

        Set<String> uniqueCols = uniqueIndexedColumns(out, schema, table);
        List<String> blocked = updateCols.stream()
                .filter(c -> uniqueCols.contains(c.toLowerCase(Locale.ROOT))).toList();
        if (!blocked.isEmpty()) {
            // Masking a uniquely-indexed column can't be done by chunked UPDATEs (two chunks can transiently
            // collide before the whole update completes). The staging rebuild replaces the table contents
            // atomically in one transaction, so uniqueness is only enforced at the final state — safe here.
            progressMessage(job, "In-place: column(s) " + blocked + " are uniquely indexed; using atomic key-update for " + table);
            return maskTableInPlaceRebuild(eng, in, out, dialect, schema, table, tableRules, job, colOverrides, spec, sourceCols, types, plans, joinKeys);
        }

        // SQL-pushdown fast path: if EVERY changed column can be masked in the database — a value-independent
        // mask (unconditional NULL_OUT / LITERAL) or a parity-verified DB-side generator (names, etc.) — do the
        // whole table as one server-side UPDATE: no rows leave the database, no staging, no chunk loop. Keyed
        // multi-draw masks (SSN, phone, credit card, FPE) can't be reproduced in SQL, so any table containing one
        // falls through to the (parallel) Java path below.
        // Resolve per-table filter/limit early so the pushdown path can honour them too
        SubsetService.TableCriterion tcPush = inPlaceTableCriteria(spec, table);
        String pushFilter = tcPush != null && tcPush.filter() != null && !tcPush.filter().isBlank() ? tcPush.filter().trim() : null;
        Integer pushLimit = tcPush != null ? tcPush.rowLimit() : null;
        if (pushFilter != null) SubsetService.guardFilter(pushFilter);

        List<String> assigns = sqlPushdownAssignments(updatePlans, ruleByCol, table, pushdown);
        // Skip pushdown when a rowLimit is set — SQL UPDATE has no portable row-limiting syntax
        if (assigns != null && (pushLimit == null || pushLimit <= 0)) {
            String sql = "UPDATE " + q(schema, table) + " SET " + String.join(", ", assigns)
                    + (pushFilter != null ? " WHERE " + pushFilter : "");
            progressMessage(job, "In-place (SQL pushdown) masking " + table + " — columns " + updateCols);
            long n;
            try (Statement st = out.createStatement()) { n = st.executeUpdate(sql); }
            out.commit();
            reportDelta(job, n);
            updateTableRowProgress(job, table, n);
            return n;
        }

        String chunkKey = inPlaceChunkKey(spec, joinKeys, table);
        if (!uniqueCols.contains(chunkKey.toLowerCase(Locale.ROOT)))
            throw ApiException.bad("In-place chunk key '" + chunkKey + "' must be unique (a primary key or unique index) "
                    + "for safe pagination on " + table + ".");

        // Conditional masking: free-form expressions pushed to SQL as flags; structured evaluated in Java.
        List<String> condExprs = new ArrayList<>();
        Map<Integer, Integer> planToFlag = new HashMap<>();
        Map<Integer, CondSet> condByPlan = new HashMap<>();
        LinkedHashSet<String> condJoins = new LinkedHashSet<>();
        for (int pi = 0; pi < updatePlans.size(); pi++) {
            ColumnOverrideEntity ov = updatePlans.get(pi).override();
            if (ov == null) continue;
            if (condNotBlank(ov.getCondExpr())) {
                guardCondition(ov.getCondExpr());
                planToFlag.put(pi, condExprs.size());
                condExprs.add(ov.getCondExpr().trim());
                if (condNotBlank(ov.getCondJoin())) { guardCondition(ov.getCondJoin()); condJoins.add(ov.getCondJoin().trim()); }
            } else {
                CondSet cs = condSetOf(ov);
                if (cs != null) condByPlan.put(pi, cs);
            }
        }

        // Columns to read: join keys + chunk key + each update column's source + structured-condition columns.
        Map<String, String> srcCanon = canonicalColumns(sourceCols);
        LinkedHashSet<String> readSet = new LinkedHashSet<>(joinKeys);
        readSet.add(chunkKey);
        for (ColumnPlan p : updatePlans) if (p.sourceColumn() != null) readSet.add(p.sourceColumn());
        Map<String, Map<String, String>> condJoinMaps = new HashMap<>();
        for (CondSet cs : condByPlan.values())
            for (Cond c : cs.clauses()) {
                String need = condNotBlank(c.joinTable()) ? c.joinSourceCol() : c.column();
                String actual = need == null ? null : srcCanon.get(need.toLowerCase(Locale.ROOT));
                if (actual != null) readSet.add(actual);
                if (condNotBlank(c.joinTable())) {
                    String jk = joinKey(c);
                    if (!condJoinMaps.containsKey(jk))
                        condJoinMaps.put(jk, loadJoinMap(in, schema, c.joinTable(), c.joinTargetCol(), c.column()));
                }
            }
        List<String> readCols = new ArrayList<>(readSet);
        String alias = "t";

        // Staging: (join keys + update columns), exact types copied from the table.
        List<String> stageCols = new ArrayList<>(joinKeys);
        stageCols.addAll(updateCols);
        int[] stageTypes = stageCols.stream()
                .mapToInt(c -> types.getOrDefault(c.toLowerCase(Locale.ROOT), Types.VARCHAR)).toArray();
        String stg = "fdm_stg_" + sanitize(table) + "_" + job.getId();
        int chunkRows = spec.path("inPlaceChunkRows").asInt(50_000);
        chunkRows = chunkRows <= 0 ? 50_000 : Math.min(chunkRows, 500_000);

        String readList = String.join(",", readCols.stream().map(c -> alias + "." + q(c)).toList());
        StringBuilder selSb = new StringBuilder("SELECT ").append(readList);
        for (int f = 0; f < condExprs.size(); f++)
            selSb.append(", CASE WHEN (").append(condExprs.get(f)).append(") THEN 1 ELSE 0 END AS ").append(q("__cond_" + f));
        selSb.append(" FROM ").append(q(schema, table)).append(" ").append(alias);
        for (String jf : condJoins) selSb.append(" ").append(jf);
        String baseFrom = selSb.toString();
        String orderBy = " ORDER BY " + alias + "." + q(chunkKey) + " ASC";
        String insStg = "INSERT INTO " + q(schema, stg)
                + " (" + String.join(",", stageCols.stream().map(ProvisioningService::q).toList()) + ") VALUES ("
                + String.join(",", Collections.nCopies(stageCols.size(), "?")) + ")";
        String updJoin = inPlaceUpdateSql(dialect, schema, table, stg, joinKeys, updateCols);
        String clearStg = (dialect == SqlDialect.GENERIC) ? "DELETE FROM " + q(schema, stg) : dialect.truncateSql(q(schema, stg));
        int chunkKeyPos = readCols.indexOf(chunkKey);
        int stageBatchRows = safeJdbcBatchRows(dialect, Math.max(1, stageCols.size()), chunkRows);

        // Reuse filter/limit already resolved for the pushdown check above
        String rowFilter = pushFilter;
        Integer rowLimit = pushLimit;
        String baseWhere = rowFilter != null ? " WHERE " + rowFilter : "";

        long updated = 0;
        progressMessage(job, "In-place masking " + table + " — columns " + updateCols);
        createStaging(out, dialect, schema, stg, table, stageCols);
        try {
            Object lastKey = null;
            while (true) {
                checkCancelled(job);
                if (rowLimit != null && rowLimit > 0 && updated >= rowLimit) break;
                // Chunk predicate: append to the row filter (if any) with AND, otherwise start a new WHERE
                String chunkCond = lastKey == null ? "" :
                        ((baseWhere.isEmpty() ? " WHERE " : " AND ") + alias + "." + q(chunkKey) + " > ?");
                String sql = baseFrom + baseWhere + chunkCond + orderBy;
                List<Object[]> staged = new ArrayList<>();
                Object chunkMax = null;
                try (PreparedStatement sel = in.prepareStatement(sql)) {
                    sel.setMaxRows(chunkRows);
                    sel.setFetchSize(Math.min(chunkRows, 10_000));
                    if (lastKey != null) sel.setObject(1, lastKey);
                    try (ResultSet rs = sel.executeQuery()) {
                        while (rs.next()) {
                            Map<String, Object> rawBySource = new HashMap<>();
                            Map<String, String> textBySource = new HashMap<>();
                            MaskContext ctx = new MaskContext(updated + staged.size() + 1);
                            for (int i = 1; i <= readCols.size(); i++) {
                                String k = readCols.get(i - 1).toLowerCase(Locale.ROOT);
                                rawBySource.put(k, rs.getObject(i));
                                String txt = rs.getString(i);
                                textBySource.put(k, txt);
                                ctx.row.put(k, txt);
                            }
                            boolean[] condFlags = new boolean[condExprs.size()];
                            for (int f = 0; f < condExprs.size(); f++)
                                condFlags[f] = rs.getInt(readCols.size() + 1 + f) == 1;
                            chunkMax = rs.getObject(chunkKeyPos + 1);

                            Object[] row = new Object[stageCols.size()];
                            for (int j = 0; j < joinKeys.size(); j++)
                                row[j] = rawBySource.get(joinKeys.get(j).toLowerCase(Locale.ROOT));
                            for (int u = 0; u < updatePlans.size(); u++) {
                                ColumnPlan p = updatePlans.get(u);
                                String ot = p.override() == null ? "USE_POLICY" : p.override().getOverrideType();
                                Integer flag = planToFlag.get(u);
                                boolean apply = flag != null ? condFlags[flag]
                                        : (condByPlan.get(u) == null || evalCondSet(condByPlan.get(u), textBySource, condJoinMaps));
                                String sk = p.sourceColumn() == null ? null : p.sourceColumn().toLowerCase(Locale.ROOT);
                                Object val;
                                if (!apply) val = sk == null ? null : rawBySource.get(sk);
                                else if ("LITERAL".equals(ot)) val = coerce(p.override().getLiteralValue(), p.sqlType());
                                else if ("NULL_OUT".equals(ot)) val = null;
                                else if (sk == null) val = null;
                                else val = applyPolicyMask(eng, table, p.sourceColumn(), p.targetColumn(),
                                            rawBySource.get(sk), textBySource.get(sk), p.sqlType(), ruleByCol, ctx);
                                row[joinKeys.size() + u] = val;
                            }
                            staged.add(row);
                        }
                    }
                }
                if (staged.isEmpty()) break;

                // Trim chunk to stay within row limit
                boolean hitLimit = false;
                if (rowLimit != null && rowLimit > 0 && updated + staged.size() > rowLimit) {
                    staged = staged.subList(0, (int)(rowLimit - updated));
                    hitLimit = true;
                }

                try (Statement st = out.createStatement()) { st.executeUpdate(clearStg); }
                try (PreparedStatement ps = out.prepareStatement(insStg)) {
                    int pendingStageRows = 0;
                    for (Object[] row : staged) {
                        for (int i = 0; i < row.length; i++) {
                            if (row[i] == null) ps.setNull(i + 1, stageTypes[i]);
                            else ps.setObject(i + 1, row[i]);
                        }
                        ps.addBatch();
                        if (++pendingStageRows >= stageBatchRows) {
                            ps.executeBatch();
                            pendingStageRows = 0;
                        }
                    }
                    if (pendingStageRows > 0) ps.executeBatch();
                }
                try (Statement st = out.createStatement()) { st.executeUpdate(updJoin); }
                out.commit();

                updated += staged.size();
                reportDelta(job, staged.size());
                updateTableRowProgress(job, table, updated);
                lastKey = chunkMax;
                if (hitLimit || staged.size() < chunkRows) break;
            }
        } finally {
            dropStaging(out, schema, stg);
        }
        return updated;
    }

    /** True if this plan actually changes a value (rule / literal / null-out / conditional), not a plain copy. */
    private boolean isTransformed(ColumnPlan p, Map<String, MaskingRuleEntity> ruleByCol) {
        String t = p.override() == null ? "USE_POLICY" : p.override().getOverrideType();
        if ("LITERAL".equals(t) || "NULL_OUT".equals(t)) return true;
        if (hasCondition(p.override())) return true;
        String sc = p.sourceColumn();
        if (sc != null && ruleByCol.containsKey(sc.toLowerCase(Locale.ROOT))) return true;
        return ruleByCol.containsKey(p.targetColumn().toLowerCase(Locale.ROOT));
    }

    /**
     * If every changed column can be masked with a value-independent SQL expression — an unconditional NULL_OUT
     * or LITERAL override — return the {@code "col = expr"} assignments for a single server-side UPDATE; else
     * null (fall back to the Java path). Keyed/rule-based masks are deliberately excluded: their output depends
     * on the HMAC secret and can't be reproduced in SQL without changing the masked values.
     */
    private List<String> sqlPushdownAssignments(List<ColumnPlan> updatePlans,
            Map<String, MaskingRuleEntity> ruleByCol, String table, DbMaskPushdown.Session pushdown) {
        List<String> assigns = new ArrayList<>();
        for (ColumnPlan p : updatePlans) {
            ColumnOverrideEntity ov = p.override();
            if (ov != null && !hasCondition(ov)) {
                String t = ov.getOverrideType();
                if ("NULL_OUT".equals(t)) { assigns.add(q(p.targetColumn()) + " = NULL"); continue; }
                if ("LITERAL".equals(t)) { assigns.add(q(p.targetColumn()) + " = " + sqlLiteral(ov.getLiteralValue(), p.sqlType())); continue; }
            }
            // Otherwise it must be a parity-verified DB-side generator, or the whole table uses the Java path.
            String gen = generatorAssignment(p, ruleByCol, table, pushdown);
            if (gen == null) return null;
            assigns.add(gen);
        }
        return assigns.isEmpty() ? null : assigns;
    }

    /** Pushdown assignment for a rule-based column whose mask has a verified DB-side equivalent; null otherwise. */
    private String generatorAssignment(ColumnPlan p, Map<String, MaskingRuleEntity> ruleByCol, String table,
                                       DbMaskPushdown.Session pushdown) {
        if (pushdown == null) return null;
        String sk = p.sourceColumn() == null ? null : p.sourceColumn().toLowerCase(Locale.ROOT);
        MaskingRuleEntity rule = sk == null ? null : ruleByCol.get(sk);
        if (rule == null) rule = ruleByCol.get(p.targetColumn().toLowerCase(Locale.ROOT));
        if (rule == null || rule.getFunction() == null) return null;
        MaskFunction fn;
        try { fn = MaskFunction.valueOf(rule.getFunction()); } catch (IllegalArgumentException e) { return null; }
        if (!dbPushdown.isPushdownable(fn, rule.getParam1(), rule.getParam2())) return null;
        String salt = saltFor(rule, table, rule.getColumnName() != null ? rule.getColumnName() : p.sourceColumn(), keySaltTL.get());
        return pushdown.assignment(q(p.targetColumn()), fn, rule.getParam1(), rule.getParam2(), salt);
    }

    /** Render a safe SQL literal: numbers emitted bare (only if they parse), everything else single-quoted/escaped. */
    private String sqlLiteral(String value, int sqlType) {
        if (value == null) return "NULL";
        boolean numeric = switch (sqlType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.DECIMAL,
                 Types.NUMERIC, Types.REAL, Types.FLOAT, Types.DOUBLE -> true;
            default -> false;
        };
        if (numeric) {
            try { Double.parseDouble(value.trim()); return value.trim(); }
            catch (NumberFormatException e) { /* not a number after all → quote it */ }
        }
        return "'" + value.replace("'", "''") + "'";
    }

    /** Join keys used to match rows in the in-place UPDATE: spec keyColumns → custom PK → catalog PK. */
    /** Resolve in-place join keys (explicit spec → custom PK map → DB primary key), or empty if the table has none. */
    private List<String> inPlaceJoinKeysOrEmpty(Connection out, String schema, String table, List<String> columns,
                                         Map<String, String> customPkMap, JsonNode spec) throws SQLException {
        List<String> explicit = keyColumns(spec);
        if (!explicit.isEmpty()) return resolveKeys(explicit, columns, table);
        String custom = customPkMap.get(table.toLowerCase(Locale.ROOT));
        if (custom != null && !custom.isBlank()) return parseColumns(custom, columns, table);
        List<String> pk = primaryKeyColumns(out, schema, table);
        if (pk.isEmpty()) return List.of();
        return resolveKeys(pk, columns, table);
    }

    /**
     * Staging-based in-place masking that works on any database (Postgres, Oracle, DB2, SQL Server, MySQL…)
     * without keyset pagination. Used for two cases the chunked path can't handle: tables with no primary key,
     * and keyed tables masking a uniquely-indexed column.
     *
     * Common step: stream every row, mask in Java, bulk-load into a private staging table, verify the staged
     * row count matches the source. Then:
     *  - KEYED (a usable key exists): apply ONE set-based {@code UPDATE … FROM staging} by key. Rows keep their
     *    keys, so inbound foreign keys stay valid (no DELETE), and a single statement avoids the cross-chunk
     *    unique collision that blocks the chunked path.
     *  - KEYLESS (no key — so the table cannot be an FK parent): atomically replace the contents in one
     *    transaction ({@code DELETE FROM t; INSERT INTO t SELECT … FROM staging}). Set
     *    {@code FORGETDM_INPLACE_KEYLESS_TRUNCATE=true} for a faster (less safe) TRUNCATE; the default DELETE
     *    keeps the swap transactional so a failed reload rolls back to the original data.
     *
     * Either way the original table object (its indexes, grants, constraints, triggers) is preserved.
     */
    private long maskTableInPlaceRebuild(MaskingEngine eng, Connection in, Connection out, SqlDialect dialect,
                                         String schema, String table, List<MaskingRuleEntity> tableRules,
                                         ProvisionJobEntity job, Map<String, ColumnOverrideEntity> colOverrides,
                                         JsonNode spec, List<String> sourceCols, Map<String, Integer> types,
                                         List<ColumnPlan> plans, List<String> joinKeys) throws SQLException {
        // Two strategies, both via a streamed staging table:
        //   KEYED  (a PK / unique key exists): stage (keys + changed columns) and apply ONE set-based UPDATE…FROM
        //          staging by key. Rows keep their keys, so inbound foreign keys stay valid (no DELETE) and a
        //          single statement avoids the cross-chunk collision that blocks chunked unique-column updates.
        //   KEYLESS (no key at all — which means the table can't be an FK parent): stage the full masked copy and
        //          atomically replace the contents (DELETE + INSERT…SELECT) in one transaction.
        boolean keyed = joinKeys != null && !joinKeys.isEmpty();
        Set<String> joinKeyLc = keyed ? lower(joinKeys) : Set.of();

        Map<String, MaskingRuleEntity> ruleByCol = new HashMap<>();
        if (tableRules != null) for (MaskingRuleEntity r : tableRules)
            ruleByCol.put(r.getColumnName().toLowerCase(Locale.ROOT), r);

        // Plans we actually write: keyed → only changed, non-key columns; keyless → every column (full copy).
        List<ColumnPlan> workPlans = new ArrayList<>();
        if (keyed) {
            for (ColumnPlan p : plans) {
                if (joinKeyLc.contains(p.targetColumn().toLowerCase(Locale.ROOT))) continue;
                if (isTransformed(p, ruleByCol)) workPlans.add(p);
            }
        } else {
            workPlans = new ArrayList<>(plans);
        }
        boolean anyTransform = keyed ? !workPlans.isEmpty()
                : plans.stream().anyMatch(p -> isTransformed(p, ruleByCol));
        if (!anyTransform) { progressMessage(job, "In-place: nothing to mask on " + table); return 0; }

        List<String> updateCols = workPlans.stream().map(ColumnPlan::targetColumn).toList();

        // Conditional masking: free-form expressions pushed to SQL as flags; structured evaluated in Java.
        List<String> condExprs = new ArrayList<>();
        Map<Integer, Integer> planToFlag = new HashMap<>();
        Map<Integer, CondSet> condByPlan = new HashMap<>();
        LinkedHashSet<String> condJoins = new LinkedHashSet<>();
        for (int pi = 0; pi < workPlans.size(); pi++) {
            ColumnOverrideEntity ov = workPlans.get(pi).override();
            if (ov == null) continue;
            if (condNotBlank(ov.getCondExpr())) {
                guardCondition(ov.getCondExpr());
                planToFlag.put(pi, condExprs.size());
                condExprs.add(ov.getCondExpr().trim());
                if (condNotBlank(ov.getCondJoin())) { guardCondition(ov.getCondJoin()); condJoins.add(ov.getCondJoin().trim()); }
            } else {
                CondSet cs = condSetOf(ov);
                if (cs != null) condByPlan.put(pi, cs);
            }
        }

        // Read set: key columns (keyed) or all columns (keyless), each work plan's source, + condition columns.
        Map<String, String> srcCanon = canonicalColumns(sourceCols);
        LinkedHashSet<String> readSet = new LinkedHashSet<>(keyed ? joinKeys : sourceCols);
        for (ColumnPlan p : workPlans) if (p.sourceColumn() != null) readSet.add(p.sourceColumn());
        Map<String, Map<String, String>> condJoinMaps = new HashMap<>();
        for (CondSet cs : condByPlan.values())
            for (Cond c : cs.clauses()) {
                String need = condNotBlank(c.joinTable()) ? c.joinSourceCol() : c.column();
                String actual = need == null ? null : srcCanon.get(need.toLowerCase(Locale.ROOT));
                if (actual != null) readSet.add(actual);
                if (condNotBlank(c.joinTable())) {
                    String jk = joinKey(c);
                    if (!condJoinMaps.containsKey(jk))
                        condJoinMaps.put(jk, loadJoinMap(in, schema, c.joinTable(), c.joinTargetCol(), c.column()));
                }
            }
        List<String> readCols = new ArrayList<>(readSet);
        String alias = "t";

        // Staging columns: keyed → keys + changed columns; keyless → full row.
        List<String> stageCols = keyed ? new ArrayList<>(joinKeys) : new ArrayList<>(sourceCols);
        if (keyed) stageCols.addAll(updateCols);
        int[] stageTypes = stageCols.stream()
                .mapToInt(c -> types.getOrDefault(c.toLowerCase(Locale.ROOT), Types.VARCHAR)).toArray();
        String stg = "fdm_stg_" + sanitize(table) + "_" + job.getId();
        int batch = spec.path("inPlaceChunkRows").asInt(50_000);
        batch = batch <= 0 ? 50_000 : Math.min(batch, 500_000);
        batch = safeJdbcBatchRows(dialect, Math.max(1, stageCols.size()), batch);

        String readList = String.join(",", readCols.stream().map(c -> alias + "." + q(c)).toList());
        StringBuilder selSb = new StringBuilder("SELECT ").append(readList);
        for (int f = 0; f < condExprs.size(); f++)
            selSb.append(", CASE WHEN (").append(condExprs.get(f)).append(") THEN 1 ELSE 0 END AS ").append(q("__cond_" + f));
        selSb.append(" FROM ").append(q(schema, table)).append(" ").append(alias);
        for (String jf : condJoins) selSb.append(" ").append(jf);
        // Per-table row filter and row limit from spec tableCriteria
        SubsetService.TableCriterion tcR = inPlaceTableCriteria(spec, table);
        String rowFilterR = tcR != null && tcR.filter() != null && !tcR.filter().isBlank() ? tcR.filter().trim() : null;
        Integer rowLimitR = tcR != null ? tcR.rowLimit() : null;
        if (rowFilterR != null) { SubsetService.guardFilter(rowFilterR); selSb.append(" WHERE ").append(rowFilterR); }
        String selSql = selSb.toString();
        String insStg = "INSERT INTO " + q(schema, stg)
                + " (" + String.join(",", stageCols.stream().map(ProvisioningService::q).toList()) + ") VALUES ("
                + String.join(",", Collections.nCopies(stageCols.size(), "?")) + ")";

        progressMessage(job, (keyed ? "In-place (atomic key-update) masking " : "In-place (keyless rebuild) masking ")
                + table + " — columns " + updateCols);
        boolean inAuto = in.getAutoCommit();
        long loaded = 0;
        try {
            if (inAuto) in.setAutoCommit(false);   // let the driver stream rows instead of buffering the whole table
            createStaging(out, dialect, schema, stg, table, stageCols);
            // 1) Stream-read → mask → bulk-load masked rows into staging.
            try (PreparedStatement sel = in.prepareStatement(selSql)) {
                sel.setFetchSize(Math.min(batch, 10_000));
                if (rowLimitR != null && rowLimitR > 0) sel.setMaxRows(rowLimitR);
                try (ResultSet rs = sel.executeQuery();
                     PreparedStatement ps = out.prepareStatement(insStg)) {
                    int inBatch = 0;
                    while (rs.next()) {
                        checkCancelled(job);
                        Map<String, Object> rawBySource = new HashMap<>();
                        Map<String, String> textBySource = new HashMap<>();
                        MaskContext ctx = new MaskContext(loaded + inBatch + 1);
                        for (int i = 1; i <= readCols.size(); i++) {
                            String k = readCols.get(i - 1).toLowerCase(Locale.ROOT);
                            rawBySource.put(k, rs.getObject(i));
                            String txt = rs.getString(i);
                            textBySource.put(k, txt);
                            ctx.row.put(k, txt);
                        }
                        boolean[] condFlags = new boolean[condExprs.size()];
                        for (int f = 0; f < condExprs.size(); f++)
                            condFlags[f] = rs.getInt(readCols.size() + 1 + f) == 1;

                        Map<String, Object> outByCol = new HashMap<>();
                        for (int c = 0; c < workPlans.size(); c++) {
                            ColumnPlan p = workPlans.get(c);
                            String ot = p.override() == null ? "USE_POLICY" : p.override().getOverrideType();
                            Integer flag = planToFlag.get(c);
                            boolean apply = flag != null ? condFlags[flag]
                                    : (condByPlan.get(c) == null || evalCondSet(condByPlan.get(c), textBySource, condJoinMaps));
                            String sk = p.sourceColumn() == null ? null : p.sourceColumn().toLowerCase(Locale.ROOT);
                            Object val;
                            if (!apply) val = sk == null ? null : rawBySource.get(sk);
                            else if ("LITERAL".equals(ot)) val = coerce(p.override().getLiteralValue(), p.sqlType());
                            else if ("NULL_OUT".equals(ot)) val = null;
                            else if (sk == null) val = null;
                            else val = applyPolicyMask(eng, table, p.sourceColumn(), p.targetColumn(),
                                        rawBySource.get(sk), textBySource.get(sk), p.sqlType(), ruleByCol, ctx);
                            if (val instanceof String masked) ctx.masked.put(p.targetColumn().toLowerCase(Locale.ROOT), masked);
                            outByCol.put(p.targetColumn().toLowerCase(Locale.ROOT), val);
                        }
                        Object[] row = new Object[stageCols.size()];
                        if (keyed) {
                            for (int j = 0; j < joinKeys.size(); j++)
                                row[j] = rawBySource.get(joinKeys.get(j).toLowerCase(Locale.ROOT));
                            for (int u = 0; u < workPlans.size(); u++)
                                row[joinKeys.size() + u] = outByCol.get(workPlans.get(u).targetColumn().toLowerCase(Locale.ROOT));
                        } else {
                            for (int i = 0; i < stageCols.size(); i++) {
                                String k = stageCols.get(i).toLowerCase(Locale.ROOT);
                                row[i] = outByCol.containsKey(k) ? outByCol.get(k) : rawBySource.get(k);
                            }
                        }
                        for (int i = 0; i < row.length; i++) {
                            if (row[i] == null) ps.setNull(i + 1, stageTypes[i]);
                            else ps.setObject(i + 1, row[i]);
                        }
                        ps.addBatch();
                        if (++inBatch >= batch) {
                            ps.executeBatch(); out.commit();
                            loaded += inBatch; reportDelta(job, inBatch); updateTableRowProgress(job, table, loaded); inBatch = 0;
                        }
                    }
                    if (inBatch > 0) { ps.executeBatch(); out.commit(); loaded += inBatch; reportDelta(job, inBatch); updateTableRowProgress(job, table, loaded); }
                }
            }

            // 2) Apply the change in one transaction.
            if (keyed) {
                // Single set-based UPDATE by key — only the staged keys change, the rest of the table is left
                // untouched. So a partial stage (from a row limit or filter) is fine; no completeness check.
                String updJoin = inPlaceUpdateSql(dialect, schema, table, stg, joinKeys, updateCols);
                try (Statement st = out.createStatement()) { st.executeUpdate(updJoin); }
            } else {
                // Keyless rebuild REPLACES all rows (DELETE + INSERT), so a partial stage would drop the
                // unstaged rows. Verify completeness; a row limit / filter can't be honored safely here.
                long srcCount = countRows(in, schema, table);
                long stgCount = countRows(out, schema, stg);
                if (stgCount != srcCount)
                    throw ApiException.bad("In-place masking of " + table + " staged " + stgCount + " of "
                            + srcCount + " row(s). A row limit or filter can't be applied to in-place masking of a "
                            + "table without a primary key — it would drop the unstaged rows. Remove the limit/filter, "
                            + "or give the table a key. Table left unchanged.");
                String allCols = String.join(",", stageCols.stream().map(ProvisioningService::q).toList());
                boolean useTruncate = "true".equalsIgnoreCase(System.getenv("FORGETDM_INPLACE_KEYLESS_TRUNCATE"));
                String clearOrig = (useTruncate && dialect != SqlDialect.GENERIC)
                        ? dialect.truncateSql(q(schema, table)) : "DELETE FROM " + q(schema, table);
                try (Statement st = out.createStatement()) {
                    st.executeUpdate(clearOrig);
                    st.executeUpdate("INSERT INTO " + q(schema, table) + " (" + allCols + ") SELECT " + allCols
                            + " FROM " + q(schema, stg));
                }
            }
            out.commit();
        } catch (SQLException | RuntimeException e) {
            try { out.rollback(); } catch (SQLException ignore) { /* keep original error */ }
            throw e;
        } finally {
            try { in.rollback(); } catch (SQLException ignore) { /* end the read transaction */ }
            if (inAuto) try { in.setAutoCommit(true); } catch (SQLException ignore) { /* best effort */ }
            dropStaging(out, schema, stg);
        }
        return loaded;
    }

    /** Read-only best-effort check: is the target table range/list/hash partitioned on this engine? */
    private boolean isPartitionedTarget(Connection out, String schema, String table) {
        try {
            SqlDialect d = SqlDialect.fromConnection(out);
            String sql; boolean upper = false;
            switch (d) {
                case POSTGRES -> sql = "SELECT 1 FROM pg_partitioned_table pt JOIN pg_class c ON c.oid = pt.partrelid "
                        + "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE lower(c.relname) = lower(?) "
                        + "AND (? IS NULL OR lower(n.nspname) = lower(?))";
                case ORACLE -> { sql = "SELECT 1 FROM ALL_PART_TABLES WHERE TABLE_NAME = ? AND (? IS NULL OR OWNER = ?)"; upper = true; }
                case MYSQL -> sql = "SELECT 1 FROM information_schema.partitions WHERE table_name = ? "
                        + "AND (? IS NULL OR table_schema = ?) AND partition_name IS NOT NULL LIMIT 1";
                case SQLSERVER -> sql = "SELECT 1 FROM sys.tables t JOIN sys.indexes i ON i.object_id = t.object_id "
                        + "JOIN sys.partition_schemes ps ON ps.data_space_id = i.data_space_id WHERE t.name = ? "
                        + "AND (? IS NULL OR SCHEMA_NAME(t.schema_id) = ?)";
                default -> { return false; }
            }
            String t = upper ? table.toUpperCase(Locale.ROOT) : table;
            String s = schema == null ? null : (upper ? schema.toUpperCase(Locale.ROOT) : schema);
            try (PreparedStatement ps = out.prepareStatement(sql)) {
                ps.setString(1, t); ps.setString(2, s); ps.setString(3, s);
                try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
            }
        } catch (Exception e) { return false; }   // best-effort; never block a load
    }

    /**
     * Phase-1 partition lever: before a bulk load into a partitioned Oracle target, mark its non-key local
     * indexes UNUSABLE so the load isn't slowed by per-row index maintenance; they're rebuilt afterwards.
     * Gated by FORGETDM_PARTITION_INDEX_REBUILD (default off). Oracle only for now. Returns the qualified
     * index names that were disabled (to rebuild). Best-effort — never blocks a load.
     */
    private List<String> maybeDisableIndexesForLoad(Connection out, String schema, List<String> tables,
                                                    Map<String, String> targetTableMap) {
        List<String> disabled = new ArrayList<>();
        if (!"true".equalsIgnoreCase(System.getenv("FORGETDM_PARTITION_INDEX_REBUILD"))) return disabled;
        SqlDialect d; try { d = SqlDialect.fromConnection(out); } catch (Exception e) { return disabled; }
        if (d != SqlDialect.ORACLE) return disabled;   // Oracle first; SQL Server/Postgres handled later
        for (String t : tables) {
            String tgt = targetTableFor(t, targetTableMap);
            if (!isPartitionedTarget(out, schema, tgt)) continue;
            try (PreparedStatement ps = out.prepareStatement(
                    "SELECT ai.owner, ai.index_name FROM all_indexes ai WHERE ai.table_name = ? "
                    + "AND (? IS NULL OR ai.owner = ?) AND NOT EXISTS (SELECT 1 FROM all_constraints ac "
                    + "WHERE ac.owner = ai.owner AND ac.index_name = ai.index_name AND ac.constraint_type IN ('P','U'))")) {
                ps.setString(1, tgt.toUpperCase(Locale.ROOT));
                String s = schema == null ? null : schema.toUpperCase(Locale.ROOT);
                ps.setString(2, s); ps.setString(3, s);
                List<String> idx = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) idx.add(q(rs.getString(1), rs.getString(2))); }
                for (String qn : idx) {
                    try (Statement st = out.createStatement()) { st.execute("ALTER INDEX " + qn + " UNUSABLE"); disabled.add(qn); }
                    catch (Exception ignore) { /* skip this index */ }
                }
            } catch (Exception ignore) { /* best-effort per table */ }
        }
        if (!disabled.isEmpty()) {
            try (Statement st = out.createStatement()) { st.execute("ALTER SESSION SET skip_unusable_indexes=TRUE"); }
            catch (Exception ignore) { }
            try { out.commit(); } catch (Exception ignore) { }
        }
        return disabled;
    }

    /** Rebuild the indexes disabled by {@link #maybeDisableIndexesForLoad}. Runs in a finally so a failed load
     *  never leaves indexes unusable. Best-effort per index; a failed rebuild is left for a DBA. */
    private void rebuildIndexesAfterLoad(Connection out, List<String> disabled) {
        if (disabled == null || disabled.isEmpty()) return;
        for (String qn : disabled) {
            try (Statement st = out.createStatement()) { st.execute("ALTER INDEX " + qn + " REBUILD"); }
            catch (Exception ignore) { /* surface to DBA; don't fail the job in finally */ }
        }
        try { out.commit(); } catch (Exception ignore) { }
    }

    /** Engine-specific session tuning for bulk loads. Oracle: enable parallel DML (direct-path APPEND is already
     *  emitted per INSERT). Best-effort — tuning must never fail a load. */
    private void tuneBulkConnection(Connection out) {
        try {
            if (SqlDialect.fromConnection(out) == SqlDialect.ORACLE) {
                try (Statement st = out.createStatement()) {
                    st.execute("ALTER SESSION ENABLE PARALLEL DML");
                    // Allow inserts when local indexes were marked UNUSABLE for the load (see index lever).
                    st.execute("ALTER SESSION SET skip_unusable_indexes=TRUE");
                }
            }
        } catch (Exception ignore) { /* tuning is best-effort */ }
    }

    private long countRows(Connection c, String schema, String table) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + q(schema, table))) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    /** Best-effort expected row counts for in-place tables (full count, capped by any per-table / blueprint limit). */
    private Map<String, Integer> inPlaceRowCounts(Connection in, String schema, List<String> tables,
                                                  Map<String, Integer> rowLimitByTable, int maxRows) {
        Map<String, Integer> out = new HashMap<>();
        for (String t : tables) {
            try {
                long c = countRows(in, schema, t);
                Integer lim = rowLimitByTable.get(t.toLowerCase(Locale.ROOT));
                if (lim == null && maxRows > 0) lim = maxRows;
                long eff = (lim != null && lim > 0) ? Math.min(c, lim) : c;
                out.put(t.toLowerCase(Locale.ROOT), (int) Math.min(eff, Integer.MAX_VALUE));
            } catch (Exception ignore) { /* best-effort; leave 0 → UI shows indeterminate */ }
        }
        return out;
    }

    /**
     * In-place subset: reduce each table to its referentially-correct subset by DELETING the non-subset rows
     * (Optim-style). Runs child → parent (reverse load order) so a parent is only deleted after its children,
     * keeping foreign keys valid; if a kept child still references a row being deleted the DB blocks it and the
     * transaction rolls back. Keyless tables (no PK to anti-join) are left intact.
     */
    private void deleteNonSubsetRowsInPlace(Connection out, String schema, SubsetService.SubsetPlan plan,
                                            ProvisionJobEntity job) throws SQLException {
        SqlDialect dialect = SqlDialect.fromConnection(out);
        List<String> order = new ArrayList<>(plan.loadOrder);
        Collections.reverse(order);
        for (String table : order) {
            checkCancelled(job);
            SubsetService.TableSlice slice = plan.slices.get(table);
            if (slice == null || slice.keyless() || slice.pkColumn() == null) continue;
            long full = countRows(out, schema, table);
            if (slice.pkValues().size() >= full) continue;   // already within the subset — nothing to trim
            progressMessage(job, "In-place subset: trimming " + table + " to " + slice.pkValues().size() + " row(s)");
            deleteNotInKeep(out, dialect, schema, table, slice.pkColumn(), slice.pkValues());
            out.commit();
        }
    }

    /** Delete rows whose key is not in {@code keep}, via a typed temp keep-table anti-join (scales to big sets). */
    private void deleteNotInKeep(Connection out, SqlDialect dialect, String schema, String table,
                                 String pkCol, java.util.Set<String> keep) throws SQLException {
        String keepTbl = "fdm_keep_" + sanitize(table) + "_" + Long.toHexString(System.nanoTime());
        createStaging(out, dialect, schema, keepTbl, table, List.of(pkCol));   // keepTbl(pkCol) typed like the source
        try {
            try (PreparedStatement ins = out.prepareStatement(
                    "INSERT INTO " + q(schema, keepTbl) + " (" + q(pkCol) + ") VALUES (?)")) {
                int n = 0;
                int batchRows = safeJdbcBatchRows(dialect, 1, 5_000);
                for (String k : keep) { bindValue(ins, 1, k); ins.addBatch(); if (++n % batchRows == 0) ins.executeBatch(); }
                ins.executeBatch();
            }
            try (Statement st = out.createStatement()) {
                st.executeUpdate("DELETE FROM " + q(schema, table) + " WHERE " + q(pkCol)
                        + " NOT IN (SELECT " + q(pkCol) + " FROM " + q(schema, keepTbl) + ")");
            }
        } finally {
            dropStaging(out, schema, keepTbl);
        }
    }

    /** Single unique orderable column for keyset pagination (default: a single-column PK). */
    private String inPlaceChunkKey(JsonNode spec, List<String> joinKeys, String table) {
        String k = textOrNull(spec, "inPlaceChunkKey");
        if (k != null && !k.isBlank()) return k.trim();
        if (joinKeys.size() == 1) return joinKeys.get(0);
        throw ApiException.bad("In-place masking of " + table + " has a composite key " + joinKeys
                + "; specify a single unique orderable 'inPlaceChunkKey' in the job spec.");
    }

    /** Columns that participate in a primary key or a unique index. */
    private Set<String> uniqueIndexedColumns(Connection c, String schema, String table) throws SQLException {
        Set<String> cols = new HashSet<>();
        try (ResultSet rs = c.getMetaData().getIndexInfo(null, schema, table, true, true)) {
            while (rs.next()) {
                String col = rs.getString("COLUMN_NAME");
                if (col != null) cols.add(col.toLowerCase(Locale.ROOT));
            }
        } catch (SQLException ignore) { /* some drivers lack index info; PK check below still applies */ }
        for (String pk : primaryKeyColumns(c, schema, table)) cols.add(pk.toLowerCase(Locale.ROOT));
        return cols;
    }

    /**
     * Auto-create a missing target table from the SOURCE table's structure (column names, types, nullability).
     * Best-effort and dialect-light: reuses the source TYPE_NAME with length/precision so same-dialect
     * source→target works out of the box; cross-dialect type names that the target rejects surface a clear error.
     */
    private void createTargetLikeSource(Connection in, String sourceSchema, String sourceTable,
                                        Connection out, String targetSchema, String targetTable) throws SQLException {
        List<String> defs = new ArrayList<>();
        try (ResultSet rs = in.getMetaData().getColumns(null, sourceSchema, sourceTable, "%")) {
            while (rs.next()) {
                String col = rs.getString("COLUMN_NAME");
                if (col == null) continue;
                String typeName = rs.getString("TYPE_NAME");
                int size = rs.getInt("COLUMN_SIZE");
                int digits = rs.getInt("DECIMAL_DIGITS");
                boolean digitsNull = rs.wasNull();
                int nullable = rs.getInt("NULLABLE");
                defs.add(q(col) + " " + targetColumnType(typeName, size, digits, digitsNull)
                        + (nullable == DatabaseMetaData.columnNoNulls ? " NOT NULL" : ""));
            }
        }
        if (defs.isEmpty())
            throw ApiException.bad("Cannot auto-create target " + targetTable + ": source table "
                    + sourceTable + " has no readable columns.");
        String ddl = "CREATE TABLE " + q(targetSchema, targetTable) + " (" + String.join(", ", defs) + ")";
        try (Statement st = out.createStatement()) { st.executeUpdate(ddl); }
        if (!out.getAutoCommit()) out.commit();
    }

    /** Reconstruct a column type for DDL from JDBC metadata, adding (length) / (precision,scale) where needed. */
    private static String targetColumnType(String typeName, int size, int digits, boolean digitsNull) {
        String t = (typeName == null || typeName.isBlank()) ? "varchar" : typeName.trim();
        String lower = t.toLowerCase(Locale.ROOT);
        boolean isDecimal = lower.contains("numeric") || lower.contains("decimal");
        boolean needsLen = lower.contains("char") || lower.contains("varbit") || lower.contains("bit varying");
        if (isDecimal && size > 0)
            return t + "(" + size + (digitsNull || digits < 0 ? "" : "," + digits) + ")";
        if (needsLen && size > 0 && size < 10_485_760)
            return t + "(" + size + ")";
        return t;
    }

    private void createStaging(Connection out, SqlDialect dialect, String schema, String stg,
                               String srcTable, List<String> cols) throws SQLException {
        dropStaging(out, dialect, schema, stg);
        String colList = String.join(",", cols.stream().map(ProvisioningService::q).toList());
        String src = q(schema, srcTable);
        String stgQ = q(schema, stg);
        String sql = switch (dialect) {
            case SQLSERVER -> "SELECT " + colList + " INTO " + stgQ + " FROM " + src + " WHERE 1=0";
            case POSTGRES  -> "CREATE UNLOGGED TABLE " + stgQ + " AS SELECT " + colList + " FROM " + src + " WHERE 1=0";
            case DB2       -> "CREATE TABLE " + stgQ + " AS (SELECT " + colList + " FROM " + src + ") WITH NO DATA";
            case TERADATA  -> "CREATE MULTISET TABLE " + stgQ + " AS (SELECT " + colList + " FROM " + src + ") WITH NO DATA";
            default        -> "CREATE TABLE " + stgQ + " AS SELECT " + colList + " FROM " + src + " WHERE 1=0";
        };
        try (Statement st = out.createStatement()) { st.executeUpdate(sql); }
        if (!out.getAutoCommit()) out.commit();
    }

    private void dropStaging(Connection out, String schema, String stg) {
        SqlDialect dialect;
        try { dialect = SqlDialect.fromConnection(out); }
        catch (Exception e) { dialect = SqlDialect.GENERIC; }
        dropStaging(out, dialect, schema, stg);
    }

    private void dropStaging(Connection out, SqlDialect dialect, String schema, String stg) {
        // Clear any aborted transaction first so this cleanup can run; use IF EXISTS so dropping a
        // non-existent staging table never errors (which on Postgres would abort the whole transaction
        // and make the subsequent CREATE fail with "current transaction is aborted").
        try { if (!out.getAutoCommit()) out.rollback(); } catch (SQLException ignore) { }
        try (Statement st = out.createStatement()) {
            String sql = switch (dialect) {
                case DB2, ORACLE, TERADATA -> "DROP TABLE " + q(schema, stg);
                default -> "DROP TABLE IF EXISTS " + q(schema, stg);
            };
            st.executeUpdate(sql);
            if (!out.getAutoCommit()) out.commit();
        } catch (SQLException ignore) {
            try { if (!out.getAutoCommit()) out.rollback(); } catch (SQLException e2) { /* ignore */ }
        }
    }

    /** Dialect-specific set-based UPDATE that copies staged masked columns into the live table by key. */
    private static String inPlaceUpdateSql(SqlDialect dialect, String schema, String table, String stg,
                                           List<String> joinKeys, List<String> updateCols) {
        String t = "t", s = "s";
        switch (dialect) {
            case POSTGRES: {
                String on = String.join(" AND ", joinKeys.stream().map(k -> t + "." + q(k) + "=" + s + "." + q(k)).toList());
                String set = String.join(",", updateCols.stream().map(c -> q(c) + "=" + s + "." + q(c)).toList());
                return "UPDATE " + q(schema, table) + " " + t + " SET " + set
                        + " FROM " + q(schema, stg) + " " + s + " WHERE " + on;
            }
            case MYSQL: {
                // ANSI-quoted to match the rest of the in-place SQL (read SELECT + staging), like copyTable.
                String on = String.join(" AND ", joinKeys.stream().map(k -> t + "." + q(k) + "=" + s + "." + q(k)).toList());
                String set = String.join(",", updateCols.stream().map(c -> t + "." + q(c) + "=" + s + "." + q(c)).toList());
                return "UPDATE " + q(schema, table) + " " + t
                        + " JOIN " + q(schema, stg) + " " + s + " ON " + on + " SET " + set;
            }
            case SQLSERVER: {
                String on = String.join(" AND ", joinKeys.stream().map(k -> t + "." + q(k) + "=" + s + "." + q(k)).toList());
                String set = String.join(",", updateCols.stream().map(c -> t + "." + q(c) + "=" + s + "." + q(c)).toList());
                return "UPDATE " + t + " SET " + set + " FROM " + q(schema, table) + " " + t
                        + " JOIN " + q(schema, stg) + " " + s + " ON " + on;
            }
            default: { // ORACLE, DB2, H2, GENERIC — MERGE (update columns exclude the ON keys)
                String on = String.join(" AND ", joinKeys.stream().map(k -> t + "." + q(k) + "=" + s + "." + q(k)).toList());
                boolean db2 = dialect == SqlDialect.DB2;
                String set = String.join(",", updateCols.stream()
                        .map(c -> (db2 ? q(c) : t + "." + q(c)) + "=" + s + "." + q(c)).toList());
                return "MERGE INTO " + q(schema, table) + (db2 ? " AS " : " ") + t
                        + " USING " + q(schema, stg) + (db2 ? " AS " : " ") + s
                        + " ON (" + on + ") WHEN MATCHED THEN UPDATE SET " + set;
            }
        }
    }

    private static Set<String> lower(List<String> in) {
        Set<String> s = new HashSet<>();
        for (String x : in) s.add(x.toLowerCase(Locale.ROOT));
        return s;
    }

    private static String sanitize(String s) {
        String x = s.replaceAll("[^A-Za-z0-9]", "_");
        return x.length() > 20 ? x.substring(0, 20) : x;
    }

    private static void requireInPlaceSameTable(DataSourceEntity src, DataSourceEntity tgt,
                                                String sourceSchema, String targetSchema,
                                                Collection<String> tables, Map<String, String> targetTableMap) {
        if (!samePhysicalDataSource(src, tgt) || !sameIgnoreCase(sourceSchema, targetSchema))
            throw ApiException.bad("In-place masking requires the same data source and schema for source and target.");
        // In-place always masks the SOURCE table itself; any target-table mapping configured in the
        // blueprint's Table Map is intentionally ignored here, so it does not block an in-place run.
    }

    private List<ColumnPlan> buildColumnPlans(String sourceTable, String targetTable,
                                              List<String> sourceCols, List<String> targetCols,
                                              Map<String, Integer> targetTypes,
                                              Map<String, ColumnOverrideEntity> overrides) {
        Map<String, String> sourceByLower = canonicalColumns(sourceCols);
        List<ColumnPlan> plans = new ArrayList<>();
        for (String targetCol : targetCols) {
            ColumnOverrideEntity override = overrides.get(targetCol.toLowerCase(Locale.ROOT));
            String overrideType = override == null ? "USE_POLICY" : override.getOverrideType();
            if ("SUPPRESS".equals(overrideType)) continue;

            String sourceCol = null;
            if (override != null && override.getSourceColumnName() != null && !override.getSourceColumnName().isBlank()) {
                sourceCol = sourceByLower.get(override.getSourceColumnName().toLowerCase(Locale.ROOT));
                if (sourceCol == null) {
                    throw ApiException.bad("Mapped source column " + override.getSourceColumnName()
                            + " was not found on " + sourceTable + " for target " + targetTable + "." + targetCol);
                }
            } else if (!"LITERAL".equals(overrideType) && !"NULL_OUT".equals(overrideType)) {
                sourceCol = sourceByLower.get(targetCol.toLowerCase(Locale.ROOT));
            }
            // a conditional LITERAL/NULL_OUT still needs the original source value for the false branch
            if (sourceCol == null && hasCondition(override))
                sourceCol = sourceByLower.get(targetCol.toLowerCase(Locale.ROOT));

            if (sourceCol == null && !"LITERAL".equals(overrideType) && !"NULL_OUT".equals(overrideType)) continue;
            plans.add(new ColumnPlan(targetCol, sourceCol, override,
                    targetTypes.getOrDefault(targetCol.toLowerCase(Locale.ROOT), Types.VARCHAR)));
        }
        return plans;
    }

    private static Map<String, String> canonicalColumns(List<String> columns) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String column : columns) {
            if (column != null && !column.isBlank())
                result.putIfAbsent(column.toLowerCase(Locale.ROOT), column);
        }
        return result;
    }

    /** Apply a policy masking rule to a mapped source value, or pass through if no rule applies. */
    private Object applyPolicyMask(MaskingEngine eng, String table, String sourceCol, String targetCol,
                                   Object original, String originalText, int sqlType,
                                   Map<String, MaskingRuleEntity> ruleByCol, MaskContext ctx) {
        MaskingRuleEntity rule = ruleByCol.get(sourceCol.toLowerCase(Locale.ROOT));
        if (rule == null) rule = ruleByCol.get(targetCol.toLowerCase(Locale.ROOT));
        if (rule == null || original == null) return original;
        String masked = eng.mask(MaskFunction.valueOf(rule.getFunction()),
                saltFor(rule, table, rule.getColumnName() != null ? rule.getColumnName() : sourceCol, keySaltTL.get()),
                originalText, rule.getParam1(), rule.getParam2(), ctx);
        ctx.masked.put(targetCol.toLowerCase(Locale.ROOT), masked);
        return masked == null ? null : coerce(masked, sqlType);
    }

    // -------------------- conditional masking --------------------

    private record Cond(String column, String operator, String value,
                        String joinTable, String joinSourceCol, String joinTargetCol) {}
    private record CondSet(String logic, List<Cond> clauses) {}   // logic: ALL (AND) | ANY (OR)

    private static boolean hasCondition(ColumnOverrideEntity ov) {
        return ov != null && (condNotBlank(ov.getCondExpr()) || condNotBlank(ov.getCondJson()) || condNotBlank(ov.getCondColumn()));
    }

    /** Light guard on free-form condition/JOIN text: no statement terminators or SQL comments. */
    private static void guardCondition(String expr) {
        String e = expr == null ? "" : expr;
        if (e.contains(";") || e.contains("--") || e.contains("/*") || e.contains("*/"))
            throw ApiException.bad("Condition/JOIN may not contain ';' or SQL comments: " + expr);
    }

    /** Resolve a column override's condition: multi-clause JSON if present, else the single cond_* columns. */
    private CondSet condSetOf(ColumnOverrideEntity ov) {
        if (ov == null) return null;
        if (condNotBlank(ov.getCondJson())) {
            try {
                JsonNode root = json.readTree(ov.getCondJson());
                List<Cond> clauses = new ArrayList<>();
                for (JsonNode c : root.path("clauses")) {
                    String col = c.path("column").asText(null);
                    if (col == null || col.isBlank()) continue;
                    clauses.add(new Cond(col, c.path("operator").asText("EQ"), c.path("value").asText(null),
                            c.path("joinTable").asText(null), c.path("joinSourceCol").asText(null), c.path("joinTargetCol").asText(null)));
                }
                if (!clauses.isEmpty()) return new CondSet(root.path("logic").asText("ALL"), clauses);
            } catch (Exception ignored) { /* fall back to the flat columns */ }
        }
        if (condNotBlank(ov.getCondColumn()))
            return new CondSet("ALL", List.of(new Cond(ov.getCondColumn(), ov.getCondOperator(), ov.getCondValue(),
                    ov.getCondJoinTable(), ov.getCondJoinSourceCol(), ov.getCondJoinTargetCol())));
        return null;
    }

    private static String joinKey(Cond c) {
        return String.valueOf(c.joinTable()).toLowerCase(Locale.ROOT) + "|"
                + String.valueOf(c.joinTargetCol()).toLowerCase(Locale.ROOT) + "|"
                + String.valueOf(c.column()).toLowerCase(Locale.ROOT);
    }

    /** Load a joined table into a key -> value map for condition lookups (capped to guard memory). */
    private Map<String, String> loadJoinMap(Connection in, String schema, String table, String keyCol, String valCol)
            throws SQLException {
        Map<String, String> map = new HashMap<>();
        if (!condNotBlank(table) || !condNotBlank(keyCol) || !condNotBlank(valCol)) return map;
        String sql = "SELECT " + q(keyCol) + "," + q(valCol) + " FROM " + q(schema, table);
        try (Statement st = in.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int n = 0;
            while (rs.next() && n++ < 2_000_000) {
                String k = rs.getString(1);
                if (k != null) map.put(k.trim().toLowerCase(Locale.ROOT), rs.getString(2));
            }
        }
        return map;
    }

    /** Combine clauses by ALL (AND) or ANY (OR). */
    private boolean evalCondSet(CondSet cs, Map<String, String> textBySource, Map<String, Map<String, String>> joinMaps) {
        boolean all = !"ANY".equalsIgnoreCase(cs.logic());
        for (Cond c : cs.clauses()) {
            boolean r = evalClause(c, textBySource, joinMaps);
            if (all && !r) return false;    // AND: any false clause fails the set
            if (!all && r) return true;     // OR: any true clause satisfies the set
        }
        return all;                          // AND with all true -> true; OR with none true -> false
    }

    private boolean evalClause(Cond c, Map<String, String> textBySource, Map<String, Map<String, String>> joinMaps) {
        String testValue;
        if (condNotBlank(c.joinTable())) {
            String joinKeyVal = textBySource.get(String.valueOf(c.joinSourceCol()).toLowerCase(Locale.ROOT));
            Map<String, String> jm = joinMaps.get(joinKey(c));
            testValue = (joinKeyVal == null || jm == null) ? null : jm.get(joinKeyVal.trim().toLowerCase(Locale.ROOT));
        } else {
            testValue = textBySource.get(String.valueOf(c.column()).toLowerCase(Locale.ROOT));
        }
        return condCompare(testValue, c.operator(), c.value());
    }

    private static boolean condCompare(String value, String op, String expected) {
        String o = op == null || op.isBlank() ? "EQ" : op.trim().toUpperCase(Locale.ROOT);
        if (o.equals("IS_NULL")) return value == null || value.isEmpty();
        if (o.equals("IS_NOT_NULL")) return value != null && !value.isEmpty();
        if (value == null) return o.equals("NE") || o.equals("NOT_IN");
        String v = value.trim();
        String e = expected == null ? "" : expected.trim();
        switch (o) {
            case "EQ":          return v.equalsIgnoreCase(e);
            case "NE":          return !v.equalsIgnoreCase(e);
            case "CONTAINS":    return v.toLowerCase(Locale.ROOT).contains(e.toLowerCase(Locale.ROOT));
            case "STARTS_WITH": return v.toLowerCase(Locale.ROOT).startsWith(e.toLowerCase(Locale.ROOT));
            case "IN":          return condCsv(e).stream().anyMatch(x -> x.equalsIgnoreCase(v));
            case "NOT_IN":      return condCsv(e).stream().noneMatch(x -> x.equalsIgnoreCase(v));
            case "GT": case "LT": case "GTE": case "LTE": return condNumCompare(v, e, o);
            default:            return v.equalsIgnoreCase(e);
        }
    }

    private static boolean condNumCompare(String a, String b, String op) {
        try {
            double x = Double.parseDouble(a), y = Double.parseDouble(b);
            switch (op) { case "GT": return x > y; case "LT": return x < y; case "GTE": return x >= y; case "LTE": return x <= y; }
        } catch (NumberFormatException ignored) {
            int c = a.compareToIgnoreCase(b);
            switch (op) { case "GT": return c > 0; case "LT": return c < 0; case "GTE": return c >= 0; case "LTE": return c <= 0; }
        }
        return false;
    }

    private static List<String> condCsv(String s) {
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) { String t = p.trim(); if (!t.isEmpty()) out.add(t); }
        return out;
    }

    private static boolean condNotBlank(String s) { return s != null && !s.isBlank(); }

    /**
     * Bind a PK string value with the correct JDBC type.
     * Integer PK columns cause PostgreSQL to reject setString() with
     * "operator does not exist: integer = character varying". Parsing to Long first avoids this.
     */
    private static void bindValue(PreparedStatement ps, int idx, String value) throws java.sql.SQLException {
        if (value == null) { ps.setNull(idx, java.sql.Types.VARCHAR); return; }
        try {
            ps.setLong(idx, Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            ps.setString(idx, value);
        }
    }

    private static String selectWhere(String pkColumn, List<String> keyChunk, String rowFilter, String alias) {
        if (keyChunk != null) {
            String pkRef = alias == null ? q(pkColumn) : alias + "." + q(pkColumn);
            return " WHERE " + pkRef + " IN (" + String.join(",", Collections.nCopies(keyChunk.size(), "?")) + ")";
        }
        return rowFilter == null || rowFilter.isBlank() ? "" : " WHERE " + rowFilter;
    }

    private record LoadOptions(String action, String targetPrep, List<String> keyColumns, int batchSize) {
        boolean truncateOnly() { return "TRUNCATE_ONLY".equals(action); }
        boolean needsKeys() { return "UPDATE".equals(action) || "INSERT_UPDATE".equals(action); }
        boolean isInPlace() { return "IN_PLACE".equals(action); }
        boolean clearsTarget() { return !"NONE".equals(targetPrep) && !isInPlace(); }
    }

    private LoadOptions loadOptions(JsonNode spec, String defaultAction) {
        String action = Optional.ofNullable(textOrNull(spec, "loadAction")).orElse(defaultAction);
        action = normalizeLoadAction(action);
        String prep = textOrNull(spec, "targetPrep");
        if (prep == null) {
            if (spec.path("truncateTarget").asBoolean(false) || "REPLACE".equals(action)) prep = "DELETE";
            else if ("TRUNCATE_ONLY".equals(action)) prep = "TRUNCATE";
            else prep = "NONE";
        }
        prep = normalizeTargetPrep(prep);
        if ("TRUNCATE_ONLY".equals(action) && "NONE".equals(prep)) prep = "TRUNCATE";
        int batch = spec.path("batchSize").asInt(props.getProvisioning().getBatchSize());
        batch = batch <= 0 ? props.getProvisioning().getBatchSize() : Math.min(batch, 50_000);
        return new LoadOptions(action, prep, keyColumns(spec), batch);
    }

    private static int safeJdbcBatchRows(SqlDialect dialect, int bindColumnsPerRow, int requestedRows) {
        int requested = requestedRows <= 0 ? 1 : requestedRows;
        int columns = Math.max(1, bindColumnsPerRow);
        int limit = Math.max(1, dialect == null ? 32_767 : dialect.bindParamLimit());
        int margin = dialect == SqlDialect.SQLSERVER ? 100 : 50;
        int maxByParams = Math.max(1, (limit - margin) / columns);
        int maxByDialect = dialect == SqlDialect.SQLSERVER ? Math.min(maxByParams, 1000) : maxByParams;
        return Math.max(1, Math.min(requested, maxByDialect));
    }

    static boolean containsLobType(int[] sqlTypes) {
        if (sqlTypes == null) return false;
        for (int sqlType : sqlTypes)
            if (sqlType == Types.BLOB || sqlType == Types.CLOB || sqlType == Types.NCLOB
                    || sqlType == Types.LONGVARBINARY || sqlType == Types.LONGVARCHAR
                    || sqlType == Types.LONGNVARCHAR || sqlType == Types.SQLXML) return true;
        return false;
    }

    static boolean containsLobValue(Object[] values) {
        if (values == null) return false;
        for (Object value : values)
            if (value instanceof Blob || value instanceof Clob || value instanceof SQLXML) return true;
        return false;
    }

    /** Cross-driver bind that consumes source LOB locators as streams instead of passing vendor objects to a target driver. */
    static void bindJdbcValue(PreparedStatement ps, int index, Object value, int sqlType) throws SQLException {
        if (value == null) {
            ps.setNull(index, sqlType);
        } else if (value instanceof NClob nclob) {
            ps.setNCharacterStream(index, nclob.getCharacterStream(), nclob.length());
        } else if (value instanceof Clob clob) {
            ps.setCharacterStream(index, clob.getCharacterStream(), clob.length());
        } else if (value instanceof Blob blob) {
            ps.setBinaryStream(index, blob.getBinaryStream(), blob.length());
        } else if (value instanceof SQLXML xml) {
            ps.setCharacterStream(index, xml.getCharacterStream());
        } else if (value instanceof byte[] bytes) {
            ps.setBinaryStream(index, new java.io.ByteArrayInputStream(bytes), bytes.length);
        } else if (value instanceof String text && (sqlType == Types.CLOB || sqlType == Types.NCLOB
                || sqlType == Types.LONGVARCHAR || sqlType == Types.LONGNVARCHAR || sqlType == Types.SQLXML)) {
            ps.setCharacterStream(index, new java.io.StringReader(text), text.length());
        } else {
            ps.setObject(index, value);
        }
    }

    private static String normalizeLoadAction(String action) {
        String a = action == null ? "REPLACE" : action.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (a) {
            case "LOAD_REPLACE", "RELOAD", "REPLACE_ALL" -> "REPLACE";
            case "APPEND", "INSERT_ONLY" -> "INSERT";
            case "MERGE", "UPSERT", "INSERT_UPDATE", "INSERT_OR_UPDATE" -> "INSERT_UPDATE";
            case "TRUNCATE", "TRUNCATE_TABLE", "CLEAR_ONLY" -> "TRUNCATE_ONLY";
            case "IN_PLACE", "INPLACE", "IN_PLACE_UPDATE", "IN_PLACE_MASK", "INPLACE_MASK" -> "IN_PLACE";
            case "REPLACE", "INSERT", "UPDATE", "TRUNCATE_ONLY" -> a;
            default -> throw ApiException.bad("loadAction must be REPLACE, INSERT, UPDATE, INSERT_UPDATE, TRUNCATE_ONLY, or IN_PLACE");
        };
    }

    private static String normalizeTargetPrep(String prep) {
        String p = prep == null ? "NONE" : prep.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (p) {
            case "", "NONE", "NO_PREP" -> "NONE";
            case "DELETE", "DELETE_ROWS", "CLEAR_ROWS" -> "DELETE";
            case "TRUNCATE", "TRUNCATE_TABLE" -> "TRUNCATE";
            default -> throw ApiException.bad("targetPrep must be NONE, DELETE, or TRUNCATE");
        };
    }

    private static List<String> keyColumns(JsonNode spec) {
        JsonNode node = spec == null ? null : spec.get("keyColumns");
        if (node == null || node.isNull()) return List.of();
        List<String> out = new ArrayList<>();
        if (node.isArray()) node.forEach(n -> addKey(out, n.asText()));
        else for (String part : node.asText("").split(",")) addKey(out, part);
        return out;
    }

    private static void addKey(List<String> out, String value) {
        String v = value == null ? "" : value.trim();
        if (!v.isEmpty()) out.add(v);
    }

    private void prepareTarget(Connection out, String schema, String table, LoadOptions load) throws SQLException {
        prepareTargets(out, schema, List.of(table), load);
    }

    private void prepareTargets(Connection out, String schema, List<String> tables, LoadOptions load) throws SQLException {
        if (!load.clearsTarget()) return;
        List<String> selected = new ArrayList<>();
        for (String t : distinctTables(tables))            // skip targets that don't exist yet — copyTable will create them fresh
            if (!columnsOf(out, schema, t).isEmpty()) selected.add(t);
        if (selected.isEmpty()) return;
        // On engines where TRUNCATE implicitly commits, a REPLACE must clear with transactional DELETE
        // so a failed load doesn't leave the target permanently emptied. (Explicit TRUNCATE_ONLY still truncates.)
        boolean useTruncate = "TRUNCATE".equals(load.targetPrep());
        if (useTruncate && !load.truncateOnly() && SqlDialect.fromConnection(out).ddlAutoCommits()) {
            useTruncate = false;
        }
        if (useTruncate) {
            try {
                truncateTableSet(out, schema, selected);
                return;
            } catch (SQLException e) {
                if (selected.size() == 1) {
                    throw ApiException.bad("TRUNCATE target failed for " + selected.get(0) + ": " + e.getMessage()
                            + ". Select all dependent tables or use Delete rows first.");
                }
                throw ApiException.bad("TRUNCATE target failed for selected tables: " + e.getMessage()
                        + ". The selected table set must include FK-dependent tables, or use Delete rows first.");
            }
        }
        // Delete from external tables that have FK references pointing at selected tables
        // but are not themselves in the selection (e.g. loan_payments → loans when only loans is selected).
        // These must be cleared first or Postgres will abort the connection on FK violation.
        List<String> extKids = externalFkChildren(out, schema, selected);
        for (String ext : extKids) {
            try (Statement st = out.createStatement()) {
                st.executeUpdate("DELETE FROM " + q(schema, ext));
            } catch (SQLException e) {
                throw ApiException.bad("DELETE failed for FK-referencing table '" + ext + "': " + e.getMessage()
                        + ". This table references a selected table. Include it in your selection to load it, or use Truncate selected tables together.");
            }
        }
        List<String> deleteOrder = childFirstOrder(out, schema, selected);
        for (String table : deleteOrder) {
            String sql = "DELETE FROM " + q(schema, table);
            try (Statement st = out.createStatement()) {
                st.executeUpdate(sql);
            } catch (SQLException e) {
                throw ApiException.bad("DELETE target failed for " + table + ": " + e.getMessage()
                        + ". Make sure dependent tables are selected or clear children first.");
            }
        }
    }

    /**
     * Finds tables in {@code schema} that have FK references pointing at any of the {@code selected} tables
     * but are NOT themselves in the selection. These are "external FK children" that must be cleared before
     * the selected tables can be deleted — otherwise the FK constraint aborts the Postgres connection.
     * Returns them in child-first order (safe for sequential DELETE).
     */
    private List<String> externalFkChildren(Connection c, String schema, List<String> selected) throws SQLException {
        Set<String> selectedLower = selected.stream()
                .map(t -> t.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> external = new LinkedHashSet<>();
        for (String table : selected) {
            try (ResultSet rs = c.getMetaData().getExportedKeys(null, schema, table)) {
                while (rs.next()) {
                    String fkTable = rs.getString("FKTABLE_NAME");
                    String fkSchema = rs.getString("FKTABLE_SCHEM");
                    // Only include tables in the same schema, not already in the selection
                    if (fkTable == null) continue;
                    if (fkSchema != null && schema != null && !fkSchema.equalsIgnoreCase(schema)) continue;
                    if (!selectedLower.contains(fkTable.toLowerCase(Locale.ROOT))) {
                        external.add(fkTable);
                    }
                }
            }
        }
        if (external.isEmpty()) return List.of();
        return childFirstOrder(c, schema, new ArrayList<>(external));
    }

    private void truncateTableSet(Connection out, String schema, List<String> tables) throws SQLException {
        SqlDialect dialect = SqlDialect.fromConnection(out);
        if (dialect.supportsMultiTableTruncate()) {
            // Postgres: one atomic statement across all tables.
            // CASCADE handles FK references from tables outside the selected set (e.g. loan_payments
            // referencing loans when only loans is in the selection).
            String sql = "TRUNCATE TABLE " + String.join(",", tables.stream().map(t -> q(schema, t)).toList()) + " CASCADE";
            try (Statement st = out.createStatement()) { st.executeUpdate(sql); }
            return;
        }
        // DB2/Oracle/SQL Server/H2: one statement per table, children before parents
        // (DB2 needs TRUNCATE ... IMMEDIATE; none of these accept a multi-table list or CASCADE)
        List<String> order = childFirstOrder(out, schema, tables);
        for (String table : order) {
            try (Statement st = out.createStatement()) {
                st.executeUpdate(dialect.truncateSql(q(schema, table)));
            }
        }
    }

    private List<String> childFirstOrder(Connection c, String schema, List<String> tables) throws SQLException {
        List<String> parentFirst = referentialLoadOrder(c, schema, tables);
        Collections.reverse(parentFirst);
        return parentFirst;
    }

    private List<String> referentialLoadOrder(Connection c, String schema, List<String> tables) throws SQLException {
        List<String> selected = distinctTables(tables);
        Map<String, String> canonical = new LinkedHashMap<>();
        for (String table : selected) canonical.put(table.toLowerCase(Locale.ROOT), table);

        Map<String, Set<String>> childrenByParent = new LinkedHashMap<>();
        Map<String, Integer> indegree = new LinkedHashMap<>();
        for (String table : selected) {
            childrenByParent.put(table, new LinkedHashSet<>());
            indegree.put(table, 0);
        }
        for (String child : selected) {
            try (ResultSet rs = c.getMetaData().getImportedKeys(null, schema, child)) {
                while (rs.next()) {
                    String parent = canonical.get(String.valueOf(rs.getString("PKTABLE_NAME")).toLowerCase(Locale.ROOT));
                    String actualChild = canonical.get(String.valueOf(rs.getString("FKTABLE_NAME")).toLowerCase(Locale.ROOT));
                    if (parent == null || actualChild == null || parent.equals(actualChild)) continue;
                    if (childrenByParent.get(parent).add(actualChild)) {
                        indegree.put(actualChild, indegree.get(actualChild) + 1);
                    }
                }
            }
        }

        Deque<String> ready = new ArrayDeque<>();
        for (String table : selected) if (indegree.get(table) == 0) ready.add(table);
        List<String> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            String table = ready.removeFirst();
            ordered.add(table);
            for (String child : childrenByParent.getOrDefault(table, Set.of())) {
                int next = indegree.get(child) - 1;
                indegree.put(child, next);
                if (next == 0) ready.add(child);
            }
        }
        if (ordered.size() < selected.size()) {
            for (String table : selected) if (!ordered.contains(table)) ordered.add(table);
        }
        return ordered;
    }

    private static List<String> distinctTables(List<String> tables) {
        if (tables == null) return List.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (String table : tables) {
            if (table == null || table.isBlank()) continue;
            out.putIfAbsent(table.toLowerCase(Locale.ROOT), table.trim());
        }
        return new ArrayList<>(out.values());
    }

    /** Bridge overload — uses empty custom-PK map (synthetic load path). */
    private static Map<String, TableProfileEntity> profileByTable(List<TableProfileEntity> profiles) {
        Map<String, TableProfileEntity> map = new LinkedHashMap<>();
        if (profiles == null) return map;
        for (TableProfileEntity profile : profiles) {
            if (profile.getTableName() == null || profile.getTableName().isBlank()) continue;
            map.put(profile.getTableName().toLowerCase(Locale.ROOT), profile);
        }
        return map;
    }

    private Map<String, List<MaskingRuleEntity>> rulesForProfile(
            Map<String, List<MaskingRuleEntity>> defaultRuleMap,
            Map<Long, Map<String, List<MaskingRuleEntity>>> profileRuleCache,
            TableProfileEntity profile) {
        if (profile == null) return defaultRuleMap;
        Long policyId = profile.getPolicyId();
        // No per-table policy → fall back to the top-level (job/blueprint) default policy so a single
        // policy can be applied to every table, while any table may still override it with its own.
        if (policyId == null) return defaultRuleMap;
        return profileRuleCache.computeIfAbsent(policyId, this::rulesByTable);
    }

    /**
     * The masking rules that actually apply to a table. The table's own (Column Map) policy is authoritative;
     * only if it has no rule for this table do we fall back to the default policy. This guarantees a policy set
     * at the Column Map level takes effect, and a misconfigured per-table policy can never silently leave a
     * table unmasked when a default exists.
     */
    private List<MaskingRuleEntity> effectiveTableRules(
            Map<String, List<MaskingRuleEntity>> defaultRuleMap,
            Map<Long, Map<String, List<MaskingRuleEntity>>> profileRuleCache,
            TableProfileEntity profile, String schema, String table) {
        List<MaskingRuleEntity> own = null;
        if (profile != null && profile.getPolicyId() != null)
            own = rulesFor(profileRuleCache.computeIfAbsent(profile.getPolicyId(), this::rulesByTable), schema, table);
        if (own != null && !own.isEmpty()) return own;
        return rulesFor(defaultRuleMap, schema, table);
    }

    private static boolean hasProfileSourceOverrides(DataSetDefinitionEntity def, String defaultSourceSchema,
                                                     List<TableProfileEntity> profiles) {
        if (profiles == null || profiles.isEmpty()) return false;
        for (TableProfileEntity profile : profiles) {
            if (!profile.isIncluded()) continue;
            if (!Objects.equals(def.getDataSourceId(), profileSourceDataSourceId(def, profile))) return true;
            if (!sameIgnoreCase(defaultSourceSchema, profileSourceSchemaName(def, defaultSourceSchema, profile))) return true;
        }
        return false;
    }

    private static Long profileSourceDataSourceId(DataSetDefinitionEntity def, TableProfileEntity profile) {
        return profile.getSourceDataSourceId() != null ? profile.getSourceDataSourceId() : def.getDataSourceId();
    }

    private static String profileSourceSchemaName(DataSetDefinitionEntity def, String defaultSourceSchema, TableProfileEntity profile) {
        String schema = profile.getSourceSchemaName();
        if (schema != null && !schema.isBlank()) return schema.trim();
        Long sourceDataSourceId = profileSourceDataSourceId(def, profile);
        return Objects.equals(sourceDataSourceId, def.getDataSourceId()) ? defaultSourceSchema : null;
    }

    private static Map<String, String> targetTableMap(List<TableProfileEntity> profiles) {
        Map<String, String> map = new LinkedHashMap<>();
        if (profiles == null) return map;
        for (TableProfileEntity profile : profiles) {
            if (profile.getTableName() == null || profile.getTableName().isBlank()) continue;
            String target = profile.getTargetTableName();
            if (target != null && !target.isBlank())
                map.put(profile.getTableName().toLowerCase(Locale.ROOT), target.trim());
        }
        return map;
    }

    private static String targetTableFor(String sourceTable, Map<String, String> targetTableMap) {
        if (sourceTable == null) return null;
        return targetTableMap.getOrDefault(sourceTable.toLowerCase(Locale.ROOT), sourceTable);
    }

    private static List<String> targetTablesFor(List<String> sourceTables, Map<String, String> targetTableMap) {
        if (sourceTables == null) return List.of();
        return distinctTables(sourceTables.stream()
                .map(table -> targetTableFor(table, targetTableMap))
                .toList());
    }

    private static void rejectSelfMappedTables(DataSourceEntity src, DataSourceEntity tgt,
                                               String sourceSchema, String targetSchema,
                                               Collection<String> sourceTables,
                                               Map<String, String> targetTableMap) {
        if (!samePhysicalDataSource(src, tgt)) return;
        if (!sameIgnoreCase(sourceSchema, targetSchema)) return;
        for (String sourceTable : sourceTables) {
            String targetTable = targetTableFor(sourceTable, targetTableMap);
            if (sameIgnoreCase(sourceTable, targetTable)) {
                throw ApiException.bad("Source and target resolve to the same table "
                        + q(sourceSchema, sourceTable)
                        + ". Choose a different target data source, schema, or target table before provisioning.");
            }
        }
    }

    /** Reject the case where two distinct source tables resolve to the same target table (would clobber data). */
    private static void rejectDuplicateTargets(Collection<String> sourceTables, String targetSchema,
                                               Map<String, String> targetTableMap) {
        Map<String, String> seen = new HashMap<>();
        for (String sourceTable : sourceTables) {
            String target = targetFor(targetSchema, sourceTable, targetTableMap);
            String prior = seen.putIfAbsent(target.toLowerCase(Locale.ROOT), sourceTable);
            if (prior != null && !sameIgnoreCase(prior, sourceTable))
                throw ApiException.bad("Source tables " + prior + " and " + sourceTable
                        + " both map to target " + target + ". Give them distinct target tables before provisioning.");
        }
    }

    private static String targetFor(String targetSchema, String sourceTable, Map<String, String> targetTableMap) {
        String t = targetTableFor(sourceTable, targetTableMap);
        return (targetSchema == null || targetSchema.isBlank()) ? t : targetSchema + "." + t;
    }

    private static boolean samePhysicalDataSource(DataSourceEntity a, DataSourceEntity b) {
        if (a == null || b == null) return false;
        if (Objects.equals(a.getId(), b.getId())) return true;
        return sameIgnoreCase(a.getJdbcUrl(), b.getJdbcUrl())
                && sameIgnoreCase(a.getUsername(), b.getUsername());
    }

    private static boolean sameIgnoreCase(String a, String b) {
        return String.valueOf(a == null ? "" : a).equalsIgnoreCase(String.valueOf(b == null ? "" : b));
    }

    private List<String> keyColumns(Connection out, String schema, String table, List<String> columns, LoadOptions load) throws SQLException {
        return keyColumns(out, schema, table, columns, load, Map.of());
    }

    /**
     * Resolve key columns for UPDATE / INSERT_UPDATE actions.
     * Priority:
     *  1. Explicit key columns from the job spec (LoadOptions.keyColumns).
     *  2. User-defined PK from the DataScope custom-PK map (entity-driven override).
     *  3. Primary keys from the JDBC catalog (DB metadata).
     */
    private List<String> keyColumns(Connection out, String schema, String table, List<String> columns,
                                    LoadOptions load, Map<String, String> customPkMap) throws SQLException {
        if (!load.needsKeys()) return List.of();

        // 1. Explicit spec override
        if (!load.keyColumns().isEmpty()) {
            return resolveKeys(load.keyColumns(), columns, table);
        }

        // 2. User-defined PK (DataScope / Access Definition)
        String customPk = customPkMap.get(table.toLowerCase(Locale.ROOT));
        if (customPk != null && !customPk.isBlank()) {
            return parseColumns(customPk, columns, table);
        }

        // 3. DB catalog
        List<String> keys = primaryKeyColumns(out, schema, table);
        if (keys.isEmpty()) {
            throw ApiException.bad("Load action " + load.action() + " requires key columns. Enter key columns or define a target primary key for " + table + ".");
        }
        return resolveKeys(keys, columns, table);
    }

    /** Split a comma-separated column list, trim, validate each against the available columns list. */
    private static List<String> parseColumns(String csv, List<String> columns, String table) {
        String[] parts = csv.split(",");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            String name = part.trim();
            if (!name.isBlank()) result.add(name);
        }
        return resolveKeys(result, columns, table);
    }

    /** Validate and canonicalize a set of key names against the actual column list. */
    private static List<String> resolveKeys(List<String> keys, List<String> columns, String table) {
        Map<String, String> canonical = new LinkedHashMap<>();
        for (String col : columns) canonical.put(col.toLowerCase(Locale.ROOT), col);
        List<String> resolved = new ArrayList<>();
        for (String key : keys) {
            String col = canonical.get(key.toLowerCase(Locale.ROOT));
            if (col == null) throw ApiException.bad("Key column " + key + " is not in selected columns for " + table);
            resolved.add(col);
        }
        return resolved;
    }

    private List<String> primaryKeyColumns(Connection c, String schema, String table) throws SQLException {
        Map<Short, String> ordered = new TreeMap<>();
        try (ResultSet rs = c.getMetaData().getPrimaryKeys(null, schema, table)) {
            while (rs.next()) ordered.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
        }
        return new ArrayList<>(ordered.values());
    }

    private Map<String, Integer> columnTypes(Connection c, String schema, String table) throws SQLException {
        Map<String, Integer> out = new LinkedHashMap<>();
        try (ResultSet rs = c.getMetaData().getColumns(null, schema, table, "%")) {
            while (rs.next()) out.put(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT), rs.getInt("DATA_TYPE"));
        }
        return out;
    }

    private static int[] sqlTypes(ResultSetMetaData md) throws SQLException {
        int[] out = new int[md.getColumnCount()];
        for (int i = 1; i <= md.getColumnCount(); i++) out[i - 1] = md.getColumnType(i);
        return out;
    }

    private static class TableLoadWriter implements AutoCloseable {
        private final String action;
        private final List<String> columns;
        private final List<String> keyColumns;
        private final List<String> nonKeyColumns;
        private final SqlDialect dialect;
        /**
         * For INSERT / REPLACE / INSERT_UPDATE (batchable dialects): the INSERT or upsert statement.
         * For INSERT_UPDATE on row-by-row MERGE dialects: the MERGE statement.
         */
        private final PreparedStatement insert;
        /** UPDATE ... WHERE pk=? statement, used for UPDATE action only. */
        private final PreparedStatement update;
        private int[] sqlTypes;
        private boolean lobColumns;
        private int pendingInserts;
        /**
         * True when the INSERT_UPDATE action uses a row-by-row MERGE (SQLSERVER, ORACLE, DB2).
         * False when it uses a batchable upsert (POSTGRES: ON CONFLICT, H2: MERGE ... KEY).
         */
        private final boolean useMergeRowByRow;

        /** Dialect-aware multi-row INSERT fast path (plain INSERT/REPLACE only). */
        private final Connection conn;
        private final String schemaName, tableName;
        private final boolean plainInsert;          // INSERT or REPLACE (not upsert/update)
        private final int multiRows;                // rows per multi-value INSERT; 1 = disabled (use single-row batch)
        private final List<Object[]> rowBuf = new ArrayList<>();
        private PreparedStatement multiInsertFull;  // cached statement sized to exactly multiRows

        TableLoadWriter(Connection out, String schema, String table, List<String> columns, int[] sqlTypes,
                        LoadOptions load, List<String> keyColumns, SqlDialect dialect) throws SQLException {
            this.action = load.action();
            this.dialect = dialect;
            this.conn = out;
            this.schemaName = schema;
            this.tableName = table;
            this.columns = List.copyOf(columns);
            this.keyColumns = List.copyOf(keyColumns);
            Set<String> keys = new LinkedHashSet<>(keyColumns.stream().map(k -> k.toLowerCase(Locale.ROOT)).toList());
            this.nonKeyColumns = columns.stream()
                    .filter(c -> !keys.contains(c.toLowerCase(Locale.ROOT)))
                    .toList();
            this.sqlTypes = sqlTypes;
            this.lobColumns = containsLobType(sqlTypes);

            if ("INSERT_UPDATE".equals(action)) {
                if (keyColumns.isEmpty()) throw ApiException.bad("Update-style load requires key columns for " + table);
                if (nonKeyColumns.isEmpty()) throw ApiException.bad("Update-style load needs at least one non-key column for " + table);
                // Determine whether this dialect uses row-by-row MERGE or a batchable upsert
                // MERGE on SQL Server / Oracle / DB2 must execute one row at a time.
                // Postgres ON CONFLICT, H2 MERGE KEY, and MySQL ON DUPLICATE KEY support batching.
                this.useMergeRowByRow = (dialect == SqlDialect.SQLSERVER
                        || dialect == SqlDialect.ORACLE
                        || dialect == SqlDialect.DB2);
                String upsertSql = upsertSql(dialect, schema, table, columns, keyColumns, nonKeyColumns);
                this.insert = out.prepareStatement(upsertSql);
                // No separate UPDATE statement for INSERT_UPDATE — handled by upsert
                this.update = null;
            } else {
                this.useMergeRowByRow = false;
                this.insert = ("INSERT".equals(action) || "REPLACE".equals(action))
                        ? out.prepareStatement(insertSql(dialect, schema, table, columns))
                        : null;
                if ("UPDATE".equals(action)) {
                    if (keyColumns.isEmpty()) throw ApiException.bad("Update-style load requires key columns for " + table);
                    if (nonKeyColumns.isEmpty()) throw ApiException.bad("Update-style load needs at least one non-key column for " + table);
                    this.update = out.prepareStatement(updateSql(schema, table, nonKeyColumns, keyColumns));
                } else {
                    this.update = null;
                }
            }
            // Multi-row INSERT fast path: only for plain INSERT/REPLACE, and only on dialects whose syntax is
            // INSERT … VALUES (…),(…). Oracle has no multi-row VALUES (it needs INSERT ALL) and keeps its
            // single-row APPEND_VALUES path; GENERIC is unknown, so it stays single-row too.
            boolean multiRowDialect = switch (dialect) {
                case POSTGRES, MYSQL, H2, SQLSERVER, DB2 -> true;
                default -> false;   // ORACLE, GENERIC
            };
            this.plainInsert = this.insert != null && ("INSERT".equals(action) || "REPLACE".equals(action));
            int cap = Math.max(1, dialect.maxRowsPerInsert());
            int want = Math.max(1, load.batchSize());
            // Keep total bind parameters safely under the driver's limit.
            int paramCeil = safeJdbcBatchRows(dialect, Math.max(1, this.columns.size()), want);
            this.multiRows = (plainInsert && multiRowDialect && !lobColumns)
                    ? Math.max(1, Math.min(Math.min(cap, want), paramCeil)) : 1;
        }

        void setSqlTypes(int[] sqlTypes) {
            this.sqlTypes = sqlTypes;
            this.lobColumns = containsLobType(sqlTypes);
        }

        void write(Object[] values) throws SQLException {
            // JDBC drivers may defer consuming stream binds until execute. Execute LOB rows immediately while the
            // source ResultSet locator/stream is still valid, and never retain a BLOB/CLOB in a row buffer.
            if (lobColumns || containsLobValue(values)) {
                flush();
                if ("UPDATE".equals(action)) {
                    bindUpdate(values);
                    update.executeUpdate();
                } else {
                    bindInsert(values);
                    insert.executeUpdate();
                }
                return;
            }
            if (plainInsert && multiRows > 1) {        // dialect-aware multi-row INSERT fast path
                rowBuf.add(values);
                if (rowBuf.size() >= multiRows) flushMulti();
                return;
            }
            if ("UPDATE".equals(action)) {
                bindUpdate(values);
                update.executeUpdate();
                return;
            }
            if ("INSERT_UPDATE".equals(action)) {
                if (useMergeRowByRow) {
                    // Row-by-row MERGE (SQL Server, Oracle, DB2): bind all columns in column-list order
                    bindInsert(values);
                    insert.executeUpdate();
                } else {
                    // Batchable upsert (Postgres ON CONFLICT, H2 MERGE ... KEY): same bind as INSERT
                    bindInsert(values);
                    insert.addBatch();
                    pendingInserts++;
                }
                return;
            }
            bindInsert(values);
            insert.addBatch();
            pendingInserts++;
        }

        void flush() throws SQLException {
            if (plainInsert && multiRows > 1) {
                if (!rowBuf.isEmpty()) flushMulti();
                return;
            }
            if (pendingInserts > 0 && insert != null) {
                insert.executeBatch();
                pendingInserts = 0;
            }
        }

        /** Emit the buffered rows as one multi-value INSERT (full-size batch reuses a cached statement). */
        private void flushMulti() throws SQLException {
            int n = rowBuf.size();
            if (n == 0) return;
            if (n == multiRows) {
                if (multiInsertFull == null) multiInsertFull = conn.prepareStatement(multiInsertSql(multiRows));
                bindMulti(multiInsertFull, rowBuf);
                multiInsertFull.executeUpdate();
            } else {
                try (PreparedStatement ps = conn.prepareStatement(multiInsertSql(n))) {
                    bindMulti(ps, rowBuf);
                    ps.executeUpdate();
                }
            }
            rowBuf.clear();
        }

        private String multiInsertSql(int n) {
            String cols = String.join(",", columns.stream().map(ProvisioningService::q).toList());
            String oneTuple = "(" + String.join(",", Collections.nCopies(columns.size(), "?")) + ")";
            String tuples = String.join(",", Collections.nCopies(n, oneTuple));
            return "INSERT INTO " + q(schemaName, tableName) + " (" + cols + ") VALUES " + tuples;
        }

        private void bindMulti(PreparedStatement ps, List<Object[]> rows) throws SQLException {
            int idx = 1;
            for (Object[] row : rows)
                for (int i = 0; i < columns.size(); i++) bind(ps, idx++, row[i], typeAt(i));
        }

        @Override
        public void close() throws SQLException {
            try {
                flush();
            } finally {
                if (multiInsertFull != null) multiInsertFull.close();
                if (insert != null) insert.close();
                if (update != null) update.close();
            }
        }

        private void bindInsert(Object[] values) throws SQLException {
            for (int i = 0; i < columns.size(); i++) bind(insert, i + 1, values[i], typeAt(i));
        }

        private void bindUpdate(Object[] values) throws SQLException {
            int idx = 1;
            for (String col : nonKeyColumns) {
                int pos = columnIndex(col);
                bind(update, idx++, values[pos], typeAt(pos));
            }
            for (String col : keyColumns) {
                int pos = columnIndex(col);
                bind(update, idx++, values[pos], typeAt(pos));
            }
        }

        private int columnIndex(String column) {
            for (int i = 0; i < columns.size(); i++)
                if (columns.get(i).equalsIgnoreCase(column)) return i;
            throw ApiException.bad("Column " + column + " not found in load writer");
        }

        private int typeAt(int index) {
            return sqlTypes == null || index >= sqlTypes.length ? Types.VARCHAR : sqlTypes[index];
        }

        private static void bind(PreparedStatement ps, int index, Object value, int sqlType) throws SQLException {
            bindJdbcValue(ps, index, value, sqlType);
        }

        /**
         * Build the INSERT statement for INSERT / REPLACE actions.
         * Oracle uses the direct-path hint for better bulk-load performance.
         */
        private static String insertSql(SqlDialect dialect, String schema, String table, List<String> columns) {
            String hint = (dialect == SqlDialect.ORACLE) ? "/*+ APPEND_VALUES */ " : "";
            return "INSERT " + hint + "INTO " + q(schema, table)
                    + " (" + String.join(",", columns.stream().map(ProvisioningService::q).toList()) + ") VALUES ("
                    + String.join(",", Collections.nCopies(columns.size(), "?")) + ")";
        }

        private static String updateSql(String schema, String table, List<String> nonKeyColumns, List<String> keyColumns) {
            String sets = String.join(",", nonKeyColumns.stream().map(c -> q(c) + "=?").toList());
            String where = String.join(" AND ", keyColumns.stream().map(c -> q(c) + "=?").toList());
            return "UPDATE " + q(schema, table) + " SET " + sets + " WHERE " + where;
        }

        /**
         * Build the upsert / MERGE statement for INSERT_UPDATE action.
         * Bind order for all dialects: all columns in the table column-list order.
         */
        private static String upsertSql(SqlDialect dialect, String schema, String table,
                                        List<String> columns, List<String> keyColumns, List<String> nonKeyColumns) {
            switch (dialect) {
                case POSTGRES: return pgUpsertSql(schema, table, columns, keyColumns, nonKeyColumns);
                case H2:       return h2MergeSql(schema, table, columns, keyColumns);
                case MYSQL:    return mysqlUpsertSql(schema, table, columns, nonKeyColumns);
                case SQLSERVER: return sqlServerMergeSql(schema, table, columns, keyColumns, nonKeyColumns);
                case ORACLE:   return oracleMergeSql(schema, table, columns, keyColumns, nonKeyColumns);
                case DB2:      return db2MergeSql(schema, table, columns, keyColumns, nonKeyColumns);
                default:
                    // Generic fallback: plain INSERT; useMergeRowByRow=false so this goes through addBatch()
                    return "INSERT INTO " + q(schema, table)
                            + " (" + String.join(",", columns.stream().map(ProvisioningService::q).toList()) + ") VALUES ("
                            + String.join(",", Collections.nCopies(columns.size(), "?")) + ")";
            }
        }

        /**
         * PostgreSQL: INSERT ... ON CONFLICT (pk) DO UPDATE SET non_key=EXCLUDED.non_key, ...
         * Bind order: all columns in column list order. Batchable.
         */
        private static String pgUpsertSql(String schema, String table,
                                           List<String> columns, List<String> keyColumns, List<String> nonKeyColumns) {
            String colList = String.join(",", columns.stream().map(ProvisioningService::q).toList());
            String params  = String.join(",", Collections.nCopies(columns.size(), "?"));
            String conflict = String.join(",", keyColumns.stream().map(ProvisioningService::q).toList());
            String updates  = String.join(",", nonKeyColumns.stream()
                    .map(c -> q(c) + "=EXCLUDED." + q(c)).toList());
            return "INSERT INTO " + q(schema, table)
                    + " (" + colList + ") VALUES (" + params + ")"
                    + " ON CONFLICT (" + conflict + ") DO UPDATE SET " + updates;
        }

        /**
         * H2: MERGE INTO t ("pk","col1","col2") KEY ("pk") VALUES (?,?,?)
         * Column order: key columns first, then non-key. Batchable.
         */
        private static String h2MergeSql(String schema, String table,
                                          List<String> columns, List<String> keyColumns) {
            // H2 MERGE expects all columns; we preserve original order
            String colList = String.join(",", columns.stream().map(ProvisioningService::q).toList());
            String params  = String.join(",", Collections.nCopies(columns.size(), "?"));
            String keyList = String.join(",", keyColumns.stream().map(ProvisioningService::q).toList());
            return "MERGE INTO " + q(schema, table)
                    + " (" + colList + ") KEY (" + keyList + ") VALUES (" + params + ")";
        }

        /**
         * MySQL/MariaDB: INSERT INTO t (col1,col2,pk) VALUES (?,?,?) ON DUPLICATE KEY UPDATE col1=VALUES(col1), ...
         * Bind order: all columns in column list order. Batchable.
         */
        private static String mysqlUpsertSql(String schema, String table,
                                              List<String> columns, List<String> nonKeyColumns) {
            String colList = String.join(",", columns.stream().map(c -> q(c, SqlDialect.MYSQL)).toList());
            String params  = String.join(",", Collections.nCopies(columns.size(), "?"));
            String updates  = String.join(",", nonKeyColumns.stream()
                    .map(c -> q(c, SqlDialect.MYSQL) + "=VALUES(" + q(c, SqlDialect.MYSQL) + ")").toList());
            return "INSERT INTO " + q(schema, table, SqlDialect.MYSQL)
                    + " (" + colList + ") VALUES (" + params + ")"
                    + " ON DUPLICATE KEY UPDATE " + updates;
        }

        /**
         * SQL Server: MERGE ... USING (SELECT ? AS col, ...) AS src ON ... WHEN MATCHED ... WHEN NOT MATCHED ...
         * Bind order: all columns in column list order. Row-by-row executeUpdate().
         */
        private static String sqlServerMergeSql(String schema, String table,
                                                  List<String> columns, List<String> keyColumns, List<String> nonKeyColumns) {
            StringBuilder src = new StringBuilder("SELECT ");
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) src.append(", ");
                src.append("? AS ").append(q(columns.get(i)));
            }
            String on = String.join(" AND ", keyColumns.stream()
                    .map(k -> "tgt." + q(k) + "=src." + q(k)).toList());
            String updateSet = String.join(",", nonKeyColumns.stream()
                    .map(c -> "tgt." + q(c) + "=src." + q(c)).toList());
            String insertCols = String.join(",", columns.stream().map(ProvisioningService::q).toList());
            String insertVals = String.join(",", columns.stream().map(c -> "src." + q(c)).toList());
            return "MERGE INTO " + q(schema, table) + " AS tgt"
                    + " USING (" + src + ") AS src"
                    + " ON (" + on + ")"
                    + " WHEN MATCHED THEN UPDATE SET " + updateSet
                    + " WHEN NOT MATCHED THEN INSERT (" + insertCols + ") VALUES (" + insertVals + ");";
        }

        /**
         * Oracle: MERGE INTO t tgt USING (SELECT ? col1, ? col2 FROM DUAL) src ON (tgt.pk=src.pk)
         * Bind order: all columns in column list order. Row-by-row executeUpdate().
         */
        private static String oracleMergeSql(String schema, String table,
                                               List<String> columns, List<String> keyColumns, List<String> nonKeyColumns) {
            StringBuilder src = new StringBuilder("SELECT ");
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) src.append(", ");
                src.append("? ").append(q(columns.get(i)));
            }
            src.append(" FROM DUAL");
            String on = String.join(" AND ", keyColumns.stream()
                    .map(k -> "tgt." + q(k) + "=src." + q(k)).toList());
            String updateSet = String.join(",", nonKeyColumns.stream()
                    .map(c -> "tgt." + q(c) + "=src." + q(c)).toList());
            String insertCols = String.join(",", columns.stream().map(ProvisioningService::q).toList());
            String insertVals = String.join(",", columns.stream().map(c -> "src." + q(c)).toList());
            return "MERGE INTO " + q(schema, table) + " tgt"
                    + " USING (" + src + ") src"
                    + " ON (" + on + ")"
                    + " WHEN MATCHED THEN UPDATE SET " + updateSet
                    + " WHEN NOT MATCHED THEN INSERT (" + insertCols + ") VALUES (" + insertVals + ")";
        }

        /**
         * DB2: MERGE INTO t AS tgt USING (VALUES (?,?,...)) AS src (col1,col2,...) ON (tgt.pk=src.pk)
         * Bind order: all columns in column list order. Row-by-row executeUpdate().
         */
        private static String db2MergeSql(String schema, String table,
                                           List<String> columns, List<String> keyColumns, List<String> nonKeyColumns) {
            String params  = String.join(",", Collections.nCopies(columns.size(), "?"));
            String srcCols = String.join(",", columns.stream().map(ProvisioningService::q).toList());
            String on = String.join(" AND ", keyColumns.stream()
                    .map(k -> "tgt." + q(k) + "=src." + q(k)).toList());
            String updateSet = String.join(",", nonKeyColumns.stream()
                    .map(c -> "tgt." + q(c) + "=src." + q(c)).toList());
            String insertCols = String.join(",", columns.stream().map(ProvisioningService::q).toList());
            String insertVals = String.join(",", columns.stream().map(c -> "src." + q(c)).toList());
            return "MERGE INTO " + q(schema, table) + " AS tgt"
                    + " USING (VALUES (" + params + ")) AS src (" + srcCols + ")"
                    + " ON (" + on + ")"
                    + " WHEN MATCHED THEN UPDATE SET " + updateSet
                    + " WHEN NOT MATCHED THEN INSERT (" + insertCols + ") VALUES (" + insertVals + ")";
        }
    }

    /**
     * Referential-integrity salt strategy:
     *  - Identity-bearing functions (names, email, SSN, CCN, phone, geo, address) use a CANONICAL
     *    global salt, so the same source value masks identically in EVERY table and database
     *    (cross-table masking consistency, with zero shared state — pure keyed HMAC).
     *  - All other functions are column-scoped, so e.g. two unrelated numeric codes never
     *    accidentally collide into the same masked space.
     */
    /** One key relationship edge (parent key column ↔ child key column), lower-cased. */
    private record KeyEdge(String parentTable, String parentColumn, String childTable, String childColumn) {}

    /**
     * Collect key relationship edges among the run's tables: DB-catalog FKs PLUS custom soft-FK relationships
     * (DataScope user relationships + RI registry edges, passed in as {@code softEdges}). Composite edges are
     * expanded per aligned column pair upstream, so each UserRelEdge is already a single column pair.
     */
    private List<KeyEdge> collectKeyEdges(Connection in, String schema, java.util.Collection<String> tables,
                                          java.util.Collection<SubsetService.UserRelEdge> softEdges) {
        Set<String> inRun = new HashSet<>();
        for (String t : tables) if (t != null) inRun.add(t.toLowerCase(Locale.ROOT));
        List<KeyEdge> edges = new ArrayList<>();
        for (String t : tables) {
            if (t == null) continue;
            try (ResultSet rs = in.getMetaData().getImportedKeys(null, schema, t)) {
                while (rs.next()) {
                    String pt = rs.getString("PKTABLE_NAME"), pc = rs.getString("PKCOLUMN_NAME");
                    String ct = rs.getString("FKTABLE_NAME"), cc = rs.getString("FKCOLUMN_NAME");
                    if (pt == null || pc == null || ct == null || cc == null) continue;
                    if (!inRun.contains(pt.toLowerCase(Locale.ROOT)) || !inRun.contains(ct.toLowerCase(Locale.ROOT))) continue;
                    edges.add(new KeyEdge(pt.toLowerCase(Locale.ROOT), pc.toLowerCase(Locale.ROOT),
                            ct.toLowerCase(Locale.ROOT), cc.toLowerCase(Locale.ROOT)));
                }
            } catch (SQLException ignore) { /* table may be unreadable; skip */ }
        }
        if (softEdges != null) for (SubsetService.UserRelEdge ue : softEdges) {
            if (ue.parentTable() == null || ue.parentColumn() == null || ue.childTable() == null || ue.childColumn() == null) continue;
            String pt = ue.parentTable().toLowerCase(Locale.ROOT), ct = ue.childTable().toLowerCase(Locale.ROOT);
            if (!inRun.contains(pt) || !inRun.contains(ct)) continue;
            edges.add(new KeyEdge(pt, ue.parentColumn().toLowerCase(Locale.ROOT), ct, ue.childColumn().toLowerCase(Locale.ROOT)));
        }
        return edges;
    }

    /**
     * Group FK-related key columns so they share one masking salt. For every key edge (parent ↔ child), the two
     * columns are unioned; each column then maps to a stable shared salt ("ri:&lt;root&gt;"). Masking both sides
     * with the same function therefore yields the same masked value for the same input → joins survive.
     */
    private Map<String, String> buildKeyConsistencySalts(List<KeyEdge> edges) {
        Map<String, String> uf = new HashMap<>();
        for (KeyEdge e : edges)
            ufUnion(uf, e.parentTable() + "." + e.parentColumn(), e.childTable() + "." + e.childColumn());
        Map<String, String> salt = new HashMap<>();
        for (String node : new ArrayList<>(uf.keySet())) salt.put(node, "ri:" + ufFind(uf, node));
        return salt;
    }

    /**
     * Warn when the two sides of a key relationship are masked inconsistently — one side masked and the other
     * not, or both masked with different functions. Either case silently breaks joins, and it can't be auto-
     * reconciled (the engine can't know which side is authoritative), so it's surfaced rather than fixed.
     */
    private List<String> keyMaskWarnings(List<KeyEdge> edges,
            Map<String, List<MaskingRuleEntity>> ruleMap,
            Map<Long, Map<String, List<MaskingRuleEntity>>> profileRuleCache,
            Map<String, TableProfileEntity> profileByTable,
            Map<String, Map<String, ColumnOverrideEntity>> colOverrideMap,
            String schema) {
        List<String> warns = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (KeyEdge e : edges) {
            String pair = e.parentTable() + "." + e.parentColumn() + " <-> " + e.childTable() + "." + e.childColumn();
            if (!seen.add(pair)) continue;
            String ps = maskSignature(e.parentTable(), e.parentColumn(), ruleMap, profileRuleCache, profileByTable, colOverrideMap, schema);
            String cs = maskSignature(e.childTable(), e.childColumn(), ruleMap, profileRuleCache, profileByTable, colOverrideMap, schema);
            boolean pm = ps != null, cm = cs != null;
            if (pm != cm)
                warns.add("Only one side of " + pair + " is masked (" + (pm ? ps : cs)
                        + ") — joins may break. Mask both sides with the same function, or neither.");
            else if (pm && !ps.equalsIgnoreCase(cs))
                warns.add("Sides of " + pair + " use different masking (" + ps + " vs " + cs
                        + ") — joins may break. Use the same function on both.");
        }
        return warns;
    }

    /** Effective masking "signature" for a column: override type, policy function, or null (unmasked). */
    private String maskSignature(String table, String col,
            Map<String, List<MaskingRuleEntity>> ruleMap,
            Map<Long, Map<String, List<MaskingRuleEntity>>> profileRuleCache,
            Map<String, TableProfileEntity> profileByTable,
            Map<String, Map<String, ColumnOverrideEntity>> colOverrideMap,
            String schema) {
        String tl = table.toLowerCase(Locale.ROOT);
        ColumnOverrideEntity ov = colOverrideMap.getOrDefault(tl, Map.of()).get(col.toLowerCase(Locale.ROOT));
        if (ov != null) {
            String ot = ov.getOverrideType();
            if ("SUPPRESS".equals(ot)) return "SUPPRESS";
            if ("LITERAL".equals(ot)) return "LITERAL";
            if ("NULL_OUT".equals(ot)) return "NULL_OUT";
            // USE_POLICY → fall through to the policy rule below
        }
        List<MaskingRuleEntity> rules = effectiveTableRules(ruleMap, profileRuleCache, profileByTable.get(tl), schema, table);
        if (rules != null) for (MaskingRuleEntity r : rules)
            if (r.getColumnName() != null && r.getColumnName().equalsIgnoreCase(col)) return r.getFunction();
        return null;
    }

    private static String asStr(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static String ufFind(Map<String, String> uf, String x) {
        uf.putIfAbsent(x, x);
        String r = x;
        while (!r.equals(uf.get(r))) r = uf.get(r);
        String cur = x;
        while (!cur.equals(r)) { String nxt = uf.get(cur); uf.put(cur, r); cur = nxt; }
        return r;
    }

    private static void ufUnion(Map<String, String> uf, String a, String b) {
        String ra = ufFind(uf, a), rb = ufFind(uf, b);
        if (!ra.equals(rb)) uf.put(ra, rb);
    }

    static String saltFor(MaskingRuleEntity rule, String table, String col, Map<String, String> keySalt) {
        return switch (rule.getFunction()) {
            // Semantic functions already use a fixed logical salt → identical output for the same value
            // anywhere, so a PK and its FKs stay consistent automatically.
            case "FIRST_NAME" -> "name.first";
            case "LAST_NAME" -> "name.last";
            case "FULL_NAME" -> "name.full";
            case "EMAIL" -> "email";
            case "SSN" -> "ssn";
            case "CREDIT_CARD" -> "ccn";
            case "PHONE" -> "phone";
            case "CITY_STATE_ZIP" -> "geo";
            case "ADDRESS_STREET" -> "addr";
            case "ADDRESS_US" -> "addr.us";
            case "COMPANY" -> "company";
            case "DOB_AGE_BAND" -> "dob";
            case "BANK_ACCOUNT" -> "bank.account";
            case "IBAN" -> "iban";
            case "SWIFT_BIC" -> "swift.bic";
            case "ABA_ROUTING" -> "routing.aba";
            case "NATIONAL_ID" -> "national.id";
            case "IP_ADDRESS" -> "network.ip";
            case "MAC_ADDRESS" -> "network.mac";
            case "DIRECT_LOOKUP" -> "lookup.direct";
            case "HASH_LOOKUP" -> "lookup.hash";
            // Everything else is table.column-salted EXCEPT FK-related key columns, which share one salt so a
            // PK and the FKs referencing it mask to the same value (referential integrity preserved).
            default -> {
                String tc = table.toLowerCase(Locale.ROOT) + "." + col.toLowerCase(Locale.ROOT);
                yield (keySalt != null && keySalt.containsKey(tc)) ? keySalt.get(tc) : tc;
            }
        };
    }

    private static Object coerce(String value, int sqlType) {
        try {
            return switch (sqlType) {
                case Types.INTEGER, Types.SMALLINT, Types.TINYINT -> Integer.parseInt(value.trim());
                case Types.BIGINT -> Long.parseLong(value.trim());
                case Types.NUMERIC, Types.DECIMAL, Types.DOUBLE, Types.FLOAT, Types.REAL -> new java.math.BigDecimal(value.trim());
                case Types.DATE -> java.sql.Date.valueOf(value.trim());
                case Types.BOOLEAN, Types.BIT -> Boolean.parseBoolean(value.trim());
                default -> value;
            };
        } catch (Exception e) { return value; }
    }

    private JsonNode spec(ProvisionJobEntity job) throws Exception {
        return json.readTree(job.getSpecJson() == null || job.getSpecJson().isBlank() ? "{}" : job.getSpecJson());
    }

    private static String textOrNull(JsonNode spec, String field) {
        if (spec == null || !spec.has(field) || spec.get(field).isNull()) return null;
        String s = spec.get(field).asText();
        return s == null || s.isBlank() ? null : s;
    }

    /** Find the per-table criterion for a given table name (case-insensitive), or null if absent. */
    private static SubsetService.TableCriterion inPlaceTableCriteria(JsonNode spec, String table) {
        for (SubsetService.TableCriterion c : tableCriteria(spec))
            if (table.equalsIgnoreCase(c.table())) return c;
        return null;
    }

    private static List<SubsetService.TableCriterion> tableCriteria(JsonNode spec) {
        if (spec == null || !spec.has("tableCriteria") || !spec.get("tableCriteria").isArray()) return List.of();
        List<SubsetService.TableCriterion> out = new ArrayList<>();
        for (JsonNode row : spec.get("tableCriteria")) {
            String table = row.path("table").asText(null);
            if (table == null || table.isBlank()) continue;
            String filter = row.path("filter").asText(null);
            Integer limit = row.has("rowLimit") && !row.get("rowLimit").isNull() ? row.get("rowLimit").asInt() : null;
            out.add(new SubsetService.TableCriterion(table, filter, limit));
        }
        return out;
    }

    private Map<String, List<MaskingRuleEntity>> rulesByTable(Long policyId) {
        Map<String, List<MaskingRuleEntity>> map = new HashMap<>();
        if (policyId == null) return map;
        for (MaskingRuleEntity r : rules.findByPolicyId(policyId))
            map.computeIfAbsent(ruleKey(r.getSchemaName(), r.getTableName()), k -> new ArrayList<>()).add(r);
        return map;
    }

    private static List<MaskingRuleEntity> rulesFor(Map<String, List<MaskingRuleEntity>> ruleMap, String schema, String table) {
        if (table == null) return null;
        String t = table.toLowerCase();
        List<MaskingRuleEntity> scoped = ruleMap.get(ruleKey(schema, table));   // exact schema.table
        if (scoped != null) return scoped;
        scoped = ruleMap.get(t);                                                // bare table (schemaless rules)
        if (scoped != null) return scoped;
        // Last resort: a policy authored for this table under a DIFFERENT schema qualifier than the one being
        // provisioned. Match by table name so a set policy still applies instead of silently masking nothing.
        for (Map.Entry<String, List<MaskingRuleEntity>> e : ruleMap.entrySet()) {
            String k = e.getKey();
            if (k.equals(t) || k.endsWith("." + t)) return e.getValue();
        }
        return null;
    }

    private static String ruleKey(String schema, String table) {
        String t = table == null ? "" : table.toLowerCase();
        return schema == null || schema.isBlank() ? t : schema.toLowerCase() + "." + t;
    }

    private List<String> allTables(Connection c) throws SQLException {
        return allTables(c, DataSourceService.schemaOf(c));
    }

    private List<String> allTables(Connection c, String schema) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = c.getMetaData().getTables(null, schema, "%", new String[]{"TABLE"})) {
            while (rs.next()) out.add(rs.getString("TABLE_NAME"));
        }
        return out;
    }

    private List<String> columnsOf(Connection c, String table) throws SQLException {
        return columnsOf(c, DataSourceService.schemaOf(c), table);
    }

    private List<String> columnsOf(Connection c, String schema, String table) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = c.getMetaData().getColumns(null, schema, table, "%")) {
            while (rs.next()) out.add(rs.getString("COLUMN_NAME"));
        }
        return out;
    }

    private List<Map<String, Object>> sampleRows(Connection c, String schema, String table,
                                                 List<String> columns, int limit) throws SQLException {
        if (columns == null || columns.isEmpty()) return List.of();
        String sql = "SELECT " + String.join(",", columns.stream().map(ProvisioningService::q).toList())
                + " FROM " + q(schema, table);
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Statement st = c.createStatement()) {
            st.setMaxRows(limit);
            try (ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (String col : columns) row.put(col, rs.getObject(col));
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private void truncate(Connection out, String table) {
        truncate(out, DataSourceService.schemaOf(out), table);
    }

    private void truncate(Connection out, String schema, String table) {
        try (Statement st = out.createStatement()) { st.executeUpdate("DELETE FROM " + q(schema, table)); }
        catch (SQLException e) { /* table may not exist yet in target; surfaces on insert with a clear error */ }
    }

    private void progress(ProvisionJobEntity job, long rows) {
        synchronized (job) { job.setRowsProcessed(rows); jobs.save(job); }
    }

    private void progressMessage(ProvisionJobEntity job, String message) {
        synchronized (job) { job.setMessage(message); jobs.save(job); }
    }

    private void checkCancelled(ProvisionJobEntity job) {
        if (Thread.currentThread().isInterrupted() || isCancelRequested(job)) throw new CancellationException("Canceled");
    }

    private boolean isCancelRequested(ProvisionJobEntity job) {
        String status = jobs.findById(job.getId()).map(ProvisionJobEntity::getStatus).orElse(job.getStatus());
        return "CANCEL_REQUESTED".equals(status) || "CANCELED".equals(status);
    }

    private static <T> List<List<T>> chunks(List<T> list, int size) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) out.add(list.subList(i, Math.min(i + size, list.size())));
        return out;
    }

    private static <T> List<List<T>> nullableChunk() {
        List<List<T>> out = new ArrayList<>();
        out.add(null);
        return out;
    }

    private static Long req(Long v, String name) {
        if (v == null) throw ApiException.bad(name + " is required");
        return v;
    }

    /**
     * Quote an identifier. Uses ANSI double-quote syntax for all databases except MySQL/MariaDB,
     * which use backtick quoting. SQL Server accepts double-quotes when QUOTED_IDENTIFIER is ON
     * (the default). Accepts $, # in addition to letters, digits and underscore (Oracle sequences,
     * some DB2 object names).
     * <p>
     * The dialect-aware overload should be used when a connection is available; the single-arg
     * form defaults to ANSI quoting and is used in static contexts (e.g., SQL templates built
     * before the connection is open).
     */
    static String q(String ident) {
        return q(ident, SqlDialect.GENERIC);
    }

    static String q(String ident, SqlDialect dialect) {
        if (!ident.matches("[A-Za-z0-9_$#]+")) throw ApiException.bad("Illegal identifier: " + ident);
        return (dialect == SqlDialect.MYSQL) ? "`" + ident + "`" : "\"" + ident + "\"";
    }

    static String q(String schema, String table) {
        return schema == null || schema.isBlank() ? q(table) : q(schema) + "." + q(table);
    }

    static String q(String schema, String table, SqlDialect dialect) {
        return schema == null || schema.isBlank() ? q(table, dialect) : q(schema, dialect) + "." + q(table, dialect);
    }
}
