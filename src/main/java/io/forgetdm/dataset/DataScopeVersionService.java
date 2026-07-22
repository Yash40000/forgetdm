package io.forgetdm.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.policy.MaskingPolicyRepository;
import io.forgetdm.policy.MaskingRuleEntity;
import io.forgetdm.policy.MaskingRuleRepository;
import io.forgetdm.security.AccessContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * DataScope blueprint version history. Captures an immutable JSON snapshot of a blueprint (definition +
 * profiles + overrides + custom PKs + custom relationships + traversal rules + the RESOLVED masking
 * rules of every referenced policy) so changes can be reviewed, compared, and rolled back later.
 *
 * The policy rules are frozen into the snapshot because the policy objects themselves are shared and
 * mutable — without freezing them, history could not answer "exactly which masking applied at v3".
 * Restore intentionally does NOT write policies back (they are shared across blueprints); the frozen
 * copy exists for audit and diff.
 */
@Service
public class DataScopeVersionService {

    private final DataSetVersionRepository versions;
    private final DataSetService datasets;
    private final MaskingPolicyRepository policies;
    private final MaskingRuleRepository policyRules;
    private final AuditService audit;
    // findAndRegisterModules: the snapshot contains Instant/LocalDateTime fields (definition.updatedAt,
    // userRels.createdAt) which a bare ObjectMapper cannot (de)serialize. Unknown properties are
    // tolerated so snapshots taken before an entity gained/lost a field remain restorable.
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public DataScopeVersionService(DataSetVersionRepository versions, DataSetService datasets,
                                   MaskingPolicyRepository policies, MaskingRuleRepository policyRules,
                                   AuditService audit) {
        this.versions = versions;
        this.datasets = datasets;
        this.policies = policies;
        this.policyRules = policyRules;
        this.audit = audit;
    }

    /** Assemble the full current blueprint as a snapshot object. */
    public Map<String, Object> snapshot(Long datasetId) {
        DataSetDefinitionEntity def = datasets.assertAuthorizedReferences(datasetId, null);
        List<TableProfileEntity> profiles = datasets.listProfiles(datasetId);
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("definition", def);
        snap.put("profiles", profiles);
        snap.put("overrides", datasets.listOverrides(datasetId));
        snap.put("customPks", datasets.listCustomPks(datasetId));
        snap.put("userRels", datasets.listUserRels(datasetId));
        snap.put("traversalRules", datasets.listTraversalRules(datasetId));
        snap.put("policies", frozenPolicies(def, profiles));
        return snap;
    }

    /** Freeze the resolved rules of every policy this blueprint references (blueprint default + per-table). */
    private List<Map<String, Object>> frozenPolicies(DataSetDefinitionEntity def, List<TableProfileEntity> profiles) {
        Set<Long> ids = new LinkedHashSet<>();
        if (def.getPolicyId() != null) ids.add(def.getPolicyId());
        for (TableProfileEntity p : profiles) if (p.getPolicyId() != null) ids.add(p.getPolicyId());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Long id : ids) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", id);
            entry.put("name", policies.findById(id).map(pol -> pol.getName()).orElse("policy #" + id + " (deleted)"));
            List<Map<String, Object>> rules = new ArrayList<>();
            for (MaskingRuleEntity r : policyRules.findByPolicyId(id)) {
                Map<String, Object> rule = new LinkedHashMap<>();
                rule.put("table", r.getTableName());
                rule.put("column", r.getColumnName());
                rule.put("schema", r.getSchemaName());
                rule.put("function", r.getFunction());
                rule.put("param1", r.getParam1());
                rule.put("param2", r.getParam2());
                rules.add(rule);
            }
            entry.put("rules", rules);
            out.add(entry);
        }
        return out;
    }

    public Map<String, Object> saveVersion(Long datasetId, String note) {
        DataSetDefinitionEntity def = datasets.get(datasetId);
        int next = versions.findTopByDatasetIdOrderByVersionNoDesc(datasetId).map(v -> v.getVersionNo() + 1).orElse(1);
        DataSetVersionEntity v = new DataSetVersionEntity();
        v.setDatasetId(datasetId);
        v.setVersionNo(next);
        v.setNote(note == null || note.isBlank() ? null : note.trim());
        v.setCreatedBy(AccessContext.current().map(p -> p.username()).orElse("system"));
        try {
            v.setSnapshotJson(json.writeValueAsString(snapshot(datasetId)));
        } catch (Exception e) {
            throw ApiException.bad("Could not snapshot blueprint: " + e.getMessage());
        }
        DataSetVersionEntity saved = versions.save(v);
        audit.log(v.getCreatedBy(), "DATASCOPE_VERSION_SAVED", def.getName() + " id=" + datasetId + " v" + next);
        return summary(saved);
    }

    public List<Map<String, Object>> listVersions(Long datasetId) {
        datasets.get(datasetId);   // validate
        List<Map<String, Object>> out = new ArrayList<>();
        for (DataSetVersionEntity v : versions.findByDatasetIdOrderByVersionNoDesc(datasetId)) out.add(summary(v));
        return out;
    }

    /** A single version with its parsed snapshot (for view / compare). */
    public Map<String, Object> getVersion(Long versionId) {
        DataSetVersionEntity v = find(versionId);
        Map<String, Object> out = summary(v);
        try {
            out.put("snapshot", json.readValue(v.getSnapshotJson(), Map.class));
        } catch (Exception e) {
            out.put("snapshot", Map.of("error", "Unable to parse snapshot"));
        }
        return out;
    }

    // ─── Compare ─────────────────────────────────────────────────────────────

    /**
     * Structured diff of a version against the current blueprint state (againstVersionId == null)
     * or against another saved version. Every section reports added / removed / changed items keyed
     * by their natural identity (table, table.column, parent→child, policy rule), never by row id.
     */
    public Map<String, Object> compare(Long versionId, Long againstVersionId) {
        DataSetVersionEntity base = find(versionId);
        JsonNode from = parseSnapshot(base.getSnapshotJson());
        JsonNode to;
        String toLabel;
        if (againstVersionId == null) {
            to = json.valueToTree(snapshot(base.getDatasetId()));
            toLabel = "current";
        } else {
            DataSetVersionEntity other = find(againstVersionId);
            if (!other.getDatasetId().equals(base.getDatasetId()))
                throw ApiException.bad("Versions belong to different blueprints");
            to = parseSnapshot(other.getSnapshotJson());
            toLabel = "v" + other.getVersionNo();
        }

        Set<String> baseIgnore = Set.of("id", "datasetId", "createdAt", "updatedAt");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", "v" + base.getVersionNo());
        out.put("to", toLabel);
        out.put("definition", fieldDiffs(from.path("definition"), to.path("definition"), baseIgnore));
        out.put("profiles", diffKeyed(from.path("profiles"), to.path("profiles"),
                n -> lower(n.path("tableName").asText()), baseIgnore));
        out.put("overrides", diffKeyed(from.path("overrides"), to.path("overrides"),
                n -> lower(n.path("tableName").asText() + "." + n.path("columnName").asText()), baseIgnore));
        out.put("customPks", diffKeyed(from.path("customPks"), to.path("customPks"),
                n -> lower(n.path("tableName").asText()), baseIgnore));
        out.put("userRels", diffKeyed(from.path("userRels"), to.path("userRels"),
                n -> lower(n.path("parentTable").asText() + " -> " + n.path("childTable").asText()
                        + rel(n.path("relName").asText(""))), baseIgnore));
        out.put("traversalRules", diffKeyed(from.path("traversalRules"), to.path("traversalRules"),
                n -> lower(n.path("parentTable").asText() + " -> " + n.path("childTable").asText()
                        + " [" + n.path("relSource").asText("DB") + "]"),
                Set.of("id", "datasetId", "createdAt", "updatedAt", "relRefId")));
        out.put("policyRules", diffKeyed(flattenPolicyRules(from.path("policies")), flattenPolicyRules(to.path("policies")),
                n -> lower(n.path("policy").asText() + ": " + n.path("table").asText() + "." + n.path("column").asText()),
                Set.of("id")));
        return out;
    }

    private static String rel(String name) { return name == null || name.isBlank() ? "" : " (" + name + ")"; }

    /** Old snapshots (before policy freezing) have no "policies" section — flatten to an empty array then. */
    private JsonNode flattenPolicyRules(JsonNode policiesNode) {
        List<Map<String, Object>> flat = new ArrayList<>();
        if (policiesNode != null && policiesNode.isArray()) {
            for (JsonNode pol : policiesNode) {
                for (JsonNode r : pol.path("rules")) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("policy", pol.path("name").asText());
                    m.put("table", r.path("table").asText());
                    m.put("column", r.path("column").asText());
                    m.put("function", r.path("function").asText(null));
                    m.put("param1", r.path("param1").asText(null));
                    m.put("param2", r.path("param2").asText(null));
                    flat.add(m);
                }
            }
        }
        return json.valueToTree(flat);
    }

    private static Map<String, Object> diffKeyed(JsonNode fromArr, JsonNode toArr,
                                                 Function<JsonNode, String> keyFn, Set<String> ignore) {
        Map<String, JsonNode> a = index(fromArr, keyFn), b = index(toArr, keyFn);
        List<String> added = new ArrayList<>(), removed = new ArrayList<>();
        List<Map<String, Object>> changed = new ArrayList<>();
        for (String k : b.keySet()) if (!a.containsKey(k)) added.add(k);
        for (String k : a.keySet()) {
            if (!b.containsKey(k)) { removed.add(k); continue; }
            List<Map<String, Object>> fields = fieldDiffs(a.get(k), b.get(k), ignore);
            if (!fields.isEmpty()) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("key", k);
                c.put("fields", fields);
                changed.add(c);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("added", added);
        out.put("removed", removed);
        out.put("changed", changed);
        return out;
    }

    private static Map<String, JsonNode> index(JsonNode arr, Function<JsonNode, String> keyFn) {
        Map<String, JsonNode> out = new TreeMap<>();
        if (arr != null && arr.isArray()) for (JsonNode n : arr) out.put(keyFn.apply(n), n);
        return out;
    }

    private static List<Map<String, Object>> fieldDiffs(JsonNode a, JsonNode b, Set<String> ignore) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> names = new java.util.TreeSet<>();
        if (a != null) a.fieldNames().forEachRemaining(names::add);
        if (b != null) b.fieldNames().forEachRemaining(names::add);
        for (String name : names) {
            if (ignore.contains(name)) continue;
            String va = scalar(a == null ? null : a.get(name));
            String vb = scalar(b == null ? null : b.get(name));
            if (!java.util.Objects.equals(va, vb)) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("field", name);
                d.put("from", va);
                d.put("to", vb);
                out.add(d);
            }
        }
        return out;
    }

    private static String scalar(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return null;
        return n.isTextual() ? n.asText() : n.toString();
    }

    private static String lower(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }

    // ─── Restore ─────────────────────────────────────────────────────────────

    /**
     * Roll the blueprint back to a saved version. The current state is auto-saved as a new version
     * first, so restore itself is always reversible. Policies are NOT written back (shared objects);
     * traversal rules referencing USER relationships are remapped to the recreated relationship ids.
     */
    @Transactional
    public Map<String, Object> restore(Long versionId) {
        DataSetVersionEntity v = find(versionId);
        Long datasetId = v.getDatasetId();
        JsonNode snap = parseSnapshot(v.getSnapshotJson());

        Map<String, Object> autoSaved = saveVersion(datasetId, "auto-save before restoring v" + v.getVersionNo());

        try {
            datasets.restoreDefinition(datasetId,
                    json.treeToValue(snap.path("definition"), DataSetDefinitionEntity.class));
            datasets.saveProfiles(datasetId, new ArrayList<>(List.of(
                    json.treeToValue(snap.path("profiles"), TableProfileEntity[].class))));
            datasets.saveOverrides(datasetId, new ArrayList<>(List.of(
                    json.treeToValue(snap.path("overrides"), ColumnOverrideEntity[].class))));
            datasets.saveCustomPks(datasetId, new ArrayList<>(List.of(
                    json.treeToValue(snap.path("customPks"), UserDefinedPkEntity[].class))));
            Map<Long, Long> relIdMap = datasets.replaceUserRels(datasetId, new ArrayList<>(List.of(
                    json.treeToValue(snap.path("userRels"), UserDefinedRelationshipEntity[].class))));
            List<RelationshipTraversalRuleEntity> rules = new ArrayList<>(List.of(
                    json.treeToValue(snap.path("traversalRules"), RelationshipTraversalRuleEntity[].class)));
            for (RelationshipTraversalRuleEntity r : rules) {
                if ("USER".equalsIgnoreCase(r.getRelSource()) && r.getRelRefId() != null) {
                    r.setRelRefId(relIdMap.get(r.getRelRefId()));   // null when unmappable → generic edge fallback
                }
            }
            datasets.saveTraversalRules(datasetId, rules);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.bad("Restore of v" + v.getVersionNo() + " failed: " + e.getMessage());
        }

        String user = AccessContext.current().map(p -> p.username()).orElse("system");
        audit.log(user, "DATASCOPE_VERSION_RESTORED", "datasetId=" + datasetId + " restored v" + v.getVersionNo());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("restored", "v" + v.getVersionNo());
        out.put("autoSavedVersion", autoSaved.get("versionNo"));
        return out;
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private DataSetVersionEntity find(Long versionId) {
        DataSetVersionEntity version = versions.findById(versionId)
                .orElseThrow(() -> ApiException.notFound("Version " + versionId + " not found"));
        datasets.get(version.getDatasetId());
        return version;
    }

    private JsonNode parseSnapshot(String snapshotJson) {
        try {
            return json.readTree(snapshotJson);
        } catch (Exception e) {
            throw ApiException.bad("Unable to parse version snapshot: " + e.getMessage());
        }
    }

    private Map<String, Object> summary(DataSetVersionEntity v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.getId());
        m.put("datasetId", v.getDatasetId());
        m.put("versionNo", v.getVersionNo());
        m.put("note", v.getNote());
        m.put("createdBy", v.getCreatedBy());
        m.put("createdAt", v.getCreatedAt());
        return m;
    }
}
