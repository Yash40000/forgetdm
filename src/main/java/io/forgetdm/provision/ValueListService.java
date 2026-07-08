package io.forgetdm.provision;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.security.AccessContext;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reference-data value lists ("domains"): system-specific enumerations like bank-a.product_type = A1|A2|A3,
 * defined once and referenced from generator params as {@code @name}. Resolution happens at generation time,
 * so updating a list here updates every plan that references it — no per-plan copies to keep in sync
 * (GenRocket needs a G-Repository sync step for its CSV-backed lists; a DB-backed registry doesn't).
 */
@Service
public class ValueListService {

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,118}");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z0-9_]+");
    private static final int MAX_IMPORT_DISTINCT = 200;

    private final ValueListRepository repo;
    private final DataSourceService dataSources;
    private final ConnectionFactory connections;
    private final AuditService audit;

    public ValueListService(ValueListRepository repo, DataSourceService dataSources,
                            ConnectionFactory connections, AuditService audit) {
        this.repo = repo;
        this.dataSources = dataSources;
        this.connections = connections;
        this.audit = audit;
    }

    public List<ValueListEntity> list() {
        String me = currentUser();
        return repo.findAll().stream()
                .filter(v -> !"PRIVATE".equalsIgnoreCase(v.getVisibility())
                        || (v.getOwnerUsername() != null && v.getOwnerUsername().equalsIgnoreCase(me)))
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .toList();
    }

    public ValueListEntity save(ValueListEntity in) {
        if (in == null || in.getName() == null || in.getName().isBlank())
            throw ApiException.bad("Value list name is required (e.g. bank-a.product_type)");
        String name = in.getName().trim().toLowerCase(Locale.ROOT);
        if (!NAME.matcher(name).matches())
            throw ApiException.bad("Value list name must be lower-case letters/digits/._- (e.g. bank-a.product_type)");
        validateValues(in.getListValues());
        ValueListEntity e = repo.findByNameIgnoreCase(name).orElseGet(ValueListEntity::new);
        if (e.getId() != null) requireEditable(e);
        if (e.getId() == null) {
            e.setName(name);
            e.setOwnerUsername(currentUser());
        }
        e.setDescription(blankToNull(in.getDescription()));
        e.setSystemTag(blankToNull(in.getSystemTag()) == null ? null : in.getSystemTag().trim().toLowerCase(Locale.ROOT));
        e.setListValues(in.getListValues().trim());
        e.setVisibility("PRIVATE".equalsIgnoreCase(in.getVisibility()) ? "PRIVATE" : "GLOBAL");
        e.setUpdatedAt(Instant.now());
        ValueListEntity saved = repo.save(e);
        audit.log(currentUser(), "VALUE_LIST_SAVED", saved.getName() + " (" + countValues(saved.getListValues()) + " values)");
        return saved;
    }

    public void delete(Long id) {
        repo.findById(id).ifPresent(e -> {
            requireEditable(e);
            repo.deleteById(id);
            audit.log(currentUser(), "VALUE_LIST_DELETED", e.getName());
        });
    }

    /**
     * Resolve {@code @name} for a generator: WEIGHTED-family generators need value:weight pairs
     * (weightless entries get :1); everything else gets bare values (numeric :weight suffixes stripped,
     * but literal colons inside values like "10:30" are preserved).
     */
    public String resolveForGenerator(String name, String generator) {
        ValueListEntity e = repo.findByNameIgnoreCase(name.trim())
                .orElseThrow(() -> ApiException.bad("Value list '@" + name + "' not found. Create it under Synthetic → Value Lists."));
        if ("PRIVATE".equalsIgnoreCase(e.getVisibility())
                && (e.getOwnerUsername() == null || !e.getOwnerUsername().equalsIgnoreCase(currentUser())))
            throw ApiException.bad("Value list '@" + name + "' is private to another user.");
        String gen = generator == null ? "" : generator.trim().toUpperCase(Locale.ROOT);
        boolean needWeights = gen.equals("WEIGHTED") || gen.endsWith("_WEIGHTED");
        return adapt(e.getListValues(), needWeights);
    }

    /** Adapt the stored pipe list to the generator's expected shape. Package-visible for tests. */
    static String adapt(String raw, boolean needWeights) {
        List<String> out = new ArrayList<>();
        for (String part : raw.split("\\|")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int idx = p.lastIndexOf(':');
            boolean numericWeight = idx > 0 && idx < p.length() - 1 && p.substring(idx + 1).trim().matches("\\d+");
            if (needWeights) out.add(numericWeight ? p : p + ":1");
            else out.add(numericWeight ? p.substring(0, idx).trim() : p);
        }
        if (out.isEmpty()) throw ApiException.bad("Value list has no usable values");
        return String.join("|", out);
    }

    public record ImportRequest(Long dataSourceId, String schema, String table, String column,
                                String name, String description, String systemTag,
                                Boolean weighted, String visibility) {}

    /** Seed a list from the live system: SELECT DISTINCT + frequencies, optionally stored as weights. */
    public ValueListEntity importFromColumn(ImportRequest req) {
        if (req == null || req.dataSourceId() == null) throw ApiException.bad("dataSourceId is required");
        guardIdent(req.table(), "table");
        guardIdent(req.column(), "column");
        if (req.schema() != null && !req.schema().isBlank()) guardIdent(req.schema(), "schema");
        DataSourceEntity ds = dataSources.get(req.dataSourceId());
        String from = req.schema() == null || req.schema().isBlank() ? req.table() : req.schema() + "." + req.table();
        boolean weighted = Boolean.TRUE.equals(req.weighted());

        List<String> parts = new ArrayList<>();
        long total = 0, skipped = 0;
        try (Connection c = connections.openPooled(ds);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT " + req.column() + " AS v, COUNT(*) AS n FROM " + from
                             + " WHERE " + req.column() + " IS NOT NULL GROUP BY " + req.column() + " ORDER BY n DESC")) {
            ps.setMaxRows(MAX_IMPORT_DISTINCT + 1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (parts.size() >= MAX_IMPORT_DISTINCT)
                        throw ApiException.bad("Column " + from + "." + req.column() + " has more than "
                                + MAX_IMPORT_DISTINCT + " distinct values — that's data, not a reference list.");
                    String v = rs.getString(1);
                    long n = rs.getLong(2);
                    if (v == null || v.isBlank()) continue;
                    if (v.contains("|")) { skipped++; continue; }   // would corrupt the pipe syntax
                    total += n;
                    parts.add(weighted ? v.trim() + ":" + n : v.trim());
                }
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.bad("Could not read distinct values from " + from + "." + req.column() + ": " + e.getMessage());
        }
        if (parts.isEmpty()) throw ApiException.bad("No usable values found in " + from + "." + req.column());

        ValueListEntity in = new ValueListEntity();
        in.setName(req.name() != null && !req.name().isBlank() ? req.name()
                : (blankToNull(req.systemTag()) != null ? req.systemTag().trim() + "." : "") + req.column());
        in.setDescription(req.description() != null && !req.description().isBlank() ? req.description()
                : "Imported from " + ds.getName() + " " + from + "." + req.column() + " (" + parts.size() + " values"
                + (weighted ? ", frequencies of " + total + " rows" : "") + (skipped > 0 ? ", " + skipped + " skipped" : "") + ")");
        in.setSystemTag(req.systemTag());
        in.setListValues(String.join("|", parts));
        in.setVisibility(req.visibility());
        return save(in);
    }

    private void requireEditable(ValueListEntity e) {
        String me = currentUser();
        boolean admin = AccessContext.current().map(p -> p.hasPermission("admin.all")).orElse(false);
        if ("PRIVATE".equalsIgnoreCase(e.getVisibility()) && !admin
                && (e.getOwnerUsername() == null || !e.getOwnerUsername().equalsIgnoreCase(me)))
            throw ApiException.bad("Value list '" + e.getName() + "' is private to " + e.getOwnerUsername());
    }

    private static void validateValues(String values) {
        if (values == null || values.isBlank())
            throw ApiException.bad("Values are required — pipe-delimited, e.g. A1|A2|A3 or A1:60|A2:30|A3:10");
        adapt(values, false);   // throws when nothing usable
    }

    private static void guardIdent(String s, String what) {
        if (s == null || !IDENT.matcher(s).matches()) throw ApiException.bad("Invalid " + what + " name");
    }

    private static int countValues(String values) { return values == null ? 0 : values.split("\\|").length; }
    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }
    private static String currentUser() {
        return AccessContext.current().map(p -> p.username()).orElse("system");
    }
}
