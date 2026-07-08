package io.forgetdm.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Transformation mappings (Informatica-style designer).
 *   GET    /api/mappings           list
 *   GET    /api/mappings/{id}      one
 *   POST   /api/mappings           create or update (id present = update)
 *   DELETE /api/mappings/{id}      delete
 *   POST   /api/mappings/preview   { dataSourceId, sql } → read-only preview rows (capped)
 *   POST   /api/mappings/load      { dataSourceId, sql } → run INSERT…SELECT / CREATE TABLE AS
 */
@RestController
@RequestMapping("/api/mappings")
public class MappingController {

    private final MappingService svc;

    public MappingController(MappingService svc) { this.svc = svc; }

    @GetMapping public List<MappingEntity> list() { return svc.list(); }

    @GetMapping("/{id}") public MappingEntity get(@PathVariable Long id) { return svc.get(id); }

    @PostMapping public MappingEntity save(@RequestBody MappingEntity body) { return svc.save(body); }

    @DeleteMapping("/{id}") public void delete(@PathVariable Long id) { svc.delete(id); }

    @PostMapping("/preview")
    public Map<String, Object> preview(@RequestBody JsonNode b) {
        Long ds = b.hasNonNull("dataSourceId") ? b.get("dataSourceId").asLong() : null;
        return svc.preview(ds, b.path("sql").asText(null));
    }

    @PostMapping("/load")
    public Map<String, Object> load(@RequestBody JsonNode b) {
        Long ds = b.hasNonNull("dataSourceId") ? b.get("dataSourceId").asLong() : null;
        return svc.load(ds, b.path("sql").asText(null));
    }

    /** Multi-target routing: { dataSourceId, statements:[sql…] } — all groups in one transaction. */
    @PostMapping("/load-multi")
    public Map<String, Object> loadMulti(@RequestBody JsonNode b) {
        Long ds = b.hasNonNull("dataSourceId") ? b.get("dataSourceId").asLong() : null;
        List<String> statements = new java.util.ArrayList<>();
        if (b.path("statements").isArray()) b.get("statements").forEach(n -> statements.add(n.asText()));
        return svc.loadMulti(ds, statements);
    }

    /** Cross-database join: { tables:[{dsId,schema,name}], joins:[{type,left,right}], limit } */
    @PostMapping("/federated")
    public Map<String, Object> federated(@RequestBody JsonNode b) {
        return svc.federated(b);
    }
}
