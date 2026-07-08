package io.forgetdm.discovery;

import io.forgetdm.common.ApiException;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessPrincipal;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;

@Service
public class DiscoveryJobService {
    private static final int MAX_JOBS = 80;

    private final DiscoveryService discovery;
    private final ExecutorService executor;
    private final ConcurrentMap<String, DiscoveryJob> jobs = new ConcurrentHashMap<>();

    public DiscoveryJobService(DiscoveryService discovery, ExecutorService provisioningExecutor) {
        this.discovery = discovery;
        this.executor = provisioningExecutor;
    }

    public JobSnapshot start(Long dataSourceId, String schemaName, Set<String> selectedTypes) {
        if (dataSourceId == null) throw ApiException.bad("dataSourceId is required");
        AccessPrincipal caller = AccessContext.current().orElse(null);
        String token = AccessContext.currentToken().orElse(null);
        String owner = caller == null ? "system" : caller.username();
        DiscoveryJob job = new DiscoveryJob(
                "disc-" + UUID.randomUUID().toString().substring(0, 8),
                dataSourceId,
                blankNull(schemaName),
                selectedTypes == null ? Set.of() : Set.copyOf(selectedTypes),
                owner,
                Instant.now());
        jobs.put(job.jobId, job);
        trimFinishedJobs();

        executor.submit(() -> AccessContext.callAs(caller, token, () -> {
            runJob(job);
            return null;
        }));
        return job.snapshot();
    }

    public JobSnapshot get(String jobId) {
        DiscoveryJob job = jobs.get(jobId);
        if (job == null || !canSee(job)) throw ApiException.notFound("Discovery job not found: " + jobId);
        return job.snapshot();
    }

    public List<JobSnapshot> list(Long dataSourceId, String schemaName) {
        String schema = blankNull(schemaName);
        return jobs.values().stream()
                .filter(this::canSee)
                .filter(j -> dataSourceId == null || dataSourceId.equals(j.dataSourceId))
                .filter(j -> schema == null || eq(schema, j.requestedSchemaName) || eq(schema, j.schemaName))
                .sorted(Comparator.comparing((DiscoveryJob j) -> j.startedAt).reversed())
                .limit(50)
                .map(DiscoveryJob::snapshot)
                .toList();
    }

    private void runJob(DiscoveryJob job) {
        job.running("Opening source metadata");
        try {
            List<ClassificationEntity> result = discovery.scan(job.dataSourceId, job.requestedSchemaName,
                    job.selectedTypes.isEmpty() ? null : job.selectedTypes, job.progress());
            job.completed(result.size());
        } catch (Exception e) {
            job.failed(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private boolean canSee(DiscoveryJob job) {
        return AccessContext.current()
                .map(p -> p.hasPermission("admin.all") || p.username().equals(job.ownerUsername))
                .orElse("system".equals(job.ownerUsername));
    }

    private void trimFinishedJobs() {
        if (jobs.size() <= MAX_JOBS) return;
        List<DiscoveryJob> finished = jobs.values().stream()
                .filter(j -> !"RUNNING".equals(j.status) && !"PENDING".equals(j.status))
                .sorted(Comparator.comparing((DiscoveryJob j) -> j.startedAt))
                .toList();
        int remove = jobs.size() - MAX_JOBS;
        for (int i = 0; i < remove && i < finished.size(); i++) {
            jobs.remove(finished.get(i).jobId);
        }
    }

    private static boolean eq(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    private static String blankNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public record JobSnapshot(String jobId, Long dataSourceId, String requestedSchemaName, String schemaName,
                              Set<String> selectedTypes, String ownerUsername, String status, Instant startedAt,
                              Instant finishedAt, int totalTables, int completedTables, String currentTable,
                              String currentColumn, int findings, int percent, String message, String error,
                              List<TableSnapshot> tables) {}

    public record TableSnapshot(String tableName, String status, int percent, int scannedColumns,
                                int totalColumns, int findings, String currentColumn, Instant startedAt,
                                Instant finishedAt) {}

    private static final class DiscoveryJob {
        private final String jobId;
        private final Long dataSourceId;
        private final String requestedSchemaName;
        private final Set<String> selectedTypes;
        private final String ownerUsername;
        private final Instant startedAt;
        private final Map<String, TableState> tables = new LinkedHashMap<>();

        private String schemaName;
        private String status = "PENDING";
        private Instant finishedAt;
        private int completedTables;
        private String currentTable;
        private String currentColumn;
        private int findings;
        private String message = "Queued";
        private String error;

        private DiscoveryJob(String jobId, Long dataSourceId, String requestedSchemaName, Set<String> selectedTypes,
                             String ownerUsername, Instant startedAt) {
            this.jobId = jobId;
            this.dataSourceId = dataSourceId;
            this.requestedSchemaName = requestedSchemaName;
            this.selectedTypes = selectedTypes;
            this.ownerUsername = ownerUsername;
            this.startedAt = startedAt;
        }

        synchronized void running(String message) {
            this.status = "RUNNING";
            this.message = message;
        }

        DiscoveryService.ScanProgress progress() {
            return new DiscoveryService.ScanProgress() {
                @Override public void schemaResolved(String resolvedSchema) {
                    synchronized (DiscoveryJob.this) {
                        schemaName = resolvedSchema;
                        message = "Schema resolved: " + safe(resolvedSchema);
                    }
                }

                @Override public void tablesDiscovered(List<String> tableNames) {
                    synchronized (DiscoveryJob.this) {
                        tables.clear();
                        for (String table : tableNames) tables.put(table, new TableState(table));
                        message = tableNames.isEmpty()
                                ? "No tables found for discovery"
                                : "Discovered " + tableNames.size() + " table(s)";
                    }
                }

                @Override public void tableStarted(String tableName, int tableIndex, int totalTables) {
                    synchronized (DiscoveryJob.this) {
                        TableState t = table(tableName);
                        t.status = "RUNNING";
                        t.startedAt = t.startedAt == null ? Instant.now() : t.startedAt;
                        currentTable = tableName;
                        currentColumn = null;
                        message = "Scanning table " + tableIndex + " of " + totalTables + ": " + tableName;
                    }
                }

                @Override public void tableColumns(String tableName, int totalColumns) {
                    synchronized (DiscoveryJob.this) {
                        TableState t = table(tableName);
                        t.totalColumns = totalColumns;
                        if (totalColumns == 0) t.percent = 100;
                    }
                }

                @Override public void columnScanned(String tableName, String columnName, int scannedColumns, int totalColumns) {
                    synchronized (DiscoveryJob.this) {
                        TableState t = table(tableName);
                        t.scannedColumns = scannedColumns;
                        t.totalColumns = totalColumns;
                        t.currentColumn = columnName;
                        t.percent = totalColumns <= 0 ? 100 : Math.min(100, (int) Math.floor(scannedColumns * 100.0 / totalColumns));
                        currentTable = tableName;
                        currentColumn = columnName;
                        message = "Scanning " + tableName + "." + columnName;
                    }
                }

                @Override public void findingDiscovered(String tableName, String columnName, String piiType) {
                    synchronized (DiscoveryJob.this) {
                        TableState t = table(tableName);
                        t.findings++;
                        findings++;
                        message = "Found " + piiType + " on " + tableName + "." + columnName;
                    }
                }

                @Override public void tableCompleted(String tableName, int findingsForTable) {
                    synchronized (DiscoveryJob.this) {
                        TableState t = table(tableName);
                        t.status = "COMPLETED";
                        t.percent = 100;
                        t.currentColumn = null;
                        t.finishedAt = Instant.now();
                        completedTables = (int) tables.values().stream().filter(x -> "COMPLETED".equals(x.status)).count();
                        currentColumn = null;
                        message = "Completed " + tableName + " (" + findingsForTable + " finding"
                                + (findingsForTable == 1 ? "" : "s") + ")";
                    }
                }
            };
        }

        synchronized void completed(int resultCount) {
            status = "COMPLETED";
            finishedAt = Instant.now();
            findings = resultCount;
            completedTables = tables.size();
            currentTable = null;
            currentColumn = null;
            for (TableState t : tables.values()) {
                if (!"COMPLETED".equals(t.status)) {
                    t.status = "COMPLETED";
                    t.percent = 100;
                    t.finishedAt = finishedAt;
                }
            }
            message = "Discovery complete: " + resultCount + " finding" + (resultCount == 1 ? "" : "s");
        }

        synchronized void failed(String message) {
            status = "FAILED";
            finishedAt = Instant.now();
            error = message;
            this.message = "Discovery failed";
            if (currentTable != null) {
                TableState t = table(currentTable);
                t.status = "FAILED";
                t.finishedAt = finishedAt;
            }
        }

        synchronized JobSnapshot snapshot() {
            List<TableSnapshot> tableSnapshots = new ArrayList<>();
            for (TableState t : tables.values()) tableSnapshots.add(t.snapshot());
            return new JobSnapshot(jobId, dataSourceId, requestedSchemaName, schemaName, selectedTypes, ownerUsername,
                    status, startedAt, finishedAt, tables.size(), completedTables, currentTable, currentColumn,
                    findings, percent(), message, error, tableSnapshots);
        }

        private int percent() {
            if ("COMPLETED".equals(status)) return 100;
            if (tables.isEmpty()) return "RUNNING".equals(status) ? 2 : 0;
            int sum = 0;
            for (TableState t : tables.values()) sum += t.percent;
            return Math.max(1, Math.min(99, (int) Math.floor(sum / (double) tables.size())));
        }

        private TableState table(String tableName) {
            return tables.computeIfAbsent(tableName, TableState::new);
        }

        private static String safe(String value) {
            return value == null || value.isBlank() ? "(default)" : value;
        }
    }

    private static final class TableState {
        private final String tableName;
        private String status = "PENDING";
        private int percent;
        private int scannedColumns;
        private int totalColumns;
        private int findings;
        private String currentColumn;
        private Instant startedAt;
        private Instant finishedAt;

        private TableState(String tableName) {
            this.tableName = tableName;
        }

        private TableSnapshot snapshot() {
            return new TableSnapshot(tableName, status, percent, scannedColumns, totalColumns, findings,
                    currentColumn, startedAt, finishedAt);
        }
    }
}
