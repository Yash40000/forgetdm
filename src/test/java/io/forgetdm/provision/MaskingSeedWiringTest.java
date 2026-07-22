package io.forgetdm.provision;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.config.ForgeProps;
import io.forgetdm.core.mask.MaskingEngine;
import io.forgetdm.dataset.DataSetDefinitionEntity;
import io.forgetdm.dataset.DataSetService;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.policy.MaskingRuleRepository;
import io.forgetdm.ri.RiRegistryService;
import io.forgetdm.security.AccessControlService;
import io.forgetdm.security.GovernedReferenceGuard;
import io.forgetdm.security.OwnershipGuard;
import io.forgetdm.subset.SubsetService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MaskingSeedWiringTest {

    @Test
    void dataScopePreviewForwardsRawSeedToCanonicalEngineNormalization() throws Exception {
        RecordingEngine engine = new RecordingEngine();
        DataSetService datasets = mock(DataSetService.class);
        DataSourceService dataSources = mock(DataSourceService.class);
        ConnectionFactory connections = mock(ConnectionFactory.class);
        DataSetDefinitionEntity definition = mock(DataSetDefinitionEntity.class);
        DataSourceEntity dataSource = mock(DataSourceEntity.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);

        when(datasets.assertAuthorizedReferences(91L, null)).thenReturn(definition);
        when(datasets.listProfiles(91L)).thenReturn(List.of());
        when(definition.getDataSourceId()).thenReturn(4L);
        when(definition.getSchemaName()).thenReturn("public");
        when(dataSources.get(4L)).thenReturn(dataSource);
        when(connections.openPooled(dataSource)).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT * FROM public.customers")).thenReturn(resultSet);
        when(resultSet.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnLabel(1)).thenReturn("customer_id");
        when(resultSet.next()).thenReturn(false);

        DataScopeMaskPreviewService service = new DataScopeMaskPreviewService(
                datasets, dataSources, connections, engine, mock(MaskingRuleRepository.class));
        service.preview(91L, new DataScopeMaskPreviewService.PreviewRequest(
                "customers", null, "  Mixed Case Seed  ", 5,
                List.of(new DataScopeMaskPreviewService.PreviewColumn(
                        "customer_id", "customer_id", "USE_POLICY", null))));

        assertEquals(List.of("  Mixed Case Seed  "), engine.seeds);
        verify(statement).setMaxRows(5);
    }

    @Test
    void provisioningUsesRawNonblankSeedAndTreatsWhitespaceOnlyAsDefault() throws Exception {
        RecordingEngine engine = new RecordingEngine();
        AuditService audit = mock(AuditService.class);
        ProvisioningService service = provisioningService(engine, audit);
        ProvisionJobEntity job = new ProvisionJobEntity();
        ObjectMapper json = new ObjectMapper();

        MaskingEngine seeded = ReflectionTestUtils.invokeMethod(service, "engineFor",
                json.readTree("{\"maskingSeed\":\"  Mixed Case Seed  \"}"), job);
        MaskingEngine defaultForBlank = ReflectionTestUtils.invokeMethod(service, "engineFor",
                json.readTree("{\"maskingSeed\":\"   \"}"), job);
        MaskingEngine defaultForMissing = ReflectionTestUtils.invokeMethod(service, "engineFor",
                json.readTree("{}"), job);

        assertEquals(List.of("  Mixed Case Seed  "), engine.seeds);
        assertSame(engine, defaultForBlank);
        assertSame(engine, defaultForMissing);
        assertEquals(engine.withSeed("Mixed Case Seed").pushdownKey(), seeded.pushdownKey());
        verify(audit).log(eq("system"), eq("MASKING_SEED_USED"), any(String.class));
    }

    private static ProvisioningService provisioningService(MaskingEngine engine, AuditService audit) {
        return new ProvisioningService(
                mock(ProvisionJobRepository.class),
                mock(MaskingRuleRepository.class),
                mock(DataSourceService.class),
                mock(ConnectionFactory.class),
                engine,
                mock(SubsetService.class),
                mock(DataSetService.class),
                audit,
                new ForgeProps(),
                mock(ExecutorService.class),
                mock(RiRegistryService.class),
                mock(DbMaskPushdown.class),
                mock(AccessControlService.class),
                new OwnershipGuard(audit),
                mock(GovernedReferenceGuard.class));
    }

    private static final class RecordingEngine extends MaskingEngine {
        private final List<String> seeds = new ArrayList<>();

        private RecordingEngine() {
            super("mask003-wiring-secret");
        }

        @Override
        public MaskingEngine withSeed(String seed) {
            seeds.add(seed);
            return super.withSeed(seed);
        }
    }
}
