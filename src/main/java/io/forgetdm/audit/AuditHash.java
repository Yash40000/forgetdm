package io.forgetdm.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Canonical audit hash. Kept as a standalone utility so both the writer (append path) and
 * {@code AuditService.verifyChain()} hash identically — if these two ever diverge the whole chain
 * reports as tampered.
 *
 * <p>The payload layout is frozen: changing field order, adding a field, or altering the separator
 * invalidates every historical hash. Do not modify without a re-anchor plan.
 */
public final class AuditHash {
    private AuditHash() {}

    public static String compute(String prevHash, AuditEventEntity e) {
        String payload = String.join("|",
                nz(prevHash),
                String.valueOf(e.getSeq()),
                nz(e.getActor()),
                nz(e.getAction()),
                nz(e.getCategory()),
                nz(e.getResourceType()),
                nz(e.getResourceId()),
                nz(e.getResourceName()),
                nz(e.getOutcome()),
                nz(e.getSeverity()),
                String.valueOf(e.getCreatedAt() == null ? 0 : e.getCreatedAt().toEpochMilli()),
                nz(e.getDetail()));
        if (e.getHashVersion() != null && e.getHashVersion() >= 2) {
            payload = payload + "|" + String.join("|",
                    String.valueOf(e.getHashVersion()),
                    String.valueOf(e.getOwnerUserId()),
                    nz(e.getOwnerUsername()),
                    String.valueOf(e.getOwnerGroupId()),
                    nz(e.getVisibility()));
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash audit event", ex);
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
