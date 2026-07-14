package io.forgetdm.automation;

import io.forgetdm.audit.AuditService;
import io.forgetdm.provision.DataScopeJobService;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SelfServiceServiceTest {
    @Test
    void makerCheckerRequestLaunchesPublishedTemplateAsRequester() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:self_service_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE datascope_saved_jobs(id VARCHAR(36) PRIMARY KEY,name VARCHAR(200),description VARCHAR(500),owner_user_id BIGINT,owner_username VARCHAR(120),self_service_enabled BOOLEAN,self_service_label VARCHAR(200),updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE self_service_requests(id VARCHAR(36) PRIMARY KEY,template_id VARCHAR(36),requested_by_id BIGINT,requested_by VARCHAR(120),purpose VARCHAR(1000),environment VARCHAR(80),status VARCHAR(40),decision_by_id BIGINT,decision_by VARCHAR(120),decision_note VARCHAR(1000),run_id BIGINT,created_at TIMESTAMP,decided_at TIMESTAMP,fulfilled_at TIMESTAMP,updated_at TIMESTAMP)");
        jdbc.update("INSERT INTO datascope_saved_jobs VALUES ('template-1','Customer QA slice','Masked customer slice',30,'architect',TRUE,'Customer 360 QA',CURRENT_TIMESTAMP)");
        DataScopeJobService jobs = mock(DataScopeJobService.class);
        when(jobs.runSelfService("template-1")).thenReturn(Map.of("runId", 77L, "status", "QUEUED"));
        IntegrationWebhookService integrations = mock(IntegrationWebhookService.class);
        SelfServiceService service = new SelfServiceService(jdbc, jobs, integrations, mock(AuditService.class));
        AccessPrincipal requester = new AccessPrincipal(10L, "tester", "Tester", Set.of("TESTER"), Set.of("provision.run", "provision.read"));
        AccessPrincipal approver = new AccessPrincipal(20L, "approver", "Approver", Set.of("ADMIN"), Set.of("admin.all", "provision.approve"));

        Map<String, Object> created = AccessContext.callAs(requester, null,
                () -> service.request(new SelfServiceService.RequestData("template-1", "Regression test cycle", "QA")));
        String id = String.valueOf(created.get("id"));
        AccessContext.callAs(approver, null, () -> service.decide(id, true, new SelfServiceService.Decision("Approved for sprint 42")));
        Map<String, Object> fulfilled = AccessContext.callAs(requester, null, () -> service.fulfill(id));

        assertEquals("FULFILLED", fulfilled.get("status"));
        assertEquals(77L, ((Number) fulfilled.get("runId")).longValue());
        verify(jobs).runSelfService("template-1");
    }
}
