package io.forgetdm.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.filevault.ManagedFileVault;
import io.forgetdm.query.QueryService;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessControlService;
import io.forgetdm.security.AccessPrincipal;
import io.forgetdm.security.OwnershipGuard;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MappingObjectTenancyTest {

    @Test
    void mappingDefinitionsFilterAndGuardEveryParentAndVersionPath() {
        MappingRepository mappings = mock(MappingRepository.class);
        MappingVersionRepository versions = mock(MappingVersionRepository.class);
        AuditService audit = mock(AuditService.class);
        MappingService service = mappingService(mappings, versions, mock(MappingFileAssetRepository.class), audit);
        MappingEntity alpha = mapping(1L, "alpha mapping", 1L, 10L, OwnershipGuard.GROUP);
        MappingEntity beta = mapping(2L, "beta mapping", 2L, 20L, OwnershipGuard.GROUP);
        MappingEntity shared = mapping(3L, "shared mapping", 2L, 20L, OwnershipGuard.SHARED);
        MappingEntity privateAlpha = mapping(4L, "private mapping", 1L, 10L, OwnershipGuard.PRIVATE);
        when(mappings.findAll()).thenReturn(List.of(alpha, beta, shared, privateAlpha));
        when(mappings.findById(1L)).thenReturn(Optional.of(alpha));

        assertEquals(List.of(3L, 1L), ids(as(alphaMember(), service::list)));
        assertEquals(List.of(4L, 3L, 2L, 1L), ids(as(admin(), service::list)));
        assertEquals(List.of(4L, 3L, 2L, 1L), ids(service.list()));
        assertSame(alpha, as(alphaMember(), () -> service.get(1L)));

        assertForbidden(() -> as(betaManager(), () -> service.get(1L)));
        assertForbidden(() -> as(betaManager(), () -> { service.delete(1L); return null; }));
        assertForbidden(() -> as(betaManager(), () -> service.versions(1L)));
        assertForbidden(() -> as(betaManager(), () -> service.restoreVersion(1L, 91L)));
        verify(mappings, never()).deleteById(1L);
        verify(versions, never()).findByMappingIdOrderByVersionNoDesc(1L);
    }

    @Test
    void mappingSaveStampsTrustedOwnershipAndVersionRestoreChecksItsParent() {
        MappingRepository mappings = mock(MappingRepository.class);
        MappingVersionRepository versions = mock(MappingVersionRepository.class);
        AuditService audit = mock(AuditService.class);
        MappingService service = mappingService(mappings, versions, mock(MappingFileAssetRepository.class), audit);
        when(mappings.findByNameIgnoreCase("alpha new mapping")).thenReturn(Optional.empty());
        when(mappings.save(any(MappingEntity.class))).thenAnswer(call -> {
            MappingEntity value = call.getArgument(0);
            if (value.getId() == null) setId(value, 11L);
            return value;
        });
        when(versions.findByMappingIdOrderByVersionNoDesc(11L)).thenReturn(List.of());

        MappingEntity request = new MappingEntity();
        request.setName("alpha new mapping");
        request.setSpecJson("{}");
        request.setOwnerUserId(999L);
        request.setOwnerUsername("spoofed");
        request.setOwnerGroupId(999L);
        request.setVisibility(OwnershipGuard.SHARED);
        MappingEntity saved = as(alphaOwner(), () -> service.save(request));

        assertEquals(1L, saved.getOwnerUserId());
        assertEquals("alpha", saved.getOwnerUsername());
        assertEquals(10L, saved.getOwnerGroupId());
        assertEquals(OwnershipGuard.GROUP, saved.getVisibility());
        verify(versions).save(any(MappingVersionEntity.class));

        MappingVersionEntity foreignVersion = version(91L, 999L);
        when(mappings.findById(11L)).thenReturn(Optional.of(saved));
        when(versions.findById(91L)).thenReturn(Optional.of(foreignVersion));
        clearInvocations(mappings, versions);

        assertThrows(ApiException.class, () -> as(alphaOwner(), () -> service.restoreVersion(11L, 91L)));
        verify(mappings, never()).save(any(MappingEntity.class));
        verify(versions, never()).save(any(MappingVersionEntity.class));
    }

    @Test
    void mappingValidationRejectsManagedAssetsOutsideTheCallerTenant() throws Exception {
        MappingFileAssetRepository assets = mock(MappingFileAssetRepository.class);
        AuditService audit = mock(AuditService.class);
        MappingService service = mappingService(mock(MappingRepository.class),
                mock(MappingVersionRepository.class), assets, audit);
        MappingFileAssetEntity beta = asset(41L, 2L, 20L, OwnershipGuard.GROUP);
        when(assets.findById(41L)).thenReturn(Optional.of(beta));
        var spec = new ObjectMapper().readTree("""
                {"specVersion":2,"sources":[{"type":"FILE","assetId":41,"alias":"input"}],
                 "target":{"type":"PREVIEW"}}
                """);

        Map<String, Object> denied = as(alphaOwner(), () -> service.validateSpec(spec));
        assertFalse((Boolean) denied.get("valid"));
        assertTrue(String.valueOf(denied.get("errors")).contains("not available"));
        assertTrue((Boolean) as(betaManager(), () -> service.validateSpec(spec)).get("valid"));
    }

    @Test
    void mappingValidationBlocksForeignTargetsAndLegacyConnectorReferencesBeforeAsyncExecution() throws Exception {
        DataSourceService sources = mock(DataSourceService.class);
        MappingFileAssetRepository assets = mock(MappingFileAssetRepository.class);
        AuditService audit = mock(AuditService.class);
        MappingService service = new MappingService(mock(MappingRepository.class), mock(QueryService.class), sources,
                mock(ConnectionFactory.class), audit, new ObjectMapper(), mock(MappingVersionRepository.class), assets,
                new OwnershipGuard(audit));
        when(assets.findById(41L)).thenReturn(Optional.of(asset(41L, 1L, 10L, OwnershipGuard.GROUP)));
        when(sources.get(700L)).thenThrow(ApiException.forbidden("foreign data source"));
        ObjectMapper json = new ObjectMapper();

        var target = json.readTree("""
                {"specVersion":2,"sources":[{"type":"FILE","assetId":41,"alias":"input"}],
                 "target":{"type":"DATABASE","dataSourceId":700,"table":"customers"}}
                """);
        Map<String, Object> targetResult = as(alphaOwner(), () -> service.validateSpec(target));
        assertFalse((Boolean) targetResult.get("valid"));
        assertTrue(String.valueOf(targetResult.get("errors")).contains("Target data source is not available"));

        var legacy = json.readTree("""
                {"srcDsId":700,"tables":[{"dsId":700,"name":"customers"}],
                 "target":{"mode":"TABLE","dsId":700,"table":"masked_customers"},
                 "loadStatements":["INSERT INTO masked_customers SELECT * FROM customers"]}
                """);
        Map<String, Object> legacyResult = as(alphaOwner(), () -> service.validateSpec(legacy));
        assertFalse((Boolean) legacyResult.get("valid"));
        assertTrue(String.valueOf(legacyResult.get("errors")).contains("Legacy source data source is not available"));
        verify(sources, org.mockito.Mockito.atLeast(2)).get(700L);
    }

    @Test
    void managedFileListPreviewStreamDeleteAndUploadAreTenantScoped() {
        MappingFileAssetRepository assets = mock(MappingFileAssetRepository.class);
        ManagedFileVault vault = mock(ManagedFileVault.class);
        AuditService audit = mock(AuditService.class);
        MappingFileService service = new MappingFileService(assets, vault, new ObjectMapper(), audit,
                new OwnershipGuard(audit), 1_000_000);
        MappingFileAssetEntity alpha = asset(51L, 1L, 10L, OwnershipGuard.GROUP);
        alpha.setName("alpha.csv"); alpha.setFormat("CSV"); alpha.setOptionsJson("{\"delimiter\":\",\",\"header\":true}");
        alpha.setSchemaJson("[]"); alpha.setStorageKey("alpha-key"); alpha.setKeySalt("salt"); alpha.setPayloadIv("iv");
        MappingFileAssetEntity beta = asset(52L, 2L, 20L, OwnershipGuard.GROUP);
        MappingFileAssetEntity shared = asset(53L, 2L, 20L, OwnershipGuard.SHARED);
        when(assets.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(beta, shared, alpha));
        when(assets.findById(51L)).thenReturn(Optional.of(alpha));
        when(vault.open("alpha-key", "salt", "iv")).thenAnswer(call -> stream("id,name\n1,Ada\n"));

        assertEquals(List.of(53L, 51L), assetIds(as(alphaMember(), service::list)));
        assertForbidden(() -> as(betaManager(), () -> service.get(51L)));
        assertForbidden(() -> as(betaManager(), () -> service.preview(51L, 10)));
        assertForbidden(() -> as(betaManager(), () -> { service.delete(51L); return null; }));
        assertForbidden(() -> as(betaManager(), () -> service.readRows(alpha, 10)));
        verify(vault, never()).delete("alpha-key");
        assertEquals(1, ((List<?>) as(alphaMember(), () -> service.preview(51L, 10)).get("rows")).size());

        byte[] upload = "id,name\n1,Ada\n".getBytes(StandardCharsets.UTF_8);
        when(vault.store(any(), anyLong())).thenReturn(new ManagedFileVault.Stored("new-key", "new-salt", "new-iv", "abc", upload.length));
        when(vault.open("new-key", "new-salt", "new-iv")).thenAnswer(call -> new ByteArrayInputStream(upload));
        when(assets.save(any(MappingFileAssetEntity.class))).thenAnswer(call -> {
            MappingFileAssetEntity value = call.getArgument(0); setId(value, 54L); return value;
        });
        MockMultipartFile file = new MockMultipartFile("file", "customers.csv", "text/csv", upload);
        MappingFileAssetEntity saved = as(alphaOwner(), () -> service.upload(file, "customer import", "CSV", ",", true));
        assertEquals(1L, saved.getOwnerUserId());
        assertEquals("alpha", saved.getOwnerUsername());
        assertEquals(10L, saved.getOwnerGroupId());
        assertEquals(OwnershipGuard.GROUP, saved.getVisibility());
    }

    @Test
    void workflowsGuardCrudAndValidateReferencedMappingsBeforeSaveAndRun() {
        WorkflowRepository workflows = mock(WorkflowRepository.class);
        MappingService mappings = mock(MappingService.class);
        DataSourceService dataSources = mock(DataSourceService.class);
        AuditService audit = mock(AuditService.class);
        WorkflowService service = new WorkflowService(workflows, mappings, dataSources, audit, new OwnershipGuard(audit));
        WorkflowEntity alpha = workflow(61L, "alpha workflow", 1L, 10L, OwnershipGuard.GROUP,
                "[{\"type\":\"MAPPING\",\"mappingId\":71}]");
        WorkflowEntity beta = workflow(62L, "beta workflow", 2L, 20L, OwnershipGuard.GROUP,
                "[{\"type\":\"MAPPING\",\"mappingId\":72}]");
        WorkflowEntity shared = workflow(63L, "shared workflow", 2L, 20L, OwnershipGuard.SHARED,
                "[{\"type\":\"MAPPING\",\"mappingId\":72}]");
        when(workflows.findAll()).thenReturn(List.of(beta, shared, alpha));
        when(workflows.findById(61L)).thenReturn(Optional.of(alpha));

        assertEquals(List.of(61L, 63L), workflowIds(as(alphaMember(), service::list)));
        assertForbidden(() -> as(betaManager(), () -> service.get(61L)));
        assertForbidden(() -> as(betaManager(), () -> { service.delete(61L); return null; }));
        assertForbidden(() -> as(betaManager(), () -> service.run(61L)));
        verify(workflows, never()).deleteById(61L);

        WorkflowEntity create = workflow(null, "new alpha workflow", 999L, 999L, OwnershipGuard.SHARED,
                "[{\"type\":\"MAPPING\",\"mappingId\":71}]");
        when(mappings.get(71L)).thenReturn(mapping(71L, "visible mapping", 1L, 10L, OwnershipGuard.GROUP));
        when(mappings.plan(71L)).thenReturn(Map.of("valid", true, "errors", List.of()));
        when(workflows.findByNameIgnoreCase("new alpha workflow")).thenReturn(Optional.empty());
        when(workflows.save(any(WorkflowEntity.class))).thenAnswer(call -> {
            WorkflowEntity value = call.getArgument(0); setId(value, 64L); return value;
        });
        WorkflowEntity saved = as(alphaOwner(), () -> service.save(create));
        assertEquals(1L, saved.getOwnerUserId());
        assertEquals("alpha", saved.getOwnerUsername());
        assertEquals(10L, saved.getOwnerGroupId());
        assertEquals(OwnershipGuard.GROUP, saved.getVisibility());

        when(mappings.get(99L)).thenThrow(ApiException.forbidden("foreign mapping"));
        when(mappings.plan(99L)).thenThrow(ApiException.forbidden("foreign mapping"));
        WorkflowEntity invalid = workflow(null, "foreign reference", null, null, null,
                "[{\"type\":\"MAPPING\",\"mappingId\":99}]");
        assertForbidden(() -> as(alphaOwner(), () -> service.save(invalid)));

        alpha.setStepsJson("[{\"type\":\"MAPPING\",\"mappingId\":99}]");
        assertForbidden(() -> as(alphaOwner(), () -> service.run(61L)));
        service.shutdown();
    }

    @Test
    void workflowRejectsAMappingWhoseReferencedDataSourceIsUnavailableToTheCaller() {
        WorkflowRepository workflows = mock(WorkflowRepository.class);
        MappingService mappings = mock(MappingService.class);
        DataSourceService dataSources = mock(DataSourceService.class);
        AuditService audit = mock(AuditService.class);
        WorkflowService service = new WorkflowService(workflows, mappings, dataSources, audit, new OwnershipGuard(audit));
        WorkflowEntity alpha = workflow(65L, "alpha workflow", 1L, 10L, OwnershipGuard.GROUP,
                "[{\"type\":\"MAPPING\",\"mappingId\":71}]");
        when(workflows.findById(65L)).thenReturn(Optional.of(alpha));
        when(mappings.get(71L)).thenReturn(mapping(71L, "shared mapping", 2L, 20L, OwnershipGuard.SHARED));
        when(mappings.plan(71L)).thenReturn(Map.of("valid", false,
                "errors", List.of("Legacy target data source is not available")));

        try {
            assertThrows(ApiException.class, () -> as(alphaOwner(), () -> service.run(65L)));
            verify(workflows, never()).save(any(WorkflowEntity.class));
            verify(mappings, never()).loadMulti(anyLong(), any());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void workflowWorkerRetainsTheInitiatingCallerForReferencedDataSourceAuthorization() throws Exception {
        WorkflowRepository workflows = mock(WorkflowRepository.class);
        MappingService mappings = mock(MappingService.class);
        DataSourceService dataSources = mock(DataSourceService.class);
        AuditService audit = mock(AuditService.class);
        WorkflowService service = new WorkflowService(workflows, mappings, dataSources, audit, new OwnershipGuard(audit));
        WorkflowEntity alpha = workflow(66L, "alpha sql workflow", 1L, 10L, OwnershipGuard.GROUP,
                "[{\"type\":\"SQL\",\"dataSourceId\":700,\"sql\":\"INSERT INTO out SELECT * FROM input\"}]");
        when(workflows.findById(66L)).thenReturn(Optional.of(alpha));
        when(workflows.save(any(WorkflowEntity.class))).thenAnswer(call -> call.getArgument(0));
        when(dataSources.get(700L)).thenReturn(new io.forgetdm.datasource.DataSourceEntity());

        CountDownLatch executed = new CountDownLatch(1);
        AtomicReference<Long> workerUser = new AtomicReference<>();
        when(mappings.loadMulti(anyLong(), any())).thenAnswer(call -> {
            workerUser.set(AccessContext.current().map(AccessPrincipal::userId).orElse(null));
            executed.countDown();
            return Map.of("totalRows", 0L);
        });

        try {
            as(alphaOwner(), () -> service.run(66L));
            assertTrue(executed.await(5, TimeUnit.SECONDS), "background workflow step did not execute");
            assertEquals(1L, workerUser.get());
        } finally {
            service.shutdown();
        }
    }

    private static MappingService mappingService(MappingRepository mappings, MappingVersionRepository versions,
                                                  MappingFileAssetRepository assets, AuditService audit) {
        return new MappingService(mappings, mock(QueryService.class), mock(DataSourceService.class),
                mock(ConnectionFactory.class), audit, new ObjectMapper(), versions, assets,
                new OwnershipGuard(audit));
    }

    private static MappingEntity mapping(Long id, String name, Long owner, Long group, String visibility) {
        MappingEntity value = new MappingEntity(); setId(value, id); value.setName(name); value.setSpecJson("{}");
        value.setOwnerUserId(owner); value.setOwnerUsername(owner == null ? null : "owner-" + owner);
        value.setOwnerGroupId(group); value.setVisibility(visibility); return value;
    }

    private static MappingVersionEntity version(Long id, Long mappingId) {
        MappingVersionEntity value = new MappingVersionEntity(); setId(value, id); value.setMappingId(mappingId);
        value.setVersionNo(1); value.setName("version"); value.setSpecJson("{}"); value.setSpecHash("hash");
        value.setCreatedBy("alpha"); return value;
    }

    private static MappingFileAssetEntity asset(Long id, Long owner, Long group, String visibility) {
        MappingFileAssetEntity value = new MappingFileAssetEntity(); setId(value, id);
        value.setOwnerUserId(owner); value.setOwnerUsername(owner == null ? null : "owner-" + owner);
        value.setOwnerGroupId(group); value.setVisibility(visibility); return value;
    }

    private static WorkflowEntity workflow(Long id, String name, Long owner, Long group, String visibility, String steps) {
        WorkflowEntity value = new WorkflowEntity(); setId(value, id); value.setName(name); value.setStepsJson(steps);
        value.setOwnerUserId(owner); value.setOwnerUsername(owner == null ? null : "owner-" + owner);
        value.setOwnerGroupId(group); value.setVisibility(visibility); return value;
    }

    private static ByteArrayInputStream stream(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void setId(Object value, Long id) {
        if (id != null) ReflectionTestUtils.setField(value, "id", id);
    }

    private static List<Long> ids(List<MappingEntity> values) { return values.stream().map(MappingEntity::getId).toList(); }
    private static List<Long> assetIds(List<MappingFileAssetEntity> values) { return values.stream().map(MappingFileAssetEntity::getId).toList(); }
    private static List<Long> workflowIds(List<WorkflowEntity> values) { return values.stream().map(WorkflowEntity::getId).toList(); }

    private static void assertForbidden(Runnable operation) {
        ApiException denied = assertThrows(ApiException.class, operation::run);
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatus());
    }

    private static AccessPrincipal alphaOwner() { return principal(1L, "alpha", 10L, Set.of("mapping.manage", "mapping.run")); }
    private static AccessPrincipal alphaMember() { return principal(3L, "alpha-member", 10L, Set.of("mapping.read")); }
    private static AccessPrincipal betaManager() { return principal(2L, "beta", 20L, Set.of("mapping.read", "mapping.manage", "mapping.run")); }
    private static AccessPrincipal admin() { return new AccessPrincipal(99L, "admin", "Admin", Set.of("ADMIN"), Set.of("admin.all")); }

    private static AccessPrincipal principal(Long id, String username, Long groupId, Set<String> permissions) {
        return new AccessPrincipal(id, username, username, Set.of("TESTER"), permissions,
                List.of(new AccessControlService.GroupLite(groupId, "group-" + groupId)));
    }

    private static <T> T as(AccessPrincipal principal, java.util.function.Supplier<T> work) {
        return AccessContext.callAs(principal, null, work);
    }
}
