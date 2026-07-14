package io.forgetdm.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.ai.AgentContracts.Compilation;
import io.forgetdm.common.ApiException;
import io.forgetdm.security.AccessContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Durable repository for agent plans, steps, approvals, events and learning feedback. */
@Repository
public class AgentRunRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AgentRunRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record StoredStep(long id, long runId, int ordinal, String code, String title, String detail,
                             String operation, String status, boolean changesData, boolean requiresApproval,
                             String actionName, JsonNode actionArgs, String actionSummary, JsonNode evidence,
                             JsonNode result, Instant startedAt, Instant finishedAt) {}

    public record StoredRun(long id, String goal, String status, String summary, String providerId, String modelName,
                            JsonNode intent, JsonNode plan, JsonNode validation, JsonNode questions, JsonNode evidence,
                            double confidence, String riskLevel, String fingerprint, boolean modelAssisted,
                            String createdBy, String approvedBy, Instant approvedAt, Instant canceledAt,
                            Instant createdAt, Instant updatedAt, List<StoredStep> steps) {}

    public long create(String goal, Compilation compilation, String actor) {
        String initialStatus = compilation.validation().stream().anyMatch(issue -> "BLOCKER".equals(issue.severity()))
                ? "BLOCKED" : "AWAITING_PLAN_APPROVAL";
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO forge_ai_agent_runs(goal,status,summary,provider_id,model_name,intent_json,plan_json,validation_json,questions_json,evidence_json," +
                            "confidence,risk_level,plan_fingerprint,model_assisted,created_by,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            ps.setString(i++, goal);
            ps.setString(i++, initialStatus);
            ps.setString(i++, compilation.summary());
            ps.setString(i++, compilation.providerId());
            ps.setString(i++, compilation.modelName());
            ps.setString(i++, write(compilation.intent()));
            ps.setString(i++, write(compilation.steps()));
            ps.setString(i++, write(compilation.validation()));
            ps.setString(i++, write(compilation.questions()));
            ps.setString(i++, write(compilation.evidence()));
            ps.setDouble(i++, compilation.confidence());
            ps.setString(i++, compilation.riskLevel());
            ps.setString(i++, compilation.fingerprint());
            ps.setBoolean(i++, compilation.modelAssisted());
            ps.setString(i++, actor);
            ps.setTimestamp(i++, Timestamp.from(Instant.now()));
            ps.setTimestamp(i, Timestamp.from(Instant.now()));
            return ps;
        }, keys);
        Number key = generatedId(keys);
        if (key == null) throw new IllegalStateException("Agent run id was not generated");
        long runId = key.longValue();
        for (AgentContracts.CompiledStep step : compilation.steps()) {
            jdbc.update("INSERT INTO forge_ai_agent_steps(run_id,ordinal_no,step_code,title,detail,operation,status,changes_data,requires_approval,action_name,action_args_json,action_summary,evidence_json,result_json,updated_at) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    runId, step.ordinal(), step.code(), step.title(), step.detail(), step.operation(), step.status(),
                    step.changesData(), step.requiresApproval(), step.actionName(), nullable(step.actionArgs()), step.detail(),
                    write(step.evidence()), step.result(), Timestamp.from(Instant.now()));
        }
        event(runId, "PLAN_COMPILED", actor, "Story compiled into a grounded execution plan",
                json.createObjectNode().put("fingerprint", compilation.fingerprint()).put("confidence", compilation.confidence()));
        return runId;
    }

    public StoredRun get(long id) {
        List<StoredRun> rows = jdbc.query("SELECT * FROM forge_ai_agent_runs WHERE id=?", (rs, n) -> new StoredRun(
                rs.getLong("id"), rs.getString("goal"), rs.getString("status"), rs.getString("summary"),
                rs.getString("provider_id"), rs.getString("model_name"), read(rs.getString("intent_json")),
                read(rs.getString("plan_json")), read(rs.getString("validation_json")), read(rs.getString("questions_json")),
                read(rs.getString("evidence_json")), rs.getDouble("confidence"), rs.getString("risk_level"),
                rs.getString("plan_fingerprint"), rs.getBoolean("model_assisted"), rs.getString("created_by"),
                rs.getString("approved_by"), instant(rs.getTimestamp("approved_at")), instant(rs.getTimestamp("canceled_at")),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")), List.of()), id);
        if (rows.isEmpty()) throw ApiException.notFound("Agent run " + id + " not found");
        StoredRun run = rows.get(0);
        assertVisible(run);
        return withSteps(run, steps(id));
    }

    public List<StoredRun> list() {
        String actor = actor();
        boolean reviewer = AccessContext.current()
                .map(p -> p.roles().contains("ADMIN") || p.hasPermission("provision.approve")).orElse(false);
        String sql = "SELECT * FROM forge_ai_agent_runs" + (reviewer ? "" : " WHERE created_by=?") + " ORDER BY id DESC LIMIT 200";
        Object[] args = reviewer ? new Object[0] : new Object[]{actor};
        return jdbc.query(sql, (rs, n) -> {
            long id = rs.getLong("id");
            return new StoredRun(id, rs.getString("goal"), rs.getString("status"), rs.getString("summary"),
                    rs.getString("provider_id"), rs.getString("model_name"), read(rs.getString("intent_json")),
                    read(rs.getString("plan_json")), read(rs.getString("validation_json")), read(rs.getString("questions_json")),
                    read(rs.getString("evidence_json")), rs.getDouble("confidence"), rs.getString("risk_level"),
                    rs.getString("plan_fingerprint"), rs.getBoolean("model_assisted"), rs.getString("created_by"),
                    rs.getString("approved_by"), instant(rs.getTimestamp("approved_at")), instant(rs.getTimestamp("canceled_at")),
                    instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")), steps(id));
        }, args);
    }

    public void approvePlan(long id, String actor) {
        int changed = jdbc.update("UPDATE forge_ai_agent_runs SET status='APPROVED',approved_by=?,approved_at=?,updated_at=? WHERE id=? AND status='AWAITING_PLAN_APPROVAL'",
                actor, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), id);
        if (changed == 0) throw ApiException.bad("Plan is not awaiting approval");
        event(id, "PLAN_APPROVED", actor, "Grounded plan approved for execution", json.createObjectNode());
    }

    public void setRunStatus(long id, String status) {
        jdbc.update("UPDATE forge_ai_agent_runs SET status=?,updated_at=? WHERE id=?", status, Timestamp.from(Instant.now()), id);
    }

    public void cancel(long id, String actor) {
        jdbc.update("UPDATE forge_ai_agent_runs SET status='CANCELED',canceled_at=?,updated_at=? WHERE id=?",
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), id);
        event(id, "RUN_CANCELED", actor, "Agent plan canceled", json.createObjectNode());
    }

    public void setStepStatus(long stepId, String status, JsonNode result, boolean start, boolean finish) {
        jdbc.update("UPDATE forge_ai_agent_steps SET status=?,result_json=?,started_at=CASE WHEN ? THEN COALESCE(started_at,?) ELSE started_at END," +
                        "finished_at=CASE WHEN ? THEN ? ELSE finished_at END,updated_at=? WHERE id=?",
                status, nullable(result), start, Timestamp.from(Instant.now()), finish, Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()), stepId);
    }

    public void feedback(long runId, Integer rating, Boolean accepted, JsonNode correction, String comment, String actor) {
        get(runId);
        if (rating != null && (rating < 1 || rating > 5)) throw ApiException.bad("Feedback rating must be between 1 and 5");
        jdbc.update("INSERT INTO forge_ai_plan_feedback(run_id,rating,accepted,correction_json,comment_text,submitted_by,created_at) VALUES (?,?,?,?,?,?,?)",
                runId, rating, accepted, nullable(correction), comment, actor, Timestamp.from(Instant.now()));
        event(runId, "PLAN_FEEDBACK", actor, "Plan feedback recorded", json.createObjectNode().put("accepted", Boolean.TRUE.equals(accepted)));
    }

    public void event(long runId, String type, String actor, String message, JsonNode detail) {
        jdbc.update("INSERT INTO forge_ai_agent_events(run_id,event_type,actor,message,detail_json,created_at) VALUES (?,?,?,?,?,?)",
                runId, type, actor, message, detail == null ? "{}" : detail.toString(), Timestamp.from(Instant.now()));
    }

    public List<Map<String, Object>> events(long runId) {
        get(runId);
        return jdbc.queryForList("SELECT id,event_type AS eventType,actor,message,detail_json AS detail,created_at AS createdAt " +
                "FROM forge_ai_agent_events WHERE run_id=? ORDER BY id", runId);
    }

    private List<StoredStep> steps(long runId) {
        return jdbc.query("SELECT * FROM forge_ai_agent_steps WHERE run_id=? ORDER BY ordinal_no", (rs, n) -> new StoredStep(
                rs.getLong("id"), rs.getLong("run_id"), rs.getInt("ordinal_no"), rs.getString("step_code"),
                rs.getString("title"), rs.getString("detail"), rs.getString("operation"), rs.getString("status"),
                rs.getBoolean("changes_data"), rs.getBoolean("requires_approval"), rs.getString("action_name"),
                readNullable(rs.getString("action_args_json")), rs.getString("action_summary"), read(rs.getString("evidence_json")),
                readNullable(rs.getString("result_json")), instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("finished_at"))), runId);
    }

    private StoredRun withSteps(StoredRun run, List<StoredStep> steps) {
        return new StoredRun(run.id(), run.goal(), run.status(), run.summary(), run.providerId(), run.modelName(), run.intent(),
                run.plan(), run.validation(), run.questions(), run.evidence(), run.confidence(), run.riskLevel(), run.fingerprint(),
                run.modelAssisted(), run.createdBy(), run.approvedBy(), run.approvedAt(), run.canceledAt(), run.createdAt(), run.updatedAt(), steps);
    }

    private void assertVisible(StoredRun run) {
        boolean allowed = AccessContext.current().map(p -> p.roles().contains("ADMIN") ||
                p.hasPermission("provision.approve") || run.createdBy().equalsIgnoreCase(p.username())).orElse(true);
        if (!allowed) throw ApiException.notFound("Agent run " + run.id() + " not found");
    }

    private String actor() { return AccessContext.current().map(p -> p.username()).orElse("system"); }

    private Number generatedId(KeyHolder keys) {
        if (keys.getKeys() != null && keys.getKeys().get("id") instanceof Number id) return id;
        if (!keys.getKeyList().isEmpty()) {
            Object value = keys.getKeyList().get(0).values().stream().filter(Number.class::isInstance).findFirst().orElse(null);
            if (value instanceof Number number) return number;
        }
        return null;
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Unable to serialize agent run", e); }
    }

    private JsonNode read(String value) {
        try { return value == null || value.isBlank() ? json.createArrayNode() : json.readTree(value); }
        catch (Exception e) { return json.createObjectNode().put("parseError", true); }
    }

    private JsonNode readNullable(String value) { return value == null || value.isBlank() ? null : read(value); }
    private String nullable(JsonNode value) { return value == null || value.isNull() ? null : value.toString(); }
    private Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
