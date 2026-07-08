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

    public BusinessEntityService(BusinessEntityDefinitionRepository defs,
                                 BusinessEntityMemberRepository members,
                                 DataSetDefinitionRepository datasets,
                                 DataSetService dataSetService,
                                 DataSourceRepository dataSources,
                                 AuditService audit) {
        this.defs = defs;
        this.members = members;
        this.datasets = datasets;
        this.dataSetService = dataSetService;
        this.dataSources = dataSources;
        this.audit = audit;
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

    public List<BusinessEntitySummary> list() {
        Map<Long, String> datasetNames = datasets.findAll().stream()
                .collect(Collectors.toMap(DataSetDefinitionEntity::getId, DataSetDefinitionEntity::getName));
        Map<Long, Long> dataSourceCounts = members.findAll().stream()
                .filter(m -> m.getEntityId() != null && m.getDataSourceId() != null)
                .collect(Collectors.groupingBy(BusinessEntityMemberEntity::getEntityId,
                        Collectors.mapping(BusinessEntityMemberEntity::getDataSourceId,
                                Collectors.collectingAndThen(Collectors.toSet(), s -> (long) s.size()))));
        return defs.findAll(Sort.by(Sort.Direction.ASC, "name")).stream()
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
        Set<Long> dsIds = memberRows.stream().map(BusinessEntityMemberEntity::getDataSourceId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, String> dsNames = dataSources.findAllById(dsIds).stream()
                .collect(Collectors.toMap(DataSourceEntity::getId, DataSourceEntity::getName,
                        (a, b) -> a, LinkedHashMap::new));
        String datasetName = entity.getPrimaryDatasetId() == null ? null
                : datasets.findById(entity.getPrimaryDatasetId()).map(DataSetDefinitionEntity::getName).orElse(null);
        return new BusinessEntityDetail(entity, memberRows, datasetName, dsNames);
    }

    @Transactional
    public BusinessEntityDefinitionEntity create(BusinessEntityDefinitionEntity body) {
        BusinessEntityDefinitionEntity clean = cleanDefinition(body, new BusinessEntityDefinitionEntity());
        defs.findByName(clean.getName()).ifPresent(existing -> {
            throw ApiException.conflict("Business Entity name already exists: " + clean.getName());
        });
        clean.setOwnerUsername(clean.getOwnerUsername() == null ? currentUsername() : clean.getOwnerUsername());
        clean.setCreatedAt(Instant.now());
        clean.setUpdatedAt(Instant.now());
        BusinessEntityDefinitionEntity saved = defs.save(clean);
        audit.log("system", "BUSINESS_ENTITY_CREATE", "Created " + saved.getName());
        return saved;
    }

    @Transactional
    public BusinessEntityDefinitionEntity update(Long id, BusinessEntityDefinitionEntity body) {
        BusinessEntityDefinitionEntity existing = get(id);
        BusinessEntityDefinitionEntity clean = cleanDefinition(body, existing);
        defs.findByName(clean.getName()).ifPresent(other -> {
            if (!Objects.equals(other.getId(), id)) throw ApiException.conflict("Business Entity name already exists: " + clean.getName());
        });
        clean.setUpdatedAt(Instant.now());
        BusinessEntityDefinitionEntity saved = defs.save(clean);
        audit.log("system", "BUSINESS_ENTITY_UPDATE", "Updated " + saved.getName());
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        BusinessEntityDefinitionEntity existing = get(id);
        members.deleteByEntityId(id);
        defs.delete(existing);
        audit.log("system", "BUSINESS_ENTITY_DELETE", "Deleted " + existing.getName());
    }

    @Transactional
    public List<BusinessEntityMemberEntity> replaceMembers(Long entityId, List<BusinessEntityMemberEntity> incoming) {
        BusinessEntityDefinitionEntity entity = get(entityId);
        members.deleteByEntityId(entityId);
        List<BusinessEntityMemberEntity> clean = cleanMembers(entityId, incoming == null ? List.of() : incoming);
        List<BusinessEntityMemberEntity> saved = members.saveAll(clean);
        entity.setUpdatedAt(Instant.now());
        defs.save(entity);
        audit.log("system", "BUSINESS_ENTITY_MEMBERS_SAVE", "Saved " + saved.size() + " members for " + entity.getName());
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

    private BusinessEntityDefinitionEntity get(Long id) {
        return defs.findById(id).orElseThrow(() -> ApiException.notFound("Business Entity not found: " + id));
    }

    private BusinessEntityDefinitionEntity cleanDefinition(BusinessEntityDefinitionEntity source,
                                                          BusinessEntityDefinitionEntity target) {
        if (source == null) throw ApiException.bad("Business Entity body is required");
        String name = trimToNull(source.getName());
        if (name == null) throw ApiException.bad("Business Entity name is required");
        target.setName(name);
        target.setDescription(trimToNull(source.getDescription()));
        target.setDomain(trimToNull(source.getDomain()));
        target.setOwnerUsername(trimToNull(source.getOwnerUsername()));
        target.setPrimaryDatasetId(source.getPrimaryDatasetId());
        if (source.getPrimaryDatasetId() != null && !datasets.existsById(source.getPrimaryDatasetId())) {
            throw ApiException.bad("Primary DataScope blueprint not found: " + source.getPrimaryDatasetId());
        }
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
            if (raw.getDatasetId() != null && !datasets.existsById(raw.getDatasetId())) {
                throw ApiException.bad("Member DataScope blueprint not found: " + raw.getDatasetId());
            }
            if (raw.getDataSourceId() != null && !dataSources.existsById(raw.getDataSourceId())) {
                throw ApiException.bad("Member data source not found: " + raw.getDataSourceId());
            }
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

    private static String currentUsername() {
        return AccessContext.current().map(p -> p.username()).orElse("system");
    }
}
