package io.forgetdm.cdc;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * True (log-based) Change Data Capture. Routes are governed by the virtualization
 * permission family in {@code AccessControlFilter} (read = virtualization.read,
 * writes = virtualization.manage).
 */
@RestController
@RequestMapping("/api/cdc")
public class CdcController {

    private final CdcService svc;
    private final CdcTimeFlowService timeFlow;

    public CdcController(CdcService svc, CdcTimeFlowService timeFlow) {
        this.svc = svc;
        this.timeFlow = timeFlow;
    }

    /** Is the source configured for log-based CDC (wal_level, replication privilege, slots)? */
    @GetMapping("/datasources/{id}/preflight")
    public CdcService.Preflight preflight(@PathVariable Long id) {
        return svc.preflight(id);
    }

    @GetMapping("/datasources/{id}/status")
    public CdcService.Status status(@PathVariable Long id) {
        return svc.status(id);
    }

    @GetMapping("/datasources/{id}/changes")
    public List<CdcChangeEntity> changes(@PathVariable Long id,
                                         @RequestParam(required = false, defaultValue = "100") int limit) {
        return svc.recentChanges(id, limit);
    }

    /** Create the replication slot and begin capturing. Optional schema/table scoping in the body. */
    @PostMapping("/datasources/{id}/enable")
    @SuppressWarnings("unchecked")
    public CdcService.Status enable(@PathVariable Long id,
                                    @RequestBody(required = false) Map<String, Object> body) {
        String schema = body == null ? null : (String) body.get("schema");
        List<String> tables = body == null ? null : (List<String>) body.get("tables");
        return svc.enable(id, schema, tables);
    }

    @PostMapping("/datasources/{id}/disable")
    public CdcService.Status disable(@PathVariable Long id) {
        return svc.disable(id);
    }

    /** Read pending changes from the transaction log now and advance the LSN checkpoint. */
    @PostMapping("/datasources/{id}/poll")
    public Map<String, Object> poll(@PathVariable Long id) {
        return svc.poll(id).asMap();
    }

    /**
     * Incremental refresh / point-in-time replay: apply buffered changes to a target as netted
     * UPSERT/DELETE. Body: { "targetDataSourceId": <id>, "purge": true|false,
     * "throughChangeId": <id>, "throughTimestamp": <ISO-8601> }. When a bound is given, the target
     * is brought to the source's state as of that point (a virtual DB "as of time T").
     */
    @PostMapping("/datasources/{id}/apply")
    public Map<String, Object> apply(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        if (body == null || body.get("targetDataSourceId") == null) {
            throw io.forgetdm.common.ApiException.bad("targetDataSourceId is required.");
        }
        Long target = Long.valueOf(String.valueOf(body.get("targetDataSourceId")));
        Long throughChangeId = body.get("throughChangeId") == null ? null
                : Long.valueOf(String.valueOf(body.get("throughChangeId")));
        java.time.Instant throughTs = body.get("throughTimestamp") == null ? null
                : java.time.Instant.parse(String.valueOf(body.get("throughTimestamp")));
        // Full refresh purges by default; a bounded point-in-time replay does not.
        boolean bounded = throughChangeId != null || throughTs != null;
        boolean purge = Boolean.parseBoolean(String.valueOf(body.getOrDefault("purge", !bounded)));
        return svc.applyIncremental(id, target, purge, throughChangeId, throughTs);
    }

    /** CDC baselines that can safely seed point-in-time TimeFlow provisioning. */
    @GetMapping("/datasources/{id}/timeflow/anchors")
    public List<io.forgetdm.virtualization.VirtualSnapshotEntity> anchors(@PathVariable Long id) {
        return timeFlow.anchors(id);
    }

    /** Async: drain CDC and capture a transactionally consistent baseline plus its checkpoint. */
    @PostMapping("/datasources/{id}/timeflow/anchors")
    public Map<String, Object> createAnchor(@PathVariable Long id,
                                             @RequestBody(required = false) Map<String, Object> body) {
        return timeFlow.startAnchor(id, str(body, "schemaName"), str(body, "name"));
    }

    /**
     * Async: replay changes after an anchor through a change id or CDC-capture timestamp,
     * persist an immutable TimeFlow snapshot, and provision a writable VDB from it.
     */
    @PostMapping("/datasources/{id}/timeflow/vdbs")
    public Map<String, Object> provisionPointInTime(@PathVariable Long id,
                                                     @RequestBody Map<String, Object> body) {
        if (body == null || body.get("anchorSnapshotId") == null) {
            throw io.forgetdm.common.ApiException.bad("anchorSnapshotId is required.");
        }
        Long anchor = Long.valueOf(String.valueOf(body.get("anchorSnapshotId")));
        Long target = longOrNull(body.get("targetDataSourceId"));
        Long throughId = longOrNull(body.get("throughChangeId"));
        java.time.Instant throughTs = body.get("throughTimestamp") == null ? null
                : parseInstant(body.get("throughTimestamp"));
        return timeFlow.startProvision(id, anchor, str(body, "name"), target, throughId, throughTs);
    }

    private static String str(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) return null;
        String value = String.valueOf(body.get(key)).trim();
        return value.isBlank() ? null : value;
    }

    private static Long longOrNull(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        try { return Long.valueOf(String.valueOf(value)); }
        catch (NumberFormatException e) { throw io.forgetdm.common.ApiException.bad("Expected a whole-number id."); }
    }

    private static java.time.Instant parseInstant(Object value) {
        try { return java.time.Instant.parse(String.valueOf(value)); }
        catch (Exception e) { throw io.forgetdm.common.ApiException.bad("throughTimestamp must be ISO-8601 UTC time."); }
    }
}
