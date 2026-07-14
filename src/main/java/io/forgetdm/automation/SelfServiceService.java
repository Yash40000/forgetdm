package io.forgetdm.automation;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.provision.DataScopeJobService;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SelfServiceService {
    private final JdbcTemplate jdbc;
    private final DataScopeJobService jobs;
    private final IntegrationWebhookService integrations;
    private final AuditService audit;

    public SelfServiceService(JdbcTemplate jdbc, DataScopeJobService jobs,
                              IntegrationWebhookService integrations, AuditService audit) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.integrations = integrations;
        this.audit = audit;
    }

    public record PublishRequest(Boolean enabled, String label) { }
    public record RequestData(String templateId, String purpose, String environment) { }
    public record Decision(String note) { }

    public List<Map<String, Object>> catalog() {
        return jdbc.queryForList("SELECT id, COALESCE(self_service_label,name) AS label, name, description, " +
                "owner_username AS \"ownerUsername\", updated_at AS \"updatedAt\" FROM datascope_saved_jobs " +
                "WHERE self_service_enabled=TRUE ORDER BY COALESCE(self_service_label,name)");
    }

    public Map<String, Object> publish(String templateId, PublishRequest request) {
        AccessPrincipal actor = current();
        List<Map<String, Object>> templates = jdbc.queryForList(
                "SELECT owner_user_id,owner_username FROM datascope_saved_jobs WHERE id=?", templateId);
        if (templates.isEmpty()) throw ApiException.notFound("DataScope saved job " + templateId + " not found");
        Map<String, Object> template = templates.get(0);
        Object ownerId = template.get("owner_user_id");
        String ownerName = String.valueOf(template.get("owner_username"));
        boolean owns = ownerId instanceof Number number && number.longValue() == actor.userId()
                || ownerName.equalsIgnoreCase(actor.username());
        if (!owns && !actor.hasPermission("admin.all"))
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the saved-job owner or an administrator can publish this template");
        boolean enabled = request != null && Boolean.TRUE.equals(request.enabled());
        String label = request == null || request.label() == null || request.label().isBlank() ? null : request.label().trim();
        int changed = jdbc.update("UPDATE datascope_saved_jobs SET self_service_enabled=?,self_service_label=?,updated_at=? WHERE id=?",
                enabled, label, ts(Instant.now()), templateId);
        if (changed == 0) throw ApiException.notFound("DataScope saved job " + templateId + " not found");
        audit.log(actor.username(), enabled ? "SELF_SERVICE_TEMPLATE_PUBLISHED" : "SELF_SERVICE_TEMPLATE_UNPUBLISHED", "template=" + templateId);
        return Map.of("templateId", templateId, "enabled", enabled, "label", label == null ? "" : label);
    }

    @Transactional
    public Map<String, Object> request(RequestData request) {
        AccessPrincipal actor = current();
        String templateId = required(request == null ? null : request.templateId(), "Template");
        String purpose = required(request.purpose(), "Business purpose");
        if (purpose.length() > 1000) throw ApiException.bad("Business purpose must be 1000 characters or fewer");
        List<String> templates = jdbc.query("SELECT id FROM datascope_saved_jobs WHERE id=? AND self_service_enabled=TRUE",
                (rs, n) -> rs.getString(1), templateId);
        if (templates.isEmpty()) throw ApiException.notFound("Published self-service template " + templateId + " not found");
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO self_service_requests(id,template_id,requested_by_id,requested_by,purpose,environment,status,created_at,updated_at) " +
                        "VALUES (?,?,?,?,?,?,'PENDING_APPROVAL',?,?)", id, templateId, actor.userId(), actor.username(), purpose,
                clean(request.environment()), ts(now), ts(now));
        audit.log(actor.username(), "SELF_SERVICE_REQUESTED", "request=" + id + " template=" + templateId);
        integrations.emit("SELF_SERVICE_REQUESTED", Map.of("requestId", id, "templateId", templateId,
                "requestedBy", actor.username(), "purpose", purpose));
        return get(id);
    }

    public List<Map<String, Object>> list() {
        AccessPrincipal actor = current();
        String select = baseSelect();
        if (actor.hasPermission("provision.approve") || actor.hasPermission("admin.all"))
            return jdbc.queryForList(select + " ORDER BY r.created_at DESC");
        return jdbc.queryForList(select + " WHERE r.requested_by_id=? ORDER BY r.created_at DESC", actor.userId());
    }

    @Transactional
    public Map<String, Object> decide(String id, boolean approve, Decision decision) {
        AccessPrincipal actor = current();
        if (!actor.hasPermission("provision.approve")) throw new ApiException(HttpStatus.FORBIDDEN, "Provision approval permission is required");
        Map<String, Object> row = raw(id);
        if (!"PENDING_APPROVAL".equals(row.get("status"))) throw ApiException.bad("Request is not awaiting approval");
        if (actor.userId().equals(((Number) row.get("requested_by_id")).longValue()))
            throw ApiException.bad("Maker-checker approval requires a different user than the requester");
        String note = required(decision == null ? null : decision.note(), approve ? "Approval note" : "Rejection reason");
        String status = approve ? "APPROVED" : "REJECTED";
        Instant now = Instant.now();
        jdbc.update("UPDATE self_service_requests SET status=?,decision_by_id=?,decision_by=?,decision_note=?,decided_at=?,updated_at=? " +
                        "WHERE id=? AND status='PENDING_APPROVAL'", status, actor.userId(), actor.username(), note, ts(now), ts(now), id);
        audit.log(actor.username(), "SELF_SERVICE_" + status, "request=" + id + " note=" + note);
        integrations.emit("SELF_SERVICE_" + status, Map.of("requestId", id, "decisionBy", actor.username(), "note", note));
        return get(id);
    }

    @Transactional
    public Map<String, Object> fulfill(String id) {
        AccessPrincipal actor = current();
        Map<String, Object> row = raw(id);
        long requesterId = ((Number) row.get("requested_by_id")).longValue();
        if (actor.userId() != requesterId && !actor.hasPermission("admin.all"))
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the requester or an administrator can launch this approved request");
        if (!"APPROVED".equals(row.get("status"))) throw ApiException.bad("Request must be approved before it can run");
        Map<String, Object> run = jobs.runSelfService(String.valueOf(row.get("template_id")));
        Object runId = run.get("runId");
        Instant now = Instant.now();
        jdbc.update("UPDATE self_service_requests SET status='FULFILLED',run_id=?,fulfilled_at=?,updated_at=? WHERE id=? AND status='APPROVED'",
                runId, ts(now), ts(now), id);
        audit.log(actor.username(), "SELF_SERVICE_FULFILLED", "request=" + id + " run=" + runId);
        integrations.emit("SELF_SERVICE_FULFILLED", Map.of("requestId", id, "runId", runId == null ? "" : runId,
                "runStatus", String.valueOf(run.get("status"))));
        Map<String, Object> out = new LinkedHashMap<>(get(id));
        out.put("run", run);
        return out;
    }

    public Map<String, Object> get(String id) {
        AccessPrincipal actor = current();
        Map<String, Object> row = view(id);
        long requesterId = ((Number) row.get("requestedById")).longValue();
        if (actor.userId() != requesterId && !actor.hasPermission("provision.approve") && !actor.hasPermission("admin.all"))
            throw new ApiException(HttpStatus.FORBIDDEN, "You cannot view this request");
        return row;
    }

    private Map<String, Object> view(String id) {
        List<Map<String, Object>> rows = jdbc.queryForList(baseSelect() + " WHERE r.id=?", id);
        if (rows.isEmpty()) throw ApiException.notFound("Self-service request " + id + " not found");
        return rows.get(0);
    }

    private Map<String, Object> raw(String id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM self_service_requests WHERE id=?", id);
        if (rows.isEmpty()) throw ApiException.notFound("Self-service request " + id + " not found");
        return rows.get(0);
    }

    private static String baseSelect() {
        return "SELECT r.id AS \"id\",r.template_id AS \"templateId\",j.name AS \"templateName\",r.requested_by_id AS \"requestedById\", " +
                "r.requested_by AS \"requestedBy\",r.purpose AS \"purpose\",r.environment AS \"environment\",r.status AS \"status\",r.decision_by AS \"decisionBy\", " +
                "r.decision_note AS \"decisionNote\",r.run_id AS \"runId\",r.created_at AS \"createdAt\", " +
                "r.decided_at AS \"decidedAt\",r.fulfilled_at AS \"fulfilledAt\",r.updated_at AS \"updatedAt\" " +
                "FROM self_service_requests r JOIN datascope_saved_jobs j ON j.id=r.template_id";
    }

    private static AccessPrincipal current() { return AccessContext.current().orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Login required")); }
    private static String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String required(String value, String label) { String clean = clean(value); if (clean == null) throw ApiException.bad(label + " is required"); return clean; }
    private static Timestamp ts(Instant value) { return Timestamp.from(value); }
}
