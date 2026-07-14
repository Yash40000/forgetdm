package io.forgetdm.automation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.mapping.MappingExecutionService;
import io.forgetdm.mapping.MappingRunEntity;
import io.forgetdm.provision.DataScopeJobService;
import io.forgetdm.provision.SyntheticGenService;
import io.forgetdm.reservation.ReservationEntity;
import io.forgetdm.reservation.ReservationService;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessPrincipal;
import io.forgetdm.virtualization.VirtualizationService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class EnterpriseSelfServiceService {
    private static final Set<String> PRODUCT_TYPES = Set.of(
            "DATASCOPE", "SYNTHETIC", "MAPPING", "RESERVATION",
            "VDB_PROVISION", "VDB_REFRESH", "VDB_ROLLBACK");
    private static final Set<String> APPROVAL_MODES = Set.of("REQUIRED", "OPTIONAL", "NONE");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final DataScopeJobService dataScope;
    private final SyntheticGenService synthetic;
    private final MappingExecutionService mappings;
    private final ReservationService reservations;
    private final VirtualizationService virtualization;
    private final IntegrationWebhookService integrations;
    private final AuditService audit;

    public EnterpriseSelfServiceService(JdbcTemplate jdbc, ObjectMapper json, DataScopeJobService dataScope,
                                        SyntheticGenService synthetic, MappingExecutionService mappings,
                                        ReservationService reservations, VirtualizationService virtualization,
                                        IntegrationWebhookService integrations, AuditService audit) {
        this.jdbc = jdbc; this.json = json; this.dataScope = dataScope; this.synthetic = synthetic;
        this.mappings = mappings; this.reservations = reservations; this.virtualization = virtualization;
        this.integrations = integrations; this.audit = audit;
    }

    public record ProductRequest(String productType, String artifactId, Integer artifactVersion, String label,
                                 String description, String category, String tags, Boolean enabled,
                                 String approvalMode, JsonNode questionnaire, JsonNode guardrails,
                                 List<String> allowedEnvironments, String deliveryInstructions) {}
    public record OrderRequest(String productId, String purpose, String testType, String environment,
                               Map<String, Object> parameters, Long requestedVolume, String requestedVariety,
                               String deliveryMode, Boolean reservationRequested, Integer reservationHours,
                               Instant scheduleAt) {}
    public record Decision(String note) {}
    public record Comment(String message) {}

    public List<Map<String, Object>> catalog(String query, String category, String type) {
        syncLegacyDataScopeProducts();
        StringBuilder sql = new StringBuilder("SELECT * FROM self_service_products WHERE enabled=TRUE");
        List<Object> args = new ArrayList<>();
        if (clean(type) != null) { sql.append(" AND product_type=?"); args.add(type.trim().toUpperCase(Locale.ROOT)); }
        if (clean(category) != null) { sql.append(" AND LOWER(category)=LOWER(?)"); args.add(category.trim()); }
        if (clean(query) != null) {
            sql.append(" AND (LOWER(label) LIKE ? OR LOWER(COALESCE(description,'')) LIKE ? OR LOWER(COALESCE(tags,'')) LIKE ?)");
            String like = "%" + query.trim().toLowerCase(Locale.ROOT) + "%"; args.add(like); args.add(like); args.add(like);
        }
        sql.append(" ORDER BY category,label");
        return jdbc.query(sql.toString(), (rs, rowNum) -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", rs.getString("id")); out.put("productType", rs.getString("product_type"));
            out.put("artifactId", rs.getString("artifact_id")); out.put("artifactVersion", rs.getObject("artifact_version"));
            out.put("label", rs.getString("label")); out.put("description", rs.getString("description"));
            out.put("category", rs.getString("category")); out.put("tags", csv(rs.getString("tags")));
            out.put("ownerUsername", rs.getString("owner_username")); out.put("approvalMode", rs.getString("approval_mode"));
            out.put("questionnaire", parseMap(rs.getString("questionnaire_json")));
            out.put("guardrails", parseMap(rs.getString("guardrails_json")));
            out.put("allowedEnvironments", csv(rs.getString("allowed_environments")));
            out.put("deliveryInstructions", rs.getString("delivery_instructions"));
            out.put("updatedAt", instant(rs.getTimestamp("updated_at")));
            return out;
        }, args.toArray());
    }

    public List<Map<String, Object>> products() {
        requireManager();
        return jdbc.queryForList("SELECT id AS \"id\",product_type AS \"productType\",artifact_id AS \"artifactId\",label AS \"label\",category AS \"category\",enabled AS \"enabled\",approval_mode AS \"approvalMode\",owner_username AS \"ownerUsername\",updated_at AS \"updatedAt\" FROM self_service_products ORDER BY updated_at DESC");
    }

    public Map<String, Object> publish(ProductRequest request) {
        AccessPrincipal actor = requireManager();
        String type = upper(required(request == null ? null : request.productType(), "Product type"));
        if (!PRODUCT_TYPES.contains(type)) throw ApiException.bad("Unsupported self-service product type: " + type);
        String artifactId = required(request.artifactId(), "Artifact");
        validateArtifact(type, artifactId);
        String label = required(request.label(), "Catalog label");
        String approval = upper(clean(request.approvalMode()) == null ? "REQUIRED" : request.approvalMode());
        if (!APPROVAL_MODES.contains(approval)) throw ApiException.bad("Approval mode must be REQUIRED, OPTIONAL, or NONE");
        String questionnaire = objectJson(request.questionnaire());
        String guardrails = objectJson(request.guardrails());
        String environments = join(request.allowedEnvironments());
        String tags = clean(request.tags());
        Instant now = Instant.now();
        List<String> existing = jdbc.query("SELECT id FROM self_service_products WHERE product_type=? AND artifact_id=?", (rs, n) -> rs.getString(1), type, artifactId);
        String id = existing.isEmpty() ? UUID.randomUUID().toString() : existing.get(0);
        if (existing.isEmpty()) {
            jdbc.update("INSERT INTO self_service_products(id,product_type,artifact_id,artifact_version,label,description,category,tags,owner_user_id,owner_username,enabled,approval_mode,questionnaire_json,guardrails_json,allowed_environments,delivery_instructions,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, type, artifactId, request.artifactVersion(), label, clean(request.description()), clean(request.category()), tags,
                    actor.userId(), actor.username(), !Boolean.FALSE.equals(request.enabled()), approval, questionnaire, guardrails,
                    environments, clean(request.deliveryInstructions()), ts(now), ts(now));
        } else {
            jdbc.update("UPDATE self_service_products SET artifact_version=?,label=?,description=?,category=?,tags=?,enabled=?,approval_mode=?,questionnaire_json=?,guardrails_json=?,allowed_environments=?,delivery_instructions=?,updated_at=? WHERE id=?",
                    request.artifactVersion(), label, clean(request.description()), clean(request.category()), tags,
                    !Boolean.FALSE.equals(request.enabled()), approval, questionnaire, guardrails, environments,
                    clean(request.deliveryInstructions()), ts(now), id);
        }
        if ("DATASCOPE".equals(type)) jdbc.update("UPDATE datascope_saved_jobs SET self_service_enabled=TRUE,self_service_label=? WHERE id=?", label, artifactId);
        audit.log(actor.username(), "SELF_SERVICE_PRODUCT_PUBLISHED", "product=" + id + " type=" + type + " artifact=" + artifactId);
        return product(id);
    }

    public Map<String, Object> setEnabled(String id, boolean enabled) {
        AccessPrincipal actor = requireManager();
        int changed = jdbc.update("UPDATE self_service_products SET enabled=?,updated_at=? WHERE id=?", enabled, ts(Instant.now()), id);
        if (changed == 0) throw ApiException.notFound("Self-service product " + id + " not found");
        audit.log(actor.username(), enabled ? "SELF_SERVICE_PRODUCT_ENABLED" : "SELF_SERVICE_PRODUCT_DISABLED", "product=" + id);
        return product(id);
    }

    public List<Map<String, Object>> candidates() {
        requireManager();
        List<Map<String, Object>> out = new ArrayList<>();
        out.addAll(candidateRows("DATASCOPE", "SELECT id,name,description FROM datascope_saved_jobs ORDER BY updated_at DESC"));
        out.addAll(candidateRows("SYNTHETIC", "SELECT id,name,description FROM synthetic_saved_jobs WHERE approval_status='APPROVED' ORDER BY updated_at DESC"));
        out.addAll(candidateRows("MAPPING", "SELECT CAST(id AS VARCHAR) AS id,name,description FROM mapping_definitions ORDER BY updated_at DESC"));
        out.addAll(candidateRows("VDB_PROVISION", "SELECT CAST(id AS VARCHAR) AS id,name,note AS description FROM virtual_snapshots ORDER BY created_at DESC"));
        out.addAll(candidateRows("VDB_REFRESH", "SELECT CAST(id AS VARCHAR) AS id,name,'' AS description FROM virtual_databases ORDER BY created_at DESC"));
        out.addAll(candidateRows("VDB_ROLLBACK", "SELECT CAST(id AS VARCHAR) AS id,name,'' AS description FROM virtual_databases ORDER BY created_at DESC"));
        return out;
    }

    @Transactional
    public Map<String, Object> request(OrderRequest request) {
        AccessPrincipal actor = current();
        Map<String, Object> product = product(required(request == null ? null : request.productId(), "Product"));
        if (!Boolean.TRUE.equals(product.get("enabled"))) throw ApiException.bad("This catalog product is not currently available");
        String purpose = required(request.purpose(), "Test objective / business purpose");
        if (purpose.length() > 1000) throw ApiException.bad("Purpose must be 1000 characters or fewer");
        validateOrder(request, product);
        String approvalMode = String.valueOf(product.get("approvalMode"));
        String status = "NONE".equals(approvalMode) ? "APPROVED" : "PENDING_APPROVAL";
        String id = UUID.randomUUID().toString(); Instant now = Instant.now();
        jdbc.update("INSERT INTO self_service_orders(id,product_id,product_type,artifact_id,product_label,requested_by_id,requested_by,purpose,test_type,environment,parameters_json,requested_volume,requested_variety,delivery_mode,reservation_requested,reservation_hours,schedule_at,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, product.get("id"), product.get("productType"), product.get("artifactId"), product.get("label"),
                actor.userId(), actor.username(), purpose, clean(request.testType()), clean(request.environment()),
                toJson(request.parameters() == null ? Map.of() : request.parameters()), request.requestedVolume(), clean(request.requestedVariety()),
                clean(request.deliveryMode()), Boolean.TRUE.equals(request.reservationRequested()), request.reservationHours(),
                request.scheduleAt() == null ? null : ts(request.scheduleAt()), status, ts(now), ts(now));
        event(id, "REQUESTED", actor.username(), purpose, Map.of("status", status));
        audit.log(actor.username(), "SELF_SERVICE_ORDER_REQUESTED", "order=" + id + " product=" + product.get("id"));
        integrations.emit("SELF_SERVICE_REQUESTED", Map.of("requestId", id, "productId", product.get("id"), "requestedBy", actor.username(), "purpose", purpose));
        return get(id);
    }

    public List<Map<String, Object>> orders() {
        AccessPrincipal actor = current();
        String sql = orderSelect();
        if (actor.hasPermission("provision.approve") || actor.hasPermission("admin.all"))
            return jdbc.queryForList(sql + " ORDER BY o.created_at DESC");
        return jdbc.queryForList(sql + " WHERE o.requested_by_id=? ORDER BY o.created_at DESC", actor.userId());
    }

    public Map<String, Object> get(String id) {
        AccessPrincipal actor = current();
        Map<String, Object> order = rawOrderView(id);
        long owner = ((Number) order.get("requestedById")).longValue();
        if (actor.userId() != owner && !actor.hasPermission("provision.approve") && !actor.hasPermission("admin.all"))
            throw ApiException.forbidden("You cannot view this self-service request");
        order.put("events", events(id));
        return order;
    }

    @Transactional
    public Map<String, Object> decide(String id, boolean approve, Decision decision) {
        AccessPrincipal actor = current();
        if (!actor.hasPermission("provision.approve") && !actor.hasPermission("admin.all")) throw ApiException.forbidden("Provision approval permission is required");
        Map<String, Object> order = rawOrder(id);
        if (!"PENDING_APPROVAL".equals(order.get("status"))) throw ApiException.bad("Request is not awaiting approval");
        if (actor.userId() == ((Number) order.get("requested_by_id")).longValue()) throw ApiException.bad("Maker-checker approval requires a different user");
        String note = required(decision == null ? null : decision.note(), approve ? "Approval note / e-signature reason" : "Rejection reason");
        String status = approve ? "APPROVED" : "REJECTED"; Instant now = Instant.now();
        jdbc.update("UPDATE self_service_orders SET status=?,decision_by_id=?,decision_by=?,decision_note=?,decided_at=?,updated_at=? WHERE id=? AND status='PENDING_APPROVAL'",
                status, actor.userId(), actor.username(), note, ts(now), ts(now), id);
        event(id, status, actor.username(), note, Map.of());
        audit.log(actor.username(), "SELF_SERVICE_ORDER_" + status, "order=" + id);
        integrations.emit("SELF_SERVICE_" + status, Map.of("requestId", id, "decisionBy", actor.username(), "note", note));
        return get(id);
    }

    @Transactional
    public Map<String, Object> fulfill(String id) {
        AccessPrincipal actor = current(); Map<String, Object> order = rawOrder(id);
        long requester = ((Number) order.get("requested_by_id")).longValue();
        if (actor.userId() != requester && !actor.hasPermission("admin.all")) throw ApiException.forbidden("Only the requester or an administrator can launch this request");
        if (!"APPROVED".equals(order.get("status"))) throw ApiException.bad("Request must be approved before launch");
        Timestamp scheduled = (Timestamp) order.get("schedule_at");
        if (scheduled != null && scheduled.toInstant().isAfter(Instant.now())) throw ApiException.bad("This request is scheduled for " + scheduled.toInstant());
        Map<String, Object> product = product(String.valueOf(order.get("product_id")));
        if (!Boolean.TRUE.equals(product.get("enabled"))) throw ApiException.bad("This catalog product has been disabled");
        Map<String, Object> parameters = parseMap((String) order.get("parameters_json"));
        Execution execution = execute(String.valueOf(order.get("product_type")), String.valueOf(order.get("artifact_id")), parameters, order);
        Instant now = Instant.now();
        jdbc.update("UPDATE self_service_orders SET status='FULFILLED',run_type=?,run_ref=?,result_json=?,fulfilled_at=?,updated_at=? WHERE id=? AND status='APPROVED'",
                execution.type(), execution.reference(), toJson(execution.result()), ts(now), ts(now), id);
        event(id, "FULFILLED", actor.username(), "Execution submitted", execution.result());
        audit.log(actor.username(), "SELF_SERVICE_ORDER_FULFILLED", "order=" + id + " run=" + execution.reference());
        integrations.emit("SELF_SERVICE_FULFILLED", Map.of("requestId", id, "runId", execution.reference(), "runType", execution.type()));
        return get(id);
    }

    public Map<String, Object> cancel(String id, Comment reason) {
        AccessPrincipal actor = current(); Map<String, Object> order = rawOrder(id);
        long requester = ((Number) order.get("requested_by_id")).longValue();
        if (actor.userId() != requester && !actor.hasPermission("admin.all")) throw ApiException.forbidden("You cannot cancel this request");
        if (!Set.of("PENDING_APPROVAL", "APPROVED").contains(String.valueOf(order.get("status")))) throw ApiException.bad("Only pending or approved requests can be canceled");
        String note = required(reason == null ? null : reason.message(), "Cancellation reason");
        jdbc.update("UPDATE self_service_orders SET status='CANCELED',canceled_at=?,updated_at=? WHERE id=?", ts(Instant.now()), ts(Instant.now()), id);
        event(id, "CANCELED", actor.username(), note, Map.of());
        return get(id);
    }

    public Map<String, Object> comment(String id, Comment comment) {
        get(id); AccessPrincipal actor = current();
        event(id, "COMMENT", actor.username(), required(comment == null ? null : comment.message(), "Comment"), Map.of());
        return get(id);
    }

    public Map<String, Object> runner(String id) {
        Map<String, Object> order = get(id);
        String path = "/api/self-service/v2/orders/" + id;
        String curl = "curl -sS -X POST \"$FORGETDM_URL" + path + "/fulfill\" -H \"Authorization: Bearer $FORGETDM_TOKEN\" -H \"Content-Type: application/json\" -d '{}'";
        String status = "curl -sS \"$FORGETDM_URL" + path + "\" -H \"Authorization: Bearer $FORGETDM_TOKEN\"";
        return Map.of("requestId", id, "product", order.get("productLabel"), "launchCommand", curl, "statusCommand", status,
                "note", "Use a personal API token with provision.run; secret values are never embedded in the command.");
    }

    public Map<String, Object> metrics() {
        AccessPrincipal actor = current();
        List<Map<String, Object>> orders = orders();
        Map<String, Long> statuses = new LinkedHashMap<>();
        long completed = 0, seconds = 0;
        for (Map<String, Object> order : orders) {
            statuses.merge(String.valueOf(order.get("status")), 1L, Long::sum);
            Instant created = toInstant(order.get("createdAt")); Instant fulfilled = toInstant(order.get("fulfilledAt"));
            if (created != null && fulfilled != null) { completed++; seconds += Math.max(0, Duration.between(created, fulfilled).toSeconds()); }
        }
        return Map.of("visibleRequests", orders.size(), "statusCounts", statuses,
                "averageFulfillmentSeconds", completed == 0 ? 0 : seconds / completed,
                "scope", actor.hasPermission("provision.approve") || actor.hasPermission("admin.all") ? "TEAM" : "PERSONAL");
    }

    private Execution execute(String type, String artifactId, Map<String, Object> p, Map<String, Object> order) {
        return switch (type) {
            case "DATASCOPE" -> execution("DATASCOPE", dataScope.runSelfService(artifactId, p));
            case "SYNTHETIC" -> execution("SYNTHETIC", synthetic.runSelfServiceSavedJob(artifactId));
            case "MAPPING" -> {
                ObjectNode request = json.valueToTree(p);
                String mappingName = jdbc.queryForObject("SELECT name FROM mapping_definitions WHERE id=?", String.class, Long.valueOf(artifactId));
                request.put("confirmation", mappingName); request.putIfAbsent("seed", json.getNodeFactory().textNode("self-service-" + order.get("id")));
                MappingRunEntity run = mappings.start(Long.valueOf(artifactId), request);
                yield new Execution("MAPPING", String.valueOf(run.getId()), Map.of("runId", run.getId(), "status", run.getStatus()));
            }
            case "RESERVATION" -> {
                ReservationEntity reservation = reservations.findAndReserve(longParam(p, "dataSourceId"), required(str(p.get("table")), "Table"),
                        str(p.get("criteria")), intParam(p, "count", 1), current().username(), String.valueOf(order.get("purpose")),
                        intParam(p, "ttlHours", 24));
                yield new Execution("RESERVATION", String.valueOf(reservation.getId()), Map.of("reservationId", reservation.getId(), "status", reservation.getStatus(), "expiresAt", reservation.getExpiresAt().toString()));
            }
            case "VDB_PROVISION" -> execution("VIRTUALIZATION", virtualization.startProvision(Long.valueOf(artifactId), required(str(p.get("name")), "VDB name"), nullableLong(p.get("targetDataSourceId")), str(p.get("pointInTime")), nullableLong(p.get("environmentId"))));
            case "VDB_REFRESH" -> execution("VIRTUALIZATION", virtualization.startRefresh(Long.valueOf(artifactId), longParam(p, "snapshotId")));
            case "VDB_ROLLBACK" -> execution("VIRTUALIZATION", virtualization.startRewind(Long.valueOf(artifactId), longParam(p, "snapshotId")));
            default -> throw ApiException.bad("Unsupported self-service product type: " + type);
        };
    }

    private Execution execution(String type, Map<String, Object> result) {
        Object ref = result.get("runId"); if (ref == null) ref = result.get("id"); if (ref == null) ref = result.get("opId");
        return new Execution(type, ref == null ? "submitted" : String.valueOf(ref), result);
    }
    private record Execution(String type, String reference, Map<String, Object> result) {}

    private void validateOrder(OrderRequest request, Map<String, Object> product) {
        List<String> allowed = (List<String>) product.getOrDefault("allowedEnvironments", List.of());
        if (!allowed.isEmpty() && clean(request.environment()) != null && allowed.stream().noneMatch(e -> e.equalsIgnoreCase(request.environment())))
            throw ApiException.bad("Environment is outside this product's published guardrails");
        Map<String, Object> guardrails = (Map<String, Object>) product.getOrDefault("guardrails", Map.of());
        long maxVolume = number(guardrails.get("maxVolume"), 0);
        if (request.requestedVolume() != null && request.requestedVolume() < 1) throw ApiException.bad("Requested volume must be positive");
        if (maxVolume > 0 && request.requestedVolume() != null && request.requestedVolume() > maxVolume)
            throw ApiException.bad("Requested volume exceeds the published maximum of " + maxVolume);
        int maxReservation = (int) number(guardrails.get("maxReservationHours"), 168);
        if (Boolean.TRUE.equals(request.reservationRequested()) && (request.reservationHours() == null || request.reservationHours() < 1 || request.reservationHours() > maxReservation))
            throw ApiException.bad("Reservation duration must be between 1 and " + maxReservation + " hours");
        if (request.scheduleAt() != null && request.scheduleAt().isBefore(Instant.now())) throw ApiException.bad("Scheduled time must be in the future");
    }

    private void validateArtifact(String type, String id) {
        String sql = switch (type) {
            case "DATASCOPE" -> "SELECT COUNT(*) FROM datascope_saved_jobs WHERE id=?";
            case "SYNTHETIC" -> "SELECT COUNT(*) FROM synthetic_saved_jobs WHERE id=? AND approval_status='APPROVED'";
            case "MAPPING" -> "SELECT COUNT(*) FROM mapping_definitions WHERE id=?";
            case "VDB_PROVISION" -> "SELECT COUNT(*) FROM virtual_snapshots WHERE id=?";
            case "VDB_REFRESH", "VDB_ROLLBACK" -> "SELECT COUNT(*) FROM virtual_databases WHERE id=?";
            case "RESERVATION" -> "SELECT COUNT(*) FROM data_sources WHERE id=?";
            default -> throw ApiException.bad("Unsupported product type");
        };
        Object key = Set.of("MAPPING", "VDB_PROVISION", "VDB_REFRESH", "VDB_ROLLBACK", "RESERVATION").contains(type) ? Long.valueOf(id) : id;
        Integer count = jdbc.queryForObject(sql, Integer.class, key);
        if (count == null || count == 0) throw ApiException.notFound("Eligible " + type + " artifact " + id + " not found");
    }

    /** Keep previously published DataScope templates visible after the catalog upgrade. */
    private void syncLegacyDataScopeProducts() {
        try {
            List<Map<String, Object>> legacy = jdbc.queryForList("SELECT id,name,description,COALESCE(self_service_label,name) AS label,owner_user_id,owner_username,updated_at FROM datascope_saved_jobs WHERE self_service_enabled=TRUE");
            for (Map<String, Object> row : legacy) {
                String artifact = String.valueOf(row.get("id"));
                Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM self_service_products WHERE product_type='DATASCOPE' AND artifact_id=?", Integer.class, artifact);
                if (count != null && count > 0) continue;
                Instant now = toInstant(row.get("updated_at")); if (now == null) now = Instant.now();
                jdbc.update("INSERT INTO self_service_products(id,product_type,artifact_id,label,description,category,tags,owner_user_id,owner_username,enabled,approval_mode,questionnaire_json,guardrails_json,allowed_environments,delivery_instructions,created_at,updated_at) VALUES (?,'DATASCOPE',?,?,?,?,?,?,?,?,?,'{}','{}','DEV,QA,UAT,PERFORMANCE,TRAINING',?,?,?)",
                        UUID.randomUUID().toString(), artifact, String.valueOf(row.get("label")), clean(str(row.get("description"))),
                        "Masked subsets", "subset,masking,provision", row.get("owner_user_id"), Objects.toString(row.get("owner_username"), "system"),
                        true, "REQUIRED", "Published DataScope template. Protected source, target, policy, and relationship settings cannot be changed.", ts(now), ts(now));
            }
        } catch (Exception ignored) { /* Migration/startup ordering: retry on the next catalog read. */ }
    }

    private Map<String, Object> product(String id) {
        List<Map<String, Object>> rows = jdbc.query("SELECT * FROM self_service_products WHERE id=?", (rs, n) -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", rs.getString("id")); out.put("productType", rs.getString("product_type")); out.put("artifactId", rs.getString("artifact_id"));
            out.put("label", rs.getString("label")); out.put("description", rs.getString("description")); out.put("category", rs.getString("category"));
            out.put("enabled", rs.getBoolean("enabled")); out.put("approvalMode", rs.getString("approval_mode"));
            out.put("questionnaire", parseMap(rs.getString("questionnaire_json"))); out.put("guardrails", parseMap(rs.getString("guardrails_json")));
            out.put("allowedEnvironments", csv(rs.getString("allowed_environments"))); out.put("deliveryInstructions", rs.getString("delivery_instructions"));
            return out;
        }, id);
        if (rows.isEmpty()) throw ApiException.notFound("Self-service product " + id + " not found");
        return rows.get(0);
    }

    private List<Map<String, Object>> candidateRows(String type, String sql) {
        try { return jdbc.query(sql, (rs, n) -> Map.of("productType", type, "artifactId", rs.getString("id"), "name", rs.getString("name"), "description", Objects.toString(rs.getString("description"), ""))); }
        catch (Exception ignored) { return List.of(); }
    }
    private List<Map<String, Object>> events(String id) { return jdbc.queryForList("SELECT event_type AS \"eventType\",actor AS \"actor\",message AS \"message\",detail_json AS \"detailJson\",created_at AS \"createdAt\" FROM self_service_order_events WHERE order_id=? ORDER BY created_at", id); }
    private void event(String id, String type, String actor, String message, Map<String, Object> detail) { jdbc.update("INSERT INTO self_service_order_events(order_id,event_type,actor,message,detail_json,created_at) VALUES (?,?,?,?,?,?)", id, type, actor, clean(message), toJson(detail), ts(Instant.now())); }
    private Map<String, Object> rawOrder(String id) { List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM self_service_orders WHERE id=?", id); if (rows.isEmpty()) throw ApiException.notFound("Self-service request " + id + " not found"); return rows.get(0); }
    private Map<String, Object> rawOrderView(String id) { List<Map<String, Object>> rows = jdbc.queryForList(orderSelect() + " WHERE o.id=?", id); if (rows.isEmpty()) throw ApiException.notFound("Self-service request " + id + " not found"); return rows.get(0); }
    private static String orderSelect() { return "SELECT o.id AS \"id\",o.product_id AS \"productId\",o.product_type AS \"productType\",o.artifact_id AS \"artifactId\",o.product_label AS \"productLabel\",o.requested_by_id AS \"requestedById\",o.requested_by AS \"requestedBy\",o.purpose AS \"purpose\",o.test_type AS \"testType\",o.environment AS \"environment\",o.parameters_json AS \"parametersJson\",o.requested_volume AS \"requestedVolume\",o.requested_variety AS \"requestedVariety\",o.delivery_mode AS \"deliveryMode\",o.reservation_requested AS \"reservationRequested\",o.reservation_hours AS \"reservationHours\",o.schedule_at AS \"scheduleAt\",o.status AS \"status\",o.decision_by AS \"decisionBy\",o.decision_note AS \"decisionNote\",o.run_type AS \"runType\",o.run_ref AS \"runRef\",o.result_json AS \"resultJson\",o.created_at AS \"createdAt\",o.decided_at AS \"decidedAt\",o.fulfilled_at AS \"fulfilledAt\",o.updated_at AS \"updatedAt\" FROM self_service_orders o"; }

    private AccessPrincipal requireManager() { AccessPrincipal p = current(); if (!p.hasPermission("datascope.manage") && !p.hasPermission("admin.all")) throw ApiException.forbidden("Catalog management permission is required"); return p; }
    private static AccessPrincipal current() { return AccessContext.current().orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Login required")); }
    private String objectJson(JsonNode node) { if (node == null || node.isNull()) return "{}"; if (!node.isObject()) throw ApiException.bad("Questionnaire and guardrails must be JSON objects"); return node.toString(); }
    private String toJson(Object value) { try { return json.writeValueAsString(value); } catch (Exception e) { throw ApiException.bad("Could not serialize self-service configuration"); } }
    private Map<String, Object> parseMap(String value) { if (value == null || value.isBlank()) return new LinkedHashMap<>(); try { return json.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() {}); } catch (Exception e) { return new LinkedHashMap<>(); } }
    private static long longParam(Map<String, Object> p, String key) { Long value = nullableLong(p.get(key)); if (value == null) throw ApiException.bad(key + " is required"); return value; }
    private static int intParam(Map<String, Object> p, String key, int fallback) { Object value = p.get(key); return value == null || String.valueOf(value).isBlank() ? fallback : Integer.parseInt(String.valueOf(value)); }
    private static Long nullableLong(Object value) { return value == null || String.valueOf(value).isBlank() ? null : Long.valueOf(String.valueOf(value)); }
    private static long number(Object value, long fallback) { if (value == null) return fallback; try { return Long.parseLong(String.valueOf(value)); } catch (Exception e) { return fallback; } }
    private static String str(Object value) { return value == null ? null : String.valueOf(value); }
    private static String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String required(String value, String label) { String clean = clean(value); if (clean == null) throw ApiException.bad(label + " is required"); return clean; }
    private static String upper(String value) { return value.toUpperCase(Locale.ROOT); }
    private static String join(List<String> values) { return values == null ? null : String.join(",", values.stream().map(EnterpriseSelfServiceService::clean).filter(Objects::nonNull).toList()); }
    private static List<String> csv(String value) { return value == null || value.isBlank() ? List.of() : Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList(); }
    private static Timestamp ts(Instant value) { return Timestamp.from(value); }
    private static String instant(Timestamp value) { return value == null ? null : value.toInstant().toString(); }
    private static Instant toInstant(Object value) { if (value instanceof Timestamp t) return t.toInstant(); if (value instanceof Instant i) return i; if (value == null) return null; try { return Instant.parse(String.valueOf(value)); } catch (Exception e) { return null; } }
}
