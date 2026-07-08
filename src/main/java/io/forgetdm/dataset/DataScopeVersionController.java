package io.forgetdm.dataset;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** DataScope blueprint version history. Under /api/datasets so it inherits datascope.read / datascope.manage. */
@RestController
@RequestMapping("/api/datasets")
public class DataScopeVersionController {

    private final DataScopeVersionService versions;

    public DataScopeVersionController(DataScopeVersionService versions) {
        this.versions = versions;
    }

    @GetMapping("/{id}/versions")
    public List<Map<String, Object>> list(@PathVariable Long id) {
        return versions.listVersions(id);
    }

    @PostMapping("/{id}/versions")
    public Map<String, Object> save(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        String note = body == null ? null : (body.get("note") == null ? null : String.valueOf(body.get("note")));
        return versions.saveVersion(id, note);
    }

    @GetMapping("/versions/{versionId}")
    public Map<String, Object> get(@PathVariable Long versionId) {
        return versions.getVersion(versionId);
    }

    /** Structured diff of this version against current state (default) or another version (?against=). */
    @GetMapping("/versions/{versionId}/diff")
    public Map<String, Object> diff(@PathVariable Long versionId,
                                    @RequestParam(required = false) Long against) {
        return versions.compare(versionId, against);
    }

    /** Roll the blueprint back to this version. Current state is auto-saved as a new version first. */
    @PostMapping("/versions/{versionId}/restore")
    public Map<String, Object> restore(@PathVariable Long versionId) {
        return versions.restore(versionId);
    }
}
