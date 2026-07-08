package io.forgetdm.validation;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/validation")
public class ValidationController {
    private final ValidationService svc;
    private final ValidationAdvisor advisor;
    public ValidationController(ValidationService svc, ValidationAdvisor advisor) { this.svc = svc; this.advisor = advisor; }

    @GetMapping("/reports") public List<ValidationReportEntity> list() { return svc.list(); }

    @PostMapping("/run")
    public ValidationReportEntity run(@RequestBody Map<String, Object> body) {
        Long source = body.get("sourceId") == null ? null : Long.valueOf(String.valueOf(body.get("sourceId")));
        Long target = Long.valueOf(String.valueOf(body.get("targetId")));
        Long policy = body.get("policyId") == null ? null : Long.valueOf(String.valueOf(body.get("policyId")));
        Long job = body.get("jobId") == null ? null : Long.valueOf(String.valueOf(body.get("jobId")));
        return svc.validate(source, target, policy, job);
    }

    /** Self-healing: AI explains each failed finding and proposes a fix. */
    @PostMapping("/reports/{id}/diagnose")
    public Map<String, Object> diagnose(@PathVariable Long id) { return advisor.diagnose(id); }

    /** Apply a proposed masking fix to the policy's rule for a column. */
    @PostMapping("/apply-fix")
    public Map<String, Object> applyFix(@RequestBody JsonNode b) {
        Long policyId = b.hasNonNull("policyId") ? b.get("policyId").asLong() : null;
        return advisor.applyFix(policyId, b.path("table").asText(null), b.path("column").asText(null),
                b.path("function").asText(null), b.path("param1").asText(null), b.path("param2").asText(null));
    }
}
