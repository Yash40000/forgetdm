package io.forgetdm.dataset;

import io.forgetdm.subset.SubsetService;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/datasets")
public class DataSetController {

    private final DataSetService svc;
    public DataSetController(DataSetService svc) { this.svc = svc; }

    // ── DataScope definitions ────────────────────────────────────────────────

    @GetMapping
    public List<DataSetDefinitionEntity> list() { return svc.list(); }

    @GetMapping("/{id}")
    public DataSetDefinitionEntity get(@PathVariable Long id) { return svc.get(id); }

    @PostMapping
    public DataSetDefinitionEntity create(@RequestBody DataSetDefinitionEntity body) {
        return svc.create(body);
    }

    @PutMapping("/{id}")
    public DataSetDefinitionEntity update(@PathVariable Long id, @RequestBody DataSetDefinitionEntity body) {
        return svc.update(id, body);
    }

    @PutMapping("/{id}/policy")
    public DataSetDefinitionEntity updatePolicy(@PathVariable Long id,
                                                @RequestBody(required = false) Map<String, Object> body) {
        Object raw = body == null ? null : body.get("policyId");
        Long policyId = raw == null || String.valueOf(raw).isBlank() ? null : Long.valueOf(String.valueOf(raw));
        return svc.updatePolicy(id, policyId);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable Long id) { svc.delete(id); }

    // ── Table Profiles ───────────────────────────────────────────────────────

    @GetMapping("/{id}/profiles")
    public List<TableProfileEntity> listProfiles(@PathVariable Long id) {
        return svc.listProfiles(id);
    }

    @PutMapping("/{id}/profiles")
    public List<TableProfileEntity> saveProfiles(@PathVariable Long id,
                                                 @RequestBody List<TableProfileEntity> body) {
        return svc.saveProfiles(id, body);
    }

    @PostMapping("/{id}/profiles")
    public TableProfileEntity saveProfile(@PathVariable Long id, @RequestBody TableProfileEntity body) {
        return svc.saveProfile(id, body);
    }

    @DeleteMapping("/{id}/profiles/{tableName}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProfile(@PathVariable Long id, @PathVariable String tableName) {
        svc.deleteProfile(id, tableName);
    }

    // ── Column Overrides ─────────────────────────────────────────────────────

    @GetMapping("/{id}/overrides")
    public List<ColumnOverrideEntity> listOverrides(@PathVariable Long id) {
        return svc.listOverrides(id);
    }

    @PutMapping("/{id}/overrides")
    public List<ColumnOverrideEntity> saveOverrides(@PathVariable Long id,
                                                    @RequestBody List<ColumnOverrideEntity> body) {
        return svc.saveOverrides(id, body);
    }

    @PostMapping("/{id}/overrides")
    public ColumnOverrideEntity saveOverride(@PathVariable Long id, @RequestBody ColumnOverrideEntity body) {
        return svc.saveOverride(id, body);
    }

    @DeleteMapping("/overrides/{overrideId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteOverride(@PathVariable Long overrideId) { svc.deleteOverride(overrideId); }

    // ── User-defined PKs ─────────────────────────────────────────────────────

    @GetMapping("/{id}/custom-pks")
    public List<UserDefinedPkEntity> listCustomPks(@PathVariable Long id) {
        return svc.listCustomPks(id);
    }

    /** Bulk upsert: replaces all custom PKs for this dataset. */
    @PutMapping("/{id}/custom-pks")
    public List<UserDefinedPkEntity> saveCustomPks(@PathVariable Long id,
                                                   @RequestBody List<UserDefinedPkEntity> body) {
        return svc.saveCustomPks(id, body);
    }

    /** Single upsert (add or replace PK for one table). */
    @PostMapping("/{id}/custom-pks")
    public UserDefinedPkEntity saveCustomPk(@PathVariable Long id, @RequestBody UserDefinedPkEntity body) {
        return svc.saveCustomPk(id, body);
    }

    @DeleteMapping("/custom-pks/{pkId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCustomPk(@PathVariable Long pkId) { svc.deleteCustomPk(pkId); }

    // ── User-defined Relationships ───────────────────────────────────────────

    @GetMapping("/{id}/user-rels")
    public List<UserDefinedRelationshipEntity> listUserRels(@PathVariable Long id) {
        return svc.listUserRels(id);
    }

    @PostMapping("/{id}/user-rels")
    public UserDefinedRelationshipEntity createUserRel(@PathVariable Long id,
                                                       @RequestBody UserDefinedRelationshipEntity body) {
        return svc.createUserRel(id, body);
    }

    @PutMapping("/user-rels/{relId}")
    public UserDefinedRelationshipEntity updateUserRel(@PathVariable Long relId,
                                                       @RequestBody UserDefinedRelationshipEntity body) {
        return svc.updateUserRel(relId, body);
    }

    @DeleteMapping("/user-rels/{relId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUserRel(@PathVariable Long relId) { svc.deleteUserRel(relId); }

    // ── Relationship Traversal Rules ─────────────────────────────────────────

    @GetMapping("/{id}/traversal-rules")
    public List<RelationshipTraversalRuleEntity> listTraversalRules(@PathVariable Long id) {
        return svc.listTraversalRules(id);
    }

    /** Bulk replace: client sends the complete list of rules for this dataset. */
    @PutMapping("/{id}/traversal-rules")
    public List<RelationshipTraversalRuleEntity> saveTraversalRules(
            @PathVariable Long id, @RequestBody List<RelationshipTraversalRuleEntity> body) {
        return svc.saveTraversalRules(id, body);
    }

    // ── Relationship discovery (Ref Integrity + Traversal Map tabs) ──────────

    /**
     * Returns all FK relationships (DB-catalog + user-defined) for the included tables
     * of this DataScope, enriched with the currently configured traversal rule.
     * This drives both the Referential Integrity editor and the read-only Traversal Map.
     */
    @GetMapping("/{id}/relationships")
    public List<DataSetService.RelationshipInfo> getRelationships(@PathVariable Long id) {
        return svc.getRelationships(id);
    }

    // ── PII coverage & schema drift (pre-provision guardrails) ──────────────

    /**
     * Cross-references PII Discovery classifications with the masking that would apply during
     * provisioning. policyId (optional) = the ad-hoc default policy chosen on the Provision tab.
     */
    @GetMapping("/{id}/pii-coverage")
    public Map<String, Object> piiCoverage(@PathVariable Long id,
                                           @RequestParam(required = false) Long policyId) {
        return svc.piiCoverage(id, policyId);
    }

    /** Checks every table/column the blueprint references against live source metadata. */
    @GetMapping("/{id}/drift")
    public Map<String, Object> schemaDrift(@PathVariable Long id) {
        return svc.schemaDrift(id);
    }

    // ── Plan Preview ─────────────────────────────────────────────────────────

    @PostMapping("/{id}/preview")
    public SubsetService.SubsetPlan preview(@PathVariable Long id,
                                            @RequestBody(required = false) Map<String, Object> body) {
        int maxRows = body != null && body.containsKey("maxDriverRows")
                ? ((Number) body.get("maxDriverRows")).intValue() : 0;
        return svc.previewPlan(id, maxRows);
    }
}
