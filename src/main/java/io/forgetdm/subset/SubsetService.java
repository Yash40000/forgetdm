package io.forgetdm.subset;

import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;
import java.util.Locale;

/**
 * Entity-based subsetting (entity-driven): pick a driver table + WHERE filter,
 * then walk the foreign-key graph in BOTH directions —
 *   parents (referenced rows you must have, or inserts break)
 *   children (dependent rows that make the entity complete)
 * — producing a referentially intact slice, loaded parents-first.
 */
@Service
public class SubsetService {

    public static final int MAX_DRIVER_ROWS = 100_000;
    private static final int KEY_CHUNK_SIZE = 5_000;
    /** Heap safety: max total PK values held across all tables during FK closure (override via env). */
    private static final long MAX_TOTAL_SUBSET_KEYS = envLong("FORGETDM_SUBSET_MAX_KEYS", 5_000_000L);
    private final ConnectionFactory connections;
    private final DataSourceService dataSources;

    public SubsetService(ConnectionFactory connections, DataSourceService dataSources) {
        this.connections = connections; this.dataSources = dataSources;
    }

    // ---- model ----
    public record FkEdge(String childTable, String childColumn, String parentTable, String parentColumn,
                         String source, Long relRefId) {
        public FkEdge(String childTable, String childColumn, String parentTable, String parentColumn) {
            this(childTable, childColumn, parentTable, parentColumn, "DB", null);
        }
    }
    public record TableCriterion(String table, String filter, Integer rowLimit) {}

    /**
     * A user-defined FK edge contributed by the DataScope's custom-relationship table.
     * Multiple UserRelEdges may share the same parent/child tables (for composite keys).
     */
    public record UserRelEdge(String parentTable, String parentColumn,
                              String childTable,  String childColumn,
                              String relName, Long relRefId) {
        public UserRelEdge(String parentTable, String parentColumn,
                           String childTable, String childColumn, String relName) {
            this(parentTable, parentColumn, childTable, childColumn, relName, null);
        }
    }

    /**
     * Per-table directive from an Access Definition.
     * Carries all the per-level controls that TableProfile stores in the database.
     *
     * @param table               table name (case-insensitive match)
     * @param included            false = exclude from extract and FK traversal
     * @param filterExpr          SQL WHERE expression (guards are applied); null = no extra filter
     * @param referentialStrategy INHERIT | FOLLOW_PARENT | INDEPENDENT
     * @param q1Mode              null = use global; YES/NO = per-table Q1 (include parents);
     *                            DEFER = Q1 only after the primary closure converges (Optim defer)
     * @param q2Mode              null = use global; YES/NO = per-table Q2 (include children);
     *                            DEFER = Q2 only after the primary closure converges (Optim defer)
     * @param rowLimit            optional per-table sampling cap; null/0 = no table-specific cap
     */
    public record TableDirective(String table, boolean included, String filterExpr,
                                 String referentialStrategy, String q1Mode, String q2Mode,
                                 Integer rowLimit) {}

    public record TableSlice(String table, String pkColumn, LinkedHashSet<String> pkValues,
                             String filter, Integer rowLimit, boolean keyless) {
        public static TableSlice keyed(String table, String pkColumn, LinkedHashSet<String> pkValues) {
            return new TableSlice(table, pkColumn, pkValues, null, null, false);
        }

        public static TableSlice keyless(String table, String filter, int rowLimit) {
            return new TableSlice(table, null, new LinkedHashSet<>(), filter, rowLimit, true);
        }
    }
    public static class SubsetPlan {
        public String driverTable;
        public String schemaName;
        public String filter;
        public Integer driverRowLimit;
        public boolean includeRelated = true;
        public boolean includeParents = true; // Q1: include parent rows for selected children
        public boolean includeChildren = true; // Q2: include child rows for selected parents
        public String mode = "REFERENTIAL_CLOSURE";
        public List<TableCriterion> criteria = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();
        public List<String> loadOrder = new ArrayList<>();              // parents first
        public Map<String, TableSlice> slices = new LinkedHashMap<>();  // table -> selected PKs
        public Map<String, Integer> rowCounts = new LinkedHashMap<>();
        public long totalRows;
    }

    /**
     * Build a subset plan driven by an Access Definition's TableDirectives.
     * Backward-compat bridge — delegates to the full overload with empty user edges + rules.
     */
    public SubsetPlan planWithDirectives(Long dataSourceId, String schemaName,
                                         String driverTable, String driverFilter,
                                         int maxDriverRows, boolean globalQ1, boolean globalQ2,
                                         List<TableDirective> directives) {
        return planWithDirectives(dataSourceId, schemaName, driverTable, driverFilter,
                maxDriverRows, globalQ1, globalQ2, directives, List.of(), Map.of());
    }

    /**
     * Full overload: adds user-defined FK edges and per-relationship traversal direction rules.
     *
     * Key behaviours beyond the base plan():
     *   userEdges           — appended to the DB-catalog FK edge list before closure begins.
     *   traversalDirections — key: "parentLower->childLower"; value: BOTH|Q1_ONLY|Q2_ONLY|NONE.
     *                         Takes precedence over per-table q1Override/q2Override when present.
     *   EXCLUDED tables     — never added to slices; FK edges to/from them are skipped.
     *   INDEPENDENT         — table rows seeded from its own filter_expr before FK closure.
     *   q1/q2 per-table     — fallback when no traversal-direction rule exists for an edge.
     */
    public SubsetPlan planWithDirectives(Long dataSourceId, String schemaName,
                                         String driverTable, String driverFilter,
                                         int maxDriverRows, boolean globalQ1, boolean globalQ2,
                                         List<TableDirective> directives,
                                         List<UserRelEdge> userEdges,
                                         Map<String, String> traversalDirections) {
        DataSourceEntity ds = dataSources.get(dataSourceId);
        try (Connection c = connections.open(ds)) {
            String schema = DataSourceService.normalizeSchema(c, schemaName);
            if (traversalDirections == null) traversalDirections = Map.of();
            // Normalised directive map: lower-cased table name → directive
            Map<String, TableDirective> dmap = new HashMap<>();
            if (directives != null) {
                for (TableDirective d : directives) {
                    if (d != null && d.table() != null)
                        dmap.put(d.table().toLowerCase(Locale.ROOT), d);
                }
            }

            final boolean hasProfileFilter = !dmap.isEmpty();
            Set<String> includedLower = new LinkedHashSet<>();
            List<String> scopedTables = new ArrayList<>();
            for (TableDirective d : dmap.values()) {
                if (d.included() && d.table() != null && !d.table().isBlank()) {
                    String lower = d.table().toLowerCase(Locale.ROOT);
                    if (includedLower.add(lower)) scopedTables.add(d.table());
                }
            }
            if (driverTable != null && !driverTable.isBlank() &&
                    includedLower.add(driverTable.toLowerCase(Locale.ROOT))) {
                scopedTables.add(driverTable);
            }

            List<FkEdge> edges = hasProfileFilter ? fkGraph(c, schema, scopedTables) : fkGraph(c, schema);
            // Merge user-defined relationships as additional FK edges. In DataScope mode, keep
            // them inside the included profile so unrelated custom edges cannot expand the plan.
            for (UserRelEdge ue : userEdges == null ? List.<UserRelEdge>of() : userEdges) {
                if (ue.parentTable() == null || ue.childTable() == null ||
                        ue.parentTable().isBlank() || ue.childTable().isBlank()) {
                    continue;
                }
                String parentLower = ue.parentTable().toLowerCase(Locale.ROOT);
                String childLower = ue.childTable().toLowerCase(Locale.ROOT);
                if (hasProfileFilter && (!includedLower.contains(parentLower) || !includedLower.contains(childLower))) {
                    continue;
                }
                edges.add(new FkEdge(ue.childTable(), ue.childColumn(),
                                     ue.parentTable(), ue.parentColumn(), "USER", ue.relRefId()));
            }
            edges = selectRelationshipEdges(edges, traversalDirections);

            SubsetPlan plan = new SubsetPlan();
            plan.driverTable = driverTable;
            plan.schemaName = schema;
            plan.filter = driverFilter;
            plan.driverRowLimit = normalizeRowLimit(maxDriverRows);
            plan.includeRelated = globalQ1 || globalQ2;
            plan.includeParents = globalQ1;
            plan.includeChildren = globalQ2;
            plan.mode = traversalMode(plan.includeRelated, plan.includeParents, plan.includeChildren);
            guardFilter(driverFilter);

            // 1) Seed driver table — OPTIONAL. A multi-start blueprint can have no driver and instead seed
            //    from one or more INDEPENDENT tables (step 2). Only seed the driver when one is configured.
            if (driverTable != null && !driverTable.isBlank()) {
                TableDirective driverDir = dmap.get(driverTable.toLowerCase(Locale.ROOT));
                int driverLimit = Math.min(plan.driverRowLimit == null ? MAX_DRIVER_ROWS : plan.driverRowLimit,
                        directiveLimit(driverDir));
                Optional<String> driverPkOpt = primaryKeyOptional(c, schema, driverTable);
                if (driverPkOpt.isEmpty()) {
                    plan.slices.put(driverTable, TableSlice.keyless(driverTable, driverFilter, driverLimit));
                    plan.loadOrder = new ArrayList<>(plan.slices.keySet());
                    plan.rowCounts.put(driverTable, countRows(c, schema, driverTable, driverFilter, driverLimit));
                    plan.totalRows = plan.rowCounts.values().stream().mapToLong(Integer::longValue).sum();
                    return plan;
                }
                String driverPk = driverPkOpt.get();
                LinkedHashSet<String> driverKeys = selectKeys(c, schema, driverTable, driverPk, driverFilter, driverLimit);
                plan.slices.put(driverTable, TableSlice.keyed(driverTable, driverPk, driverKeys));
            }

            // 2) Seed all INDEPENDENT tables up front (before FK closure)
            for (TableDirective d : dmap.values()) {
                if (!d.included()) continue;
                if (d.table().equalsIgnoreCase(driverTable)) continue;
                if (!"INDEPENDENT".equals(d.referentialStrategy())) continue;
                guardFilter(d.filterExpr());
                Optional<String> pk = primaryKeyOptional(c, schema, d.table());
                int tableLimit = directiveLimit(d);
                if (pk.isEmpty()) {
                    plan.slices.put(d.table(), TableSlice.keyless(d.table(), d.filterExpr(), tableLimit));
                } else {
                    LinkedHashSet<String> keys = selectKeys(c, schema, d.table(), pk.get(), d.filterExpr(), tableLimit);
                    if (!plan.slices.containsKey(d.table())) {
                        plan.slices.put(d.table(), TableSlice.keyed(d.table(), pk.get(), keys));
                    } else {
                        plan.slices.get(d.table()).pkValues().addAll(keys);
                        capKeys(plan.slices.get(d.table()), tableLimit);
                    }
                }
            }

            // 3) FK closure with per-table Q1/Q2 and strategy.
            //    Optim-style DEFER runs as a second phase: deferred directions sit out the primary
            //    closure entirely, then activate once every primary extraction path has converged.
            //    Their consequences (new rows pulling further parents/children) still cascade,
            //    because the phase itself loops to convergence under the same guard.
            int guardLimit = Math.max(12, edges.size() * 2 + plan.slices.size() + 4);
            boolean[] phases = hasDeferredDirectives(dmap.values()) ? new boolean[]{false, true} : new boolean[]{false};
            boolean exhausted = false;
            for (boolean deferPhase : phases) {
            boolean changed = true; int guard = 0;
            while (changed && guard++ < guardLimit) {
                changed = false;

                // Q2: expand children of tables already in plan
                for (FkEdge e : edges) {
                    TableSlice parent = plan.slices.get(e.parentTable());
                    if (parent == null || parent.pkValues().isEmpty()) continue;
                    // Should parent's children be expanded?
                    // Per-relationship traversal rule takes precedence over per-table Q2 override.
                    TableDirective parentDir = dmap.get(e.parentTable().toLowerCase(Locale.ROOT));
                    String dirQ2 = traversalDirection(traversalDirections, e);
                    boolean q2 = dirQ2 != null ? ("BOTH".equals(dirQ2) || "Q2_ONLY".equals(dirQ2)) : resolveQ2(parentDir, globalQ2, deferPhase);
                    if (!q2) continue;
                    // Is child excluded?
                    TableDirective childDir = dmap.get(e.childTable().toLowerCase(Locale.ROOT));
                    if (childDir != null && !childDir.included()) continue;
                    // If a profile is configured, skip tables that were not explicitly included.
                    if (hasProfileFilter && childDir == null) continue;
                    // Is child INDEPENDENT? Already seeded; skip FK-based selection.
                    if (childDir != null && "INDEPENDENT".equals(childDir.referentialStrategy())) continue;
                    Optional<String> childPk = primaryKeyOptional(c, schema, e.childTable());
                    if (childPk.isEmpty()) {
                        warnOnce(plan, "Skipping child '" + e.childTable() + "': no primary key.");
                        continue;
                    }
                    Set<String> parentValues = parent.pkValues();
                    if (!e.parentColumn().equalsIgnoreCase(parent.pkColumn())) {
                        parentValues = selectColumnIn(c, schema, e.parentTable(), e.parentColumn(), e.parentColumn(), parent.pkValues());
                    }
                    LinkedHashSet<String> childKeys = selectKeysIn(c, schema, e.childTable(), childPk.get(), e.childColumn(), parentValues);
                    changed |= merge(plan, e.childTable(), childPk.get(), childKeys);
                }

                // Q1: pull parents for each child already in plan
                for (FkEdge e : edges) {
                    TableSlice child = plan.slices.get(e.childTable());
                    if (child == null || child.pkValues().isEmpty()) continue;
                    // Should child's parents be expanded?
                    // Per-relationship traversal rule takes precedence over per-table Q1 override.
                    TableDirective childDir = dmap.get(e.childTable().toLowerCase(Locale.ROOT));
                    String dirQ1 = traversalDirection(traversalDirections, e);
                    boolean q1 = dirQ1 != null ? ("BOTH".equals(dirQ1) || "Q1_ONLY".equals(dirQ1)) : resolveQ1(childDir, globalQ1, deferPhase);
                    if (!q1) continue;
                    // Is parent excluded?
                    TableDirective parentDir = dmap.get(e.parentTable().toLowerCase(Locale.ROOT));
                    if (parentDir != null && !parentDir.included()) continue;
                    // If a profile is configured, skip tables that were not explicitly included.
                    if (hasProfileFilter && parentDir == null) continue;
                    Optional<String> parentPk = primaryKeyOptional(c, schema, e.parentTable());
                    if (parentPk.isEmpty()) {
                        warnOnce(plan, "Skipping parent '" + e.parentTable() + "': no primary key.");
                        continue;
                    }
                    LinkedHashSet<String> fkValues = selectColumnIn(c, schema, e.childTable(), e.childColumn(), child.pkColumn(), child.pkValues());
                    LinkedHashSet<String> parentKeys = e.parentColumn().equalsIgnoreCase(parentPk.get())
                            ? fkValues
                            : selectKeysIn(c, schema, e.parentTable(), parentPk.get(), e.parentColumn(), fkValues);
                    changed |= merge(plan, e.parentTable(), parentPk.get(), parentKeys);
                }
            }
            exhausted |= changed;
            }
            if (exhausted) warnOnce(plan, "Traversal stopped after " + guardLimit + " passes. Review FK cycles or directive settings.");

            // Build a canonical lower-case → actual-key map for case-insensitive lookup into plan.slices
            Map<String, String> sliceKeyMap = new HashMap<>();
            for (String k : plan.slices.keySet()) sliceKeyMap.put(k.toLowerCase(Locale.ROOT), k);

            // 4) Apply per-table filter_expr to FOLLOW_PARENT or INHERIT tables that have one.
            //    Re-queries the PK set filtered by filterExpr, retaining only rows that satisfy both
            //    the FK-closure selection AND the per-table filter.
            for (TableDirective d : dmap.values()) {
                if (!d.included()) continue;
                if ("INDEPENDENT".equals(d.referentialStrategy())) continue; // already used during seeding
                if (d.filterExpr() == null || d.filterExpr().isBlank()) continue;
                String actualKey = sliceKeyMap.get(d.table().toLowerCase(Locale.ROOT));
                TableSlice slice = actualKey != null ? plan.slices.get(actualKey) : null;
                if (slice != null) {
                    guardFilter(d.filterExpr());
                    if (!slice.keyless() && !slice.pkValues().isEmpty()) {
                        LinkedHashSet<String> filtered = filterKeys(c, schema, slice.table(), slice.pkColumn(), slice.pkValues(), d.filterExpr());
                        slice.pkValues().retainAll(filtered);
                    }
                }
            }

            // 5) Remove EXCLUDED tables from final slices (case-insensitive)
            Set<String> excludedLower = dmap.values().stream()
                    .filter(d -> !d.included())
                    .map(d -> d.table().toLowerCase(Locale.ROOT))
                    .collect(java.util.stream.Collectors.toSet());
            plan.slices.keySet().removeIf(k -> excludedLower.contains(k.toLowerCase(Locale.ROOT)));
            applyRowLimits(plan, dmap);

            plan.loadOrder = topoOrder(plan.slices.keySet(), edges);
            for (TableSlice s : plan.slices.values()) plan.rowCounts.put(s.table(), s.pkValues().size());
            plan.totalRows = plan.rowCounts.values().stream().mapToLong(Integer::longValue).sum();
            return plan;
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("Subset planning failed: " + e.getMessage()); }
    }

    /** Build the FK graph from JDBC metadata. */
    public List<FkEdge> fkGraph(Connection c) throws SQLException {
        return fkGraph(c, DataSourceService.schemaOf(c));
    }

    public List<FkEdge> fkGraph(Connection c, String schema) throws SQLException {
        List<FkEdge> edges = new ArrayList<>();
        List<String> tables = new ArrayList<>();
        try (ResultSet rs = c.getMetaData().getTables(null, schema, "%", new String[]{"TABLE"})) {
            while (rs.next()) tables.add(rs.getString("TABLE_NAME"));
        }
        for (String t : tables) {
            try (ResultSet rs = c.getMetaData().getImportedKeys(null, schema, t)) {
                while (rs.next()) {
                    edges.add(new FkEdge(rs.getString("FKTABLE_NAME"), rs.getString("FKCOLUMN_NAME"),
                                         rs.getString("PKTABLE_NAME"), rs.getString("PKCOLUMN_NAME")));
                }
            }
        }
        return edges;
    }

    public List<FkEdge> fkGraph(Connection c, String schema, Collection<String> scopeTables) throws SQLException {
        if (scopeTables == null || scopeTables.isEmpty()) return fkGraph(c, schema);
        List<FkEdge> edges = new ArrayList<>();
        Set<String> scopeLower = new LinkedHashSet<>();
        Map<String, String> requestedNames = new LinkedHashMap<>();
        List<String> tables = new ArrayList<>();
        for (String table : scopeTables) {
            if (table == null || table.isBlank()) continue;
            String lower = table.toLowerCase(Locale.ROOT);
            if (scopeLower.add(lower)) {
                requestedNames.put(lower, table);
                tables.add(metadataIdentifier(c, table));
            }
        }
        if (tables.isEmpty()) return edges;
        for (String t : tables) {
            try (ResultSet rs = c.getMetaData().getImportedKeys(null, schema, t)) {
                while (rs.next()) {
                    String childTable = rs.getString("FKTABLE_NAME");
                    String parentTable = rs.getString("PKTABLE_NAME");
                    if (!scopeLower.contains(childTable.toLowerCase(Locale.ROOT)) ||
                            !scopeLower.contains(parentTable.toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                    edges.add(new FkEdge(requestedNames.get(childTable.toLowerCase(Locale.ROOT)), rs.getString("FKCOLUMN_NAME"),
                                         requestedNames.get(parentTable.toLowerCase(Locale.ROOT)), rs.getString("PKCOLUMN_NAME")));
                }
            }
        }
        return edges;
    }

    public String primaryKey(Connection c, String table) throws SQLException {
        return primaryKey(c, null, table);
    }

    public String primaryKey(Connection c, String schema, String table) throws SQLException {
        return primaryKeyOptional(c, schema, table)
                .orElseThrow(() -> ApiException.bad("Table " + table + " has no primary key - subsetting needs PKs"));
    }

    public Optional<String> primaryKeyOptional(Connection c, String table) throws SQLException {
        return primaryKeyOptional(c, null, table);
    }

    public Optional<String> primaryKeyOptional(Connection c, String schema, String table) throws SQLException {
        try (ResultSet rs = c.getMetaData().getPrimaryKeys(null, schema == null ? DataSourceService.schemaOf(c) : schema,
                metadataIdentifier(c, table))) {
            if (rs.next()) return Optional.of(rs.getString("COLUMN_NAME"));
        }
        return Optional.empty();
    }

    /**
     * Compute a referentially intact subset plan.
     * @param filter SQL boolean expression over the driver table (validated read-only by statement shape)
     */
    public SubsetPlan plan(Long dataSourceId, String driverTable, String filter, int maxDriverRows) {
        return plan(dataSourceId, null, driverTable, filter, maxDriverRows, true, List.of());
    }

    public SubsetPlan plan(Long dataSourceId, String driverTable, String filter, int maxDriverRows, boolean includeRelated) {
        return plan(dataSourceId, null, driverTable, filter, maxDriverRows, includeRelated, List.of());
    }

    public SubsetPlan plan(Long dataSourceId, String schemaName, String driverTable, String filter,
                           int maxDriverRows, boolean includeRelated, List<TableCriterion> tableCriteria) {
        return plan(dataSourceId, schemaName, driverTable, filter, maxDriverRows,
                includeRelated, includeRelated, includeRelated, tableCriteria);
    }

    public SubsetPlan plan(Long dataSourceId, String schemaName, String driverTable, String filter,
                           int maxDriverRows, boolean includeRelated, boolean includeParents,
                           boolean includeChildren, List<TableCriterion> tableCriteria) {
        DataSourceEntity ds = dataSources.get(dataSourceId);
        try (Connection c = connections.open(ds)) {
            String schema = DataSourceService.normalizeSchema(c, schemaName);
            List<FkEdge> edges = fkGraph(c, schema);
            SubsetPlan plan = new SubsetPlan();
            plan.driverTable = driverTable;
            plan.schemaName = schema;
            plan.filter = filter;
            plan.driverRowLimit = normalizeRowLimit(maxDriverRows);
            plan.includeRelated = includeRelated;
            plan.includeParents = includeRelated && includeParents;
            plan.includeChildren = includeRelated && includeChildren;
            plan.mode = traversalMode(plan.includeRelated, plan.includeParents, plan.includeChildren);
            plan.criteria = normalizeCriteria(tableCriteria);
            guardFilter(filter);

            // 1) driver PKs
            Optional<String> driverPkOpt = primaryKeyOptional(c, schema, driverTable);
            if (driverPkOpt.isEmpty()) {
                int limit = plan.driverRowLimit == null ? MAX_DRIVER_ROWS : plan.driverRowLimit;
                plan.includeRelated = false;
                plan.includeParents = false;
                plan.includeChildren = false;
                plan.mode = "DRIVER_ROW_LIMIT";
                plan.warnings.add("Driver table '" + driverTable + "' has no primary key. Planned a driver-only row-limited slice; FK closure requires key metadata.");
                plan.slices.put(driverTable, TableSlice.keyless(driverTable, filter, limit));
                plan.loadOrder = List.of(driverTable);
                int rows = countRows(c, schema, driverTable, filter, limit);
                plan.rowCounts.put(driverTable, rows);
                plan.totalRows = rows;
                return plan;
            }

            String driverPk = driverPkOpt.get();
            LinkedHashSet<String> driverKeys = selectKeys(c, schema, driverTable, driverPk, filter,
                    plan.driverRowLimit == null ? MAX_DRIVER_ROWS : plan.driverRowLimit);
            plan.slices.put(driverTable, TableSlice.keyed(driverTable, driverPk, driverKeys));

            for (TableCriterion criterion : plan.criteria) {
                if (criterion.table().equalsIgnoreCase(driverTable)) continue;
                Optional<String> pk = primaryKeyOptional(c, schema, criterion.table());
                if (pk.isEmpty()) {
                    warnOnce(plan, "Skipping criterion for '" + criterion.table() + "' because it has no primary key for subset tracking.");
                    continue;
                }
                LinkedHashSet<String> keys = selectKeys(c, schema, criterion.table(), pk.get(), criterion.filter(),
                        criterion.rowLimit() == null ? MAX_DRIVER_ROWS : criterion.rowLimit());
                merge(plan, criterion.table(), pk.get(), keys);
            }

            if (!plan.includeRelated || (!plan.includeParents && !plan.includeChildren)) {
                plan.includeRelated = false;
                plan.includeParents = false;
                plan.includeChildren = false;
                plan.mode = "DRIVER_ROW_LIMIT";
                plan.loadOrder = new ArrayList<>(plan.slices.keySet());
                for (TableSlice s : plan.slices.values()) plan.rowCounts.put(s.table(), s.pkValues().size());
                plan.totalRows = plan.rowCounts.values().stream().mapToLong(Integer::longValue).sum();
                return plan;
            }

            // 2) closure: Q2 children, Q1 parents, repeated until cycles stop adding rows.
            boolean changed = true; int guard = 0;
            int guardLimit = Math.max(12, edges.size() * 2 + plan.slices.size() + 4);
            while (changed && guard++ < guardLimit) {
                changed = false;
                if (plan.includeChildren) {
                    for (FkEdge e : edges) {
                        TableSlice parent = plan.slices.get(e.parentTable());
                        if (parent == null || parent.pkValues().isEmpty()) continue;
                        Optional<String> childPk = primaryKeyOptional(c, schema, e.childTable());
                        if (childPk.isEmpty()) {
                            warnOnce(plan, "Skipping child table '" + e.childTable() + "' because it has no primary key for subset tracking.");
                            continue;
                        }
                        Set<String> parentValues = parent.pkValues();
                        if (!e.parentColumn().equalsIgnoreCase(parent.pkColumn())) {
                            parentValues = selectColumnIn(c, schema, e.parentTable(), e.parentColumn(), parent.pkColumn(), parent.pkValues());
                        }
                        LinkedHashSet<String> childKeys = selectKeysIn(c, schema, e.childTable(), childPk.get(), e.childColumn(), parentValues);
                        changed |= merge(plan, e.childTable(), childPk.get(), childKeys);
                    }
                }
                if (plan.includeParents) {
                    for (FkEdge e : edges) {
                        TableSlice child = plan.slices.get(e.childTable());
                        if (child == null || child.pkValues().isEmpty()) continue;
                        Optional<String> parentPk = primaryKeyOptional(c, schema, e.parentTable());
                        if (parentPk.isEmpty()) {
                            warnOnce(plan, "Skipping parent table '" + e.parentTable() + "' because it has no primary key for subset tracking.");
                            continue;
                        }
                        LinkedHashSet<String> fkValues = selectColumnIn(c, schema, e.childTable(), e.childColumn(), child.pkColumn(), child.pkValues());
                        LinkedHashSet<String> parentKeys = e.parentColumn().equalsIgnoreCase(parentPk.get())
                                ? fkValues
                                : selectKeysIn(c, schema, e.parentTable(), parentPk.get(), e.parentColumn(), fkValues);
                        changed |= merge(plan, e.parentTable(), parentPk.get(), parentKeys);
                    }
                }
            }
            if (changed) {
                warnOnce(plan, "Traversal stopped after " + guardLimit + " passes. Review FK cycles, filters, or Q1/Q2 settings.");
            }

            // 3) load order: topological, parents before children
            plan.loadOrder = topoOrder(plan.slices.keySet(), edges);
            for (TableSlice s : plan.slices.values()) plan.rowCounts.put(s.table(), s.pkValues().size());
            plan.totalRows = plan.rowCounts.values().stream().mapToLong(Integer::longValue).sum();
            return plan;
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("Subset planning failed: " + e.getMessage()); }
    }

    private boolean merge(SubsetPlan plan, String table, String pk, LinkedHashSet<String> keys) {
        TableSlice existing = plan.slices.get(table);
        boolean changed;
        if (existing == null) {
            plan.slices.put(table, TableSlice.keyed(table, pk, keys));
            changed = !keys.isEmpty();
        } else {
            changed = existing.pkValues().addAll(keys);
        }
        enforceKeyBudget(plan);
        return changed;
    }

    /**
     * Bound the in-memory key set built during FK closure. The closure holds selected primary-key values on the
     * heap; a wide fan-out (large driver set × many related tables) could otherwise exhaust memory. Fail fast
     * with actionable guidance instead of OOM-ing. Raise the ceiling with FORGETDM_SUBSET_MAX_KEYS when heap allows.
     */
    private void enforceKeyBudget(SubsetPlan plan) {
        long total = 0;
        for (TableSlice s : plan.slices.values()) total += s.pkValues().size();
        if (total > MAX_TOTAL_SUBSET_KEYS)
            throw ApiException.bad("Subset closure exceeded the key budget (" + total + " > " + MAX_TOTAL_SUBSET_KEYS
                    + " keys across " + plan.slices.size() + " tables). Narrow the extraction: lower Max driver rows, "
                    + "add a driver or per-table filter, or turn off Q1/Q2 on tables that fan out widely. "
                    + "(Raise the limit with FORGETDM_SUBSET_MAX_KEYS if you have the heap.)");
    }

    private static long envLong(String name, long fallback) {
        try {
            String v = System.getenv(name);
            return v == null || v.isBlank() ? fallback : Math.max(1L, Long.parseLong(v.trim()));
        } catch (Exception e) { return fallback; }
    }

    private static int directiveLimit(TableDirective d) {
        if (d == null || d.rowLimit() == null || d.rowLimit() <= 0) return MAX_DRIVER_ROWS;
        return Math.min(d.rowLimit(), MAX_DRIVER_ROWS);
    }

    private static void applyRowLimits(SubsetPlan plan, Map<String, TableDirective> dmap) {
        if (dmap == null || dmap.isEmpty()) return;
        for (Map.Entry<String, TableDirective> entry : dmap.entrySet()) {
            TableDirective d = entry.getValue();
            if (d == null || d.rowLimit() == null || d.rowLimit() <= 0) continue;
            TableSlice slice = plan.slices.get(d.table());
            if (slice == null) {
                String actualKey = plan.slices.keySet().stream()
                        .filter(k -> k.equalsIgnoreCase(d.table()))
                        .findFirst().orElse(null);
                slice = actualKey == null ? null : plan.slices.get(actualKey);
            }
            if (slice != null) capKeys(slice, directiveLimit(d));
        }
    }

    private static void capKeys(TableSlice slice, int limit) {
        if (slice == null || slice.keyless() || limit <= 0 || slice.pkValues().size() <= limit) return;
        int seen = 0;
        Iterator<String> it = slice.pkValues().iterator();
        while (it.hasNext()) {
            it.next();
            if (++seen > limit) it.remove();
        }
    }

    private LinkedHashSet<String> selectKeys(Connection c, String table, String pk, String filter, int max) throws SQLException {
        return selectKeys(c, null, table, pk, filter, max);
    }

    private LinkedHashSet<String> selectKeys(Connection c, String schema, String table, String pk, String filter, int max) throws SQLException {
        String sql = "SELECT " + q(c, pk) + " FROM " + q(c, schema, table) + where(filter) + " ORDER BY " + q(c, pk);
        LinkedHashSet<String> out = new LinkedHashSet<>();
        try (Statement st = c.createStatement()) {
            st.setMaxRows(max);
            try (ResultSet rs = st.executeQuery(sql)) { while (rs.next()) out.add(rs.getString(1)); }
        }
        return out;
    }

    private LinkedHashSet<String> selectKeysIn(Connection c, String table, String selectCol, String inCol, Set<String> values) throws SQLException {
        return selectKeysIn(c, null, table, selectCol, inCol, values);
    }

    private LinkedHashSet<String> selectKeysIn(Connection c, String schema, String table, String selectCol, String inCol, Set<String> values) throws SQLException {
        return chunkedIn(c, "SELECT " + q(c, selectCol) + " FROM " + q(c, schema, table) + " WHERE " + q(c, inCol), values);
    }

    private LinkedHashSet<String> selectColumnIn(Connection c, String table, String selectCol, String inCol, Set<String> values) throws SQLException {
        return selectColumnIn(c, null, table, selectCol, inCol, values);
    }

    private LinkedHashSet<String> selectColumnIn(Connection c, String schema, String table, String selectCol, String inCol, Set<String> values) throws SQLException {
        return chunkedIn(c, "SELECT DISTINCT " + q(c, selectCol) + " FROM " + q(c, schema, table) + " WHERE " + q(c, inCol), values);
    }

    private LinkedHashSet<String> chunkedIn(Connection c, String prefix, Set<String> values) throws SQLException {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (values == null || values.isEmpty()) return out;
        List<String> all = new ArrayList<>(values);
        int chunkLimit = keyChunkSize(c);
        for (int i = 0; i < all.size(); i += chunkLimit) {
            List<String> chunk = all.subList(i, Math.min(i + chunkLimit, all.size()));
            String sql = prefix + " IN (" + String.join(",", Collections.nCopies(chunk.size(), "?")) + ")";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int j = 0; j < chunk.size(); j++) bindValue(ps, j + 1, chunk.get(j));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) { String v = rs.getString(1); if (v != null) out.add(v); }
                }
            }
        }
        return out;
    }

    /**
     * Bind a string PK value with the correct JDBC type.
     * PK values are always stored as strings internally, but the actual column may be numeric.
     * Using setString() against an integer column causes PostgreSQL to reject the query with
     * "operator does not exist: integer = character varying". Parsing to Long first avoids this.
     */
    private static void bindValue(PreparedStatement ps, int idx, String value) throws SQLException {
        if (value == null) { ps.setNull(idx, java.sql.Types.VARCHAR); return; }
        try {
            ps.setLong(idx, Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            ps.setString(idx, value);
        }
    }

    private int countRows(Connection c, String table, String filter, int max) throws SQLException {
        return countRows(c, null, table, filter, max);
    }

    private int countRows(Connection c, String schema, String table, String filter, int max) throws SQLException {
        String sql = "SELECT 1 FROM " + q(c, schema, table) + where(filter);
        int rows = 0;
        try (Statement st = c.createStatement()) {
            st.setMaxRows(max);
            try (ResultSet rs = st.executeQuery(sql)) { while (rs.next()) rows++; }
        }
        return rows;
    }

    private static String where(String filter) {
        guardFilter(filter);
        return filter == null || filter.isBlank() ? "" : " WHERE " + filter;
    }

    /** Re-query to keep only PKs that satisfy an additional filterExpr for a FOLLOW_PARENT table. */
    private LinkedHashSet<String> filterKeys(Connection c, String schema, String table, String pkColumn,
                                              LinkedHashSet<String> existingKeys, String filterExpr) throws SQLException {
        if (existingKeys == null || existingKeys.isEmpty()) return existingKeys;
        LinkedHashSet<String> result = new LinkedHashSet<>();
        List<String> all = new ArrayList<>(existingKeys);
        int chunkLimit = keyChunkSize(c);
        for (int i = 0; i < all.size(); i += chunkLimit) {
            List<String> chunk = all.subList(i, Math.min(i + chunkLimit, all.size()));
            String sql = "SELECT " + q(c, pkColumn) + " FROM " + q(c, schema, table)
                    + " WHERE " + q(c, pkColumn) + " IN (" + String.join(",", Collections.nCopies(chunk.size(), "?")) + ")"
                    + " AND (" + filterExpr + ")";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int j = 0; j < chunk.size(); j++) bindValue(ps, j + 1, chunk.get(j));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) { String v = rs.getString(1); if (v != null) result.add(v); }
                }
            }
        }
        return result;
    }

    /** Effective Q1 for a child table in the given phase. DEFER counts as OFF during the
     *  primary closure and ON once all primary extraction paths have converged. */
    private static boolean resolveQ1(TableDirective dir, boolean globalQ1, boolean deferPhase) {
        return resolveQMode(dir == null ? null : dir.q1Mode(), globalQ1, deferPhase);
    }

    /** Effective Q2 for a parent table in the given phase (see {@link #resolveQ1}). */
    private static boolean resolveQ2(TableDirective dir, boolean globalQ2, boolean deferPhase) {
        return resolveQMode(dir == null ? null : dir.q2Mode(), globalQ2, deferPhase);
    }

    private static boolean resolveQMode(String mode, boolean globalDefault, boolean deferPhase) {
        if (mode == null || mode.isBlank()) return globalDefault;
        return switch (mode.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "YES" -> true;
            case "NO" -> false;
            case "DEFER" -> deferPhase;
            default -> globalDefault;
        };
    }

    /**
     * Select one concrete relationship for each parent/child pair. Explicit source-specific
     * rules win. Otherwise the catalog FK is preferred; when none exists, the tool-defined
     * relationship is selected automatically. NONE removes the edge from traversal and order.
     */
    static List<FkEdge> selectRelationshipEdges(List<FkEdge> edges, Map<String, String> directions) {
        if (edges == null || edges.isEmpty()) return List.of();
        Map<String, LinkedHashMap<String, List<FkEdge>>> grouped = new LinkedHashMap<>();
        for (FkEdge edge : edges) {
            grouped.computeIfAbsent(relationshipPairKey(edge), ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(relationshipRuleKey(edge), ignored -> new ArrayList<>())
                    .add(edge);
        }

        Map<String, String> configured = directions == null ? Map.of() : directions;
        List<FkEdge> selected = new ArrayList<>();
        for (Map.Entry<String, LinkedHashMap<String, List<FkEdge>>> pairEntry : grouped.entrySet()) {
            LinkedHashMap<String, List<FkEdge>> candidates = pairEntry.getValue();
            boolean hasSpecificChoice = candidates.keySet().stream().anyMatch(configured::containsKey);
            if (hasSpecificChoice) {
                for (Map.Entry<String, List<FkEdge>> candidate : candidates.entrySet()) {
                    String direction = configured.get(candidate.getKey());
                    if (direction != null && !"NONE".equalsIgnoreCase(direction)) selected.addAll(candidate.getValue());
                }
                continue;
            }

            if ("NONE".equalsIgnoreCase(configured.get(pairEntry.getKey()))) continue;
            List<FkEdge> preferred = candidates.entrySet().stream()
                    .filter(entry -> entry.getKey().contains(":DB"))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElseGet(() -> candidates.values().iterator().next());
            selected.addAll(preferred);
        }
        return selected;
    }

    private static String traversalDirection(Map<String, String> directions, FkEdge edge) {
        String direction = directions.get(relationshipRuleKey(edge));
        if (direction == null) direction = directions.get(relationshipPairKey(edge));
        return "INHERIT".equalsIgnoreCase(direction) ? null : direction;
    }

    private static String relationshipPairKey(FkEdge edge) {
        return edge.parentTable().toLowerCase(Locale.ROOT) + "->" + edge.childTable().toLowerCase(Locale.ROOT);
    }

    private static String relationshipRuleKey(FkEdge edge) {
        String source = edge.source() == null ? "DB" : edge.source().toUpperCase(Locale.ROOT);
        String key = relationshipPairKey(edge) + ":" + source;
        return edge.relRefId() == null ? key : key + ":" + edge.relRefId();
    }

    private static boolean hasDeferredDirectives(Collection<TableDirective> directives) {
        return directives.stream().anyMatch(d ->
                "DEFER".equalsIgnoreCase(String.valueOf(d.q1Mode())) || "DEFER".equalsIgnoreCase(String.valueOf(d.q2Mode())));
    }

    private static void warnOnce(SubsetPlan plan, String warning) {
        if (!plan.warnings.contains(warning)) plan.warnings.add(warning);
    }

    public static Integer normalizeRowLimit(int requested) {
        if (requested <= 0) return null;
        return Math.min(requested, MAX_DRIVER_ROWS);
    }

    public static String traversalMode(boolean includeRelated, boolean includeParents, boolean includeChildren) {
        if (!includeRelated || (!includeParents && !includeChildren)) return "DRIVER_ROW_LIMIT";
        if (includeParents && includeChildren) return "Q1_Q2_CYCLE_CLOSURE";
        if (includeParents) return "Q1_PARENT_CLOSURE";
        return "Q2_CHILD_CLOSURE";
    }

    private static List<TableCriterion> normalizeCriteria(List<TableCriterion> criteria) {
        if (criteria == null) return List.of();
        List<TableCriterion> out = new ArrayList<>();
        for (TableCriterion c : criteria) {
            if (c == null || c.table() == null || c.table().isBlank()) continue;
            guardFilter(c.filter());
            out.add(new TableCriterion(c.table().trim(), blankToNull(c.filter()),
                    c.rowLimit() == null || c.rowLimit() <= 0 ? null : Math.min(c.rowLimit(), MAX_DRIVER_ROWS)));
        }
        return out;
    }

    public static List<String> topoOrder(Set<String> tables, List<FkEdge> edges) {
        Map<String, Set<String>> deps = new HashMap<>(); // table -> parents it depends on
        for (String t : tables) deps.put(t, new HashSet<>());
        for (FkEdge e : edges)
            if (tables.contains(e.childTable()) && tables.contains(e.parentTable()) && !e.childTable().equals(e.parentTable()))
                deps.get(e.childTable()).add(e.parentTable());
        List<String> order = new ArrayList<>(); Set<String> done = new HashSet<>();
        int guard = 0;
        while (done.size() < tables.size() && guard++ <= tables.size() + 2) {
            for (String t : tables)
                if (!done.contains(t) && done.containsAll(deps.get(t))) { order.add(t); done.add(t); }
        }
        for (String t : tables) if (!done.contains(t)) order.add(t); // FK cycles: append; loader defers constraints
        return order;
    }

    /** Defense-in-depth: subset filters must be plain boolean expressions, not statements. */
    public static void guardFilter(String filter) {
        if (filter == null) return;
        String f = filter.toLowerCase();
        if (f.contains(";") || f.contains("--") || f.matches(".*\\b(insert|update|delete|drop|alter|grant|truncate)\\b.*"))
            throw ApiException.bad("Filter may only be a boolean WHERE expression");
    }

    static String q(String ident) {
        if (!ident.matches("[A-Za-z0-9_]+")) throw ApiException.bad("Illegal identifier: " + ident);
        return "\"" + ident + "\"";
    }

    static String q(String schema, String table) {
        return schema == null || schema.isBlank() ? q(table) : q(schema) + "." + q(table);
    }

    static String q(Connection connection, String ident) throws SQLException {
        if (ident == null || !ident.matches("[A-Za-z0-9_$#]+")) throw ApiException.bad("Illegal identifier: " + ident);
        String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
        String normalized = product.contains("oracle") || product.contains("db2")
                ? ident.toUpperCase(Locale.ROOT) : ident;
        if (product.contains("mysql") || product.contains("mariadb")) return "`" + normalized + "`";
        if (product.contains("sql server") || product.contains("microsoft")) return "[" + normalized + "]";
        return "\"" + normalized + "\"";
    }

    static String q(Connection connection, String schema, String table) throws SQLException {
        return schema == null || schema.isBlank() ? q(connection, table)
                : q(connection, schema) + "." + q(connection, table);
    }

    private static String metadataIdentifier(Connection connection, String identifier) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        if (metadata.storesUpperCaseIdentifiers()) return identifier.toUpperCase(Locale.ROOT);
        if (metadata.storesLowerCaseIdentifiers()) return identifier.toLowerCase(Locale.ROOT);
        return identifier;
    }

    private static int keyChunkSize(Connection connection) throws SQLException {
        String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
        if (product.contains("oracle") || product.contains("db2")) return 900;
        if (product.contains("sql server") || product.contains("microsoft")) return 1_800;
        return KEY_CHUNK_SIZE;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
