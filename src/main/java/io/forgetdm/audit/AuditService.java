package io.forgetdm.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.common.ApiException;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessControlFilter;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final AuditWriter writer;
    private final ObjectMapper objectMapper;

    public AuditService(AuditEventRepository repo, AuditWriter writer, ObjectMapper objectMapper) {
        this.repo = repo;
        this.writer = writer;
        this.objectMapper = objectMapper;
    }

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
        LegacyResource resource = inferResource(action, detail);
        e.setResourceType(resource.type());
        e.setResourceId(resource.id());
        e.setResourceName(resource.name());
        e.setDetail(truncate(detail, 2000));
        e.setMetadata(contextMetadata(null));
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
        LegacyResource inferred = inferResource(action, detail);
        e.setResourceType(firstText(resourceType, inferred.type()));
        e.setResourceId(firstText(resourceId, inferred.id()));
        e.setResourceName(firstText(resourceName, inferred.name()));
        e.setDetail(truncate(detail, 2000));
        e.setMetadata(contextMetadata(metadataJson));
        chainAndSave(e);
    }

    /**
     * Hand the event to {@link AuditWriter}, which assigns the sequence and hash and commits in its
     * own transaction. Auditing must never fail the business operation it is recording, so a write
     * problem is swallowed after being surfaced on stderr.
     */
    private void chainAndSave(AuditEventEntity e) {
        e.setIpAddress(currentIp());
        e.setUserAgent(currentUserAgent());
        try {
            writer.append(e);
        } catch (RuntimeException ex) {
            System.err.println("[audit] failed to persist " + e.getAction() + ": " + ex.getMessage());
        }
    }

    // --------------------------------------------------------------- integrity

    /** Recompute the whole chain and report tamper status. Legacy (pre-hardening) rows have no hash and are skipped. */
    /**
     * Recompute the whole chain and report tamper status.
     *
     * <p>Verification deliberately does <b>not</b> stop at the first bad link (DEF-0009). A single
     * historical break — e.g. the seq 702/703 fork created before the sequence was made atomic —
     * previously aborted the walk and left ~3,000 later events, including every current security
     * event, unverified while reporting a permanent {@code valid:false}. That is worse than useless:
     * the operator cannot tell a known structural break from live tampering.
     *
     * <p>Instead every break is recorded, the walk re-anchors on the current row and continues, and
     * integrity is reported per segment. {@code tamperSuspected} distinguishes a <b>content</b>
     * mismatch (a row's own hash does not match its fields — the signature of edited history) from a
     * mere <b>link</b> break (a discontinuity such as a fork or a rolled-back write).
     */
    public Map<String, Object> verifyChain() {
        List<AuditEventEntity> all = repo.findAllByOrderBySeqAsc();
        String expectedPrev = null;
        int hashed = 0, legacy = 0, verified = 0, linkBreaks = 0, contentBreaks = 0;
        int activeLinkBreaks = 0, activeContentBreaks = 0;
        long verifiedThrough = 0;
        List<Map<String, Object>> breaks = new java.util.ArrayList<>();
        List<Map<String, Object>> segments = new java.util.ArrayList<>();
        List<AuditEventEntity> prefix = new java.util.ArrayList<>();
        Long segmentStart = null;
        Long segmentPrevSeq = null;
        Long latestAnchorSeq = null;
        int historicalBreaks = 0;
        boolean anchorDigestValid = true;

        for (AuditEventEntity e : all) {
            if (e.getHash() == null || e.getHash().isBlank()) {
                legacy++;
                prefix.add(e);
                continue;
            }
            hashed++;
            String prev = e.getPrevHash() == null ? "" : e.getPrevHash();

            if ("AUDIT_CHAIN_REANCHORED".equals(e.getAction())) {
                String expectedDigest = metadataText(e.getMetadata(), "historyDigest");
                boolean digestOk = expectedDigest != null && expectedDigest.equals(historyDigest(prefix));
                boolean anchorHashOk = prev.isEmpty() && AuditHash.compute("", e).equals(e.getHash());
                anchorDigestValid = digestOk && anchorHashOk;
                historicalBreaks = breaks.size();
                activeLinkBreaks = 0;
                activeContentBreaks = 0;
                if (!anchorDigestValid) {
                    activeContentBreaks++;
                    contentBreaks++;
                    Map<String, Object> b = new LinkedHashMap<>();
                    b.put("seq", e.getSeq());
                    b.put("reason", !digestOk ? "ANCHOR_HISTORY_DIGEST_MISMATCH" : "ANCHOR_CONTENT_MISMATCH");
                    b.put("action", e.getAction());
                    breaks.add(b);
                } else {
                    verified++;
                    verifiedThrough = e.getSeq() == null ? verifiedThrough : e.getSeq();
                }
                if (segmentStart != null) segments.add(segment(segmentStart, segmentPrevSeq, true));
                segmentStart = anchorDigestValid ? e.getSeq() : null;
                segmentPrevSeq = e.getSeq();
                expectedPrev = e.getHash();
                latestAnchorSeq = e.getSeq();
                prefix.add(e);
                continue;
            }
            if (expectedPrev == null) expectedPrev = prev;            // genesis of the hashed region

            boolean linkOk = expectedPrev.equals(prev);
            boolean hashOk = AuditHash.compute(prev, e).equals(e.getHash());

            if (!linkOk || !hashOk) {
                if (!hashOk) {
                    contentBreaks++;
                    activeContentBreaks++;
                } else {
                    linkBreaks++;
                    activeLinkBreaks++;
                }
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("seq", e.getSeq());
                b.put("reason", !hashOk ? "CONTENT_MISMATCH" : "LINK_BREAK");
                b.put("action", e.getAction());
                breaks.add(b);
                if (segmentStart != null) segments.add(segment(segmentStart, segmentPrevSeq, true));
                segmentStart = null;                                   // re-anchor below
            } else {
                verified++;
                verifiedThrough = e.getSeq() == null ? verifiedThrough : e.getSeq();
                if (segmentStart == null) segmentStart = e.getSeq();
            }
            segmentPrevSeq = e.getSeq();
            expectedPrev = e.getHash();                                // re-anchor and keep going
            prefix.add(e);
        }
        if (segmentStart != null) segments.add(segment(segmentStart, segmentPrevSeq, true));

        Map<String, Object> out = new LinkedHashMap<>();
        boolean activeValid = latestAnchorSeq == null ? breaks.isEmpty()
                : anchorDigestValid && activeLinkBreaks == 0 && activeContentBreaks == 0;
        out.put("valid", activeValid);
        out.put("activeLedgerValid", activeValid);
        out.put("tamperSuspected", latestAnchorSeq == null ? contentBreaks > 0 : activeContentBreaks > 0);
        out.put("total", all.size());
        out.put("hashedCount", hashed);
        out.put("legacyCount", legacy);
        out.put("verifiedCount", verified);
        out.put("verifiedThroughSeq", verifiedThrough);
        out.put("linkBreaks", linkBreaks);
        out.put("contentBreaks", contentBreaks);
        out.put("activeLinkBreaks", activeLinkBreaks);
        out.put("activeContentBreaks", activeContentBreaks);
        out.put("historicalBreaks", latestAnchorSeq == null ? 0 : historicalBreaks);
        out.put("historicalIntegrityAcknowledged", latestAnchorSeq != null && historicalBreaks > 0);
        out.put("latestAnchorSeq", latestAnchorSeq);
        out.put("breaks", breaks.size() > 50 ? breaks.subList(0, 50) : breaks);
        out.put("verifiedSegments", segments);
        return out;
    }

    public Map<String, Object> reanchor(String reason) {
        var principal = AccessContext.current().orElseThrow(() -> ApiException.forbidden("Authentication required"));
        if (!principal.hasPermission("admin.all") && !principal.hasPermission("audit.manage")) {
            throw ApiException.forbidden("Only an audit administrator can re-anchor the ledger");
        }
        String normalizedReason = reason == null ? "" : reason.trim();
        if (normalizedReason.length() < 20 || normalizedReason.length() > 500) {
            throw ApiException.bad("Re-anchor reason must be between 20 and 500 characters");
        }
        List<AuditEventEntity> history = repo.findAllByOrderBySeqAsc();
        Map<String, Object> before = verifyChain();
        String digest = historyDigest(history);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("historyDigest", digest);
        metadata.put("historyRows", history.size());
        metadata.put("acknowledgedLinkBreaks", before.get("linkBreaks"));
        metadata.put("acknowledgedContentBreaks", before.get("contentBreaks"));
        metadata.put("reason", normalizedReason);

        AuditEventEntity anchor = new AuditEventEntity();
        anchor.setActor(principal.username());
        anchor.setAction("AUDIT_CHAIN_REANCHORED");
        anchor.setCategory("SECURITY");
        anchor.setResourceType("audit-ledger");
        anchor.setResourceName("audit-chain-checkpoint");
        anchor.setOutcome("SUCCESS");
        anchor.setSeverity("NOTICE");
        anchor.setDetail("Operator approved a new audit-chain checkpoint; historical rows were preserved");
        try {
            anchor.setMetadata(contextMetadata(objectMapper.writeValueAsString(metadata)));
        } catch (Exception e) {
            throw ApiException.bad("Unable to create audit checkpoint metadata");
        }
        anchor.setIpAddress(currentIp());
        anchor.setUserAgent(currentUserAgent());
        AuditEventEntity saved = writer.appendAnchor(anchor);
        return Map.of("anchorSeq", saved.getSeq(), "historyRows", history.size(), "historyDigest", digest,
                "reason", normalizedReason);
    }

    static String historyDigest(List<AuditEventEntity> events) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (AuditEventEntity event : events) {
                String canonicalHash = AuditHash.compute(event.getPrevHash(), event);
                String line = String.valueOf(event.getId()) + '|' + String.valueOf(event.getSeq()) + '|'
                        + String.valueOf(event.getHash()) + '|' + canonicalHash + '\n';
                digest.update(line.getBytes(StandardCharsets.UTF_8));
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to digest audit history", e);
        }
    }

    private String metadataText(String metadata, String field) {
        if (metadata == null || metadata.isBlank()) return null;
        try {
            String value = objectMapper.readTree(metadata).path(field).asText(null);
            return value == null || value.isBlank() ? null : value;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Map<String, Object> segment(Long from, Long to, boolean valid) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("fromSeq", from);
        s.put("toSeq", to);
        s.put("valid", valid);
        return s;
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

    private static final Pattern DETAIL_KEY = Pattern.compile(
            "(?i)(?:^|\\s)(id|job|savedJob|run|request|policy|source|target|entity|template|mapping|blueprint|profile|flow|package|snapshot|reservation|connection|delivery|report|instance|grant|name)=([^\\s,]+)");

    private static LegacyResource inferResource(String action, String detail) {
        String normalized = action == null ? "event" : action.toLowerCase(Locale.ROOT);
        String type = normalized
                .replaceAll("_(created|updated|deleted|saved|started|queued|completed|failed|canceled|cancelled|approved|rejected|requested|released|retried|exported|provisioned|launched|run|create|update|delete|save)$", "")
                .replace('_', '-');
        Matcher matcher = DETAIL_KEY.matcher(detail == null ? "" : detail);
        String id = null;
        String name = null;
        while (matcher.find()) {
            String key = matcher.group(1).toLowerCase(Locale.ROOT);
            String value = matcher.group(2);
            if (id == null && ("id".equals(key) || key.matches("job|savedjob|run|request|policy|entity|template|mapping|blueprint|flow|package|snapshot|reservation|delivery|report|instance|grant"))) {
                id = value;
            }
            if (name == null && key.matches("source|target|profile|connection|name")) name = value;
        }
        if (name == null && detail != null && !detail.isBlank()) name = truncate(detail.trim(), 200);
        return new LegacyResource(truncate(type, 100), truncate(id, 200), name);
    }

    private String contextMetadata(String suppliedJson) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (suppliedJson != null && !suppliedJson.isBlank()) {
            try {
                metadata.putAll(objectMapper.readValue(suppliedJson, new TypeReference<Map<String, Object>>() {}));
            } catch (Exception ignored) {
                metadata.put("context", truncate(suppliedJson, 1000));
            }
        }
        HttpServletRequest request = currentRequest();
        if (request != null) {
            Object correlationId = request.getAttribute(AccessControlFilter.CORRELATION_ID_ATTRIBUTE);
            if (correlationId != null) metadata.put("correlationId", String.valueOf(correlationId));
            metadata.put("method", request.getMethod());
            metadata.put("path", truncate(request.getRequestURI(), 500));
        }
        if (metadata.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstText(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    private record LegacyResource(String type, String id, String name) {}

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
