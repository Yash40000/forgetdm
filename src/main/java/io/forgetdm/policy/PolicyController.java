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
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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
            case FIRST_NAME, LAST_NAME, COMPANY, ADDRESS_STREET, FORMAT_PRESERVE -> {
                optionalCase(param1, fn.name() + " param1");
                optionalCase(param2, fn.name() + " param2");
            }
            case FULL_NAME -> {
                optionalChoice(param1, "FULL_NAME param1", Set.of(
                        "FIRST LAST", "FIRST MIDDLE LAST", "FIRST MID LAST", "LAST FIRST",
                        "LAST MIDDLE FIRST", "LAST MID FIRST", "LAST, FIRST",
                        "LAST, FIRST MIDDLE", "FIRST, LAST"));
                optionalCase(param2, "FULL_NAME param2");
            }
            case EMAIL -> {
                if (param1 != null && !param1.toLowerCase(Locale.ROOT).endsWith(".txt")) {
                    optionalChoice(param1, "EMAIL param1", Set.of(
                            "NAME_SAFE", "USER_SAFE", "HASH_LOCAL", "REDACT_LOCAL", "PRESERVE_DOMAIN"));
                }
                optionalChoice(param2, "EMAIL param2", Set.of("SAFE_DOMAIN", "PRESERVE_DOMAIN"));
            }
            case PHONE -> {
                optionalChoice(param1, "PHONE param1", Set.of(
                        "FORMAT_PRESERVE", "PRESERVE_AREA", "KEEP_LAST4", "REDACT", "DIGITS_ONLY"));
                optionalChoice(param2, "PHONE param2", Set.of("PRESERVE_COUNTRY", "OBFUSCATE_ALL"));
            }
            case SSN -> {
                optionalChoice(param1, "SSN param1", Set.of(
                        "VALID_PRESERVE_AREA", "VALID_RANDOM_AREA", "KEEP_LAST4", "REDACT", "FORMAT_PRESERVE"));
                optionalChoice(param2, "SSN param2", Set.of("PRESERVE_FORMAT", "DASHED", "DIGITS_ONLY"));
            }
            case CREDIT_CARD -> {
                optionalChoice(param1, "CREDIT_CARD param1", Set.of(
                        "VALID_PRESERVE_BIN", "VALID_RANDOM_BIN", "VALID_KEEP_LAST4", "KEEP_LAST4",
                        "FORMAT_PRESERVE", "REDACT"));
                optionalChoice(param2, "CREDIT_CARD param2", Set.of(
                        "PRESERVE_FORMAT", "SPACES", "DASHES", "DIGITS_ONLY"));
            }
            case DATE_SHIFT -> {
                validateDateShift(param1);
                validateDateFormat(param2, "DATE_SHIFT param2");
            }
            case DOB_AGE_BAND -> {
                if (param1 != null) {
                    int band = parseInteger(param1, "DOB_AGE_BAND param1 must be a positive integer");
                    if (band < 1) throw ApiException.bad("DOB_AGE_BAND param1 must be a positive integer");
                }
                validateDateFormat(param2, "DOB_AGE_BAND param2");
            }
            case ADDRESS_US -> {
                optionalChoice(param1, "ADDRESS_US param1", Set.of(
                        "FULL", "LINE1", "LINE2", "CITY", "STATE", "ZIP", "COUNTRY"));
                optionalChoice(param2, "ADDRESS_US param2", Set.of("PRESERVE_STATE"));
            }
            case CITY_STATE_ZIP -> {
                optionalChoice(param1, "CITY_STATE_ZIP param1", Set.of("FULL", "CITY", "STATE", "ZIP"));
                optionalChoice(param2, "CITY_STATE_ZIP param2", Set.of("PRESERVE_STATE"));
            }
            case CHARACTER_MAP -> {
                validatePreserveRanges(param1);
                optionalCase(param2, "CHARACTER_MAP param2");
            }
            case SECURE_LOOKUP -> require(param1, "SECURE_LOOKUP param1 needs pipe-delimited values, a seedlist file, or @value-list");
            case DIRECT_LOOKUP -> require(param1, "DIRECT_LOOKUP param1 needs source=>replacement pairs or @value-list");
            case HASH_LOOKUP -> require(param1, "HASH_LOOKUP param1 needs replacement rows or @value-list");
            case SCRIPT -> require(param1, "SCRIPT param1 needs a saved script name");
            case BY_INDICATOR -> validateIndicatorMap(param1, param2);
            case PARTIAL_MASK -> validatePartialMask(param1, param2);
            case PHONE_SPLIT -> validateSplitColumns(fn, param1, param2, 2);
            case SSN_SPLIT -> validateSplitColumns(fn, param1, param2, 3);
            case DATE_SPLIT -> validateDateSplit(param1, param2);
            case AGE -> {
                require(param1, "AGE param1 shift is required");
                if (!param1.trim().matches("(?i)(?:[+-]?\\d+\\s*[ymwd]\\s*)+"))
                    throw ApiException.bad("AGE param1 must contain only y/m/w/d shift tokens");
                validateDateFormat(param2, "AGE param2");
            }
            case TOKENIZE -> {
                if (param2 != null) {
                    int length = parseInteger(param2, "TOKENIZE param2 must be an integer from 12 to 64");
                    if (length < 12 || length > 64) throw ApiException.bad("TOKENIZE param2 must be from 12 to 64");
                }
            }
            case NUMERIC_NOISE -> validateNumericNoise(param1, param2);
            case MIN_MAX -> validateMinMax(param1, param2);
            case REDACT -> validateRedact(param1, param2);
            case BANK_ACCOUNT -> optionalChoice(param1, "BANK_ACCOUNT param1",
                    Set.of("KEEP_LAST4", "FORMAT_PRESERVE", "REDACT"));
            case IBAN -> {
                optionalChoice(param1, "IBAN param1", Set.of("PRESERVE_COUNTRY", "RANDOM_COUNTRY"));
                optionalChoice(param2, "IBAN param2", Set.of("PRESERVE_FORMAT", "COMPACT"));
            }
            case SWIFT_BIC -> optionalChoice(param1, "SWIFT_BIC param1",
                    Set.of("PRESERVE_COUNTRY", "RANDOM_COUNTRY"));
            case ABA_ROUTING -> optionalChoice(param1, "ABA_ROUTING param1",
                    Set.of("PRESERVE_FED_DISTRICT", "RANDOM_DISTRICT"));
            case NATIONAL_ID -> {
                optionalChoice(param1, "NATIONAL_ID param1", Set.of("GENERIC", "US", "CA", "UK"));
                optionalChoice(param2, "NATIONAL_ID param2", Set.of("PRESERVE_FORMAT", "DASHED", "DIGITS_ONLY"));
            }
            case IP_ADDRESS -> optionalChoice(param1, "IP_ADDRESS param1",
                    Set.of("SAFE_TEST_RANGE", "PRESERVE_PRIVATE"));
            case MAC_ADDRESS -> optionalChoice(param1, "MAC_ADDRESS param1",
                    Set.of("LOCAL_ADMIN", "PRESERVE_OUI"));
            case FIXED -> {
                require(param1, "FIXED param1 value is required");
                optionalCase(param2, "FIXED param2");
            }
            default -> { }
        }
    }

    private static void optionalCase(String value, String field) {
        optionalChoice(value, field, Set.of(
                "LOWER", "LOWERCASE", "UPPER", "UPPERCASE", "PROPER", "TITLE",
                "TITLE_CASE", "AS_IS", "PRESERVE", "ORIGINAL"));
    }

    private static void optionalChoice(String value, String field, Set<String> allowed) {
        if (value == null || value.isBlank()) return;
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (!allowed.contains(normalized))
            throw ApiException.bad(field + " must be one of: " + String.join(", ", allowed));
    }

    private static void validateDateShift(String value) {
        if (value == null || value.isBlank()) return;
        String spec = value.trim();
        if (spec.matches("[+-]?\\d+")) return;
        if (!spec.matches("[+-]?\\d+:[+-]?\\d+"))
            throw ApiException.bad("DATE_SHIFT param1 must be maxDays or minDays:maxDays");
        String[] parts = spec.split(":", -1);
        long min;
        long max;
        try {
            min = Long.parseLong(parts[0]);
            max = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            throw ApiException.bad("DATE_SHIFT param1 bounds must be integers");
        }
        if (min > max) throw ApiException.bad("DATE_SHIFT param1 minimum must be <= maximum");
    }

    private static void validateDateFormat(String value, String field) {
        if (value == null || value.isBlank()) return;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (Set.of("YYYYDDD", "JULIAN", "YYDDD", "CYYDDD", "JDE").contains(normalized)) return;
        try { DateTimeFormatter.ofPattern(value.trim()); }
        catch (IllegalArgumentException e) { throw ApiException.bad(field + " is not a valid date format"); }
    }

    private static void validatePreserveRanges(String value) {
        if (value == null || value.isBlank()) return;
        if (!value.trim().matches("(?i)(?:FIRST|LAST)\\s*[:=]\\s*\\d+(?:\\s*[,;]\\s*(?:FIRST|LAST)\\s*[:=]\\s*\\d+)*"))
            throw ApiException.bad("CHARACTER_MAP param1 must use FIRST:n and/or LAST:n");
    }

    private static void validateIndicatorMap(String indicator, String mapping) {
        require(indicator, "BY_INDICATOR param1 is required");
        require(mapping, "BY_INDICATOR param2 is required");
        for (String entry : mapping.split("\\|", -1)) {
            int equals = entry.indexOf('=');
            if (equals <= 0 || equals == entry.length() - 1)
                throw ApiException.bad("BY_INDICATOR param2 entries must use value=FUNCTION");
            String function = entry.substring(equals + 1).trim().toUpperCase(Locale.ROOT);
            MaskFunction nested;
            try { nested = MaskFunction.valueOf(function); }
            catch (IllegalArgumentException e) { throw ApiException.bad("BY_INDICATOR contains unknown function: " + function); }
            if (nested == MaskFunction.BY_INDICATOR)
                throw ApiException.bad("BY_INDICATOR cannot dispatch recursively");
        }
    }

    private static void validatePartialMask(String pattern, String function) {
        if (pattern != null && !pattern.isBlank()) {
            try { Pattern.compile(pattern); }
            catch (RuntimeException e) { throw ApiException.bad("PARTIAL_MASK param1 is not a valid regex"); }
        }
        if (function == null || function.isBlank()) return;
        MaskFunction nested;
        try { nested = MaskFunction.valueOf(function.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw ApiException.bad("PARTIAL_MASK param2 contains an unknown function"); }
        if (nested == MaskFunction.PARTIAL_MASK || nested == MaskFunction.BY_INDICATOR)
            throw ApiException.bad("PARTIAL_MASK cannot use a recursive function");
    }

    private static void validateSplitColumns(MaskFunction function, String self, String columns, int minimum) {
        require(self, function.name() + " param1 is required");
        require(columns, function.name() + " param2 is required");
        List<String> names = Arrays.stream(columns.split(",", -1)).map(String::trim).filter(s -> !s.isBlank()).toList();
        if (names.size() < minimum || names.stream().noneMatch(name -> name.equalsIgnoreCase(self.trim())))
            throw ApiException.bad(function.name() + " param2 must contain param1 and at least " + minimum + " columns");
    }

    private static void validateDateSplit(String self, String roles) {
        require(self, "DATE_SPLIT param1 is required");
        require(roles, "DATE_SPLIT param2 is required");
        Map<String, String> parsed = Arrays.stream(roles.split(",", -1))
                .map(String::trim)
                .filter(part -> part.contains("="))
                .map(part -> part.split("=", 2))
                .collect(java.util.stream.Collectors.toMap(
                        part -> part[0].trim().toLowerCase(Locale.ROOT), part -> part[1].trim(), (a, b) -> b));
        String year = parsed.containsKey("yyyy") ? parsed.get("yyyy") : parsed.get("yy");
        if (parsed.get("dd") == null || parsed.get("mm") == null || year == null
                || parsed.values().stream().noneMatch(name -> name.equalsIgnoreCase(self.trim())))
            throw ApiException.bad("DATE_SPLIT param2 needs dd, mm, yyyy/yy roles and must include param1");
    }

    private static void validateRedact(String mask, String mode) {
        if (mask != null && mask.codePointCount(0, mask.length()) != 1)
            throw ApiException.bad("REDACT param1 must be exactly one mask character");
        if (mode == null || mode.isBlank()) return;
        String normalized = mode.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("FULL|KEEP_LAST4|KEEP_FIRST2|KEEP_FIRST2_LAST4|KEEP_FIRST:\\d+|KEEP_LAST:\\d+|STANDARD:\\d+"))
            throw ApiException.bad("REDACT param2 is not a supported redaction mode");
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
