package io.forgetdm.businessentity;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.security.GovernedReferenceGuard;
import io.forgetdm.virtualization.VirtualizationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
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
    void createsEvidenceOnlyEntitySnapshotWithVerifiedMemberCount() throws Exception {
        BusinessEntityService entities = mock(BusinessEntityService.class);
        BusinessEntitySnapshotRepository snapshots = mock(BusinessEntitySnapshotRepository.class);
        BusinessEntitySnapshotMemberRepository members = mock(BusinessEntitySnapshotMemberRepository.class);
        DataSourceService dataSources = mock(DataSourceService.class);
        AuditService audit = mock(AuditService.class);

        BusinessEntityDefinitionEntity entity = entity("Customer 360");
        String url = "jdbc:h2:mem:be_snapshot_ops;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS \"customers\" (\"customer_id\" BIGINT PRIMARY KEY)");
            st.execute("DELETE FROM \"customers\"");
            st.execute("INSERT INTO \"customers\"(\"customer_id\") VALUES (1), (2), (3)");
        }
        BusinessEntityMemberEntity member = member(11L, 101L, null, "customers", "customer_id");
        when(entities.getDetail(1L)).thenReturn(new BusinessEntityService.BusinessEntityDetail(entity, List.of(member), null, java.util.Map.of()));
        when(dataSources.get(101L)).thenReturn(source(101L, url));
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
        assertEquals(3, detail.members().get(0).getRowCount());
        assertTrue(detail.members().get(0).getEvidenceJson().contains("\"countVerified\":true"));
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
                entities, reservations, reservationMembers, snapshots, dataSources, new ConnectionFactory(), audit,
                mock(BusinessEntityCapsuleService.class), mock(GovernedReferenceGuard.class));
        BusinessEntityReservationService.ReservationDetail first = service.reserve(1L,
                new BusinessEntityReservationService.ReservationRequest("cycle", null, "qa1", null,
                        "testing", "UAT", "status = 'ACTIVE'", 1, 24, "BLOCK", null, null, null, null));

        assertEquals("ACTIVE", first.reservation().getStatus());
        assertTrue(first.reservation().getBusinessKeyValuesJson().contains("100"));

        var conflict = assertThrows(io.forgetdm.common.ApiException.class, () -> service.reserve(1L,
                new BusinessEntityReservationService.ReservationRequest("cycle2", null, "qa2", null,
                        "testing", "UAT", "status = 'ACTIVE'", 1, 24, "BLOCK", null, null, null, null)));
        assertTrue(conflict.getMessage().contains("already reserved"));
        assertEquals(Instant.class, first.reservation().getExpiresAt().getClass());
    }

    @Test
    void deniesBetaSnapshotRawIdBeforeMemberReadOrRollback() {
        BusinessEntityService entities = mock(BusinessEntityService.class);
        BusinessEntitySnapshotRepository snapshots = mock(BusinessEntitySnapshotRepository.class);
        BusinessEntitySnapshotMemberRepository members = mock(BusinessEntitySnapshotMemberRepository.class);
        VirtualizationService virtualization = mock(VirtualizationService.class);
        BusinessEntitySnapshotEntity beta = new BusinessEntitySnapshotEntity();
        beta.setId(77L);
        beta.setEntityId(2L);
        beta.setStatus("AVAILABLE");
        when(snapshots.findById(77L)).thenReturn(Optional.of(beta));
        when(entities.getDetail(2L)).thenThrow(new ApiException(HttpStatus.FORBIDDEN, "BETA entity"));
        BusinessEntitySnapshotService service = new BusinessEntitySnapshotService(
                entities, snapshots, members, mock(DataSourceService.class), new ConnectionFactory(),
                virtualization, mock(AuditService.class));

        assertThrows(ApiException.class, () -> service.detail(77L));
        assertThrows(ApiException.class, () -> service.rollback(77L, null));

        verify(members, never()).findBySnapshotIdOrderByIdAsc(anyLong());
        verifyNoInteractions(virtualization);
        verify(snapshots, never()).save(any());
    }

    @Test
    void deniesBetaReservationRawIdBeforeReadOrReleaseMutation() {
        BusinessEntityService entities = mock(BusinessEntityService.class);
        BusinessEntityReservationRepository reservations = mock(BusinessEntityReservationRepository.class);
        BusinessEntityReservationMemberRepository members = mock(BusinessEntityReservationMemberRepository.class);
        BusinessEntityReservationEntity beta = new BusinessEntityReservationEntity();
        beta.setId(55L);
        beta.setEntityId(2L);
        beta.setStatus("ACTIVE");
        when(reservations.findById(55L)).thenReturn(Optional.of(beta));
        when(entities.getDetail(2L)).thenThrow(new ApiException(HttpStatus.FORBIDDEN, "BETA entity"));
        BusinessEntityReservationService service = new BusinessEntityReservationService(
                entities, reservations, members, mock(BusinessEntitySnapshotRepository.class),
                mock(DataSourceService.class), new ConnectionFactory(), mock(AuditService.class),
                mock(BusinessEntityCapsuleService.class), mock(GovernedReferenceGuard.class));

        assertThrows(ApiException.class, () -> service.detail(55L));
        assertThrows(ApiException.class, () -> service.release(55L));

        verify(members, never()).findByReservationIdOrderByIdAsc(anyLong());
        verify(reservations, never()).save(any());
        assertEquals("ACTIVE", beta.getStatus());
    }

    @Test
    void validatesCapsulePolicyBeforeReservationPersistence() {
        BusinessEntityService entities = mock(BusinessEntityService.class);
        BusinessEntityReservationRepository reservations = mock(BusinessEntityReservationRepository.class);
        BusinessEntityReservationMemberRepository members = mock(BusinessEntityReservationMemberRepository.class);
        GovernedReferenceGuard references = mock(GovernedReferenceGuard.class);
        BusinessEntityCapsuleService capsules = mock(BusinessEntityCapsuleService.class);
        BusinessEntityDefinitionEntity definition = entity("Alpha entity");
        definition.setRootTable("customers");
        definition.setBusinessKeyColumns("customer_id");
        BusinessEntityMemberEntity root = member(11L, 101L, null, "customers", "customer_id");
        when(entities.getDetail(1L)).thenReturn(new BusinessEntityService.BusinessEntityDetail(
                definition, List.of(root), null, java.util.Map.of()));
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "BETA policy")).when(references).policy(900L);
        BusinessEntityReservationService service = new BusinessEntityReservationService(
                entities, reservations, members, mock(BusinessEntitySnapshotRepository.class),
                mock(DataSourceService.class), new ConnectionFactory(), mock(AuditService.class), capsules, references);

        assertThrows(ApiException.class, () -> service.reserve(1L,
                new BusinessEntityReservationService.ReservationRequest("blocked", null, "spoof", "BETA",
                        "test", "UAT", null, 1, 24, "BLOCK",
                        List.of(java.util.Map.of("customer_id", 100L)), null, true, 900L)));

        verify(reservations, never()).save(any());
        verify(members, never()).saveAll(any());
        verifyNoInteractions(capsules);
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
