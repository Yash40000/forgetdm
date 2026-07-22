package io.forgetdm.provision;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.config.ForgeProps;
import io.forgetdm.core.mask.MaskingEngine;
import io.forgetdm.dataset.DataSetService;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.policy.MaskingRuleRepository;
import io.forgetdm.ri.RiRegistryService;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessControlService;
import io.forgetdm.security.AccessPrincipal;
import io.forgetdm.security.GovernedReferenceGuard;
import io.forgetdm.security.OwnershipGuard;
import io.forgetdm.subset.SubsetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProvisioningTenancyTest {

    private ProvisionJobRepository jobs;
    private AuditService audit;
    private AccessControlService access;
    private ExecutorService executor;
    private Future<?> future;
    private DataSetService datasets;
    private GovernedReferenceGuard references;
    private ProvisioningService service;

    @BeforeEach
    void setUp() {
        jobs = mock(ProvisionJobRepository.class);
        audit = mock(AuditService.class);
        access = mock(AccessControlService.class);
        executor = mock(ExecutorService.class);
        future = mock(Future.class);
        doReturn(future).when(executor).submit(any(Runnable.class));
        datasets = mock(DataSetService.class);
        references = mock(GovernedReferenceGuard.class);

        OwnershipGuard ownership = new OwnershipGuard(audit);
        service = new ProvisioningService(
                jobs,
                mock(MaskingRuleRepository.class),
                mock(DataSourceService.class),
                mock(ConnectionFactory.class),
                mock(MaskingEngine.class),
                mock(SubsetService.class),
                datasets,
                audit,
                new ForgeProps(),
                executor,
                mock(RiRegistryService.class),
                mock(DbMaskPushdown.class),
                access,
                ownership,
                references);
    }

    @Test
    void submitStampsAuthenticatedOwnerAndGroupInsteadOfTrustingPayloadOwnership() {
        ProvisionJobEntity job = new ProvisionJobEntity();
        job.setName("alpha synthetic run");
        job.setJobType("SYNTHETIC_LOAD");
        job.setOwnerUserId(999L);
        job.setOwnerUsername("spoofed");
        job.setOwnerGroupId(999L);
        job.setVisibility(OwnershipGuard.SHARED);
        when(jobs.save(any(ProvisionJobEntity.class))).thenAnswer(invocation -> {
            ProvisionJobEntity saved = invocation.getArgument(0);
            setId(saved, 101L);
            return saved;
        });

        ProvisionJobEntity saved = as(alphaOwner(), () -> service.submit(job));

        assertEquals(1L, saved.getOwnerUserId());
        assertEquals("alpha", saved.getOwnerUsername());
        assertEquals(10L, saved.getOwnerGroupId());
        assertEquals(OwnershipGuard.GROUP, saved.getVisibility());
        assertEquals("alpha", saved.getCreatedBy());
        verify(executor).submit(any(Runnable.class));
    }

    @Test
    void listFiltersByOwnerGroupAndVisibilityWhileAdminAndSystemRetainAccess() {
        ProvisionJobEntity alpha = job(11L, "alpha", 1L, 10L, OwnershipGuard.GROUP, "FAILED");
        ProvisionJobEntity beta = job(12L, "beta", 2L, 20L, OwnershipGuard.GROUP, "FAILED");
        ProvisionJobEntity shared = job(13L, "shared", 2L, 20L, OwnershipGuard.SHARED, "FAILED");
        ProvisionJobEntity privateAlpha = job(14L, "private-alpha", 1L, 10L, OwnershipGuard.PRIVATE, "FAILED");
        when(jobs.findAll()).thenReturn(List.of(alpha, beta, shared, privateAlpha));

        assertEquals(List.of(13L, 11L), ids(as(alphaGroupMember(), service::list)));
        assertEquals(List.of(14L, 13L, 12L, 11L), ids(as(admin(), service::list)));
        assertEquals(List.of(14L, 13L, 12L, 11L), ids(service.list()));
    }

    @Test
    void getAllowsOwnerGroupAdminAndSystemButRejectsAnotherTenant() {
        ProvisionJobEntity alpha = job(21L, "alpha", 1L, 10L, OwnershipGuard.GROUP, "FAILED");
        when(jobs.findById(21L)).thenReturn(Optional.of(alpha));

        assertSame(alpha, as(alphaGroupMember(), () -> service.get(21L)));
        ApiException denied = assertThrows(ApiException.class,
                () -> as(betaUser(), () -> service.get(21L)));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatus());
        assertSame(alpha, as(admin(), () -> service.get(21L)));
        assertSame(alpha, service.get(21L));
    }

    @Test
    void crossTenantMutationsStopAtTheOwnershipGuard() {
        ProvisionJobEntity alpha = job(31L, "alpha", 1L, 10L, OwnershipGuard.GROUP, "FAILED");
        when(jobs.findById(31L)).thenReturn(Optional.of(alpha));

        assertForbidden(() -> as(betaUser(), () -> { service.delete(31L); return null; }));
        verify(jobs, never()).deleteById(31L);

        alpha.setStatus("RUNNING");
        assertForbidden(() -> as(betaUser(), () -> service.cancel(31L)));
        verify(jobs, never()).save(alpha);

        alpha.setStatus("FAILED");
        assertForbidden(() -> as(betaUser(), () -> service.retry(31L)));
        verify(executor, never()).submit(any(Runnable.class));

        alpha.setStatus("AWAITING_APPROVAL");
        assertForbidden(() -> as(betaUser(), () -> service.reject(31L, "not approved")));
        verify(jobs, never()).save(alpha);
    }

    @Test
    void approvalIsTenantScopedRetainsMakerCheckerAndAllowsAdmin() {
        ProvisionJobEntity alpha = job(41L, "alpha", 1L, 10L, OwnershipGuard.GROUP, "AWAITING_APPROVAL");
        when(jobs.findById(41L)).thenReturn(Optional.of(alpha));
        when(jobs.save(alpha)).thenReturn(alpha);
        when(access.groupIdsForUsername("alpha")).thenReturn(Set.of(10L));

        assertForbidden(() -> as(betaApprover(), () -> service.approve(41L, "cross-tenant approval")));
        verify(jobs, never()).save(alpha);

        ProvisionJobEntity approved = as(alphaApprover(), () -> service.approve(41L, "approved for test"));
        assertEquals("APPROVED", approved.getApprovalStatus());
        assertEquals("PENDING", approved.getStatus());
        assertEquals("alpha-checker", approved.getApprovedBy());
        verify(executor).submit(any(Runnable.class));

        clearInvocations(jobs, executor);
        alpha.setStatus("AWAITING_APPROVAL");
        alpha.setApprovalStatus("PENDING_APPROVAL");
        assertEquals("PENDING", as(admin(), () -> service.approve(41L, "admin approval")).getStatus());
        verify(jobs).save(alpha);
        verify(executor).submit(any(Runnable.class));
    }

    @Test
    void submitRejectsHiddenTransitiveReferencesBeforePersistingOrQueueing() {
        ProvisionJobEntity request = new ProvisionJobEntity();
        request.setName("hidden blueprint attempt");
        request.setJobType("SUBSET_MASK");
        request.setDatasetId(91L);
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "hidden blueprint reference"))
                .when(datasets).assertAuthorizedReferences(91L, null);

        ApiException denial = assertThrows(ApiException.class,
                () -> as(alphaOwner(), () -> service.submit(request)));

        assertEquals(HttpStatus.FORBIDDEN, denial.getStatus());
        verify(jobs, never()).save(any());
        verify(executor, never()).submit(any(Runnable.class));
    }

    private static void assertForbidden(Runnable operation) {
        ApiException denied = assertThrows(ApiException.class, operation::run);
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatus());
    }

    private static ProvisionJobEntity job(Long id, String owner, Long ownerUserId, Long ownerGroupId,
                                          String visibility, String status) {
        ProvisionJobEntity job = new ProvisionJobEntity();
        setId(job, id);
        job.setName(owner + " job");
        job.setJobType("MASK_COPY");
        job.setCreatedBy(owner);
        job.setOwnerUsername(owner);
        job.setOwnerUserId(ownerUserId);
        job.setOwnerGroupId(ownerGroupId);
        job.setVisibility(visibility);
        job.setStatus(status);
        return job;
    }

    private static void setId(ProvisionJobEntity job, Long id) {
        ReflectionTestUtils.setField(job, "id", id);
    }

    private static List<Long> ids(List<ProvisionJobEntity> jobs) {
        return jobs.stream().map(ProvisionJobEntity::getId).toList();
    }

    private static AccessPrincipal alphaOwner() {
        return principal(1L, "alpha", 10L, Set.of("provision.run", "provision.read"));
    }

    private static AccessPrincipal alphaGroupMember() {
        return principal(3L, "alpha-member", 10L, Set.of("provision.read"));
    }

    private static AccessPrincipal alphaApprover() {
        return principal(4L, "alpha-checker", 10L, Set.of("provision.approve"));
    }

    private static AccessPrincipal betaUser() {
        return principal(2L, "beta", 20L, Set.of("provision.run", "provision.read"));
    }

    private static AccessPrincipal betaApprover() {
        return principal(5L, "beta-checker", 20L, Set.of("provision.approve"));
    }

    private static AccessPrincipal admin() {
        return new AccessPrincipal(99L, "admin", "Admin", Set.of("ADMIN"), Set.of("admin.all"));
    }

    private static AccessPrincipal principal(Long id, String username, Long groupId, Set<String> permissions) {
        return new AccessPrincipal(id, username, username, Set.of("TESTER"), permissions,
                List.of(new AccessControlService.GroupLite(groupId, "group-" + groupId)));
    }

    private static <T> T as(AccessPrincipal principal, java.util.function.Supplier<T> work) {
        return AccessContext.callAs(principal, null, work);
    }
}
