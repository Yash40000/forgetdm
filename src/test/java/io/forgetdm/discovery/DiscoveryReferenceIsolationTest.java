package io.forgetdm.discovery;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.config.ForgeProps;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceRepository;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.policy.MaskingPolicyRepository;
import io.forgetdm.policy.MaskingRuleRepository;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessControlService;
import io.forgetdm.security.AccessPrincipal;
import io.forgetdm.security.OwnershipGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class DiscoveryReferenceIsolationTest {

    private final ClassificationRepository classifications = mock(ClassificationRepository.class);
    private final DataSourceRepository sourceRepo = mock(DataSourceRepository.class);
    private DiscoveryService service;

    @BeforeEach
    void setUp() {
        AuditService audit = mock(AuditService.class);
        OwnershipGuard ownership = new OwnershipGuard(audit);
        DataSourceService sources = new DataSourceService(sourceRepo, mock(ConnectionFactory.class), audit,
                ownership, mock(JdbcTemplate.class));
        when(sourceRepo.findById(1L)).thenReturn(Optional.of(source(1L, 11L, 101L)));
        when(sourceRepo.findById(2L)).thenReturn(Optional.of(source(2L, 21L, 202L)));
        service = new DiscoveryService(classifications, sources, mock(ConnectionFactory.class),
                mock(MaskingPolicyRepository.class), mock(MaskingRuleRepository.class), audit,
                new ForgeProps(), mock(PiiPatternService.class));
    }

    @Test
    void resultsRejectHiddenDataSourceBeforeReadingFindings() {
        ApiException denial = asAlphaFailure(() -> service.results(2L));

        assertEquals(HttpStatus.FORBIDDEN, denial.getStatus());
        verify(classifications, never()).findByDataSourceId(anyLong());
    }

    @Test
    void rawClassificationIdCannotMutateAnotherGroupsFinding() {
        ClassificationEntity beta = finding(82L, 2L);
        when(classifications.findById(82L)).thenReturn(Optional.of(beta));

        ApiException denial = asAlphaFailure(() -> service.setStatus(82L, "APPROVED"));

        assertEquals(HttpStatus.FORBIDDEN, denial.getStatus());
        assertEquals("SUGGESTED", beta.getStatus());
        verify(classifications, never()).save(any());
    }

    @Test
    void mixedTenantBulkMutationIsAtomicOnAuthorizationFailure() {
        ClassificationEntity alpha = finding(81L, 1L);
        ClassificationEntity beta = finding(82L, 2L);
        when(classifications.findAllById(any())).thenReturn(List.of(alpha, beta));

        ApiException denial = asAlphaFailure(() -> service.bulkUpdateClassifications(List.of(81L, 82L), "APPROVED"));

        assertEquals(HttpStatus.FORBIDDEN, denial.getStatus());
        assertEquals("SUGGESTED", alpha.getStatus());
        assertEquals("SUGGESTED", beta.getStatus());
        verify(classifications, never()).saveAll(any());
    }

    private ApiException asAlphaFailure(Runnable action) {
        AccessPrincipal alpha = new AccessPrincipal(11L, "alpha-user", "Alpha", Set.of(), Set.of(),
                List.of(new AccessControlService.GroupLite(101L, "alpha")));
        return assertThrows(ApiException.class, () -> AccessContext.callAs(alpha, null, () -> {
            action.run();
            return null;
        }));
    }

    private static DataSourceEntity source(Long id, Long ownerId, Long groupId) {
        DataSourceEntity source = new DataSourceEntity();
        source.setId(id);
        source.setName("source-" + id);
        source.setRole("SOURCE");
        source.setOwnerUserId(ownerId);
        source.setOwnerGroupId(groupId);
        source.setVisibility(OwnershipGuard.GROUP);
        return source;
    }

    private static ClassificationEntity finding(Long id, Long sourceId) {
        ClassificationEntity finding = spy(new ClassificationEntity());
        when(finding.getId()).thenReturn(id);
        finding.setDataSourceId(sourceId);
        finding.setTableName("customers");
        finding.setColumnName("ssn");
        finding.setPiiType("SSN");
        finding.setStatus("SUGGESTED");
        return finding;
    }
}
