package io.forgetdm.businessentity;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceRepository;
import io.forgetdm.security.AccessContext;
import io.forgetdm.subset.SubsetService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
public class BusinessEntitySyncService {
    private final JdbcTemplate jdbc;
    private final BusinessEntityService entities;
    private final DataSourceRepository dataSources;
    private final ConnectionFactory connections;
    private final AuditService audit;
    private final BusinessEntityCapsuleService capsules;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    public record SyncPolicyRequest(Long id, String name, String syncMode, String status, Integer maxLagSeconds,
                                    String scheduleCron, String syncStrategy, Boolean autoRefreshEnabled,
                                    String notes, List<SyncMemberRequest> members) {}
    public record SyncMemberRequest(Long id, Long memberId, String systemName, Long dataSourceId, String schemaName,
                                    String tableName, String logicalRole, String keyColumns, String watermarkColumn,
                                    Integer maxLagSeconds, String syncMode, String queryFilter) {}
    public record HeartbeatRequest(Long memberPolicyId, String sourceWatermark, String status, String message) {}

    public BusinessEntitySyncService(JdbcTemplate jdbc, BusinessEntityService entities,
                                     DataSourceRepository dataSources, ConnectionFactory connections,
                                     AuditService audit, BusinessEntityCapsuleService capsules) {
        this.jdbc = jdbc;
        this.entities = entities;
        this.dataSources = dataSources;
        this.connections = connections;
        this.audit = audit;
        this.capsules = capsules;
    }

    public List<Map<String, Object>> listPolicies(Long entityId) {
        entities.getDetail(entityId);
        return rows(policySelect() + " WHERE p.entity_id = ? ORDER BY p.updated_at DESC, p.id DESC", entityId)
                .stream().map(this::policyRow).toList();
    }

    public Map<String, Object> getPolicy(Long policyId) {
        return policyRow(rawPolicy(policyId));
    }

    @Transactional
    public Map<String, Object> savePolicy(Long entityId, SyncPolicyRequest request) {
        BusinessEntityService.BusinessEntityDetail detail = entities.getDetail(entityId);
        String name = required(request == null ? null : request.name(), "Sync policy name");
        int defaultLag = request == null || request.maxLagSeconds() == null ? 900 : Math.max(1, request.maxLagSeconds());
        Long id = request == null ? null : request.id();
        if (id == null) {
            id = insert("""
                    INSERT INTO be_sync_policies(entity_id, name, sync_mode, status, max_lag_seconds,
                        schedule_cron, sync_strategy, auto_refresh_enabled, notes, created_by, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, entityId, name, upperDefault(request.syncMode(), "POLLING"),
                    upperDefault(request.status(), "ACTIVE"), defaultLag, blank(request.scheduleCron()),
                    upperDefault(request.syncStrategy(), "FRESHNESS_CHECK"),
                    Boolean.TRUE.equals(request.autoRefreshEnabled()), blank(request.notes()),
                    currentUsername(), ts(Instant.now()));
        } else {
            Map<String, Object> existing = rawPolicy(id);
            if (!Objects.equals(num(existing.get("entityId")), entityId)) {
                throw ApiException.bad("Sync policy does not belong to this Business Entity.");
            }
            jdbc.update("""
                    UPDATE be_sync_policies
                       SET name = ?, sync_mode = ?, status = ?, max_lag_seconds = ?, schedule_cron = ?,
                           sync_strategy = ?, auto_refresh_enabled = ?, notes = ?, updated_at = ?
                     WHERE id = ?
                    """, name, upperDefault(request.syncMode(), "POLLING"), upperDefault(request.status(), "ACTIVE"),
                    defaultLag, blank(request.scheduleCron()), upperDefault(request.syncStrategy(), "FRESHNESS_CHECK"),
                    Boolean.TRUE.equals(request.autoRefreshEnabled()), blank(request.notes()), ts(Instant.now()), id);
            jdbc.update("DELETE FROM be_sync_policy_members WHERE policy_id = ?", id);
        }

        List<SyncMemberRequest> memberRequests = request == null || request.members() == null || request.members().isEmpty()
                ? defaultMemberPolicies(detail, defaultLag, request == null ? null : request.syncMode())
                : request.members();
        for (SyncMemberRequest memberRequest : memberRequests) {
            insertMemberPolicy(detail, id, defaultLag, memberRequest);
        }
        audit.log(currentUsername(), "BUSINESS_ENTITY_SYNC_POLICY_SAVE",
                "entity=" + entityId + " policy=" + id + " members=" + memberRequests.size());
        return getPolicy(id);
    }

    @Transactional
    public void deletePolicy(Long policyId) {
        Map<String, Object> policy = rawPolicy(policyId);
        jdbc.update("DELETE FROM be_sync_policies WHERE id = ?", policyId);
        audit.log(currentUsername(), "BUSINESS_ENTITY_SYNC_POLICY_DELETE",
                "policy=" + policyId + " entity=" + policy.get("entityId"));
    }

    @Transactional
    public Map<String, Object> checkFreshness(Long policyId) {
        Map<String, Object> policy = getPolicy(policyId);
        Long entityId = num(policy.get("entityId"));
        Instant started = Instant.now();
        List<Map<String, Object>> memberResults = new ArrayList<>();
        for (Map<String, Object> member : castList(policy.get("members"))) {
            Map<String, Object> result = checkMember(policy, member);
            memberResults.add(result);
            jdbc.update("""
                    UPDATE be_sync_policy_members
                       SET last_source_watermark = ?, last_checked_at = ?, last_status = ?, last_message = ?, updated_at = ?
                     WHERE id = ?
                    """, blank(String.valueOf(result.get("sourceWatermark"))), ts(started),
                    result.get("status"), result.get("message"), ts(Instant.now()), member.get("id"));
        }
        String status = aggregateStatus(memberResults);
        Map<String, Object> runResult = new LinkedHashMap<>();
        runResult.put("policyId", policyId);
        runResult.put("policyName", policy.get("name"));
        runResult.put("status", status);
        runResult.put("checkedAt", started.toString());
        runResult.put("members", memberResults);
        long runId = insert("""
                INSERT INTO be_sync_runs(entity_id, policy_id, run_type, status, result_json, created_by, completed_at)
                VALUES (?, ?, 'FRESHNESS_CHECK', ?, ?, ?, ?)
                """, entityId, policyId, status, writeJson(runResult), currentUsername(), ts(Instant.now()));
        audit.log(currentUsername(), "BUSINESS_ENTITY_SYNC_FRESHNESS_CHECK",
                "policy=" + policyId + " status=" + status + " members=" + memberResults.size());
        // Best-effort: push this check's per-member freshness onto any Micro-DB capsule instance that
        // already tracks a watermark for that member (captured at materialize time).
        try { capsules.propagateFreshness(entityId, memberResults); } catch (Exception ignored) {}
        return getRun(runId);
    }

    @Transactional
    public Map<String, Object> heartbeat(Long policyId, HeartbeatRequest request) {
        Map<String, Object> policy = getPolicy(policyId);
        Long memberPolicyId = request == null ? null : request.memberPolicyId();
        if (memberPolicyId == null) throw ApiException.bad("memberPolicyId is required");
        Map<String, Object> member = one("""
                SELECT id, policy_id AS "policyId" FROM be_sync_policy_members WHERE id = ?
                """, memberPolicyId);
        if (!Objects.equals(num(member.get("policyId")), policyId)) throw ApiException.bad("Member policy does not belong to sync policy.");
        jdbc.update("""
                UPDATE be_sync_policy_members
                   SET last_source_watermark = ?, last_checked_at = ?, last_status = ?, last_message = ?, updated_at = ?
                 WHERE id = ?
                """, required(request.sourceWatermark(), "sourceWatermark"), ts(Instant.now()),
                upperDefault(request.status(), "HEARTBEAT"), blank(request.message()), ts(Instant.now()), memberPolicyId);
        audit.log(currentUsername(), "BUSINESS_ENTITY_SYNC_HEARTBEAT",
                "policy=" + policyId + " memberPolicy=" + memberPolicyId);
        return getPolicy(policyId);
    }

    public List<Map<String, Object>> listRuns(Long policyId) {
        rawPolicy(policyId);
        return rows("""
                SELECT id, entity_id AS "entityId", policy_id AS "policyId", run_type AS "runType",
                       status, result_json AS "resultJson", created_by AS "createdBy",
                       started_at AS "startedAt", completed_at AS "completedAt"
                  FROM be_sync_runs WHERE policy_id = ? ORDER BY started_at DESC, id DESC LIMIT 20
                """, policyId).stream().map(this::runRow).toList();
    }

    private Map<String, Object> checkMember(Map<String, Object> policy, Map<String, Object> member) {
        Instant now = Instant.now();
        int maxLag = num(member.get("maxLagSeconds")) == null ? ((Number) policy.get("maxLagSeconds")).intValue()
                : num(member.get("maxLagSeconds")).intValue();
        String watermarkColumn = blank((String) member.get("watermarkColumn"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("memberPolicyId", member.get("id"));
        out.put("memberId", member.get("memberId"));
        out.put("systemName", member.get("systemName"));
        out.put("tableName", member.get("tableName"));
        out.put("watermarkColumn", watermarkColumn);
        out.put("maxLagSeconds", maxLag);
        if (watermarkColumn == null) {
            return unknown(out, "No watermark column configured; freshness is policy-only until a heartbeat or source watermark is configured.");
        }
        Long dataSourceId = num(member.get("dataSourceId"));
        if (dataSourceId == null) return unknown(out, "No data source is configured for this member.");
        try {
            DataSourceEntity ds = entities.requireAuthorizedDataSource(dataSourceId);
            Object value = readSourceWatermark(ds, member, watermarkColumn);
            Instant sourceTime = toInstant(value);
            out.put("sourceWatermark", value == null ? null : String.valueOf(value));
            if (sourceTime == null) {
                return unknown(out, "Watermark value is empty or not timestamp-like.");
            }
            long lagSeconds = Math.max(0, Duration.between(sourceTime, now).getSeconds());
            out.put("sourceWatermarkInstant", sourceTime.toString());
            out.put("lagSeconds", lagSeconds);
            if (lagSeconds <= maxLag) {
                out.put("status", "FRESH");
                out.put("message", "Source watermark is within SLA.");
            } else {
                out.put("status", "STALE");
                out.put("message", "Source watermark is older than SLA by " + (lagSeconds - maxLag) + " seconds.");
            }
            return out;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            out.put("status", "FAILED");
            out.put("message", e.getMessage());
            return out;
        }
    }

    private Object readSourceWatermark(DataSourceEntity ds, Map<String, Object> member, String watermarkColumn) throws Exception {
        String schema = blank((String) member.get("schemaName"));
        String table = required((String) member.get("tableName"), "Sync member table name");
        String filter = blank((String) member.get("queryFilter"));
        if (filter != null) SubsetService.guardFilter(filter);
        String sql = "SELECT MAX(" + BusinessEntitySql.identifier(ds, watermarkColumn) + ") FROM "
                + BusinessEntitySql.name(ds, schema, table)
                + (filter == null ? "" : " WHERE " + filter);
        try (Connection c = connections.openPooled(ds);
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getObject(1) : null;
        }
    }

    private Map<String, Object> unknown(Map<String, Object> out, String message) {
        out.put("status", "UNKNOWN");
        out.put("message", message);
        return out;
    }

    private String aggregateStatus(List<Map<String, Object>> results) {
        if (results.isEmpty()) return "UNKNOWN";
        boolean failed = false, stale = false, unknown = false;
        for (Map<String, Object> result : results) {
            String status = String.valueOf(result.get("status")).toUpperCase(Locale.ROOT);
            if ("FAILED".equals(status)) failed = true;
            else if ("STALE".equals(status)) stale = true;
            else if ("UNKNOWN".equals(status)) unknown = true;
        }
        if (failed) return "FAILED";
        if (stale) return "STALE";
        if (unknown) return results.stream().anyMatch(r -> "FRESH".equals(r.get("status"))) ? "PARTIAL" : "UNKNOWN";
        return "FRESH";
    }

    private void insertMemberPolicy(BusinessEntityService.BusinessEntityDetail detail, Long policyId,
                                    int defaultLag, SyncMemberRequest req) {
        BusinessEntityMemberEntity member = null;
        if (req.memberId() != null) {
            member = detail.members().stream()
                    .filter(m -> Objects.equals(m.getId(), req.memberId()))
                    .findFirst().orElseThrow(() -> ApiException.bad("Business Entity member not found: " + req.memberId()));
            assertMemberCoordinates(member, req);
        } else {
            member = detail.members().stream()
                    .filter(m -> req.dataSourceId() == null || Objects.equals(m.getDataSourceId(), req.dataSourceId()))
                    .filter(m -> blank(req.systemName()) == null || same(m.getSystemName(), req.systemName()))
                    .filter(m -> blank(req.schemaName()) == null || same(m.getSchemaName(), req.schemaName()))
                    .filter(m -> blank(req.tableName()) == null || same(m.getTableName(), req.tableName()))
                    .filter(m -> blank(req.logicalRole()) == null || same(m.getLogicalRole(), req.logicalRole()))
                    .findFirst().orElseThrow(() -> ApiException.bad(
                            "Sync policy member must reference a member of this Business Entity."));
        }
        String table = required(firstNonBlank(req.tableName(), member == null ? null : member.getTableName()), "Sync member table");
        jdbc.update("""
                INSERT INTO be_sync_policy_members(policy_id, entity_id, member_id, system_name, data_source_id,
                    schema_name, table_name, logical_role, key_columns, watermark_column, max_lag_seconds,
                    sync_mode, query_filter, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, policyId, detail.entity().getId(), member == null ? req.memberId() : member.getId(),
                firstNonBlank(req.systemName(), member == null ? null : member.getSystemName()),
                req.dataSourceId() != null ? req.dataSourceId() : member == null ? null : member.getDataSourceId(),
                firstNonBlank(req.schemaName(), member == null ? null : member.getSchemaName()),
                table,
                firstNonBlank(req.logicalRole(), member == null ? null : member.getLogicalRole()),
                firstNonBlank(req.keyColumns(), member == null ? null : member.getKeyColumns()),
                blank(req.watermarkColumn()),
                req.maxLagSeconds() == null ? defaultLag : Math.max(1, req.maxLagSeconds()),
                upperDefault(req.syncMode(), "POLLING"),
                blank(req.queryFilter()), ts(Instant.now()));
    }

    private void assertMemberCoordinates(BusinessEntityMemberEntity member, SyncMemberRequest request) {
        if (request.dataSourceId() != null && !Objects.equals(request.dataSourceId(), member.getDataSourceId())) {
            throw ApiException.bad("Sync policy data source does not match the selected Business Entity member.");
        }
        assertSameMemberValue("system", request.systemName(), member.getSystemName());
        assertSameMemberValue("schema", request.schemaName(), member.getSchemaName());
        assertSameMemberValue("table", request.tableName(), member.getTableName());
        assertSameMemberValue("logical role", request.logicalRole(), member.getLogicalRole());
        assertSameMemberValue("key columns", request.keyColumns(), member.getKeyColumns());
    }

    private void assertSameMemberValue(String label, String supplied, String expected) {
        if (blank(supplied) != null && !same(supplied, expected)) {
            throw ApiException.bad("Sync policy " + label + " does not match the selected Business Entity member.");
        }
    }

    private List<SyncMemberRequest> defaultMemberPolicies(BusinessEntityService.BusinessEntityDetail detail,
                                                         int defaultLag, String mode) {
        return detail.members().stream()
                .filter(BusinessEntityMemberEntity::isIncludeInSubset)
                .map(m -> new SyncMemberRequest(null, m.getId(), m.getSystemName(), m.getDataSourceId(),
                        m.getSchemaName(), m.getTableName(), m.getLogicalRole(), m.getKeyColumns(),
                        null, defaultLag, mode, null))
                .toList();
    }

    private Map<String, Object> policyRow(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>(row);
        out.put("members", rows("""
                SELECT id, policy_id AS "policyId", entity_id AS "entityId", member_id AS "memberId",
                       system_name AS "systemName", data_source_id AS "dataSourceId", schema_name AS "schemaName",
                       table_name AS "tableName", logical_role AS "logicalRole", key_columns AS "keyColumns",
                       watermark_column AS "watermarkColumn", max_lag_seconds AS "maxLagSeconds",
                       sync_mode AS "syncMode", query_filter AS "queryFilter",
                       last_source_watermark AS "lastSourceWatermark", last_checked_at AS "lastCheckedAt",
                       last_status AS "lastStatus", last_message AS "lastMessage", updated_at AS "updatedAt"
                  FROM be_sync_policy_members WHERE policy_id = ? ORDER BY id
                """, row.get("id")));
        out.put("runs", listRunsRaw(num(row.get("id"))));
        return out;
    }

    private List<Map<String, Object>> listRunsRaw(Long policyId) {
        return rows("""
                SELECT id, entity_id AS "entityId", policy_id AS "policyId", run_type AS "runType",
                       status, result_json AS "resultJson", created_by AS "createdBy",
                       started_at AS "startedAt", completed_at AS "completedAt"
                  FROM be_sync_runs WHERE policy_id = ? ORDER BY started_at DESC, id DESC LIMIT 5
                """, policyId).stream().map(this::runRow).toList();
    }

    private Map<String, Object> runRow(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>(row);
        out.put("result", readJson(row.get("resultJson")));
        return out;
    }

    private Map<String, Object> getRun(Long runId) {
        return runRow(one("""
                SELECT id, entity_id AS "entityId", policy_id AS "policyId", run_type AS "runType",
                       status, result_json AS "resultJson", created_by AS "createdBy",
                       started_at AS "startedAt", completed_at AS "completedAt"
                  FROM be_sync_runs WHERE id = ?
                """, runId));
    }

    private Map<String, Object> rawPolicy(Long id) {
        Map<String, Object> policy = one(policySelect() + " WHERE p.id = ?", id);
        entities.getDetail(num(policy.get("entityId")));
        return policy;
    }

    private String policySelect() {
        return """
                SELECT p.id, p.entity_id AS "entityId", p.name, p.sync_mode AS "syncMode",
                       p.status, p.max_lag_seconds AS "maxLagSeconds", p.schedule_cron AS "scheduleCron",
                       p.sync_strategy AS "syncStrategy", p.auto_refresh_enabled AS "autoRefreshEnabled",
                       p.notes, p.created_by AS "createdBy", p.created_at AS "createdAt",
                       p.updated_at AS "updatedAt"
                  FROM be_sync_policies p
                """;
    }

    static Instant toInstant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant i) return i;
        if (value instanceof Timestamp t) return t.toInstant();
        if (value instanceof java.sql.Date d) return d.toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant();
        if (value instanceof java.util.Date d) return d.toInstant();
        if (value instanceof OffsetDateTime o) return o.toInstant();
        if (value instanceof LocalDateTime l) return l.atZone(ZoneId.systemDefault()).toInstant();
        if (value instanceof LocalDate l) return l.atStartOfDay(ZoneId.systemDefault()).toInstant();
        try { return Timestamp.valueOf(String.valueOf(value)).toLocalDateTime().atZone(ZoneId.systemDefault()).toInstant(); } catch (Exception ignored) {}
        try { return Instant.parse(String.valueOf(value)); } catch (Exception ignored) {}
        try { return OffsetDateTime.parse(String.valueOf(value)).toInstant(); } catch (Exception ignored) {}
        try { return LocalDateTime.parse(String.valueOf(value)).atZone(ZoneId.systemDefault()).toInstant(); } catch (Exception ignored) {}
        return null;
    }

    private Map<String, Object> readJson(Object value) {
        try {
            if (value == null) return Map.of();
            return json.readValue(String.valueOf(value), Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String writeJson(Object value) {
        try { return json.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception e) { throw ApiException.bad("Could not write sync JSON: " + e.getMessage()); }
    }

    private long insert(String sql, Object... args) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, key);
        Number n = key.getKeyList().isEmpty() ? null : (Number) key.getKeyList().get(0).get("id");
        if (n == null) n = key.getKey();
        if (n == null) throw ApiException.bad("Insert did not return an id");
        return n.longValue();
    }

    private List<Map<String, Object>> rows(String sql, Object... args) {
        return jdbc.queryForList(sql, args);
    }

    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = rows(sql, args);
        if (rows.isEmpty()) throw ApiException.notFound("Business Entity sync object not found");
        return rows.get(0);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object value) {
        if (value instanceof List<?> list) return (List<Map<String, Object>>) list;
        return List.of();
    }

    private static Timestamp ts(Instant i) { return i == null ? null : Timestamp.from(i); }
    private static Long num(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(value).trim()); } catch (Exception e) { return null; }
    }
    private static String required(String value, String label) {
        String clean = blank(value);
        if (clean == null) throw ApiException.bad(label + " is required");
        return clean;
    }
    private static String upperDefault(String value, String fallback) {
        String clean = blank(value);
        return (clean == null ? fallback : clean).toUpperCase(Locale.ROOT);
    }
    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            String clean = blank(value);
            if (clean != null) return clean;
        }
        return null;
    }
    private static String blank(String value) {
        if (value == null) return null;
        String clean = value.trim();
        return clean.isEmpty() ? null : clean;
    }
    private static boolean same(String left, String right) {
        return Objects.equals(blank(left) == null ? "" : blank(left).toLowerCase(Locale.ROOT),
                blank(right) == null ? "" : blank(right).toLowerCase(Locale.ROOT));
    }
    private static String currentUsername() {
        return AccessContext.current().map(p -> p.username()).orElse("system");
    }
}
