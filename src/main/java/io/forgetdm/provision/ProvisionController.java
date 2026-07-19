package io.forgetdm.provision;

import io.forgetdm.config.ForgeProps;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/jobs")
public class ProvisionController {
    private final ProvisioningService svc;
    private final ForgeProps props;
    public ProvisionController(ProvisioningService svc, ForgeProps props) {
        this.svc = svc;
        this.props = props;
    }

    @GetMapping public List<ProvisionJobEntity> list() { return svc.list(); }
    @GetMapping("/{id}") public ProvisionJobEntity get(@PathVariable Long id) { return svc.get(id); }
    @PostMapping public ProvisionJobEntity submit(@RequestBody ProvisionJobEntity job) { return svc.submit(job); }
    @PostMapping("/{id}/cancel") public ProvisionJobEntity cancel(@PathVariable Long id) { return svc.cancel(id); }
    @PostMapping("/{id}/retry") public ProvisionJobEntity retry(@PathVariable Long id) { return svc.retry(id); }

    /** Maker-checker: approve/reject a job in AWAITING_APPROVAL (requires provision.approve). */
    @PostMapping("/{id}/approval/approve")
    public ProvisionJobEntity approve(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        return svc.approve(id, body == null ? null : body.get("note"));
    }

    @PostMapping("/{id}/approval/reject")
    public ProvisionJobEntity reject(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        return svc.reject(id, body == null ? null : body.get("note"));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) { svc.delete(id); }

    /** Returns the configured auto-purge retention period in days (0 = disabled). */
    @GetMapping("/retention")
    public Map<String, Object> retention() {
        return Map.of("retentionDays", props.getProvisioning().getJobRetentionDays());
    }

    @GetMapping("/{id}/sample")
    public Map<String, Object> sample(@PathVariable Long id,
                                      @RequestParam String table,
                                      @RequestParam(defaultValue = "5") int limit) {
        return svc.sample(id, table, limit);
    }
}
