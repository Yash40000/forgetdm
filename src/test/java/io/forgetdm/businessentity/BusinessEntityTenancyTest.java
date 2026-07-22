package io.forgetdm.businessentity;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.dataset.DataSetDefinitionRepository;
import io.forgetdm.dataset.DataSetService;
import io.forgetdm.datasource.DataSourceRepository;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessControlService;
import io.forgetdm.security.AccessPrincipal;
import io.forgetdm.security.OwnershipGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessEntityTenancyTest {
    private BusinessEntityDefinitionRepository definitions;
    private BusinessEntityMemberRepository members;
    private AuditService audit;
    private BusinessEntityService service;
    private Map<Long, BusinessEntityDefinitionEntity> rows;
    private AccessPrincipal alpha;
    private AccessPrincipal alphaOwner;
    private AccessPrincipal beta;
    private AccessPrincipal admin;

    @BeforeEach
    void setUp() {
        definitions = mock(BusinessEntityDefinitionRepository.class);
        members = mock(BusinessEntityMemberRepository.class);
        DataSetDefinitionRepository datasets = mock(DataSetDefinitionRepository.class);
        DataSetService dataSetService = mock(DataSetService.class);
        DataSourceRepository dataSources = mock(DataSourceRepository.class);
        audit = mock(AuditService.class);
        service = new BusinessEntityService(definitions, members, datasets, dataSetService, dataSources,
                audit, new OwnershipGuard(audit));

        rows = new LinkedHashMap<>();
        rows.put(1L, entity(1L, "Alpha group entity", 102L, "alpha-owner", 10L, OwnershipGuard.GROUP));
        rows.put(2L, entity(2L, "Beta entity", 202L, "beta-owner", 20L, OwnershipGuard.GROUP));
        rows.put(3L, entity(3L, "Shared entity", 202L, "beta-owner", 20L, OwnershipGuard.SHARED));
        rows.put(4L, entity(4L, "Alpha private entity", 102L, "alpha-owner", 10L, OwnershipGuard.PRIVATE));

        when(definitions.findAll(any(Sort.class))).thenAnswer(inv -> List.copyOf(rows.values()));
        when(definitions.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(rows.get(inv.getArgument(0))));
        when(definitions.findByName(anyString())).thenReturn(Optional.empty());
        AtomicLong ids = new AtomicLong(100L);
        when(definitions.save(any())).thenAnswer(inv -> {
            BusinessEntityDefinitionEntity value = inv.getArgument(0);
            if (value.getId() == null) value.setId(ids.getAndIncrement());
            rows.put(value.getId(), value);
            return value;
        });
        when(members.findByEntityIdOrderByOrdinalNoAscIdAsc(anyLong())).thenReturn(List.of());
        when(members.findAll()).thenReturn(List.of());

        alpha = principal(101L, "alpha-user", 10L, "ALPHA", false);
        alphaOwner = principal(102L, "alpha-owner", 10L, "ALPHA", false);
        beta = principal(202L, "beta-owner", 20L, "BETA", false);
        admin = principal(1L, "admin", null, null, true);
    }

    @Test
    void filtersRootsAndEnforcesPrivateGroupSharedAdminAndBackgroundSemantics() {
        List<Long> alphaVisible = as(alpha, () -> service.list().stream()
                .map(BusinessEntityService.BusinessEntitySummary::id).toList());
        assertEquals(List.of(1L, 3L), alphaVisible);

        assertEquals(1L, as(alpha, () -> service.getDetail(1L)).entity().getId());
        assertEquals(4L, as(alphaOwner, () -> service.getDetail(4L)).entity().getId());
        assertThrows(ApiException.class, () -> as(alpha, () -> service.getDetail(2L)));
        assertThrows(ApiException.class, () -> as(alpha, () -> service.getDetail(4L)));
        assertEquals(2L, as(beta, () -> service.getDetail(2L)).entity().getId());
        assertEquals(2L, as(admin, () -> service.getDetail(2L)).entity().getId());
        assertEquals(2L, AccessContext.callAs(null, null, () -> service.getDetail(2L)).entity().getId());
    }

    @Test
    void crossGroupRootMutationDeleteAndMemberReplacementHaveNoBusinessSideEffects() {
        BusinessEntityDefinitionEntity update = new BusinessEntityDefinitionEntity();
        update.setName("Spoofed beta update");
        clearInvocations(definitions, members);

        assertThrows(ApiException.class, () -> as(alpha, () -> service.update(2L, update)));
        assertThrows(ApiException.class, () -> as(alpha, () -> {
            service.replaceMembers(2L, List.of());
            return null;
        }));
        assertThrows(ApiException.class, () -> as(alpha, () -> {
            service.delete(2L);
            return null;
        }));

        verify(definitions, never()).save(any());
        verify(definitions, never()).delete(any());
        verify(members, never()).deleteByEntityId(anyLong());
        verify(members, never()).deleteAll(any());
        assertEquals("Beta entity", rows.get(2L).getName());
    }

    @Test
    void sameGroupCanMutateAndCallerSuppliedOwnerMetadataCannotSpoofTenancy() {
        BusinessEntityDefinitionEntity update = new BusinessEntityDefinitionEntity();
        update.setName("Alpha group entity updated");
        BusinessEntityDefinitionEntity updated = as(alpha, () -> service.update(1L, update));
        assertEquals("Alpha group entity updated", updated.getName());
        assertEquals(102L, updated.getOwnerUserId());
        assertEquals(10L, updated.getOwnerGroupId());

        BusinessEntityDefinitionEntity request = new BusinessEntityDefinitionEntity();
        request.setName("Alpha owned new entity");
        request.setOwnerUserId(9999L);
        request.setOwnerUsername("beta-spoof");
        request.setOwnerGroupId(20L);
        request.setVisibility(OwnershipGuard.PRIVATE);
        BusinessEntityDefinitionEntity created = as(alpha, () -> service.create(request));

        assertEquals(101L, created.getOwnerUserId());
        assertEquals("alpha-user", created.getOwnerUsername());
        assertEquals(10L, created.getOwnerGroupId());
        assertEquals(OwnershipGuard.PRIVATE, created.getVisibility());
    }

    private static BusinessEntityDefinitionEntity entity(Long id, String name, Long ownerUserId,
                                                          String ownerUsername, Long ownerGroupId, String visibility) {
        BusinessEntityDefinitionEntity entity = new BusinessEntityDefinitionEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setOwnerUserId(ownerUserId);
        entity.setOwnerUsername(ownerUsername);
        entity.setOwnerGroupId(ownerGroupId);
        entity.setVisibility(visibility);
        return entity;
    }

    private static AccessPrincipal principal(Long userId, String username, Long groupId, String groupName, boolean admin) {
        List<AccessControlService.GroupLite> groups = groupId == null
                ? List.of() : List.of(new AccessControlService.GroupLite(groupId, groupName));
        return new AccessPrincipal(userId, username, username, Set.of(),
                admin ? Set.of("admin.all") : Set.of("business-entity.manage"), groups);
    }

    private static <T> T as(AccessPrincipal principal, java.util.function.Supplier<T> work) {
        return AccessContext.callAs(principal, "test-token", work);
    }
}
