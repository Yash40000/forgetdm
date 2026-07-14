package io.forgetdm.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.platform.ClusterLeaseService;
import io.forgetdm.security.AccessContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class IntegrationWebhookService {
    private static final Set<String> KINDS = Set.of("GENERIC", "JIRA", "SERVICENOW", "AZURE_DEVOPS");
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ClusterLeaseService leases;
    private final ObjectMapper json;
    private final HttpClient http;
    private final boolean allowHttp;

    public IntegrationWebhookService(JdbcTemplate jdbc, AuditService audit, ClusterLeaseService leases,
                                     ObjectMapper json,
                                     @Value("${forgetdm.integrations.allow-http:false}") boolean allowHttp) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.leases = leases;
        this.json = json;
        this.allowHttp = allowHttp;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public record EndpointRequest(String name, String kind, String url, String eventTypes, String secretEnv, Boolean enabled) { }

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("SELECT id, name, kind, url, event_types AS \"eventTypes\", secret_env AS \"secretEnv\", " +
                "enabled, created_by AS \"createdBy\", created_at AS \"createdAt\", updated_at AS \"updatedAt\" " +
                "FROM integration_endpoints ORDER BY name");
    }

    public List<Map<String, Object>> deliveries(String endpointId, Integer requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit == null ? 100 : requestedLimit, 500));
        String select = "SELECT o.id,o.endpoint_id AS \"endpointId\",e.name AS \"endpointName\"," +
                "o.event_type AS \"eventType\",o.status,o.attempts,o.next_attempt_at AS \"nextAttemptAt\"," +
                "o.delivered_at AS \"deliveredAt\",o.last_error AS \"lastError\"," +
                "o.created_at AS \"createdAt\",o.updated_at AS \"updatedAt\" " +
                "FROM integration_outbox o JOIN integration_endpoints e ON e.id=o.endpoint_id ";
        if (endpointId == null || endpointId.isBlank()) {
            return jdbc.queryForList(select + "ORDER BY o.created_at DESC LIMIT " + limit);
        }
        return jdbc.queryForList(select + "WHERE o.endpoint_id=? ORDER BY o.created_at DESC LIMIT " + limit,
                endpointId.trim());
    }

    public Map<String, Object> save(String id, EndpointRequest request) {
        String name = required(request == null ? null : request.name(), "Endpoint name");
        String kind = required(request.kind(), "Endpoint kind").toUpperCase(Locale.ROOT);
        if (!KINDS.contains(kind)) throw ApiException.bad("kind must be GENERIC, JIRA, SERVICENOW, or AZURE_DEVOPS");
        String url = validateUrl(request.url());
        String events = clean(request.eventTypes());
        String secretEnv = clean(request.secretEnv());
        boolean enabled = request.enabled() == null || request.enabled();
        Instant now = Instant.now();
        String actor = AccessContext.current().map(p -> p.username()).orElse("system");
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO integration_endpoints(id,name,kind,url,event_types,secret_env,enabled,created_by,created_at,updated_at) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?)", id, name, kind, url, events, secretEnv, enabled, actor, ts(now), ts(now));
            audit.log(actor, "INTEGRATION_ENDPOINT_CREATED", "endpoint=" + id + " kind=" + kind);
        } else {
            int changed = jdbc.update("UPDATE integration_endpoints SET name=?,kind=?,url=?,event_types=?,secret_env=?,enabled=?,updated_at=? WHERE id=?",
                    name, kind, url, events, secretEnv, enabled, ts(now), id);
            if (changed == 0) throw ApiException.notFound("Integration endpoint " + id + " not found");
            audit.log(actor, "INTEGRATION_ENDPOINT_UPDATED", "endpoint=" + id + " kind=" + kind);
        }
        return find(id);
    }

    public void delete(String id) {
        if (jdbc.update("DELETE FROM integration_endpoints WHERE id = ?", id) == 0)
            throw ApiException.notFound("Integration endpoint " + id + " not found");
        audit.log(AccessContext.current().map(p -> p.username()).orElse("system"), "INTEGRATION_ENDPOINT_DELETED", "endpoint=" + id);
    }

    public void emit(String eventType, Map<String, Object> data) {
        String event = required(eventType, "Event type").toUpperCase(Locale.ROOT);
        Instant now = Instant.now();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", event);
        envelope.put("occurredAt", now.toString());
        envelope.put("tenant", System.getenv().getOrDefault("FORGETDM_TENANT_ID", "default"));
        envelope.put("data", data == null ? Map.of() : data);
        String payload;
        try { payload = json.writeValueAsString(envelope); }
        catch (Exception e) { throw new IllegalStateException("Cannot serialize integration event", e); }
        for (Map<String, Object> endpoint : jdbc.queryForList("SELECT id,event_types FROM integration_endpoints WHERE enabled=TRUE")) {
            if (!subscribed((String) endpoint.get("event_types"), event)) continue;
            jdbc.update("INSERT INTO integration_outbox(id,endpoint_id,event_type,payload_json,status,attempts,next_attempt_at,created_at,updated_at) " +
                            "VALUES (?,?,?,?, 'PENDING',0,?,?,?)", UUID.randomUUID().toString(), endpoint.get("id"), event,
                    payload, ts(now), ts(now), ts(now));
        }
    }

    public Map<String, Object> test(String id) {
        Map<String, Object> endpoint = find(id);
        if (!Boolean.TRUE.equals(endpoint.get("enabled"))) {
            throw ApiException.bad("Enable endpoint " + endpoint.get("name") + " before testing it");
        }
        Instant now = Instant.now();
        String payload;
        try {
            payload = json.writeValueAsString(Map.of("eventId", UUID.randomUUID().toString(),
                    "eventType", "INTEGRATION_TEST", "occurredAt", now.toString(),
                    "tenant", System.getenv().getOrDefault("FORGETDM_TENANT_ID", "default"),
                    "data", Map.of("endpointId", id, "message", "ForgeTDM integration test")));
        } catch (Exception e) { throw new IllegalStateException("Cannot serialize integration test", e); }
        jdbc.update("INSERT INTO integration_outbox(id,endpoint_id,event_type,payload_json,status,attempts,next_attempt_at,created_at,updated_at) " +
                        "VALUES (?,?, 'INTEGRATION_TEST',?, 'PENDING',0,?,?,?)",
                UUID.randomUUID().toString(), id, payload, ts(now), ts(now), ts(now));
        return Map.of("queued", true, "endpoint", endpoint.get("name"));
    }

    public Map<String, Object> retry(String deliveryId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT o.id,o.endpoint_id AS \"endpointId\",o.status,e.enabled,e.name AS \"endpointName\" " +
                        "FROM integration_outbox o JOIN integration_endpoints e ON e.id=o.endpoint_id WHERE o.id=?",
                deliveryId);
        if (rows.isEmpty()) throw ApiException.notFound("Integration delivery " + deliveryId + " not found");
        Map<String, Object> row = rows.get(0);
        if (!Boolean.TRUE.equals(row.get("enabled"))) {
            throw ApiException.bad("Enable endpoint " + row.get("endpointName") + " before retrying this delivery");
        }
        String status = String.valueOf(row.get("status"));
        if (!"RETRY".equals(status) && !"DEAD".equals(status)) {
            throw ApiException.bad("Only failed integration deliveries can be retried");
        }
        Instant now = Instant.now();
        jdbc.update("UPDATE integration_outbox SET status='PENDING',next_attempt_at=?,updated_at=? WHERE id=?",
                ts(now), ts(now), deliveryId);
        String actor = AccessContext.current().map(p -> p.username()).orElse("system");
        audit.log(actor, "INTEGRATION_DELIVERY_RETRIED", "delivery=" + deliveryId + " endpoint=" + row.get("endpointId"));
        return Map.of("queued", true, "deliveryId", deliveryId, "endpoint", row.get("endpointName"));
    }

    @Scheduled(fixedDelayString = "${forgetdm.integrations.dispatch-ms:10000}")
    public void dispatch() {
        if (!leases.acquire("integration-webhook-dispatcher", Duration.ofSeconds(9))) return;
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT o.id,o.event_type,o.payload_json,o.attempts,e.url,e.secret_env " +
                "FROM integration_outbox o JOIN integration_endpoints e ON e.id=o.endpoint_id " +
                "WHERE o.status IN ('PENDING','RETRY') AND o.next_attempt_at <= ? AND e.enabled=TRUE ORDER BY o.created_at LIMIT 25",
                ts(Instant.now()));
        for (Map<String, Object> row : rows) deliver(row);
    }

    private void deliver(Map<String, Object> row) {
        String id = String.valueOf(row.get("id"));
        String payload = String.valueOf(row.get("payload_json"));
        String event = String.valueOf(row.get("event_type"));
        int attempts = ((Number) row.get("attempts")).intValue() + 1;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(String.valueOf(row.get("url"))))
                    .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                    .header("X-ForgeTDM-Event", event).header("X-ForgeTDM-Delivery", id)
                    .POST(HttpRequest.BodyPublishers.ofString(payload));
            String secretEnv = clean((String) row.get("secret_env"));
            if (secretEnv != null) {
                String secret = System.getenv(secretEnv);
                if (secret == null || secret.isBlank()) throw new IllegalStateException("Secret environment variable " + secretEnv + " is not set");
                builder.header("X-ForgeTDM-Signature-256", "sha256=" + hmac(secret, payload));
            }
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new IllegalStateException("HTTP " + response.statusCode() + ": " + trim(response.body(), 500));
            jdbc.update("UPDATE integration_outbox SET status='DELIVERED',attempts=?,delivered_at=?,last_error=NULL,updated_at=? WHERE id=?",
                    attempts, ts(Instant.now()), ts(Instant.now()), id);
        } catch (Exception e) {
            boolean dead = attempts >= 8;
            long delay = Math.min(3600, 5L * (1L << Math.min(9, attempts - 1)));
            jdbc.update("UPDATE integration_outbox SET status=?,attempts=?,next_attempt_at=?,last_error=?,updated_at=? WHERE id=?",
                    dead ? "DEAD" : "RETRY", attempts, ts(Instant.now().plusSeconds(delay)), trim(e.getMessage(), 1900), ts(Instant.now()), id);
        }
    }

    private Map<String, Object> find(String id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,name,kind,url,event_types AS \"eventTypes\",secret_env AS \"secretEnv\",enabled FROM integration_endpoints WHERE id=?", id);
        if (rows.isEmpty()) throw ApiException.notFound("Integration endpoint " + id + " not found");
        return rows.get(0);
    }

    private String validateUrl(String raw) {
        String value = required(raw, "Endpoint URL");
        URI uri;
        try { uri = URI.create(value); } catch (Exception e) { throw ApiException.bad("Invalid endpoint URL"); }
        String scheme = String.valueOf(uri.getScheme()).toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !(allowHttp && "http".equals(scheme)))
            throw ApiException.bad("Integration endpoints must use HTTPS (set FORGETDM_INTEGRATIONS_ALLOW_HTTP=true only for local testing)");
        if (uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null)
            throw ApiException.bad("Endpoint URL must have a host and must not embed credentials");
        return uri.toString();
    }

    private static boolean subscribed(String configured, String event) {
        if (configured == null || configured.isBlank() || "*".equals(configured.trim())) return true;
        for (String item : configured.split(",")) if (event.equalsIgnoreCase(item.trim())) return true;
        return false;
    }

    private static String hmac(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private static String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String required(String value, String label) { String clean = clean(value); if (clean == null) throw ApiException.bad(label + " is required"); return clean; }
    private static String trim(String value, int max) { String clean = value == null ? "Unknown error" : value; return clean.length() <= max ? clean : clean.substring(0, max); }
    private static Timestamp ts(Instant value) { return Timestamp.from(value); }
}
