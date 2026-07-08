package io.forgetdm.security;

import java.util.Set;

public record AccessPrincipal(
        Long userId,
        String username,
        String displayName,
        Set<String> roles,
        Set<String> permissions
) {
    public boolean hasPermission(String permission) {
        return permissions.contains("admin.all") || permissions.contains(permission);
    }
}
