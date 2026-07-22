package io.forgetdm.dataset;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceRepository;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.discovery.ClassificationRepository;
import io.forgetdm.policy.MaskingPolicyEntity;
import io.forgetdm.policy.MaskingPolicyRepository;
import io.forgetdm.policy.MaskingRuleRepository;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessControlService;
import io.forgetdm.security.AccessPrincipal;
import io.forgetdm.security.GovernedReferenceGuard;
import io.forgetdm.security.OwnershipGuard;
import io.forgetdm.subset.SubsetService;
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

class DataSetReferenceIsolationTest {

    private final DataSetDefinitionRepository definitions = mock(DataSetDefinitionRepository.class);
    private final TableProfileRepository profiles = mock(TableProfileRepository.class);
    private final DataSourceRepository sourceRepo = mock(DataSourceRepository.class);
    private final MaskingPolicyRepository policyRepo = mock(MaskingPolicyRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private DataSetService service;

    @BeforeEach
    void setUp() {
        OwnershipGuard ownership = new OwnershipGuard(audit);
        DataSourceService sources = new DataSourceService(sourceRepo, mock(ConnectionFactory.class), audit,
                ownership, mock(JdbcTemplate.class));
        GovernedReferenceGuard references = new GovernedReferenceGuard(sourceRepo, policyRepo, ownership);

        when(sourceRepo.findById(1L)).thenReturn(Optional.of(source(1L, 11L, 101L)));
        when(sourceRepo.findById(2L)).thenReturn(Optional.of(source(2L, 21L, 202L)));
        when(policyRepo.findById(31L)).thenReturn(Optional.of(policy(31L, 11L, 101L, 1L)));
        when(policyRepo.findById(32L)).thenReturn(Optional.of(policy(32L, 21L, 202L, 2L)));

        service = new DataSetService(definitions, profiles, mock(ColumnOverrideRepository.class),
                mock(UserDefinedPkRepository.class), mock(UserDefinedRelationshipRepository.class),
                mock(RelationshipTraversalRuleRepository.class), mock(SubsetService.class), sources,
                mock(ConnectionFactory.class), audit, mock(ClassificationRepository.class),
                mock(MaskingRuleRepository.class), ownership, references);
    }

    @Test
    void createRejectsHiddenSourceBeforeSavingBlueprint() {
        DataSetDefinitionEntity request = definition(null, 2L, null);
        request.setName("alpha-scope");

        ApiException denial = asAlphaFailure(() -> service.create(request));

        assertEquals(HttpStatus.FORBIDDEN, denial.getStatus());
        verify(definitions, never()).save(any());
    }

    @Test
    void updateRejectsHiddenPolicyBeforeMutatingBlueprint() {
        DataSetDefinitionEntity alpha = definition(91L, 1L, null);
        when(definitions.findById(91L)).thenReturn(Optional.of(alpha));

        ApiException denial = asAlphaFailure(() -> service.updatePolicy(91L, 32L));

        assertEquals(HttpStatus.FORBIDDEN, denial.getStatus());
        verify(definitions, never()).save(any());
    }

    @Test
    void profileSaveRejectsHiddenPerTableSourceBeforeReplacingRows() {
        DataSetDefinitionEntity alpha = definition(91L, 1L, 31L);
        when(definitions.findById(91L)).thenReturn(Optional.of(alpha));
        TableProfileEntity profile = new TableProfileEntity();
        profile.setTableName("accounts");
        profile.setSourceDataSourceId(2L);

        ApiException denial = asAlphaFailure(() -> service.saveProfiles(91L, List.of(profile)));

        assertEquals(HttpStatus.FORBIDDEN, denial.getStatus());
        verify(profiles, never()).deleteByDatasetId(anyLong());
        verify(profiles, never()).saveAll(any());
    }

    @Test
    void runtimeReauthorizationRejectsLegacyHiddenProfileReference() {
        DataSetDefinitionEntity alpha = definition(91L, 1L, 31L);
        when(definitions.findById(91L)).thenReturn(Optional.of(alpha));
        TableProfileEntity hidden = new TableProfileEntity();
        hidden.setTableName("legacy_beta_table");
        hidden.setSourceDataSourceId(2L);
        when(profiles.findByDatasetId(91L)).thenReturn(List.of(hidden));

        ApiException denial = asAlphaFailure(() -> service.assertAuthorizedReferences(91L, null));

        assertEquals(HttpStatus.FORBIDDEN, denial.getStatus());
    }

    @Test
    void sameGroupReferencesPassRuntimeReauthorization() {
        DataSetDefinitionEntity alpha = definition(91L, 1L, 31L);
        when(definitions.findById(91L)).thenReturn(Optional.of(alpha));
        when(profiles.findByDatasetId(91L)).thenReturn(List.of());

        DataSetDefinitionEntity result = asAlpha(() -> service.assertAuthorizedReferences(91L, 31L));

        assertEquals(alpha, result);
    }

    private ApiException asAlphaFailure(Runnable action) {
        return assertThrows(ApiException.class, () -> asAlpha(() -> {
            action.run();
            return null;
        }));
    }

    private <T> T asAlpha(java.util.function.Supplier<T> work) {
        AccessPrincipal principal = new AccessPrincipal(11L, "alpha-user", "Alpha", Set.of(), Set.of(),
                List.of(new AccessControlService.GroupLite(101L, "alpha")));
        return AccessContext.callAs(principal, null, work);
    }

    private static DataSetDefinitionEntity definition(Long id, Long sourceId, Long policyId) {
        DataSetDefinitionEntity d = spy(new DataSetDefinitionEntity());
        if (id != null) when(d.getId()).thenReturn(id);
        d.setDataSourceId(sourceId);
        d.setPolicyId(policyId);
        d.setOwnerUserId(11L);
        d.setOwnerGroupId(101L);
        d.setVisibility(OwnershipGuard.GROUP);
        return d;
    }

    private static DataSourceEntity source(Long id, Long ownerId, Long groupId) {
        DataSourceEntity source = new DataSourceEntity();
        source.setId(id);
        source.setName("source-" + id);
        source.setRole("BOTH");
        source.setOwnerUserId(ownerId);
        source.setOwnerGroupId(groupId);
        source.setVisibility(OwnershipGuard.GROUP);
        return source;
    }

    private static MaskingPolicyEntity policy(Long id, Long ownerId, Long groupId, Long sourceId) {
        MaskingPolicyEntity policy = new MaskingPolicyEntity();
        policy.setId(id);
        policy.setName("policy-" + id);
        policy.setDataSourceId(sourceId);
        policy.setOwnerUserId(ownerId);
        policy.setOwnerGroupId(groupId);
        policy.setVisibility(OwnershipGuard.GROUP);
        return policy;
    }
}
