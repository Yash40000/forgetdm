package io.forgetdm.security;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The authenticated caller. Carries the effective role/permission set and the caller's
 * group membership — groups are both an identity fact (surfaced by /api/auth/me) and the
 * tenant scope used to decide visibility of group-owned objects (see {@link OwnershipGuard}).
 */
public record AccessPrincipal(
        Long userId,
        String username,
        String displayName,
        Set<String> roles,
        Set<String> permissions,
        List<AccessControlService.GroupLite> groups
) {
    /** Convenience for callers (and tests) that do not model group membership. */
    public AccessPrincipal(Long userId, String username, String displayName,
                           Set<String> roles, Set<String> permissions) {
        this(userId, username, displayName, roles, permissions, List.of());
    }

    public boolean hasPermission(String permission) {
        return permissions.contains("admin.all") || permissions.contains(permission);
    }

    /** Platform administrators bypass tenant scoping (and are audited for it). */
    public boolean isAdmin() {
        return permissions.contains("admin.all");
    }

    /** Group ids this user belongs to — the tenant scope for group-owned objects. */
    public Set<Long> groupIds() {
        if (groups == null || groups.isEmpty()) return Set.of();
        return groups.stream()
                .map(AccessControlService.GroupLite::id)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }
}
