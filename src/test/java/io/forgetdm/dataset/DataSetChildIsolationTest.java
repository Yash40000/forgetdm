package io.forgetdm.dataset;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.discovery.ClassificationRepository;
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

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class DataSetChildIsolationTest {

    private static final long ALPHA_DATASET = 91L;

    private final DataSetDefinitionRepository definitions = mock(DataSetDefinitionRepository.class);
    private final TableProfileRepository profiles = mock(TableProfileRepository.class);
    private final ColumnOverrideRepository overrides = mock(ColumnOverrideRepository.class);
    private final UserDefinedPkRepository customPks = mock(UserDefinedPkRepository.class);
    private final UserDefinedRelationshipRepository relationships = mock(UserDefinedRelationshipRepository.class);
    private final RelationshipTraversalRuleRepository traversalRules = mock(RelationshipTraversalRuleRepository.class);
    private final AuditService audit = mock(AuditService.class);

    private DataSetService service;

    @BeforeEach
    void setUp() {
        DataSetDefinitionEntity alpha = mock(DataSetDefinitionEntity.class);
        when(alpha.getOwnerUserId()).thenReturn(11L);
        when(alpha.getOwnerGroupId()).thenReturn(101L);
        when(alpha.getVisibility()).thenReturn(OwnershipGuard.GROUP);
        when(definitions.findById(ALPHA_DATASET)).thenReturn(Optional.of(alpha));

        service = new DataSetService(
                definitions, profiles, overrides, customPks, relationships, traversalRules,
                mock(SubsetService.class), mock(DataSourceService.class), mock(ConnectionFactory.class),
                audit, mock(ClassificationRepository.class), mock(MaskingRuleRepository.class),
                new OwnershipGuard(audit), mock(GovernedReferenceGuard.class));
    }

    @Test
    void blocksCrossGroupProfileDeleteBeforeLookingUpTheChild() {
        ApiException denial = asBeta(() -> service.deleteProfile(ALPHA_DATASET, "accounts"));

        assertEquals(HttpStatus.FORBIDDEN, denial.getStatus());
        verifyNoInteractions(profiles);
    }

    @Test
    void blocksCrossGroupColumnOverrideDeleteBeforeMutation() {
        ColumnOverrideEntity child = new ColumnOverrideEntity();
        child.setDatasetId(ALPHA_DATASET);
        when(overrides.findById(201L)).thenReturn(Optional.of(child));

        ApiException denial = asBeta(() -> service.deleteOverride(201L));

        assertEquals(HttpStatus.FORBIDDEN, denial.getStatus());
        verify(overrides, never()).deleteById(anyLong());
    }

    @Test
    void blocksCrossGroupCustomPrimaryKeyDeleteBeforeMutation() {
        UserDefinedPkEntity child = new UserDefinedPkEntity();
        child.setDatasetId(ALPHA_DATASET);
        when(customPks.findById(301L)).thenReturn(Optional.of(child));

        ApiException denial = asBeta(() -> service.deleteCustomPk(301L));

        assertEquals(HttpStatus.FORBIDDEN, denial.getStatus());
        verify(customPks, never()).deleteById(anyLong());
    }

    @Test
    void blocksCrossGroupRelationshipUpdateBeforeMutation() {
        UserDefinedRelationshipEntity child = relationship(ALPHA_DATASET);
        when(relationships.findById(401L)).thenReturn(Optional.of(child));

        ApiException denial = asBeta(() -> service.updateUserRel(401L, relationship(ALPHA_DATASET)));

        assertEquals(HttpStatus.FORBIDDEN, denial.getStatus());
        verify(relationships, never()).save(any());
    }

    @Test
    void blocksCrossGroupRelationshipDeleteAndCascadeBeforeMutation() {
        UserDefinedRelationshipEntity child = relationship(ALPHA_DATASET);
        when(relationships.findById(401L)).thenReturn(Optional.of(child));

        ApiException denial = asBeta(() -> service.deleteUserRel(401L));

        assertEquals(HttpStatus.FORBIDDEN, denial.getStatus());
        verifyNoInteractions(traversalRules);
        verify(relationships, never()).deleteById(anyLong());
    }

    @Test
    void allowsSameGroupChildMutation() {
        ColumnOverrideEntity child = new ColumnOverrideEntity();
        child.setDatasetId(ALPHA_DATASET);
        when(overrides.findById(201L)).thenReturn(Optional.of(child));

        AccessContext.callAs(principal(12L, "alpha-peer", 101L), null, () -> {
            service.deleteOverride(201L);
            return null;
        });

        verify(overrides).deleteById(201L);
    }

    private ApiException asBeta(Runnable action) {
        return assertThrows(ApiException.class, () -> AccessContext.callAs(
                principal(21L, "beta-user", 202L), null, () -> {
                    action.run();
                    return null;
                }));
    }

    private static AccessPrincipal principal(Long id, String username, Long groupId) {
        return new AccessPrincipal(id, username, username, Set.of(), Set.of(),
                List.of(new AccessControlService.GroupLite(groupId, "group-" + groupId)));
    }

    private static UserDefinedRelationshipEntity relationship(Long datasetId) {
        UserDefinedRelationshipEntity rel = new UserDefinedRelationshipEntity();
        rel.setDatasetId(datasetId);
        rel.setParentTable("customers");
        rel.setParentColumns("customer_id");
        rel.setChildTable("accounts");
        rel.setChildColumns("customer_id");
        return rel;
    }
}
