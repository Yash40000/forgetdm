package io.forgetdm.security;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class AccessControlService {
    public static final String SESSION_COOKIE = "FORGETDM_SESSION";
    private static final SecureRandom RNG = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final String bootstrapPassword;
    private final Duration sessionTtl;

    /**
     * A real PBKDF2 hash of an unguessable value, verified against when no user matches so that the
     * "unknown user" path performs the same work as the "wrong password" path (DEF-0016). Built once
     * at construction with the same algorithm and iteration count as stored credentials, so the two
     * paths stay cost-matched if those parameters ever change.
     */
    private final String dummyHash = PasswordHasher.hash(UUID.randomUUID().toString());

    public AccessControlService(JdbcTemplate jdbc,
                                AuditService audit,
                                @Value("${forgetdm.security.bootstrap-password:admin123}") String bootstrapPassword,
                                @Value("${forgetdm.security.session-hours:12}") long sessionHours) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.bootstrapPassword = bootstrapPassword;
        this.sessionTtl = Duration.ofHours(Math.max(1, sessionHours));
    }

    @PostConstruct
    @Transactional
    public void bootstrap() {
        Integer users = jdbc.queryForObject("SELECT COUNT(*) FROM forge_users", Integer.class);
        if (users != null && users > 0) return;

        String passwordHash = PasswordHasher.hash(bootstrapPassword);
        Long adminId = insertUser("admin", "Platform Admin", passwordHash, true);
        jdbc.update("INSERT INTO forge_user_roles(user_id, role_name) VALUES (?, ?)", adminId, "ADMIN");
        Long groupId = insertGroup("TDM Admins", "Users with complete platform administration access.");
        jdbc.update("INSERT INTO forge_group_roles(group_id, role_name) VALUES (?, ?)", groupId, "ADMIN");
        jdbc.update("INSERT INTO forge_user_groups(user_id, group_id) VALUES (?, ?)", adminId, groupId);
        audit.log("system", "SECURITY_BOOTSTRAP", "Created default admin user and TDM Admins group");
    }

    public Map<String, Object> roles() {
        return Map.of("roles", RoleDefinition.ALL);
    }

    public Optional<AccessPrincipal> principalFromRequest(HttpServletRequest request) {
        return tokenFromRequest(request).flatMap(this::principalFromToken);
    }

    public Optional<AccessPrincipal> principalFromToken(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        String hash = tokenHash(token);
        List<Long> ids = jdbc.query(
                "SELECT u.id FROM forge_sessions s JOIN forge_users u ON u.id = s.user_id " +
                        "WHERE s.token_hash = ? AND s.expires_at > ? AND u.active = TRUE",
                (rs, rowNum) -> rs.getLong(1), hash, ts(Instant.now()));
        if (ids.isEmpty()) {
            ids = jdbc.query(
                    "SELECT u.id FROM forge_api_tokens t JOIN forge_users u ON u.id = t.user_id " +
                            "WHERE t.token_hash = ? AND t.revoked_at IS NULL AND (t.expires_at IS NULL OR t.expires_at > ?) AND u.active = TRUE",
                    (rs, rowNum) -> rs.getLong(1), hash, ts(Instant.now()));
            if (ids.isEmpty()) return Optional.empty();
            jdbc.update("UPDATE forge_api_tokens SET last_used_at = ? WHERE token_hash = ?", ts(Instant.now()), hash);
        } else {
            jdbc.update("UPDATE forge_sessions SET last_seen_at = ? WHERE token_hash = ?", ts(Instant.now()), hash);
        }
        return Optional.of(principal(ids.get(0)));
    }

    @Transactional
    public ApiTokenCreated createApiToken(ApiTokenRequest request) {
        AccessPrincipal principal = AccessContext.current()
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Login required"));
        String name = blankToNull(request == null ? null : request.name());
        if (name == null) throw ApiException.bad("Token name is required");
        if (name.length() > 160) throw ApiException.bad("Token name must be 160 characters or fewer");
        Instant expiresAt = request == null ? null : request.expiresAt();
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) throw ApiException.bad("Token expiry must be in the future");
        String id = UUID.randomUUID().toString();
        String clear = "ftdm_" + newToken();
        Instant now = Instant.now();
        try {
            jdbc.update("INSERT INTO forge_api_tokens(id, user_id, name, token_hash, token_prefix, created_at, expires_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    id, principal.userId(), name, tokenHash(clear), clear.substring(0, Math.min(16, clear.length())),
                    ts(now), expiresAt == null ? null : ts(expiresAt));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw ApiException.conflict("You already have an API token named " + name);
        }
        audit.record(principal.username(), "API_TOKEN_CREATED", "SECURITY", "api-token", id, name,
                "SUCCESS", "Created API token", expiresAt == null ? null : "{\"expiresAt\":\"" + expiresAt + "\"}");
        return new ApiTokenCreated(id, name, clear, expiresAt, now);
    }

    public List<ApiTokenView> apiTokens() {
        AccessPrincipal principal = AccessContext.current()
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Login required"));
        return jdbc.query("SELECT id, name, token_prefix, created_at, expires_at, last_used_at, revoked_at " +
                        "FROM forge_api_tokens WHERE user_id = ? ORDER BY created_at DESC",
                (rs, rowNum) -> new ApiTokenView(rs.getString("id"), rs.getString("name"), rs.getString("token_prefix"),
                        instant(rs, "created_at"), instant(rs, "expires_at"), instant(rs, "last_used_at"), instant(rs, "revoked_at")),
                principal.userId());
    }

    public void revokeApiToken(String id) {
        AccessPrincipal principal = AccessContext.current()
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Login required"));
        int changed = jdbc.update("UPDATE forge_api_tokens SET revoked_at = ? WHERE id = ? AND user_id = ? AND revoked_at IS NULL",
                ts(Instant.now()), id, principal.userId());
        if (changed == 0) throw ApiException.notFound("Active API token " + id + " not found");
        audit.record(principal.username(), "API_TOKEN_REVOKED", "SECURITY", "api-token", id, null,
                "SUCCESS", "Revoked API token", null);
    }

    @Transactional
    public LoginResult login(String username, String password) {
        List<UserSecret> rows = jdbc.query(
                "SELECT id, username, display_name, password_hash, active FROM forge_users WHERE LOWER(username) = LOWER(?)",
                (rs, rowNum) -> new UserSecret(
                        rs.getLong("id"),
                        rs.getString("username"),
                        rs.getString("display_name"),
                        rs.getString("password_hash"),
                        rs.getBoolean("active")),
                username == null ? "" : username.trim());

        // Constant-work verification (DEF-0016). PBKDF2 runs 160k iterations (~600ms); short-circuiting
        // on "user not found" or "user inactive" skipped that work and answered in ~23ms, a 27x timing
        // gap with no overlap — a reliable username-enumeration oracle even though the status and body
        // are identical. Always perform exactly one verification, against a dummy hash when there is no
        // candidate, so every failure path costs the same.
        UserSecret candidate = rows.isEmpty() ? null : rows.get(0);
        boolean passwordOk = PasswordHasher.verify(password, candidate == null ? dummyHash : candidate.passwordHash);
        if (candidate == null || !candidate.active || !passwordOk) {
            audit.log(username == null || username.isBlank() ? "anonymous" : username, "LOGIN_FAILED", "Invalid username/password");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }

        UserSecret user = candidate;
        String token = newToken();
        Instant now = Instant.now();
        Instant expires = now.plus(sessionTtl);
        jdbc.update("INSERT INTO forge_sessions(token_hash, user_id, created_at, expires_at, last_seen_at) VALUES (?, ?, ?, ?, ?)",
                tokenHash(token), user.id, ts(now), ts(expires), ts(now));
        AccessPrincipal principal = principal(user.id);
        audit.log(user.username, "LOGIN_SUCCESS", "User signed in");
        return new LoginResult(token, expires, principal);
    }

    @Transactional
    public void logout(String token) {
        if (token == null || token.isBlank()) return;
        principalFromToken(token).ifPresent(p -> audit.log(p.username(), "LOGOUT", "User signed out"));
        jdbc.update("DELETE FROM forge_sessions WHERE token_hash = ?", tokenHash(token));
    }

    public AccessPrincipal principal(long userId) {
        UserView user = jdbc.queryForObject(
                "SELECT id, username, display_name, active, created_at, updated_at FROM forge_users WHERE id = ?",
                this::mapUser, userId);
        if (user == null || !user.active()) throw new ApiException(HttpStatus.UNAUTHORIZED, "User is inactive");
        Set<String> roles = rolesForUser(user.id());
        Set<String> permissions = permissionsForRoles(roles);
        // Group membership is part of the principal: /api/auth/me exposes it (DEF-0002) and
        // tenant scoping uses it to decide which group-owned objects the caller may see (DEF-0007).
        List<GroupLite> groups = groupsForUser(user.id());
        return new AccessPrincipal(user.id(), user.username(), user.displayName(), roles, permissions, groups);
    }

    public List<UserView> users() {
        return jdbc.query("SELECT id, username, display_name, active, created_at, updated_at FROM forge_users ORDER BY username", this::mapUser).stream()
                .map(u -> u.withRolesAndGroups(rolesForUser(u.id()), groupsForUser(u.id())))
                .toList();
    }

    public List<GroupView> groups() {
        return jdbc.query("SELECT id, name, description, created_at FROM forge_groups ORDER BY name", this::mapGroup).stream()
                .map(g -> g.withRoles(rolesForGroup(g.id())))
                .toList();
    }

    @Transactional
    public UserView createUser(UserRequest req) {
        requireUsername(req.username());
        String password = req.password();
        if (password == null || password.length() < 8) throw ApiException.bad("Password must be at least 8 characters");
        Long id = insertUser(req.username().trim(), blankToNull(req.displayName()), PasswordHasher.hash(password), req.active() == null || req.active());
        setUserRoles(id, cleanRoles(req.roles()));
        setUserGroups(id, cleanIds(req.groupIds()));
        audit.record(currentActor(), "SECURITY_USER_CREATED", "SECURITY", "security-user",
                String.valueOf(id), req.username().trim(), "SUCCESS", "Created security user", null);
        return users().stream().filter(u -> u.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public UserView updateUser(long id, UserRequest req) {
        UserView existing = requireUser(id);
        if (req.displayName() != null || req.active() != null) {
            jdbc.update("UPDATE forge_users SET display_name = COALESCE(?, display_name), active = COALESCE(?, active), updated_at = ? WHERE id = ?",
                    req.displayName(), req.active(), ts(Instant.now()), id);
        }
        if (req.password() != null && !req.password().isBlank()) {
            if (req.password().length() < 8) throw ApiException.bad("Password must be at least 8 characters");
            jdbc.update("UPDATE forge_users SET password_hash = ?, updated_at = ? WHERE id = ?",
                    PasswordHasher.hash(req.password()), ts(Instant.now()), id);
        }
        if (req.roles() != null) setUserRoles(id, cleanRoles(req.roles()));
        if (req.groupIds() != null) setUserGroups(id, cleanIds(req.groupIds()));
        UserView updated = users().stream().filter(u -> u.id().equals(id)).findFirst().orElseThrow();
        audit.record(currentActor(), "SECURITY_USER_UPDATED", "SECURITY", "security-user",
                String.valueOf(id), updated.username(), "SUCCESS", "Updated security user",
                "{\"previousActive\":" + existing.active() + ",\"active\":" + updated.active() + "}");
        return updated;
    }

    @Transactional
    public void deleteUser(long id) {
        UserView user = requireUser(id);
        if ("admin".equalsIgnoreCase(user.username()) && users().stream().filter(UserView::active).count() == 1) {
            throw ApiException.conflict("Cannot delete the only active admin bootstrap user");
        }
        jdbc.update("DELETE FROM forge_users WHERE id = ?", id);
        audit.record(currentActor(), "SECURITY_USER_DELETED", "SECURITY", "security-user",
                String.valueOf(id), user.username(), "SUCCESS", "Deleted security user", null);
    }

    @Transactional
    public GroupView createGroup(GroupRequest req) {
        if (req.name() == null || req.name().isBlank()) throw ApiException.bad("Group name is required");
        Long id = insertGroup(req.name().trim(), blankToNull(req.description()));
        setGroupRoles(id, cleanRoles(req.roles()));
        audit.record(currentActor(), "SECURITY_GROUP_CREATED", "SECURITY", "security-group",
                String.valueOf(id), req.name().trim(), "SUCCESS", "Created security group", null);
        return groups().stream().filter(g -> g.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public GroupView updateGroup(long id, GroupRequest req) {
        GroupView existing = requireGroup(id);
        if (req.name() != null || req.description() != null) {
            jdbc.update("UPDATE forge_groups SET name = COALESCE(?, name), description = COALESCE(?, description) WHERE id = ?",
                    blankToNull(req.name()), req.description(), id);
        }
        if (req.roles() != null) setGroupRoles(id, cleanRoles(req.roles()));
        GroupView updated = groups().stream().filter(g -> g.id().equals(id)).findFirst().orElseThrow();
        audit.record(currentActor(), "SECURITY_GROUP_UPDATED", "SECURITY", "security-group",
                String.valueOf(id), updated.name(), "SUCCESS", "Updated security group",
                "{\"previousName\":\"" + jsonSafe(existing.name()) + "\"}");
        return updated;
    }

    @Transactional
    public void deleteGroup(long id) {
        GroupView group = requireGroup(id);
        jdbc.update("DELETE FROM forge_groups WHERE id = ?", id);
        audit.record(currentActor(), "SECURITY_GROUP_DELETED", "SECURITY", "security-group",
                String.valueOf(id), group.name(), "SUCCESS", "Deleted security group", null);
    }

    private static String currentActor() {
        return AccessContext.current().map(AccessPrincipal::username).orElse("system");
    }

    private static String jsonSafe(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private Long insertUser(String username, String displayName, String passwordHash, boolean active) {
        Long existing = jdbc.query("SELECT id FROM forge_users WHERE LOWER(username) = LOWER(?)",
                (rs, rowNum) -> rs.getLong(1), username).stream().findFirst().orElse(null);
        if (existing != null) throw ApiException.conflict("Username already exists");
        jdbc.update("INSERT INTO forge_users(username, display_name, password_hash, active, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                username, displayName, passwordHash, active, ts(Instant.now()), ts(Instant.now()));
        return jdbc.queryForObject("SELECT id FROM forge_users WHERE LOWER(username) = LOWER(?)", Long.class, username);
    }

    private Long insertGroup(String name, String description) {
        Long existing = jdbc.query("SELECT id FROM forge_groups WHERE LOWER(name) = LOWER(?)",
                (rs, rowNum) -> rs.getLong(1), name).stream().findFirst().orElse(null);
        if (existing != null) throw ApiException.conflict("Group already exists");
        jdbc.update("INSERT INTO forge_groups(name, description, created_at) VALUES (?, ?, ?)", name, description, ts(Instant.now()));
        return jdbc.queryForObject("SELECT id FROM forge_groups WHERE LOWER(name) = LOWER(?)", Long.class, name);
    }

    private UserView requireUser(long id) {
        return jdbc.query("SELECT id, username, display_name, active, created_at, updated_at FROM forge_users WHERE id = ?",
                this::mapUser, id).stream().findFirst().orElseThrow(() -> ApiException.notFound("User not found"));
    }

    private GroupView requireGroup(long id) {
        return jdbc.query("SELECT id, name, description, created_at FROM forge_groups WHERE id = ?",
                this::mapGroup, id).stream().findFirst().orElseThrow(() -> ApiException.notFound("Group not found"));
    }

    private void requireUsername(String username) {
        if (username == null || username.isBlank()) throw ApiException.bad("Username is required");
        if (!username.matches("[A-Za-z0-9._@-]{3,120}")) throw ApiException.bad("Username must be 3-120 letters, numbers, dot, underscore, at-sign, or dash");
    }

    private void setUserRoles(long userId, Set<String> roles) {
        jdbc.update("DELETE FROM forge_user_roles WHERE user_id = ?", userId);
        roles.forEach(role -> jdbc.update("INSERT INTO forge_user_roles(user_id, role_name) VALUES (?, ?)", userId, role));
    }

    private void setGroupRoles(long groupId, Set<String> roles) {
        jdbc.update("DELETE FROM forge_group_roles WHERE group_id = ?", groupId);
        roles.forEach(role -> jdbc.update("INSERT INTO forge_group_roles(group_id, role_name) VALUES (?, ?)", groupId, role));
    }

    private void setUserGroups(long userId, Set<Long> groupIds) {
        jdbc.update("DELETE FROM forge_user_groups WHERE user_id = ?", userId);
        for (Long groupId : groupIds) {
            requireGroup(groupId);
            jdbc.update("INSERT INTO forge_user_groups(user_id, group_id) VALUES (?, ?)", userId, groupId);
        }
    }

    private Set<String> rolesForUser(long userId) {
        Set<String> roles = new LinkedHashSet<>();
        roles.addAll(jdbc.query("SELECT role_name FROM forge_user_roles WHERE user_id = ? ORDER BY role_name",
                (rs, rowNum) -> rs.getString(1), userId));
        roles.addAll(jdbc.query("SELECT gr.role_name FROM forge_group_roles gr JOIN forge_user_groups ug ON ug.group_id = gr.group_id WHERE ug.user_id = ? ORDER BY gr.role_name",
                (rs, rowNum) -> rs.getString(1), userId));
        return roles;
    }

    private Set<String> rolesForGroup(long groupId) {
        return new LinkedHashSet<>(jdbc.query("SELECT role_name FROM forge_group_roles WHERE group_id = ? ORDER BY role_name",
                (rs, rowNum) -> rs.getString(1), groupId));
    }

    /**
     * Group ids for a username — used to bound an approver to the creator's tenant
     * (DEF-0007 / RBAC-002-05). Returns empty for unknown/legacy free-text creators.
     */
    public Set<Long> groupIdsForUsername(String username) {
        if (username == null || username.isBlank()) return Set.of();
        return new LinkedHashSet<>(jdbc.query(
                "SELECT ug.group_id FROM forge_user_groups ug JOIN forge_users u ON u.id = ug.user_id " +
                        "WHERE LOWER(u.username) = LOWER(?)",
                (rs, rowNum) -> rs.getLong(1), username.trim()));
    }

    private List<GroupLite> groupsForUser(long userId) {
        return jdbc.query("SELECT g.id, g.name FROM forge_groups g JOIN forge_user_groups ug ON ug.group_id = g.id WHERE ug.user_id = ? ORDER BY g.name",
                (rs, rowNum) -> new GroupLite(rs.getLong("id"), rs.getString("name")), userId);
    }

    private Set<String> permissionsForRoles(Set<String> roles) {
        Set<String> permissions = new LinkedHashSet<>();
        for (String role : roles) {
            RoleDefinition def = RoleDefinition.BY_NAME.get(role);
            if (def != null) permissions.addAll(def.permissions());
        }
        return permissions;
    }

    private Set<String> cleanRoles(List<String> roles) {
        if (roles == null) return Set.of();
        Set<String> clean = new LinkedHashSet<>();
        for (String role : roles) {
            if (role == null || role.isBlank()) continue;
            String normalized = role.trim().toUpperCase();
            if (!RoleDefinition.BY_NAME.containsKey(normalized)) throw ApiException.bad("Unknown role: " + role);
            clean.add(normalized);
        }
        return clean;
    }

    private Set<Long> cleanIds(List<Long> ids) {
        if (ids == null) return Set.of();
        Set<Long> clean = new LinkedHashSet<>();
        for (Long id : ids) if (id != null) clean.add(id);
        return clean;
    }

    private UserView mapUser(ResultSet rs, int rowNum) throws SQLException {
        return new UserView(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getBoolean("active"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toInstant(),
                List.of(),
                List.of());
    }

    private GroupView mapGroup(ResultSet rs, int rowNum) throws SQLException {
        return new GroupView(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getTimestamp("created_at").toInstant(),
                List.of());
    }

    /** Extract the session token (Bearer header or session cookie) from a request. Public so the security filter
     *  can stash it in {@link AccessContext} for in-process self-calls (the AI assistant's tools). */
    public Optional<String> tokenFromRequest(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = auth.substring(7).trim();
            if (!token.isBlank()) return Optional.of(token);
        }
        if (request.getCookies() == null) return Optional.empty();
        for (Cookie cookie : request.getCookies()) {
            if (SESSION_COOKIE.equals(cookie.getName())) return Optional.ofNullable(cookie.getValue());
        }
        return Optional.empty();
    }

    private String newToken() {
        byte[] bytes = new byte[48];
        RNG.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String tokenHash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash session token", e);
        }
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public record LoginResult(String token, Instant expiresAt, AccessPrincipal principal) {}
    public record ApiTokenRequest(String name, Instant expiresAt) {}
    public record ApiTokenCreated(String id, String name, String token, Instant expiresAt, Instant createdAt) {}
    public record ApiTokenView(String id, String name, String tokenPrefix, Instant createdAt, Instant expiresAt,
                               Instant lastUsedAt, Instant revokedAt) {}
    private record UserSecret(Long id, String username, String displayName, String passwordHash, boolean active) {}
    public record GroupLite(Long id, String name) {}

    public record UserView(Long id, String username, String displayName, boolean active, Instant createdAt, Instant updatedAt,
                           List<String> roles, List<GroupLite> groups) {
        UserView withRolesAndGroups(Set<String> nextRoles, List<GroupLite> nextGroups) {
            List<String> sortedRoles = new ArrayList<>(nextRoles);
            sortedRoles.sort(Comparator.naturalOrder());
            return new UserView(id, username, displayName, active, createdAt, updatedAt, sortedRoles, nextGroups);
        }
    }

    public record GroupView(Long id, String name, String description, Instant createdAt, List<String> roles) {
        GroupView withRoles(Set<String> nextRoles) {
            List<String> sortedRoles = new ArrayList<>(nextRoles);
            sortedRoles.sort(Comparator.naturalOrder());
            return new GroupView(id, name, description, createdAt, sortedRoles);
        }
    }

    public record UserRequest(String username, String displayName, String password, Boolean active, List<String> roles, List<Long> groupIds) {}
    public record GroupRequest(String name, String description, List<String> roles) {}
}
