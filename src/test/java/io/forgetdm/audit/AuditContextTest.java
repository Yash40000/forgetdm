package io.forgetdm.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessControlFilter;
import io.forgetdm.security.AccessControlService;
import io.forgetdm.security.AccessPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditContextTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void legacyLogInfersResourceAndCarriesRequestCorrelation() throws Exception {
        AuditWriter writer = mock(AuditWriter.class);
        AuditService service = new AuditService(mock(AuditEventRepository.class), writer, mapper);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/synthetic/saved-jobs/job-123/run");
        request.setAttribute(AccessControlFilter.CORRELATION_ID_ATTRIBUTE, "corr-acceptance-001");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        service.log("operator", "SYNTHETIC_JOB_COMPLETED", "job=job-123 name=nightly-bank-load rows=5000");

        ArgumentCaptor<AuditEventEntity> event = ArgumentCaptor.forClass(AuditEventEntity.class);
        verify(writer).append(event.capture());
        assertEquals("synthetic-job", event.getValue().getResourceType());
        assertEquals("job-123", event.getValue().getResourceId());
        JsonNode metadata = mapper.readTree(event.getValue().getMetadata());
        assertEquals("corr-acceptance-001", metadata.path("correlationId").asText());
        assertEquals("POST", metadata.path("method").asText());
        assertEquals("/api/synthetic/saved-jobs/job-123/run", metadata.path("path").asText());
    }

    @Test
    void structuredRecordPreservesMetadataAndAddsCorrelation() throws Exception {
        AuditWriter writer = mock(AuditWriter.class);
        AuditService service = new AuditService(mock(AuditEventRepository.class), writer, mapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/mappings/runs/42/download");
        request.setAttribute(AccessControlFilter.CORRELATION_ID_ATTRIBUTE, "corr-export-042");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        service.record("operator", "MAPPING_OUTPUT_EXPORTED", "MAPPING", "mapping-run", "42",
                "result.csv", "SUCCESS", "download prepared", "{\"format\":\"csv\",\"rows\":10}");

        ArgumentCaptor<AuditEventEntity> event = ArgumentCaptor.forClass(AuditEventEntity.class);
        verify(writer).append(event.capture());
        JsonNode metadata = mapper.readTree(event.getValue().getMetadata());
        assertEquals("csv", metadata.path("format").asText());
        assertEquals(10, metadata.path("rows").asInt());
        assertEquals("corr-export-042", metadata.path("correlationId").asText());
        assertEquals("mapping-run", event.getValue().getResourceType());
        assertEquals("42", event.getValue().getResourceId());
    }

    @Test
    void legacySavedJobDetailInfersStableResourceIdentity() {
        AuditWriter writer = mock(AuditWriter.class);
        AuditService service = new AuditService(mock(AuditEventRepository.class), writer, mapper);

        service.log("maker", "SYNTHETIC_JOB_APPROVAL_REQUESTED",
                "savedJob=job-987 name=nightly-customer-load");

        ArgumentCaptor<AuditEventEntity> event = ArgumentCaptor.forClass(AuditEventEntity.class);
        verify(writer).append(event.capture());
        assertEquals("synthetic-job-approval", event.getValue().getResourceType());
        assertEquals("job-987", event.getValue().getResourceId());
        assertEquals("nightly-customer-load", event.getValue().getResourceName());
    }

    @Test
    void requestAuditEventCarriesCallerTenantAndHashVersion() {
        AuditWriter writer = mock(AuditWriter.class);
        AuditService service = new AuditService(mock(AuditEventRepository.class), writer, mapper);
        AccessPrincipal alpha = new AccessPrincipal(7L, "alpha", "Alpha User",
                Set.of("OPERATOR"), Set.of("audit.read"),
                List.of(new AccessControlService.GroupLite(11L, "Alpha Group")));

        AccessContext.callAs(alpha, null, () -> {
            service.log("alpha", "POLICY_UPDATED", "policy=14");
            return null;
        });

        ArgumentCaptor<AuditEventEntity> event = ArgumentCaptor.forClass(AuditEventEntity.class);
        verify(writer).append(event.capture());
        assertEquals(7L, event.getValue().getOwnerUserId());
        assertEquals("alpha", event.getValue().getOwnerUsername());
        assertEquals(11L, event.getValue().getOwnerGroupId());
        assertEquals("GROUP", event.getValue().getVisibility());
        assertEquals(2, event.getValue().getHashVersion());
    }
}
