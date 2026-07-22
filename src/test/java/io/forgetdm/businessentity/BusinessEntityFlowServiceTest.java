package io.forgetdm.businessentity;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.security.GovernedReferenceGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessEntityFlowServiceTest {
    private JdbcTemplate jdbc;
    private BusinessEntityService entities;
    private BusinessEntityEnterpriseService enterprise;
    private AuditService audit;
    private GovernedReferenceGuard references;
    private BusinessEntityFlowService service;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:be_flow;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"));
        createTables();
        entities = mock(BusinessEntityService.class);
        enterprise = mock(BusinessEntityEnterpriseService.class);
        audit = mock(AuditService.class);
        references = mock(GovernedReferenceGuard.class);
        service = new BusinessEntityFlowService(jdbc, entities, enterprise, audit, references);

        BusinessEntityDefinitionEntity entity = new BusinessEntityDefinitionEntity();
        entity.setId(1L);
        entity.setName("Customer 360");
        entity.setPrimaryDatasetId(201L);

        BusinessEntityMemberEntity customer = member(11L, 201L, "customers");
        BusinessEntityMemberEntity card = member(12L, 202L, "cards");
        when(entities.getDetail(1L)).thenReturn(new BusinessEntityService.BusinessEntityDetail(entity,
                List.of(customer, card), null, Map.of(101L, "core", 102L, "cards")));
    }

    @Test
    void savesFlowAndDebugRoutesFailureToExceptionHandler() {
        Map<String, Object> starter = service.starterFlow(1L);
        List<Map<String, Object>> nodes = castList(starter.get("nodes"));
        assertTrue(nodes.stream().anyMatch(n -> "DATASCOPE_FANOUT".equals(n.get("type"))));
        assertTrue(nodes.stream().anyMatch(n -> "TWO_PHASE_COMMIT".equals(n.get("type"))));

        Map<String, Object> saved = service.saveFlow(1L, new BusinessEntityFlowService.FlowRequest(null,
                "Customer enterprise flow", "debug me", "ACTIVE", nodes, castList(starter.get("edges")),
                castMap(starter.get("settings"))));

        Map<String, Object> debug = service.debugFlow(((Number) saved.get("id")).longValue(),
                new BusinessEntityFlowService.DebugRequest("DEBUG_DRY_RUN", "fanout", List.of(), Map.of("ticket", "QA-1")));

        assertEquals("COMPLETED", debug.get("status"));
        List<Map<String, Object>> events = castList(debug.get("events"));
        assertTrue(events.stream().anyMatch(e -> "FAILED".equals(e.get("status")) && "fanout".equals(e.get("stepKey"))));
        assertTrue(events.stream().anyMatch(e -> "EXCEPTION_HANDLER".equals(e.get("type"))));
        assertEquals(1, service.listDebugRuns(((Number) saved.get("id")).longValue()).size());
        verify(audit, atLeastOnce()).log(any(), any(), any());
    }

    @Test
    void validatesPublishesAndRunsApprovedExecutionPlanFromFlow() {
        Map<String, Object> starter = service.starterFlow(1L);
        Map<String, Object> saved = service.saveFlow(1L, new BusinessEntityFlowService.FlowRequest(null,
                "Customer enterprise flow", "run me", "DRAFT", castList(starter.get("nodes")),
                castList(starter.get("edges")), castMap(starter.get("settings"))));

        Map<String, Object> validation = service.validateFlow(((Number) saved.get("id")).longValue());
        assertEquals("PASS", validation.get("status"));
        Map<String, Object> published = service.publishFlow(((Number) saved.get("id")).longValue());
        assertEquals("ACTIVE", published.get("status"));
        when(enterprise.launchExecutionPlan(eq(900L), any(BusinessEntityEnterpriseService.LaunchRequest.class)))
                .thenReturn(Map.of("engine", "DATASCOPE_FANOUT", "runId", "be-run-1", "status", "SUBMITTED"));
        jdbc.update("INSERT INTO be_entity_execution_plans(id, entity_id) VALUES (900, 1)");

        Map<String, Object> run = service.runFlow(((Number) saved.get("id")).longValue(),
                new BusinessEntityFlowService.RunRequest("EXECUTE_APPROVED", 900L, 102L, "uat",
                        null, "seed1", "REPLACE", "DELETE", 100, null, 99L, "SINGLE",
                        null, null, null, List.of(), Map.of("ticket", "QA-2")));

        assertEquals("SUBMITTED", run.get("status"));
        List<Map<String, Object>> events = castList(run.get("events"));
        assertTrue(events.stream().anyMatch(e -> "EXECUTION_PLAN".equals(e.get("type"))
                && String.valueOf(e.get("message")).contains("launched")));
        verify(enterprise).launchExecutionPlan(eq(900L), any(BusinessEntityEnterpriseService.LaunchRequest.class));
    }

    @Test
    void deniesBetaFlowRawIdBeforeReadDeleteOrDebugEvidence() {
        Map<String, Object> starter = service.starterFlow(1L);
        Map<String, Object> saved = service.saveFlow(1L, new BusinessEntityFlowService.FlowRequest(null,
                "Beta-owned flow", "cross tenant", "ACTIVE", castList(starter.get("nodes")),
                castList(starter.get("edges")), castMap(starter.get("settings"))));
        long flowId = ((Number) saved.get("id")).longValue();
        jdbc.update("UPDATE be_orchestration_flows SET entity_id = 2 WHERE id = ?", flowId);
        when(entities.getDetail(2L)).thenThrow(new ApiException(HttpStatus.FORBIDDEN, "BETA entity"));

        assertThrows(ApiException.class, () -> service.getFlow(flowId));
        assertThrows(ApiException.class, () -> service.debugFlow(flowId,
                new BusinessEntityFlowService.DebugRequest("DEBUG_DRY_RUN", null, List.of(), Map.of())));
        assertThrows(ApiException.class, () -> service.deleteFlow(flowId));

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM be_orchestration_flows WHERE id = ?", Integer.class, flowId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM be_orchestration_debug_runs WHERE flow_id = ?", Integer.class, flowId));
    }

    @Test
    void rejectsCrossEntityExecutionPlanBeforeCreatingRunEvidence() {
        Map<String, Object> starter = service.starterFlow(1L);
        Map<String, Object> saved = service.saveFlow(1L, new BusinessEntityFlowService.FlowRequest(null,
                "Alpha active flow", "preflight", "ACTIVE", castList(starter.get("nodes")),
                castList(starter.get("edges")), castMap(starter.get("settings"))));
        long flowId = ((Number) saved.get("id")).longValue();
        jdbc.update("INSERT INTO be_entity_execution_plans(id, entity_id) VALUES (901, 2)");
        when(entities.getDetail(2L)).thenThrow(new ApiException(HttpStatus.FORBIDDEN, "BETA entity"));

        assertThrows(ApiException.class, () -> service.runFlow(flowId,
                new BusinessEntityFlowService.RunRequest("EXECUTE_APPROVED", 901L, null, null,
                        null, null, "REPLACE", "DELETE", null, null, null, "SINGLE",
                        null, null, null, List.of(), Map.of())));

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM be_orchestration_debug_runs WHERE flow_id = ?", Integer.class, flowId));
        verify(enterprise, never()).launchExecutionPlan(anyLong(), any());
    }

    @Test
    void rejectsUnauthorizedTargetBeforeExecutionOrDebugEvidence() {
        Map<String, Object> starter = service.starterFlow(1L);
        Map<String, Object> saved = service.saveFlow(1L, new BusinessEntityFlowService.FlowRequest(null,
                "Alpha target preflight", "target authorization", "ACTIVE", castList(starter.get("nodes")),
                castList(starter.get("edges")), castMap(starter.get("settings"))));
        long flowId = ((Number) saved.get("id")).longValue();
        jdbc.update("INSERT INTO be_entity_execution_plans(id, entity_id) VALUES (902, 1)");
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "BETA target"))
                .when(references).dataSource(999L);

        assertThrows(ApiException.class, () -> service.runFlow(flowId,
                new BusinessEntityFlowService.RunRequest("EXECUTE_APPROVED", 902L, 999L, "uat",
                        null, null, "REPLACE", "DELETE", null, null, null, "SINGLE",
                        null, null, null, List.of(), Map.of())));

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM be_orchestration_debug_runs WHERE flow_id = ?", Integer.class, flowId));
        verify(enterprise, never()).launchExecutionPlan(anyLong(), any());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private BusinessEntityMemberEntity member(Long id, Long datasetId, String table) {
        BusinessEntityMemberEntity member = new BusinessEntityMemberEntity();
        member.setId(id);
        member.setEntityId(1L);
        member.setDataSourceId(datasetId - 100);
        member.setDatasetId(datasetId);
        member.setTableName(table);
        member.setIncludeInSubset(true);
        return member;
    }

    private void createTables() {
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE business_entities (id BIGINT PRIMARY KEY)");
        jdbc.update("INSERT INTO business_entities(id) VALUES (1)");
        jdbc.execute("""
                CREATE TABLE be_orchestration_flows (
                  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                  entity_id BIGINT NOT NULL,
                  name VARCHAR(220) NOT NULL,
                  description CLOB,
                  version_no INTEGER NOT NULL DEFAULT 1,
                  status VARCHAR(40) NOT NULL DEFAULT 'DRAFT',
                  canvas_json CLOB NOT NULL,
                  created_by VARCHAR(200),
                  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE be_orchestration_debug_runs (
                  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                  entity_id BIGINT NOT NULL,
                  flow_id BIGINT NOT NULL,
                  mode VARCHAR(60) NOT NULL DEFAULT 'DEBUG_DRY_RUN',
                  status VARCHAR(40) NOT NULL DEFAULT 'COMPLETED',
                  current_step_key VARCHAR(160),
                  input_json CLOB NOT NULL,
                  events_json CLOB NOT NULL,
                  created_by VARCHAR(200),
                  started_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                  completed_at TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE be_entity_execution_plans (
                  id BIGINT PRIMARY KEY,
                  entity_id BIGINT NOT NULL)
                """);
    }
}
