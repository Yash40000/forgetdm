package io.forgetdm.dataset;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.discovery.ClassificationEntity;
import io.forgetdm.discovery.ClassificationRepository;
import io.forgetdm.policy.MaskingRuleEntity;
import io.forgetdm.policy.MaskingRuleRepository;
import io.forgetdm.security.GovernedReferenceGuard;
import io.forgetdm.subset.SubsetService;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.*;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * DataScope management: CRUD for DataSetDefinitions, TableProfiles, ColumnOverrides,
 * UserDefinedPks, UserDefinedRelationships, and RelationshipTraversalRules.
 * Also exposes plan preview and the relationship discovery endpoint used by the
 * Referential Integrity and Traversal Map tabs.
 */
@Service
public class DataSetService {

    private static final int BLUEPRINT_NAME_MIN_LENGTH = 8;
    private static final int BLUEPRINT_NAME_MAX_LENGTH = 64;

    private final DataSetDefinitionRepository       defs;
    private final TableProfileRepository            profiles;
    private final ColumnOverrideRepository          overrides;
    private final UserDefinedPkRepository           userPks;
    private final UserDefinedRelationshipRepository userRels;
    private final RelationshipTraversalRuleRepository traversalRules;
    private final SubsetService      subsets;
    private final DataSourceService  dataSources;
    private final ConnectionFactory  connections;
    private final AuditService       audit;
    private final ClassificationRepository classifications;
    private final MaskingRuleRepository    maskingRules;
    private final io.forgetdm.security.OwnershipGuard ownership;
    private final GovernedReferenceGuard references;

    public DataSetService(DataSetDefinitionRepository defs,
                          TableProfileRepository profiles,
                          ColumnOverrideRepository overrides,
                          UserDefinedPkRepository userPks,
                          UserDefinedRelationshipRepository userRels,
                          RelationshipTraversalRuleRepository traversalRules,
                          SubsetService subsets,
                          DataSourceService dataSources,
                          ConnectionFactory connections,
                          AuditService audit,
                          ClassificationRepository classifications,
                          MaskingRuleRepository maskingRules,
                          io.forgetdm.security.OwnershipGuard ownership,
                          GovernedReferenceGuard references) {
        this.defs = defs; this.profiles = profiles; this.overrides = overrides;
        this.userPks = userPks; this.userRels = userRels; this.traversalRules = traversalRules;
        this.subsets = subsets; this.dataSources = dataSources;
        this.connections = connections; this.audit = audit;
        this.classifications = classifications; this.maskingRules = maskingRules;
        this.ownership = ownership;
        this.references = references;
    }

    // ─── DTO for relationship discovery ─────────────────────────────────────

    /**
     * A single FK relationship visible to the DataScope — either a catalog-level
     * constraint or a user-defined one. Used by the Referential Integrity tab
     * and the Traversal Map tab.
     */
    public record RelationshipInfo(
            String parentTable, List<String> parentColumns,
            String childTable,  List<String> childColumns,
            String source,      // "DB" | "USER"
            Long   relRefId,    // user_defined_relationships.id when source=USER
            String relName,
            // Currently configured traversal rule for this edge (null = not configured → falls back to global/per-table)
            String traverseDirection,
            Long   traversalRuleId,
            int    priority,
            String traversalNote
    ) {}

    // ─── Definition CRUD ────────────────────────────────────────────────────

    /** Tenant-scoped: only blueprints the caller owns, their group owns, or that are SHARED. */
    public List<DataSetDefinitionEntity> list() {
        List<DataSetDefinitionEntity> all = new java.util.ArrayList<>(defs.findAll().stream()
                .filter(d -> ownership.canSee(d.getOwnerUserId(), d.getOwnerGroupId(), d.getVisibility()))
                .toList());
        all.sort(Comparator.comparing(DataSetDefinitionEntity::getId).reversed());
        return all;
    }

    /** Object-level tenancy gate — every blueprint read/mutate/run path resolves through here. */
    public DataSetDefinitionEntity get(Long id) {
        DataSetDefinitionEntity def = defs.findById(id).orElseThrow(() ->
                ApiException.notFound("DataScope " + id + " not found"));
        ownership.assertCanSee("DataScope blueprint", id,
                def.getOwnerUserId(), def.getOwnerGroupId(), def.getVisibility());
        return def;
    }

    public DataSetDefinitionEntity create(DataSetDefinitionEntity req) {
        String name = validateBlueprintName(req.getName());
        if (req.getDataSourceId() == null)
            throw ApiException.bad("dataSourceId is required");
        assertDefinitionReferences(req);
        defs.findByName(name).ifPresent(d -> {
            throw ApiException.bad("DataScope '" + name + "' already exists");
        });
        req.setName(name);
        req.setUpdatedAt(Instant.now());
        req.setOwnerUserId(ownership.defaultOwnerUserId());
        req.setOwnerUsername(ownership.defaultOwnerUsername());
        req.setOwnerGroupId(ownership.defaultOwnerGroupId());
        if (req.getVisibility() == null || req.getVisibility().isBlank()) {
            req.setVisibility(ownership.defaultVisibility());
        }
        DataSetDefinitionEntity saved = defs.save(req);
        audit.record(currentActor(), "DATASET_CREATED", "DATASCOPE", "dataset",
                String.valueOf(saved.getId()), saved.getName(), "SUCCESS",
                "Created DataScope blueprint", null);
        return saved;
    }

    public DataSetDefinitionEntity update(Long id, DataSetDefinitionEntity req) {
        DataSetDefinitionEntity existing = get(id);
        if (req.getName() != null && !req.getName().isBlank()) {
            String name = validateBlueprintName(req.getName());
            defs.findByName(name).ifPresent(d -> {
                if (!d.getId().equals(id)) throw ApiException.bad("Name '" + name + "' already used");
            });
            existing.setName(name);
        }
        if (req.getDescription() != null) existing.setDescription(req.getDescription());
        if (req.getDataSourceId() != null) existing.setDataSourceId(req.getDataSourceId());
        if (req.getSchemaName() != null)  existing.setSchemaName(blankToNull(req.getSchemaName()));
        if (req.getTargetDataSourceId() != null) existing.setTargetDataSourceId(req.getTargetDataSourceId());
        if (req.getTargetSchemaName() != null) existing.setTargetSchemaName(blankToNull(req.getTargetSchemaName()));
        if (req.getPolicyId() != null) existing.setPolicyId(req.getPolicyId());
        if (req.getDriverTable() != null) existing.setDriverTable(req.getDriverTable());
        if (req.getDriverFilter() != null) existing.setDriverFilter(blankToNull(req.getDriverFilter()));
        existing.setGlobalQ1(req.isGlobalQ1());
        existing.setGlobalQ2(req.isGlobalQ2());
        assertDefinitionReferences(existing);
        existing.setUpdatedAt(Instant.now());
        DataSetDefinitionEntity saved = defs.save(existing);
        audit.record(currentActor(), "DATASET_UPDATED", "DATASCOPE", "dataset",
                String.valueOf(saved.getId()), saved.getName(), "SUCCESS",
                "Updated DataScope blueprint", null);
        return saved;
    }

    public DataSetDefinitionEntity updatePolicy(Long id, Long policyId) {
        DataSetDefinitionEntity existing = get(id);
        references.policy(policyId);
        existing.setPolicyId(policyId);
        existing.setUpdatedAt(Instant.now());
        DataSetDefinitionEntity saved = defs.save(existing);
        audit.record(currentActor(), "DATASET_POLICY_UPDATED", "DATASCOPE", "dataset",
                String.valueOf(saved.getId()), saved.getName(), "SUCCESS",
                "Updated default masking policy", "{\"policyId\":" + (policyId == null ? "null" : policyId) + "}");
        return saved;
    }

    /**
     * Restore all configuration fields verbatim from a version snapshot (unlike update(), which
     * merges non-null fields only). Name is identity and is intentionally NOT restored.
     */
    @Transactional
    public DataSetDefinitionEntity restoreDefinition(Long id, DataSetDefinitionEntity s) {
        DataSetDefinitionEntity existing = get(id);
        if (s.getDataSourceId() != null) existing.setDataSourceId(s.getDataSourceId());
        existing.setDescription(s.getDescription());
        existing.setSchemaName(s.getSchemaName());
        existing.setTargetDataSourceId(s.getTargetDataSourceId());
        existing.setTargetSchemaName(s.getTargetSchemaName());
        existing.setPolicyId(s.getPolicyId());
        existing.setDriverTable(s.getDriverTable());
        existing.setDriverFilter(s.getDriverFilter());
        existing.setGlobalQ1(s.isGlobalQ1());
        existing.setGlobalQ2(s.isGlobalQ2());
        assertDefinitionReferences(existing);
        existing.setUpdatedAt(Instant.now());
        return defs.save(existing);
    }

    /**
     * Bulk replace of user-defined relationships (used by version restore). Returns oldId → newId so
     * the caller can remap traversal rules that reference USER relationships by id.
     */
    @Transactional
    public Map<Long, Long> replaceUserRels(Long datasetId, List<UserDefinedRelationshipEntity> incoming) {
        get(datasetId);
        userRels.deleteByDatasetId(datasetId);
        userRels.flush();
        Map<Long, Long> idMap = new LinkedHashMap<>();
        for (UserDefinedRelationshipEntity r : incoming == null ? List.<UserDefinedRelationshipEntity>of() : incoming) {
            validateUserRel(r);
            // copy into a fresh entity: the entity has no setId(), and the incoming ids are from the snapshot
            UserDefinedRelationshipEntity fresh = new UserDefinedRelationshipEntity();
            fresh.setDatasetId(datasetId);
            fresh.setRelName(r.getRelName());
            fresh.setParentTable(r.getParentTable());
            fresh.setParentColumns(r.getParentColumns());
            fresh.setChildTable(r.getChildTable());
            fresh.setChildColumns(r.getChildColumns());
            fresh.setNote(r.getNote());
            UserDefinedRelationshipEntity saved = userRels.save(fresh);
            if (r.getId() != null) idMap.put(r.getId(), saved.getId());
        }
        return idMap;
    }

    @Transactional
    public void delete(Long id) {
        DataSetDefinitionEntity definition = get(id);
        profiles.deleteByDatasetId(id);
        overrides.deleteByDatasetId(id);
        userPks.deleteByDatasetId(id);
        userRels.deleteByDatasetId(id);
        traversalRules.deleteByDatasetId(id);
        defs.deleteById(id);
        audit.record(currentActor(), "DATASET_DELETED", "DATASCOPE", "dataset",
                String.valueOf(id), definition.getName(), "SUCCESS",
                "Deleted DataScope blueprint", null);
    }

    private String currentActor() {
        return ownership.caller().map(io.forgetdm.security.AccessPrincipal::username).orElse("system");
    }

    // ─── Table Profile CRUD ─────────────────────────────────────────────────

    public List<TableProfileEntity> listProfiles(Long datasetId) {
        get(datasetId);
        return profiles.findByDatasetId(datasetId);
    }

    @Transactional
    public List<TableProfileEntity> saveProfiles(Long datasetId, List<TableProfileEntity> incoming) {
        DataSetDefinitionEntity definition = get(datasetId);
        for (TableProfileEntity p : incoming) {
            validateProfile(p);
            assertProfileReferences(definition, p);
        }
        validatePhysicalSourceTables(definition, incoming);

        profiles.deleteByDatasetId(datasetId);
        profiles.flush();          // flush deletes before re-inserting to avoid unique-constraint violations
        for (TableProfileEntity p : incoming) {
            p.setDatasetId(datasetId);
            p.setId(null);         // force INSERT — deleted rows no longer exist
        }
        List<TableProfileEntity> saved = profiles.saveAll(incoming);
        definition.setUpdatedAt(Instant.now());
        audit.log("system", "DATASET_PROFILES_SAVED", "datasetId=" + datasetId + " count=" + saved.size());
        return saved;
    }

    @Transactional
    public TableProfileEntity saveProfile(Long datasetId, TableProfileEntity p) {
        DataSetDefinitionEntity definition = get(datasetId);
        validateProfile(p);
        assertProfileReferences(definition, p);
        validatePhysicalSourceTables(definition, List.of(p));
        p.setDatasetId(datasetId);
        profiles.findByDatasetIdAndTableName(datasetId, p.getTableName())
                .map(TableProfileEntity::getId).ifPresent(profiles::deleteById);
        profiles.flush();
        return profiles.save(p);
    }

    @Transactional
    public void deleteProfile(Long datasetId, String tableName) {
        get(datasetId);
        profiles.findByDatasetIdAndTableName(datasetId, tableName)
                .ifPresent(p -> profiles.deleteById(p.getId()));
    }

    // ─── Column Override CRUD ───────────────────────────────────────────────

    public List<ColumnOverrideEntity> listOverrides(Long datasetId) {
        get(datasetId);
        return overrides.findByDatasetId(datasetId);
    }

    @Transactional
    public List<ColumnOverrideEntity> saveOverrides(Long datasetId, List<ColumnOverrideEntity> incoming) {
        get(datasetId);
        overrides.deleteByDatasetId(datasetId);
        overrides.flush();
        for (ColumnOverrideEntity o : incoming) {
            validateOverride(o);
            o.setDatasetId(datasetId);
            o.setId(null);
        }
        List<ColumnOverrideEntity> saved = overrides.saveAll(incoming);
        audit.log("system", "DATASET_OVERRIDES_SAVED", "datasetId=" + datasetId + " count=" + saved.size());
        return saved;
    }

    @Transactional
    public ColumnOverrideEntity saveOverride(Long datasetId, ColumnOverrideEntity o) {
        get(datasetId);
        validateOverride(o);
        o.setDatasetId(datasetId);
        overrides.findByDatasetIdAndTableNameAndColumnName(datasetId, o.getTableName(), o.getColumnName())
                .map(ColumnOverrideEntity::getId).ifPresent(overrides::deleteById);
        overrides.flush();
        return overrides.save(o);
    }

    @Transactional
    public void deleteOverride(Long id) {
        ColumnOverrideEntity existing = overrides.findById(id)
                .orElseThrow(() -> ApiException.notFound("Column override " + id + " not found"));
        get(existing.getDatasetId());
        overrides.deleteById(id);
    }

    // ─── User-defined PKs ───────────────────────────────────────────────────

    public List<UserDefinedPkEntity> listCustomPks(Long datasetId) {
        get(datasetId);
        return userPks.findByDatasetId(datasetId);
    }

    @Transactional
    public List<UserDefinedPkEntity> saveCustomPks(Long datasetId, List<UserDefinedPkEntity> incoming) {
        get(datasetId);
        userPks.deleteByDatasetId(datasetId);
        userPks.flush();
        for (UserDefinedPkEntity p : incoming) {
            if (p.getTableName() == null || p.getTableName().isBlank())
                throw ApiException.bad("table_name is required for custom PK");
            if (p.getColumnNames() == null || p.getColumnNames().isBlank())
                throw ApiException.bad("column_names is required for custom PK");
            p.setDatasetId(datasetId);
            p.setId(null);
        }
        return userPks.saveAll(incoming);
    }

    @Transactional
    public UserDefinedPkEntity saveCustomPk(Long datasetId, UserDefinedPkEntity p) {
        get(datasetId);
        if (p.getTableName() == null || p.getTableName().isBlank())
            throw ApiException.bad("table_name is required");
        if (p.getColumnNames() == null || p.getColumnNames().isBlank())
            throw ApiException.bad("column_names is required");
        p.setDatasetId(datasetId);
        userPks.findByDatasetIdAndTableName(datasetId, p.getTableName())
               .map(UserDefinedPkEntity::getId).ifPresent(userPks::deleteById);
        return userPks.save(p);
    }

    @Transactional
    public void deleteCustomPk(Long pkId) {
        UserDefinedPkEntity existing = userPks.findById(pkId)
                .orElseThrow(() -> ApiException.notFound("Custom primary key " + pkId + " not found"));
        get(existing.getDatasetId());
        userPks.deleteById(pkId);
    }

    // ─── User-defined Relationships ─────────────────────────────────────────

    public List<UserDefinedRelationshipEntity> listUserRels(Long datasetId) {
        get(datasetId);
        return userRels.findByDatasetId(datasetId);
    }

    @Transactional
    public UserDefinedRelationshipEntity createUserRel(Long datasetId, UserDefinedRelationshipEntity r) {
        get(datasetId);
        validateUserRel(r);
        r.setDatasetId(datasetId);
        UserDefinedRelationshipEntity saved = userRels.save(r);
        audit.log("system", "USER_REL_CREATED",
                "datasetId=" + datasetId + " " + r.getParentTable() + "→" + r.getChildTable());
        return saved;
    }

    @Transactional
    public UserDefinedRelationshipEntity updateUserRel(Long relId, UserDefinedRelationshipEntity r) {
        UserDefinedRelationshipEntity existing = userRels.findById(relId)
                .orElseThrow(() -> ApiException.notFound("Relationship " + relId + " not found"));
        get(existing.getDatasetId());
        validateUserRel(r);
        existing.setRelName(r.getRelName());
        existing.setParentTable(r.getParentTable());
        existing.setParentColumns(r.getParentColumns());
        existing.setChildTable(r.getChildTable());
        existing.setChildColumns(r.getChildColumns());
        existing.setNote(r.getNote());
        return userRels.save(existing);
    }

    @Transactional
    public void deleteUserRel(Long relId) {
        UserDefinedRelationshipEntity relationship = userRels.findById(relId)
                .orElseThrow(() -> ApiException.notFound("Relationship " + relId + " not found"));
        get(relationship.getDatasetId());
        // cascade: remove any traversal rules that reference this user rel
        traversalRules.findByDatasetId(relationship.getDatasetId()).stream()
                .filter(tr -> "USER".equals(tr.getRelSource()) && relId.equals(tr.getRelRefId()))
                .forEach(tr -> traversalRules.deleteById(tr.getId()));
        userRels.deleteById(relId);
    }

    // ─── Relationship Traversal Rules ───────────────────────────────────────

    public List<RelationshipTraversalRuleEntity> listTraversalRules(Long datasetId) {
        get(datasetId);
        return traversalRules.findByDatasetId(datasetId);
    }

    @Transactional
    public List<RelationshipTraversalRuleEntity> saveTraversalRules(Long datasetId,
                                                                     List<RelationshipTraversalRuleEntity> incoming) {
        get(datasetId);
        traversalRules.deleteByDatasetId(datasetId);
        traversalRules.flush();
        Set<String> DIRECTIONS = Set.of("INHERIT", "BOTH", "Q1_ONLY", "Q2_ONLY", "NONE");
        for (RelationshipTraversalRuleEntity r : incoming) {
            if (r.getParentTable() == null || r.getChildTable() == null)
                throw ApiException.bad("parentTable and childTable are required for traversal rule");
            String source = r.getRelSource() == null ? "DB" : r.getRelSource().trim().toUpperCase(Locale.ROOT);
            if (!Set.of("DB", "USER").contains(source))
                throw ApiException.bad("relSource must be DB or USER");
            if ("USER".equals(source) && r.getRelRefId() == null)
                throw ApiException.bad("relRefId is required for a tool relationship traversal rule");
            String dir = r.getTraverseDirection() == null ? "BOTH" : r.getTraverseDirection().toUpperCase();
            if (!DIRECTIONS.contains(dir)) throw ApiException.bad("traverseDirection must be INHERIT, BOTH, Q1_ONLY, Q2_ONLY, or NONE");
            r.setTraverseDirection(dir);
            r.setRelSource(source);
            if ("DB".equals(source)) r.setRelRefId(null);
            r.setDatasetId(datasetId);
            r.setId(null);
        }
        return traversalRules.saveAll(incoming);
    }

    // ─── Relationship discovery (for Ref Integrity + Traversal Map tabs) ────

    /**
     * Returns all FK relationships (DB catalog + user-defined) between the included
     * tables of this DataScope. Enriched with the currently configured traversal rule.
     * Filtered to tables that are included=true in table_profiles; if no profile exists
     * for a table then it is considered included (opt-out model).
     */
    public List<RelationshipInfo> getRelationships(Long datasetId) {
        DataSetDefinitionEntity def = get(datasetId);

        // Opt-in: only show relationships between tables that are in profiles and marked included.
        // If no profiles have been configured yet, fall back to showing all (null = no filter).
        List<TableProfileEntity> profileList = profiles.findByDatasetId(datasetId);
        final Set<String> includedSet = profileList.isEmpty() ? null :
                profileList.stream()
                        .filter(TableProfileEntity::isIncluded)
                        .map(p -> p.getTableName().toLowerCase(Locale.ROOT))
                        .collect(Collectors.toSet());

        // Traversal rules indexed by "parentLower->childLower" (DB rules)
        // and "parentLower->childLower:USER:<relRefId>" (USER rules)
        Map<String, RelationshipTraversalRuleEntity> ruleIndex = new HashMap<>();
        for (RelationshipTraversalRuleEntity r : traversalRules.findByDatasetId(datasetId)) {
            String key = ruleKey(r.getParentTable(), r.getChildTable(), r.getRelSource(), r.getRelRefId());
            ruleIndex.put(key, r);
        }

        List<RelationshipInfo> result = new ArrayList<>();

        // 1. DB-level FK relationships
        String schema = def.getSchemaName();
        try (Connection c = connections.openPooled(dataSources.get(def.getDataSourceId()))) {
            DatabaseMetaData meta = c.getMetaData();
            // Gather all tables visible in this schema and retrieve their imported FKs
            try (ResultSet tables = meta.getTables(null, schema, "%", new String[]{"TABLE"})) {
                while (tables.next()) {
                    String childTable = tables.getString("TABLE_NAME");
                    if (includedSet != null && !includedSet.contains(childTable.toLowerCase(Locale.ROOT))) continue;
                    // imported keys = FKs where this table is the child
                    try (ResultSet fks = meta.getImportedKeys(null, schema, childTable)) {
                        // Multi-column FK: group by FK_NAME
                        Map<String, List<Object[]>> fkGroups = new LinkedHashMap<>();
                        while (fks.next()) {
                            String fkName = fks.getString("FK_NAME");
                            if (fkName == null) fkName = fks.getString("PKTABLE_NAME") + "_" + childTable;
                            fkGroups.computeIfAbsent(fkName, k -> new ArrayList<>()).add(new Object[]{
                                    fks.getString("PKTABLE_NAME"),
                                    fks.getString("PKCOLUMN_NAME"),
                                    fks.getString("FKCOLUMN_NAME"),
                                    fks.getShort("KEY_SEQ")
                            });
                        }
                        for (List<Object[]> cols : fkGroups.values()) {
                            cols.sort(Comparator.comparingInt(row -> (Short) row[3]));
                            String parentTable = (String) cols.get(0)[0];
                            if (includedSet != null && !includedSet.contains(parentTable.toLowerCase(Locale.ROOT))) continue;
                            List<String> parentCols = cols.stream().map(r -> (String) r[1]).toList();
                            List<String> childCols  = cols.stream().map(r -> (String) r[2]).toList();
                            String key = ruleKey(parentTable, childTable, "DB", null);
                            RelationshipTraversalRuleEntity rule = ruleIndex.get(key);
                            result.add(new RelationshipInfo(
                                    parentTable, parentCols, childTable, childCols,
                                    "DB", null, null,
                                    rule != null ? rule.getTraverseDirection() : null,
                                    rule != null ? rule.getId() : null,
                                    rule != null ? rule.getPriority() : 0,
                                    rule != null ? rule.getNote() : null
                            ));
                        }
                    }
                }
            }
        } catch (Exception e) {
            // DB metadata unavailable — proceed with user-defined only
        }

        // 2. User-defined relationships
        for (UserDefinedRelationshipEntity ur : userRels.findByDatasetId(datasetId)) {
            if (includedSet != null && !includedSet.contains(ur.getParentTable().toLowerCase(Locale.ROOT))) continue;
            if (includedSet != null && !includedSet.contains(ur.getChildTable().toLowerCase(Locale.ROOT))) continue;
            String key = ruleKey(ur.getParentTable(), ur.getChildTable(), "USER", ur.getId());
            RelationshipTraversalRuleEntity rule = ruleIndex.get(key);
            result.add(new RelationshipInfo(
                    ur.getParentTable(), List.of(ur.getParentColumns().split(",\\s*")),
                    ur.getChildTable(),  List.of(ur.getChildColumns().split(",\\s*")),
                    "USER", ur.getId(), ur.getRelName(),
                    rule != null ? rule.getTraverseDirection() : null,
                    rule != null ? rule.getId() : null,
                    rule != null ? rule.getPriority() : 0,
                    rule != null ? rule.getNote() : null
            ));
        }

        return result;
    }

    // ─── Plan Preview ───────────────────────────────────────────────────────

    public SubsetService.SubsetPlan previewPlan(Long datasetId, int maxDriverRows) {
        DataSetDefinitionEntity def = assertAuthorizedReferences(datasetId, null);
        if (def.getDriverTable() == null || def.getDriverTable().isBlank())
            throw ApiException.bad("DataScope has no driver table set");

        List<TableProfileEntity>             tableProfiles = profiles.findByDatasetId(datasetId);
        List<UserDefinedRelationshipEntity>  uRels         = userRels.findByDatasetId(datasetId);
        List<RelationshipTraversalRuleEntity> tRules       = traversalRules.findByDatasetId(datasetId);

        return subsets.planWithDirectives(
                def.getDataSourceId(), def.getSchemaName(),
                def.getDriverTable(), def.getDriverFilter(),
                maxDriverRows, def.isGlobalQ1(), def.isGlobalQ2(),
                toDirectives(tableProfiles),
                toUserEdges(uRels),
                toTraversalDirectionMap(tRules));
    }

    // ─── PII coverage (pre-provision masking guardrail) ─────────────────────

    /**
     * For every included table, cross-references the PII Discovery classifications with the masking
     * that would actually apply during provisioning (per-table policy rules + column overrides).
     * APPROVED classifications with no masking are the compliance risk surfaced to the user.
     * defaultPolicyId overrides the blueprint's linked policy (the Provision tab lets users pick one ad hoc).
     */
    public Map<String, Object> piiCoverage(Long datasetId, Long defaultPolicyId) {
        DataSetDefinitionEntity def = assertAuthorizedReferences(datasetId, defaultPolicyId);
        Long effectiveDefaultPolicy = defaultPolicyId != null ? defaultPolicyId : def.getPolicyId();
        List<TableProfileEntity> included = profiles.findByDatasetId(datasetId).stream()
                .filter(TableProfileEntity::isIncluded).toList();
        List<ColumnOverrideEntity> allOverrides = overrides.findByDatasetId(datasetId);

        Map<Long, List<ClassificationEntity>> clsByDs = new HashMap<>();
        Map<Long, List<MaskingRuleEntity>> rulesByPolicy = new HashMap<>();

        List<Map<String, Object>> tables = new ArrayList<>();
        List<Map<String, Object>> unmaskedApproved = new ArrayList<>();
        int approvedTotal = 0, approvedMasked = 0, suggestedTotal = 0, suggestedMasked = 0;

        for (TableProfileEntity p : included) {
            Long dsId = p.getSourceDataSourceId() != null ? p.getSourceDataSourceId() : def.getDataSourceId();
            String schema = notBlank(p.getSourceSchemaName()) ? p.getSourceSchemaName() : def.getSchemaName();
            List<ClassificationEntity> cls = clsByDs.computeIfAbsent(dsId, classifications::findByDataSourceId).stream()
                    .filter(c -> c.getTableName().equalsIgnoreCase(p.getTableName()))
                    .filter(c -> !notBlank(schema) || c.getSchemaName() == null || c.getSchemaName().equalsIgnoreCase(schema))
                    .filter(c -> "APPROVED".equalsIgnoreCase(c.getStatus()) || "SUGGESTED".equalsIgnoreCase(c.getStatus()))
                    .toList();
            if (cls.isEmpty()) continue;

            Long policyId = p.getPolicyId() != null ? p.getPolicyId() : effectiveDefaultPolicy;
            List<MaskingRuleEntity> rules = policyId == null ? List.of()
                    : rulesByPolicy.computeIfAbsent(policyId, maskingRules::findByPolicyId);
            List<ColumnOverrideEntity> tableOvs = allOverrides.stream()
                    .filter(o -> o.getTableName().equalsIgnoreCase(p.getTableName())).toList();

            int tApproved = 0, tApprovedMasked = 0, tSuggested = 0, tSuggestedMasked = 0;
            List<Map<String, Object>> columns = new ArrayList<>();
            for (ClassificationEntity c : cls) {
                String state = maskState(p.getTableName(), c.getColumnName(), schema, rules, tableOvs);
                boolean masked = !"UNMASKED".equals(state);
                boolean approved = "APPROVED".equalsIgnoreCase(c.getStatus());
                if (approved) { tApproved++; if (masked) tApprovedMasked++; }
                else          { tSuggested++; if (masked) tSuggestedMasked++; }
                Map<String, Object> col = new LinkedHashMap<>();
                col.put("column", c.getColumnName());
                col.put("piiType", c.getPiiType());
                col.put("status", c.getStatus());
                col.put("confidence", c.getConfidence());
                col.put("maskState", state);   // MASKED | CONDITIONAL | UNMASKED
                columns.add(col);
                if (approved && !masked) {
                    Map<String, Object> u = new LinkedHashMap<>();
                    u.put("table", p.getTableName());
                    u.put("column", c.getColumnName());
                    u.put("piiType", c.getPiiType());
                    unmaskedApproved.add(u);
                }
            }
            approvedTotal += tApproved; approvedMasked += tApprovedMasked;
            suggestedTotal += tSuggested; suggestedMasked += tSuggestedMasked;

            Map<String, Object> t = new LinkedHashMap<>();
            t.put("table", p.getTableName());
            t.put("approved", tApproved);
            t.put("approvedMasked", tApprovedMasked);
            t.put("suggested", tSuggested);
            t.put("suggestedMasked", tSuggestedMasked);
            t.put("columns", columns);
            tables.add(t);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("approvedTotal", approvedTotal);
        out.put("approvedMasked", approvedMasked);
        out.put("suggestedTotal", suggestedTotal);
        out.put("suggestedMasked", suggestedMasked);
        out.put("unmaskedApproved", unmaskedApproved);
        out.put("tables", tables);
        return out;
    }

    /** How a source column is treated during provisioning: an explicit override wins over policy rules. */
    private static String maskState(String table, String column, String schema,
                                    List<MaskingRuleEntity> rules, List<ColumnOverrideEntity> tableOvs) {
        for (ColumnOverrideEntity o : tableOvs) {
            String src = notBlank(o.getSourceColumnName()) ? o.getSourceColumnName() : o.getColumnName();
            if (!src.equalsIgnoreCase(column)) continue;
            String type = o.getOverrideType() == null ? "USE_POLICY" : o.getOverrideType().trim().toUpperCase(Locale.ROOT);
            if ("LITERAL".equals(type) || "NULL_OUT".equals(type) || "SUPPRESS".equals(type)) {
                boolean conditional = notBlank(o.getCondExpr()) || notBlank(o.getCondColumn()) || notBlank(o.getCondJson());
                return conditional ? "CONDITIONAL" : "MASKED";
            }
            break;   // USE_POLICY — fall through to the policy rules
        }
        for (MaskingRuleEntity r : rules) {
            if (!r.getTableName().equalsIgnoreCase(table)) continue;
            if (r.getSchemaName() != null && notBlank(schema) && !r.getSchemaName().equalsIgnoreCase(schema)) continue;
            if (!r.getColumnName().equalsIgnoreCase(column)) continue;
            if (!"PASSTHROUGH".equalsIgnoreCase(r.getFunction())) return "MASKED";
        }
        return "UNMASKED";
    }

    // ─── Schema drift (blueprint vs live source metadata) ───────────────────

    /**
     * Verifies that every table and column this blueprint references still exists in the live source
     * schema: table profiles, the driver table, column overrides, custom PKs, and user-defined
     * relationships. Renames and drops otherwise surface only as mid-job failures.
     */
    public Map<String, Object> schemaDrift(Long datasetId) {
        DataSetDefinitionEntity def = get(datasetId);
        List<TableProfileEntity> included = profiles.findByDatasetId(datasetId).stream()
                .filter(TableProfileEntity::isIncluded).toList();
        List<ColumnOverrideEntity> allOverrides = overrides.findByDatasetId(datasetId);
        List<UserDefinedPkEntity> pks = userPks.findByDatasetId(datasetId);
        List<UserDefinedRelationshipEntity> rels = userRels.findByDatasetId(datasetId);

        List<Map<String, Object>> issues = new ArrayList<>();
        Map<String, Set<String>> liveTablesByCtx = new HashMap<>();   // "dsId|schema" -> lower table names (null = unreachable)
        Map<String, Set<String>> liveColumnsByTable = new HashMap<>(); // "dsId|schema|table" -> lower column names

        String defaultCtx = ctxKey(def.getDataSourceId(), def.getSchemaName());
        Map<String, TableProfileEntity> profileByTable = new HashMap<>();
        for (TableProfileEntity p : included) profileByTable.put(p.getTableName().toLowerCase(Locale.ROOT), p);

        // 1. Table profiles (per source context) + the driver table (default context)
        for (TableProfileEntity p : included) {
            Long dsId = p.getSourceDataSourceId() != null ? p.getSourceDataSourceId() : def.getDataSourceId();
            String schema = notBlank(p.getSourceSchemaName()) ? p.getSourceSchemaName() : def.getSchemaName();
            Set<String> live = liveTables(dsId, schema, liveTablesByCtx, issues);
            if (live != null && !live.contains(p.getTableName().toLowerCase(Locale.ROOT))) {
                issues.add(issue("TABLE_MISSING", p.getTableName(), null, "table profile",
                        "Table no longer exists in the source schema"));
            }
        }
        if (notBlank(def.getDriverTable())) {
            Set<String> live = liveTables(def.getDataSourceId(), def.getSchemaName(), liveTablesByCtx, issues);
            if (live != null && !live.contains(def.getDriverTable().toLowerCase(Locale.ROOT))) {
                issues.add(issue("TABLE_MISSING", def.getDriverTable(), null, "driver table",
                        "Driver table no longer exists in the source schema"));
            }
        }

        // 2. Column overrides — the source column must still exist on the profiled table
        for (ColumnOverrideEntity o : allOverrides) {
            TableProfileEntity p = profileByTable.get(o.getTableName().toLowerCase(Locale.ROOT));
            if (p == null) continue;   // override on an excluded/removed table — profiles check covers it
            String col = notBlank(o.getSourceColumnName()) ? o.getSourceColumnName() : o.getColumnName();
            checkColumn(def, p, o.getTableName(), col, "column override", liveTablesByCtx, liveColumnsByTable, issues);
        }

        // 3. Custom PKs and user-defined relationships (defined against the default source)
        for (UserDefinedPkEntity pk : pks) {
            for (String col : splitCols(pk.getColumnNames())) {
                checkColumn(def, profileByTable.get(pk.getTableName().toLowerCase(Locale.ROOT)),
                        pk.getTableName(), col, "custom PK", liveTablesByCtx, liveColumnsByTable, issues);
            }
        }
        for (UserDefinedRelationshipEntity r : rels) {
            for (String col : splitCols(r.getParentColumns())) {
                checkColumn(def, profileByTable.get(r.getParentTable().toLowerCase(Locale.ROOT)),
                        r.getParentTable(), col, "relationship " + r.getRelName(), liveTablesByCtx, liveColumnsByTable, issues);
            }
            for (String col : splitCols(r.getChildColumns())) {
                checkColumn(def, profileByTable.get(r.getChildTable().toLowerCase(Locale.ROOT)),
                        r.getChildTable(), col, "relationship " + r.getRelName(), liveTablesByCtx, liveColumnsByTable, issues);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("issues", issues);
        out.put("checkedAt", Instant.now().toString());
        out.put("inSync", issues.isEmpty());
        // convenience for the UI: was the default source context reachable at all?
        out.put("sourceReachable", liveTablesByCtx.get(defaultCtx) != null);
        return out;
    }

    private Set<String> liveTables(Long dsId, String schema, Map<String, Set<String>> cache,
                                   List<Map<String, Object>> issues) {
        String key = ctxKey(dsId, schema);
        if (cache.containsKey(key)) return cache.get(key);
        Set<String> live;
        try {
            live = dataSources.tables(dsId, notBlank(schema) ? schema : null).stream()
                    .map(m -> String.valueOf(m.get("table")).toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            live = null;
            issues.add(issue("SOURCE_UNREACHABLE", null, null, "source",
                    "Cannot read metadata from data source " + dsId + (notBlank(schema) ? " / " + schema : "")
                            + ": " + e.getMessage()));
        }
        cache.put(key, live);
        return live;
    }

    private void checkColumn(DataSetDefinitionEntity def, TableProfileEntity p, String table, String column,
                             String artifact, Map<String, Set<String>> liveTablesByCtx,
                             Map<String, Set<String>> liveColumnsByTable, List<Map<String, Object>> issues) {
        if (!notBlank(column)) return;
        Long dsId = p != null && p.getSourceDataSourceId() != null ? p.getSourceDataSourceId() : def.getDataSourceId();
        String schema = p != null && notBlank(p.getSourceSchemaName()) ? p.getSourceSchemaName() : def.getSchemaName();
        Set<String> live = liveTablesByCtx.get(ctxKey(dsId, schema));
        if (live == null || !live.contains(table.toLowerCase(Locale.ROOT))) return;   // table-level issue already reported
        String tableKey = ctxKey(dsId, schema) + "|" + table.toLowerCase(Locale.ROOT);
        Set<String> cols = liveColumnsByTable.computeIfAbsent(tableKey, k -> {
            try {
                Set<String> out = new HashSet<>();
                for (Map<String, Object> c : dataSources.columns(dsId, notBlank(schema) ? schema : null, table))
                    out.add(String.valueOf(c.get("column")).toLowerCase(Locale.ROOT));
                return out;
            } catch (Exception e) { return Set.of(); }
        });
        if (!cols.isEmpty() && !cols.contains(column.trim().toLowerCase(Locale.ROOT))) {
            issues.add(issue("COLUMN_MISSING", table, column.trim(), artifact,
                    "Column no longer exists on the source table"));
        }
    }

    private static Map<String, Object> issue(String type, String table, String column, String artifact, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("table", table);
        m.put("column", column);
        m.put("artifact", artifact);
        m.put("detail", detail);
        return m;
    }

    private static String ctxKey(Long dsId, String schema) {
        return dsId + "|" + (schema == null ? "" : schema.trim().toLowerCase(Locale.ROOT));
    }

    private static List<String> splitCols(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    // ─── Helpers used by ProvisioningService ────────────────────────────────

    public List<SubsetService.TableDirective> toDirectives(List<TableProfileEntity> tableProfiles) {
        return tableProfiles.stream().map(p -> {
            boolean independent = "INDEPENDENT".equalsIgnoreCase(p.getReferentialStrategy());
            return new SubsetService.TableDirective(
                    p.getTableName(), p.isIncluded(), p.getFilterExpr(),
                    p.getReferentialStrategy(),
                    independent ? "NO" : effectiveQMode(p.getQ1Mode(), p.getQ1Override()),
                    independent ? "NO" : effectiveQMode(p.getQ2Mode(), p.getQ2Override()),
                    p.getRowLimit()
            );
        }).collect(Collectors.toList());
    }

    /** New-style q1/q2 mode (YES/NO/DEFER) wins; otherwise fall back to the classic console's boolean override. */
    private static String effectiveQMode(String mode, Boolean legacyOverride) {
        if (mode != null && !mode.isBlank()) {
            String clean = mode.trim().toUpperCase(java.util.Locale.ROOT);
            if (clean.equals("YES") || clean.equals("NO") || clean.equals("DEFER")) return clean;
        }
        if (legacyOverride == null) return null;
        return legacyOverride ? "YES" : "NO";
    }

    public List<SubsetService.UserRelEdge> toUserEdges(List<UserDefinedRelationshipEntity> rels) {
        List<SubsetService.UserRelEdge> edges = new ArrayList<>();
        for (UserDefinedRelationshipEntity r : rels) {
            String[] parentCols = r.getParentColumns().split(",\\s*");
            String[] childCols  = r.getChildColumns().split(",\\s*");
            int len = Math.min(parentCols.length, childCols.length);
            for (int i = 0; i < len; i++) {
                edges.add(new SubsetService.UserRelEdge(
                        r.getParentTable(), parentCols[i].trim(),
                        r.getChildTable(),  childCols[i].trim(),
                        r.getRelName() != null ? r.getRelName() : r.getParentTable() + "→" + r.getChildTable(),
                        r.getId()
                ));
            }
        }
        return edges;
    }

    /** Traversal direction map keyed by parent/child plus relationship source and tool relationship id. */
    public Map<String, String> toTraversalDirectionMap(List<RelationshipTraversalRuleEntity> rules) {
        Map<String, String> map = new HashMap<>();
        // Sort by priority so higher-priority (lower number) wins on duplicate keys
        rules.stream()
             .sorted(Comparator.comparingInt(RelationshipTraversalRuleEntity::getPriority))
             .forEach(r -> {
                 String key = ruleKey(r.getParentTable(), r.getChildTable(), r.getRelSource(), r.getRelRefId());
                 map.putIfAbsent(key, r.getTraverseDirection());
             });
        return map;
    }

    /** Build a per-table, per-column override map: tableName.lower → columnName.lower → override. */
    public Map<String, Map<String, ColumnOverrideEntity>> overrideMap(Long datasetId) {
        Map<String, Map<String, ColumnOverrideEntity>> result = new LinkedHashMap<>();
        for (ColumnOverrideEntity o : overrides.findByDatasetId(datasetId)) {
            result.computeIfAbsent(o.getTableName().toLowerCase(Locale.ROOT), k -> new LinkedHashMap<>())
                  .put(o.getColumnName().toLowerCase(Locale.ROOT), o);
        }
        return result;
    }

    /** Custom PK map: tableName.lower → comma-separated column list. */
    public Map<String, String> customPkMap(Long datasetId) {
        Map<String, String> map = new HashMap<>();
        for (UserDefinedPkEntity p : userPks.findByDatasetId(datasetId)) {
            map.put(p.getTableName().toLowerCase(Locale.ROOT), p.getColumnNames());
        }
        return map;
    }

    // ─── Validation helpers ─────────────────────────────────────────────────

    /**
     * Re-authorize the complete transitive reference set before previewing or queueing work.
     * Stored blueprints created before this guard may still contain stale or cross-tenant ids, so
     * runtime callers must not rely solely on validation performed when the blueprint was saved.
     */
    public DataSetDefinitionEntity assertAuthorizedReferences(Long datasetId, Long overridePolicyId) {
        DataSetDefinitionEntity definition = get(datasetId);
        assertDefinitionReferences(definition);
        for (TableProfileEntity profile : profiles.findByDatasetId(datasetId)) {
            assertProfileReferences(definition, profile);
        }
        references.policy(overridePolicyId);
        return definition;
    }

    private void assertDefinitionReferences(DataSetDefinitionEntity definition) {
        if (definition.getDataSourceId() == null) throw ApiException.bad("dataSourceId is required");
        dataSources.getSourceCapable(definition.getDataSourceId());
        if (definition.getTargetDataSourceId() != null) {
            dataSources.getTargetCapable(definition.getTargetDataSourceId());
        }
        references.policy(definition.getPolicyId());
    }

    private void assertProfileReferences(DataSetDefinitionEntity definition, TableProfileEntity profile) {
        Long sourceId = profile.getSourceDataSourceId() != null
                ? profile.getSourceDataSourceId() : definition.getDataSourceId();
        dataSources.getSourceCapable(sourceId);
        references.policy(profile.getPolicyId());
    }

    private static void validateProfile(TableProfileEntity p) {
        if (p.getTableName() == null || p.getTableName().isBlank())
            throw ApiException.bad("table_name is required for table profile");
        p.setTableName(p.getTableName().trim());
        if (p.getSourceDataSourceId() != null && p.getSourceDataSourceId() <= 0) p.setSourceDataSourceId(null);
        if (p.getPolicyId() != null && p.getPolicyId() <= 0) p.setPolicyId(null);
        p.setSourceSchemaName(blankToNull(p.getSourceSchemaName()));
        p.setTargetTableName(blankToNull(p.getTargetTableName()));
        if (p.getRowLimit() != null && p.getRowLimit() <= 0) p.setRowLimit(null);
        String strat = p.getReferentialStrategy() == null ? "INHERIT" : p.getReferentialStrategy().toUpperCase();
        if (!Set.of("INHERIT", "FOLLOW_PARENT", "INDEPENDENT").contains(strat))
            throw ApiException.bad("referentialStrategy must be INHERIT, FOLLOW_PARENT, or INDEPENDENT");
        p.setReferentialStrategy(strat);
        if ("INDEPENDENT".equals(strat)) {
            p.setQ1Mode(null);
            p.setQ1Override(null);
            p.setQ2Mode(null);
            p.setQ2Override(null);
        }
        if (p.getFilterExpr() != null) SubsetService.guardFilter(p.getFilterExpr());
    }

    /**
     * Resolve every physical source before replacing the saved profile. This catches a
     * mistyped DB_ALIAS,SCHEMA.TABLE while the user is still in Table Map instead of
     * allowing a provisioning job to fail later.
     */
    private void validatePhysicalSourceTables(DataSetDefinitionEntity definition,
                                              List<TableProfileEntity> tableProfiles) {
        Map<SourceCatalogKey, Set<String>> tableCatalogs = new HashMap<>();
        for (TableProfileEntity profile : tableProfiles) {
            Long sourceId = profile.getSourceDataSourceId() != null
                    ? profile.getSourceDataSourceId()
                    : definition.getDataSourceId();
            String schema = profile.getSourceSchemaName() != null
                    ? profile.getSourceSchemaName()
                    : definition.getSchemaName();
            if (sourceId == null) {
                throw ApiException.bad("A source data source is required before saving Table Map");
            }

            DataSourceEntity source = dataSources.get(sourceId);
            String role = String.valueOf(source.getRole()).toUpperCase(Locale.ROOT);
            if (!Set.of("SOURCE", "BOTH").contains(role)) {
                throw ApiException.bad("Data source '" + source.getName() + "' is not source-capable");
            }

            SourceCatalogKey key = new SourceCatalogKey(
                    sourceId,
                    schema == null ? "" : schema.trim().toLowerCase(Locale.ROOT));
            Set<String> tableNames = tableCatalogs.computeIfAbsent(key, ignored ->
                    dataSources.tables(sourceId, notBlank(schema) ? schema : null).stream()
                            .map(row -> String.valueOf(row.get("table")).toLowerCase(Locale.ROOT))
                            .collect(Collectors.toSet()));
            if (!tableNames.contains(profile.getTableName().toLowerCase(Locale.ROOT))) {
                throw ApiException.bad("Source table '" + profile.getTableName()
                        + "' was not found in data source '" + source.getName()
                        + "', schema '" + (notBlank(schema) ? schema : "default")
                        + "'. Correct DB_ALIAS,SCHEMA.TABLE before saving Table Map.");
            }
        }
    }

    private record SourceCatalogKey(Long dataSourceId, String schemaName) {}

    private static void validateOverride(ColumnOverrideEntity o) {
        if (o.getTableName() == null || o.getTableName().isBlank())
            throw ApiException.bad("table_name is required for column override");
        if (o.getColumnName() == null || o.getColumnName().isBlank())
            throw ApiException.bad("column_name is required for column override");
        o.setSourceColumnName(blankToNull(o.getSourceColumnName()));
        String type = o.getOverrideType() == null ? "USE_POLICY" : o.getOverrideType().toUpperCase();
        if (!Set.of("USE_POLICY", "LITERAL", "NULL_OUT", "SUPPRESS").contains(type))
            throw ApiException.bad("overrideType must be USE_POLICY, LITERAL, NULL_OUT, or SUPPRESS");
        o.setOverrideType(type);
        if ("LITERAL".equals(type) && (o.getLiteralValue() == null || o.getLiteralValue().isBlank()))
            throw ApiException.bad("literalValue is required when overrideType is LITERAL");
    }

    private static void validateUserRel(UserDefinedRelationshipEntity r) {
        if (r.getParentTable() == null || r.getParentTable().isBlank())
            throw ApiException.bad("parentTable is required");
        if (r.getChildTable() == null || r.getChildTable().isBlank())
            throw ApiException.bad("childTable is required");
        if (r.getParentColumns() == null || r.getParentColumns().isBlank())
            throw ApiException.bad("parentColumns is required");
        if (r.getChildColumns() == null || r.getChildColumns().isBlank())
            throw ApiException.bad("childColumns is required");
        String[] pCols = r.getParentColumns().split(",");
        String[] cCols = r.getChildColumns().split(",");
        if (pCols.length != cCols.length)
            throw ApiException.bad("parentColumns and childColumns must have the same number of entries");
    }

    private static String ruleKey(String parent, String child, String source, Long refId) {
        String base = parent.toLowerCase(Locale.ROOT) + "->" + child.toLowerCase(Locale.ROOT) + ":" + source;
        return refId != null ? base + ":" + refId : base;
    }

    private static String validateBlueprintName(String value) {
        if (value == null || value.isBlank()) throw ApiException.bad("name is required");
        String name = value.trim();
        if (name.length() < BLUEPRINT_NAME_MIN_LENGTH || name.length() > BLUEPRINT_NAME_MAX_LENGTH) {
            throw ApiException.bad("DataScope blueprint name must be between " + BLUEPRINT_NAME_MIN_LENGTH + " and " + BLUEPRINT_NAME_MAX_LENGTH + " characters");
        }
        return name;
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }
}
