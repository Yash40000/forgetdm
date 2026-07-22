package io.forgetdm.cdc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.datasource.SqlDialect;
import io.forgetdm.virtualization.ChunkStore;
import io.forgetdm.virtualization.SnapshotManifest;
import io.forgetdm.virtualization.TimeFlowEngine;
import io.forgetdm.virtualization.TimeFlowEntity;
import io.forgetdm.virtualization.TimeFlowRepository;
import io.forgetdm.virtualization.VirtOps;
import io.forgetdm.virtualization.VirtualDatabaseEntity;
import io.forgetdm.virtualization.VirtualSnapshotEntity;
import io.forgetdm.virtualization.VirtualSnapshotRepository;
import io.forgetdm.virtualization.VirtualizationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CdcTimeFlowServiceTest {

    @TempDir Path temp;

    @Test
    void anchorPlusBufferedChangeProducesAuditablePointSnapshotAndExpectedRows() throws Exception {
        String sourceUrl = "jdbc:h2:mem:cdc-timeflow-source;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try (Connection sourceConnection = DriverManager.getConnection(sourceUrl, "sa", "")) {
            sourceConnection.createStatement().execute(
                    "CREATE TABLE public.customers (id BIGINT PRIMARY KEY, name VARCHAR(80), status VARCHAR(20))");
            sourceConnection.createStatement().execute(
                    "INSERT INTO public.customers VALUES (1, 'Alice', 'ACTIVE')");
        }

        DataSourceEntity source = new DataSourceEntity();
        source.setId(1L);
        source.setName("core-source");
        source.setKind("H2");
        source.setRole("SOURCE");
        source.setJdbcUrl(sourceUrl);
        source.setUsername("sa");
        source.setPassword("");

        CdcService cdc = mock(CdcService.class);
        CdcService.CdcCheckpoint anchorBoundary =
                new CdcService.CdcCheckpoint(7L, "public", "ACTIVE", "0/10", 0);
        CdcService.CdcCheckpoint latestBoundary =
                new CdcService.CdcCheckpoint(7L, "public", "ACTIVE", "0/20", 1);
        when(cdc.checkpoint(1L)).thenReturn(anchorBoundary, anchorBoundary, latestBoundary);
        when(cdc.poll(1L)).thenReturn(new CdcService.PollSummary(1L, 0, 0, "0/10", true),
                new CdcService.PollSummary(1L, 1, 1, "0/20", true));

        CdcChangeEntity update = new CdcChangeEntity();
        update.setId(1L);
        update.setCaptureId(7L);
        update.setDataSourceId(1L);
        update.setSchemaName("public");
        update.setTableName("customers");
        update.setOp("U");
        update.setPkJson("{\"id\":\"1\"}");
        update.setChangeJson("{\"name\":\"Alicia\"}");
        update.setLsn("0/20");
        update.setCapturedAt(Instant.parse("2026-07-21T18:00:00Z"));
        when(cdc.loadBufferedChanges(7L, 0L)).thenReturn(List.of(update));

        DataSourceService dataSources = mock(DataSourceService.class);
        when(dataSources.getSourceCapable(1L)).thenReturn(source);
        ConnectionFactory connections = mock(ConnectionFactory.class);
        when(connections.openForBulk(source)).thenAnswer(ignored -> DriverManager.getConnection(sourceUrl, "sa", ""));

        VirtualSnapshotRepository snapshots = mock(VirtualSnapshotRepository.class);
        AtomicLong snapshotIds = new AtomicLong(10);
        List<VirtualSnapshotEntity> savedSnapshots = new ArrayList<>();
        when(snapshots.save(any(VirtualSnapshotEntity.class))).thenAnswer(invocation -> {
            VirtualSnapshotEntity value = invocation.getArgument(0);
            if (value.getId() == null) value.setId(snapshotIds.getAndIncrement());
            savedSnapshots.add(value);
            return value;
        });
        TimeFlowRepository timeflows = mock(TimeFlowRepository.class);
        when(timeflows.findFirstBySourceIdAndContainerTypeAndSchemaName(1L, "DSOURCE", "public"))
                .thenReturn(Optional.empty());
        when(timeflows.save(any(TimeFlowEntity.class))).thenAnswer(invocation -> {
            TimeFlowEntity value = invocation.getArgument(0);
            value.setId(5L);
            return value;
        });

        ChunkStore pool = new ChunkStore(temp.resolve("pool"));
        TimeFlowEngine engine = new TimeFlowEngine(pool);
        VirtualizationService virtualization = mock(VirtualizationService.class);
        VirtualDatabaseEntity provisioned = new VirtualDatabaseEntity();
        provisioned.setId(99L);
        when(virtualization.provision(any(), any(), any(), any(), any())).thenReturn(provisioned);

        CdcTimeFlowService service = new CdcTimeFlowService(cdc, dataSources, connections,
                new CdcIncrementalApplier(new ObjectMapper()), snapshots, timeflows, engine,
                virtualization, new VirtOps(), mock(AuditService.class));

        VirtualSnapshotEntity anchor = service.createAnchor(1L, "public", "Core CDC anchor");
        Map<String, Object> result = service.provisionAtPoint(1L, anchor, "qa-asof", null, 1L, null);

        assertEquals(99L, result.get("vdbId"));
        assertEquals(1, result.get("changesReplayed"));
        VirtualSnapshotEntity point = savedSnapshots.get(savedSnapshots.size() - 1);
        assertEquals("CDC_POINT_IN_TIME", point.getSnapshotType());
        assertEquals(anchor.getId(), point.getCdcBaseSnapshotId());
        assertEquals(1L, point.getCdcThroughChangeId());
        assertEquals(1, point.getCdcChangesApplied());
        assertNotEquals(anchor.getManifestHash(), point.getManifestHash());

        SnapshotManifest manifest = engine.loadManifest(point.getManifestHash());
        try (Connection verify = DriverManager.getConnection(
                "jdbc:h2:mem:cdc-timeflow-verify;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE", "sa", "")) {
            engine.materialize(verify, SqlDialect.H2, manifest);
            try (ResultSet row = verify.createStatement().executeQuery(
                    "SELECT name, status FROM public.customers WHERE id = 1")) {
                row.next();
                assertEquals("Alicia", row.getString(1));
                assertEquals("ACTIVE", row.getString(2));
            }
        }
    }
}
