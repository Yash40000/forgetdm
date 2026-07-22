package io.forgetdm.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.core.mask.MaskingEngine;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.filevault.ManagedFileVault;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessControlService;
import io.forgetdm.security.AccessPrincipal;
import io.forgetdm.security.OwnershipGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MappingExecutionTenancyTest {
    private MappingRunRepository runs;
    private MappingService mappings;
    private MappingVersionRepository versions;
    private MappingFileService files;
    private DataSourceService dataSources;
    private ManagedFileVault vault;
    private AuditService audit;
    private MappingExecutionService service;

    @BeforeEach
    void setUp() {
        runs = mock(MappingRunRepository.class);
        mappings = mock(MappingService.class);
        versions = mock(MappingVersionRepository.class);
        files = mock(MappingFileService.class);
        dataSources = mock(DataSourceService.class);
        vault = mock(ManagedFileVault.class);
        audit = mock(AuditService.class);
        service = new MappingExecutionService(runs, mappings, versions, files,
                dataSources, mock(ConnectionFactory.class), vault,
                mock(MaskingEngine.class), new ObjectMapper(), audit, new OwnershipGuard(audit));
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        service.shutdown();
    }

    @Test
    void startStampsCallerOwnershipAndQueuesOnlyAnAuthorizedMapping() {
        MappingEntity mapping = new MappingEntity();
        setId(mapping, 101L); mapping.setName("alpha mapping"); mapping.setSpecJson("{}");
        when(mappings.get(101L)).thenReturn(mapping);
        when(mappings.plan(101L)).thenReturn(Map.of("valid", true, "destructive", false));
        MappingVersionEntity version = new MappingVersionEntity(); version.setVersionNo(7);
        when(versions.findByMappingIdOrderByVersionNoDesc(101L)).thenReturn(List.of(version));
        when(runs.save(any(MappingRunEntity.class))).thenAnswer(call -> {
            MappingRunEntity value = call.getArgument(0); setId(value, 201L); return value;
        });

        TransactionSynchronizationManager.initSynchronization();
        MappingRunEntity saved = as(alphaOwner(), () -> service.start(101L, null));

        assertEquals(1L, saved.getOwnerUserId());
        assertEquals("alpha", saved.getOwnerUsername());
        assertEquals(10L, saved.getOwnerGroupId());
        assertEquals(OwnershipGuard.GROUP, saved.getVisibility());
        assertEquals("alpha", saved.getCreatedBy());
        assertEquals(7, saved.getMappingVersion());

        when(mappings.get(999L)).thenThrow(ApiException.forbidden("foreign mapping"));
        assertForbidden(() -> as(alphaOwner(), () -> service.start(999L, null)));
    }

    @Test
    void runListsReadsAndManagerActionsStayWithinTenantWhileSharedAdminAndSystemWork() {
        MappingRunEntity alpha = run(301L, 1L, 10L, OwnershipGuard.GROUP, "RUNNING");
        MappingRunEntity beta = run(302L, 2L, 20L, OwnershipGuard.GROUP, "FAILED");
        MappingRunEntity shared = run(303L, 2L, 20L, OwnershipGuard.SHARED, "COMPLETED");
        MappingRunEntity privateAlpha = run(304L, 1L, 10L, OwnershipGuard.PRIVATE, "FAILED");
        when(runs.findTop100ByOrderByCreatedAtDesc()).thenReturn(List.of(privateAlpha, shared, beta, alpha));
        when(runs.findById(301L)).thenReturn(Optional.of(alpha));

        assertEquals(List.of(303L, 301L), ids(as(alphaMember(), service::list)));
        assertEquals(List.of(304L, 303L, 302L, 301L), ids(as(admin(), service::list)));
        assertEquals(List.of(304L, 303L, 302L, 301L), ids(service.list()));
        assertSame(alpha, as(alphaMember(), () -> service.get(301L)));

        assertForbidden(() -> as(betaManager(), () -> service.get(301L)));
        assertForbidden(() -> as(betaManager(), () -> service.cancel(301L)));
        assertForbidden(() -> as(betaManager(), () -> service.retry(301L)));
        assertForbidden(() -> as(betaManager(), () -> service.output(301L)));
        verify(runs, never()).save(alpha);
        verify(vault, never()).open(any(), any(), any());
    }

    @Test
    void downloadHonorsExplicitSharingAndRetryRechecksTheParentMapping() {
        MappingRunEntity shared = run(401L, 1L, 10L, OwnershipGuard.SHARED, "COMPLETED");
        shared.setOutputName("result.csv"); shared.setOutputFormat("CSV");
        shared.setOutputStorageKey("out"); shared.setOutputKeySalt("salt"); shared.setOutputIv("iv");
        when(runs.findById(401L)).thenReturn(Optional.of(shared));
        when(vault.open("out", "salt", "iv")).thenReturn(new ByteArrayInputStream(new byte[0]));
        assertEquals("result.csv", as(betaManager(), () -> service.output(401L)).filename());

        MappingRunEntity retry = run(402L, 1L, 10L, OwnershipGuard.GROUP, "FAILED");
        retry.setMappingId(777L); retry.setRequestJson("{}");
        when(runs.findById(402L)).thenReturn(Optional.of(retry));
        when(mappings.get(777L)).thenThrow(ApiException.forbidden("parent mapping is no longer visible"));
        assertForbidden(() -> as(alphaOwner(), () -> service.retry(402L)));
        verify(runs, never()).save(retry);
    }

    @Test
    void previewCannotReadAForeignManagedAssetEvenIfValidationIsIncorrectlyReportedValid() throws Exception {
        when(mappings.validateSpec(any())).thenReturn(Map.of("valid", true, "warnings", List.of()));
        when(files.get(888L)).thenThrow(ApiException.forbidden("foreign asset"));
        var spec = new ObjectMapper().readTree("""
                {"sources":[{"type":"FILE","assetId":888,"alias":"input"}],
                 "columns":[],"target":{"type":"PREVIEW"}}
                """);

        assertForbidden(() -> as(alphaOwner(), () -> service.preview(spec)));
        verify(files, never()).streamRows(any(), anyLong(), any());
    }

    @Test
    void asyncExecutionRetainsTheInitiatingCallerForReferencedDataSourceAuthorization() throws Exception {
        String spec = """
                {"specVersion":2,
                 "sources":[{"type":"DATABASE","dataSourceId":501,"table":"customers"}],
                 "target":{"type":"DATABASE","dataSourceId":700,"table":"masked_customers"}}
                """;
        MappingEntity mapping = new MappingEntity();
        setId(mapping, 101L); mapping.setName("alpha mapping"); mapping.setSpecJson(spec);
        when(mappings.get(101L)).thenReturn(mapping);
        when(mappings.plan(101L)).thenReturn(Map.of("valid", true, "destructive", false));
        MappingVersionEntity version = new MappingVersionEntity();
        version.setVersionNo(7); version.setSpecJson(spec);
        when(versions.findByMappingIdOrderByVersionNoDesc(101L)).thenReturn(List.of(version));

        AtomicReference<MappingRunEntity> stored = new AtomicReference<>();
        when(runs.save(any(MappingRunEntity.class))).thenAnswer(call -> {
            MappingRunEntity value = call.getArgument(0);
            if (value.getId() == null) setId(value, 201L);
            stored.set(value);
            return value;
        });
        when(runs.findById(201L)).thenAnswer(call -> Optional.ofNullable(stored.get()));

        CountDownLatch checked = new CountDownLatch(1);
        AtomicReference<Long> workerUser = new AtomicReference<>();
        when(dataSources.get(700L)).thenAnswer(call -> {
            workerUser.set(AccessContext.current().map(AccessPrincipal::userId).orElse(null));
            checked.countDown();
            throw ApiException.forbidden("foreign data source");
        });

        as(alphaOwner(), () -> service.start(101L, null));

        assertTrue(checked.await(5, TimeUnit.SECONDS), "background execution did not resolve the target data source");
        assertEquals(1L, workerUser.get());
    }

    private static MappingRunEntity run(Long id, Long owner, Long group, String visibility, String status) {
        MappingRunEntity value = new MappingRunEntity(); setId(value, id); value.setMappingId(100L);
        value.setMappingVersion(1); value.setCreatedBy(owner != null && owner == 1L ? "alpha" : "beta");
        value.setOwnerUserId(owner); value.setOwnerUsername(value.getCreatedBy()); value.setOwnerGroupId(group);
        value.setVisibility(visibility); value.setStatus(status); return value;
    }

    private static void setId(Object value, Long id) { ReflectionTestUtils.setField(value, "id", id); }
    private static List<Long> ids(List<MappingRunEntity> values) { return values.stream().map(MappingRunEntity::getId).toList(); }

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
