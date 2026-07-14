package io.forgetdm.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessPrincipal;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AgentRunRepositoryTest {
    @Test
    void planStepsAndApprovalSurviveRepositoryReload() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:forge_agent_runs;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        try (Connection connection = ds.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V58__forge_intelligence_store.sql"));
        }
        ObjectMapper json = new ObjectMapper();
        AgentRunRepository repository = new AgentRunRepository(new JdbcTemplate(ds), json);
        var intent = new AgentContracts.TestDataIntent("test", List.of("VALIDATE"), List.of(), List.of(), null,
                List.of(), null, null, "SYNTHETIC", false, List.of("ROW_COUNTS"), false, null,
                "DATABASE", "DATABASE", List.of(), List.of());
        var step = new AgentContracts.CompiledStep(1, "GROUND_STORY", "Ground", "Ground intent", "GROUND",
                false, false, null, null, List.of("FDS-1"), "PENDING", null);
        var compilation = new AgentContracts.Compilation(intent, "summary", List.of(step), List.of(), List.of(), List.of(),
                .91, "LOW", "abc123", false, null, null);

        long id = repository.create("story", compilation, "tester1");
        AgentRunRepository reloaded = new AgentRunRepository(new JdbcTemplate(ds), json);
        var stored = reloaded.get(id);
        assertEquals("AWAITING_PLAN_APPROVAL", stored.status());
        assertEquals("GROUND_STORY", stored.steps().get(0).code());

        AccessPrincipal reviewer = new AccessPrincipal(2L, "checker", "Checker", Set.of("TDM_ARCHITECT"),
                Set.of("provision.approve"));
        assertEquals(1, AccessContext.callAs(reviewer, null, () -> reloaded.list().size()));
        AccessPrincipal unrelated = new AccessPrincipal(3L, "other", "Other", Set.of("TESTER"), Set.of("assistant.use"));
        assertEquals(0, AccessContext.callAs(unrelated, null, () -> reloaded.list().size()));

        reloaded.approvePlan(id, "tester1");
        assertEquals("APPROVED", reloaded.get(id).status());
        assertEquals("tester1", reloaded.get(id).approvedBy());
    }
}
