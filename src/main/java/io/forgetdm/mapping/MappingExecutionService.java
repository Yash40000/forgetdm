package io.forgetdm.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.core.mask.MaskContext;
import io.forgetdm.core.mask.MaskFunction;
import io.forgetdm.core.mask.MaskingEngine;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.datasource.SqlDialect;
import io.forgetdm.filevault.ManagedFileVault;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessPrincipal;
import io.forgetdm.security.OwnershipGuard;
import io.forgetdm.subset.SubsetService;
import jakarta.annotation.PreDestroy;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class MappingExecutionService {
    private final MappingRunRepository runs;
    private final MappingService mappings;
    private final MappingVersionRepository versions;
    private final MappingFileService files;
    private final DataSourceService dataSources;
    private final ConnectionFactory connections;
    private final ManagedFileVault vault;
    private final MaskingEngine masking;
    private final ObjectMapper json;
    private final AuditService audit;
    private final OwnershipGuard ownership;
    private final ExecutorService executor = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "forgetdm-mapping-run"); t.setDaemon(true); return t;
    });

    public MappingExecutionService(MappingRunRepository runs, MappingService mappings,
                                   MappingVersionRepository versions, MappingFileService files,
                                   DataSourceService dataSources, ConnectionFactory connections,
                                   ManagedFileVault vault, MaskingEngine masking, ObjectMapper json,
                                   AuditService audit, OwnershipGuard ownership) {
        this.runs = runs; this.mappings = mappings; this.versions = versions; this.files = files;
        this.dataSources = dataSources; this.connections = connections; this.vault = vault;
        this.masking = masking; this.json = json; this.audit = audit; this.ownership = ownership;
    }

    @PreDestroy void shutdown() { executor.shutdownNow(); }

    @Transactional
    public MappingRunEntity start(Long mappingId, JsonNode request) {
        MappingEntity mapping = mappings.get(mappingId);
        Map<String, Object> plan = mappings.plan(mappingId);
        if (!Boolean.TRUE.equals(plan.get("valid"))) throw ApiException.bad("Mapping is not ready: " + plan.get("errors"));
        if (Boolean.TRUE.equals(plan.get("destructive"))) {
            String confirmation = request == null ? "" : request.path("confirmation").asText("");
            if (!mapping.getName().equals(confirmation))
                throw ApiException.bad("Confirm the destructive target preparation by entering the mapping name exactly");
        }
        int version = versions.findByMappingIdOrderByVersionNoDesc(mappingId).stream()
                .findFirst().map(MappingVersionEntity::getVersionNo).orElse(1);
        MappingRunEntity run = new MappingRunEntity();
        run.setMappingId(mappingId); run.setMappingVersion(version); run.setCreatedBy(actor());
        run.setOwnerUserId(ownership.defaultOwnerUserId());
        run.setOwnerUsername(ownership.defaultOwnerUsername());
        run.setOwnerGroupId(ownership.defaultOwnerGroupId());
        run.setVisibility(ownership.defaultVisibility());
        run.setRequestJson(request == null ? "{}" : request.toString());
        run.setMessage("Queued for governed execution");
        run = runs.save(run);
        Long runId = run.getId();
        audit.record(actor(), "MAPPING_RUN_QUEUED", "MAPPING", "MAPPING_RUN", String.valueOf(runId),
                mapping.getName(), "SUCCESS", "version=" + version, null);
        AccessPrincipal caller = AccessContext.current().orElse(null);
        String callerToken = AccessContext.currentToken().orElse(null);
        submitAfterCommit(runId, caller, callerToken);
        return run;
    }

    private void submitAfterCommit(Long runId, AccessPrincipal caller, String callerToken) {
        Runnable submit = () -> executor.submit(() -> AccessContext.callAs(caller, callerToken, () -> {
            execute(runId);
            return null;
        }));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { submit.run(); }
            });
        } else {
            submit.run();
        }
    }

    public List<MappingRunEntity> list() {
        return runs.findTop100ByOrderByCreatedAtDesc().stream()
                .filter(run -> ownership.canSee(run.getOwnerUserId(), run.getOwnerGroupId(), run.getVisibility()))
                .toList();
    }
    public MappingRunEntity get(Long id) {
        MappingRunEntity run = runs.findById(id).orElseThrow(() -> ApiException.notFound("Mapping run " + id + " not found"));
        ownership.assertCanSee("mapping run", id, run.getOwnerUserId(), run.getOwnerGroupId(), run.getVisibility());
        return run;
    }

    @Transactional
    public MappingRunEntity cancel(Long id) {
        MappingRunEntity run = get(id);
        if (Set.of("COMPLETED", "FAILED", "CANCELED").contains(run.getStatus())) return run;
        run.setCancelRequested(true); run.setMessage("Cancellation requested; current database batch will finish safely");
        MappingEntity mapping = mappings.get(run.getMappingId());
        audit.record(actor(), "MAPPING_RUN_CANCEL_REQUESTED", "CANCEL", "MAPPING_RUN", String.valueOf(id),
                mapping.getName(), "SUCCESS", "Mapping cancellation requested", null);
        return runs.save(run);
    }

    @Transactional
    public MappingRunEntity retry(Long id) {
        MappingRunEntity previous = get(id);
        if (!Set.of("FAILED", "CANCELED").contains(previous.getStatus())) {
            throw ApiException.bad("Only failed or canceled mapping runs can be retried");
        }
        try {
            MappingRunEntity retry = start(previous.getMappingId(), json.readTree(previous.getRequestJson()));
            MappingEntity mapping = mappings.get(previous.getMappingId());
            audit.record(actor(), "MAPPING_RUN_RETRIED", "MAPPING", "MAPPING_RUN", String.valueOf(retry.getId()),
                    mapping.getName(), "SUCCESS", "Created retry attempt",
                    "{\"previousRunId\":" + previous.getId() + "}");
            return retry;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.bad("Stored mapping request cannot be retried: " + safeError(e));
        }
    }

    public InputStreamDownload output(Long id) {
        MappingRunEntity run = get(id);
        if (!"COMPLETED".equals(run.getStatus()) || run.getOutputStorageKey() == null)
            throw ApiException.bad("This mapping run has no completed file output");
        return new InputStreamDownload(run.getOutputName(), contentType(run.getOutputFormat()),
                vault.open(run.getOutputStorageKey(), run.getOutputKeySalt(), run.getOutputIv()));
    }

    public record InputStreamDownload(String filename, String contentType, java.io.InputStream stream) {}

    public Map<String, Object> preview(JsonNode spec) {
        Map<String, Object> validation = mappings.validateSpec(spec);
        if (!Boolean.TRUE.equals(validation.get("valid"))) throw ApiException.bad("Mapping is not ready: " + validation.get("errors"));
        JsonNode sources = spec.path("sources");
        if (!sources.isArray() || sources.isEmpty()) throw ApiException.bad("Add at least one source");
        JsonNode previewSource = compiledSource(spec);
        if (previewSource == null && sources.size() > 1) return previewFederated(spec, validation);
        if (previewSource == null) previewSource = sources.get(0);
        List<LinkedHashMap<String, Object>> collected = new ArrayList<>();
        RowProjector projector = new RowProjector(spec.path("columns"), masking.withSeed(spec.path("previewSeed").asText("preview")));
        JsonNode sourceToRead = previewSource;
        try { readSource(sourceToRead, 101, row -> { if (collected.size() < 101) collected.add(projector.project(row, collected.size() + 1L)); }); }
        catch (Exception e) { throw e instanceof ApiException a ? a : ApiException.bad("Preview failed: " + e.getMessage()); }
        boolean truncated = collected.size() > 100;
        List<LinkedHashMap<String, Object>> rows = truncated ? new ArrayList<>(collected.subList(0, 100)) : collected;
        List<String> columns = rows.isEmpty() ? List.of() : new ArrayList<>(rows.get(0).keySet());
        return Map.of("columns", columns, "rows", rows, "rowCount", rows.size(), "truncated", truncated,
                "warnings", validation.get("warnings"));
    }

    private Map<String, Object> previewFederated(JsonNode spec, Map<String, Object> validation) {
        JsonNode sources = spec.path("sources");
        LinkedHashMap<String, List<LinkedHashMap<String, Object>>> byAlias = new LinkedHashMap<>();
        try {
            for (JsonNode source : sources) {
                String alias = source.path("alias").asText("source_" + (byAlias.size() + 1)).toLowerCase(Locale.ROOT);
                List<LinkedHashMap<String, Object>> rows = new ArrayList<>();
                readSource(source, 1001, row -> {
                    LinkedHashMap<String, Object> qualified = new LinkedHashMap<>();
                    row.forEach((key, value) -> qualified.put(alias + "." + key, value)); rows.add(qualified);
                });
                byAlias.put(alias, rows);
            }
        } catch (Exception e) { throw e instanceof ApiException a ? a : ApiException.bad("Federated preview failed: " + e.getMessage()); }
        List<String> aliases = new ArrayList<>(byAlias.keySet());
        List<LinkedHashMap<String, Object>> result = new ArrayList<>(byAlias.get(aliases.get(0)));
        Set<String> joined = new HashSet<>(); joined.add(aliases.get(0));
        JsonNode joins = spec.path("joins");
        while (joined.size() < aliases.size()) {
            String nextAlias = aliases.stream().filter(a -> !joined.contains(a)).filter(a -> hasJoinTo(a, joined, joins)).findFirst()
                    .orElseThrow(() -> ApiException.bad("Every additional source needs a join path to the existing preview graph"));
            List<JsonNode> conditions = new ArrayList<>();
            for (JsonNode join : joins) {
                String leftAlias = aliasOf(join.path("left").asText()); String rightAlias = aliasOf(join.path("right").asText());
                if ((nextAlias.equals(leftAlias) && joined.contains(rightAlias)) || (nextAlias.equals(rightAlias) && joined.contains(leftAlias))) conditions.add(join);
            }
            String joinType = conditions.get(0).path("type").asText("INNER").toUpperCase(Locale.ROOT);
            List<LinkedHashMap<String, Object>> rightRows = byAlias.get(nextAlias);
            Set<LinkedHashMap<String, Object>> matchedRight = Collections.newSetFromMap(new IdentityHashMap<>());
            List<LinkedHashMap<String, Object>> merged = new ArrayList<>();
            for (LinkedHashMap<String, Object> leftRow : result) {
                boolean matched = false;
                for (LinkedHashMap<String, Object> rightRow : rightRows) if (joinMatches(leftRow, rightRow, conditions)) {
                    LinkedHashMap<String, Object> row = new LinkedHashMap<>(leftRow); row.putAll(rightRow); merged.add(row);
                    matchedRight.add(rightRow); matched = true; if (merged.size() > 10_000) throw ApiException.bad("Federated preview exceeded 10,000 intermediate rows; add selective filters");
                }
                if (!matched && ("LEFT".equals(joinType) || "FULL".equals(joinType))) merged.add(new LinkedHashMap<>(leftRow));
            }
            if ("RIGHT".equals(joinType) || "FULL".equals(joinType)) for (LinkedHashMap<String, Object> rightRow : rightRows)
                if (!matchedRight.contains(rightRow)) merged.add(new LinkedHashMap<>(rightRow));
            result = merged; joined.add(nextAlias);
        }
        RowProjector projector = new RowProjector(spec.path("columns"), masking.withSeed(spec.path("previewSeed").asText("preview")));
        List<LinkedHashMap<String, Object>> output = new ArrayList<>();
        for (int i = 0; i < Math.min(result.size(), 101); i++) output.add(projector.project(result.get(i), i + 1L));
        boolean truncated = result.size() > 100; if (output.size() > 100) output = new ArrayList<>(output.subList(0, 100));
        List<String> columns = output.isEmpty() ? List.of() : new ArrayList<>(output.get(0).keySet());
        return Map.of("columns", columns, "rows", output, "rowCount", output.size(), "truncated", truncated,
                "warnings", validation.get("warnings"), "executionMode", "BOUNDED_FEDERATION_PREVIEW");
    }

    private static boolean hasJoinTo(String alias, Set<String> joined, JsonNode joins) {
        if (!joins.isArray()) return false;
        for (JsonNode join : joins) {
            String left = aliasOf(join.path("left").asText()), right = aliasOf(join.path("right").asText());
            if ((alias.equals(left) && joined.contains(right)) || (alias.equals(right) && joined.contains(left))) return true;
        }
        return false;
    }
    private static boolean joinMatches(Map<String, Object> left, Map<String, Object> right, List<JsonNode> conditions) {
        for (JsonNode condition : conditions) {
            String leftKey = condition.path("left").asText(), rightKey = condition.path("right").asText();
            Object a = left.containsKey(leftKey) ? left.get(leftKey) : right.get(leftKey);
            Object b = left.containsKey(rightKey) ? left.get(rightKey) : right.get(rightKey);
            if (a == null || b == null || !String.valueOf(a).trim().equals(String.valueOf(b).trim())) return false;
        }
        return true;
    }
    private static String aliasOf(String qualified) { int dot = qualified.indexOf('.'); return (dot < 0 ? qualified : qualified.substring(0, dot)).toLowerCase(Locale.ROOT); }

    private void execute(Long runId) {
        MappingRunEntity run = get(runId);
        MappingEntity mapping = mappings.get(run.getMappingId());
        try {
            update(run, "RUNNING", "VALIDATE", 5, "Validating immutable mapping version");
            run = get(runId);
            run.setStartedAt(Instant.now());
            runs.save(run);
            audit.record(run.getCreatedBy(), "MAPPING_RUN_STARTED", "MAPPING", "MAPPING_RUN", String.valueOf(runId),
                    mapping.getName(), "SUCCESS", "Mapping execution started", null);
            JsonNode spec = json.readTree(mapping.getSpecJson());
            if (spec.path("sources").isArray() && !spec.path("sources").isEmpty()) executeV2(run, spec);
            else executeLegacy(run, spec);
            run = get(runId);
            run.setStatus("COMPLETED"); run.setStage("COMPLETED"); run.setProgress(100);
            run.setMessage("Provisioning completed and validation evidence recorded");
            run.setFinishedAt(Instant.now());
            run.setResultJson(json.writeValueAsString(Map.of("rowsRead", run.getRowsRead(),
                    "rowsWritten", run.getRowsWritten(), "rowsRejected", run.getRowsRejected(),
                    "outputSha256", Objects.toString(run.getOutputSha256(), ""))));
            runs.save(run);
            audit.record(run.getCreatedBy(), "MAPPING_RUN_COMPLETED", "MAPPING", "MAPPING_RUN", String.valueOf(runId),
                    mapping.getName(), "SUCCESS", "rows=" + run.getRowsWritten(), run.getResultJson());
        } catch (Canceled e) {
            run = get(runId); run.setStatus("CANCELED"); run.setStage("CANCELED");
            run.setMessage("Canceled safely; active target transaction was rolled back"); run.setFinishedAt(Instant.now()); runs.save(run);
            audit.record(run.getCreatedBy(), "MAPPING_RUN_CANCELED", "CANCEL", "MAPPING_RUN", String.valueOf(runId),
                    mapping.getName(), "SUCCESS", "Mapping execution canceled safely", null);
        } catch (Exception e) {
            run = get(runId); run.setStatus("FAILED"); run.setStage("FAILED");
            run.setMessage("Execution failed"); run.setErrorMessage(safeError(e)); run.setFinishedAt(Instant.now()); runs.save(run);
            audit.record(run.getCreatedBy(), "MAPPING_RUN_FAILED", "MAPPING", "MAPPING_RUN", String.valueOf(runId),
                    mapping.getName(), "FAILURE", safeError(e), null);
        }
    }

    private void executeLegacy(MappingRunEntity run, JsonNode spec) {
        JsonNode statements = spec.path("loadStatements");
        Long target = spec.path("target").hasNonNull("dsId") ? spec.path("target").get("dsId").asLong() : null;
        if (!statements.isArray() || statements.isEmpty() || target == null)
            throw ApiException.bad("Legacy mapping has no persisted table load route; open it in Mapping Designer, choose a target, and save");
        List<String> sql = new ArrayList<>(); statements.forEach(n -> sql.add(n.asText()));
        update(run, "RUNNING", "LOAD", 45, "Executing transactional mapping route");
        Map<String, Object> result = mappings.loadMulti(target, sql);
        run = get(run.getId());
        run.setRowsWritten(((Number) result.getOrDefault("totalRows", 0)).longValue()); runs.save(run);
    }

    private void executeV2(MappingRunEntity run, JsonNode spec) throws Exception {
        JsonNode sources = spec.path("sources");
        if (spec.path("routeExecution").asBoolean(false)) {
            JsonNode statements = spec.path("loadStatements");
            Long target = spec.path("target").hasNonNull("dataSourceId") ? spec.path("target").get("dataSourceId").asLong() : null;
            if (!statements.isArray() || statements.isEmpty() || target == null) throw ApiException.bad("Router targets are not fully configured");
            update(run, "RUNNING", "ROUTE_LOAD", 35, "Executing all router targets in one transaction");
            List<String> sql = new ArrayList<>(); statements.forEach(node -> sql.add(node.asText()));
            Map<String, Object> result = mappings.loadMulti(target, sql);
            MappingRunEntity current = get(run.getId());
            current.setRowsWritten(((Number) result.getOrDefault("totalRows", 0)).longValue()); current.setRowsRead(current.getRowsWritten()); runs.save(current);
            return;
        }
        JsonNode source = compiledSource(spec);
        if (source == null && sources.size() != 1) {
            JsonNode statements = spec.path("loadStatements");
            Long target = spec.path("target").hasNonNull("dataSourceId") ? spec.path("target").get("dataSourceId").asLong() : null;
            if (statements.isArray() && !statements.isEmpty() && target != null) {
                List<String> sql = new ArrayList<>(); statements.forEach(n -> sql.add(n.asText()));
                Map<String, Object> result = mappings.loadMulti(target, sql);
                run = get(run.getId()); run.setRowsWritten(((Number) result.getOrDefault("totalRows", 0)).longValue()); runs.save(run); return;
            }
            throw ApiException.bad("Production execution of multiple database/file sources requires a persisted staging route. Preview remains available for bounded federation.");
        }
        if (source == null) source = sources.get(0);
        JsonNode target = spec.path("target");
        String targetType = target.path("type").asText("FILE").toUpperCase(Locale.ROOT);
        long rowLimit = positiveLong(spec.path("rowLimit").asLong(0));
        String seed = requestSeed(run.getRequestJson());
        RowProjector projector = new RowProjector(spec.path("columns"), masking.withSeed(seed));
        final Long executionRunId = run.getId();

        try (RowSink sink = "DATABASE".equals(targetType)
                ? new DatabaseSink(run, target)
                : new FileSink(run, target)) {
            update(run, "RUNNING", "READ", 12, "Opening source with streaming and bounded fetch settings");
            AtomicLong index = new AtomicLong();
            RowConsumer consumer = row -> {
                long n = index.incrementAndGet();
                checkCancel(executionRunId, n);
                LinkedHashMap<String, Object> projected = projector.project(row, n);
                sink.accept(projected);
                if (n % 250 == 0) {
                    MappingRunEntity current = get(executionRunId); current.setRowsRead(n); current.setRowsWritten(sink.written());
                    current.setStage("TRANSFORM_LOAD"); current.setProgress(rowLimit > 0 ? 15 + (int) Math.min(75, n * 75 / rowLimit) : Math.min(90, 15 + (int) Math.log10(n + 1) * 15));
                    current.setMessage("Streaming " + n + " rows through " + projector.summary()); runs.save(current);
                }
            };
            readSource(source, rowLimit, consumer);
            sink.finish();
            MappingRunEntity current = get(executionRunId); current.setRowsRead(index.get()); current.setRowsWritten(sink.written());
            current.setStage("VALIDATE"); current.setProgress(95); current.setMessage("Validating delivered row counts and output digest"); runs.save(current);
        }
    }

    private void readSource(JsonNode source, long rowLimit, RowConsumer consumer) throws Exception {
        String type = source.path("type").asText("DATABASE").toUpperCase(Locale.ROOT);
        if ("FILE".equals(type)) {
            MappingFileAssetEntity asset = files.get(source.path("assetId").asLong());
            files.streamRows(asset, rowLimit, consumer::accept);
            return;
        }
        DataSourceEntity ds = dataSources.get(source.path("dataSourceId").asLong());
        try (Connection c = connections.openForBulk(ds)) {
            String sql = source.path("sql").asText("").trim();
            if (sql.isBlank()) {
                String schema = source.path("schema").asText("");
                String table = source.path("table").asText("");
                sql = "SELECT * FROM " + qualified(c, schema, table);
                String filter = source.path("filter").asText("").trim();
                if (!filter.isBlank()) { SubsetService.guardFilter(filter); sql += " WHERE " + filter; }
            } else guardSelect(sql);
            try (Statement st = c.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                try { st.setFetchSize(1000); } catch (Exception ignore) { }
                if (rowLimit > 0) st.setMaxRows((int) Math.min(rowLimit, Integer.MAX_VALUE));
                try (ResultSet rs = st.executeQuery(sql)) {
                    ResultSetMetaData md = rs.getMetaData(); int count = md.getColumnCount(); long n = 0;
                    while (rs.next() && (rowLimit <= 0 || n++ < rowLimit)) {
                        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= count; i++) row.put(md.getColumnLabel(i), rs.getObject(i));
                        consumer.accept(row);
                    }
                }
            }
        }
    }

    /** A visual multi-table transformation compiles to one guarded SELECT on one source database. */
    private JsonNode compiledSource(JsonNode spec) {
        String sql = spec.path("compiledSql").asText("").trim();
        if (sql.isBlank() || !spec.hasNonNull("compiledDataSourceId")) return null;
        var source = json.createObjectNode();
        source.put("type", "DATABASE");
        source.put("alias", "compiled_mapping");
        source.put("dataSourceId", spec.get("compiledDataSourceId").asLong());
        source.put("sql", sql);
        return source;
    }

    private interface RowConsumer { void accept(LinkedHashMap<String, Object> row) throws Exception; }
    private interface RowSink extends AutoCloseable {
        void accept(LinkedHashMap<String, Object> row) throws Exception;
        void finish() throws Exception;
        long written();
    }

    private final class DatabaseSink implements RowSink {
        private final MappingRunEntity run;
        private final JsonNode target;
        private final DataSourceEntity ds;
        private final Connection connection;
        private PreparedStatement insert;
        private List<String> columns;
        private Map<String, Integer> sqlTypes = Map.of();
        private int pending;
        private long written;
        private boolean finished;

        private DatabaseSink(MappingRunEntity run, JsonNode target) throws Exception {
            this.run = run; this.target = target; this.ds = dataSources.get(target.path("dataSourceId").asLong());
            this.connection = connections.openForBulk(ds); connection.setAutoCommit(false);
            String table = qualified(connection, target.path("schema").asText(""), target.path("table").asText(""));
            String pre = target.path("preAction").asText("NONE").toUpperCase(Locale.ROOT);
            if ("DELETE".equals(pre) || "TRUNCATE".equals(pre)) {
                update(run, "RUNNING", "PREPARE", 18, pre + " target before load");
                String sql = "DELETE".equals(pre) ? "DELETE FROM " + table : SqlDialect.of(ds).truncateSql(table);
                try (Statement st = connection.createStatement()) { st.executeUpdate(sql); }
            }
        }

        @Override public void accept(LinkedHashMap<String, Object> row) throws Exception {
            if (insert == null) prepare(row);
            for (int i = 0; i < columns.size(); i++) setValue(insert, i + 1, row.get(columns.get(i)), sqlTypes.getOrDefault(columns.get(i).toLowerCase(Locale.ROOT), Types.VARCHAR));
            insert.addBatch(); pending++;
            int batch = Math.max(1, Math.min(1000, SqlDialect.of(ds).bindParamLimit() / Math.max(1, columns.size())));
            if (pending >= batch) flush();
        }

        private void prepare(LinkedHashMap<String, Object> row) throws Exception {
            columns = new ArrayList<>(row.keySet());
            if (columns.isEmpty()) throw ApiException.bad("Column mapping produced no target columns");
            String schema = target.path("schema").asText(""); String tableName = target.path("table").asText("");
            String table = qualified(connection, schema, tableName);
            String quote = quote(connection);
            String names = columns.stream().map(c -> quote(c, quote)).collect(java.util.stream.Collectors.joining(","));
            String binds = String.join(",", Collections.nCopies(columns.size(), "?"));
            insert = connection.prepareStatement("INSERT INTO " + table + " (" + names + ") VALUES (" + binds + ")");
            sqlTypes = targetTypes(connection, schema, tableName);
        }

        private void flush() throws Exception { if (pending == 0) return; int[] counts = insert.executeBatch(); for (int n : counts) written += n == Statement.SUCCESS_NO_INFO ? 1 : Math.max(0, n); pending = 0; }
        @Override public void finish() throws Exception { flush(); connection.commit(); finished = true; }
        @Override public long written() { return written; }
        @Override public void close() throws Exception {
            try { if (!finished) connection.rollback(); } finally { if (insert != null) insert.close(); connection.close(); }
        }
    }

    private final class FileSink implements RowSink {
        private final MappingRunEntity run;
        private final String format;
        private final ManagedFileVault.OutputHandle handle;
        private final BufferedWriter writer;
        private CSVPrinter csv;
        private List<String> columns;
        private long written;
        private boolean first = true;
        private boolean finished;

        private FileSink(MappingRunEntity run, JsonNode target) throws Exception {
            this.run = run; this.format = target.path("format").asText("CSV").toUpperCase(Locale.ROOT);
            if (!Set.of("CSV", "JSON", "JSONL").contains(format)) throw ApiException.bad("File targets support CSV, JSON, and JSONL");
            this.handle = vault.createOutput(); this.writer = new BufferedWriter(new OutputStreamWriter(handle.stream(), StandardCharsets.UTF_8));
            if ("JSON".equals(format)) writer.write("[");
        }

        @Override public void accept(LinkedHashMap<String, Object> row) throws Exception {
            if (columns == null) {
                columns = new ArrayList<>(row.keySet());
                if ("CSV".equals(format)) csv = new CSVPrinter(writer, CSVFormat.DEFAULT.builder().setHeader(columns.toArray(String[]::new)).get());
            }
            if ("CSV".equals(format)) csv.printRecord(columns.stream().map(row::get).toList());
            else {
                if ("JSON".equals(format) && !first) writer.write(",");
                writer.write(json.writeValueAsString(row));
                if ("JSONL".equals(format)) writer.newLine();
            }
            first = false; written++;
        }

        @Override public void finish() throws Exception {
            if ("JSON".equals(format)) writer.write("]");
            if (csv != null) csv.flush(); writer.flush(); handle.close(); finished = true;
            MappingRunEntity current = get(run.getId());
            current.setOutputStorageKey(handle.storageKey()); current.setOutputKeySalt(handle.keySalt()); current.setOutputIv(handle.iv());
            current.setOutputSha256(handle.sha256()); current.setOutputFormat(format);
            current.setOutputName("mapping-" + current.getMappingId() + "-run-" + current.getId() + "." + format.toLowerCase(Locale.ROOT)); runs.save(current);
        }
        @Override public long written() { return written; }
        @Override public void close() throws Exception {
            if (!finished) { try { writer.close(); } finally { vault.delete(handle.storageKey()); } }
        }
    }

    private static final class RowProjector {
        private final JsonNode columns;
        private final MaskingEngine masking;
        private RowProjector(JsonNode columns, MaskingEngine masking) { this.columns = columns; this.masking = masking; }
        private LinkedHashMap<String, Object> project(LinkedHashMap<String, Object> row, long index) {
            if (columns == null || !columns.isArray() || columns.isEmpty()) return new LinkedHashMap<>(row);
            MaskContext context = new MaskContext(index);
            row.forEach((k, v) -> context.row.put(k.toLowerCase(Locale.ROOT), v == null ? null : String.valueOf(v)));
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            for (JsonNode col : columns) {
                String action = col.path("action").asText("COPY").toUpperCase(Locale.ROOT);
                if ("UNUSED".equals(action)) continue;
                String target = col.path("target").asText();
                Object original = sourceValue(row, col.path("source").asText());
                Object value = original;
                if ("LITERAL".equals(action)) value = col.path("literal").isNull() ? null : col.path("literal").asText();
                else if ("MASK".equals(action)) {
                    MaskFunction fn;
                    try { fn = MaskFunction.valueOf(col.path("maskFunction").asText().toUpperCase(Locale.ROOT)); }
                    catch (Exception e) { throw ApiException.bad("Unknown masking function for " + target); }
                    value = masking.mask(fn, col.path("salt").asText(target), original == null ? null : String.valueOf(original),
                            textOrNull(col, "param1"), textOrNull(col, "param2"), context);
                }
                out.put(target, value); context.masked.put(target.toLowerCase(Locale.ROOT), value == null ? null : String.valueOf(value));
            }
            return out;
        }
        private String summary() { return columns == null || !columns.isArray() ? "pass-through mapping" : columns.size() + " column actions"; }
    }

    private void checkCancel(Long runId, long row) {
        if (row == 1 || row % 250 == 0) if (get(runId).isCancelRequested()) throw new Canceled();
    }

    private void update(MappingRunEntity run, String status, String stage, int progress, String message) {
        MappingRunEntity current = get(run.getId()); current.setStatus(status); current.setStage(stage);
        current.setProgress(progress); current.setMessage(message);
        if (current.getStartedAt() == null) current.setStartedAt(Instant.now()); runs.save(current);
    }

    private static Object sourceValue(Map<String, Object> row, String source) {
        if (source == null) return null;
        if (row.containsKey(source)) return row.get(source);
        String simple = source.contains(".") ? source.substring(source.lastIndexOf('.') + 1) : source;
        for (Map.Entry<String, Object> e : row.entrySet()) if (e.getKey().equalsIgnoreCase(source) || e.getKey().equalsIgnoreCase(simple)) return e.getValue();
        return null;
    }

    private static void guardSelect(String sql) {
        String s = sql.trim(); while (s.endsWith(";")) s = s.substring(0, s.length() - 1).trim();
        if (s.contains(";") || !(s.toLowerCase(Locale.ROOT).startsWith("select") || s.toLowerCase(Locale.ROOT).startsWith("with")))
            throw ApiException.bad("Mapping source SQL must be one read-only SELECT or WITH query");
    }

    private static String qualified(Connection c, String schema, String table) throws Exception {
        if (table == null || table.isBlank()) throw ApiException.bad("Table name is required");
        String q = quote(c); return schema == null || schema.isBlank() ? quote(table, q) : quote(schema, q) + "." + quote(table, q);
    }
    private static String quote(Connection c) throws Exception { String q = c.getMetaData().getIdentifierQuoteString(); return q == null || q.isBlank() ? "\"" : q.trim(); }
    private static String quote(String value, String q) { if (value.indexOf('\0') >= 0) throw ApiException.bad("Invalid identifier"); return q + value.replace(q, q + q) + q; }

    private static Map<String, Integer> targetTypes(Connection c, String schema, String table) throws Exception {
        Map<String, Integer> out = new HashMap<>();
        try (ResultSet rs = c.getMetaData().getColumns(c.getCatalog(), schema == null || schema.isBlank() ? null : schema, table, null))
        { while (rs.next()) out.put(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT), rs.getInt("DATA_TYPE")); }
        return out;
    }

    private static void setValue(PreparedStatement ps, int index, Object value, int type) throws Exception {
        if (value == null) { ps.setNull(index, type); return; }
        if (!(value instanceof String s)) { ps.setObject(index, value); return; }
        String v = s.trim();
        try {
            switch (type) {
                case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> ps.setInt(index, Integer.parseInt(v));
                case Types.BIGINT -> ps.setLong(index, Long.parseLong(v));
                case Types.NUMERIC, Types.DECIMAL, Types.FLOAT, Types.REAL, Types.DOUBLE -> ps.setBigDecimal(index, new BigDecimal(v));
                case Types.BOOLEAN, Types.BIT -> ps.setBoolean(index, Boolean.parseBoolean(v));
                case Types.DATE -> ps.setObject(index, LocalDate.parse(v));
                case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> { try { ps.setObject(index, OffsetDateTime.parse(v)); } catch (DateTimeParseException e) { ps.setTimestamp(index, Timestamp.valueOf(v)); } }
                default -> ps.setString(index, s);
            }
        } catch (RuntimeException e) { throw ApiException.bad("Value '" + clip(v, 80) + "' is not valid for target JDBC type " + type); }
    }

    private static String requestSeed(String requestJson) { try { return new ObjectMapper().readTree(requestJson).path("seed").asText(""); } catch (Exception e) { return ""; } }
    private static String textOrNull(JsonNode node, String field) { String value = node.path(field).asText(""); return value.isBlank() ? null : value; }
    private static long positiveLong(long value) { return Math.max(0, value); }
    private static String safeError(Exception e) { return clip(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), 4000); }
    private static String clip(String s, int max) { return s != null && s.length() > max ? s.substring(0, max) : s; }
    private static String actor() { return AccessContext.current().map(p -> p.username()).orElse("system"); }
    private static String contentType(String format) { return "JSON".equalsIgnoreCase(format) || "JSONL".equalsIgnoreCase(format) ? "application/json" : "text/csv"; }
    private static final class Canceled extends RuntimeException {}
}
