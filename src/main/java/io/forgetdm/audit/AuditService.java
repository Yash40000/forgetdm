package io.forgetdm.audit;

import io.forgetdm.security.AccessContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every consequential action lands in an append-only, tamper-evident audit ledger.
 *
 * <p>Each event is structured (actor, source IP, action, category, resource, outcome, severity) and
 * hash-chained: {@code hash = SHA-256(prev_hash | seq | actor | action | ... | detail)}. Because every
 * record commits the hash of the one before it, altering or deleting any historical row breaks the
 * chain and is detected by {@link #verifyChain()} — the essence of a compliance-grade trail.
 */
@Service
public class AuditService {
    private final AuditEventRepository repo;

    private volatile boolean initialized = false;
    private long lastSeq = 0;
    private String lastHash = "";

    public AuditService(AuditEventRepository repo) { this.repo = repo; }

    // ------------------------------------------------------------------ write

    /** Legacy entry point used across the app — actor/action/detail, everything else inferred. */
    public void log(String actor, String action, String detail) {
        String outcome = inferOutcome(action);
        AuditEventEntity e = new AuditEventEntity();
        e.setActor(resolveActor(actor));
        e.setAction(action);
        e.setCategory(inferCategory(action));
        e.setOutcome(outcome);
        e.setSeverity(inferSeverity(action, outcome));
        e.setDetail(detail);
        chainAndSave(e);
    }

    /** Structured entry point — new call sites can record the resource and rich context explicitly. */
    public void record(String actor, String action, String category, String resourceType, String resourceId,
                       String resourceName, String outcome, String detail, String metadataJson) {
        AuditEventEntity e = new AuditEventEntity();
        e.setActor(resolveActor(actor));
        e.setAction(action);
        e.setCategory(category != null ? category : inferCategory(action));
        String out = outcome != null ? outcome.toUpperCase(Locale.ROOT) : inferOutcome(action);
        e.setOutcome(out);
        e.setSeverity(inferSeverity(action, out));
        e.setResourceType(resourceType);
        e.setResourceId(resourceId);
        e.setResourceName(resourceName);
        e.setDetail(detail);
        e.setMetadata(metadataJson);
        chainAndSave(e);
    }

    private synchronized void chainAndSave(AuditEventEntity e) {
        ensureInit();
        long seq = ++lastSeq;
        Instant now = e.getCreatedAt() != null ? e.getCreatedAt() : Instant.now();
        e.setCreatedAt(now);
        e.setSeq(seq);
        e.setIpAddress(currentIp());
        e.setUserAgent(currentUserAgent());
        if (e.getOutcome() == null) e.setOutcome("SUCCESS");
        if (e.getSeverity() == null) e.setSeverity("INFO");
        if (e.getCategory() == null) e.setCategory("GENERAL");
        String prev = lastHash;
        e.setPrevHash(prev);
        e.setHash(computeHash(prev, e));
        AuditEventEntity saved = repo.save(e);
        lastHash = saved.getHash();
    }

    private synchronized void ensureInit() {
        if (initialized) return;
        repo.findFirstByOrderBySeqDesc().ifPresent(e -> {
            lastSeq = e.getSeq() == null ? 0 : e.getSeq();
            lastHash = e.getHash() == null ? "" : e.getHash();
        });
        initialized = true;
    }

    // --------------------------------------------------------------- integrity

    /** Recompute the whole chain and report tamper status. Legacy (pre-hardening) rows have no hash and are skipped. */
    public Map<String, Object> verifyChain() {
        List<AuditEventEntity> all = repo.findAllByOrderBySeqAsc();
        String expectedPrev = null;
        long verifiedThrough = 0;
        int hashed = 0;
        int legacy = 0;
        Long brokenAtSeq = null;
        for (AuditEventEntity e : all) {
            if (e.getHash() == null || e.getHash().isBlank()) { legacy++; continue; }
            if (expectedPrev == null) expectedPrev = e.getPrevHash() == null ? "" : e.getPrevHash(); // accept first as genesis
            boolean linkOk = expectedPrev.equals(e.getPrevHash() == null ? "" : e.getPrevHash());
            boolean hashOk = computeHash(e.getPrevHash() == null ? "" : e.getPrevHash(), e).equals(e.getHash());
            if (!linkOk || !hashOk) { brokenAtSeq = e.getSeq(); break; }
            hashed++;
            verifiedThrough = e.getSeq() == null ? verifiedThrough : e.getSeq();
            expectedPrev = e.getHash();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("valid", brokenAtSeq == null);
        out.put("total", all.size());
        out.put("hashedCount", hashed);
        out.put("legacyCount", legacy);
        out.put("verifiedThroughSeq", verifiedThrough);
        if (brokenAtSeq != null) out.put("brokenAtSeq", brokenAtSeq);
        return out;
    }

    private String computeHash(String prevHash, AuditEventEntity e) {
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
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash audit event", ex);
        }
    }

    // --------------------------------------------------------------- inference

    private static String inferCategory(String action) {
        String a = action == null ? "" : action.toUpperCase(Locale.ROOT);
        if (a.startsWith("LOGIN") || a.startsWith("LOGOUT") || a.contains("SESSION")) return "AUTH";
        if (a.startsWith("SECURITY") || a.contains("ROLE") || a.contains("PERMISSION") || a.contains("GRANT") || a.contains("REVOKE")) return "SECURITY";
        if (a.startsWith("VIRT")) return "VIRTUALIZATION";
        if (a.startsWith("POLICY") || a.contains("MASK")) return "MASKING";
        if (a.startsWith("SYNTHETIC")) return "SYNTHETIC";
        if (a.startsWith("DATASCOPE") || a.contains("PROVISION")) return "PROVISIONING";
        if (a.startsWith("DISCOVERY") || a.contains("PII")) return "DISCOVERY";
        if (a.contains("BUSINESS_ENTITY") || a.startsWith("BE_") || a.startsWith("ENTITY")) return "BUSINESS_ENTITY";
        if (a.startsWith("MAPPING")) return "MAPPING";
        if (a.startsWith("DATASOURCE") || a.contains("DATA_SOURCE")) return "DATA_SOURCE";
        if (a.startsWith("MAINFRAME") || a.startsWith("MF_") || a.contains("COPYBOOK")) return "MAINFRAME";
        return "GENERAL";
    }

    private static String inferOutcome(String action) {
        String a = action == null ? "" : action.toUpperCase(Locale.ROOT);
        if (a.contains("FAIL") || a.contains("REJECT") || a.contains("DENIED") || a.contains("ERROR") || a.contains("INVALID")) return "FAILURE";
        return "SUCCESS";
    }

    private static String inferSeverity(String action, String outcome) {
        String a = action == null ? "" : action.toUpperCase(Locale.ROOT);
        if ("FAILURE".equals(outcome)) return a.contains("LOGIN") || a.contains("DENIED") || a.contains("SECURITY") ? "CRITICAL" : "WARNING";
        if (a.contains("DELETE") || a.contains("REVOKE") || a.contains("DROP") || a.contains("BOOTSTRAP")) return "NOTICE";
        return "INFO";
    }

    // ------------------------------------------------------------------ context

    private String resolveActor(String actor) {
        if (actor != null && !actor.isBlank() && !"system".equalsIgnoreCase(actor)) return actor;
        return AccessContext.current().map(p -> p.username()).orElse(actor == null || actor.isBlank() ? "system" : actor);
    }

    private String currentIp() {
        HttpServletRequest req = currentRequest();
        if (req == null) return null;
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return req.getRemoteAddr();
    }

    private String currentUserAgent() {
        HttpServletRequest req = currentRequest();
        return req == null ? null : truncate(req.getHeader("User-Agent"), 400);
    }

    private HttpServletRequest currentRequest() {
        try {
            var attrs = RequestContextHolder.getRequestAttributes();
            return attrs instanceof ServletRequestAttributes sra ? sra.getRequest() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
