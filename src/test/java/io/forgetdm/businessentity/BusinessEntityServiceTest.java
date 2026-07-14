package io.forgetdm.businessentity;

import io.forgetdm.audit.AuditService;
import io.forgetdm.dataset.DataSetDefinitionEntity;
import io.forgetdm.dataset.DataSetDefinitionRepository;
import io.forgetdm.dataset.DataSetService;
import io.forgetdm.dataset.TableProfileEntity;
import io.forgetdm.datasource.DataSourceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BusinessEntityServiceTest {

    @Test
    void createsBusinessEntityFromDataScopeProfiles() {
        BusinessEntityDefinitionRepository defs = mock(BusinessEntityDefinitionRepository.class);
        BusinessEntityMemberRepository members = mock(BusinessEntityMemberRepository.class);
        DataSetDefinitionRepository datasets = mock(DataSetDefinitionRepository.class);
        DataSetService dataSetService = mock(DataSetService.class);
        DataSourceRepository dataSources = mock(DataSourceRepository.class);
        AuditService audit = mock(AuditService.class);
        AtomicReference<BusinessEntityDefinitionEntity> savedDef = new AtomicReference<>();
        AtomicReference<List<BusinessEntityMemberEntity>> savedMembers = new AtomicReference<>(List.of());
        AtomicLong memberIds = new AtomicLong(100L);

        DataSetDefinitionEntity ds = new DataSetDefinitionEntity();
        ReflectionTestUtils.setField(ds, "id", 7L);
        ds.setName("retail-customer-scope");
        ds.setDataSourceId(10L);
        ds.setSchemaName("public");
        ds.setDriverTable("customers");

        TableProfileEntity customer = profile(7L, "customers", null, null, true);
        TableProfileEntity account = profile(7L, "accounts", 20L, "finance", false);

        when(dataSetService.get(7L)).thenReturn(ds);
        when(dataSetService.listProfiles(7L)).thenReturn(List.of(customer, account));
        when(dataSetService.listUserRels(7L)).thenReturn(List.of());
        when(dataSetService.customPkMap(7L)).thenReturn(Map.of("customers", "customer_id", "accounts", "account_id"));
        when(datasets.existsById(7L)).thenReturn(true);
        when(datasets.findById(7L)).thenReturn(Optional.of(ds));
        when(dataSources.existsById(10L)).thenReturn(true);
        when(dataSources.existsById(20L)).thenReturn(true);
        when(dataSources.findAllById(any())).thenReturn(List.of());
        when(defs.findByName(any())).thenReturn(Optional.empty());
        when(defs.findById(1L)).thenAnswer(inv -> Optional.ofNullable(savedDef.get()));
        when(defs.save(any())).thenAnswer(inv -> {
            BusinessEntityDefinitionEntity e = inv.getArgument(0);
            if (e.getId() == null) ReflectionTestUtils.setField(e, "id", 1L);
            savedDef.set(e);
            return e;
        });
        when(members.saveAll(any())).thenAnswer(inv -> {
            List<BusinessEntityMemberEntity> rows = new ArrayList<>(inv.getArgument(0));
            rows.forEach(row -> {
                if (row.getId() == null) ReflectionTestUtils.setField(row, "id", memberIds.getAndIncrement());
            });
            savedMembers.set(rows);
            return rows;
        });
        when(members.findByEntityIdOrderByOrdinalNoAscIdAsc(1L)).thenAnswer(inv -> savedMembers.get());

        BusinessEntityService service = new BusinessEntityService(defs, members, datasets, dataSetService, dataSources, audit);
        BusinessEntityService.BusinessEntityDetail detail = service.createFromDataset(7L,
                new BusinessEntityService.FromDatasetRequest("Customer 360", "Retail customer graph", "Retail Banking"));

        assertEquals("Customer 360", detail.entity().getName());
        assertEquals("customers", detail.entity().getRootTable());
        assertEquals("customer_id", detail.entity().getBusinessKeyColumns());
        assertEquals(2, detail.members().size());
        assertEquals(10L, detail.members().get(0).getDataSourceId());
        assertEquals("public", detail.members().get(0).getSchemaName());
        assertEquals(20L, detail.members().get(1).getDataSourceId());
        assertEquals("finance", detail.members().get(1).getSchemaName());
        assertEquals(false, detail.members().get(1).isIncludeInSubset());
        assertNotNull(detail.entity().getUpdatedAt());

        List<Long> originalIds = detail.members().stream().map(BusinessEntityMemberEntity::getId).toList();
        List<BusinessEntityMemberEntity> resaved = service.replaceMembers(1L, detail.members());
        assertEquals(originalIds, resaved.stream().map(BusinessEntityMemberEntity::getId).toList());
        verify(members, never()).deleteAll(any());
        verify(audit, atLeastOnce()).log(any(), startsWith("BUSINESS_ENTITY"), any());
    }

    private static TableProfileEntity profile(Long datasetId, String table, Long sourceId, String schema, boolean included) {
        TableProfileEntity p = new TableProfileEntity();
        p.setDatasetId(datasetId);
        p.setTableName(table);
        p.setSourceDataSourceId(sourceId);
        p.setSourceSchemaName(schema);
        p.setIncluded(included);
        return p;
    }
}
