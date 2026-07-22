package io.forgetdm.security;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.core.mask.MaskingEngine;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceRepository;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.policy.MaskingPolicyEntity;
import io.forgetdm.policy.MaskingPolicyRepository;
import io.forgetdm.policy.MaskingRuleRepository;
import io.forgetdm.policy.PolicyController;
import io.forgetdm.provision.ValueListService;
import io.forgetdm.reservation.ReservationEntity;
import io.forgetdm.reservation.ReservationRepository;
import io.forgetdm.reservation.ReservationService;
import io.forgetdm.subset.SubsetService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class CoreObjectIsolationTest {

    private final AuditService audit = mock(AuditService.class);
    private final OwnershipGuard guard = new OwnershipGuard(audit);

    @Test
    void dataSourceListAndDirectReadRespectTenantAndSharedScopes() {
        DataSourceRepository repo = mock(DataSourceRepository.class);
        DataSourceEntity alpha = dataSource(11L, 1L, 101L, OwnershipGuard.GROUP);
        DataSourceEntity beta = dataSource(12L, 2L, 202L, OwnershipGuard.GROUP);
        DataSourceEntity shared = dataSource(13L, 1L, 101L, OwnershipGuard.SHARED);
        when(repo.findAll()).thenReturn(List.of(alpha, beta, shared));
        when(repo.findById(11L)).thenReturn(Optional.of(alpha));
        DataSourceService service = new DataSourceService(repo, mock(ConnectionFactory.class), audit,
                guard, mock(JdbcTemplate.class));

        List<Long> visible = as(beta(), () -> service.list().stream().map(DataSourceEntity::getId).toList());
        assertEquals(List.of(12L, 13L), visible);
        assertForbidden(() -> as(beta(), () -> service.get(11L)));
        assertSame(alpha, as(alphaPeer(), () -> service.get(11L)));
        assertSame(alpha, as(admin(), () -> service.get(11L)));
    }

    @Test
    void policyListRulesAndDeleteRespectTenantBoundary() {
        MaskingPolicyRepository policies = mock(MaskingPolicyRepository.class);
        MaskingRuleRepository rules = mock(MaskingRuleRepository.class);
        MaskingPolicyEntity alpha = policy(21L, 1L, 101L, OwnershipGuard.GROUP);
        MaskingPolicyEntity beta = policy(22L, 2L, 202L, OwnershipGuard.GROUP);
        MaskingPolicyEntity shared = policy(23L, 1L, 101L, OwnershipGuard.SHARED);
        when(policies.findAll()).thenReturn(List.of(alpha, beta, shared));
        when(policies.findById(21L)).thenReturn(Optional.of(alpha));
        GovernedReferenceGuard references = mock(GovernedReferenceGuard.class);
        when(references.canSeeDataSource(nullable(Long.class))).thenReturn(true);
        PolicyController controller = new PolicyController(policies, rules, mock(MaskingEngine.class),
                audit, mock(ValueListService.class), guard, references);

        List<Long> visible = as(beta(), () -> controller.list(null, null).stream()
                .map(MaskingPolicyEntity::getId).toList());
        assertEquals(List.of(22L, 23L), visible);
        assertForbidden(() -> as(beta(), () -> controller.rules(21L)));
        assertForbidden(() -> as(beta(), () -> {
            controller.delete(21L);
            return null;
        }));
        verifyNoInteractions(rules);
        verify(policies, never()).deleteById(21L);
    }

    @Test
    void reservationListAndReleaseRespectTenantBoundary() {
        ReservationRepository repo = mock(ReservationRepository.class);
        ReservationEntity alpha = reservation(31L, 1L, 101L, OwnershipGuard.GROUP);
        ReservationEntity beta = reservation(32L, 2L, 202L, OwnershipGuard.GROUP);
        ReservationEntity shared = reservation(33L, 1L, 101L, OwnershipGuard.SHARED);
        when(repo.findAll()).thenReturn(List.of(alpha, beta, shared));
        when(repo.findById(31L)).thenReturn(Optional.of(alpha));
        when(repo.save(alpha)).thenReturn(alpha);
        ReservationService service = new ReservationService(repo, mock(DataSourceService.class),
                mock(ConnectionFactory.class), mock(SubsetService.class), audit, guard);

        List<Long> visible = as(beta(), () -> service.list().stream().map(ReservationEntity::getId).toList());
        assertEquals(List.of(33L, 32L), visible);
        assertForbidden(() -> as(beta(), () -> service.release(31L)));
        verify(repo, never()).save(alpha);
        assertSame(alpha, as(alphaPeer(), () -> service.release(31L)));
        assertEquals("RELEASED", alpha.getStatus());
    }

    private static DataSourceEntity dataSource(Long id, Long ownerId, Long groupId, String visibility) {
        DataSourceEntity entity = new DataSourceEntity();
        entity.setId(id);
        entity.setName("source-" + id);
        entity.setOwnerUserId(ownerId);
        entity.setOwnerGroupId(groupId);
        entity.setVisibility(visibility);
        return entity;
    }

    private static MaskingPolicyEntity policy(Long id, Long ownerId, Long groupId, String visibility) {
        MaskingPolicyEntity entity = new MaskingPolicyEntity();
        entity.setId(id);
        entity.setName("policy-" + id);
        entity.setOwnerUserId(ownerId);
        entity.setOwnerGroupId(groupId);
        entity.setVisibility(visibility);
        return entity;
    }

    private static ReservationEntity reservation(Long id, Long ownerId, Long groupId, String visibility) {
        ReservationEntity entity = new ReservationEntity();
        ReflectionTestUtils.setField(entity, "id", id);
        entity.setReservedBy("owner-" + ownerId);
        entity.setOwnerUserId(ownerId);
        entity.setOwnerGroupId(groupId);
        entity.setVisibility(visibility);
        entity.setExpiresAt(Instant.now().plusSeconds(3600));
        return entity;
    }

    private static void assertForbidden(Runnable work) {
        ApiException denial = assertThrows(ApiException.class, work::run);
        assertEquals(HttpStatus.FORBIDDEN, denial.getStatus());
    }

    private static AccessPrincipal alphaPeer() {
        return principal(3L, "alpha-peer", 101L, Set.of());
    }

    private static AccessPrincipal beta() {
        return principal(2L, "beta", 202L, Set.of());
    }

    private static AccessPrincipal admin() {
        return new AccessPrincipal(99L, "admin", "Admin", Set.of(), Set.of("admin.all"));
    }

    private static AccessPrincipal principal(Long id, String username, Long groupId, Set<String> permissions) {
        return new AccessPrincipal(id, username, username, Set.of(), permissions,
                List.of(new AccessControlService.GroupLite(groupId, "group-" + groupId)));
    }

    private static <T> T as(AccessPrincipal principal, java.util.function.Supplier<T> work) {
        return AccessContext.callAs(principal, null, work);
    }
}
