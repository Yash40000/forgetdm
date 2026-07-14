package io.forgetdm.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessPrincipal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class AgentServiceTest {

    @Test
    void planCreatorCannotSelfApproveEvenWithApprovalPermission() {
        AgentRunRepository repository = mock(AgentRunRepository.class);
        ObjectMapper json = new ObjectMapper();
        var run = new AgentRunRepository.StoredRun(1, "story", "AWAITING_PLAN_APPROVAL", "summary", "ollama", "model",
                json.createObjectNode(), json.createArrayNode(), json.createArrayNode(), json.createArrayNode(),
                json.createArrayNode(), .8, "MEDIUM", "fingerprint", true, "maker", null, null, null,
                Instant.now(), Instant.now(), List.of());
        when(repository.get(1)).thenReturn(run);
        AgentService service = new AgentService(mock(AgentPlanningService.class), repository,
                mock(ForgeIntelligenceStoreService.class), mock(AiTools.class), mock(AuditService.class), json);
        AccessPrincipal maker = new AccessPrincipal(10L, "maker", "Maker", Set.of("TDM_ARCHITECT"),
                Set.of("assistant.use", "provision.approve"));

        assertThrows(ApiException.class, () -> AccessContext.callAs(maker, null, () -> service.approvePlan(1)));
        verify(repository, never()).approvePlan(anyLong(), anyString());
    }
}
