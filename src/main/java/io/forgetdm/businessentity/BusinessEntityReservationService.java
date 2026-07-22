package io.forgetdm.businessentity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.GovernedReferenceGuard;
import io.forgetdm.subset.SubsetService;
import jakarta.transaction.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class BusinessEntityReservationService {
    private final BusinessEntityService entities;
    private final BusinessEntityReservationRepository reservations;
    private final BusinessEntityReservationMemberRepository reservationMembers;
    private final BusinessEntitySnapshotRepository snapshots;
    private final DataSourceService dataSources;
    private final ConnectionFactory connections;
    private final AuditService audit;
    private final BusinessEntityCapsuleService capsules;
    private final GovernedReferenceGuard references;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    public BusinessEntityReservationService(BusinessEntityService entities,
                                            BusinessEntityReservationRepository reservations,
                                            BusinessEntityReservationMemberRepository reservationMembers,
                                            BusinessEntitySnapshotRepository snapshots,
                                            DataSourceService dataSources,
                                            ConnectionFactory connections,
                                            AuditService audit,
                                            BusinessEntityCapsuleService capsules,
                                            GovernedReferenceGuard references) {
        this.entities = entities;
        this.reservations = reservations;
        this.reservationMembers = reservationMembers;
        this.snapshots = snapshots;
        this.dataSources = dataSources;
        this.connections = connections;
        this.audit = audit;
        this.capsules = capsules;
        this.references = references;
    }

    public record ReservationRequest(String name, Long snapshotId, String reservedBy, String ownerGroup,
                                     String purpose, String environment, String criteria, Integer count,
                                     Integer ttlHours, String conflictPolicy, List<Map<String, Object>> businessKeys,
                                     Map<String, String> memberCriteria,
                                     Boolean materializeCapsules, Long capsulePolicyId) {}
    public record ReservationDetail(BusinessEntityReservationEntity reservation,
                                    List<BusinessEntityReservationMemberEntity> members) {}

    public List<BusinessEntityReservationEntity> list(Long entityId) {
        entities.getDetail(entityId);
        return reservations.findByEntityIdOrderByCreatedAtDesc(entityId);
    }

    public ReservationDetail detail(Long reservationId) {
        BusinessEntityReservationEntity r = get(reservationId);
        return new ReservationDetail(r, reservationMembers.findByReservationIdOrderByIdAsc(reservationId));
    }

    @Transactional
    public ReservationDetail reserve(Long entityId, ReservationRequest request) {
        BusinessEntityService.BusinessEntityDetail detail = entities.getDetail(entityId);
        if (detail.members().isEmpty()) throw ApiException.bad("Business Entity has no member tables to reserve.");
        int count = Math.max(1, request == null || request.count() == null ? 1 : request.count());
        String conflictPolicy = blankToDefault(request == null ? null : request.conflictPolicy(), "BLOCK").toUpperCase(Locale.ROOT);
        if (!"BLOCK".equals(conflictPolicy)) throw ApiException.bad("Only BLOCK conflict policy is supported for entity reservations.");
        String criteria = blankToNull(request == null ? null : request.criteria());
        if (criteria != null) SubsetService.guardFilter(criteria);
        if (request != null && request.snapshotId() != null) {
            BusinessEntitySnapshotEntity snapshot = snapshots.findById(request.snapshotId())
                    .orElseThrow(() -> ApiException.bad("Business Entity snapshot not found: " + request.snapshotId()));
            entities.getDetail(snapshot.getEntityId());
            if (!Objects.equals(snapshot.getEntityId(), entityId)) {
                throw ApiException.bad("Snapshot does not belong to this Business Entity.");
            }
        }
        if (request != null && Boolean.TRUE.equals(request.materializeCapsules())) {
            references.policy(request.capsulePolicyId());
        }

        BusinessEntityMemberEntity root = rootMember(detail);
        if (blankToNull(root.getKeyColumns()) == null && detail.entity().getBusinessKeyColumns() != null) {
            root.setKeyColumns(detail.entity().getBusinessKeyColumns());
        }
        List<Map<String, Object>> businessKeys = request != null && request.businessKeys() != null && !request.businessKeys().isEmpty()
                ? request.businessKeys()
                : pickKeys(root, criteriaFor(root, request), count);
        if (businessKeys.size() < count) {
            throw ApiException.conflict("Only " + businessKeys.size() + " unreserved entity key(s) found; requested " + count + ".");
        }
        assertNoActiveConflict(entityId, businessKeys);

        BusinessEntityReservationEntity r = new BusinessEntityReservationEntity();
        r.setEntityId(entityId);
        r.setSnapshotId(request == null ? null : request.snapshotId());
        r.setName(blankToNull(request == null ? null : request.name()));
        r.setReservedBy(currentUsername());
        r.setOwnerGroup(currentGroupName());
        r.setPurpose(blankToNull(request == null ? null : request.purpose()));
        r.setEnvironment(blankToNull(request == null ? null : request.environment()));
        r.setCriteria(criteria);
        r.setRequestedCount(count);
        r.setConflictPolicy(conflictPolicy);
        r.setBusinessKeyValuesJson(writeJson(businessKeys));
        int ttl = request == null || request.ttlHours() == null ? 24 : request.ttlHours();
        r.setExpiresAt(Instant.now().plus(Math.max(1, Math.min(ttl, 24 * 365)), ChronoUnit.HOURS));
        r.setStatus("ACTIVE");
        r = reservations.save(r);

        List<BusinessEntityReservationMemberEntity> memberRows = new ArrayList<>();
        for (BusinessEntityMemberEntity member : detail.members()) {
            BusinessEntityReservationMemberEntity row = new BusinessEntityReservationMemberEntity();
            row.setReservationId(r.getId());
            row.setEntityMemberId(member.getId());
            row.setDataSourceId(member.getDataSourceId());
            row.setSchemaName(member.getSchemaName());
            row.setTableName(member.getTableName());
            row.setKeyColumns(keyColumns(member, detail.entity()));
            row.setCriteria(criteriaFor(member, request));
            List<Map<String, Object>> keys = member.getId() != null && Objects.equals(member.getId(), root.getId())
                    ? businessKeys
                    : safePickKeys(member, row.getCriteria(), count);
            row.setRowKeysJson(writeJson(keys));
            row.setRowCount(keys.size());
            row.setStatus(keys.isEmpty() ? "NO_KEYS_CAPTURED" : "RESERVED");
            memberRows.add(row);
        }
        memberRows = reservationMembers.saveAll(memberRows);
        audit.log(r.getReservedBy(), "BUSINESS_ENTITY_RESERVED",
                "entity=" + detail.entity().getName() + " reservation=" + r.getId()
                        + " keys=" + businessKeys.size() + " expires=" + r.getExpiresAt());
        // Opt-in: materialize (or refresh) a Micro-DB capsule for each reserved business key, so the
        // reserved entity is immediately available as a governed, reusable row source. Best-effort —
        // a capture failure never breaks the reservation itself.
        if (request != null && Boolean.TRUE.equals(request.materializeCapsules())) {
            for (Map<String, Object> key : businessKeys) {
                capsules.materializeForSystem(entityId, key, request.capsulePolicyId(),
                        "auto-materialized by reservation #" + r.getId());
            }
        }
        // Best-effort: if a Micro-DB capsule already exists for any of these business keys, record this
        // reservation on its lineage trail. Never creates a new capsule unless opted in above.
        capsules.recordLineageForKeys(entityId, businessKeys, "RESERVED", "reservation=" + r.getId());
        return new ReservationDetail(r, memberRows);
    }

    @Transactional
    public ReservationDetail release(Long id) {
        BusinessEntityReservationEntity r = get(id);
        r.setStatus("RELEASED");
        r.setReleasedAt(Instant.now());
        r = reservations.save(r);
        audit.log(r.getReservedBy(), "BUSINESS_ENTITY_RESERVATION_RELEASED", "reservation=" + id);
        return detail(r.getId());
    }

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void expireStale() {
        List<BusinessEntityReservationEntity> active = reservations.findByStatus("ACTIVE");
        active.forEach(r -> entities.getDetail(r.getEntityId()));
        for (BusinessEntityReservationEntity r : active) {
            if (r.getExpiresAt() != null && r.getExpiresAt().isBefore(Instant.now())) {
                r.setStatus("EXPIRED");
                r.setReleasedAt(Instant.now());
                reservations.save(r);
                audit.log("system", "BUSINESS_ENTITY_RESERVATION_EXPIRED", "reservation=" + r.getId());
            }
        }
    }

    private void assertNoActiveConflict(Long entityId, List<Map<String, Object>> requested) {
        Set<String> requestedKeys = canonicalSet(requested);
        for (BusinessEntityReservationEntity existing : reservations.findByEntityIdAndStatus(entityId, "ACTIVE")) {
            if (existing.getExpiresAt() != null && existing.getExpiresAt().isBefore(Instant.now())) continue;
            Set<String> active = canonicalSet(readKeys(existing.getBusinessKeyValuesJson()));
            active.retainAll(requestedKeys);
            if (!active.isEmpty()) {
                throw ApiException.conflict("Entity key(s) already reserved by " + existing.getReservedBy()
                        + " in reservation #" + existing.getId() + ": " + String.join(", ", active));
            }
        }
    }

    private BusinessEntityMemberEntity rootMember(BusinessEntityService.BusinessEntityDetail detail) {
        String root = detail.entity().getRootTable();
        if (root != null) {
            for (BusinessEntityMemberEntity member : detail.members()) {
                if (root.equalsIgnoreCase(member.getTableName())) return member;
            }
        }
        return detail.members().get(0);
    }

    private List<Map<String, Object>> safePickKeys(BusinessEntityMemberEntity member, String criteria, int count) {
        try { return pickKeys(member, criteria, count); }
        catch (Exception e) { return List.of(); }
    }

    private List<Map<String, Object>> pickKeys(BusinessEntityMemberEntity member, String criteria, int count) {
        String keyColumns = keyColumns(member, null);
        if (keyColumns == null) throw ApiException.bad("Key columns are required for member table " + member.getTableName()
                + ". Add key columns on the Business Entity member before reserving.");
        if (member.getDataSourceId() == null) throw ApiException.bad("Data source is required for member table " + member.getTableName());
        if (criteria != null) SubsetService.guardFilter(criteria);
        List<String> cols = splitColumns(keyColumns);
        List<Map<String, Object>> out = new ArrayList<>();
        DataSourceEntity ds = dataSources.get(member.getDataSourceId());
        try (Connection c = connections.open(ds); Statement st = c.createStatement()) {
            st.setMaxRows(Math.max(count * 10, 100));
            String sql = "SELECT " + cols.stream().map(column -> BusinessEntitySql.identifier(ds, column)).reduce((a, b) -> a + ", " + b).orElseThrow()
                    + " FROM " + BusinessEntitySql.name(ds, member.getSchemaName(), member.getTableName())
                    + (criteria == null || criteria.isBlank() ? "" : " WHERE " + criteria);
            try (ResultSet rs = st.executeQuery(sql)) {
                while (rs.next() && out.size() < count) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 0; i < cols.size(); i++) row.put(cols.get(i), rs.getObject(i + 1));
                    out.add(row);
                }
            }
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("Could not reserve keys from " + member.getTableName() + ": " + e.getMessage()); }
        return out;
    }

    private String keyColumns(BusinessEntityMemberEntity member, BusinessEntityDefinitionEntity entity) {
        String cols = blankToNull(member.getKeyColumns());
        if (cols == null && entity != null && entity.getRootTable() != null
                && entity.getRootTable().equalsIgnoreCase(member.getTableName())) {
            cols = blankToNull(entity.getBusinessKeyColumns());
        }
        return cols;
    }

    private String criteriaFor(BusinessEntityMemberEntity member, ReservationRequest request) {
        if (request == null) return null;
        Map<String, String> byMember = request.memberCriteria() == null ? Map.of() : request.memberCriteria();
        String v = byMember.get(member.getLogicalRole());
        if (v == null) v = byMember.get(member.getTableName());
        if (v == null) v = request.criteria();
        return blankToNull(v);
    }

    private BusinessEntityReservationEntity get(Long id) {
        BusinessEntityReservationEntity reservation = reservations.findById(id)
                .orElseThrow(() -> ApiException.notFound("Business Entity reservation not found: " + id));
        entities.getDetail(reservation.getEntityId());
        return reservation;
    }

    private List<Map<String, Object>> readKeys(String raw) {
        try { return json.readValue(raw, new TypeReference<List<Map<String, Object>>>() {}); }
        catch (Exception e) { return List.of(); }
    }

    private Set<String> canonicalSet(List<Map<String, Object>> rows) {
        Set<String> out = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) out.add(canonical(row));
        return out;
    }

    private String canonical(Map<String, Object> row) {
        return row.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey().toLowerCase(Locale.ROOT) + "=" + String.valueOf(e.getValue()))
                .reduce((a, b) -> a + "|" + b)
                .orElse("");
    }

    private String writeJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw ApiException.bad("Could not serialize reservation evidence: " + e.getMessage()); }
    }

    private static List<String> splitColumns(String columns) {
        return Arrays.stream(columns.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
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

    private static String currentGroupName() {
        return AccessContext.current().stream()
                .flatMap(p -> p.groups() == null ? java.util.stream.Stream.empty() : p.groups().stream())
                .map(g -> g.name())
                .filter(Objects::nonNull)
                .findFirst().orElse(null);
    }
}
