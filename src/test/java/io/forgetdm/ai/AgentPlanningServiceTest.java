package io.forgetdm.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentPlanningServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private AiAssistantService assistant;
    private ForgeIntelligenceStoreService store;
    private AgentPlanningService planning;

    @BeforeEach
    void setUp() {
        assistant = mock(AiAssistantService.class);
        store = mock(ForgeIntelligenceStoreService.class);
        when(assistant.ready()).thenReturn(false);
        when(store.ensureFresh()).thenReturn(Map.of("documents", 5));
        planning = new AgentPlanningService(assistant, store, json);
    }

    @Test
    void compilesCrossSystemBankingStoryIntoExactGovernedAction() {
        when(store.search(anyString(), anyInt())).thenReturn(List.of(
                hit(1, "BUSINESS_ENTITY", "Retail Customer", Map.of("id", 30, "primaryDatasetId", 10)),
                hit(2, "DATA_SOURCE", "CoreBank", Map.of("id", 1, "role", "SOURCE", "environment", "PROD")),
                hit(3, "DATA_SOURCE", "QA_DB", Map.of("id", 2, "role", "TARGET", "environment", "QA")),
                hit(4, "DATASCOPE", "Customer360", Map.of("id", 10, "dataSourceId", 1)),
                hit(5, "MASKING_POLICY", "Retail PII", Map.of("id", 20))
        ));

        var result = planning.compile("Provision 250 masked Retail Customer entities from CoreBank to QA_DB using Customer360 and Retail PII", null, null, false);

        assertFalse(result.validation().stream().anyMatch(issue -> issue.severity().equals("BLOCKER")));
        var delivery = result.steps().stream().filter(step -> step.code().equals("DELIVER_DATA")).findFirst().orElseThrow();
        assertEquals("submit_job", delivery.actionName());
        assertEquals(1, delivery.actionArgs().path("sourceId").asInt());
        assertEquals(2, delivery.actionArgs().path("targetId").asInt());
        assertEquals(10, delivery.actionArgs().path("datasetId").asInt());
        assertEquals(20, delivery.actionArgs().path("policyId").asInt());
        assertEquals("HIGH", result.riskLevel(), "PROD source remains high risk even when masked");
    }

    @Test
    void blocksAmbiguousAndUnmaskedProductionDelivery() {
        when(store.search(anyString(), anyInt())).thenReturn(List.of(
                hit(1, "DATA_SOURCE", "ProdTarget", Map.of("id", 9, "role", "TARGET", "environment", "PROD"))
        ));
        var result = planning.compile("Copy unmasked customer records to ProdTarget", null, null, false);
        var codes = result.validation().stream().map(AgentContracts.ValidationIssue::code).toList();
        assertTrue(codes.contains("PRODUCTION_TARGET"));
        assertTrue(codes.contains("UNMASKED_REQUEST"));
        assertTrue(codes.contains("SOURCE_REQUIRED"));
        assertEquals("BLOCKED", result.steps().stream().filter(step -> step.code().equals("DELIVER_DATA")).findFirst().orElseThrow().status());
    }

    @Test
    void resolvesApprovedSyntheticJobWithoutConstructingRawPlan() {
        when(store.search(anyString(), anyInt())).thenReturn(List.of(
                hit(7, "SAVED_JOB", "Retail Perf Pack", Map.of("id", "syn-7", "jobKind", "SYNTHETIC", "approvalStatus", "APPROVED"))
        ));
        var result = planning.compile("Run Retail Perf Pack synthetic generation for UAT", null, null, false);
        var delivery = result.steps().stream().filter(step -> step.code().equals("DELIVER_DATA")).findFirst().orElseThrow();
        assertEquals("run_synthetic_job", delivery.actionName());
        assertEquals("syn-7", delivery.actionArgs().path("savedJobId").asText());
    }

    @Test
    void honorsFromAndIntoWhenBothConnectionsHaveBothRole() {
        when(store.search(anyString(), anyInt())).thenReturn(List.of(
                hit(1, "DATA_SOURCE", "Core Banking PostgreSQL", Map.of("id", 10, "role", "BOTH", "environment", "QA")),
                hit(2, "DATA_SOURCE", "targetdb", Map.of("id", 3, "role", "BOTH", "environment", "STAGE")),
                hit(3, "BUSINESS_ENTITY", "Customer 360", Map.of("id", 8, "primaryDatasetId", 32)),
                hit(4, "DATASCOPE", "Core Banking Scope", Map.of("id", 32, "dataSourceId", 10)),
                hit(5, "MASKING_POLICY", "Customer 360 Masking", Map.of("id", 16))
        ));

        var result = planning.compile("Provision 250 masked Customer 360 entities from Core Banking PostgreSQL into targetdb using Core Banking Scope and Customer 360 Masking; validate PII exposure", null, null, false);
        var delivery = result.steps().stream().filter(step -> step.code().equals("DELIVER_DATA")).findFirst().orElseThrow();

        assertEquals(10, delivery.actionArgs().path("sourceId").asInt());
        assertEquals(3, delivery.actionArgs().path("targetId").asInt());
        assertEquals(250L, result.intent().requestedEntities());
        assertNull(result.intent().requestedRows());
        assertTrue(result.steps().stream().noneMatch(step -> step.code().equals("DISCOVER_PII")));
        assertTrue(result.evidence().stream().anyMatch(item -> item.title().equals("targetdb")));
    }

    @Test
    void localRuntimeFailureFallsBackWithoutASecondModelCall() {
        when(assistant.ready(nullable(String.class))).thenReturn(true);
        when(assistant.isLocalProvider(nullable(String.class))).thenReturn(true);
        when(store.search(anyString(), anyInt())).thenReturn(List.of());
        when(assistant.complete(anyString(), anyString(), nullable(String.class), nullable(String.class), eq(true)))
                .thenThrow(new IllegalStateException("local timeout"));

        var result = planning.compile("Generate synthetic customers for QA", null, null, false);

        assertFalse(result.modelAssisted());
        verify(assistant, times(1)).complete(anyString(), anyString(), nullable(String.class), nullable(String.class), eq(true));
        verify(assistant, never()).completeStructured(anyString(), anyString(), nullable(String.class), nullable(String.class), any());
    }

    private ForgeIntelligenceStoreService.SearchHit hit(long id, String type, String title, Map<String, Object> metadata) {
        return new ForgeIntelligenceStoreService.SearchHit(id, "FDS-" + id, type, "SYSTEM", title, title,
                10, json.valueToTree(metadata), "METADATA_ONLY", Instant.now());
    }
}
