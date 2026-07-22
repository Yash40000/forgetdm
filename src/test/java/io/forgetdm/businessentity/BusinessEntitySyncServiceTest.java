package io.forgetdm.businessentity;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.http.HttpStatus;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessEntitySyncServiceTest {
    private JdbcTemplate jdbc;
    private JdbcTemplate sourceJdbc;
    private BusinessEntityService entities;
    private DataSourceRepository dataSources;
    private AuditService audit;
    private BusinessEntitySyncService service;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:be_sync;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"));
        sourceJdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:be_sync_source;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"));
        createTables();

        entities = mock(BusinessEntityService.class);
        dataSources = mock(DataSourceRepository.class);
        audit = mock(AuditService.class);
        service = new BusinessEntitySyncService(jdbc, entities, dataSources, new ConnectionFactory(), audit,
                mock(BusinessEntityCapsuleService.class));

        BusinessEntityDefinitionEntity entity = new BusinessEntityDefinitionEntity();
        entity.setId(1L);
        entity.setName("Customer 360");
        BusinessEntityMemberEntity customer = member(11L, 101L, "Core Postgres", "customers", "customer", "customer_id");
        when(entities.getDetail(1L)).thenReturn(new BusinessEntityService.BusinessEntityDetail(entity,
                List.of(customer), null, Map.of(101L, "source-core")));

        DataSourceEntity ds = new DataSourceEntity();
        ds.setId(101L);
        ds.setName("source-core");
        ds.setKind("H2");
        ds.setRole("SOURCE");
        ds.setJdbcUrl("jdbc:h2:mem:be_sync_source;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
        when(dataSources.findById(101L)).thenReturn(Optional.of(ds));
        when(entities.requireAuthorizedDataSource(101L)).thenReturn(ds);
    }

    @Test
    void savesPolicyAndClassifiesFreshSourceWatermark() {
        sourceJdbc.update("INSERT INTO \"customers\" (\"customer_id\", \"updated_at\") VALUES (?, ?)",
                10025, Timestamp.from(Instant.now().minusSeconds(30)));

        Map<String, Object> saved = service.savePolicy(1L, request(900));
        Map<String, Object> run = service.checkFreshness(((Number) saved.get("id")).longValue());

        assertEquals("FRESH", run.get("status"));
        Map<String, Object> result = castMap(run.get("result"));
        List<Map<String, Object>> members = castList(result.get("members"));
        assertEquals("FRESH", members.get(0).get("status"));
        assertEquals(1, service.listRuns(((Number) saved.get("id")).longValue()).size());
        verify(audit, atLeastOnce()).log(any(), any(), any());
    }

    @Test
    void marksPolicyStaleWhenWatermarkExceedsLagSla() {
        sourceJdbc.update("INSERT INTO \"customers\" (\"customer_id\", \"updated_at\") VALUES (?, ?)",
                10025, Timestamp.from(Instant.now().minusSeconds(3600)));

        Map<String, Object> saved = service.savePolicy(1L, request(10));
        Map<String, Object> run = service.checkFreshness(((Number) saved.get("id")).longValue());

        assertEquals("STALE", run.get("status"));
        Map<String, Object> result = castMap(run.get("result"));
        List<Map<String, Object>> members = castList(result.get("members"));
        assertEquals("STALE", members.get(0).get("status"));
        assertTrue(String.valueOf(members.get(0).get("message")).contains("older than SLA"));
    }

    @Test
    void parsesOracleTimestampTextReturnedByJdbcDriver() {
        Instant parsed = BusinessEntitySyncService.toInstant("2026-07-13 13:55:00.038");
        assertEquals(LocalDateTime.of(2026, 7, 13, 13, 55, 0, 38_000_000)
                .atZone(ZoneId.systemDefault()).toInstant(), parsed);
    }

    @Test
    void deniesBetaPolicyRawIdsBeforeMutationConnectionOrRunEvidence() {
        jdbc.update("""
                INSERT INTO be_sync_policies(id, entity_id, name, sync_mode, status, max_lag_seconds,
                    sync_strategy, auto_refresh_enabled, notes)
                VALUES (200, 2, 'Beta freshness', 'POLLING', 'ACTIVE', 900, 'FRESHNESS_CHECK', TRUE, 'beta')
                """);
        jdbc.update("""
                INSERT INTO be_sync_policy_members(id, policy_id, entity_id, data_source_id, table_name,
                    watermark_column, sync_mode, last_status)
                VALUES (201, 200, 2, 101, 'customers', 'updated_at', 'POLLING', 'NEVER_CHECKED')
                """);
        when(entities.getDetail(2L)).thenThrow(new ApiException(HttpStatus.FORBIDDEN, "BETA entity"));

        assertThrows(ApiException.class, () -> service.getPolicy(200L));
        assertThrows(ApiException.class, () -> service.checkFreshness(200L));
        assertThrows(ApiException.class, () -> service.heartbeat(200L,
                new BusinessEntitySyncService.HeartbeatRequest(201L, Instant.now().toString(), "FRESH", "spoof")));
        assertThrows(ApiException.class, () -> service.listRuns(200L));
        assertThrows(ApiException.class, () -> service.deletePolicy(200L));

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM be_sync_policies WHERE id = 200", Integer.class));
        assertEquals("NEVER_CHECKED", jdbc.queryForObject(
                "SELECT last_status FROM be_sync_policy_members WHERE id = 201", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM be_sync_runs WHERE entity_id = 2", Integer.class));
        verify(entities, never()).requireAuthorizedDataSource(anyLong());
    }

    @Test
    void rejectsPhysicalCoordinateOverrideForSelectedSyncMember() {
        BusinessEntitySyncService.SyncMemberRequest spoofed = new BusinessEntitySyncService.SyncMemberRequest(
                null, 11L, "Core Postgres", 999L, null, "customers", "customer", "customer_id",
                "updated_at", 900, "POLLING", null);

        ApiException ex = assertThrows(ApiException.class, () -> service.savePolicy(1L,
                new BusinessEntitySyncService.SyncPolicyRequest(null, "Spoofed freshness", "POLLING",
                        "ACTIVE", 900, null, "FRESHNESS_CHECK", true, "test", List.of(spoofed))));
        assertTrue(ex.getMessage().contains("data source"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM be_sync_policy_members", Integer.class));
    }

    @Test
    void propagatesReferencedDataSourceDenialWithoutWritingFreshnessEvidence() {
        jdbc.update("""
                INSERT INTO be_sync_policies(id, entity_id, name, sync_mode, status, max_lag_seconds,
                    sync_strategy, auto_refresh_enabled, notes)
                VALUES (300, 1, 'Tampered source', 'POLLING', 'ACTIVE', 900, 'FRESHNESS_CHECK', TRUE, 'test')
                """);
        jdbc.update("""
                INSERT INTO be_sync_policy_members(id, policy_id, entity_id, member_id, data_source_id,
                    table_name, watermark_column, sync_mode, last_status)
                VALUES (301, 300, 1, 11, 999, 'customers', 'updated_at', 'POLLING', 'NEVER_CHECKED')
                """);
        when(entities.requireAuthorizedDataSource(999L))
                .thenThrow(new ApiException(HttpStatus.FORBIDDEN, "BETA data source"));

        assertThrows(ApiException.class, () -> service.checkFreshness(300L));

        assertEquals("NEVER_CHECKED", jdbc.queryForObject(
                "SELECT last_status FROM be_sync_policy_members WHERE id = 301", String.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM be_sync_runs WHERE policy_id = 300", Integer.class));
    }

    private BusinessEntitySyncService.SyncPolicyRequest request(int lagSeconds) {
        return new BusinessEntitySyncService.SyncPolicyRequest(null, "Customer freshness", "POLLING",
                "ACTIVE", lagSeconds, null, "FRESHNESS_CHECK", true, "test",
                List.of(new BusinessEntitySyncService.SyncMemberRequest(null, 11L, "Core Postgres",
                        101L, null, "customers", "customer", "customer_id", "updated_at",
                        lagSeconds, "POLLING", null)));
    }

    private BusinessEntityMemberEntity member(Long id, Long ds, String system, String table, String role, String keys) {
        BusinessEntityMemberEntity m = new BusinessEntityMemberEntity();
        m.setId(id);
        m.setEntityId(1L);
        m.setDataSourceId(ds);
        m.setSystemName(system);
        m.setTableName(table);
        m.setLogicalRole(role);
        m.setKeyColumns(keys);
        m.setIncludeInSubset(true);
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private void createTables() {
        jdbc.execute("DROP ALL OBJECTS");
        sourceJdbc.execute("DROP ALL OBJECTS");
        sourceJdbc.execute("CREATE TABLE \"customers\" (\"customer_id\" INT, \"updated_at\" TIMESTAMP)");
        jdbc.execute("CREATE TABLE business_entities (id BIGINT PRIMARY KEY)");
        jdbc.update("INSERT INTO business_entities(id) VALUES (1)");
        jdbc.execute("""
                CREATE TABLE be_sync_policies (
                  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                  entity_id BIGINT NOT NULL,
                  name VARCHAR(220) NOT NULL,
                  sync_mode VARCHAR(60) NOT NULL DEFAULT 'POLLING',
                  status VARCHAR(40) NOT NULL DEFAULT 'ACTIVE',
                  max_lag_seconds INTEGER NOT NULL DEFAULT 900,
                  schedule_cron VARCHAR(120),
                  sync_strategy VARCHAR(80) NOT NULL DEFAULT 'FRESHNESS_CHECK',
                  auto_refresh_enabled BOOLEAN NOT NULL DEFAULT FALSE,
                  notes CLOB,
                  created_by VARCHAR(200),
                  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE be_sync_policy_members (
                  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                  policy_id BIGINT NOT NULL,
                  entity_id BIGINT NOT NULL,
                  member_id BIGINT,
                  system_name VARCHAR(160),
                  data_source_id BIGINT,
                  schema_name VARCHAR(160),
                  table_name VARCHAR(240) NOT NULL,
                  logical_role VARCHAR(120),
                  key_columns CLOB,
                  watermark_column VARCHAR(240),
                  max_lag_seconds INTEGER,
                  sync_mode VARCHAR(60) NOT NULL DEFAULT 'POLLING',
                  query_filter CLOB,
                  last_source_watermark VARCHAR(240),
                  last_checked_at TIMESTAMP,
                  last_status VARCHAR(40) NOT NULL DEFAULT 'NEVER_CHECKED',
                  last_message CLOB,
                  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE be_sync_runs (
                  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                  entity_id BIGINT NOT NULL,
                  policy_id BIGINT NOT NULL,
                  run_type VARCHAR(60) NOT NULL DEFAULT 'FRESHNESS_CHECK',
                  status VARCHAR(40) NOT NULL,
                  result_json CLOB NOT NULL,
                  created_by VARCHAR(200),
                  started_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                  completed_at TIMESTAMP)
                """);
    }
}
