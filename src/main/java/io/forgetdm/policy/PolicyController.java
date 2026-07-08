package io.forgetdm.policy;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.core.mask.MaskContext;
import io.forgetdm.core.mask.MaskFunction;
import io.forgetdm.core.mask.MaskingEngine;
import jakarta.transaction.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/policies")
public class PolicyController {
    private final MaskingPolicyRepository policies;
    private final MaskingRuleRepository rules;
    private final MaskingEngine engine;
    private final AuditService audit;

    public PolicyController(MaskingPolicyRepository policies, MaskingRuleRepository rules,
                            MaskingEngine engine, AuditService audit) {
        this.policies = policies; this.rules = rules; this.engine = engine; this.audit = audit;
    }

    @GetMapping public List<MaskingPolicyEntity> list(@RequestParam(required = false) Long dataSourceId,
                                                      @RequestParam(required = false) String schema) {
        if (dataSourceId != null && schema != null && !schema.isBlank())
            return policies.findByDataSourceIdAndSchemaName(dataSourceId, schema);
        return policies.findAll();
    }

    @PostMapping public MaskingPolicyEntity create(@RequestBody MaskingPolicyEntity p) {
        MaskingPolicyEntity saved = policies.save(p);
        audit.log("system", "POLICY_CREATED", saved.getName());
        return saved;
    }

    @DeleteMapping("/{id}")
    @Transactional
    public void delete(@PathVariable Long id) {
        rules.deleteByPolicyId(id);
        policies.deleteById(id);
        audit.log("system", "POLICY_DELETED", "id=" + id);
    }

    @GetMapping("/{id}/rules") public List<MaskingRuleEntity> rules(@PathVariable Long id) {
        return rules.findByPolicyId(id);
    }

    @PostMapping("/{id}/rules") public MaskingRuleEntity addRule(@PathVariable Long id, @RequestBody MaskingRuleEntity r) {
        policies.findById(id).orElseThrow(() -> ApiException.notFound("Policy " + id + " not found"));
        validateFunction(r.getFunction());
        r.setPolicyId(id);
        return rules.save(r);
    }

    /** Edit an existing rule in place — change function and/or params without delete+re-add. */
    @PatchMapping("/rules/{ruleId}")
    public MaskingRuleEntity updateRule(@PathVariable Long ruleId, @RequestBody Map<String, String> body) {
        MaskingRuleEntity r = rules.findById(ruleId)
                .orElseThrow(() -> ApiException.notFound("Rule " + ruleId + " not found"));
        if (body.containsKey("function") && body.get("function") != null && !body.get("function").isBlank()) {
            validateFunction(body.get("function"));
            r.setFunction(body.get("function").trim().toUpperCase());
        }
        if (body.containsKey("param1")) r.setParam1(emptyToNull(body.get("param1")));
        if (body.containsKey("param2")) r.setParam2(emptyToNull(body.get("param2")));
        MaskingRuleEntity saved = rules.save(r);
        audit.log("system", "RULE_UPDATED", r.getTableName() + "." + r.getColumnName() + " -> " + r.getFunction());
        return saved;
    }

    private static String emptyToNull(String s) { return s == null || s.isBlank() ? null : s; }

    @DeleteMapping("/rules/{ruleId}") public void deleteRule(@PathVariable Long ruleId) { rules.deleteById(ruleId); }

    @GetMapping("/functions") public List<String> functions() {
        return Arrays.stream(MaskFunction.values()).map(Enum::name).toList();
    }

    /** Masking Studio: live preview of any function against a sample value.
     *  Optional body.seed re-keys determinism the same way job spec.maskingSeed does. */
    @PostMapping("/preview")
    public Map<String, String> preview(@RequestBody Map<String, String> body) {
        MaskFunction fn = parseFunction(body.get("function"));
        String value = body.get("value");
        String masked = engine.withSeed(body.get("seed")).mask(fn, body.getOrDefault("salt", previewSalt(fn)),
                value, body.get("param1"), body.get("param2"), new MaskContext(1));
        return Map.of("original", value == null ? "" : value, "masked", masked == null ? "(null)" : masked);
    }

    private static void validateFunction(String fn) { parseFunction(fn); }

    private static MaskFunction parseFunction(String fn) {
        try { return MaskFunction.valueOf(String.valueOf(fn).toUpperCase()); }
        catch (Exception e) { throw ApiException.bad("Unknown masking function: " + fn); }
    }

    private static String previewSalt(MaskFunction fn) {
        return switch (fn) {
            case FIRST_NAME -> "name.first";
            case LAST_NAME -> "name.last";
            case FULL_NAME -> "name.full";
            case EMAIL -> "email";
            case SSN -> "ssn";
            case CREDIT_CARD -> "ccn";
            case PHONE -> "phone";
            case CITY_STATE_ZIP -> "geo";
            case ADDRESS_STREET -> "addr";
            case ADDRESS_US -> "addr.us";
            case COMPANY -> "company";
            case DOB_AGE_BAND -> "dob";
            default -> "preview";
        };
    }
}
