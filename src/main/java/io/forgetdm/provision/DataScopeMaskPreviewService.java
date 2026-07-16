package io.forgetdm.provision;

import io.forgetdm.common.ApiException;
import io.forgetdm.core.mask.MaskContext;
import io.forgetdm.core.mask.MaskFunction;
import io.forgetdm.core.mask.MaskingEngine;
import io.forgetdm.dataset.DataSetDefinitionEntity;
import io.forgetdm.dataset.DataSetService;
import io.forgetdm.dataset.TableProfileEntity;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.policy.MaskingRuleEntity;
import io.forgetdm.policy.MaskingRuleRepository;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Column Map "before → after" preview: reads a handful of live source rows and masks them with the
 * EXACT job-time semantics — same engine, same {@link ProvisioningService#saltFor} salt convention,
 * same MaskContext row wiring (names masked before emails so NAME_SAFE email composition works).
 * Lives in the provision package specifically for access to the package-private saltFor.
 *
 * Deliberate limits: FK key-consistency salts are not computed (keySalt = empty map — key columns are
 * rarely masked), and conditional overrides are shown as their masked branch without evaluating the
 * condition. Values are truncated and never logged.
 */
@Service
public class DataScopeMaskPreviewService {

    private static final Pattern IDENT = Pattern.compile("[A-Za-z0-9_]+");
    private static final int MAX_PREVIEW_ROWS = 20;
    private static final int MAX_VALUE_CHARS = 80;

    private final DataSetService datasets;
    private final DataSourceService dataSources;
    private final ConnectionFactory connections;
    private final MaskingEngine engine;
    private final MaskingRuleRepository rules;

    public DataScopeMaskPreviewService(DataSetService datasets, DataSourceService dataSources,
                                       ConnectionFactory connections, MaskingEngine engine,
                                       MaskingRuleRepository rules) {
        this.datasets = datasets;
        this.dataSources = dataSources;
        this.connections = connections;
        this.engine = engine;
        this.rules = rules;
    }

    public record PreviewColumn(String targetColumn, String sourceColumn, String action, String literalValue) {}
    public record PreviewRequest(String table, Long policyId, String seed, Integer rows, List<PreviewColumn> columns) {}

    public Map<String, Object> preview(Long datasetId, PreviewRequest req) {
        if (req == null || req.table() == null || req.table().isBlank())
            throw ApiException.bad("table is required");
        if (!IDENT.matcher(req.table()).matches())
            throw ApiException.bad("Invalid table name");
        List<PreviewColumn> cols = req.columns() == null ? List.of() : req.columns().stream()
                .filter(c -> c != null && c.targetColumn() != null && !c.targetColumn().isBlank()).toList();
        if (cols.isEmpty()) throw ApiException.bad("At least one mapped column is required");

        DataSetDefinitionEntity def = datasets.get(datasetId);
        TableProfileEntity profile = datasets.listProfiles(datasetId).stream()
                .filter(p -> p.getTableName().equalsIgnoreCase(req.table())).findFirst().orElse(null);
        Long dsId = profile != null && profile.getSourceDataSourceId() != null
                ? profile.getSourceDataSourceId() : def.getDataSourceId();
        String schema = profile != null && notBlank(profile.getSourceSchemaName())
                ? profile.getSourceSchemaName() : def.getSchemaName();
        if (notBlank(schema) && !IDENT.matcher(schema).matches())
            throw ApiException.bad("Invalid schema name");

        Map<String, MaskingRuleEntity> ruleByCol = new HashMap<>();
        if (req.policyId() != null) {
            for (MaskingRuleEntity r : rules.findByPolicyId(req.policyId())) {
                if (!r.getTableName().equalsIgnoreCase(req.table())) continue;
                if (r.getSchemaName() != null && notBlank(schema) && !r.getSchemaName().equalsIgnoreCase(schema)) continue;
                ruleByCol.put(r.getColumnName().toLowerCase(Locale.ROOT), r);
            }
        }

        // Same ordering trick as the job engine: name columns first so EMAIL NAME_SAFE can read
        // ctx.maskedOf("first_name"/"last_name") composed in the same row.
        List<PreviewColumn> ordered = new ArrayList<>(cols);
        ordered.sort(Comparator.comparingInt(c -> maskOrder(ruleFor(c, ruleByCol))));

        int limit = Math.min(MAX_PREVIEW_ROWS, req.rows() == null || req.rows() <= 0 ? 5 : req.rows());
        MaskingEngine eng = engine.withSeed(req.seed());
        List<Map<String, String>> sourceRows = readSample(dsId, schema, req.table(), limit);

        List<Map<String, Object>> columnsOut = new ArrayList<>();
        for (PreviewColumn c : cols) {
            MaskingRuleEntity rule = ruleFor(c, ruleByCol);
            String action = normAction(c.action());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("targetColumn", c.targetColumn());
            m.put("sourceColumn", c.sourceColumn());
            m.put("state", switch (action) {
                case "NULL_OUT" -> "null";
                case "LITERAL" -> "literal";
                default -> rule == null ? "copied as-is" : rule.getFunction();
            });
            columnsOut.add(m);
        }

        List<List<Map<String, String>>> rowsOut = new ArrayList<>();
        long rowIdx = 0;
        for (Map<String, String> src : sourceRows) {
            rowIdx++;
            MaskContext ctx = ProvisioningService.maskContext(rowIdx, new ArrayList<>(ruleByCol.values()));
            src.forEach((k, v) -> ctx.row.put(k, v));   // readSample lower-cases keys already
            Map<String, String> maskedByTarget = new HashMap<>();
            for (PreviewColumn c : ordered) {
                String action = normAction(c.action());
                String original = c.sourceColumn() == null ? null
                        : src.get(c.sourceColumn().toLowerCase(Locale.ROOT));
                String masked;
                switch (action) {
                    case "NULL_OUT" -> masked = null;
                    case "LITERAL" -> masked = c.literalValue();
                    default -> {
                        MaskingRuleEntity rule = ruleFor(c, ruleByCol);
                        if (rule == null) {
                            masked = original;
                        } else {
                            String col = rule.getColumnName() != null ? rule.getColumnName() : c.sourceColumn();
                            masked = eng.mask(MaskFunction.valueOf(rule.getFunction()),
                                    ProvisioningService.saltFor(rule, req.table(), col, Map.of()),
                                    original, rule.getParam1(), rule.getParam2(), ctx);
                            ctx.masked.put(c.targetColumn().toLowerCase(Locale.ROOT), masked);
                        }
                    }
                }
                maskedByTarget.put(c.targetColumn().toLowerCase(Locale.ROOT), masked);
            }
            List<Map<String, String>> cells = new ArrayList<>();
            for (PreviewColumn c : cols) {
                String original = c.sourceColumn() == null ? null
                        : src.get(c.sourceColumn().toLowerCase(Locale.ROOT));
                Map<String, String> cell = new HashMap<>();
                cell.put("original", truncate(original));
                cell.put("masked", truncate(maskedByTarget.get(c.targetColumn().toLowerCase(Locale.ROOT))));
                cells.add(cell);
            }
            rowsOut.add(cells);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("table", req.table());
        out.put("columns", columnsOut);
        out.put("rows", rowsOut);
        return out;
    }

    private static MaskingRuleEntity ruleFor(PreviewColumn c, Map<String, MaskingRuleEntity> ruleByCol) {
        // job-time lookup order (applyPolicyMask): source column first, then target column
        MaskingRuleEntity rule = c.sourceColumn() == null ? null
                : ruleByCol.get(c.sourceColumn().toLowerCase(Locale.ROOT));
        return rule != null ? rule : ruleByCol.get(c.targetColumn().toLowerCase(Locale.ROOT));
    }

    private static int maskOrder(MaskingRuleEntity rule) {
        if (rule == null) return 1;
        return switch (rule.getFunction()) {
            case "FIRST_NAME", "LAST_NAME", "FULL_NAME" -> 0;
            default -> 1;
        };
    }

    private static String normAction(String action) {
        String a = action == null || action.isBlank() ? "USE_POLICY" : action.trim().toUpperCase(Locale.ROOT);
        return switch (a) {
            case "LITERAL", "NULL_OUT" -> a;
            default -> "USE_POLICY";   // SUPPRESS columns should not be sent; treat unknowns as policy
        };
    }

    private List<Map<String, String>> readSample(Long dsId, String schema, String table, int limit) {
        DataSourceEntity ds = dataSources.get(dsId);
        String from = notBlank(schema) ? schema + "." + table : table;
        List<Map<String, String>> out = new ArrayList<>();
        try (Connection c = connections.openPooled(ds); Statement st = c.createStatement()) {
            st.setMaxRows(limit);
            try (ResultSet rs = st.executeQuery("SELECT * FROM " + from)) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                String[] names = new String[n];
                for (int i = 1; i <= n; i++) names[i - 1] = md.getColumnLabel(i).toLowerCase(Locale.ROOT);
                while (rs.next() && out.size() < limit) {
                    Map<String, String> row = new LinkedHashMap<>();
                    for (int i = 1; i <= n; i++) {
                        Object v = rs.getObject(i);
                        row.put(names[i - 1], v == null ? null : String.valueOf(v));
                    }
                    out.add(row);
                }
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.bad("Could not read sample rows from " + from + ": " + e.getMessage());
        }
        return out;
    }

    private static String truncate(String v) {
        if (v == null) return null;
        return v.length() <= MAX_VALUE_CHARS ? v : v.substring(0, MAX_VALUE_CHARS) + "…";
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
}
