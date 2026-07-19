package io.forgetdm.security;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Tenant scoping for core governed objects (DEF-0007 / RBAC-002).
 *
 * <p>{@link AccessControlFilter} answers "may this caller call this route at all?" (RBAC).
 * This guard answers the second question the filter cannot: "may this caller touch <em>this
 * particular object</em>?" Without it, any holder of {@code policy.manage} could delete another
 * group's policy — which RBAC-002 demonstrated live.
 *
 * <p>Mirrors the pattern already proven in {@code SyntheticGenService.ensureCanSee}, generalised
 * to owner + group + visibility so every core service can reuse it.
 *
 * <p>Visibility semantics:
 * <ul>
 *   <li>{@code PRIVATE} — owner only</li>
 *   <li>{@code GROUP}   — owner plus members of the owning group (default for new objects)</li>
 *   <li>{@code SHARED}  — any authenticated caller who holds the route permission</li>
 * </ul>
 *
 * <p><b>System context:</b> when there is no {@link AccessContext} principal the guard allows the
 * operation. That is deliberate — async provisioning workers, {@code @Scheduled} sweeps and
 * {@code @PostConstruct} bootstrap run off-request with no user to scope against, and
 * {@link AccessControlFilter} has already rejected unauthenticated API traffic with 401 before any
 * service is reached. Failing closed here would break background jobs, not improve security.
 *
 * <p>{@code admin.all} bypasses scoping (and remains audited by the normal audit path).
 */
@Component
public class OwnershipGuard {

    public static final String PRIVATE = "PRIVATE";
    public static final String GROUP = "GROUP";
    public static final String SHARED = "SHARED";

    private final AuditService audit;

    public OwnershipGuard(AuditService audit) {
        this.audit = audit;
    }

    /** The current user, if this is a user request (empty in system/background context). */
    public Optional<AccessPrincipal> caller() {
        return AccessContext.current();
    }

    /** The current user, or 401 — only for paths that genuinely require a user. */
    public AccessPrincipal require() {
        return AccessContext.current()
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Login required"));
    }

    /** True when the caller may see/act on an object with this ownership triple. */
    public boolean canSee(Long ownerUserId, Long ownerGroupId, String visibility) {
        Optional<AccessPrincipal> maybe = AccessContext.current();
        if (maybe.isEmpty()) return true;               // system/background context — see class note
        AccessPrincipal p = maybe.get();
        if (p.isAdmin()) return true;

        String scope = visibility == null || visibility.isBlank() ? GROUP : visibility.trim().toUpperCase();

        if (SHARED.equals(scope)) return true;
        // Legacy/unowned rows (pre-V61 rows the backfill left unowned) stay visible.
        if (ownerUserId == null && ownerGroupId == null) return true;

        if (ownerUserId != null && Objects.equals(ownerUserId, p.userId())) return true;
        if (PRIVATE.equals(scope)) return false;

        return ownerGroupId != null && p.groupIds().contains(ownerGroupId);
    }

    /**
     * Enforce object-level access, or fail with 403 and an audited denial.
     *
     * @param resourceType e.g. "policy", "data source" — used in the audit detail and message
     */
    public void assertCanSee(String resourceType, Object resourceId,
                             Long ownerUserId, Long ownerGroupId, String visibility) {
        if (canSee(ownerUserId, ownerGroupId, visibility)) return;
        String actor = AccessContext.current().map(AccessPrincipal::username).orElse("system");
        // Structured resource identity so the trail is queryable by object (DEF-0010), not just by
        // string-matching the free-text detail.
        audit.record(actor, "ACCESS_DENIED", "SECURITY", resourceType,
                resourceId == null ? null : String.valueOf(resourceId), null, "FAILURE",
                resourceType + " " + resourceId + " belongs to another tenant", null);
        throw new ApiException(HttpStatus.FORBIDDEN, "This " + resourceType + " belongs to another group");
    }

    /** Owner user id to stamp on a newly created object (null in system context). */
    public Long defaultOwnerUserId() {
        return AccessContext.current().map(AccessPrincipal::userId).orElse(null);
    }

    /** Owner username to stamp on a newly created object (null in system context). */
    public String defaultOwnerUsername() {
        return AccessContext.current().map(AccessPrincipal::username).orElse(null);
    }

    /** Owning group for a newly created object — the caller's first group, if any. */
    public Long defaultOwnerGroupId() {
        Set<Long> ids = AccessContext.current().map(AccessPrincipal::groupIds).orElse(Set.of());
        return ids.isEmpty() ? null : ids.iterator().next();
    }

    /**
     * Default visibility for a new object: GROUP when we know the creator, otherwise SHARED so
     * system-created artifacts stay reachable (they have no tenant to belong to).
     */
    public String defaultVisibility() {
        return AccessContext.current().isPresent() ? GROUP : SHARED;
    }
}
