package io.forgetdm.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import io.forgetdm.audit.AuditService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

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
    private final MappingFileService files;
    private final MappingExecutionService executions;
    private final AuditService audit;

    public MappingController(MappingService svc, MappingFileService files, MappingExecutionService executions,
                             AuditService audit) {
        this.svc = svc; this.files = files; this.executions = executions; this.audit = audit;
    }

    @GetMapping public List<MappingEntity> list() { return svc.list(); }

    @GetMapping("/{id}") public MappingEntity get(@PathVariable Long id) { return svc.get(id); }

    @PostMapping public MappingEntity save(@RequestBody MappingEntity body) { return svc.save(body); }

    @DeleteMapping("/{id}") public void delete(@PathVariable Long id) { svc.delete(id); }

    @GetMapping("/{id}/versions") public List<MappingVersionEntity> versions(@PathVariable Long id) { return svc.versions(id); }

    @PostMapping("/{id}/versions/{versionId}/restore")
    public MappingEntity restoreVersion(@PathVariable Long id, @PathVariable Long versionId) { return svc.restoreVersion(id, versionId); }

    @PostMapping("/validate") public Map<String, Object> validate(@RequestBody JsonNode body) { return svc.validateSpec(body); }

    @GetMapping("/{id}/plan") public Map<String, Object> plan(@PathVariable Long id) { return svc.plan(id); }

    @GetMapping("/assets") public List<MappingFileAssetEntity> assets() { return files.list(); }

    @PostMapping(value = "/assets", consumes = "multipart/form-data")
    public MappingFileAssetEntity uploadAsset(@RequestPart("file") MultipartFile file,
                                              @RequestParam(required = false) String name,
                                              @RequestParam(defaultValue = "AUTO") String format,
                                              @RequestParam(required = false) String delimiter,
                                              @RequestParam(defaultValue = "true") boolean header) {
        return files.upload(file, name, format, delimiter, header);
    }

    @GetMapping("/assets/{id}/preview")
    public Map<String, Object> previewAsset(@PathVariable Long id, @RequestParam(defaultValue = "100") int limit) {
        return files.preview(id, limit);
    }

    @DeleteMapping("/assets/{id}") public void deleteAsset(@PathVariable Long id) { files.delete(id); }

    @PostMapping("/{id}/runs") public MappingRunEntity start(@PathVariable Long id, @RequestBody(required = false) JsonNode body) {
        return executions.start(id, body);
    }

    @GetMapping("/runs") public List<MappingRunEntity> runs() { return executions.list(); }

    @GetMapping("/runs/{id}") public MappingRunEntity run(@PathVariable Long id) { return executions.get(id); }

    @PostMapping("/runs/{id}/cancel") public MappingRunEntity cancel(@PathVariable Long id) { return executions.cancel(id); }

    @PostMapping("/runs/{id}/retry") public MappingRunEntity retry(@PathVariable Long id) { return executions.retry(id); }

    @GetMapping("/runs/{id}/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable Long id) {
        MappingExecutionService.InputStreamDownload file = executions.output(id);
        audit.record(null, "MAPPING_OUTPUT_EXPORTED", "MAPPING", "mapping-run", String.valueOf(id),
                file.filename(), "SUCCESS", "Mapping output download prepared", "{\"format\":\"file\"}");
        StreamingResponseBody body = output -> { try (var in = file.stream()) { in.transferTo(output); } };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename().replace("\"", "") + "\"")
                .contentType(MediaType.parseMediaType(file.contentType())).body(body);
    }

    @PostMapping("/preview")
    public Map<String, Object> preview(@RequestBody JsonNode b) {
        Long ds = b.hasNonNull("dataSourceId") ? b.get("dataSourceId").asLong() : null;
        return svc.preview(ds, b.path("sql").asText(null));
    }

    @PostMapping("/preview-spec")
    public Map<String, Object> previewSpec(@RequestBody JsonNode body) { return executions.preview(body); }

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
