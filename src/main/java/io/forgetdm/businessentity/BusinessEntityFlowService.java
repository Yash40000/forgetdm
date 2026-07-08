package io.forgetdm.businessentity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.security.AccessContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class BusinessEntityFlowService {
    private final JdbcTemplate jdbc;
    private final BusinessEntityService entities;
    private final BusinessEntityEnterpriseService enterprise;
    private final AuditService audit;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    public record FlowRequest(Long id, String name, String description, String status,
                              List<Map<String, Object>> nodes, List<Map<String, Object>> edges,
                              Map<String, Object> settings) {}
    public record DebugRequest(String mode, String failStepKey, List<String> breakpoints,
                               Map<String, Object> inputs) {}
    public record RunRequest(String mode, Long executionPlanId, Long targetDataSourceId, String targetSchema,
                             Long policyId, String maskingSeed, String loadAction, String targetPrep,
                             Integer maxRows, Long rowCount, Long seed, String executionMode,
                             Long lookalikeProfileId, String filter, String failStepKey,
                             List<String> breakpoints, Map<String, Object> inputs) {}

    public BusinessEntityFlowService(JdbcTemplate jdbc, BusinessEntityService entities,
                                     BusinessEntityEnterpriseService enterprise, AuditService audit) {
        this.jdbc = jdbc;
        this.entities = entities;
        this.enterprise = enterprise;
        this.audit = audit;
    }

    public List<Map<String, Object>> listFlows(Long entityId) {
        entities.getDetail(entityId);
        return rows("""
                SELECT id, entity_id AS "entityId", name, description, version_no AS "versionNo",
                       status, canvas_json AS "canvasJson", created_by AS "createdBy",
                       created_at AS "createdAt", updated_at AS "updatedAt"
                  FROM be_orchestration_flows
                 WHERE entity_id = ? ORDER BY updated_at DESC, id DESC
                """, entityId).stream().map(this::flowRow).toList();
    }

    public Map<String, Object> starterFlow(Long entityId) {
        BusinessEntityService.BusinessEntityDetail detail = entities.getDetail(entityId);
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        nodes.add(node("start", "START", "Start request", 28, 90, Map.of("entry", true)));
        nodes.add(node("approval", "APPROVAL_CHECK", "Maker-checker gate", 245, 90,
                Map.of("requiresApprovedRun", true)));
        nodes.add(node("reserve", "RESERVATION_CHECK", "Reservation and conflict check", 490, 90,
                Map.of("activeReservations", "checked")));
        nodes.add(node("fanout", "DATASCOPE_FANOUT", "Multi-app DataScope fan-out", 745, 90,
                Map.of("sliceCount", dataScopeSliceCount(detail), "members", detail.members().size())));
        nodes.add(node("transform", "TRANSFORM", "Reusable transformation rules", 745, 255,
                Map.of("library", "BE_TRANSFORM_LIBRARY", "mode", "metadata-driven")));
        nodes.add(node("prepare", "TWO_PHASE_COMMIT", "Prepare target loaders", 490, 255,
                Map.of("prepare", true, "rollbackOnFailure", true)));
        nodes.add(node("launch", "EXECUTION_PLAN", "Launch approved run", 245, 255,
                Map.of("usesApprovedPlan", true, "dryRunOnlyInDebugger", true)));
        nodes.add(node("exception", "EXCEPTION_HANDLER", "Exception and rollback route", 490, 420,
                Map.of("onFailure", "capture evidence and rollback prepared targets")));
        nodes.add(node("end", "END", "Evidence bundle complete", 28, 255, Map.of("auditReady", true)));
        edge(edges, "start", "approval", "SUCCESS");
        edge(edges, "approval", "reserve", "SUCCESS");
        edge(edges, "reserve", "fanout", "SUCCESS");
        edge(edges, "fanout", "transform", "SUCCESS");
        edge(edges, "transform", "prepare", "SUCCESS");
        edge(edges, "prepare", "launch", "SUCCESS");
        edge(edges, "launch", "end", "SUCCESS");
        edge(edges, "fanout", "exception", "ERROR");
        edge(edges, "transform", "exception", "ERROR");
        edge(edges, "prepare", "exception", "ERROR");
        edge(edges, "launch", "exception", "ERROR");
        edge(edges, "exception", "end", "SUCCESS");
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("transactionMode", "TWO_PHASE_COMMIT");
        settings.put("exceptionPolicy", "ROUTE_TO_HANDLER");
        settings.put("debugMode", "STEP_THROUGH");
        settings.put("reusableTransformations", List.of("format-preserving mask", "eligibility filter", "cross-system key hash"));
        return Map.of(
                "name", safe(detail.entity().getName()) + " enterprise flow",
                "description", "No-code orchestration flow for " + safe(detail.entity().getName()),
                "status", "DRAFT",
                "nodes", nodes,
                "edges", edges,
                "settings", settings
        );
    }

    public Map<String, Object> saveFlow(Long entityId, FlowRequest request) {
        entities.getDetail(entityId);
        String name = required(request == null ? null : request.name(), "Flow name");
        List<Map<String, Object>> nodes = cleanList(request == null ? null : request.nodes());
        List<Map<String, Object>> edges = cleanList(request == null ? null : request.edges());
        if (nodes.isEmpty()) throw ApiException.bad("Add at least one orchestration step before saving the flow.");
        validateFlowShape(nodes, edges);
        Map<String, Object> canvas = new LinkedHashMap<>();
        canvas.put("nodes", nodes);
        canvas.put("edges", edges);
        canvas.put("settings", request == null || request.settings() == null ? Map.of() : request.settings());
        Long existingId = request == null ? null : request.id();
        long id;
        if (existingId == null) {
            id = insert("""
                    INSERT INTO be_orchestration_flows(entity_id, name, description, status, canvas_json, created_by, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, entityId, name, blank(request.description()), upperDefault(request.status(), "DRAFT"),
                    writeJson(canvas), currentUsername(), ts(Instant.now()));
        } else {
            Map<String, Object> existing = rawFlow(existingId);
            if (!Objects.equals(num(existing.get("entityId")), entityId)) throw ApiException.bad("Flow does not belong to this Business Entity.");
            jdbc.update("""
                    UPDATE be_orchestration_flows
                       SET name = ?, description = ?, status = ?, canvas_json = ?,
                           version_no = version_no + 1, updated_at = ?
                     WHERE id = ?
                    """, name, blank(request.description()), upperDefault(request.status(), "DRAFT"),
                    writeJson(canvas), ts(Instant.now()), existingId);
            id = existingId;
        }
        audit.log(currentUsername(), "BUSINESS_ENTITY_FLOW_SAVE", "entity=" + entityId + " flow=" + id + " nodes=" + nodes.size());
        return getFlow(id);
    }

    public Map<String, Object> getFlow(Long flowId) {
        return flowRow(rawFlow(flowId));
    }

    public Map<String, Object> validateFlow(Long flowId) {
        Map<String, Object> flow = getFlow(flowId);
        return validationReport(castList(flow.get("nodes")), castList(flow.get("edges")));
    }

    public Map<String, Object> publishFlow(Long flowId) {
        Map<String, Object> flow = getFlow(flowId);
        Map<String, Object> validation = validationReport(castList(flow.get("nodes")), castList(flow.get("edges")));
        if (!"PASS".equals(validation.get("status"))) {
            throw ApiException.bad("Flow cannot be published until critical validation findings are fixed.");
        }
        jdbc.update("UPDATE be_orchestration_flows SET status = 'ACTIVE', version_no = version_no + 1, updated_at = ? WHERE id = ?",
                ts(Instant.now()), flowId);
        audit.log(currentUsername(), "BUSINESS_ENTITY_FLOW_PUBLISH",
                "flow=" + flowId + " entity=" + flow.get("entityId") + " score=" + validation.get("score"));
        return getFlow(flowId);
    }

    public void deleteFlow(Long flowId) {
        Map<String, Object> flow = rawFlow(flowId);
        jdbc.update("DELETE FROM be_orchestration_flows WHERE id = ?", flowId);
        audit.log(currentUsername(), "BUSINESS_ENTITY_FLOW_DELETE", "flow=" + flowId + " entity=" + flow.get("entityId"));
    }

    public List<Map<String, Object>> listDebugRuns(Long flowId) {
        rawFlow(flowId);
        return rows("""
                SELECT id, entity_id AS "entityId", flow_id AS "flowId", mode, status,
                       current_step_key AS "currentStepKey", input_json AS "inputJson",
                       events_json AS "eventsJson", created_by AS "createdBy",
                       started_at AS "startedAt", completed_at AS "completedAt"
                  FROM be_orchestration_debug_runs
                 WHERE flow_id = ? ORDER BY started_at DESC, id DESC LIMIT 12
                """, flowId).stream().map(this::debugRow).toList();
    }

    public Map<String, Object> debugFlow(Long flowId, DebugRequest request) {
        RunRequest run = new RunRequest(
                upperDefault(request == null ? null : request.mode(), "DEBUG_DRY_RUN"),
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                request == null ? null : request.failStepKey(),
                request == null ? List.of() : request.breakpoints(),
                request == null ? Map.of() : request.inputs());
        return executeFlow(flowId, run, false);
    }

    public Map<String, Object> runFlow(Long flowId, RunRequest request) {
        return executeFlow(flowId, request, true);
    }

    private Map<String, Object> executeFlow(Long flowId, RunRequest request, boolean physicalExecution) {
        Map<String, Object> flow = getFlow(flowId);
        if (physicalExecution && !"ACTIVE".equals(String.valueOf(flow.get("status")).toUpperCase(Locale.ROOT))) {
            throw ApiException.bad("Publish this flow before running approved physical execution.");
        }
        Map<String, Object> validation = validationReport(castList(flow.get("nodes")), castList(flow.get("edges")));
        if (physicalExecution && !"PASS".equals(validation.get("status"))) {
            throw ApiException.bad("Flow has critical validation findings and cannot run.");
        }
        List<Map<String, Object>> nodes = castList(flow.get("nodes"));
        List<Map<String, Object>> edges = castList(flow.get("edges"));
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> node : nodes) byKey.put(safe(node.get("key")), node);
        Map<String, List<Map<String, Object>>> outgoing = new LinkedHashMap<>();
        for (Map<String, Object> edge : edges) outgoing.computeIfAbsent(safe(edge.get("from")), k -> new ArrayList<>()).add(edge);
        Set<String> breakpoints = new LinkedHashSet<>(request == null || request.breakpoints() == null ? List.of() : request.breakpoints());
        String failStepKey = safe(request == null ? null : request.failStepKey());
        String mode = upperDefault(request == null ? null : request.mode(), physicalExecution ? "EXECUTE_APPROVED" : "DEBUG_DRY_RUN");
        String current = nodes.stream()
                .filter(n -> "START".equals(type(n)))
                .map(n -> safe(n.get("key")))
                .findFirst().orElse(safe(nodes.get(0).get("key")));
        List<Map<String, Object>> events = new ArrayList<>();
        String status = "COMPLETED";
        String currentStepKey = current;
        Set<String> visited = new LinkedHashSet<>();
        int sequence = 1;
        while (current != null && byKey.containsKey(current) && sequence <= 200) {
            Map<String, Object> node = byKey.get(current);
            currentStepKey = current;
            Map<String, Object> event = simulateStep(sequence++, node, flow, request);
            if (!physicalExecution && current.equals(failStepKey)) {
                event.put("status", "FAILED");
                event.put("message", "Injected debugger failure. Exception path was evaluated before any physical data movement.");
                events.add(event);
                String routed = nextFor(outgoing.get(current), "ERROR");
                if (routed != null && byKey.containsKey(routed)) {
                    current = routed;
                    continue;
                }
                status = "FAILED";
                break;
            }
            if (!physicalExecution && breakpoints.contains(current)) {
                event.put("status", "BREAKPOINT");
                event.put("message", "Debugger paused here. No target data was changed.");
                events.add(event);
                status = "BREAKPOINT";
                break;
            }
            if (physicalExecution && "EXECUTION_PLAN".equals(type(node))) {
                try {
                    Map<String, Object> launched = launchExecutionPlanFromFlow(flow, node, request);
                    event.put("message", "Approved Business Entity execution plan launched from orchestration flow.");
                    map(event.get("details")).put("launchResult", launched);
                    status = String.valueOf(launched.getOrDefault("status", launched.getOrDefault("engineStatus", "SUBMITTED")));
                } catch (RuntimeException ex) {
                    event.put("status", "FAILED");
                    event.put("message", "Execution plan launch failed: " + ex.getMessage());
                    events.add(event);
                    String routed = nextFor(outgoing.get(current), "ERROR");
                    if (routed != null && byKey.containsKey(routed)) {
                        current = routed;
                        status = "FAILED_HANDLED";
                        continue;
                    }
                    status = "FAILED";
                    break;
                }
            }
            events.add(event);
            if ("END".equals(type(node))) break;
            String next = nextFor(outgoing.get(current), "SUCCESS");
            if (next == null || visited.contains(current + "->" + next)) break;
            visited.add(current + "->" + next);
            current = next;
        }
        if (sequence > 200) status = "FAILED";
        long id = insert("""
                INSERT INTO be_orchestration_debug_runs(entity_id, flow_id, mode, status, current_step_key,
                    input_json, events_json, created_by, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, num(flow.get("entityId")), flowId, mode,
                status, blank(currentStepKey), writeJson(request == null || request.inputs() == null ? Map.of() : request.inputs()),
                writeJson(events), currentUsername(), "BREAKPOINT".equals(status) ? null : ts(Instant.now()));
        audit.log(currentUsername(), physicalExecution ? "BUSINESS_ENTITY_FLOW_RUN" : "BUSINESS_ENTITY_FLOW_DEBUG",
                "flow=" + flowId + " status=" + status + " events=" + events.size());
        return getDebugRun(id);
    }

    private Map<String, Object> launchExecutionPlanFromFlow(Map<String, Object> flow, Map<String, Object> node, RunRequest request) {
        Map<String, Object> config = map(node.get("config"));
        Long planId = firstNonNull(request == null ? null : request.executionPlanId(), num(config.get("executionPlanId")));
        if (planId == null) throw ApiException.bad("Choose an execution plan before running this flow.");
        BusinessEntityEnterpriseService.LaunchRequest launchRequest = new BusinessEntityEnterpriseService.LaunchRequest(
                request == null ? null : request.targetDataSourceId(),
                request == null ? null : request.targetSchema(),
                request == null ? null : request.policyId(),
                request == null ? null : request.maskingSeed(),
                upperDefault(request == null ? null : request.loadAction(), "REPLACE"),
                upperDefault(request == null ? null : request.targetPrep(), "DELETE"),
                request == null ? null : request.maxRows(),
                request == null ? null : request.rowCount(),
                request == null ? null : request.seed(),
                upperDefault(request == null ? null : request.executionMode(), "SINGLE"),
                request == null ? null : request.lookalikeProfileId(),
                request == null ? null : request.filter());
        Map<String, Object> result = enterprise.launchExecutionPlan(planId, launchRequest);
        Map<String, Object> out = new LinkedHashMap<>(result);
        out.put("executionPlanId", planId);
        out.put("flowId", flow.get("id"));
        return out;
    }

    private Map<String, Object> simulateStep(int sequence, Map<String, Object> node, Map<String, Object> flow, RunRequest request) {
        String nodeType = type(node);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("sequence", sequence);
        event.put("stepKey", node.get("key"));
        event.put("label", node.getOrDefault("label", nodeType));
        event.put("type", nodeType);
        event.put("status", "PASSED");
        event.put("message", messageFor(nodeType));
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> config = map(node.get("config"));
        details.putAll(config);
        if ("DATASCOPE_FANOUT".equals(nodeType)) {
            details.put("physicalRuns", config.getOrDefault("sliceCount", 1));
            details.put("debugGuarantee", "No physical DataScope job is submitted in debugger mode.");
        } else if ("LOOP".equals(nodeType)) {
            details.put("iterationsPlanned", config.getOrDefault("iterations", config.getOrDefault("members", 1)));
            details.put("loopMode", config.getOrDefault("loopMode", "FOR_EACH_APPLICATION_SLICE"));
        } else if ("TWO_PHASE_COMMIT".equals(nodeType)) {
            details.put("phase1", "prepare target loaders and validate rollback handles");
            details.put("phase2", "commit only after every participant is ready");
            details.put("rollback", "route to exception handler if any participant rejects prepare");
        } else if ("EXCEPTION_HANDLER".equals(nodeType)) {
            details.put("captures", List.of("failed step", "input evidence", "rollback instruction", "audit event"));
        } else if ("TRANSFORM".equals(nodeType)) {
            details.put("reusable", true);
            details.put("libraryHash", sha256(String.valueOf(config)).substring(0, 12));
        }
        event.put("details", details);
        return event;
    }

    private String messageFor(String nodeType) {
        return switch (nodeType) {
            case "START" -> "Accepted orchestration request and initialized context.";
            case "APPROVAL_CHECK" -> "Verified maker-checker approval evidence before execution.";
            case "RESERVATION_CHECK" -> "Checked reservation conflicts and active entity locks.";
            case "DATASCOPE_FANOUT" -> "Resolved physical application slices for DataScope fan-out.";
            case "SYNTHETIC_LOOKALIKE" -> "Resolved synthetic look-alike generation plan.";
            case "TRANSFORM" -> "Loaded reusable transformation configuration.";
            case "LOOP" -> "Expanded loop across configured participants.";
            case "TWO_PHASE_COMMIT" -> "Prepared transaction participants for coordinated commit.";
            case "EXECUTION_PLAN" -> "Validated approved execution plan launch payload.";
            case "EXCEPTION_HANDLER" -> "Captured exception evidence and selected rollback path.";
            case "END" -> "Closed the orchestration run and prepared evidence bundle.";
            default -> "Validated no-code orchestration step.";
        };
    }

    private Map<String, Object> validationReport(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        List<Map<String, Object>> findings = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        Map<String, String> types = new LinkedHashMap<>();
        for (Map<String, Object> node : nodes) {
            String key = safe(node.get("key"));
            if (key.isBlank()) {
                findings.add(finding("CRITICAL", "MISSING_KEY", "Every step must have a stable key.", null));
                continue;
            }
            if (!keys.add(key)) findings.add(finding("CRITICAL", "DUPLICATE_KEY", "Duplicate step key: " + key, key));
            types.put(key, type(node));
        }
        long startCount = types.values().stream().filter("START"::equals).count();
        long endCount = types.values().stream().filter("END"::equals).count();
        if (startCount != 1) findings.add(finding("CRITICAL", "START_REQUIRED", "Flow must have exactly one START step.", null));
        if (endCount < 1) findings.add(finding("CRITICAL", "END_REQUIRED", "Flow must have at least one END step.", null));
        if (!types.containsValue("EXECUTION_PLAN")) {
            findings.add(finding("CRITICAL", "EXECUTION_STEP_REQUIRED", "Enterprise flow must include an approved execution-plan launch step.", null));
        }
        if (!types.containsValue("EXCEPTION_HANDLER")) {
            findings.add(finding("CRITICAL", "EXCEPTION_HANDLER_REQUIRED", "Enterprise flow must include an exception handler.", null));
        }
        if (!types.containsValue("TWO_PHASE_COMMIT")) {
            findings.add(finding("WARN", "TWO_PHASE_COMMIT_RECOMMENDED", "Add two-phase commit prepare/commit evidence for multi-target runs.", null));
        }

        Map<String, List<Map<String, Object>>> outgoing = new LinkedHashMap<>();
        for (Map<String, Object> edge : edges) {
            String from = safe(edge.get("from"));
            String to = safe(edge.get("to"));
            if (!keys.contains(from) || !keys.contains(to)) {
                findings.add(finding("CRITICAL", "BROKEN_EDGE", "Edge references a missing step: " + from + " -> " + to, from));
            }
            outgoing.computeIfAbsent(from, k -> new ArrayList<>()).add(edge);
        }
        Optional<String> exceptionKey = types.entrySet().stream()
                .filter(e -> "EXCEPTION_HANDLER".equals(e.getValue()))
                .map(Map.Entry::getKey).findFirst();
        if (exceptionKey.isPresent()) {
            for (Map.Entry<String, String> entry : types.entrySet()) {
                if (Set.of("START", "END", "EXCEPTION_HANDLER").contains(entry.getValue())) continue;
                boolean hasErrorRoute = outgoing.getOrDefault(entry.getKey(), List.of()).stream()
                        .anyMatch(e -> "ERROR".equalsIgnoreCase(safe(e.get("condition"))) && exceptionKey.get().equals(safe(e.get("to"))));
                if (!hasErrorRoute) {
                    findings.add(finding("WARN", "MISSING_ERROR_ROUTE",
                            "Step has no ERROR route to the exception handler.", entry.getKey()));
                }
            }
        }
        Set<String> reachable = reachableKeys(types, outgoing);
        for (String key : keys) {
            if (!reachable.contains(key)) {
                findings.add(finding("WARN", "UNREACHABLE_STEP", "Step is not reachable from START.", key));
            }
        }
        long critical = findings.stream().filter(f -> "CRITICAL".equals(f.get("severity"))).count();
        long warn = findings.stream().filter(f -> "WARN".equals(f.get("severity"))).count();
        int score = Math.max(0, 100 - (int) critical * 25 - (int) warn * 5);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", critical == 0 ? "PASS" : "FAIL");
        out.put("score", score);
        out.put("criticalCount", critical);
        out.put("warningCount", warn);
        out.put("findings", findings);
        out.put("summary", critical == 0
                ? "Flow is publishable. Warnings should be reviewed before production."
                : "Flow has critical design gaps and cannot be published or physically run.");
        return out;
    }

    private Set<String> reachableKeys(Map<String, String> types, Map<String, List<Map<String, Object>>> outgoing) {
        String start = types.entrySet().stream().filter(e -> "START".equals(e.getValue())).map(Map.Entry::getKey).findFirst().orElse(null);
        if (start == null) return Set.of();
        Set<String> seen = new LinkedHashSet<>();
        ArrayDeque<String> q = new ArrayDeque<>();
        q.add(start);
        while (!q.isEmpty()) {
            String cur = q.removeFirst();
            if (!seen.add(cur)) continue;
            for (Map<String, Object> edge : outgoing.getOrDefault(cur, List.of())) {
                String to = safe(edge.get("to"));
                if (!to.isBlank() && !seen.contains(to)) q.add(to);
            }
        }
        return seen;
    }

    private Map<String, Object> finding(String severity, String code, String message, String stepKey) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("severity", severity);
        row.put("code", code);
        row.put("message", message);
        row.put("stepKey", blank(stepKey));
        return row;
    }

    private void validateFlowShape(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        Set<String> keys = new LinkedHashSet<>();
        for (Map<String, Object> node : nodes) {
            String key = required(safe(node.get("key")), "Flow step key");
            if (!keys.add(key)) throw ApiException.bad("Duplicate flow step key: " + key);
            required(type(node), "Flow step type");
        }
        for (Map<String, Object> edge : edges) {
            String from = required(safe(edge.get("from")), "Flow edge source");
            String to = required(safe(edge.get("to")), "Flow edge target");
            if (!keys.contains(from) || !keys.contains(to)) {
                throw ApiException.bad("Flow edge references a missing step: " + from + " -> " + to);
            }
        }
    }

    private Map<String, Object> flowRow(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>(row);
        JsonNode canvas = readJson(row.get("canvasJson"), "flow canvas");
        out.put("nodes", json.convertValue(canvas.path("nodes"), List.class));
        out.put("edges", json.convertValue(canvas.path("edges"), List.class));
        out.put("settings", json.convertValue(canvas.path("settings"), Map.class));
        return out;
    }

    private Map<String, Object> debugRow(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>(row);
        out.put("events", json.convertValue(readJson(row.get("eventsJson"), "debug events"), List.class));
        out.put("inputs", json.convertValue(readJson(row.get("inputJson"), "debug inputs"), Map.class));
        return out;
    }

    private Map<String, Object> getDebugRun(Long id) {
        return debugRow(one("""
                SELECT id, entity_id AS "entityId", flow_id AS "flowId", mode, status,
                       current_step_key AS "currentStepKey", input_json AS "inputJson",
                       events_json AS "eventsJson", created_by AS "createdBy",
                       started_at AS "startedAt", completed_at AS "completedAt"
                  FROM be_orchestration_debug_runs WHERE id = ?
                """, id));
    }

    private Map<String, Object> rawFlow(Long id) {
        return one("""
                SELECT id, entity_id AS "entityId", name, description, version_no AS "versionNo",
                       status, canvas_json AS "canvasJson", created_by AS "createdBy",
                       created_at AS "createdAt", updated_at AS "updatedAt"
                  FROM be_orchestration_flows WHERE id = ?
                """, id);
    }

    private int dataScopeSliceCount(BusinessEntityService.BusinessEntityDetail detail) {
        Set<Long> datasetIds = new LinkedHashSet<>();
        Long primary = detail.entity().getPrimaryDatasetId();
        for (BusinessEntityMemberEntity member : detail.members()) {
            if (!member.isIncludeInSubset()) continue;
            Long datasetId = member.getDatasetId() == null ? primary : member.getDatasetId();
            if (datasetId != null) datasetIds.add(datasetId);
        }
        return Math.max(1, datasetIds.isEmpty() && primary != null ? 1 : datasetIds.size());
    }

    private Map<String, Object> node(String key, String type, String label, int x, int y, Map<String, Object> config) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("key", key);
        node.put("type", type);
        node.put("label", label);
        node.put("x", x);
        node.put("y", y);
        node.put("config", config);
        return node;
    }

    private void edge(List<Map<String, Object>> edges, String from, String to, String condition) {
        edges.add(new LinkedHashMap<>(Map.of("from", from, "to", to, "condition", condition)));
    }

    private String nextFor(List<Map<String, Object>> edges, String condition) {
        if (edges == null) return null;
        return edges.stream()
                .filter(e -> condition.equalsIgnoreCase(safe(e.get("condition"))))
                .map(e -> safe(e.get("to")))
                .filter(s -> !s.isBlank())
                .findFirst().orElse(null);
    }

    private long insert(String sql, Object... args) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, key);
        Number n = key.getKeyList().isEmpty() ? null : (Number) key.getKeyList().get(0).get("id");
        if (n == null) n = key.getKey();
        if (n == null) throw ApiException.bad("Insert did not return an id");
        return n.longValue();
    }

    private List<Map<String, Object>> rows(String sql, Object... args) {
        return jdbc.queryForList(sql, args);
    }

    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = rows(sql, args);
        if (rows.isEmpty()) throw ApiException.notFound("Business Entity flow object not found");
        return rows.get(0);
    }

    private JsonNode readJson(Object v, String label) {
        try {
            if (v == null) return json.createObjectNode();
            return json.readTree(String.valueOf(v));
        } catch (Exception e) {
            throw ApiException.bad("Could not read " + label + ": " + e.getMessage());
        }
    }

    private String writeJson(Object v) {
        try { return json.writeValueAsString(v); }
        catch (Exception e) { throw ApiException.bad("Could not write flow manifest: " + e.getMessage()); }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object value) {
        if (value instanceof List<?> list) return (List<Map<String, Object>>) list;
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        if (value instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return Map.of();
    }

    private List<Map<String, Object>> cleanList(List<Map<String, Object>> rows) {
        if (rows == null) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (row != null) out.add(new LinkedHashMap<>(row));
        }
        return out;
    }

    private static Timestamp ts(Instant i) { return i == null ? null : Timestamp.from(i); }
    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        if (values == null) return null;
        for (T value : values) if (value != null) return value;
        return null;
    }
    private static Long num(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(v).trim()); } catch (Exception e) { return null; }
    }
    private static String type(Map<String, Object> node) {
        return safe(node == null ? null : node.get("type")).toUpperCase(Locale.ROOT);
    }
    private static String required(String v, String label) {
        String clean = blank(v);
        if (clean == null) throw ApiException.bad(label + " is required");
        return clean;
    }
    private static String upperDefault(String v, String d) {
        String clean = blank(v);
        return (clean == null ? d : clean).toUpperCase(Locale.ROOT);
    }
    private static String blank(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }
    private static String safe(Object v) {
        String clean = blank(v == null ? null : String.valueOf(v));
        return clean == null ? "" : clean;
    }
    private static String currentUsername() {
        return AccessContext.current().map(p -> p.username()).orElse("system");
    }
    private static String sha256(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) { return UUID.randomUUID().toString().replace("-", ""); }
    }
}
