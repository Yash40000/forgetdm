package io.forgetdm.policy;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.core.mask.MaskContext;
import io.forgetdm.core.mask.MaskFunction;
import io.forgetdm.core.mask.MaskingEngine;
import io.forgetdm.provision.ValueListService;
import io.forgetdm.security.AccessPrincipal;
import io.forgetdm.security.GovernedReferenceGuard;
import io.forgetdm.security.OwnershipGuard;
import jakarta.transaction.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/policies")
public class PolicyController {
    private final MaskingPolicyRepository policies;
    private final MaskingRuleRepository rules;
    private final MaskingEngine engine;
    private final AuditService audit;
    private final ValueListService valueLists;
    private final OwnershipGuard ownership;
    private final GovernedReferenceGuard references;

    public PolicyController(MaskingPolicyRepository policies, MaskingRuleRepository rules,
                            MaskingEngine engine, AuditService audit, ValueListService valueLists,
                            OwnershipGuard ownership, GovernedReferenceGuard references) {
        this.policies = policies; this.rules = rules; this.engine = engine; this.audit = audit; this.valueLists = valueLists;
        this.ownership = ownership;
        this.references = references;
    }

    /** Tenant-scoped: callers only see policies they own, their group owns, or that are SHARED. */
    @GetMapping public List<MaskingPolicyEntity> list(@RequestParam(required = false) Long dataSourceId,
                                                      @RequestParam(required = false) String schema) {
        references.dataSource(dataSourceId);
        List<MaskingPolicyEntity> found = (dataSourceId != null && schema != null && !schema.isBlank())
                ? policies.findByDataSourceIdAndSchemaName(dataSourceId, schema)
                : policies.findAll();
        return found.stream()
                .filter(p -> ownership.canSee(p.getOwnerUserId(), p.getOwnerGroupId(), p.getVisibility()))
                .filter(p -> references.canSeeDataSource(p.getDataSourceId()))
                .toList();
    }

    @PostMapping public MaskingPolicyEntity create(@RequestBody MaskingPolicyEntity p) {
        p.setName(PolicyNameRules.normalize(p.getName()));
        references.dataSource(p.getDataSourceId());
        policies.findByNameIgnoreCase(p.getName()).ifPresent(existing -> {
            throw ApiException.conflict("A policy named '" + p.getName() + "' already exists");
        });
        p.setOwnerUserId(ownership.defaultOwnerUserId());
        p.setOwnerUsername(ownership.defaultOwnerUsername());
        p.setOwnerGroupId(ownership.defaultOwnerGroupId());
        if (p.getVisibility() == null || p.getVisibility().isBlank()) p.setVisibility(ownership.defaultVisibility());
        MaskingPolicyEntity saved = policies.save(p);
        audit.record(ownership.caller().map(AccessPrincipal::username).orElse("system"),
                "POLICY_CREATED", "MASKING", "policy", String.valueOf(saved.getId()), saved.getName(),
                "SUCCESS", saved.getName(), null);
        return saved;
    }

    /** Update policy metadata without replacing its rules or ownership. */
    @PutMapping("/{id}")
    @Transactional
    public MaskingPolicyEntity update(@PathVariable Long id, @RequestBody MaskingPolicyEntity body) {
        MaskingPolicyEntity policy = requireVisiblePolicy(id);
        if (body.getName() != null && !body.getName().isBlank()) {
            String name = PolicyNameRules.normalize(body.getName());
            policies.findByNameIgnoreCase(name).ifPresent(existing -> {
                if (!existing.getId().equals(id)) {
                    throw ApiException.conflict("A policy named '" + name + "' already exists");
                }
            });
            policy.setName(name);
        }
        if (body.getDescription() != null) policy.setDescription(body.getDescription());
        if (body.getDataSourceId() != null) {
            references.dataSource(body.getDataSourceId());
            policy.setDataSourceId(body.getDataSourceId());
        }
        if (body.getSchemaName() != null) policy.setSchemaName(emptyToNull(body.getSchemaName()));
        if (body.getVisibility() != null && !body.getVisibility().isBlank()) {
            String visibility = body.getVisibility().trim().toUpperCase(Locale.ROOT);
            if (!List.of(OwnershipGuard.PRIVATE, OwnershipGuard.GROUP, OwnershipGuard.SHARED).contains(visibility)) {
                throw ApiException.bad("Visibility must be PRIVATE, GROUP, or SHARED");
            }
            policy.setVisibility(visibility);
        }
        MaskingPolicyEntity saved = policies.save(policy);
        audit.record(currentActor(), "POLICY_UPDATED", "MASKING", "policy", String.valueOf(id),
                saved.getName(), "SUCCESS", "Updated masking policy", null);
        return saved;
    }

    @DeleteMapping("/{id}")
    @Transactional
    public void delete(@PathVariable Long id) {
        MaskingPolicyEntity policy = requireVisiblePolicy(id);
        rules.deleteByPolicyId(id);
        policies.deleteById(id);
        audit.record(currentActor(),
                "POLICY_DELETED", "MASKING", "policy", String.valueOf(id), policy.getName(),
                "SUCCESS", "id=" + id + " name=" + policy.getName(), null);
    }

    @GetMapping("/{id}/rules") public List<MaskingRuleEntity> rules(@PathVariable Long id) {
        requireVisiblePolicy(id);
        return rules.findByPolicyId(id);
    }

    /** Object-level tenancy check: the route permission alone must not grant another group's policy. */
    private MaskingPolicyEntity requireVisiblePolicy(Long id) {
        MaskingPolicyEntity policy = policies.findById(id)
                .orElseThrow(() -> ApiException.notFound("Policy " + id + " not found"));
        ownership.assertCanSee("policy", id, policy.getOwnerUserId(), policy.getOwnerGroupId(), policy.getVisibility());
        return policy;
    }

    @PostMapping("/{id}/rules") public MaskingRuleEntity addRule(@PathVariable Long id, @RequestBody MaskingRuleEntity r) {
        MaskingPolicyEntity policy = requireVisiblePolicy(id);
        MaskFunction fn = parseFunction(r.getFunction());
        r.setFunction(fn.name());
        r.setParam1(emptyToNull(r.getParam1()));
        r.setParam2(emptyToNull(r.getParam2()));
        validateRuleConfig(fn, r.getParam1(), r.getParam2());
        validateLookupReference(fn, r.getParam1());
        validateLookupConfiguration(fn, r.getParam1(), r.getParam2());
        r.setPolicyId(id);
        MaskingRuleEntity saved = rules.save(r);
        audit.record(currentActor(), "POLICY_RULE_CREATED", "MASKING", "policy-rule",
                String.valueOf(saved.getId()), r.getTableName() + "." + r.getColumnName(), "SUCCESS",
                "Added rule to policy " + policy.getName(), "{\"policyId\":" + id + "}");
        return saved;
    }

    /** Edit an existing rule in place — change function and/or params without delete+re-add. */
    @PatchMapping("/rules/{ruleId}")
    public MaskingRuleEntity updateRule(@PathVariable Long ruleId, @RequestBody Map<String, String> body) {
        MaskingRuleEntity r = rules.findById(ruleId)
                .orElseThrow(() -> ApiException.notFound("Rule " + ruleId + " not found"));
        MaskingPolicyEntity policy = requireVisiblePolicy(r.getPolicyId());   // a rule inherits its policy's tenancy
        if (body.containsKey("function") && body.get("function") != null && !body.get("function").isBlank()) {
            validateFunction(body.get("function"));
            r.setFunction(body.get("function").trim().toUpperCase());
        }
        if (body.containsKey("param1")) r.setParam1(emptyToNull(body.get("param1")));
        if (body.containsKey("param2")) r.setParam2(emptyToNull(body.get("param2")));
        MaskFunction effectiveFunction = parseFunction(r.getFunction());
        validateRuleConfig(effectiveFunction, r.getParam1(), r.getParam2());
        validateLookupReference(effectiveFunction, r.getParam1());
        validateLookupConfiguration(effectiveFunction, r.getParam1(), r.getParam2());
        MaskingRuleEntity saved = rules.save(r);
        audit.record(currentActor(), "POLICY_RULE_UPDATED", "MASKING", "policy-rule",
                String.valueOf(saved.getId()), r.getTableName() + "." + r.getColumnName(), "SUCCESS",
                "Updated rule in policy " + policy.getName(), "{\"policyId\":" + r.getPolicyId() + "}");
        return saved;
    }

    private static String emptyToNull(String s) { return s == null || s.isBlank() ? null : s; }

    @DeleteMapping("/rules/{ruleId}") public void deleteRule(@PathVariable Long ruleId) {
        MaskingRuleEntity rule = rules.findById(ruleId)
                .orElseThrow(() -> ApiException.notFound("Rule " + ruleId + " not found"));
        MaskingPolicyEntity policy = requireVisiblePolicy(rule.getPolicyId());
        rules.deleteById(ruleId);
        audit.record(currentActor(), "POLICY_RULE_DELETED", "MASKING", "policy-rule",
                String.valueOf(ruleId), rule.getTableName() + "." + rule.getColumnName(), "SUCCESS",
                "Deleted rule from policy " + policy.getName(), "{\"policyId\":" + rule.getPolicyId() + "}");
    }

    private String currentActor() {
        return ownership.caller().map(AccessPrincipal::username).orElse("system");
    }

    @GetMapping("/functions") public List<String> functions() {
        return Arrays.stream(MaskFunction.values()).map(Enum::name).toList();
    }

    @GetMapping("/lookup-references") public List<String> lookupReferences() {
        return valueLists.maskingLookupReferences();
    }

    /** Masking Studio: live preview of any function against a sample value.
     *  Optional body.seed re-keys determinism the same way job spec.maskingSeed does. */
    @PostMapping("/preview")
    public Map<String, String> preview(@RequestBody Map<String, String> body) {
        MaskFunction fn = parseFunction(body.get("function"));
        String value = body.get("value");
        String param1 = emptyToNull(body.get("param1"));
        String param2 = emptyToNull(body.get("param2"));
        validateRuleConfig(fn, param1, param2);
        validateLookupReference(fn, param1);
        validateLookupConfiguration(fn, param1, param2);
        try {
            String masked = engine.withSeed(body.get("seed")).mask(fn, body.getOrDefault("salt", previewSalt(fn)),
                    value, param1, param2, new MaskContext(1));
            return Map.of("original", value == null ? "" : value, "masked", masked == null ? "(null)" : masked);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw ApiException.bad("Invalid " + fn.name() + " configuration: " + e.getMessage());
        }
    }

    private static void validateFunction(String fn) { parseFunction(fn); }

    private static MaskFunction parseFunction(String fn) {
        try { return MaskFunction.valueOf(String.valueOf(fn).trim().toUpperCase(Locale.ROOT)); }
        catch (Exception e) { throw ApiException.bad("Unknown masking function: " + fn); }
    }

    static void validateRuleConfig(MaskFunction fn, String param1, String param2) {
        switch (fn) {
            case SECURE_LOOKUP -> require(param1, "SECURE_LOOKUP param1 needs pipe-delimited values, a seedlist file, or @value-list");
            case DIRECT_LOOKUP -> require(param1, "DIRECT_LOOKUP param1 needs source=>replacement pairs or @value-list");
            case HASH_LOOKUP -> require(param1, "HASH_LOOKUP param1 needs replacement rows or @value-list");
            case SCRIPT -> require(param1, "SCRIPT param1 needs a saved script name");
            case BY_INDICATOR, PHONE_SPLIT, SSN_SPLIT, DATE_SPLIT -> {
                require(param1, fn.name() + " param1 is required");
                require(param2, fn.name() + " param2 is required");
            }
            case TOKENIZE -> {
                if (param2 != null) {
                    int length = parseInteger(param2, "TOKENIZE param2 must be an integer from 12 to 64");
                    if (length < 12 || length > 64) throw ApiException.bad("TOKENIZE param2 must be from 12 to 64");
                }
            }
            case NUMERIC_NOISE -> validateNumericNoise(param1, param2);
            case MIN_MAX -> validateMinMax(param1, param2);
            default -> { }
        }
    }

    private static void validateNumericNoise(String noiseSpec, String clampSpec) {
        String spec = noiseSpec == null ? "PERCENT:10" : noiseSpec.trim().toUpperCase(Locale.ROOT);
        if (!spec.matches("(PERCENT|ABS):[+]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)"))
            throw ApiException.bad("NUMERIC_NOISE param1 must be PERCENT:n or ABS:n");
        if (clampSpec == null) return;
        String[] bounds = clampSpec.split(":", -1);
        if (bounds.length != 2) throw ApiException.bad("NUMERIC_NOISE param2 must be min:max");
        BigDecimal min = decimal(bounds[0], "NUMERIC_NOISE minimum must be numeric");
        BigDecimal max = decimal(bounds[1], "NUMERIC_NOISE maximum must be numeric");
        if (min.compareTo(max) > 0) throw ApiException.bad("NUMERIC_NOISE minimum must be <= maximum");
    }

    private static void validateMinMax(String minSpec, String maxSpec) {
        require(minSpec, "MIN_MAX param1 minimum is required");
        require(maxSpec, "MIN_MAX param2 maximum is required");
        BigDecimal min = decimal(minSpec, "MIN_MAX param1 must be numeric");
        BigDecimal max = decimal(maxSpec, "MIN_MAX param2 must be numeric");
        if (min.compareTo(max) > 0) throw ApiException.bad("MIN_MAX param1 must be <= param2");
    }

    private static BigDecimal decimal(String value, String message) {
        try { return new BigDecimal(String.valueOf(value).trim()); }
        catch (NumberFormatException e) { throw ApiException.bad(message); }
    }

    private static int parseInteger(String value, String message) {
        try { return Integer.parseInt(value.trim()); }
        catch (NumberFormatException e) { throw ApiException.bad(message); }
    }

    private static void require(String value, String message) {
        if (value == null || value.isBlank()) throw ApiException.bad(message);
    }

    private void validateLookupReference(MaskFunction fn, String param1) {
        if (fn != MaskFunction.SECURE_LOOKUP && fn != MaskFunction.DIRECT_LOOKUP && fn != MaskFunction.HASH_LOOKUP) return;
        if (param1 == null || !param1.trim().startsWith("@")) return;
        String reference = param1.trim().toLowerCase(Locale.ROOT);
        if (fn == MaskFunction.DIRECT_LOOKUP && reference.startsWith("@lookup:hash:"))
            throw ApiException.bad("DIRECT_LOOKUP needs an @lookup:direct:name reference");
        if (fn == MaskFunction.HASH_LOOKUP && reference.startsWith("@lookup:direct:"))
            throw ApiException.bad("HASH_LOOKUP needs an @lookup:hash:name reference");
        if (fn == MaskFunction.SECURE_LOOKUP && reference.startsWith("@lookup:"))
            throw ApiException.bad("SECURE_LOOKUP uses a regular @value-list; use DIRECT_LOOKUP or HASH_LOOKUP for relational lookups");
        valueLists.validateMaskingReference(param1);
    }

    private void validateLookupConfiguration(MaskFunction fn, String param1, String param2) {
        try { engine.validateLookupConfiguration(fn, param1, param2); }
        catch (ApiException e) { throw e; }
        catch (IllegalArgumentException | IllegalStateException e) {
            throw ApiException.bad("Invalid " + fn.name() + " configuration: " + e.getMessage());
        }
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
            case BANK_ACCOUNT -> "bank.account";
            case IBAN -> "iban";
            case SWIFT_BIC -> "swift.bic";
            case ABA_ROUTING -> "routing.aba";
            case NATIONAL_ID -> "national.id";
            case IP_ADDRESS -> "network.ip";
            case MAC_ADDRESS -> "network.mac";
            case HASH_LOOKUP -> "lookup.hash";
            case DIRECT_LOOKUP -> "lookup.direct";
            default -> "preview";
        };
    }
}
