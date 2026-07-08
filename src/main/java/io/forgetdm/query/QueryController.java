package io.forgetdm.query;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Data Explorer: run a single read-only SELECT against a connected source and preview up to 1000 rows.
 *   POST /api/query/run  { dataSourceId, sql } → { columns, rows, rowCount, truncated, elapsedMs }
 */
@RestController
@RequestMapping("/api/query")
public class QueryController {

    private final QueryService svc;

    public QueryController(QueryService svc) { this.svc = svc; }

    @PostMapping("/run")
    public Map<String, Object> run(@RequestBody JsonNode body) {
        JsonNode idNode = body.path("dataSourceId");
        Long id = (idNode.isMissingNode() || idNode.isNull()) ? null : idNode.asLong();
        return svc.run(id, body.path("sql").asText(null));
    }
}
