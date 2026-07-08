package io.forgetdm.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.ai.AiAssistantService;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.core.mask.MaskFunction;
import io.forgetdm.policy.MaskingRuleEntity;
import io.forgetdm.policy.MaskingRuleRepository;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Self-healing validation: when checks fail, the AI explains the likely root cause of each finding and
 * proposes a concrete fix — and where the fix is a masking change it suggests a valid masking function,
 * which the user can apply in one click. Closes the loop: validate → diagnose → fix → re-validate.
 */
@Service
public class ValidationAdvisor {

    private final ValidationReportRepository reports;
    private final MaskingRuleRepository rules;
    private final AiAssistantService ai;
    private final AuditService audit;
    private final ObjectMapper json = new ObjectMapper();

    public ValidationAdvisor(ValidationReportRepository reports, MaskingRuleRepository rules,
                             AiAssistantService ai, AuditService audit) {
        this.reports = reports; this.rules = rules; this.ai = ai; this.audit = audit;
    }

    /** Explain each failed finding and propose a fix. Returns {reportId, policyId, result, remedies:[...]}. */
    public Map<String, Object> diagnose(Long reportId) {
        ValidationReportEntity rep = reports.findById(reportId)
                .orElseThrow(() -> ApiException.notFound("Validation report " + reportId + " not found"));
        if (!ai.ready())
            throw ApiException.bad("Self-healing validation needs an AI provider — set one up (see AI_COPILOT_SETUP.txt).");

        String functions = Arrays.stream(MaskFunction.values()).map(Enum::name).collect(Collectors.joining(", "));
        String sys = "You are a Test Data Management data-quality remediation expert. For EACH non-INFO validation "
                + "finding (each has check, table, column, detail), give a one-sentence root cause and a concrete, "
                + "actionable fix. When the fix is a masking change, set suggestedFunction to one EXACT name from this "
                + "list and add params only if relevant: [" + functions + "]. Common patterns: LEAK = value identical to "
                + "source (use a stronger/deterministic mask); FORMAT = output breaks the column's format (use a "
                + "format-preserving function); RI = a key/reference was altered (don't mask the key, or mask it "
                + "consistently across tables); DOMAIN = values land on real/deliverable domains (use a safe test domain). "
                + "Return STRICT JSON only, no prose, no code fences: {\"remedies\":[{\"check\":\"\",\"table\":\"\","
                + "\"column\":\"\",\"severity\":\"\",\"cause\":\"\",\"fix\":\"\",\"suggestedFunction\":\"\","
                + "\"suggestedParam1\":\"\",\"suggestedParam2\":\"\"}]}. Omit suggestedFunction when the fix isn't a masking change.";
        String user = "Result: " + rep.getResult() + "\nFindings JSON:\n" + rep.getFindingsJson();

        JsonNode node = parseLoose(ai.complete(sys, user, null, null, true));
        audit.log("system", "VALIDATION_DIAGNOSED", "report=" + reportId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reportId", reportId);
        out.put("policyId", rep.getPolicyId());
        out.put("result", rep.getResult());
        out.put("remedies", node.path("remedies"));
        return out;
    }

    /** Apply a proposed masking fix to the policy's rule for a column (then the user re-runs validation). */
    public Map<String, Object> applyFix(Long policyId, String table, String column,
                                        String function, String param1, String param2) {
        if (policyId == null) throw ApiException.bad("policyId is required to apply a fix");
        if (table == null || column == null) throw ApiException.bad("table and column are required");
        if (function == null || function.isBlank()) throw ApiException.bad("A suggested masking function is required");
        String fn = function.trim().toUpperCase();
        try { MaskFunction.valueOf(fn); }
        catch (Exception e) { throw ApiException.bad("Unknown masking function: " + function); }

        MaskingRuleEntity rule = rules.findByPolicyId(policyId).stream()
                .filter(r -> r.getTableName().equalsIgnoreCase(table) && r.getColumnName().equalsIgnoreCase(column))
                .findFirst()
                .orElseThrow(() -> ApiException.bad("No masking rule for " + table + "." + column + " in policy " + policyId
                        + " — open the policy to add or adjust it manually."));
        String before = rule.getFunction();
        rule.setFunction(fn);
        if (param1 != null) rule.setParam1(param1.isBlank() ? null : param1);
        if (param2 != null) rule.setParam2(param2.isBlank() ? null : param2);
        rules.save(rule);
        audit.log("system", "VALIDATION_FIX_APPLIED", policyId + " " + table + "." + column + " " + before + " -> " + fn);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("ruleId", rule.getId());
        out.put("function", fn);
        return out;
    }

    private JsonNode parseLoose(String s) {
        if (s == null) s = "";
        String t = s.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) t = t.substring(nl + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        int a = t.indexOf('{'), b = t.lastIndexOf('}');
        if (a >= 0 && b > a) t = t.substring(a, b + 1);
        try { return json.readTree(t); }
        catch (Exception e) { throw ApiException.bad("The AI did not return valid JSON; try again."); }
    }
}
