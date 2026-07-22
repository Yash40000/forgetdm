package io.forgetdm.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessPrincipal;
import io.forgetdm.security.OwnershipGuard;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Workflow orchestration: ordered steps executed sequentially on a background thread —
 * MAPPING steps run a saved mapping's persisted load statements (single- or multi-target),
 * SQL steps run one INSERT…SELECT / CTAS. Per-step status streams into last_run_json so the
 * UI can poll; failure policy per step: STOP (default) aborts the rest, CONTINUE records and moves on.
 */
@Service
public class WorkflowService {

    private final WorkflowRepository repo;
    private final MappingService mappings;
    private final DataSourceService dataSources;
    private final AuditService audit;
    private final OwnershipGuard ownership;
    private final ObjectMapper json = new ObjectMapper();
    private final ExecutorService runner = Executors.newFixedThreadPool(2);
    private final Map<Long, Boolean> running = new ConcurrentHashMap<>();

    public WorkflowService(WorkflowRepository repo, MappingService mappings, DataSourceService dataSources,
                           AuditService audit, OwnershipGuard ownership) {
        this.repo = repo;
        this.mappings = mappings;
        this.dataSources = dataSources;
        this.audit = audit;
        this.ownership = ownership;
    }

    @PreDestroy
    void shutdown() { runner.shutdownNow(); }

    public List<WorkflowEntity> list() {
        List<WorkflowEntity> all = new java.util.ArrayList<>(repo.findAll().stream()
                .filter(w -> ownership.canSee(w.getOwnerUserId(), w.getOwnerGroupId(), w.getVisibility()))
                .toList());
        all.sort(Comparator.comparing(WorkflowEntity::getName, String.CASE_INSENSITIVE_ORDER));
        return all;
    }

    public WorkflowEntity get(Long id) {
        WorkflowEntity workflow = repo.findById(id)
                .orElseThrow(() -> ApiException.notFound("Workflow " + id + " not found"));
        ownership.assertCanSee("mapping workflow", id, workflow.getOwnerUserId(),
                workflow.getOwnerGroupId(), workflow.getVisibility());
        return workflow;
    }

    public WorkflowEntity save(WorkflowEntity in) {
        if (in == null || in.getName() == null || in.getName().isBlank())
            throw ApiException.bad("Workflow name is required");
        JsonNode steps = parseSteps(in.getStepsJson());
        if (steps.size() == 0) throw ApiException.bad("Add at least one step");
        for (int i = 0; i < steps.size(); i++) validateStep(steps.get(i), i + 1);
        WorkflowEntity e;
        boolean creating;
        if (in.getId() != null) {
            e = get(in.getId());
            creating = false;
        } else {
            var existing = repo.findByNameIgnoreCase(in.getName().trim());
            e = existing.isPresent() ? get(existing.get().getId()) : new WorkflowEntity();
            creating = existing.isEmpty();
        }
        e.setName(in.getName().trim());
        e.setDescription(in.getDescription());
        e.setStepsJson(in.getStepsJson());
        if (creating) {
            e.setOwnerUserId(ownership.defaultOwnerUserId());
            e.setOwnerUsername(ownership.defaultOwnerUsername());
            e.setOwnerGroupId(ownership.defaultOwnerGroupId());
            e.setVisibility(ownership.defaultVisibility());
        } else if (in.getVisibility() != null && !in.getVisibility().isBlank()) {
            e.setVisibility(normalizeVisibility(in.getVisibility()));
        }
        e.setUpdatedAt(Instant.now());
        WorkflowEntity saved = repo.save(e);
        audit.log(actor(), "WORKFLOW_SAVED", saved.getName() + " (" + steps.size() + " steps)");
        return saved;
    }

    public void delete(Long id) {
        WorkflowEntity e = get(id);
        if (Boolean.TRUE.equals(running.get(id))) throw ApiException.bad("Workflow is running — wait for it to finish");
        repo.deleteById(id);
        audit.log(actor(), "WORKFLOW_DELETED", e.getName());
    }

    /** Kick off a run on a background thread; poll GET /{id} for last_run_json progress. */
    public Map<String, Object> run(Long id) {
        WorkflowEntity wf = get(id);
        JsonNode steps = parseSteps(wf.getStepsJson());
        for (int i = 0; i < steps.size(); i++) validateStep(steps.get(i), i + 1);
        if (running.putIfAbsent(id, Boolean.TRUE) != null)
            throw ApiException.bad("Workflow '" + wf.getName() + "' is already running");
        ObjectNode run = json.createObjectNode();
        run.put("status", "RUNNING");
        run.put("startedAt", Instant.now().toString());
        ArrayNode stepStates = run.putArray("steps");
        for (int i = 0; i < steps.size(); i++) {
            ObjectNode s = stepStates.addObject();
            s.put("label", stepLabel(steps.get(i), i + 1));
            s.put("status", "PENDING");
        }
        persistRun(id, run);
        audit.log(actor(), "WORKFLOW_STARTED", wf.getName());
        AccessPrincipal caller = AccessContext.current().orElse(null);
        String callerToken = AccessContext.currentToken().orElse(null);
        runner.submit(() -> AccessContext.callAs(caller, callerToken, () -> {
            execute(id, wf.getName(), steps, run);
            return null;
        }));
        return Map.of("started", true, "steps", steps.size());
    }

    private void execute(Long id, String name, JsonNode steps, ObjectNode run) {
        boolean anyFailed = false;
        try {
            for (int i = 0; i < steps.size(); i++) {
                JsonNode step = steps.get(i);
                ObjectNode state = (ObjectNode) run.get("steps").get(i);
                state.put("status", "RUNNING");
                persistRun(id, run);
                long t0 = System.currentTimeMillis();
                try {
                    long rows = runStep(step);
                    state.put("status", "COMPLETED");
                    state.put("rows", rows);
                } catch (Exception e) {
                    anyFailed = true;
                    state.put("status", "FAILED");
                    state.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                    if (!"CONTINUE".equalsIgnoreCase(step.path("onError").asText("STOP"))) {
                        for (int j = i + 1; j < steps.size(); j++)
                            ((ObjectNode) run.get("steps").get(j)).put("status", "SKIPPED");
                        break;
                    }
                } finally {
                    state.put("elapsedMs", System.currentTimeMillis() - t0);
                }
            }
        } finally {
            run.put("status", anyFailed ? "FAILED" : "COMPLETED");
            run.put("finishedAt", Instant.now().toString());
            persistRun(id, run);
            running.remove(id);
            audit.log("system", anyFailed ? "WORKFLOW_FAILED" : "WORKFLOW_COMPLETED", name);
        }
    }

    private long runStep(JsonNode step) {
        String type = step.path("type").asText("");
        if ("SQL".equalsIgnoreCase(type)) {
            Map<String, Object> r = mappings.loadMulti(step.get("dataSourceId").asLong(),
                    List.of(step.path("sql").asText()));
            return ((Number) r.get("totalRows")).longValue();
        }
        // MAPPING: run the saved mapping's persisted load statements (written by the designer on Save)
        MappingEntity m = mappings.get(step.get("mappingId").asLong());
        JsonNode spec;
        try { spec = json.readTree(m.getSpecJson() == null ? "{}" : m.getSpecJson()); }
        catch (Exception e) { throw ApiException.bad("Mapping '" + m.getName() + "' has an unreadable spec"); }
        JsonNode load = spec.path("loadStatements");
        Long dsId = spec.path("target").hasNonNull("dsId") ? spec.path("target").get("dsId").asLong() : null;
        if (!load.isArray() || load.size() == 0 || dsId == null)
            throw ApiException.bad("Mapping '" + m.getName() + "' has no saved load statements — open it in the "
                    + "Designer, set Output = Table (with a target), and Save again");
        java.util.List<String> statements = new java.util.ArrayList<>();
        load.forEach(n -> statements.add(n.asText()));
        Map<String, Object> r = mappings.loadMulti(dsId, statements);
        return ((Number) r.get("totalRows")).longValue();
    }

    private void validateStep(JsonNode step, int n) {
        String type = step.path("type").asText("");
        if ("MAPPING".equalsIgnoreCase(type)) {
            if (!step.hasNonNull("mappingId")) throw ApiException.bad("Step " + n + ": pick a saved mapping");
            long mappingId = step.get("mappingId").asLong();
            mappings.get(mappingId);
            Map<String, Object> plan = mappings.plan(mappingId);
            if (!Boolean.TRUE.equals(plan.get("valid"))) {
                throw ApiException.bad("Step " + n + ": mapping is not ready: " + plan.get("errors"));
            }
        } else if ("SQL".equalsIgnoreCase(type)) {
            if (!step.hasNonNull("dataSourceId")) throw ApiException.bad("Step " + n + ": pick a data source");
            dataSources.get(step.get("dataSourceId").asLong());
            String s = step.path("sql").asText("").trim().toLowerCase(java.util.Locale.ROOT);
            if (!(s.startsWith("insert") || s.startsWith("create table")))
                throw ApiException.bad("Step " + n + ": SQL must be INSERT … SELECT or CREATE TABLE AS");
        } else {
            throw ApiException.bad("Step " + n + ": type must be MAPPING or SQL");
        }
    }

    private String stepLabel(JsonNode step, int n) {
        String explicit = step.path("label").asText("");
        if (!explicit.isBlank()) return explicit;
        if ("MAPPING".equalsIgnoreCase(step.path("type").asText(""))) {
            try { return "Mapping: " + mappings.get(step.get("mappingId").asLong()).getName(); }
            catch (Exception e) { return "Mapping #" + step.path("mappingId").asText("?"); }
        }
        return "SQL step " + n;
    }

    private JsonNode parseSteps(String stepsJson) {
        try {
            JsonNode n = json.readTree(stepsJson == null ? "[]" : stepsJson);
            if (!n.isArray()) throw new IllegalArgumentException("not an array");
            return n;
        } catch (Exception e) {
            throw ApiException.bad("steps_json must be a JSON array of steps");
        }
    }

    private static String normalizeVisibility(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of(OwnershipGuard.PRIVATE, OwnershipGuard.GROUP, OwnershipGuard.SHARED).contains(normalized)) {
            throw ApiException.bad("Visibility must be PRIVATE, GROUP, or SHARED");
        }
        return normalized;
    }

    private static String actor() {
        return AccessContext.current().map(p -> p.username()).orElse("system");
    }

    private void persistRun(Long id, ObjectNode run) {
        try {
            WorkflowEntity e = get(id);
            e.setLastRunJson(json.writeValueAsString(run));
            repo.save(e);
        } catch (Exception ignore) { /* progress persistence is best-effort */ }
    }
}
