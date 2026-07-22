package io.forgetdm.unstructured;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.config.ForgeProps;
import io.forgetdm.core.mask.MaskingEngine;
import io.forgetdm.filevault.ManagedFileVault;
import io.forgetdm.security.OwnershipGuard;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UnstructuredMaskingServiceTest {
    @Test void previewMasksDetectedValuesAndReturnsCountsWithoutClearFindings() {
        UnstructuredProfileRepository profiles = mock(UnstructuredProfileRepository.class);
        UnstructuredProfileEntity profile = new UnstructuredProfileEntity(); profile.setId(7L); profile.setName("baseline");
        profile.setRulesJson("""
                [{"name":"Email","piiType":"EMAIL","pattern":"[A-Z0-9._%+-]+@[A-Z0-9.-]+\\\\.[A-Z]{2,}","function":"EMAIL","param1":"","param2":"","selector":"","enabled":true},
                 {"name":"SSN","piiType":"SSN","pattern":"\\\\d{3}-\\\\d{2}-\\\\d{4}","function":"SSN","param1":"","param2":"","selector":"","enabled":true}]
                """);
        when(profiles.findById(7L)).thenReturn(Optional.of(profile));
        UnstructuredMaskingService service = new UnstructuredMaskingService(profiles, mock(UnstructuredJobRepository.class),
                mock(ManagedFileVault.class), new MaskingEngine("secret"), new ObjectMapper(), mock(AuditService.class),
                mock(OwnershipGuard.class),
                10_000_000, 1_000_000, 72);

        Map<String, Object> result = service.preview(7L, "Email jane.doe@example.com SSN 123-45-6789", "seed-a");
        String masked = String.valueOf(result.get("masked"));
        assertFalse(masked.contains("jane.doe@example.com"));
        assertFalse(masked.contains("123-45-6789"));
        assertEquals(2L, ((Number) result.get("findingsCount")).longValue());
        assertFalse(String.valueOf(result.get("findings")).contains("jane.doe"));
    }

    @Test void capabilitiesExplicitlyFailClosedForUnsupportedBinaryContent() {
        UnstructuredMaskingService service = new UnstructuredMaskingService(mock(UnstructuredProfileRepository.class),
                mock(UnstructuredJobRepository.class), mock(ManagedFileVault.class), new MaskingEngine("secret"),
                new ObjectMapper(), mock(AuditService.class), mock(OwnershipGuard.class),
                10_000_000, 1_000_000, 72);
        assertTrue(String.valueOf(service.capabilities().get("guarantee")).contains("fails closed"));
    }

    @Test void previewMasksContextualFreeFlowNameAndCompactSsnWithoutMaskingUnlabelledNumbers() {
        UnstructuredProfileRepository profiles = mock(UnstructuredProfileRepository.class);
        UnstructuredProfileEntity profile = new UnstructuredProfileEntity(); profile.setId(8L); profile.setName("contextual");
        profile.setRulesJson("""
                [{"name":"Contextual full name","piiType":"FULL_NAME","pattern":"(?:(?:my\\\\s+)?name\\\\s*(?:is|[:=])\\\\s*)([\\\\p{L}][\\\\p{L}'-]{1,49}(?:\\\\s+(?!my\\\\b|ssn\\\\b)[\\\\p{L}][\\\\p{L}'-]{1,49}){0,3})(?=\\\\s*(?:\\\\bmy\\\\b|$))","function":"FULL_NAME","param1":"","param2":"","selector":"","enabled":true,"valueGroup":1},
                 {"name":"Compact SSN before label","piiType":"SSN","pattern":"(?<!\\\\d)(\\\\d{9})\\\\s*(?:is\\\\s+)?(?:my\\\\s+)?ssn","function":"SSN","param1":"","param2":"","selector":"","enabled":true,"valueGroup":1}]
                """);
        when(profiles.findById(8L)).thenReturn(Optional.of(profile));
        UnstructuredMaskingService service = new UnstructuredMaskingService(profiles, mock(UnstructuredJobRepository.class),
                mock(ManagedFileVault.class), new MaskingEngine("secret"), new ObjectMapper(), mock(AuditService.class),
                mock(OwnershipGuard.class),
                10_000_000, 1_000_000, 72);

        Map<String, Object> result = service.preview(8L,
                "my name is yash pall my 442577654 is my ssn; account reference 987654321", "seed-a");
        String masked = String.valueOf(result.get("masked"));
        assertFalse(masked.contains("yash pall"));
        assertFalse(masked.contains("442577654"));
        assertTrue(masked.contains("987654321"), "Unlabelled nine-digit business values must not be guessed as SSNs");
        assertEquals(2L, ((Number) result.get("findingsCount")).longValue());
    }

    @Test void plainTextUploadLeavesDetectionStageAndProducesMaskedEncryptedOutput() throws Exception {
        UnstructuredProfileRepository profiles = mock(UnstructuredProfileRepository.class);
        UnstructuredProfileEntity profile = new UnstructuredProfileEntity(); profile.setId(9L); profile.setName("upload");
        profile.setStatus("ACTIVE"); profile.setVersionNo(1); profile.setCreatedBy("system");
        profile.setRulesJson("""
                [{"name":"Compact SSN before label","piiType":"SSN","pattern":"(?<!\\\\d)(\\\\d{9})\\\\s*(?:is\\\\s+)?(?:my\\\\s+)?ssn","function":"SSN","param1":"","param2":"","selector":"","enabled":true,"valueGroup":1}]
                """);
        when(profiles.findById(9L)).thenReturn(Optional.of(profile));

        UnstructuredJobRepository jobs = mock(UnstructuredJobRepository.class);
        AtomicReference<UnstructuredJobEntity> persisted = new AtomicReference<>();
        when(jobs.save(any(UnstructuredJobEntity.class))).thenAnswer(invocation -> {
            UnstructuredJobEntity job = invocation.getArgument(0);
            if (job.getId() == null) ReflectionTestUtils.setField(job, "id", 101L);
            persisted.set(job);
            return job;
        });
        when(jobs.findById(101L)).thenAnswer(invocation -> Optional.ofNullable(persisted.get()));

        ForgeProps props = new ForgeProps(); props.setMaskingSecret("test-secret"); props.setCapsuleSecret("test-secret");
        ManagedFileVault vault = new ManagedFileVault(props, Files.createTempDirectory("forgetdm-unstructured-test").toString());
        ReflectionTestUtils.invokeMethod(vault, "init");
        UnstructuredMaskingService service = new UnstructuredMaskingService(profiles, jobs, vault,
                new MaskingEngine("secret"), new ObjectMapper(), mock(AuditService.class),
                mock(OwnershipGuard.class),
                10_000_000, 1_000_000, 72);

        service.start(9L, new MockMultipartFile("file", "sample.txt", "text/plain",
                "Customer note: 442577654 is my ssn".getBytes(StandardCharsets.UTF_8)), "seed-a");
        for (int i = 0; i < 100 && !"COMPLETED".equals(persisted.get().getStatus()); i++) Thread.sleep(50);

        assertEquals("COMPLETED", persisted.get().getStatus(), persisted.get().getErrorMessage());
        assertEquals(100, persisted.get().getProgress());
        try (var in = service.output(101L).stream()) {
            String masked = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(masked.contains("442577654"));
        } finally {
            service.shutdown();
        }
    }

    @Test void tikaRuntimeUsesTheCommonsLangVersionItWasCompiledAgainst() {
        assertDoesNotThrow(() -> org.apache.commons.lang3.SystemProperties.getUserName("unknown"));
    }

    @Test void cancellationRequestedBeforeDetectionStopsAtTheFirstSafeBoundary() throws Exception {
        UnstructuredProfileRepository profiles = mock(UnstructuredProfileRepository.class);
        UnstructuredProfileEntity profile = new UnstructuredProfileEntity();
        profile.setId(10L); profile.setName("cancel"); profile.setRulesJson("[]");
        when(profiles.findById(10L)).thenReturn(Optional.of(profile));

        UnstructuredJobEntity job = new UnstructuredJobEntity();
        ReflectionTestUtils.setField(job, "id", 102L);
        job.setProfileId(10L); job.setOriginalFilename("large.txt"); job.setCreatedBy("admin");
        job.setStatus("QUEUED"); job.setCancelRequested(true);
        UnstructuredJobRepository jobs = mock(UnstructuredJobRepository.class);
        when(jobs.findById(102L)).thenReturn(Optional.of(job));
        when(jobs.save(any(UnstructuredJobEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AuditService audit = mock(AuditService.class);
        ManagedFileVault vault = mock(ManagedFileVault.class);
        UnstructuredMaskingService service = new UnstructuredMaskingService(profiles, jobs, vault,
                new MaskingEngine("secret"), new ObjectMapper(), audit, mock(OwnershipGuard.class),
                10_000_000, 1_000_000, 72);

        ReflectionTestUtils.invokeMethod(service, "process", 102L, "seed-a");

        assertEquals("CANCELED", job.getStatus());
        assertEquals("CANCELED", job.getStage());
        verify(vault, never()).open(any(), any(), any());
        verify(audit).record(eq("admin"), eq("UNSTRUCTURED_JOB_CANCELED"), eq("CANCEL"),
                eq("UNSTRUCTURED_JOB"), eq("102"), eq("large.txt"), eq("SUCCESS"), any(), isNull());
        service.shutdown();
    }
}
