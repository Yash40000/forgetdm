package io.forgetdm.businessentity;

import io.forgetdm.audit.AuditService;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.virtualization.VirtualizationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BusinessEntityEnterpriseOpsTest {

    @Test
    void createsEvidenceOnlyEntitySnapshotWithMemberManifest() {
        BusinessEntityService entities = mock(BusinessEntityService.class);
        BusinessEntitySnapshotRepository snapshots = mock(BusinessEntitySnapshotRepository.class);
        BusinessEntitySnapshotMemberRepository members = mock(BusinessEntitySnapshotMemberRepository.class);
        DataSourceService dataSources = mock(DataSourceService.class);
        AuditService audit = mock(AuditService.class);

        BusinessEntityDefinitionEntity entity = entity("Customer 360");
        BusinessEntityMemberEntity member = member(11L, 101L, "PUBLIC", "customers", "customer_id");
        when(entities.getDetail(1L)).thenReturn(new BusinessEntityService.BusinessEntityDetail(entity, List.of(member), null, java.util.Map.of()));
        when(snapshots.save(any())).thenAnswer(inv -> {
            BusinessEntitySnapshotEntity s = inv.getArgument(0);
            if (s.getId() == null) ReflectionTestUtils.setField(s, "id", 77L);
            return s;
        });
        when(members.saveAll(any())).thenAnswer(inv -> new ArrayList<>(inv.getArgument(0)));

        BusinessEntitySnapshotService service = new BusinessEntitySnapshotService(
                entities, snapshots, members, dataSources, new ConnectionFactory(), mock(VirtualizationService.class), audit);
        BusinessEntitySnapshotService.SnapshotDetail detail = service.create(1L,
                new BusinessEntitySnapshotService.SnapshotRequest("QA baseline", "ENTITY_BOOKMARK",
                        "EVIDENCE_ONLY", "before test cycle", 30, null, null));

        assertEquals("QA baseline", detail.snapshot().getName());
        assertEquals("EVIDENCE_ONLY", detail.snapshot().getCaptureMode());
        assertEquals("AVAILABLE", detail.snapshot().getStatus());
        assertEquals(1, detail.members().size());
        assertEquals("customers", detail.members().get(0).getTableName());
        assertNull(detail.members().get(0).getVirtualSnapshotId());
        assertNotNull(detail.snapshot().getRollbackPlanJson());
        verify(audit).log(any(), eq("BUSINESS_ENTITY_SNAPSHOT_CREATE"), any());
    }

    @Test
    void blocksOverlappingEntityReservationKeys() throws Exception {
        String url = "jdbc:h2:mem:be_reservation_ops;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE customers (customer_id BIGINT PRIMARY KEY, status VARCHAR(20))");
            st.execute("INSERT INTO customers(customer_id, status) VALUES (100, 'ACTIVE'), (101, 'ACTIVE')");
        }

        BusinessEntityService entities = mock(BusinessEntityService.class);
        BusinessEntityReservationRepository reservations = mock(BusinessEntityReservationRepository.class);
        BusinessEntityReservationMemberRepository reservationMembers = mock(BusinessEntityReservationMemberRepository.class);
        BusinessEntitySnapshotRepository snapshots = mock(BusinessEntitySnapshotRepository.class);
        DataSourceService dataSources = mock(DataSourceService.class);
        AuditService audit = mock(AuditService.class);
        AtomicReference<BusinessEntityReservationEntity> active = new AtomicReference<>();

        BusinessEntityDefinitionEntity entity = entity("Customer 360");
        entity.setRootTable("customers");
        entity.setBusinessKeyColumns("customer_id");
        BusinessEntityMemberEntity member = member(11L, 101L, null, "customers", "customer_id");
        when(entities.getDetail(1L)).thenReturn(new BusinessEntityService.BusinessEntityDetail(entity, List.of(member), null, java.util.Map.of()));
        when(dataSources.get(101L)).thenReturn(source(101L, url));
        when(reservations.findByEntityIdAndStatus(1L, "ACTIVE")).thenAnswer(inv -> active.get() == null ? List.of() : List.of(active.get()));
        when(reservations.save(any())).thenAnswer(inv -> {
            BusinessEntityReservationEntity r = inv.getArgument(0);
            if (r.getId() == null) ReflectionTestUtils.setField(r, "id", 55L);
            active.set(r);
            return r;
        });
        when(reservationMembers.saveAll(any())).thenAnswer(inv -> new ArrayList<>(inv.getArgument(0)));

        BusinessEntityReservationService service = new BusinessEntityReservationService(
                entities, reservations, reservationMembers, snapshots, dataSources, new ConnectionFactory(), audit);
        BusinessEntityReservationService.ReservationDetail first = service.reserve(1L,
                new BusinessEntityReservationService.ReservationRequest("cycle", null, "qa1", null,
                        "testing", "UAT", "status = 'ACTIVE'", 1, 24, "BLOCK", null, null));

        assertEquals("ACTIVE", first.reservation().getStatus());
        assertTrue(first.reservation().getBusinessKeyValuesJson().contains("100"));

        var conflict = assertThrows(io.forgetdm.common.ApiException.class, () -> service.reserve(1L,
                new BusinessEntityReservationService.ReservationRequest("cycle2", null, "qa2", null,
                        "testing", "UAT", "status = 'ACTIVE'", 1, 24, "BLOCK", null, null)));
        assertTrue(conflict.getMessage().contains("already reserved"));
        assertEquals(Instant.class, first.reservation().getExpiresAt().getClass());
    }

    private static BusinessEntityDefinitionEntity entity(String name) {
        BusinessEntityDefinitionEntity e = new BusinessEntityDefinitionEntity();
        e.setId(1L);
        e.setName(name);
        return e;
    }

    private static BusinessEntityMemberEntity member(Long id, Long sourceId, String schema, String table, String keys) {
        BusinessEntityMemberEntity m = new BusinessEntityMemberEntity();
        m.setId(id);
        m.setDataSourceId(sourceId);
        m.setSchemaName(schema);
        m.setTableName(table);
        m.setLogicalRole(table);
        m.setKeyColumns(keys);
        return m;
    }

    private static DataSourceEntity source(Long id, String url) {
        DataSourceEntity ds = new DataSourceEntity();
        ds.setId(id);
        ds.setName("h2-source");
        ds.setKind("H2");
        ds.setRole("SOURCE");
        ds.setJdbcUrl(url);
        return ds;
    }
}
