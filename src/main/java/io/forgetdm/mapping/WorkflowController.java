package io.forgetdm.mapping;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Workflows. Under /api/mappings → GET = mapping.read, mutations/run = mapping.manage (filter). */
@RestController
@RequestMapping("/api/mappings/workflows")
public class WorkflowController {

    private final WorkflowService svc;

    public WorkflowController(WorkflowService svc) { this.svc = svc; }

    @GetMapping public List<WorkflowEntity> list() { return svc.list(); }

    @GetMapping("/{id}") public WorkflowEntity get(@PathVariable Long id) { return svc.get(id); }

    @PostMapping public WorkflowEntity save(@RequestBody WorkflowEntity body) { return svc.save(body); }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) { svc.delete(id); }

    /** Starts the run on a background thread; poll GET /{id} → lastRunJson for per-step progress. */
    @PostMapping("/{id}/run")
    public Map<String, Object> run(@PathVariable Long id) { return svc.run(id); }
}
