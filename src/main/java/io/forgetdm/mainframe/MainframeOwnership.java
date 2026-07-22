package io.forgetdm.mainframe;

import io.forgetdm.common.ApiException;
import io.forgetdm.security.OwnershipGuard;

import java.util.Locale;

/** MAINFRAME-specific ownership invariants layered over the shared tenancy guard. */
final class MainframeOwnership {

    private MainframeOwnership() { }

    static boolean canSee(OwnershipGuard ownership, Long ownerUserId, Long ownerGroupId, String visibility) {
        if (isOrphanedNonShared(ownerUserId, ownerGroupId, visibility)) {
            return ownership.caller().map(caller -> caller.isAdmin()).orElse(true);
        }
        return ownership.canSee(ownerUserId, ownerGroupId, visibility);
    }

    static void assertCanSee(OwnershipGuard ownership, String resourceType, Object resourceId,
                             Long ownerUserId, Long ownerGroupId, String visibility) {
        if (!isOrphanedNonShared(ownerUserId, ownerGroupId, visibility)) {
            ownership.assertCanSee(resourceType, resourceId, ownerUserId, ownerGroupId, visibility);
            return;
        }
        ownership.caller().filter(caller -> !caller.isAdmin()).ifPresent(caller -> {
            // Force the shared guard's normal audited denial without colliding with this caller's id.
            Long deniedOwnerId = Long.valueOf(Long.MIN_VALUE).equals(caller.userId()) ? Long.MAX_VALUE : Long.MIN_VALUE;
            ownership.assertCanSee(resourceType, resourceId, deniedOwnerId, null, OwnershipGuard.PRIVATE);
        });
    }

    static boolean isOrphanedNonShared(Long ownerUserId, Long ownerGroupId, String visibility) {
        if (ownerUserId != null || ownerGroupId != null) return false;
        String scope = visibility == null || visibility.isBlank()
                ? OwnershipGuard.GROUP : visibility.trim().toUpperCase(Locale.ROOT);
        return !OwnershipGuard.SHARED.equals(scope);
    }

    static void assertOwnedOrShared(String resourceType, Object resourceId,
                                    Long ownerUserId, Long ownerGroupId, String visibility) {
        if (isOrphanedNonShared(ownerUserId, ownerGroupId, visibility)) {
            throw ApiException.forbidden("This " + resourceType + " no longer has a valid owner: " + resourceId);
        }
    }
}
