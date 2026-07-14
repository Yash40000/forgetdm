package io.forgetdm.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.platform.ClusterLeaseService;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class IntegrationWebhookServiceTest {
    @Test
    void writesSubscribedEventsToDurableOutbox() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:webhook_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE integration_endpoints(id VARCHAR(36) PRIMARY KEY,name VARCHAR(160) UNIQUE,kind VARCHAR(40),url VARCHAR(2000),event_types VARCHAR(2000),secret_env VARCHAR(160),enabled BOOLEAN,created_by VARCHAR(120),created_at TIMESTAMP,updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE integration_outbox(id VARCHAR(36) PRIMARY KEY,endpoint_id VARCHAR(36),event_type VARCHAR(120),payload_json CLOB,status VARCHAR(30),attempts INT,next_attempt_at TIMESTAMP,delivered_at TIMESTAMP,last_error VARCHAR(2000),created_at TIMESTAMP,updated_at TIMESTAMP)");
        IntegrationWebhookService service = new IntegrationWebhookService(jdbc, mock(AuditService.class), mock(ClusterLeaseService.class), new ObjectMapper(), false);
        AccessPrincipal admin = new AccessPrincipal(1L, "admin", "Admin", Set.of("ADMIN"), Set.of("admin.all"));
        AccessContext.callAs(admin, null, () -> service.save(null,
                new IntegrationWebhookService.EndpointRequest("servicenow", "SERVICENOW", "https://example.com/hook", "SELF_SERVICE_APPROVED", "FORGETDM_TEST_WEBHOOK_SECRET", true)));

        service.emit("SELF_SERVICE_REQUESTED", Map.of("requestId", "ignored"));
        service.emit("SELF_SERVICE_APPROVED", Map.of("requestId", "req-1"));

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM integration_outbox", Integer.class));
        assertEquals("SELF_SERVICE_APPROVED", jdbc.queryForObject("SELECT event_type FROM integration_outbox", String.class));
    }

    @Test
    void disabledEndpointCannotQueueAStuckTestDelivery() {
        TestContext context = context("disabled");
        Map<String, Object> endpoint = AccessContext.callAs(context.admin(), null, () -> context.service().save(null,
                new IntegrationWebhookService.EndpointRequest("review-only", "GENERIC", "https://example.com/hook", "*", null, false)));

        ApiException error = assertThrows(ApiException.class, () -> context.service().test(String.valueOf(endpoint.get("id"))));

        assertTrue(error.getMessage().contains("Enable endpoint"));
        assertEquals(0, context.jdbc().queryForObject("SELECT COUNT(*) FROM integration_outbox", Integer.class));
    }

    @Test
    void exposesDeliveryEvidenceAndAllowsFailedDeliveryToBeRetried() {
        TestContext context = context("activity");
        Map<String, Object> endpoint = AccessContext.callAs(context.admin(), null, () -> context.service().save(null,
                new IntegrationWebhookService.EndpointRequest("qa-gateway", "SERVICENOW", "https://example.com/hook", "*", null, true)));
        String endpointId = String.valueOf(endpoint.get("id"));
        context.service().test(endpointId);
        String deliveryId = context.jdbc().queryForObject("SELECT id FROM integration_outbox", String.class);
        context.jdbc().update("UPDATE integration_outbox SET status='DEAD',attempts=8,last_error='HTTP 500' WHERE id=?", deliveryId);

        Map<String, Object> retried = AccessContext.callAs(context.admin(), null, () -> context.service().retry(deliveryId));
        var deliveries = context.service().deliveries(endpointId, 10);

        assertEquals(true, retried.get("queued"));
        assertEquals(1, deliveries.size());
        assertEquals("PENDING", deliveries.get(0).get("status"));
        assertEquals("qa-gateway", deliveries.get(0).get("endpointName"));
        assertEquals(8, ((Number) deliveries.get(0).get("attempts")).intValue());
    }

    private TestContext context(String name) {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:webhook_" + name + "_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE integration_endpoints(id VARCHAR(36) PRIMARY KEY,name VARCHAR(160) UNIQUE,kind VARCHAR(40),url VARCHAR(2000),event_types VARCHAR(2000),secret_env VARCHAR(160),enabled BOOLEAN,created_by VARCHAR(120),created_at TIMESTAMP,updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE integration_outbox(id VARCHAR(36) PRIMARY KEY,endpoint_id VARCHAR(36),event_type VARCHAR(120),payload_json CLOB,status VARCHAR(30),attempts INT,next_attempt_at TIMESTAMP,delivered_at TIMESTAMP,last_error VARCHAR(2000),created_at TIMESTAMP,updated_at TIMESTAMP)");
        IntegrationWebhookService service = new IntegrationWebhookService(jdbc, mock(AuditService.class), mock(ClusterLeaseService.class), new ObjectMapper(), false);
        AccessPrincipal admin = new AccessPrincipal(1L, "admin", "Admin", Set.of("ADMIN"), Set.of("admin.all"));
        return new TestContext(jdbc, service, admin);
    }

    private record TestContext(JdbcTemplate jdbc, IntegrationWebhookService service, AccessPrincipal admin) { }
}
