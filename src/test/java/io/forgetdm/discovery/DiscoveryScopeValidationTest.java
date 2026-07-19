package io.forgetdm.discovery;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.config.ForgeProps;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.policy.MaskingPolicyRepository;
import io.forgetdm.policy.MaskingRuleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** DISC-006 contract pack using a disposable in-memory H2 source, never shared user data. */
class DiscoveryScopeValidationTest {
    private static final long SOURCE_ID = 6006L;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ConnectionFactory connections = new ConnectionFactory();
    private final DataSourceService sources = mock(DataSourceService.class);
    private final ClassificationRepository classifications = mock(ClassificationRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private DiscoveryService discovery;
    private String url;

    @BeforeEach
    void setUp() throws Exception {
        url = "jdbc:h2:mem:disc006_" + System.nanoTime() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=FALSE;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", ""); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA EMPTY_SCOPE");
            statement.execute("CREATE SCHEMA FLYWAY_SCOPE");
            statement.execute("CREATE TABLE FLYWAY_SCOPE.flyway_schema_history (installed_rank INT)");
            statement.execute("CREATE SCHEMA POPULATED_SCOPE");
            statement.execute("CREATE TABLE POPULATED_SCOPE.customers (customer_id BIGINT, email VARCHAR(120))");
        }

        DataSourceEntity source = new DataSourceEntity();
        source.setId(SOURCE_ID);
        source.setName("DISC-006 isolated H2");
        source.setKind("H2");
        source.setJdbcUrl(url);
        source.setUsername("sa");
        source.setPassword("");
        source.setRole("SOURCE");
        when(sources.getSourceCapable(SOURCE_ID)).thenReturn(source);
        when(classifications.findByDataSourceIdAndSchemaName(any(), anyString())).thenReturn(List.of());

        PiiPatternService patterns = mock(PiiPatternService.class);
        when(patterns.resolveEffective()).thenReturn(new PiiPatternService.Effective(Map.of(), Map.of(), Map.of()));
        discovery = new DiscoveryService(classifications, sources, connections,
                mock(MaskingPolicyRepository.class), mock(MaskingRuleRepository.class), audit, new ForgeProps(), patterns);
    }

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
        connections.destroy();
    }

    @Test
    void rejectsEmptyFlywayOnlyMissingAndMissingFocusedScopesBeforeScanWork() {
        ApiException empty = assertThrows(ApiException.class,
                () -> discovery.validateScanScope(SOURCE_ID, "EMPTY_SCOPE", Set.of()));
        assertTrue(empty.getMessage().toLowerCase().contains("no scannable tables"), empty::getMessage);

        ApiException flyway = assertThrows(ApiException.class,
                () -> discovery.validateScanScope(SOURCE_ID, "FLYWAY_SCOPE", Set.of()));
        assertTrue(flyway.getMessage().toLowerCase().contains("no scannable tables"), flyway::getMessage);

        ApiException missingSchema = assertThrows(ApiException.class,
                () -> discovery.validateScanScope(SOURCE_ID, "MISSING_SCOPE", Set.of()));
        assertTrue(missingSchema.getMessage().contains("does not exist or is not visible"), missingSchema::getMessage);
        assertFalse(missingSchema.getMessage().contains(url));

        ApiException missingFocus = assertThrows(ApiException.class,
                () -> discovery.validateScanScope(SOURCE_ID, "POPULATED_SCOPE", Set.of("not_a_table")));
        assertTrue(missingFocus.getMessage().contains("not_a_table"));
        assertTrue(discovery.validateScanScope(SOURCE_ID, "POPULATED_SCOPE", Set.of()).contains("CUSTOMERS"));
    }

    @Test
    void syncAndAsyncRejectionsLeaveNoFalseSuccessAndEmitSanitizedAuditEvidence() {
        ApiException sync = assertThrows(ApiException.class,
                () -> discovery.scan(SOURCE_ID, "EMPTY_SCOPE", Set.of(), Set.of(), null));
        assertTrue(sync.getMessage().toLowerCase().contains("no scannable tables"), sync::getMessage);
        verify(classifications, never()).save(any());
        verify(classifications, never()).deleteAll(any());
        verify(audit).record(eq("system"), eq("DISCOVERY_SCAN_REJECTED"), eq("DISCOVERY"),
                eq("DISCOVERY_SCOPE"), eq("datasource:" + SOURCE_ID), eq("EMPTY_SCOPE"), eq("FAILURE"),
                eq("reason=NO_SCANNABLE_TABLES"), eq("{\"schema\":\"EMPTY_SCOPE\",\"reason\":\"NO_SCANNABLE_TABLES\"}"));

        DiscoveryJobService jobs = new DiscoveryJobService(discovery, executor, audit);
        ApiException async = assertThrows(ApiException.class,
                () -> jobs.start(SOURCE_ID, "EMPTY_SCOPE", Set.of(), Set.of()));
        assertTrue(async.getMessage().contains("no scannable tables"));
        assertTrue(jobs.list(SOURCE_ID, "EMPTY_SCOPE").isEmpty(), "rejected scope must not create a history job");
        verify(audit).record(eq("system"), eq("DISCOVERY_JOB_REJECTED"), eq("DISCOVERY"),
                eq("DISCOVERY_SCOPE"), eq("datasource:" + SOURCE_ID), eq("EMPTY_SCOPE"), eq("FAILURE"),
                eq("reason=NO_SCANNABLE_TABLES"), eq("{\"schema\":\"EMPTY_SCOPE\",\"reason\":\"NO_SCANNABLE_TABLES\",\"selectedTables\":0}"));
        verify(audit, never()).record(anyString(), eq("DISCOVERY_JOB_QUEUED"), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any());
        verify(audit, never()).record(anyString(), eq("DISCOVERY_JOB_COMPLETED"), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }
}
