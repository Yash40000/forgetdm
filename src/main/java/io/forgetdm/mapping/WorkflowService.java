package io.forgetdm.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
    private final AuditService audit;
    private final ObjectMapper json = new ObjectMapper();
    private final ExecutorService runner = Executors.newFixedThreadPool(2);
    private final Map<Long, Boolean> running = new ConcurrentHashMap<>();

    public WorkflowService(WorkflowRepository repo, MappingService mappings, AuditService audit) {
        this.repo = repo;
        this.mappings = mappings;
        this.audit = audit;
    }

    @PreDestroy
    void shutdown() { runner.shutdownNow(); }

    public List<WorkflowEntity> list() {
        List<WorkflowEntity> all = repo.findAll();
        all.sort(Comparator.comparing(WorkflowEntity::getName, String.CASE_INSENSITIVE_ORDER));
        return all;
    }

    public WorkflowEntity get(Long id) {
        return repo.findById(id).orElseThrow(() -> ApiException.notFound("Workflow " + id + " not found"));
    }

    public WorkflowEntity save(WorkflowEntity in) {
        if (in == null || in.getName() == null || in.getName().isBlank())
            throw ApiException.bad("Workflow name is required");
        JsonNode steps = parseSteps(in.getStepsJson());
        if (steps.size() == 0) throw ApiException.bad("Add at least one step");
        for (int i = 0; i < steps.size(); i++) validateStep(steps.get(i), i + 1);
        WorkflowEntity e = in.getId() != null ? get(in.getId())
                : repo.findByNameIgnoreCase(in.getName().trim()).orElseGet(WorkflowEntity::new);
        e.setName(in.getName().trim());
        e.setDescription(in.getDescription());
        e.setStepsJson(in.getStepsJson());
        e.setUpdatedAt(Instant.now());
        WorkflowEntity saved = repo.save(e);
        audit.log("system", "WORKFLOW_SAVED", saved.getName() + " (" + steps.size() + " steps)");
        return saved;
    }

    public void delete(Long id) {
        WorkflowEntity e = get(id);
        if (Boolean.TRUE.equals(running.get(id))) throw ApiException.bad("Workflow is running — wait for it to finish");
        repo.deleteById(id);
        audit.log("system", "WORKFLOW_DELETED", e.getName());
    }

    /** Kick off a run on a background thread; poll GET /{id} for last_run_json progress. */
    public Map<String, Object> run(Long id) {
        WorkflowEntity wf = get(id);
        if (running.putIfAbsent(id, Boolean.TRUE) != null)
            throw ApiException.bad("Workflow '" + wf.getName() + "' is already running");
        JsonNode steps = parseSteps(wf.getStepsJson());
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
        audit.log("system", "WORKFLOW_STARTED", wf.getName());
        runner.submit(() -> execute(id, wf.getName(), steps, run));
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
        } else if ("SQL".equalsIgnoreCase(type)) {
            if (!step.hasNonNull("dataSourceId")) throw ApiException.bad("Step " + n + ": pick a data source");
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

    private void persistRun(Long id, ObjectNode run) {
        try {
            WorkflowEntity e = get(id);
            e.setLastRunJson(json.writeValueAsString(run));
            repo.save(e);
        } catch (Exception ignore) { /* progress persistence is best-effort */ }
    }
}
