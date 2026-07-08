package io.forgetdm.businessentity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.security.AccessContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class BusinessEntityIdentityService {
    private final JdbcTemplate jdbc;
    private final BusinessEntityService entities;
    private final AuditService audit;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    public record CrosswalkRequest(String canonicalKey, String identityType, String status,
                                   Double confidence, Map<String, Object> attributes,
                                   List<LinkRequest> links) {}
    public record LinkRequest(Long memberId, String systemName, Long dataSourceId, String schemaName,
                              String tableName, String logicalRole, String keyColumns,
                              Map<String, Object> keyValues, String externalId, Double confidence,
                              String matchRule, String status, String source) {}
    public record ResolveRequest(Long memberId, String systemName, Long dataSourceId, String schemaName,
                                 String tableName, String keyColumns, Map<String, Object> keyValues,
                                 String externalId) {}

    public BusinessEntityIdentityService(JdbcTemplate jdbc, BusinessEntityService entities, AuditService audit) {
        this.jdbc = jdbc;
        this.entities = entities;
        this.audit = audit;
    }

    public List<Map<String, Object>> list(Long entityId, String query) {
        entities.getDetail(entityId);
        String q = blank(query);
        List<Map<String, Object>> subjects = q == null
                ? rows(subjectSelect() + " WHERE s.entity_id = ? ORDER BY s.updated_at DESC, s.id DESC LIMIT 100", entityId)
                : rows(subjectSelect() + """
                     WHERE s.entity_id = ?
                       AND (LOWER(s.canonical_key) LIKE ? OR EXISTS (
                         SELECT 1 FROM be_identity_links l
                          WHERE l.subject_id = s.id
                            AND (LOWER(l.external_id) LIKE ? OR LOWER(COALESCE(l.system_name,'')) LIKE ?
                                 OR LOWER(l.table_name) LIKE ? OR LOWER(COALESCE(l.logical_role,'')) LIKE ?)
                       ))
                     ORDER BY s.updated_at DESC, s.id DESC LIMIT 100
                    """, entityId, like(q), like(q), like(q), like(q), like(q));
        return subjects.stream().map(this::withLinks).toList();
    }

    public Map<String, Object> get(Long subjectId) {
        return withLinks(subject(subjectId));
    }

    @Transactional
    public Map<String, Object> upsert(Long entityId, CrosswalkRequest request) {
        BusinessEntityService.BusinessEntityDetail detail = entities.getDetail(entityId);
        String canonicalKey = required(request == null ? null : request.canonicalKey(), "Canonical key");
        String status = upperDefault(request == null ? null : request.status(), "ACTIVE");
        String identityType = upperDefault(request == null ? null : request.identityType(), "BUSINESS_ENTITY");
        double confidence = clamp(request == null ? null : request.confidence());
        Long subjectId = findSubjectId(entityId, canonicalKey);
        if (subjectId == null) {
            subjectId = insert("""
                    INSERT INTO be_identity_subjects(entity_id, canonical_key, identity_type, status, confidence,
                        attributes_json, created_by, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, entityId, canonicalKey, identityType, status, confidence,
                    writeJson(request == null || request.attributes() == null ? Map.of() : request.attributes()),
                    currentUsername(), ts(Instant.now()));
        } else {
            jdbc.update("""
                    UPDATE be_identity_subjects
                       SET identity_type = ?, status = ?, confidence = ?, attributes_json = ?, updated_at = ?
                     WHERE id = ?
                    """, identityType, status, confidence,
                    writeJson(request == null || request.attributes() == null ? Map.of() : request.attributes()),
                    ts(Instant.now()), subjectId);
        }
        for (LinkRequest link : request == null || request.links() == null ? List.<LinkRequest>of() : request.links()) {
            upsertLink(detail, subjectId, link);
        }
        audit.log(currentUsername(), "BUSINESS_ENTITY_IDENTITY_UPSERT",
                "entity=" + entityId + " canonical=" + canonicalKey + " links=" + (request == null || request.links() == null ? 0 : request.links().size()));
        return get(subjectId);
    }

    @Transactional
    public Map<String, Object> addLink(Long entityId, Long subjectId, LinkRequest request) {
        BusinessEntityService.BusinessEntityDetail detail = entities.getDetail(entityId);
        Map<String, Object> subject = subject(subjectId);
        if (!Objects.equals(num(subject.get("entityId")), entityId)) throw ApiException.bad("Identity does not belong to this Business Entity.");
        upsertLink(detail, subjectId, request);
        return get(subjectId);
    }

    public Map<String, Object> resolve(Long entityId, ResolveRequest request) {
        BusinessEntityService.BusinessEntityDetail detail = entities.getDetail(entityId);
        LinkRequest link = new LinkRequest(request == null ? null : request.memberId(),
                request == null ? null : request.systemName(),
                request == null ? null : request.dataSourceId(),
                request == null ? null : request.schemaName(),
                request == null ? null : request.tableName(),
                null,
                request == null ? null : request.keyColumns(),
                request == null ? null : request.keyValues(),
                request == null ? null : request.externalId(),
                null, null, null, null);
        LinkCandidate candidate = linkCandidate(detail, link);
        List<Map<String, Object>> matches = rows("""
                SELECT subject_id AS "subjectId" FROM be_identity_links
                 WHERE entity_id = ? AND identity_key_hash = ? AND status = 'ACTIVE'
                 ORDER BY updated_at DESC, id DESC LIMIT 1
                """, entityId, candidate.hash());
        if (matches.isEmpty()) {
            return Map.of("matched", false, "lookup", candidate.evidence(), "message", "No identity crosswalk match found.");
        }
        Map<String, Object> crosswalk = get(num(matches.get(0).get("subjectId")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("matched", true);
        out.put("lookup", candidate.evidence());
        out.put("crosswalk", crosswalk);
        return out;
    }

    @Transactional
    public void deleteLink(Long linkId) {
        Map<String, Object> link = one("SELECT id, subject_id AS \"subjectId\", entity_id AS \"entityId\" FROM be_identity_links WHERE id = ?", linkId);
        jdbc.update("DELETE FROM be_identity_links WHERE id = ?", linkId);
        jdbc.update("UPDATE be_identity_subjects SET updated_at = ? WHERE id = ?", ts(Instant.now()), link.get("subjectId"));
        audit.log(currentUsername(), "BUSINESS_ENTITY_IDENTITY_LINK_DELETE", "link=" + linkId + " entity=" + link.get("entityId"));
    }

    @Transactional
    public void deleteSubject(Long subjectId) {
        Map<String, Object> subject = subject(subjectId);
        jdbc.update("DELETE FROM be_identity_subjects WHERE id = ?", subjectId);
        audit.log(currentUsername(), "BUSINESS_ENTITY_IDENTITY_DELETE",
                "subject=" + subjectId + " canonical=" + subject.get("canonicalKey"));
    }

    private void upsertLink(BusinessEntityService.BusinessEntityDetail detail, Long subjectId, LinkRequest request) {
        LinkCandidate candidate = linkCandidate(detail, request);
        List<Map<String, Object>> existing = rows("""
                SELECT id, subject_id AS "subjectId" FROM be_identity_links
                 WHERE entity_id = ? AND identity_key_hash = ?
                """, detail.entity().getId(), candidate.hash());
        if (!existing.isEmpty() && !Objects.equals(num(existing.get(0).get("subjectId")), subjectId)) {
            throw ApiException.conflict("This system identity is already linked to another canonical key.");
        }
        if (existing.isEmpty()) {
            insert("""
                    INSERT INTO be_identity_links(entity_id, subject_id, member_id, system_name, data_source_id,
                        schema_name, table_name, logical_role, key_columns, key_values_json, external_id,
                        identity_key_hash, confidence, match_rule, status, source, created_by, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, detail.entity().getId(), subjectId, candidate.memberId(), candidate.systemName(),
                    candidate.dataSourceId(), candidate.schemaName(), candidate.tableName(), candidate.logicalRole(),
                    candidate.keyColumns(), writeJson(candidate.keyValues()), candidate.externalId(), candidate.hash(),
                    clamp(request == null ? null : request.confidence()),
                    blank(request == null ? null : request.matchRule()),
                    upperDefault(request == null ? null : request.status(), "ACTIVE"),
                    blank(request == null ? null : request.source()),
                    currentUsername(), ts(Instant.now()));
        } else {
            jdbc.update("""
                    UPDATE be_identity_links
                       SET subject_id = ?, member_id = ?, system_name = ?, data_source_id = ?, schema_name = ?,
                           table_name = ?, logical_role = ?, key_columns = ?, key_values_json = ?, external_id = ?,
                           confidence = ?, match_rule = ?, status = ?, source = ?, updated_at = ?
                     WHERE id = ?
                    """, subjectId, candidate.memberId(), candidate.systemName(), candidate.dataSourceId(),
                    candidate.schemaName(), candidate.tableName(), candidate.logicalRole(), candidate.keyColumns(),
                    writeJson(candidate.keyValues()), candidate.externalId(),
                    clamp(request == null ? null : request.confidence()),
                    blank(request == null ? null : request.matchRule()),
                    upperDefault(request == null ? null : request.status(), "ACTIVE"),
                    blank(request == null ? null : request.source()), ts(Instant.now()), existing.get(0).get("id"));
        }
        jdbc.update("UPDATE be_identity_subjects SET updated_at = ? WHERE id = ?", ts(Instant.now()), subjectId);
    }

    private LinkCandidate linkCandidate(BusinessEntityService.BusinessEntityDetail detail, LinkRequest request) {
        if (request == null) throw ApiException.bad("Identity link is required");
        BusinessEntityMemberEntity member = null;
        if (request.memberId() != null) {
            member = detail.members().stream()
                    .filter(m -> Objects.equals(m.getId(), request.memberId()))
                    .findFirst().orElseThrow(() -> ApiException.bad("Business Entity member not found: " + request.memberId()));
        }
        String table = required(firstNonBlank(request.tableName(), member == null ? null : member.getTableName()), "Link table name");
        String system = firstNonBlank(request.systemName(), member == null ? null : member.getSystemName(),
                member == null || member.getDataSourceId() == null ? null : detail.dataSourceNames().get(member.getDataSourceId()));
        Long dataSourceId = request.dataSourceId() != null ? request.dataSourceId() : member == null ? null : member.getDataSourceId();
        String schema = firstNonBlank(request.schemaName(), member == null ? null : member.getSchemaName());
        String role = firstNonBlank(request.logicalRole(), member == null ? null : member.getLogicalRole());
        String keyColumns = firstNonBlank(request.keyColumns(), member == null ? null : member.getKeyColumns());
        Map<String, Object> keyValues = normalizedKeyValues(request.keyValues());
        String externalId = firstNonBlank(request.externalId(), externalId(keyColumns, keyValues));
        if (externalId == null) throw ApiException.bad("Provide externalId or keyValues for the system identity.");
        String material = normalize(detail.entity().getId()) + "|" + normalize(dataSourceId) + "|" + normalize(system)
                + "|" + normalize(schema) + "|" + normalize(table) + "|" + normalize(role) + "|" + normalize(externalId);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("memberId", member == null ? request.memberId() : member.getId());
        evidence.put("systemName", system);
        evidence.put("dataSourceId", dataSourceId);
        evidence.put("schemaName", schema);
        evidence.put("tableName", table);
        evidence.put("logicalRole", role);
        evidence.put("keyColumns", keyColumns);
        evidence.put("keyValues", keyValues);
        evidence.put("externalId", externalId);
        return new LinkCandidate(member == null ? request.memberId() : member.getId(), system, dataSourceId, schema,
                table, role, keyColumns, keyValues, externalId, sha256(material), evidence);
    }

    private Map<String, Object> withLinks(Map<String, Object> subject) {
        Map<String, Object> out = new LinkedHashMap<>(subject);
        out.put("attributes", readJsonMap(subject.get("attributesJson")));
        out.put("links", rows("""
                SELECT id, entity_id AS "entityId", subject_id AS "subjectId", member_id AS "memberId",
                       system_name AS "systemName", data_source_id AS "dataSourceId", schema_name AS "schemaName",
                       table_name AS "tableName", logical_role AS "logicalRole", key_columns AS "keyColumns",
                       key_values_json AS "keyValuesJson", external_id AS "externalId",
                       confidence, match_rule AS "matchRule", status, source,
                       created_by AS "createdBy", created_at AS "createdAt", updated_at AS "updatedAt"
                  FROM be_identity_links
                 WHERE subject_id = ? ORDER BY system_name, table_name, external_id
                """, subject.get("id")).stream().map(this::linkRow).toList());
        return out;
    }

    private Map<String, Object> linkRow(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>(row);
        out.put("keyValues", readJsonMap(row.get("keyValuesJson")));
        return out;
    }

    private Map<String, Object> subject(Long subjectId) {
        return one(subjectSelect() + " WHERE s.id = ?", subjectId);
    }

    private Long findSubjectId(Long entityId, String canonicalKey) {
        List<Map<String, Object>> rows = rows("SELECT id FROM be_identity_subjects WHERE entity_id = ? AND canonical_key = ?",
                entityId, canonicalKey);
        return rows.isEmpty() ? null : num(rows.get(0).get("id"));
    }

    private String subjectSelect() {
        return """
                SELECT s.id, s.entity_id AS "entityId", s.canonical_key AS "canonicalKey",
                       s.identity_type AS "identityType", s.status, s.confidence,
                       s.attributes_json AS "attributesJson", s.created_by AS "createdBy",
                       s.created_at AS "createdAt", s.updated_at AS "updatedAt"
                  FROM be_identity_subjects s
                """;
    }

    private Map<String, Object> normalizedKeyValues(Map<String, Object> input) {
        if (input == null || input.isEmpty()) return Map.of();
        Map<String, Object> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        input.forEach((k, v) -> {
            String key = blank(k);
            if (key != null && v != null) out.put(key, String.valueOf(v).trim());
        });
        return new LinkedHashMap<>(out);
    }

    private String externalId(String keyColumns, Map<String, Object> keyValues) {
        if (keyValues == null || keyValues.isEmpty()) return null;
        List<String> preferred = split(keyColumns);
        if (preferred.isEmpty()) preferred = new ArrayList<>(keyValues.keySet());
        List<String> parts = new ArrayList<>();
        for (String col : preferred) {
            Object value = valueIgnoreCase(keyValues, col);
            if (value != null) parts.add(col + "=" + value);
        }
        if (parts.isEmpty()) {
            keyValues.forEach((k, v) -> parts.add(k + "=" + v));
        }
        return String.join("|", parts);
    }

    private Object valueIgnoreCase(Map<String, Object> map, String key) {
        if (map == null || key == null) return null;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (key.equalsIgnoreCase(e.getKey())) return e.getValue();
        }
        return null;
    }

    private Map<String, Object> readJsonMap(Object value) {
        try {
            JsonNode node = value == null ? json.createObjectNode() : json.readTree(String.valueOf(value));
            return json.convertValue(node, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String writeJson(Object value) {
        try { return json.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception e) { throw ApiException.bad("Could not write identity JSON: " + e.getMessage()); }
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
        if (rows.isEmpty()) throw ApiException.notFound("Identity crosswalk object not found");
        return rows.get(0);
    }

    private String like(String value) {
        return "%" + value.toLowerCase(Locale.ROOT) + "%";
    }

    private static Timestamp ts(Instant i) { return i == null ? null : Timestamp.from(i); }
    private static Long num(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(value).trim()); } catch (Exception e) { return null; }
    }
    private static double clamp(Double value) {
        if (value == null) return 1.0;
        return Math.max(0.0, Math.min(1.0, value));
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
    private static String normalize(Object value) {
        return String.valueOf(value == null ? "" : value).trim().toLowerCase(Locale.ROOT);
    }
    private static List<String> split(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
    }
    private static String currentUsername() {
        return AccessContext.current().map(p -> p.username()).orElse("system");
    }
    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) { return UUID.randomUUID().toString().replace("-", ""); }
    }

    private record LinkCandidate(Long memberId, String systemName, Long dataSourceId, String schemaName,
                                 String tableName, String logicalRole, String keyColumns,
                                 Map<String, Object> keyValues, String externalId, String hash,
                                 Map<String, Object> evidence) {}
}
