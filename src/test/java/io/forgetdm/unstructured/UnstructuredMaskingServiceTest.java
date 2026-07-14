package io.forgetdm.unstructured;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.core.mask.MaskingEngine;
import io.forgetdm.filevault.ManagedFileVault;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

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
                new ObjectMapper(), mock(AuditService.class), 10_000_000, 1_000_000, 72);
        assertTrue(String.valueOf(service.capabilities().get("guarantee")).contains("fails closed"));
    }
}
