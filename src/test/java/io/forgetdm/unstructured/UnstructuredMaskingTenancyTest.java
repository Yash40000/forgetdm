package io.forgetdm.unstructured;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.core.mask.MaskingEngine;
import io.forgetdm.filevault.ManagedFileVault;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessControlService;
import io.forgetdm.security.AccessPrincipal;
import io.forgetdm.security.OwnershipGuard;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UnstructuredMaskingTenancyTest {

    @Test
    void profileAndJobCollectionsHonorPrivateGroupSharedAndAdminScopes() {
        UnstructuredProfileRepository profiles = mock(UnstructuredProfileRepository.class);
        UnstructuredJobRepository jobs = mock(UnstructuredJobRepository.class);
        OwnershipGuard ownership = new OwnershipGuard(mock(AuditService.class));
        UnstructuredMaskingService service = service(profiles, jobs, mock(ManagedFileVault.class), ownership);

        UnstructuredProfileEntity privateAlpha = profile(1L, "private-alpha", 1L, 10L, OwnershipGuard.PRIVATE);
        UnstructuredProfileEntity groupAlpha = profile(2L, "group-alpha", 2L, 10L, OwnershipGuard.GROUP);
        UnstructuredProfileEntity groupBeta = profile(3L, "group-beta", 3L, 20L, OwnershipGuard.GROUP);
        UnstructuredProfileEntity shared = profile(4L, "shared", 3L, 20L, OwnershipGuard.SHARED);
        when(profiles.findByNameIgnoreCase("Enterprise PII baseline")).thenReturn(Optional.empty());
        when(profiles.count()).thenReturn(4L);
        when(profiles.findAll()).thenReturn(List.of(privateAlpha, groupAlpha, groupBeta, shared));

        UnstructuredJobEntity privateAlphaJob = job(11L, 1L, 10L, OwnershipGuard.PRIVATE);
        UnstructuredJobEntity groupAlphaJob = job(12L, 2L, 10L, OwnershipGuard.GROUP);
        UnstructuredJobEntity groupBetaJob = job(13L, 3L, 20L, OwnershipGuard.GROUP);
        UnstructuredJobEntity sharedJob = job(14L, 3L, 20L, OwnershipGuard.SHARED);
        when(jobs.findAll()).thenReturn(List.of(privateAlphaJob, groupAlphaJob, groupBetaJob, sharedJob));

        assertEquals(Set.of(1L, 2L, 4L), ids(AccessContext.callAs(alpha(), null, service::listProfiles)));
        assertEquals(Set.of(11L, 12L, 14L), jobIds(AccessContext.callAs(alpha(), null, service::listJobs)));
        assertEquals(Set.of(2L, 4L), ids(AccessContext.callAs(alphaPeer(), null, service::listProfiles)));
        assertEquals(Set.of(3L, 4L), ids(AccessContext.callAs(betaManager(), null, service::listProfiles)));
        assertEquals(Set.of(1L, 2L, 3L, 4L), ids(AccessContext.callAs(admin(), null, service::listProfiles)));
        assertEquals(Set.of(11L, 12L, 13L, 14L), jobIds(AccessContext.callAs(admin(), null, service::listJobs)));

        service.shutdown();
    }

    @Test
    void jobHistoryAppliesTenantScopeBeforeTheOneHundredRowLimit() {
        UnstructuredJobRepository jobs = mock(UnstructuredJobRepository.class);
        OwnershipGuard ownership = new OwnershipGuard(mock(AuditService.class));
        UnstructuredMaskingService service = service(mock(UnstructuredProfileRepository.class), jobs,
                mock(ManagedFileVault.class), ownership);
        List<UnstructuredJobEntity> history = new ArrayList<>();
        for (long id = 1; id <= 120; id++) {
            history.add(job(id, 3L, 20L, OwnershipGuard.GROUP));
        }
        history.add(job(1001L, 1L, 10L, OwnershipGuard.PRIVATE));
        history.add(job(1002L, 2L, 10L, OwnershipGuard.GROUP));
        when(jobs.findAll()).thenReturn(history);

        List<UnstructuredJobEntity> visible = AccessContext.callAs(alpha(), null, service::listJobs);

        assertEquals(Set.of(1001L, 1002L), jobIds(visible));
        service.shutdown();
    }

    @Test
    void wrongGroupManagerCannotReadMutateRunOrDownloadAnotherGroupsObjects() {
        UnstructuredProfileRepository profiles = mock(UnstructuredProfileRepository.class);
        UnstructuredJobRepository jobs = mock(UnstructuredJobRepository.class);
        ManagedFileVault vault = mock(ManagedFileVault.class);
        OwnershipGuard ownership = new OwnershipGuard(mock(AuditService.class));
        UnstructuredMaskingService service = service(profiles, jobs, vault, ownership);

        UnstructuredProfileEntity alphaProfile = profile(21L, "alpha-profile", 1L, 10L, OwnershipGuard.GROUP);
        alphaProfile.setStatus("ACTIVE");
        alphaProfile.setRulesJson(validRules());
        when(profiles.findById(21L)).thenReturn(Optional.of(alphaProfile));

        UnstructuredJobEntity alphaJob = job(22L, 1L, 10L, OwnershipGuard.GROUP);
        alphaJob.setStatus("COMPLETED");
        alphaJob.setOutputStorageKey("alpha-output");
        alphaJob.setOutputName("masked.txt");
        when(jobs.findById(22L)).thenReturn(Optional.of(alphaJob));

        UnstructuredProfileEntity update = profile(21L, "alpha-profile", 999L, 999L, OwnershipGuard.SHARED);
        update.setStatus("ACTIVE");
        update.setRulesJson(validRules());
        MockMultipartFile upload = new MockMultipartFile("file", "input.txt", "text/plain", "secret".getBytes(StandardCharsets.UTF_8));

        assertForbidden(() -> service.getProfile(21L));
        assertForbidden(() -> service.saveProfile(update));
        assertForbidden(() -> service.deleteProfile(21L));
        assertForbidden(() -> service.preview(21L, "secret", "seed"));
        assertForbidden(() -> service.start(21L, upload, "seed"));
        assertForbidden(() -> service.getJob(22L));
        assertForbidden(() -> service.cancel(22L));
        assertForbidden(() -> service.output(22L));
        assertForbidden(() -> service.deleteJob(22L));

        verify(profiles, never()).delete(any());
        verify(jobs, never()).delete(any());
        verify(vault, never()).open(any(), any(), any());
        verify(vault, never()).store(any(), anyLong());
        service.shutdown();
    }

    @Test
    void newProfilesAndJobsIgnoreCallerSuppliedOwnersAndStampAuthenticatedTenant() throws Exception {
        UnstructuredProfileRepository profiles = mock(UnstructuredProfileRepository.class);
        UnstructuredJobRepository jobs = mock(UnstructuredJobRepository.class);
        ManagedFileVault vault = mock(ManagedFileVault.class);
        OwnershipGuard ownership = new OwnershipGuard(mock(AuditService.class));
        UnstructuredMaskingService service = service(profiles, jobs, vault, ownership);

        when(profiles.findByNameIgnoreCase("alpha-profile")).thenReturn(Optional.empty());
        when(profiles.save(any())).thenAnswer(invocation -> {
            UnstructuredProfileEntity saved = invocation.getArgument(0);
            if (saved.getId() == null) saved.setId(31L);
            return saved;
        });

        UnstructuredProfileEntity input = new UnstructuredProfileEntity();
        input.setName("alpha-profile");
        input.setStatus("ACTIVE");
        input.setRulesJson(validRules());
        input.setOwnerUserId(999L);
        input.setOwnerUsername("forged-owner");
        input.setOwnerGroupId(999L);
        UnstructuredProfileEntity savedProfile = AccessContext.callAs(alpha(), null, () -> service.saveProfile(input));

        assertEquals(1L, savedProfile.getOwnerUserId());
        assertEquals("alpha", savedProfile.getOwnerUsername());
        assertEquals(10L, savedProfile.getOwnerGroupId());
        assertEquals(OwnershipGuard.GROUP, savedProfile.getVisibility());
        assertEquals("alpha", savedProfile.getCreatedBy());

        when(profiles.findById(31L)).thenReturn(Optional.of(savedProfile));
        when(vault.store(any(), anyLong())).thenReturn(new ManagedFileVault.Stored("source", "salt", "iv", "hash", 6));
        when(jobs.save(any())).thenAnswer(invocation -> {
            UnstructuredJobEntity saved = invocation.getArgument(0);
            if (saved.getId() == null) ReflectionTestUtils.setField(saved, "id", 32L);
            return saved;
        });

        TransactionSynchronizationManager.initSynchronization();
        UnstructuredJobEntity savedJob;
        try {
            savedJob = AccessContext.callAs(alpha(), null, () -> service.start(31L,
                    new MockMultipartFile("file", "input.txt", "text/plain", "secret".getBytes(StandardCharsets.UTF_8)), "seed"));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertEquals(1L, savedJob.getOwnerUserId());
        assertEquals("alpha", savedJob.getOwnerUsername());
        assertEquals(10L, savedJob.getOwnerGroupId());
        assertEquals(OwnershipGuard.GROUP, savedJob.getVisibility());
        assertEquals("alpha", savedJob.getCreatedBy());
        service.shutdown();
    }

    @Test
    void sameGroupCanOperateGovernedJobFilesWithoutReceivingGlobalManagerAccess() throws Exception {
        UnstructuredProfileRepository profiles = mock(UnstructuredProfileRepository.class);
        UnstructuredJobRepository jobs = mock(UnstructuredJobRepository.class);
        ManagedFileVault vault = mock(ManagedFileVault.class);
        OwnershipGuard ownership = new OwnershipGuard(mock(AuditService.class));
        UnstructuredMaskingService service = service(profiles, jobs, vault, ownership);

        UnstructuredJobEntity cancelable = job(35L, 2L, 10L, OwnershipGuard.GROUP);
        cancelable.setStatus("RUNNING");
        UnstructuredJobEntity downloadable = job(36L, 2L, 10L, OwnershipGuard.GROUP);
        downloadable.setStatus("COMPLETED");
        downloadable.setOutputStorageKey("output-key");
        downloadable.setOutputKeySalt("salt");
        downloadable.setOutputIv("iv");
        downloadable.setOutputName("masked.txt");
        UnstructuredJobEntity deletable = job(37L, 2L, 10L, OwnershipGuard.GROUP);
        deletable.setStatus("FAILED");
        deletable.setSourceStorageKey("source-key");
        deletable.setOutputStorageKey("failed-output-key");
        when(jobs.findById(35L)).thenReturn(Optional.of(cancelable));
        when(jobs.findById(36L)).thenReturn(Optional.of(downloadable));
        when(jobs.findById(37L)).thenReturn(Optional.of(deletable));
        when(jobs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(vault.open("output-key", "salt", "iv"))
                .thenReturn(new ByteArrayInputStream("masked".getBytes(StandardCharsets.UTF_8)));

        AccessContext.callAs(alphaPeer(), null, () -> service.cancel(35L));
        UnstructuredMaskingService.Download output = AccessContext.callAs(alphaPeer(), null, () -> service.output(36L));
        try (var stream = output.stream()) {
            assertEquals("masked", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
        AccessContext.callAs(alphaPeer(), null, () -> { service.deleteJob(37L); return null; });

        assertTrue(cancelable.isCancelRequested());
        verify(vault).open("output-key", "salt", "iv");
        verify(vault).delete("source-key");
        verify(vault).delete("failed-output-key");
        verify(jobs).delete(deletable);
        service.shutdown();
    }

    @Test
    void backgroundWorkerUsesDirectRepositoriesWithoutUserScopeBypass() {
        UnstructuredProfileRepository profiles = mock(UnstructuredProfileRepository.class);
        UnstructuredJobRepository jobs = mock(UnstructuredJobRepository.class);
        ManagedFileVault vault = mock(ManagedFileVault.class);
        OwnershipGuard ownership = mock(OwnershipGuard.class);
        UnstructuredMaskingService service = service(profiles, jobs, vault, ownership);

        UnstructuredProfileEntity profile = profile(41L, "private-profile", 1L, 10L, OwnershipGuard.PRIVATE);
        profile.setRulesJson("[]");
        UnstructuredJobEntity job = job(42L, 1L, 10L, OwnershipGuard.PRIVATE);
        job.setProfileId(41L);
        job.setStatus("QUEUED");
        job.setCancelRequested(true);
        job.setOriginalFilename("input.txt");
        when(profiles.findById(41L)).thenReturn(Optional.of(profile));
        when(jobs.findById(42L)).thenReturn(Optional.of(job));
        when(jobs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ReflectionTestUtils.invokeMethod(service, "process", 42L, "seed");

        assertEquals("CANCELED", job.getStatus());
        verifyNoInteractions(ownership);
        verify(vault, never()).open(any(), any(), any());
        service.shutdown();
    }

    private static UnstructuredMaskingService service(UnstructuredProfileRepository profiles,
                                                       UnstructuredJobRepository jobs,
                                                       ManagedFileVault vault,
                                                       OwnershipGuard ownership) {
        return new UnstructuredMaskingService(profiles, jobs, vault, new MaskingEngine("secret"),
                new ObjectMapper(), mock(AuditService.class), ownership, 10_000_000, 1_000_000, 72);
    }

    private static UnstructuredProfileEntity profile(Long id, String name, Long owner, Long group, String visibility) {
        UnstructuredProfileEntity profile = new UnstructuredProfileEntity();
        profile.setId(id);
        profile.setName(name);
        profile.setOwnerUserId(owner);
        profile.setOwnerUsername("user-" + owner);
        profile.setOwnerGroupId(group);
        profile.setVisibility(visibility);
        profile.setCreatedBy("user-" + owner);
        return profile;
    }

    private static UnstructuredJobEntity job(Long id, Long owner, Long group, String visibility) {
        UnstructuredJobEntity job = new UnstructuredJobEntity();
        ReflectionTestUtils.setField(job, "id", id);
        job.setOwnerUserId(owner);
        job.setOwnerUsername("user-" + owner);
        job.setOwnerGroupId(group);
        job.setVisibility(visibility);
        job.setCreatedBy("user-" + owner);
        job.setOriginalFilename("input.txt");
        return job;
    }

    private static Set<Long> ids(List<UnstructuredProfileEntity> values) {
        return values.stream().map(UnstructuredProfileEntity::getId).collect(java.util.stream.Collectors.toSet());
    }

    private static Set<Long> jobIds(List<UnstructuredJobEntity> values) {
        return values.stream().map(UnstructuredJobEntity::getId).collect(java.util.stream.Collectors.toSet());
    }

    private static String validRules() {
        return "[{\"name\":\"Email\",\"piiType\":\"EMAIL\",\"pattern\":\"[A-Z0-9._%+-]+@[A-Z0-9.-]+\\\\.[A-Z]{2,}\",\"function\":\"EMAIL\",\"enabled\":true,\"valueGroup\":0}]";
    }

    private static AccessPrincipal alpha() {
        return principal(1L, "alpha", 10L, Set.of("unstructured.read", "unstructured.manage", "unstructured.run", "unstructured.cancel"));
    }

    private static AccessPrincipal alphaPeer() {
        return principal(4L, "alpha-peer", 10L, Set.of("unstructured.read"));
    }

    private static AccessPrincipal betaManager() {
        return principal(3L, "beta-manager", 20L, Set.of("unstructured.read", "unstructured.manage", "unstructured.run", "unstructured.cancel"));
    }

    private static AccessPrincipal admin() {
        return principal(99L, "admin", 99L, Set.of("admin.all"));
    }

    private static AccessPrincipal principal(Long id, String username, Long group, Set<String> permissions) {
        return new AccessPrincipal(id, username, username, Set.of(), permissions,
                List.of(new AccessControlService.GroupLite(group, "group-" + group)));
    }

    private static void assertForbidden(Runnable action) {
        ApiException error = assertThrows(ApiException.class,
                () -> AccessContext.callAs(betaManager(), null, () -> { action.run(); return null; }));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
    }
}
