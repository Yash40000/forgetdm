package io.forgetdm.subset;

import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/subset")
public class SubsetController {
    private final SubsetService svc;
    public SubsetController(SubsetService svc) { this.svc = svc; }

    /** Dry-run a referentially intact subset plan; execution happens through a SUBSET_MASK provisioning job. */
    @PostMapping("/plan")
    public SubsetService.SubsetPlan plan(@RequestBody Map<String, Object> body) {
        Long dsId = Long.valueOf(String.valueOf(body.get("dataSourceId")));
        String schema = body.get("schemaName") == null ? null : String.valueOf(body.get("schemaName"));
        String driver = String.valueOf(body.get("driverTable"));
        String filter = body.get("filter") == null ? null : String.valueOf(body.get("filter"));
        int max = body.get("maxDriverRows") == null ? 0 : Integer.parseInt(String.valueOf(body.get("maxDriverRows")));
        boolean includeRelated = body.get("includeRelated") == null
                || Boolean.parseBoolean(String.valueOf(body.get("includeRelated")));
        boolean includeParents = body.get("includeParents") == null
                ? includeRelated : Boolean.parseBoolean(String.valueOf(body.get("includeParents")));
        boolean includeChildren = body.get("includeChildren") == null
                ? includeRelated : Boolean.parseBoolean(String.valueOf(body.get("includeChildren")));
        return svc.plan(dsId, schema, driver, filter, max, includeRelated, includeParents, includeChildren,
                criteria(body.get("tableCriteria")));
    }

    @SuppressWarnings("unchecked")
    private static List<SubsetService.TableCriterion> criteria(Object raw) {
        if (!(raw instanceof List<?> rows)) return List.of();
        List<SubsetService.TableCriterion> out = new ArrayList<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> m)) continue;
            String table = stringOrNull(m.get("table"));
            if (table == null) continue;
            String filter = stringOrNull(m.get("filter"));
            Integer limit = intOrNull(m.get("rowLimit"));
            out.add(new SubsetService.TableCriterion(table, filter, limit));
        }
        return out;
    }

    private static String stringOrNull(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value);
        return s.isBlank() ? null : s;
    }

    private static Integer intOrNull(Object value) {
        try {
            if (value == null || String.valueOf(value).isBlank()) return null;
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) { return null; }
    }
}
