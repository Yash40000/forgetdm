package io.forgetdm.provision;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Owner-private, reusable DataScope provisioning jobs (parity with /api/synthetic/saved-jobs).
 * Running one goes through the normal provisioning maker-checker gate via ProvisioningService.submit().
 */
@RestController
@RequestMapping("/api/datascope/saved-jobs")
public class DataScopeJobController {
    private final DataScopeJobService svc;
    public DataScopeJobController(DataScopeJobService svc) { this.svc = svc; }

    @GetMapping public List<Map<String, Object>> list() { return svc.list(); }
    @GetMapping("/{id}") public Map<String, Object> get(@PathVariable String id) { return svc.get(id); }

    @PostMapping public Map<String, Object> save(@RequestBody DataScopeJobService.SavedJobRequest req) { return svc.save(req); }
    @PutMapping("/{id}") public Map<String, Object> update(@PathVariable String id,
                                                           @RequestBody DataScopeJobService.SavedJobRequest req) {
        return svc.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) { svc.delete(id); }

    @PostMapping("/{id}/run") public Map<String, Object> run(@PathVariable String id) { return svc.run(id); }

    @PutMapping("/{id}/schedule")
    public Map<String, Object> schedule(@PathVariable String id, @RequestBody DataScopeJobService.ScheduleRequest req) {
        return svc.setSchedule(id, req);
    }

    /** Validate a cron expression and return its next run time (for the schedule dialog preview). */
    @PostMapping("/schedule/preview")
    public Map<String, Object> previewSchedule(@RequestBody Map<String, String> body) {
        return svc.previewSchedule(body == null ? null : body.get("cron"),
                body == null ? null : body.get("zone"));
    }
}
