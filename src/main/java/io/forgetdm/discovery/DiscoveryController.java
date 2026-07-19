package io.forgetdm.discovery;

import io.forgetdm.policy.MaskingPolicyEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/discovery")
public class DiscoveryController {
    private final DiscoveryService svc;
    private final DiscoveryJobService jobs;
    public DiscoveryController(DiscoveryService svc, DiscoveryJobService jobs) { this.svc = svc; this.jobs = jobs; }

    @PostMapping("/scan/{dataSourceId}")
    public List<ClassificationEntity> scan(@PathVariable Long dataSourceId,
                                           @RequestParam(required = false) String schema,
                                           @RequestBody(required = false) Map<String, Object> body) {
        Set<String> types = stringSet(body, "piiTypes");
        Set<String> tables = stringSet(body, "tableNames");
        return svc.scan(dataSourceId, schema, types.isEmpty() ? null : types,
                tables.isEmpty() ? null : tables, null);
    }

    @PostMapping("/scan-jobs/{dataSourceId}")
    public DiscoveryJobService.JobSnapshot startScanJob(@PathVariable Long dataSourceId,
                                                        @RequestParam(required = false) String schema,
                                                        @RequestBody(required = false) Map<String, Object> body) {
        Set<String> types = stringSet(body, "piiTypes");
        Set<String> tables = stringSet(body, "tableNames");
        return jobs.start(dataSourceId, schema, types, tables);
    }

    @GetMapping("/scan-jobs")
    public List<DiscoveryJobService.JobSnapshot> scanJobs(@RequestParam(required = false) Long dataSourceId,
                                                          @RequestParam(required = false) String schema) {
        return jobs.list(dataSourceId, schema);
    }

    @GetMapping("/scan-jobs/{jobId}")
    public DiscoveryJobService.JobSnapshot scanJob(@PathVariable String jobId) {
        return jobs.get(jobId);
    }

    @PostMapping("/scan-jobs/{jobId}/cancel")
    public DiscoveryJobService.JobSnapshot cancelScanJob(@PathVariable String jobId) {
        return jobs.cancel(jobId);
    }

    /** Built-in + custom PII types the user can target on the Scan Source page. */
    @GetMapping("/pii-types")
    public List<String> piiTypes() {
        return svc.piiTypeCatalog();
    }

    @GetMapping("/results/{dataSourceId}")
    public List<ClassificationEntity> results(@PathVariable Long dataSourceId,
                                              @RequestParam(required = false) String schema,
                                              @RequestParam(required = false) String tableFilter,
                                              @RequestParam(required = false) List<String> piiTypes) {
        return svc.results(dataSourceId, schema, tableFilter, typeSet(piiTypes));
    }

    @PostMapping("/approve-all/{dataSourceId}")
    public Map<String, Object> approveAll(@PathVariable Long dataSourceId,
                                          @RequestParam(required = false) String schema,
                                          @RequestParam(required = false) String tableFilter,
                                          @RequestParam(required = false) List<String> piiTypes) {
        return Map.of("count", svc.approveAll(dataSourceId, schema, tableFilter, typeSet(piiTypes)));
    }

    @PostMapping("/reject-all/{dataSourceId}")
    public Map<String, Object> rejectAll(@PathVariable Long dataSourceId,
                                         @RequestParam(required = false) String schema,
                                         @RequestParam(required = false) String tableFilter,
                                         @RequestParam(required = false) List<String> piiTypes) {
        return Map.of("count", svc.rejectAll(dataSourceId, schema, tableFilter, typeSet(piiTypes)));
    }

    @GetMapping("/table-columns/{dataSourceId}")
    public List<Map<String, Object>> tableColumns(@PathVariable Long dataSourceId,
                                                  @RequestParam(required = false) String schema,
                                                  @RequestParam String table,
                                                  @RequestParam(required = false) List<String> piiTypes) {
        return svc.tableColumns(dataSourceId, schema, table, typeSet(piiTypes));
    }

    @PostMapping("/manual/{dataSourceId}")
    public ClassificationEntity manual(@PathVariable Long dataSourceId,
                                       @RequestBody Map<String, String> body) {
        return svc.markManual(dataSourceId, body);
    }

    @GetMapping("/graph/{dataSourceId}")
    public Map<String, Object> graph(@PathVariable Long dataSourceId,
                                     @RequestParam(required = false) String schema,
                                     @RequestParam(required = false) List<String> piiTypes) {
        return svc.graph(dataSourceId, schema, typeSet(piiTypes));
    }

    @PatchMapping("/classifications/{id}")
    public ClassificationEntity updateClassification(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return svc.updateClassification(id,
                body.get("status") == null ? null : body.get("status").toUpperCase(),
                body.get("suggestedFunction"),
                body.get("suggestedParam1"),
                body.get("suggestedParam2"));
    }

    public record BulkClassificationRequest(List<Long> ids, String status) {}

    @PostMapping("/classifications/bulk")
    public Map<String, Object> bulkUpdateClassifications(@RequestBody BulkClassificationRequest body) {
        return Map.of("count", svc.bulkUpdateClassifications(body.ids(), body.status()));
    }

    @PostMapping("/generate-policy/{dataSourceId}")
    public MaskingPolicyEntity generatePolicy(@PathVariable Long dataSourceId,
                                              @RequestParam(required = false) String schema,
                                              @RequestBody Map<String, String> body) {
        return svc.generatePolicy(dataSourceId, schema, body.getOrDefault("name", "policy-ds-" + dataSourceId));
    }

    private static Set<String> typeSet(List<String> raw) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (raw == null) return out;
        for (String item : raw) {
            if (item == null) continue;
            for (String part : item.split(",")) if (!part.isBlank()) out.add(part.trim());
        }
        return out;
    }

    private static Set<String> stringSet(Map<String, Object> body, String key) {
        Set<String> out = new java.util.LinkedHashSet<>();
        if (body != null && body.get(key) instanceof List<?> list) {
            for (Object value : list) {
                if (value != null && !String.valueOf(value).isBlank()) out.add(String.valueOf(value).trim());
            }
        }
        return out;
    }
}
