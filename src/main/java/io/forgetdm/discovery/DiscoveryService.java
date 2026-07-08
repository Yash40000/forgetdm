package io.forgetdm.discovery;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.config.ForgeProps;
import io.forgetdm.core.util.PiiPatterns;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.policy.MaskingPolicyEntity;
import io.forgetdm.policy.MaskingPolicyRepository;
import io.forgetdm.policy.MaskingRuleEntity;
import io.forgetdm.policy.MaskingRuleRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Dual-signal PII discovery:
 *   confidence = 0.6 * columnNameSignal + 0.4 * sampledValueSignal
 * Results land in a human review queue (SUGGESTED -> APPROVED/REJECTED),
 * and approved findings compile straight into an executable masking policy
 * (IBM Governance-Catalog "drag a rule onto a column" made automatic).
 */
@Service
public class DiscoveryService {

    public interface ScanProgress {
        default void schemaResolved(String schemaName) {}
        default void tablesDiscovered(List<String> tableNames) {}
        default void tableStarted(String tableName, int tableIndex, int totalTables) {}
        default void tableColumns(String tableName, int totalColumns) {}
        default void columnScanned(String tableName, String columnName, int scannedColumns, int totalColumns) {}
        default void findingDiscovered(String tableName, String columnName, String piiType) {}
        default void tableCompleted(String tableName, int findingsForTable) {}
    }

    private final ClassificationRepository classifications;
    private final DataSourceService dataSources;
    private final ConnectionFactory connections;
    private final MaskingPolicyRepository policies;
    private final MaskingRuleRepository rules;
    private final AuditService audit;
    private final ForgeProps props;
    private final PiiPatternService customPatterns;

    public DiscoveryService(ClassificationRepository classifications, DataSourceService dataSources,
                            ConnectionFactory connections, MaskingPolicyRepository policies,
                            MaskingRuleRepository rules, AuditService audit, ForgeProps props,
                            PiiPatternService customPatterns) {
        this.classifications = classifications; this.dataSources = dataSources;
        this.connections = connections; this.policies = policies;
        this.rules = rules; this.audit = audit; this.props = props;
        this.customPatterns = customPatterns;
    }

    @Transactional
    public List<ClassificationEntity> scan(Long dataSourceId) {
        return scan(dataSourceId, null, null);
    }

    @Transactional
    public List<ClassificationEntity> scan(Long dataSourceId, String schemaName) {
        return scan(dataSourceId, schemaName, null);
    }

    /**
     * @param selectedTypes when non-empty, only these PII types are scanned for (e.g. just SSN, CREDIT_CARD,
     *                      FULL_NAME, ADDRESS). When empty/null, every known type is considered.
     */
    @Transactional
    public List<ClassificationEntity> scan(Long dataSourceId, String schemaName, Set<String> selectedTypes) {
        return scan(dataSourceId, schemaName, selectedTypes, null);
    }

    @Transactional
    public List<ClassificationEntity> scan(Long dataSourceId, String schemaName, Set<String> selectedTypes,
                                           ScanProgress progress) {
        DataSourceEntity ds = dataSources.get(dataSourceId);
        List<ClassificationEntity> found = new ArrayList<>();
        ScanProgress scanProgress = progress == null ? new ScanProgress() {} : progress;

        // Effective patterns = built-in, overlaid with the current user's custom patterns (user > group > global),
        // then narrowed to the user's selected PII types if any were chosen on the Scan Source page.
        PiiPatternService.Effective custom = customPatterns.resolveEffective();
        Map<String, Pattern> nameHints = new LinkedHashMap<>(PiiPatterns.NAME_HINTS);
        nameHints.putAll(custom.name());
        Map<String, Pattern> valueHints = new LinkedHashMap<>(PiiPatterns.VALUE_HINTS);
        valueHints.putAll(custom.value());
        Map<String, String> suggested = new LinkedHashMap<>(PiiPatterns.SUGGESTED);
        suggested.putAll(custom.suggested());
        Set<String> selected = normalizeTypes(selectedTypes);
        if (!selected.isEmpty()) {
            nameHints.keySet().retainAll(selected);
            valueHints.keySet().retainAll(selected);
        }

        try (Connection c = connections.openPooled(ds)) {
            String schema = DataSourceService.normalizeSchema(c, schemaName);
            scanProgress.schemaResolved(schema);
            // Preserve human decisions across re-scans: keep APPROVED / REJECTED / manual classifications,
            // refresh only the machine SUGGESTED ones. (A re-scan must not wipe an analyst's review work.)
            List<ClassificationEntity> existing = classifications.findByDataSourceIdAndSchemaName(dataSourceId, schema);
            Set<String> locked = new HashSet<>();
            List<ClassificationEntity> stale = new ArrayList<>();
            for (ClassificationEntity e : existing) {
                if ("SUGGESTED".equals(e.getStatus())) stale.add(e);
                else {
                    locked.add(colKey(e.getTableName(), e.getColumnName()));
                    if (selected.isEmpty() || selected.contains(normalizeType(e.getPiiType()))) found.add(e);
                }
            }
            classifications.deleteAll(stale);
            // Flush the deletes NOW. Otherwise Hibernate orders the fresh INSERTs below before these
            // DELETEs at commit, and re-inserting the same (data_source, table, column) collides with the
            // not-yet-deleted stale row on uq_class — which is why a second scan of the same source failed.
            classifications.flush();

            List<String> tables = new ArrayList<>();
            try (ResultSet rs = c.getMetaData().getTables(null, schema, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    String table = rs.getString("TABLE_NAME");
                    if (!table.toLowerCase().startsWith("flyway_")) tables.add(table);
                }
            }
            scanProgress.tablesDiscovered(List.copyOf(tables));
            int tableIndex = 0;
            for (String table : tables) {
                tableIndex++;
                scanProgress.tableStarted(table, tableIndex, tables.size());
                Map<String, String> colTypes = new LinkedHashMap<>();
                try (ResultSet rs = c.getMetaData().getColumns(null, schema, table, "%")) {
                    while (rs.next()) colTypes.put(rs.getString("COLUMN_NAME"), rs.getString("TYPE_NAME"));
                }
                scanProgress.tableColumns(table, colTypes.size());
                int scannedColumns = 0;
                int tableFindings = 0;
                for (Map.Entry<String, String> col : colTypes.entrySet()) {
                    scannedColumns++;
                    if (locked.contains(colKey(table, col.getKey()))) {
                        scanProgress.columnScanned(table, col.getKey(), scannedColumns, colTypes.size());
                        continue;   // analyst already decided this column
                    }
                    Scored scored = classify(c, schema, table, col.getKey(), col.getValue(), nameHints, valueHints);
                    if (scored != null) {
                        ClassificationEntity e = new ClassificationEntity();
                        e.setDataSourceId(dataSourceId);
                        e.setSchemaName(schema);
                        e.setTableName(table);
                        e.setColumnName(col.getKey());
                        e.setDataType(col.getValue());
                        e.setPiiType(scored.piiType);
                        e.setConfidence(Math.round(scored.confidence * 100.0) / 100.0);
                        // Never assign a masker that is incompatible with the column's data type
                        // (e.g. a name/text function on a BIGINT or a DATE column).
                        String fn = typeSafeFunction(suggested.getOrDefault(scored.piiType, "FORMAT_PRESERVE"), col.getValue());
                        e.setSuggestedFunction(fn);
                        e.setSuggestedParam1(defaultParam1(fn, scored.piiType));
                        e.setSuggestedParam2(defaultParam2(fn, scored.piiType));
                        e.setSampleValue(scored.sample);
                        found.add(classifications.save(e));
                        tableFindings++;
                        scanProgress.findingDiscovered(table, col.getKey(), scored.piiType);
                    }
                    scanProgress.columnScanned(table, col.getKey(), scannedColumns, colTypes.size());
                }
                scanProgress.tableCompleted(table, tableFindings);
            }
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("Discovery scan failed: " + e.getMessage()); }

        audit.log("system", "DISCOVERY_SCAN", "datasource=" + ds.getName() + " schema=" + schemaName + " findings=" + found.size());
        return found;
    }

    private record Scored(String piiType, double confidence, String sample) {}

    private Scored classify(Connection c, String schema, String table, String column, String typeName,
                            Map<String, Pattern> nameHints, Map<String, Pattern> valueHints) {
        String category = typeCategory(typeName);
        // 1) name signal
        String best = null; double nameScore = 0;
        for (Map.Entry<String, Pattern> e : nameHints.entrySet()) {
            if (e.getValue().matcher(column).find()) { best = e.getKey(); nameScore = 1.0; break; }
        }
        // 2) value signal from a bounded sample.
        //    Skip value matching for NUMERIC/BOOLEAN columns: numbers rendered as strings match too many
        //    patterns (a 9-digit BIGINT id is not an SSN). For these, PII must be name-indicated, which also
        //    prevents suggesting a name/format masker for a key/amount column.
        List<String> sample = sample(c, schema, table, column, props.getDiscovery().getSampleRows());
        String bestValueType = null; double valueScore = 0;
        boolean valueSignalAllowed = !"NUMERIC".equals(category) && !"BOOLEAN".equals(category) && !"BINARY".equals(category);
        if (valueSignalAllowed && !sample.isEmpty()) {
            Map<String, Integer> hits = new LinkedHashMap<>();
            for (String v : sample) {
                if (v == null || v.isBlank()) continue;
                for (Map.Entry<String, Pattern> e : valueHints.entrySet()) {
                    if (e.getValue().matcher(v.trim()).matches()) hits.merge(e.getKey(), 1, Integer::sum);
                }
                if (valueHints.containsKey("CREDIT_CARD") && PiiPatterns.looksLikeCard(v.trim())) hits.merge("CREDIT_CARD", 1, Integer::sum);
            }
            int n = sample.size();
            for (Map.Entry<String, Integer> h : hits.entrySet()) {
                double ratio = h.getValue() / (double) n;
                if (ratio >= 0.6 && ratio > valueScore) { valueScore = ratio; bestValueType = h.getKey(); }
            }
        }
        // 3) combine — value evidence can confirm or override a name hint
        String piiType; double conf;
        if (best != null && best.equals(bestValueType)) { piiType = best; conf = 0.6 + 0.4 * valueScore; }
        else if (best != null && bestValueType == null)  { piiType = best; conf = 0.6; }
        else if (best == null && bestValueType != null)  { piiType = bestValueType; conf = 0.4 * valueScore + 0.35; }
        else if (best != null)                           { piiType = bestValueType; conf = 0.4 * valueScore + 0.3; } // disagree: trust data
        else return null;
        String sampleShown = sample.stream().filter(Objects::nonNull).findFirst().orElse(null);
        return new Scored(piiType, Math.min(conf, 0.99), redactSample(truncate(sampleShown)));
    }

    private List<String> sample(Connection c, String schema, String table, String column, int rows) {
        List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement()) {
            st.setMaxRows(rows);
            try (ResultSet rs = st.executeQuery("SELECT " + quote(column) + " FROM " + q(schema, table))) {
                while (rs.next()) out.add(rs.getString(1));
            }
        } catch (Exception ignored) { /* non-text or unreadable columns are simply skipped */ }
        return out;
    }

    @Transactional
    public ClassificationEntity setStatus(Long id, String status) {
        return updateClassification(id, status, null, null, null);
    }

    /**
     * Review-queue editing: before (or after) approving a finding the analyst can both
     * change its status AND override the suggested masking function — the override is
     * what generate-policy compiles into the rule.
     */
    @Transactional
    public ClassificationEntity updateClassification(Long id, String status, String suggestedFunction,
                                                     String suggestedParam1, String suggestedParam2) {
        ClassificationEntity e = classifications.findById(id)
                .orElseThrow(() -> ApiException.notFound("Classification " + id + " not found"));
        if (status != null) {
            if (!status.equals("APPROVED") && !status.equals("REJECTED") && !status.equals("SUGGESTED"))
                throw ApiException.bad("Status must be APPROVED, REJECTED or SUGGESTED");
            e.setStatus(status);
        }
        if (suggestedFunction != null && !suggestedFunction.isBlank()) {
            String fn = suggestedFunction.trim().toUpperCase();
            try { io.forgetdm.core.mask.MaskFunction.valueOf(fn); }
            catch (Exception ex) { throw ApiException.bad("Unknown masking function: " + suggestedFunction); }
            String cat = typeCategory(e.getDataType());
            if (!maskCompatible(fn, cat))
                throw ApiException.bad(fn + " can't be applied to " + e.getDataType() + " column "
                        + e.getTableName() + "." + e.getColumnName() + " (" + cat + "). Use a " + cat.toLowerCase(Locale.ROOT)
                        + "-safe function such as " + safeDefaultForCategory(cat) + ".");
            e.setSuggestedFunction(fn);
            e.setSuggestedParam1(defaultParam1(fn, e.getPiiType()));
            e.setSuggestedParam2(defaultParam2(fn, e.getPiiType()));
            audit.log("system", "CLASSIFICATION_RULE_CHANGED",
                    e.getTableName() + "." + e.getColumnName() + " -> " + fn);
        }
        if (suggestedParam1 != null) e.setSuggestedParam1(emptyToNull(suggestedParam1));
        if (suggestedParam2 != null) e.setSuggestedParam2(emptyToNull(suggestedParam2));
        return classifications.save(e);
    }

    /** Compile every APPROVED classification of a data source into a ready-to-run masking policy. */
    @Transactional
    public MaskingPolicyEntity generatePolicy(Long dataSourceId, String policyName) {
        return generatePolicy(dataSourceId, null, policyName);
    }

    @Transactional
    public MaskingPolicyEntity generatePolicy(Long dataSourceId, String schemaName, String policyName) {
        DataSourceEntity ds = dataSources.get(dataSourceId);
        String schema = schemaName;
        if (schema == null || schema.isBlank()) {
            try (Connection c = connections.openPooled(ds)) { schema = DataSourceService.schemaOf(c); }
            catch (Exception e) { schema = null; }
        }
        List<ClassificationEntity> approved = schema == null
                ? classifications.findByDataSourceIdAndStatus(dataSourceId, "APPROVED")
                : classifications.findByDataSourceIdAndSchemaNameAndStatus(dataSourceId, schema, "APPROVED");
        if (approved.isEmpty()) throw ApiException.bad("No APPROVED classifications for data source " + dataSourceId);
        MaskingPolicyEntity p = new MaskingPolicyEntity();
        p.setName(policyName);
        p.setDataSourceId(dataSourceId);
        p.setSchemaName(schema);
        p.setDescription("Auto-generated from discovery of data source " + dataSourceId + (schema == null ? "" : " schema " + schema));
        p = policies.save(p);
        for (ClassificationEntity cl : approved) {
            MaskingRuleEntity r = new MaskingRuleEntity();
            r.setPolicyId(p.getId());
            r.setSchemaName(cl.getSchemaName());
            r.setTableName(cl.getTableName());
            r.setColumnName(cl.getColumnName());
            r.setFunction(cl.getSuggestedFunction());
            r.setParam1(cl.getSuggestedParam1() == null ? defaultParam1(cl.getSuggestedFunction(), cl.getPiiType()) : cl.getSuggestedParam1());
            r.setParam2(cl.getSuggestedParam2() == null ? defaultParam2(cl.getSuggestedFunction(), cl.getPiiType()) : cl.getSuggestedParam2());
            rules.save(r);
        }
        audit.log("system", "POLICY_GENERATED", policyName + " (" + approved.size() + " rules)");
        return p;
    }

    public List<ClassificationEntity> results(Long dataSourceId) {
        return stableOrder(classifications.findByDataSourceId(dataSourceId));
    }

    public List<ClassificationEntity> results(Long dataSourceId, String schemaName) {
        List<ClassificationEntity> rows = (schemaName == null || schemaName.isBlank())
                ? classifications.findByDataSourceId(dataSourceId)
                : classifications.findByDataSourceIdAndSchemaName(dataSourceId, schemaName);
        return stableOrder(rows);
    }

    /** Fixed (table, column) order so editing a finding's status/mask never reshuffles the review list.
     *  Without this, the DB returns rows in physical order, which changes when a row is updated. */
    private static List<ClassificationEntity> stableOrder(List<ClassificationEntity> rows) {
        return rows.stream()
                .sorted(Comparator.comparing((ClassificationEntity e) -> e.getTableName() == null ? "" : e.getTableName(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(e -> e.getColumnName() == null ? "" : e.getColumnName(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(ClassificationEntity::getId))
                .toList();
    }

    public List<ClassificationEntity> results(Long dataSourceId, String schemaName, String tableFilter) {
        List<ClassificationEntity> rows = results(dataSourceId, schemaName);
        String filter = emptyToNull(tableFilter);
        if (filter == null) return rows;
        // Exact (case-insensitive) table match — the UI sends a specific table name, so "customer"
        // must NOT also match "customer_900".
        return rows.stream()
                .filter(r -> r.getTableName() != null && r.getTableName().equalsIgnoreCase(filter))
                .toList();
    }

    @Transactional
    public int approveAll(Long dataSourceId, String schemaName, String tableFilter) {
        List<ClassificationEntity> rows = results(dataSourceId, schemaName, tableFilter);
        rows.forEach(r -> r.setStatus("APPROVED"));
        classifications.saveAll(rows);
        audit.log("system", "CLASSIFICATIONS_APPROVED",
                "datasource=" + dataSourceId + " schema=" + schemaName + " tableFilter=" + tableFilter + " count=" + rows.size());
        return rows.size();
    }

    public List<ClassificationEntity> results(Long dataSourceId, String schemaName, String tableFilter,
                                              Set<String> piiTypes) {
        List<ClassificationEntity> rows = results(dataSourceId, schemaName, tableFilter);
        Set<String> types = normalizeTypes(piiTypes);
        if (types.isEmpty()) return rows;
        return rows.stream()
                .filter(r -> types.contains(normalizeType(r.getPiiType())))
                .toList();
    }

    @Transactional
    public int approveAll(Long dataSourceId, String schemaName, String tableFilter, Set<String> piiTypes) {
        List<ClassificationEntity> rows = results(dataSourceId, schemaName, tableFilter, piiTypes);
        rows.forEach(r -> r.setStatus("APPROVED"));
        classifications.saveAll(rows);
        audit.log("system", "CLASSIFICATIONS_APPROVED",
                "datasource=" + dataSourceId + " schema=" + schemaName + " tableFilter=" + tableFilter +
                        " piiTypes=" + normalizeTypes(piiTypes) + " count=" + rows.size());
        return rows.size();
    }

    @Transactional
    public int rejectAll(Long dataSourceId, String schemaName, String tableFilter) {
        List<ClassificationEntity> rows = results(dataSourceId, schemaName, tableFilter);
        rows.forEach(r -> r.setStatus("REJECTED"));
        classifications.saveAll(rows);
        audit.log("system", "CLASSIFICATIONS_REJECTED",
                "datasource=" + dataSourceId + " schema=" + schemaName + " tableFilter=" + tableFilter + " count=" + rows.size());
        return rows.size();
    }

    @Transactional
    public int rejectAll(Long dataSourceId, String schemaName, String tableFilter, Set<String> piiTypes) {
        List<ClassificationEntity> rows = results(dataSourceId, schemaName, tableFilter, piiTypes);
        rows.forEach(r -> r.setStatus("REJECTED"));
        classifications.saveAll(rows);
        audit.log("system", "CLASSIFICATIONS_REJECTED",
                "datasource=" + dataSourceId + " schema=" + schemaName + " tableFilter=" + tableFilter +
                        " piiTypes=" + normalizeTypes(piiTypes) + " count=" + rows.size());
        return rows.size();
    }

    public List<Map<String, Object>> tableColumns(Long dataSourceId, String schemaName, String table) {
        return tableColumns(dataSourceId, schemaName, table, null);
    }

    public List<Map<String, Object>> tableColumns(Long dataSourceId, String schemaName, String table, Set<String> piiTypes) {
        DataSourceEntity ds = dataSources.get(dataSourceId);
        try (Connection c = connections.openPooled(ds)) {
            String schema = DataSourceService.normalizeSchema(c, schemaName);
            Set<String> types = normalizeTypes(piiTypes);
            Map<String, ClassificationEntity> existing = new LinkedHashMap<>();
            classifications.findByDataSourceIdAndSchemaNameAndTableName(dataSourceId, schema, table)
                    .forEach(cl -> {
                        if (types.isEmpty() || types.contains(normalizeType(cl.getPiiType()))) {
                            existing.put(columnKey(cl.getColumnName()), cl);
                        }
                    });

            List<Map<String, Object>> out = new ArrayList<>();
            try (ResultSet rs = c.getMetaData().getColumns(null, schema, table, "%")) {
                while (rs.next()) {
                    String column = rs.getString("COLUMN_NAME");
                    ClassificationEntity cl = existing.get(columnKey(column));
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("schemaName", schema);
                    row.put("tableName", table);
                    row.put("columnName", column);
                    row.put("dataType", rs.getString("TYPE_NAME"));
                    row.put("nullable", rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable);
                    row.put("sampleValue", redactSample(sample(c, schema, table, column, 1).stream()
                            .filter(Objects::nonNull).findFirst().orElse(null)));
                    row.put("typeCategory", typeCategory(rs.getString("TYPE_NAME")));
                    if (cl == null) {
                        row.put("status", "NOT_PII");
                        row.put("confidence", 0.0);
                    } else {
                        row.put("classificationId", cl.getId());
                        row.put("status", cl.getStatus());
                        row.put("piiType", cl.getPiiType());
                        row.put("confidence", cl.getConfidence());
                        row.put("suggestedFunction", cl.getSuggestedFunction());
                        row.put("suggestedParam1", cl.getSuggestedParam1());
                        row.put("suggestedParam2", cl.getSuggestedParam2());
                    }
                    out.add(row);
                }
            }
            if (out.isEmpty()) throw ApiException.notFound("Table " + table + " not found in schema " + schema);
            return out;
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("Column review failed: " + e.getMessage()); }
    }

    @Transactional
    public ClassificationEntity markManual(Long dataSourceId, Map<String, String> body) {
        DataSourceEntity ds = dataSources.get(dataSourceId);
        String table = requireText(body.get("tableName"), "tableName");
        String column = requireText(body.get("columnName"), "columnName");
        String piiType = Optional.ofNullable(emptyToNull(body.get("piiType")))
                .map(v -> v.trim().toUpperCase(Locale.ROOT))
                .orElse("MANUAL_PII");
        String fn = normalizeMaskFunction(Optional.ofNullable(emptyToNull(body.get("suggestedFunction")))
                .orElse(PiiPatterns.SUGGESTED.getOrDefault(piiType, "FORMAT_PRESERVE")));
        String status = normalizeStatus(body.get("status"), "APPROVED");
        String p1 = Optional.ofNullable(emptyToNull(body.get("suggestedParam1"))).orElse(defaultParam1(fn, piiType));
        String p2 = Optional.ofNullable(emptyToNull(body.get("suggestedParam2"))).orElse(defaultParam2(fn, piiType));

        try (Connection c = connections.openPooled(ds)) {
            String schema = DataSourceService.normalizeSchema(c, body.get("schemaName"));
            Map<String, String> colMeta = findColumn(c, schema, table, column);
            String actualColumn = colMeta.get("column");
            String cat = typeCategory(colMeta.get("type"));
            if (!maskCompatible(fn, cat))
                throw ApiException.bad(fn + " can't be applied to " + colMeta.get("type") + " column "
                        + table + "." + actualColumn + " (" + cat + "). Use a " + cat.toLowerCase(Locale.ROOT)
                        + "-safe function such as " + safeDefaultForCategory(cat) + ".");
            ClassificationEntity e = classifications
                    .findByDataSourceIdAndSchemaNameAndTableNameAndColumnName(dataSourceId, schema, table, actualColumn)
                    .orElseGet(ClassificationEntity::new);
            e.setDataSourceId(dataSourceId);
            e.setSchemaName(schema);
            e.setTableName(table);
            e.setColumnName(actualColumn);
            e.setDataType(colMeta.get("type"));
            e.setPiiType(piiType);
            e.setConfidence(1.0);
            e.setSuggestedFunction(fn);
            e.setSuggestedParam1(p1);
            e.setSuggestedParam2(p2);
            e.setStatus(status);
            e.setSampleValue(redactSample(sample(c, schema, table, actualColumn, 1).stream()
                    .filter(Objects::nonNull).findFirst().orElse(null)));
            ClassificationEntity saved = classifications.save(e);
            audit.log("system", "CLASSIFICATION_MANUAL",
                    ds.getName() + " " + schema + "." + table + "." + actualColumn + " -> " + piiType + "/" + fn);
            return saved;
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("Manual PII classification failed: " + e.getMessage()); }
    }

    /**
     * Entity-relationship traversal model for the UI:
     *  - nodes carry the PII columns discovered on each table (column, piiType, function, status)
     *  - edges are FK relationships, parent (PK side) -> child (FK side), with the join columns
     * This is what lets a user applying a rule on t1 see that child t2 also carries a name field.
     */
    public Map<String, Object> graph(Long dataSourceId, String schemaName) {
        return graph(dataSourceId, schemaName, null);
    }

    public Map<String, Object> graph(Long dataSourceId, String schemaName, Set<String> piiTypes) {
        DataSourceEntity ds = dataSources.get(dataSourceId);
        try (Connection c = connections.openPooled(ds)) {
            String schema = DataSourceService.normalizeSchema(c, schemaName);
            List<ClassificationEntity> findings = results(dataSourceId, schema, null, piiTypes);
            Map<String, List<Map<String, Object>>> piiByTable = new LinkedHashMap<>();
            for (ClassificationEntity f : findings) {
                Map<String, Object> col = new LinkedHashMap<>();
                col.put("column", f.getColumnName());
                col.put("piiType", f.getPiiType());
                col.put("function", f.getSuggestedFunction());
                col.put("param1", f.getSuggestedParam1());
                col.put("param2", f.getSuggestedParam2());
                col.put("status", f.getStatus());
                col.put("confidence", f.getConfidence());
                piiByTable.computeIfAbsent(f.getTableName(), k -> new ArrayList<>()).add(col);
            }

            List<Map<String, Object>> nodes = new ArrayList<>();
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = c.getMetaData().getTables(null, schema, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    String table = rs.getString("TABLE_NAME");
                    if (table.toLowerCase().startsWith("flyway_")) continue;
                    tables.add(table);
                    List<Map<String, Object>> piiCols = piiByTable.getOrDefault(table, List.of());
                    Map<String, Object> node = new LinkedHashMap<>();
                    node.put("id", table);
                    node.put("label", table);
                    node.put("piiCount", (long) piiCols.size());
                    node.put("piiColumns", piiCols);
                    nodes.add(node);
                }
            }

            List<Map<String, Object>> edges = new ArrayList<>();
            for (String table : tables) {
                try (ResultSet rs = c.getMetaData().getImportedKeys(null, schema, table)) {
                    while (rs.next()) {
                        String parent = rs.getString("PKTABLE_NAME");
                        String child = rs.getString("FKTABLE_NAME");
                        String pk = rs.getString("PKCOLUMN_NAME");
                        String fk = rs.getString("FKCOLUMN_NAME");
                        Map<String, Object> edge = new LinkedHashMap<>();
                        edge.put("id", edgeId(parent, child, pk, fk));
                        edge.put("from", parent);   // parent
                        edge.put("to", child);      // child
                        edge.put("pkColumn", pk);
                        edge.put("fkColumn", fk);
                        edge.put("label", fk + " -> " + pk);
                        edges.add(edge);
                    }
                }
            }
            List<Map<String, Object>> cycles = traversalCycles(tables, edges);
            Set<String> cycleEdgeIds = new LinkedHashSet<>();
            cycles.forEach(cycle -> ((List<?>) cycle.getOrDefault("edgeIds", List.of()))
                    .forEach(id -> cycleEdgeIds.add(String.valueOf(id))));
            return Map.of("schema", schema == null ? "" : schema,
                    "nodes", nodes,
                    "edges", edges,
                    "cycles", cycles,
                    "cycleEdgeIds", cycleEdgeIds,
                    "traversalMode", cycles.isEmpty() ? "ACYCLIC" : "CYCLE_GUARDED");
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("Discovery graph failed: " + e.getMessage()); }
    }

    private static String edgeId(String parent, String child, String pk, String fk) {
        return parent + "." + pk + "->" + child + "." + fk;
    }

    private static List<Map<String, Object>> traversalCycles(List<String> tables, List<Map<String, Object>> edges) {
        Map<String, List<Map<String, Object>>> outgoing = new LinkedHashMap<>();
        for (String table : tables) outgoing.put(table, new ArrayList<>());
        for (Map<String, Object> edge : edges) {
            String from = String.valueOf(edge.get("from"));
            if (outgoing.containsKey(from)) outgoing.get(from).add(edge);
        }
        List<Map<String, Object>> cycles = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String start : tables) {
            findCycles(start, start, outgoing, new ArrayList<>(List.of(start)), new ArrayList<>(), seen, cycles, tables.size());
            if (cycles.size() >= 25) break;
        }
        return cycles;
    }

    private static void findCycles(String start, String current, Map<String, List<Map<String, Object>>> outgoing,
                                   List<String> path, List<String> edgePath, Set<String> seen,
                                   List<Map<String, Object>> cycles, int maxDepth) {
        if (path.size() > maxDepth + 1 || cycles.size() >= 25) return;
        for (Map<String, Object> edge : outgoing.getOrDefault(current, List.of())) {
            String next = String.valueOf(edge.get("to"));
            String edgeId = String.valueOf(edge.get("id"));
            if (next.equals(start)) {
                List<String> nodes = new ArrayList<>(path);
                String key = canonicalCycleKey(nodes);
                if (seen.add(key)) {
                    List<String> closed = new ArrayList<>(nodes);
                    closed.add(start);
                    List<String> cycleEdges = new ArrayList<>(edgePath);
                    cycleEdges.add(edgeId);
                    Map<String, Object> cycle = new LinkedHashMap<>();
                    cycle.put("id", "cycle-" + (cycles.size() + 1));
                    cycle.put("tables", closed);
                    cycle.put("edgeIds", cycleEdges);
                    cycle.put("length", nodes.size());
                    cycles.add(cycle);
                }
            } else if (!path.contains(next)) {
                path.add(next);
                edgePath.add(edgeId);
                findCycles(start, next, outgoing, path, edgePath, seen, cycles, maxDepth);
                edgePath.remove(edgePath.size() - 1);
                path.remove(path.size() - 1);
            }
        }
    }

    private static String canonicalCycleKey(List<String> cycleNodes) {
        String best = null;
        for (int i = 0; i < cycleNodes.size(); i++) {
            List<String> rotated = new ArrayList<>();
            for (int j = 0; j < cycleNodes.size(); j++) rotated.add(cycleNodes.get((i + j) % cycleNodes.size()));
            String key = String.join("->", rotated);
            if (best == null || key.compareTo(best) < 0) best = key;
        }
        return best == null ? "" : best;
    }

    static String quote(String ident) {
        if (!ident.matches("[A-Za-z0-9_]+")) throw ApiException.bad("Illegal identifier: " + ident);
        return "\"" + ident + "\"";
    }

    static String q(String schema, String table) {
        return schema == null || schema.isBlank() ? quote(table) : quote(schema) + "." + quote(table);
    }

    private static String truncate(String s) { return s == null ? null : (s.length() > 290 ? s.substring(0, 290) : s); }
    private static String emptyToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private static String columnKey(String column) {
        return column == null ? "" : column.toLowerCase(Locale.ROOT);
    }

    private static String requireText(String value, String field) {
        String v = emptyToNull(value);
        if (v == null) throw ApiException.bad(field + " is required");
        return v;
    }

    private static String normalizeStatus(String value, String fallback) {
        String status = Optional.ofNullable(emptyToNull(value)).orElse(fallback).toUpperCase(Locale.ROOT);
        if (!status.equals("APPROVED") && !status.equals("REJECTED") && !status.equals("SUGGESTED"))
            throw ApiException.bad("Status must be APPROVED, REJECTED or SUGGESTED");
        return status;
    }

    private static String normalizeMaskFunction(String value) {
        String fn = requireText(value, "suggestedFunction").toUpperCase(Locale.ROOT);
        try { io.forgetdm.core.mask.MaskFunction.valueOf(fn); }
        catch (Exception ex) { throw ApiException.bad("Unknown masking function: " + value); }
        return fn;
    }

    private static Map<String, String> findColumn(Connection c, String schema, String table, String column) throws SQLException {
        try (ResultSet rs = c.getMetaData().getColumns(null, schema, table, "%")) {
            while (rs.next()) {
                String candidate = rs.getString("COLUMN_NAME");
                if (candidate != null && candidate.equalsIgnoreCase(column)) {
                    Map<String, String> out = new LinkedHashMap<>();
                    out.put("column", candidate);
                    out.put("type", rs.getString("TYPE_NAME"));
                    return out;
                }
            }
        }
        throw ApiException.notFound("Column " + table + "." + column + " not found in schema " + schema);
    }

    private static Set<String> normalizeTypes(Set<String> types) {
        if (types == null) return Set.of();
        Set<String> out = new HashSet<>();
        for (String t : types) if (t != null && !t.isBlank()) out.add(normalizeType(t));
        return out;
    }

    private static String normalizeType(String type) {
        return type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
    }

    /** All scannable PII types (built-in + the current user's custom types) for the Scan Source selector. */
    public List<String> piiTypeCatalog() {
        Set<String> all = new TreeSet<>();
        all.addAll(PiiPatterns.NAME_HINTS.keySet());
        all.addAll(PiiPatterns.VALUE_HINTS.keySet());
        all.addAll(PiiPatterns.SUGGESTED.keySet());
        all.addAll(customPatterns.customTypes());
        return new ArrayList<>(all);
    }

    private static String colKey(String table, String column) {
        return (table == null ? "" : table.toLowerCase(Locale.ROOT)) + "." + (column == null ? "" : column.toLowerCase(Locale.ROOT));
    }

    /** Broad data-type category from a JDBC TYPE_NAME, used to keep maskers type-compatible. */
    static String typeCategory(String typeName) {
        String t = typeName == null ? "" : typeName.toLowerCase(Locale.ROOT);
        if (t.contains("bool") || t.equals("bit")) return "BOOLEAN";
        if (t.contains("date") || t.contains("time")) return "DATE";              // date / timestamp / time / timestamptz
        if (t.contains("char") || t.contains("text") || t.contains("clob")
                || t.contains("json") || t.contains("uuid") || t.contains("enum")) return "TEXT";
        if (t.contains("int") || t.contains("serial") || t.contains("numeric") || t.contains("decimal")
                || t.contains("real") || t.contains("double") || t.contains("float")
                || t.contains("money") || t.contains("number")) return "NUMERIC";
        if (t.contains("binary") || t.contains("blob") || t.contains("bytea") || t.contains("raw")) return "BINARY";
        return "TEXT";   // default permissive
    }

    /** True if a masking function can safely produce a value writable to a column of this type category. */
    static boolean maskCompatible(String fn, String category) {
        if (fn == null) return false;
        switch (fn) {                                   // type-agnostic — write any column
            case "NULLIFY": case "FIXED": case "PASSTHROUGH": return true;
            default: break;
        }
        return switch (category == null ? "TEXT" : category) {
            case "TEXT" -> true;                        // any masker renders to text
            case "NUMERIC" -> Set.of("FORMAT_PRESERVE", "SEQUENCE").contains(fn);
            case "DATE" -> Set.of("DATE_SHIFT", "DOB_AGE_BAND").contains(fn);
            default -> false;                           // BOOLEAN / BINARY → only the type-agnostic ones above
        };
    }

    static String safeDefaultForCategory(String category) {
        return switch (category == null ? "TEXT" : category) {
            case "NUMERIC" -> "FORMAT_PRESERVE";
            case "DATE" -> "DATE_SHIFT";
            case "BOOLEAN", "BINARY" -> "NULLIFY";
            default -> "FORMAT_PRESERVE";
        };
    }

    /** Pick the function, but never one incompatible with the column's data type. */
    static String typeSafeFunction(String fn, String typeName) {
        String cat = typeCategory(typeName);
        return maskCompatible(fn, cat) ? fn : safeDefaultForCategory(cat);
    }

    /** Never persist/show raw PII in the review sample — keep just enough to recognize the format. */
    static String redactSample(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.isEmpty()) return null;
        int at = v.indexOf('@');
        if (at > 0) return v.charAt(0) + "***" + v.substring(at);     // email: keep first char + domain
        if (v.length() <= 2) return "**";
        if (v.length() <= 4) return v.charAt(0) + "***";
        return v.charAt(0) + "***" + v.substring(v.length() - 2);     // first char + last two
    }

    static String defaultParam1(String fn, String pii) {
        if ("FULL_NAME".equals(fn)) return "FIRST LAST";
        if ("EMAIL".equals(fn)) return "NAME_SAFE";
        if ("PHONE".equals(fn)) return "FORMAT_PRESERVE";
        if ("SSN".equals(fn)) return "VALID_PRESERVE_AREA";
        if ("CREDIT_CARD".equals(fn)) return "VALID_PRESERVE_BIN";
        if ("CITY_STATE_ZIP".equals(fn))
            return "CITY".equals(pii) ? "CITY" : "STATE".equals(pii) ? "STATE" : "ZIP".equals(pii) ? "ZIP" : "FULL";
        if ("ADDRESS_US".equals(fn)) return "FULL";
        return null;
    }

    static String defaultParam2(String fn, String pii) {
        if (Set.of("FIRST_NAME", "LAST_NAME", "FULL_NAME", "COMPANY", "ADDRESS_STREET").contains(fn)) return "PROPER";
        if ("EMAIL".equals(fn)) return "SAFE_DOMAIN";
        if ("PHONE".equals(fn)) return "PRESERVE_COUNTRY";
        if ("SSN".equals(fn) || "CREDIT_CARD".equals(fn)) return "PRESERVE_FORMAT";
        if ("CITY_STATE_ZIP".equals(fn) || "ADDRESS_US".equals(fn)) return "PRESERVE_STATE";
        return null;
    }
}
