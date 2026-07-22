package io.forgetdm.businessentity;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.dataset.DataSetDefinitionEntity;
import io.forgetdm.dataset.DataSetDefinitionRepository;
import io.forgetdm.dataset.DataSetService;
import io.forgetdm.dataset.TableProfileEntity;
import io.forgetdm.dataset.UserDefinedRelationshipEntity;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceRepository;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.OwnershipGuard;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class BusinessEntityService {
    private final BusinessEntityDefinitionRepository defs;
    private final BusinessEntityMemberRepository members;
    private final DataSetDefinitionRepository datasets;
    private final DataSetService dataSetService;
    private final DataSourceRepository dataSources;
    private final AuditService audit;
    private final OwnershipGuard ownership;

    public BusinessEntityService(BusinessEntityDefinitionRepository defs,
                                 BusinessEntityMemberRepository members,
                                 DataSetDefinitionRepository datasets,
                                 DataSetService dataSetService,
                                 DataSourceRepository dataSources,
                                 AuditService audit,
                                 OwnershipGuard ownership) {
        this.defs = defs;
        this.members = members;
        this.datasets = datasets;
        this.dataSetService = dataSetService;
        this.dataSources = dataSources;
        this.audit = audit;
        this.ownership = ownership;
    }

    public record BusinessEntitySummary(Long id, String name, String description, String domain,
                                        String ownerUsername, Long primaryDatasetId, String primaryDatasetName,
                                        String rootTable, String businessKeyColumns, String status,
                                        long memberCount, long dataSourceCount, Instant updatedAt) {}
    public record BusinessEntityDetail(BusinessEntityDefinitionEntity entity,
                                       List<BusinessEntityMemberEntity> members,
                                       String primaryDatasetName,
                                       Map<Long, String> dataSourceNames) {}
    public record FromDatasetRequest(String name, String description, String domain) {}
    public record ImportDatasetRequest(Boolean makePrimary, String systemName) {}
    public record DatasetImportResult(BusinessEntityDetail detail, Long datasetId, String datasetName,
                                      String systemName, int addedMembers, int skippedDuplicates) {}

    public List<BusinessEntitySummary> list() {
        List<BusinessEntityDefinitionEntity> visible = defs.findAll(Sort.by(Sort.Direction.ASC, "name")).stream()
                .filter(d -> ownership.canSee(d.getOwnerUserId(), d.getOwnerGroupId(), d.getVisibility()))
                .toList();
        Set<Long> visibleIds = visible.stream().map(BusinessEntityDefinitionEntity::getId).collect(Collectors.toSet());
        Map<Long, String> datasetNames = visible.stream()
                .map(BusinessEntityDefinitionEntity::getPrimaryDatasetId)
                .filter(Objects::nonNull)
                .distinct()
                .map(this::requireAuthorizedDataset)
                .collect(Collectors.toMap(DataSetDefinitionEntity::getId, DataSetDefinitionEntity::getName));
        Map<Long, Long> dataSourceCounts = members.findAll().stream()
                .filter(m -> visibleIds.contains(m.getEntityId()))
                .filter(m -> m.getEntityId() != null && m.getDataSourceId() != null)
                .collect(Collectors.groupingBy(BusinessEntityMemberEntity::getEntityId,
                        Collectors.mapping(BusinessEntityMemberEntity::getDataSourceId,
                                Collectors.collectingAndThen(Collectors.toSet(), s -> (long) s.size()))));
        return visible.stream()
                .map(d -> new BusinessEntitySummary(
                        d.getId(), d.getName(), d.getDescription(), d.getDomain(), d.getOwnerUsername(),
                        d.getPrimaryDatasetId(), datasetNames.get(d.getPrimaryDatasetId()),
                        d.getRootTable(), d.getBusinessKeyColumns(), d.getStatus(),
                        members.countByEntityId(d.getId()), dataSourceCounts.getOrDefault(d.getId(), 0L),
                        d.getUpdatedAt()))
                .toList();
    }

    public BusinessEntityDetail getDetail(Long id) {
        BusinessEntityDefinitionEntity entity = get(id);
        List<BusinessEntityMemberEntity> memberRows = members.findByEntityIdOrderByOrdinalNoAscIdAsc(id);
        memberRows.stream().map(BusinessEntityMemberEntity::getDatasetId).filter(Objects::nonNull)
                .distinct().forEach(this::requireAuthorizedDataset);
        Set<Long> dsIds = memberRows.stream().map(BusinessEntityMemberEntity::getDataSourceId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, String> dsNames = dataSources.findAllById(dsIds).stream()
                .peek(this::assertDataSourceAccess)
                .collect(Collectors.toMap(DataSourceEntity::getId, DataSourceEntity::getName,
                        (a, b) -> a, LinkedHashMap::new));
        String datasetName = entity.getPrimaryDatasetId() == null ? null
                : requireAuthorizedDataset(entity.getPrimaryDatasetId()).getName();
        return new BusinessEntityDetail(entity, memberRows, datasetName, dsNames);
    }

    @Transactional
    public BusinessEntityDefinitionEntity create(BusinessEntityDefinitionEntity body) {
        BusinessEntityDefinitionEntity clean = cleanDefinition(body, new BusinessEntityDefinitionEntity());
        defs.findByName(clean.getName()).ifPresent(existing -> {
            throw ApiException.conflict("Business Entity name already exists: " + clean.getName());
        });
        clean.setOwnerUserId(ownership.defaultOwnerUserId());
        clean.setOwnerUsername(ownership.defaultOwnerUsername());
        clean.setOwnerGroupId(ownership.defaultOwnerGroupId());
        clean.setVisibility(ownership.caller().isEmpty()
                ? OwnershipGuard.SHARED
                : normalizeVisibility(body != null && body.isVisibilitySpecified() ? body.getVisibility() : ownership.defaultVisibility()));
        clean.setCreatedAt(Instant.now());
        clean.setUpdatedAt(Instant.now());
        BusinessEntityDefinitionEntity saved = defs.save(clean);
        audit.log(currentUsername(), "BUSINESS_ENTITY_CREATE", "Created " + saved.getName());
        return saved;
    }

    @Transactional
    public BusinessEntityDefinitionEntity update(Long id, BusinessEntityDefinitionEntity body) {
        BusinessEntityDefinitionEntity existing = get(id);
        Long ownerUserId = existing.getOwnerUserId();
        String ownerUsername = existing.getOwnerUsername();
        Long ownerGroupId = existing.getOwnerGroupId();
        String visibility = existing.getVisibility();
        BusinessEntityDefinitionEntity clean = cleanDefinition(body, existing);
        defs.findByName(clean.getName()).ifPresent(other -> {
            if (!Objects.equals(other.getId(), id)) throw ApiException.conflict("Business Entity name already exists: " + clean.getName());
        });
        clean.setOwnerUserId(ownerUserId);
        clean.setOwnerUsername(ownerUsername);
        clean.setOwnerGroupId(ownerGroupId);
        clean.setVisibility(body != null && body.isVisibilitySpecified()
                ? normalizeVisibility(body.getVisibility()) : visibility);
        clean.setUpdatedAt(Instant.now());
        BusinessEntityDefinitionEntity saved = defs.save(clean);
        audit.log(currentUsername(), "BUSINESS_ENTITY_UPDATE", "Updated " + saved.getName());
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        BusinessEntityDefinitionEntity existing = get(id);
        members.deleteByEntityId(id);
        members.flush();
        defs.delete(existing);
        audit.log(currentUsername(), "BUSINESS_ENTITY_DELETE", "Deleted " + existing.getName());
    }

    @Transactional
    public List<BusinessEntityMemberEntity> replaceMembers(Long entityId, List<BusinessEntityMemberEntity> incoming) {
        BusinessEntityDefinitionEntity entity = get(entityId);
        List<BusinessEntityMemberEntity> existing = members.findByEntityIdOrderByOrdinalNoAscIdAsc(entityId);
        Map<String, BusinessEntityMemberEntity> existingByLogicalKey = existing.stream()
                .collect(Collectors.toMap(this::memberKey, m -> m, (first, ignored) -> first, LinkedHashMap::new));
        List<BusinessEntityMemberEntity> clean = cleanMembers(entityId, incoming == null ? List.of() : incoming);
        Set<Long> retainedIds = new HashSet<>();
        for (BusinessEntityMemberEntity member : clean) {
            BusinessEntityMemberEntity prior = existingByLogicalKey.get(memberKey(member));
            if (prior != null) {
                member.setId(prior.getId());
                retainedIds.add(prior.getId());
            }
        }
        List<BusinessEntityMemberEntity> removed = existing.stream()
                .filter(member -> !retainedIds.contains(member.getId()))
                .toList();
        if (!removed.isEmpty()) {
            members.deleteAll(removed);
            members.flush();
        }
        List<BusinessEntityMemberEntity> saved = members.saveAll(clean);
        entity.setUpdatedAt(Instant.now());
        defs.save(entity);
        audit.log(currentUsername(), "BUSINESS_ENTITY_MEMBERS_SAVE", "Saved " + saved.size() + " members for " + entity.getName());
        return saved;
    }

    @Transactional
    public BusinessEntityDetail createFromDataset(Long datasetId, FromDatasetRequest request) {
        DataSetDefinitionEntity ds = dataSetService.get(datasetId);
        String requestedName = trimToNull(request == null ? null : request.name());
        String name = requestedName != null ? requestedName : ds.getName() + " Entity";
        BusinessEntityDefinitionEntity body = new BusinessEntityDefinitionEntity();
        body.setName(name);
        body.setDescription(trimToNull(request == null ? null : request.description()));
        body.setDomain(trimToNull(request == null ? null : request.domain()));
        body.setPrimaryDatasetId(datasetId);
        body.setRootTable(ds.getDriverTable());
        body.setBusinessKeyColumns(rootKeyColumns(datasetId, ds.getDriverTable()));
        BusinessEntityDefinitionEntity saved = create(body);
        replaceMembers(saved.getId(), membersFromDataset(ds));
        return getDetail(saved.getId());
    }

    /**
     * Attach another DataScope blueprint to an existing Business Entity. A Business Entity has one
     * canonical (primary) blueprint, but any number of application blueprints; every imported member
     * retains its own dataset/data-source identity. Re-importing is idempotent and role names are made
     * unique so cross-application tables with the same physical name remain unambiguous.
     */
    @Transactional
    public DatasetImportResult importDataset(Long entityId, Long datasetId, ImportDatasetRequest request) {
        BusinessEntityDefinitionEntity entity = get(entityId);
        DataSetDefinitionEntity dataset = dataSetService.get(datasetId);
        List<BusinessEntityMemberEntity> existing = members.findByEntityIdOrderByOrdinalNoAscIdAsc(entityId);
        List<BusinessEntityMemberEntity> candidates = membersFromDataset(dataset);
        String requestedSystem = trimToNull(request == null ? null : request.systemName());
        String systemName = requestedSystem != null ? requestedSystem : dataset.getName();

        Set<String> physicalMembers = existing.stream()
                .map(this::physicalMemberKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> usedRoles = existing.stream()
                .map(BusinessEntityMemberEntity::getLogicalRole)
                .filter(Objects::nonNull)
                .map(BusinessEntityService::norm)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<BusinessEntityMemberEntity> imported = new ArrayList<>();
        Map<String, String> importedRoles = new LinkedHashMap<>();
        int skipped = 0;
        for (BusinessEntityMemberEntity candidate : candidates) {
            if (!physicalMembers.add(physicalMemberKey(candidate))) {
                skipped++;
                continue;
            }
            String oldRole = candidate.getLogicalRole();
            String role = uniqueRole(oldRole, systemName, usedRoles);
            importedRoles.put(norm(oldRole), role);
            candidate.setLogicalRole(role);
            candidate.setSystemName(systemName);
            imported.add(candidate);
        }
        for (BusinessEntityMemberEntity candidate : imported) {
            String parentRole = trimToNull(candidate.getJoinToRole());
            if (parentRole != null && importedRoles.containsKey(norm(parentRole))) {
                candidate.setJoinToRole(importedRoles.get(norm(parentRole)));
            }
        }

        boolean makePrimary = entity.getPrimaryDatasetId() == null
                || (request != null && Boolean.TRUE.equals(request.makePrimary()));
        if (makePrimary) {
            entity.setPrimaryDatasetId(datasetId);
            entity.setRootTable(dataset.getDriverTable());
            entity.setBusinessKeyColumns(rootKeyColumns(datasetId, dataset.getDriverTable()));
            entity.setUpdatedAt(Instant.now());
            defs.save(entity);
        }

        List<BusinessEntityMemberEntity> merged = new ArrayList<>(existing);
        merged.addAll(imported);
        replaceMembers(entityId, merged);
        audit.log(currentUsername(), "BUSINESS_ENTITY_BLUEPRINT_ATTACH",
                "entity=" + entityId + " dataset=" + datasetId + " added=" + imported.size()
                        + " skipped=" + skipped + " primary=" + makePrimary);
        return new DatasetImportResult(getDetail(entityId), datasetId, dataset.getName(), systemName,
                imported.size(), skipped);
    }

    private List<BusinessEntityMemberEntity> membersFromDataset(DataSetDefinitionEntity ds) {
        Map<String, String> pkMap = dataSetService.customPkMap(ds.getId());
        Map<String, UserDefinedRelationshipEntity> childToRel = dataSetService.listUserRels(ds.getId()).stream()
                .collect(Collectors.toMap(r -> norm(r.getChildTable()), r -> r, (a, b) -> a, LinkedHashMap::new));
        List<TableProfileEntity> profiles = dataSetService.listProfiles(ds.getId());
        List<BusinessEntityMemberEntity> out = new ArrayList<>();
        int ordinal = 0;
        for (TableProfileEntity p : profiles) {
            BusinessEntityMemberEntity m = new BusinessEntityMemberEntity();
            m.setDatasetId(ds.getId());
            m.setDataSourceId(p.getSourceDataSourceId() != null ? p.getSourceDataSourceId() : ds.getDataSourceId());
            m.setSchemaName(trimToNull(p.getSourceSchemaName()) != null ? p.getSourceSchemaName() : ds.getSchemaName());
            m.setTableName(p.getTableName());
            m.setLogicalRole(logicalRole(p.getTableName()));
            m.setTableAlias(trimToNull(p.getTargetTableName()));
            m.setKeyColumns(pkMap.get(norm(p.getTableName())));
            UserDefinedRelationshipEntity rel = childToRel.get(norm(p.getTableName()));
            if (rel != null) {
                m.setJoinToRole(logicalRole(rel.getParentTable()));
                m.setRelationshipJson(relationshipJson(rel));
            }
            m.setIncludeInSubset(p.isIncluded());
            m.setIncludeInSynthetic(p.isIncluded());
            m.setOrdinalNo(ordinal++);
            out.add(m);
        }
        return out;
    }

    private String memberKey(BusinessEntityMemberEntity member) {
        return norm(member.getLogicalRole()) + "|" + norm(member.getTableName());
    }

    private String physicalMemberKey(BusinessEntityMemberEntity member) {
        return String.valueOf(member.getDatasetId()) + "|" + String.valueOf(member.getDataSourceId()) + "|"
                + norm(member.getSchemaName()) + "|" + norm(member.getTableName());
    }

    private static String uniqueRole(String requestedRole, String systemName, Set<String> usedRoles) {
        String base = roleSlug(requestedRole);
        if (usedRoles.add(norm(base))) return base;
        String prefix = roleSlug(systemName);
        String candidate = prefix + "_" + base;
        int suffix = 2;
        while (!usedRoles.add(norm(candidate))) candidate = prefix + "_" + base + "_" + suffix++;
        return candidate;
    }

    private static String roleSlug(String value) {
        String clean = norm(value).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return clean.isBlank() ? "member" : clean;
    }

    public BusinessEntityDefinitionEntity get(Long id) {
        BusinessEntityDefinitionEntity entity = defs.findById(id)
                .orElseThrow(() -> ApiException.notFound("Business Entity not found: " + id));
        ownership.assertCanSee("business entity", id,
                entity.getOwnerUserId(), entity.getOwnerGroupId(), entity.getVisibility());
        return entity;
    }

    private BusinessEntityDefinitionEntity cleanDefinition(BusinessEntityDefinitionEntity source,
                                                          BusinessEntityDefinitionEntity target) {
        if (source == null) throw ApiException.bad("Business Entity body is required");
        String name = trimToNull(source.getName());
        if (name == null) throw ApiException.bad("Business Entity name is required");
        target.setName(name);
        target.setDescription(trimToNull(source.getDescription()));
        target.setDomain(trimToNull(source.getDomain()));
        target.setPrimaryDatasetId(source.getPrimaryDatasetId());
        if (source.getPrimaryDatasetId() != null) requireAuthorizedDataset(source.getPrimaryDatasetId());
        target.setRootTable(trimToNull(source.getRootTable()));
        target.setBusinessKeyColumns(trimToNull(source.getBusinessKeyColumns()));
        String status = trimToNull(source.getStatus());
        target.setStatus(status == null ? "ACTIVE" : status.toUpperCase(Locale.ROOT));
        return target;
    }

    private List<BusinessEntityMemberEntity> cleanMembers(Long entityId, List<BusinessEntityMemberEntity> incoming) {
        Set<String> seen = new HashSet<>();
        List<BusinessEntityMemberEntity> out = new ArrayList<>();
        int ordinal = 0;
        for (BusinessEntityMemberEntity raw : incoming) {
            if (raw == null) continue;
            String table = trimToNull(raw.getTableName());
            if (table == null) throw ApiException.bad("Member table name is required");
            String role = trimToNull(raw.getLogicalRole());
            if (role == null) role = logicalRole(table);
            String key = role.toLowerCase(Locale.ROOT) + "|" + table.toLowerCase(Locale.ROOT);
            if (!seen.add(key)) throw ApiException.bad("Duplicate member role/table: " + role + " / " + table);
            if (raw.getDatasetId() != null) requireAuthorizedDataset(raw.getDatasetId());
            if (raw.getDataSourceId() != null) requireAuthorizedDataSource(raw.getDataSourceId());
            BusinessEntityMemberEntity m = new BusinessEntityMemberEntity();
            m.setEntityId(entityId);
            m.setSystemName(trimToNull(raw.getSystemName()));
            m.setDataSourceId(raw.getDataSourceId());
            m.setSchemaName(trimToNull(raw.getSchemaName()));
            m.setDatasetId(raw.getDatasetId());
            m.setLogicalRole(role);
            m.setTableName(table);
            m.setTableAlias(trimToNull(raw.getTableAlias()));
            m.setKeyColumns(trimToNull(raw.getKeyColumns()));
            m.setJoinToRole(trimToNull(raw.getJoinToRole()));
            m.setRelationshipJson(trimToNull(raw.getRelationshipJson()));
            m.setIncludeInSubset(raw.isIncludeInSubset());
            m.setIncludeInSynthetic(raw.isIncludeInSynthetic());
            m.setOrdinalNo(raw.getOrdinalNo() > 0 ? raw.getOrdinalNo() : ordinal);
            out.add(m);
            ordinal++;
        }
        return out;
    }

    private String rootKeyColumns(Long datasetId, String rootTable) {
        if (rootTable == null || rootTable.isBlank()) return null;
        return dataSetService.customPkMap(datasetId).get(norm(rootTable));
    }

    private static String relationshipJson(UserDefinedRelationshipEntity rel) {
        return "{\"name\":\"" + json(rel.getRelName()) + "\",\"parentTable\":\"" + json(rel.getParentTable()) +
                "\",\"parentColumns\":\"" + json(rel.getParentColumns()) + "\",\"childTable\":\"" +
                json(rel.getChildTable()) + "\",\"childColumns\":\"" + json(rel.getChildColumns()) + "\"}";
    }

    private static String logicalRole(String table) {
        String clean = trimToNull(table);
        return clean == null ? "member" : clean.toLowerCase(Locale.ROOT);
    }

    private static String norm(String s) {
        return String.valueOf(s == null ? "" : s).trim().toLowerCase(Locale.ROOT);
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String json(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    DataSetDefinitionEntity requireAuthorizedDataset(Long datasetId) {
        return dataSetService.get(datasetId);
    }

    DataSourceEntity requireAuthorizedDataSource(Long dataSourceId) {
        DataSourceEntity source = dataSources.findById(dataSourceId)
                .orElseThrow(() -> ApiException.notFound("Data source " + dataSourceId + " not found"));
        assertDataSourceAccess(source);
        return source;
    }

    private void assertDataSourceAccess(DataSourceEntity dataSource) {
        ownership.assertCanSee("data source", dataSource.getId(), dataSource.getOwnerUserId(),
                dataSource.getOwnerGroupId(), dataSource.getVisibility());
    }

    private static String normalizeVisibility(String value) {
        String normalized = trimToNull(value);
        normalized = normalized == null ? OwnershipGuard.GROUP : normalized.toUpperCase(Locale.ROOT);
        if (!Set.of(OwnershipGuard.PRIVATE, OwnershipGuard.GROUP, OwnershipGuard.SHARED).contains(normalized)) {
            throw ApiException.bad("visibility must be PRIVATE, GROUP, or SHARED");
        }
        return normalized;
    }

    private static String currentUsername() {
        return AccessContext.current().map(p -> p.username()).orElse("system");
    }
}
