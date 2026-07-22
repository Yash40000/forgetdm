package io.forgetdm.security;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OwnershipGuardTest {

    private AuditService audit;
    private OwnershipGuard guard;

    @BeforeEach
    void setUp() {
        audit = mock(AuditService.class);
        guard = new OwnershipGuard(audit);
    }

    @Test
    void privateObjectsAreOwnerOnly() {
        assertTrue(as(principal(11L, "owner", 101L),
                () -> guard.canSee(11L, 101L, OwnershipGuard.PRIVATE)));
        assertFalse(as(principal(12L, "peer", 101L),
                () -> guard.canSee(11L, 101L, OwnershipGuard.PRIVATE)));
    }

    @Test
    void groupObjectsFollowCurrentMembershipOnEveryRequest() {
        assertTrue(as(principal(12L, "member", 101L),
                () -> guard.canSee(11L, 101L, OwnershipGuard.GROUP)));
        assertFalse(as(principal(12L, "member", 202L),
                () -> guard.canSee(11L, 101L, OwnershipGuard.GROUP)));
    }

    @Test
    void sharedObjectsDoNotExposePrivateSiblings() {
        AccessPrincipal beta = principal(21L, "beta", 202L);
        assertTrue(as(beta, () -> guard.canSee(11L, 101L, OwnershipGuard.SHARED)));
        assertFalse(as(beta, () -> guard.canSee(11L, 101L, OwnershipGuard.PRIVATE)));
    }

    @Test
    void administratorMayAccessEveryVisibility() {
        AccessPrincipal admin = new AccessPrincipal(1L, "admin", "Admin", Set.of(), Set.of("admin.all"), List.of());
        assertTrue(as(admin, () -> guard.canSee(11L, 101L, OwnershipGuard.PRIVATE)));
        assertTrue(as(admin, () -> guard.canSee(11L, 101L, OwnershipGuard.GROUP)));
    }

    @Test
    void backgroundSystemContextRemainsAvailableForWorkers() {
        assertTrue(guard.canSee(11L, 101L, OwnershipGuard.PRIVATE));
    }

    @Test
    void deniedAccessIsForbiddenAndStructurallyAudited() {
        ApiException denial = assertThrows(ApiException.class, () -> as(
                principal(21L, "beta", 202L),
                () -> {
                    guard.assertCanSee("policy", 77L, 11L, 101L, OwnershipGuard.GROUP);
                    return null;
                }));

        assertEquals(HttpStatus.FORBIDDEN, denial.getStatus());
        verify(audit).record("beta", "ACCESS_DENIED", "SECURITY", "policy", "77", null,
                "FAILURE", "policy 77 belongs to another tenant", null);
    }

    @Test
    void newObjectsReceiveCallerOwnershipAndGroupScope() {
        AccessPrincipal owner = principal(11L, "owner", 101L);
        as(owner, () -> {
            assertEquals(11L, guard.defaultOwnerUserId());
            assertEquals("owner", guard.defaultOwnerUsername());
            assertEquals(101L, guard.defaultOwnerGroupId());
            assertEquals(OwnershipGuard.GROUP, guard.defaultVisibility());
            return null;
        });
    }

    private static AccessPrincipal principal(Long id, String username, Long groupId) {
        return new AccessPrincipal(id, username, username, Set.of(), Set.of(),
                List.of(new AccessControlService.GroupLite(groupId, "group-" + groupId)));
    }

    private static <T> T as(AccessPrincipal principal, java.util.function.Supplier<T> work) {
        return AccessContext.callAs(principal, null, work);
    }
}
