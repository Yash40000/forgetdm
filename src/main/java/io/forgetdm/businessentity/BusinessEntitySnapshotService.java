package io.forgetdm.businessentity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.security.AccessContext;
import io.forgetdm.subset.SubsetService;
import io.forgetdm.virtualization.VirtualSnapshotEntity;
import io.forgetdm.virtualization.VirtualizationService;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class BusinessEntitySnapshotService {
    private final BusinessEntityService entities;
    private final BusinessEntitySnapshotRepository snapshots;
    private final BusinessEntitySnapshotMemberRepository snapshotMembers;
    private final DataSourceService dataSources;
    private final ConnectionFactory connections;
    private final VirtualizationService virtualization;
    private final AuditService audit;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    public BusinessEntitySnapshotService(BusinessEntityService entities,
                                         BusinessEntitySnapshotRepository snapshots,
                                         BusinessEntitySnapshotMemberRepository snapshotMembers,
                                         DataSourceService dataSources,
                                         ConnectionFactory connections,
                                         VirtualizationService virtualization,
                                         AuditService audit) {
        this.entities = entities;
        this.snapshots = snapshots;
        this.snapshotMembers = snapshotMembers;
        this.dataSources = dataSources;
        this.connections = connections;
        this.virtualization = virtualization;
        this.audit = audit;
    }

    public record SnapshotRequest(String name, String snapshotType, String captureMode, String note,
                                  Integer retentionDays, String criteria, Map<String, String> memberCriteria) {}
    public record SnapshotDetail(BusinessEntitySnapshotEntity snapshot,
                                 List<BusinessEntitySnapshotMemberEntity> members) {}
    public record RollbackRequest(Boolean dryRun, Map<Long, Long> targetVdbByDataSource,
                                  Map<Long, Long> targetVdbBySnapshot, String reason, String confirmText) {}

    public List<BusinessEntitySnapshotEntity> list(Long entityId) {
        return snapshots.findByEntityIdOrderByCreatedAtDesc(entityId);
    }

    public SnapshotDetail detail(Long snapshotId) {
        BusinessEntitySnapshotEntity snapshot = get(snapshotId);
        return new SnapshotDetail(snapshot, snapshotMembers.findBySnapshotIdOrderByIdAsc(snapshotId));
    }

    @Transactional
    public SnapshotDetail create(Long entityId, SnapshotRequest request) {
        BusinessEntityService.BusinessEntityDetail detail = entities.getDetail(entityId);
        if (detail.entity().getId() == null) throw ApiException.notFound("Business Entity not found: " + entityId);
        String mode = normalizeMode(request == null ? null : request.captureMode());
        String consistencyId = "BE-" + entityId + "-" + Instant.now().toEpochMilli();

        BusinessEntitySnapshotEntity snapshot = new BusinessEntitySnapshotEntity();
        snapshot.setEntityId(entityId);
        snapshot.setName(requiredName(request == null ? null : request.name(), detail.entity().getName() + " snapshot"));
        snapshot.setSnapshotType(blankToDefault(request == null ? null : request.snapshotType(), "ENTITY_BOOKMARK").toUpperCase(Locale.ROOT));
        snapshot.setCaptureMode(mode);
        snapshot.setConsistencyId(consistencyId);
        snapshot.setCreatedBy(currentUsername());
        snapshot.setNote(blankToNull(request == null ? null : request.note()));
        snapshot.setRetentionUntil(retentionDays(request == null ? null : request.retentionDays()));
        snapshot.setEntityJson(writeJson(detail.entity()));
        snapshot.setMemberManifestJson(writeJson(detail.members()));
        snapshot.setTotalMembers(detail.members().size());
        snapshot.setStatus("CAPTURING");
        snapshot.setCreatedAt(Instant.now());
        snapshot = snapshots.save(snapshot);

        Map<String, Long> physicalSnapshots = new LinkedHashMap<>();
        if ("PHYSICAL_SNAPSHOT".equals(mode)) {
            physicalSnapshots = capturePhysicalSnapshots(detail, snapshot, request);
        }

        List<BusinessEntitySnapshotMemberEntity> rows = new ArrayList<>();
        long totalRows = 0;
        int linked = 0;
        int countFailures = 0;
        for (BusinessEntityMemberEntity member : detail.members()) {
            BusinessEntitySnapshotMemberEntity row = new BusinessEntitySnapshotMemberEntity();
            row.setSnapshotId(snapshot.getId());
            row.setEntityMemberId(member.getId());
            row.setDataSourceId(member.getDataSourceId());
            row.setSchemaName(member.getSchemaName());
            row.setTableName(member.getTableName());
            row.setKeyColumns(member.getKeyColumns());
            String criteria = criteriaFor(member, request);
            row.setCriteria(criteria);
            Long vSnap = physicalSnapshots.get(systemKey(member.getDataSourceId(), member.getSchemaName()));
            if (vSnap != null) {
                row.setVirtualSnapshotId(vSnap);
                linked++;
            }
            CountEvidence count = countRows(member, criteria);
            row.setRowCount(count.rowCount());
            totalRows += count.rowCount();
            if (!count.success()) countFailures++;
            row.setStatus(!count.success() ? "COUNT_FAILED"
                    : vSnap == null && "PHYSICAL_SNAPSHOT".equals(mode) ? "EVIDENCE_ONLY" : "CAPTURED");
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("consistencyId", consistencyId);
            evidence.put("captureMode", mode);
            evidence.put("businessRole", String.valueOf(member.getLogicalRole()));
            evidence.put("tableAlias", String.valueOf(member.getTableAlias()));
            evidence.put("countVerified", count.success());
            if (count.message() != null) evidence.put("countMessage", count.message());
            row.setEvidenceJson(writeJson(evidence));
            rows.add(row);
        }
        rows = snapshotMembers.saveAll(rows);
        snapshot.setLinkedVirtualSnapshots(linked);
        snapshot.setTotalRows(totalRows);
        snapshot.setRollbackPlanJson(writeRollbackPlan(rows));
        snapshot.setStatus(countFailures == 0 ? "AVAILABLE" : "AVAILABLE_WITH_WARNINGS");
        snapshot.setCompletedAt(Instant.now());
        snapshot = snapshots.save(snapshot);
        audit.log("system", "BUSINESS_ENTITY_SNAPSHOT_CREATE",
                "entity=" + detail.entity().getName() + " snapshot=" + snapshot.getId()
                        + " mode=" + mode + " linked=" + linked + " countFailures=" + countFailures);
        return new SnapshotDetail(snapshot, rows);
    }

    public Map<String, Object> rollback(Long snapshotId, RollbackRequest request) {
        SnapshotDetail detail = detail(snapshotId);
        boolean dryRun = request == null || !Boolean.FALSE.equals(request.dryRun()) ? true : false;
        List<Map<String, Object>> actions = rollbackActions(detail.members(), request);
        List<Map<String, Object>> missing = actions.stream()
                .filter(a -> a.get("virtualSnapshotId") != null && a.get("targetVdbId") == null)
                .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshotId", snapshotId);
        out.put("dryRun", dryRun);
        out.put("requiredConfirmText", "ROLLBACK " + snapshotId);
        out.put("actions", actions);
        out.put("missingTargets", missing);
        if (dryRun) return out;

        String confirm = request == null ? null : request.confirmText();
        if (!Objects.equals(confirm, "ROLLBACK " + snapshotId)) {
            throw ApiException.bad("Type exactly ROLLBACK " + snapshotId + " to execute rollback.");
        }
        if (!missing.isEmpty()) {
            throw ApiException.bad("Rollback target VDB is missing for " + missing.size() + " source snapshot(s).");
        }
        Set<String> executed = new LinkedHashSet<>();
        for (Map<String, Object> action : actions) {
            Long vSnapId = (Long) action.get("virtualSnapshotId");
            Long vdbId = (Long) action.get("targetVdbId");
            if (vSnapId == null || vdbId == null) continue;
            String key = vdbId + "|" + vSnapId;
            if (!executed.add(key)) continue;
            virtualization.refresh(vdbId, vSnapId);
        }
        audit.log("system", "BUSINESS_ENTITY_ROLLBACK",
                "snapshot=" + snapshotId + " actions=" + executed.size()
                        + " reason=" + blankToNull(request == null ? null : request.reason()));
        out.put("executed", executed.size());
        out.put("dryRun", false);
        return out;
    }

    private Map<String, Long> capturePhysicalSnapshots(BusinessEntityService.BusinessEntityDetail detail,
                                                       BusinessEntitySnapshotEntity snapshot,
                                                       SnapshotRequest request) {
        Map<String, Long> out = new LinkedHashMap<>();
        Set<String> systems = new LinkedHashSet<>();
        for (BusinessEntityMemberEntity member : detail.members()) {
            if (member.getDataSourceId() != null) systems.add(systemKey(member.getDataSourceId(), member.getSchemaName()));
        }
        for (String key : systems) {
            String[] parts = key.split("\\|", 2);
            Long dsId = Long.valueOf(parts[0]);
            String schema = parts.length > 1 && !parts[1].isBlank() ? parts[1] : null;
            VirtualSnapshotEntity v = virtualization.snapshotDataSource(dsId, schema,
                    snapshot.getName() + " / " + dataSources.get(dsId).getName(),
                    "Business Entity " + detail.entity().getName() + " snapshot " + snapshot.getConsistencyId(),
                    "POOL");
            out.put(key, v.getId());
        }
        return out;
    }

    private List<Map<String, Object>> rollbackActions(List<BusinessEntitySnapshotMemberEntity> members,
                                                      RollbackRequest request) {
        Map<Long, Long> byDs = request == null || request.targetVdbByDataSource() == null ? Map.of() : request.targetVdbByDataSource();
        Map<Long, Long> bySnap = request == null || request.targetVdbBySnapshot() == null ? Map.of() : request.targetVdbBySnapshot();
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (BusinessEntitySnapshotMemberEntity member : members) {
            String key = String.valueOf(member.getVirtualSnapshotId()) + "|" + member.getDataSourceId() + "|" + member.getSchemaName();
            Map<String, Object> action = grouped.computeIfAbsent(key, k -> {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("virtualSnapshotId", member.getVirtualSnapshotId());
                a.put("dataSourceId", member.getDataSourceId());
                a.put("schemaName", member.getSchemaName());
                Long target = member.getVirtualSnapshotId() == null ? null : bySnap.get(member.getVirtualSnapshotId());
                if (target == null && member.getDataSourceId() != null) target = byDs.get(member.getDataSourceId());
                a.put("targetVdbId", target);
                a.put("tables", new ArrayList<String>());
                a.put("status", member.getVirtualSnapshotId() == null ? "NO_PHYSICAL_SNAPSHOT" : (target == null ? "NEEDS_TARGET_VDB" : "READY"));
                return a;
            });
            @SuppressWarnings("unchecked")
            List<String> tables = (List<String>) action.get("tables");
            tables.add(member.getTableName());
        }
        return new ArrayList<>(grouped.values());
    }

    private String writeRollbackPlan(List<BusinessEntitySnapshotMemberEntity> rows) {
        return writeJson(Map.of(
                "requiresExplicitConfirm", true,
                "confirmPattern", "ROLLBACK {snapshotId}",
                "physicalSnapshots", rows.stream().filter(r -> r.getVirtualSnapshotId() != null).count(),
                "members", rows.size()));
    }

    private CountEvidence countRows(BusinessEntityMemberEntity member, String criteria) {
        if (member.getDataSourceId() == null || member.getTableName() == null) {
            return new CountEvidence(0, false, "Member data source or table is not configured.");
        }
        if (criteria != null) SubsetService.guardFilter(criteria);
        try {
            DataSourceEntity ds = dataSources.get(member.getDataSourceId());
            try (Connection c = connections.open(ds); Statement st = c.createStatement()) {
                String sql = "SELECT COUNT(*) FROM " + BusinessEntitySql.name(ds, member.getSchemaName(), member.getTableName())
                        + (criteria == null || criteria.isBlank() ? "" : " WHERE " + criteria);
                try (ResultSet rs = st.executeQuery(sql)) {
                    return rs.next()
                            ? new CountEvidence(rs.getLong(1), true, null)
                            : new CountEvidence(0, false, "Count query returned no row.");
                }
            }
        } catch (Exception e) {
            String message = e.getMessage();
            if (message == null || message.isBlank()) message = e.getClass().getSimpleName();
            return new CountEvidence(0, false, message.length() > 500 ? message.substring(0, 500) : message);
        }
    }

    private record CountEvidence(long rowCount, boolean success, String message) {}

    private String criteriaFor(BusinessEntityMemberEntity member, SnapshotRequest request) {
        if (request == null) return null;
        Map<String, String> byMember = request.memberCriteria() == null ? Map.of() : request.memberCriteria();
        String v = byMember.get(member.getLogicalRole());
        if (v == null) v = byMember.get(member.getTableName());
        if (v == null) v = request.criteria();
        return blankToNull(v);
    }

    private BusinessEntitySnapshotEntity get(Long id) {
        return snapshots.findById(id).orElseThrow(() -> ApiException.notFound("Business Entity snapshot not found: " + id));
    }

    private static String normalizeMode(String mode) {
        String clean = blankToDefault(mode, "EVIDENCE_ONLY").toUpperCase(Locale.ROOT);
        if (!Set.of("EVIDENCE_ONLY", "PHYSICAL_SNAPSHOT").contains(clean)) {
            throw ApiException.bad("captureMode must be EVIDENCE_ONLY or PHYSICAL_SNAPSHOT");
        }
        return clean;
    }

    private Instant retentionDays(Integer days) {
        if (days == null || days <= 0) return null;
        return Instant.now().plus(Math.min(days, 3650), ChronoUnit.DAYS);
    }

    private String writeJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw ApiException.bad("Could not serialize snapshot evidence: " + e.getMessage()); }
    }

    private static String systemKey(Long dataSourceId, String schema) {
        return String.valueOf(dataSourceId) + "|" + String.valueOf(schema == null ? "" : schema);
    }

    private static String requiredName(String value, String fallback) {
        String clean = blankToNull(value);
        return clean == null ? fallback : clean;
    }

    private static String blankToDefault(String value, String fallback) {
        String clean = blankToNull(value);
        return clean == null ? fallback : clean;
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }

    private static String currentUsername() {
        return AccessContext.current().map(p -> p.username()).orElse("system");
    }
}
