package io.forgetdm.provision;

import io.forgetdm.audit.AuditService;
import io.forgetdm.platform.ClusterLeaseService;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessControlService;
import io.forgetdm.security.AccessPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DataScopeJobScheduleIsolationTest {

    @Test
    void scheduledRunRehydratesSavedOwnerBeforeSubmittingReferences() {
        JdbcTemplate jdbc = database();
        jdbc.update("INSERT INTO datascope_saved_jobs(" +
                        "id,owner_user_id,owner_username,name,spec_json,schedule_cron,schedule_zone," +
                        "schedule_enabled,next_run_at,self_service_enabled,created_at,updated_at) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                "alpha-schedule", 11L, "alpha-user", "Alpha scheduled load",
                "{\"jobType\":\"MASK_COPY\",\"sourceId\":1,\"targetId\":2,\"datasetId\":91}",
                "*/30 * * * * *", "UTC", true, Timestamp.from(Instant.now().minusSeconds(2)), false,
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));

        ClusterLeaseService leases = mock(ClusterLeaseService.class);
        when(leases.acquire(anyString(), any())).thenReturn(true);
        AccessControlService access = mock(AccessControlService.class);
        AccessPrincipal alpha = new AccessPrincipal(11L, "alpha-user", "Alpha", Set.of(), Set.of(),
                List.of(new AccessControlService.GroupLite(101L, "alpha")));
        when(access.principal(11L)).thenReturn(alpha);

        AtomicReference<AccessPrincipal> submitPrincipal = new AtomicReference<>();
        ProvisioningService provisioning = mock(ProvisioningService.class);
        when(provisioning.submit(any())).thenAnswer(invocation -> {
            submitPrincipal.set(AccessContext.current().orElse(null));
            ProvisionJobEntity submitted = spy(invocation.<ProvisionJobEntity>getArgument(0));
            doReturn(77L).when(submitted).getId();
            submitted.setStatus("PENDING");
            submitted.setApprovalStatus("NOT_REQUIRED");
            submitted.setMessage("Queued");
            return submitted;
        });

        DataScopeJobService jobs = new DataScopeJobService(jdbc, provisioning, mock(AuditService.class),
                leases, access);

        jobs.runDueSchedules();

        assertNotNull(submitPrincipal.get());
        assertEquals(11L, submitPrincipal.get().userId());
        assertEquals("alpha-user", submitPrincipal.get().username());
        assertTrue(AccessContext.current().isEmpty(), "scheduled identity must not leak to the scheduler thread");
        assertEquals(77L, jdbc.queryForObject(
                "SELECT last_run_job_id FROM datascope_saved_jobs WHERE id='alpha-schedule'", Long.class));
    }

    private static JdbcTemplate database() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:datascope_schedule_isolation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE datascope_saved_jobs (" +
                "id VARCHAR(80) PRIMARY KEY, owner_user_id BIGINT, owner_username VARCHAR(120) NOT NULL," +
                "name VARCHAR(200) NOT NULL, description VARCHAR(500), spec_json TEXT NOT NULL," +
                "last_run_job_id BIGINT, schedule_cron VARCHAR(120), schedule_zone VARCHAR(60)," +
                "schedule_enabled BOOLEAN NOT NULL, next_run_at TIMESTAMP, last_scheduled_run_at TIMESTAMP," +
                "self_service_enabled BOOLEAN NOT NULL, self_service_label VARCHAR(200)," +
                "created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL)");
        return jdbc;
    }
}
