package io.forgetdm.discovery;

import io.forgetdm.policy.MaskingPolicyEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/discovery")
public class DiscoveryController {
    private final DiscoveryService svc;
    public DiscoveryController(DiscoveryService svc) { this.svc = svc; }

    @PostMapping("/scan/{dataSourceId}")
    public List<ClassificationEntity> scan(@PathVariable Long dataSourceId,
                                           @RequestParam(required = false) String schema,
                                           @RequestBody(required = false) Map<String, Object> body) {
        java.util.Set<String> types = new java.util.HashSet<>();
        if (body != null && body.get("piiTypes") instanceof List<?> list)
            for (Object o : list) if (o != null) types.add(String.valueOf(o));
        return svc.scan(dataSourceId, schema, types.isEmpty() ? null : types);
    }

    /** Built-in + custom PII types the user can target on the Scan Source page. */
    @GetMapping("/pii-types")
    public List<String> piiTypes() {
        return svc.piiTypeCatalog();
    }

    @GetMapping("/results/{dataSourceId}")
    public List<ClassificationEntity> results(@PathVariable Long dataSourceId,
                                              @RequestParam(required = false) String schema,
                                              @RequestParam(required = false) String tableFilter) {
        return svc.results(dataSourceId, schema, tableFilter);
    }

    @PostMapping("/approve-all/{dataSourceId}")
    public Map<String, Object> approveAll(@PathVariable Long dataSourceId,
                                          @RequestParam(required = false) String schema,
                                          @RequestParam(required = false) String tableFilter) {
        return Map.of("count", svc.approveAll(dataSourceId, schema, tableFilter));
    }

    @PostMapping("/reject-all/{dataSourceId}")
    public Map<String, Object> rejectAll(@PathVariable Long dataSourceId,
                                         @RequestParam(required = false) String schema,
                                         @RequestParam(required = false) String tableFilter) {
        return Map.of("count", svc.rejectAll(dataSourceId, schema, tableFilter));
    }

    @GetMapping("/table-columns/{dataSourceId}")
    public List<Map<String, Object>> tableColumns(@PathVariable Long dataSourceId,
                                                  @RequestParam(required = false) String schema,
                                                  @RequestParam String table) {
        return svc.tableColumns(dataSourceId, schema, table);
    }

    @PostMapping("/manual/{dataSourceId}")
    public ClassificationEntity manual(@PathVariable Long dataSourceId,
                                       @RequestBody Map<String, String> body) {
        return svc.markManual(dataSourceId, body);
    }

    @GetMapping("/graph/{dataSourceId}")
    public Map<String, Object> graph(@PathVariable Long dataSourceId,
                                     @RequestParam(required = false) String schema) {
        return svc.graph(dataSourceId, schema);
    }

    @PatchMapping("/classifications/{id}")
    public ClassificationEntity updateClassification(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return svc.updateClassification(id,
                body.get("status") == null ? null : body.get("status").toUpperCase(),
                body.get("suggestedFunction"),
                body.get("suggestedParam1"),
                body.get("suggestedParam2"));
    }

    @PostMapping("/generate-policy/{dataSourceId}")
    public MaskingPolicyEntity generatePolicy(@PathVariable Long dataSourceId,
                                              @RequestParam(required = false) String schema,
                                              @RequestBody Map<String, String> body) {
        return svc.generatePolicy(dataSourceId, schema, body.getOrDefault("name", "policy-ds-" + dataSourceId));
    }
}
