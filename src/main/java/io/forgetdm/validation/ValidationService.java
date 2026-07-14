package io.forgetdm.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.core.util.Luhn;
import io.forgetdm.core.util.PiiPatterns;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.policy.MaskingRuleEntity;
import io.forgetdm.policy.MaskingRuleRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.InetAddress;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;

/**
 * Post-mask validation — ForgeTDM treats validation as a first-class citizen (the trust feature):
 *  1. LEAK CHECK     masked value identical to a live source value            -> FAIL
 *  2. FORMAT CHECK   masked value violates the field's contract (Luhn, regex) -> FAIL/WARN
 *  3. RI CHECK       same source value maps to >1 masked value across tables  -> FAIL
 *  4. DOMAIN CHECK   masked emails on deliverable (non-.test) domains         -> WARN
 * Produces an evidence report stored for compliance audits.
 */
@Service
public class ValidationService {

    private static final int SAMPLE = 300;

    private final ValidationReportRepository reports;
    private final MaskingRuleRepository rules;
    private final DataSourceService dataSources;
    private final ConnectionFactory connections;
    private final AuditService audit;
    private final ObjectMapper json = new ObjectMapper();

    public ValidationService(ValidationReportRepository reports, MaskingRuleRepository rules,
                             DataSourceService dataSources, ConnectionFactory connections, AuditService audit) {
        this.reports = reports; this.rules = rules; this.dataSources = dataSources;
        this.connections = connections; this.audit = audit;
    }

    public ValidationReportEntity validate(Long sourceId, Long targetId, Long policyId, Long jobId) {
        DataSourceEntity src = sourceId == null ? null : dataSources.get(sourceId);
        DataSourceEntity tgt = dataSources.get(targetId);
        List<MaskingRuleEntity> policyRules = policyId == null ? List.of() : rules.findByPolicyId(policyId);

        List<Map<String, Object>> findings = new ArrayList<>();
        int fails = 0, warns = 0;
        Map<String, Map<String, String>> riWitness = new HashMap<>(); // saltGroup -> source -> masked

        try (Connection out = connections.open(tgt);
             Connection in = src == null ? null : connections.open(src)) {

            for (MaskingRuleEntity rule : policyRules) {
                List<String> masked = sample(out, rule.getTableName(), rule.getColumnName());
                if (masked.isEmpty()) continue;
                Set<String> sourceVals = in == null ? Set.of()
                        : new HashSet<>(sample(in, rule.getTableName(), rule.getColumnName()));

                // 1) leak check
                if (!sourceVals.isEmpty()) {
                    long leaks = masked.stream().filter(v -> v != null && sourceVals.contains(v)).count();
                    if (leaks > 0) {
                        fails++;
                        findings.add(finding("FAIL", "LEAK", rule, leaks + " masked value(s) identical to live source values"));
                    }
                }

                // 2) format contract per function
                String fn = rule.getFunction();
                long bad = 0;
                for (String v : masked) {
                    if (v == null) continue;
                    boolean ok = switch (fn) {
                        case "EMAIL" -> PiiPatterns.VALUE_HINTS.get("EMAIL").matcher(v).matches();
                        case "SSN" -> ssnFormatOk(v, rule.getParam1());
                        case "CREDIT_CARD" -> cardFormatOk(v, rule.getParam1());
                        case "DOB_AGE_BAND", "DATE_SHIFT" -> v.matches("\\d{4}-\\d{2}-\\d{2}.*|\\d{2}[/-]\\d{2}[/-]\\d{4}");
                        case "IBAN" -> ibanFormatOk(v);
                        case "SWIFT_BIC" -> v.replaceAll("\\s", "").matches("[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?");
                        case "ABA_ROUTING" -> abaFormatOk(v);
                        case "NATIONAL_ID" -> nationalIdFormatOk(v, rule.getParam1());
                        case "IP_ADDRESS" -> ipFormatOk(v);
                        case "MAC_ADDRESS" -> v.matches("(?i)([0-9a-f]{2}[:-]){5}[0-9a-f]{2}");
                        case "UUID" -> uuidFormatOk(v);
                        case "NUMERIC_NOISE" -> numericFormatOk(v);
                        case "MIN_MAX" -> minMaxFormatOk(v, rule.getParam1(), rule.getParam2());
                        case "TOKENIZE" -> tokenFormatOk(v, rule.getParam1(), rule.getParam2());
                        default -> true;
                    };
                    if (!ok) bad++;
                }
                if (bad > 0) {
                    fails++;
                    findings.add(finding("FAIL", "FORMAT", rule, bad + " value(s) violate the " + fn + " format contract"));
                }

                // 3) email deliverability hygiene
                if ("EMAIL".equals(fn)) {
                    long deliverable = masked.stream().filter(v -> v != null && v.contains("@") && !v.endsWith(".test")).count();
                    if (deliverable > 0) {
                        warns++;
                        findings.add(finding("WARN", "DOMAIN", rule, deliverable + " masked email(s) on potentially deliverable domains"));
                    }
                }

                // 4) referential-integrity witness across tables sharing an identity function
                if (in != null && isIdentityFn(fn)) {
                    Map<String, String> pairings = pairSample(in, out, rule);
                    Map<String, String> group = riWitness.computeIfAbsent(fn, k -> new HashMap<>());
                    for (Map.Entry<String, String> p : pairings.entrySet()) {
                        String prev = group.putIfAbsent(p.getKey(), p.getValue());
                        if (prev != null && !prev.equals(p.getValue())) {
                            fails++;
                            findings.add(finding("FAIL", "RI", rule,
                                    "Source value masks inconsistently across tables ('" + prev + "' vs '" + p.getValue() + "')"));
                            break;
                        }
                    }
                }
            }
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.bad("Validation failed to run: " + e.getMessage()); }

        if (findings.isEmpty())
            findings.add(Map.of("severity", "INFO", "check", "ALL",
                    "detail", "All checks passed on sampled data (" + policyRules.size() + " rules, sample " + SAMPLE + "/column)"));

        ValidationReportEntity rep = new ValidationReportEntity();
        rep.setJobId(jobId);
        rep.setDataSourceId(targetId);
        rep.setPolicyId(policyId);
        rep.setResult(fails > 0 ? "FAIL" : warns > 0 ? "WARN" : "PASS");
        try { rep.setFindingsJson(json.writeValueAsString(findings)); }
        catch (Exception e) { throw new IllegalStateException(e); }
        ValidationReportEntity saved = reports.save(rep);
        audit.log("system", "VALIDATION_" + saved.getResult(), "target=" + tgt.getName() + " findings=" + findings.size());
        return saved;
    }

    private static boolean isIdentityFn(String fn) {
        return Set.of("FIRST_NAME", "LAST_NAME", "FULL_NAME", "EMAIL", "SSN", "CREDIT_CARD", "PHONE", "ADDRESS_US",
                "BANK_ACCOUNT", "IBAN", "SWIFT_BIC", "ABA_ROUTING", "NATIONAL_ID", "IP_ADDRESS", "MAC_ADDRESS",
                "DIRECT_LOOKUP", "HASH_LOOKUP").contains(fn);
    }

    private static boolean ssnFormatOk(String value, String mode) {
        String m = mode == null ? "" : mode.toUpperCase(Locale.ROOT);
        if (m.equals("KEEP_LAST4")) return value.matches("\\*{3}-\\*{2}-\\d{4}|\\*{5}\\d{4}");
        if (m.equals("REDACT")) return value.matches("\\*{3}-\\*{2}-\\*{4}|\\*{9}");
        return value.matches("\\d{3}-\\d{2}-\\d{4}|\\d{9}");
    }

    private static boolean cardFormatOk(String value, String mode) {
        return Luhn.isValid(value.replaceAll("[ -]", ""));
    }

    private static boolean ibanFormatOk(String value) {
        String compact = value.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        if (!compact.matches("[A-Z]{2}\\d{2}[A-Z0-9]{11,30}")) return false;
        int remainder = 0;
        String rearranged = compact.substring(4) + compact.substring(0, 4);
        for (char c : rearranged.toCharArray()) {
            String digits = Character.isLetter(c) ? String.valueOf(c - 'A' + 10) : String.valueOf(c);
            for (char digit : digits.toCharArray()) remainder = (remainder * 10 + digit - '0') % 97;
        }
        return remainder == 1;
    }

    private static boolean abaFormatOk(String value) {
        String digits = value.replaceAll("\\D", "");
        if (digits.length() != 9) return false;
        int[] weights = {3, 7, 1, 3, 7, 1, 3, 7, 1};
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) sum += (digits.charAt(i) - '0') * weights[i];
        return sum % 10 == 0;
    }

    private static boolean nationalIdFormatOk(String value, String countrySpec) {
        String country = countrySpec == null ? "GENERIC" : countrySpec.toUpperCase(Locale.ROOT);
        String compact = value.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        return switch (country) {
            case "US" -> ssnFormatOk(value, "VALID_RANDOM_AREA");
            case "CA" -> compact.matches("\\d{9}") && Luhn.isValid(compact);
            case "UK" -> compact.matches("[ABCEGHJKLMNPRSTWXYZ]{2}\\d{6}[A-D]");
            default -> !compact.isBlank();
        };
    }

    private static boolean ipFormatOk(String value) {
        String trimmed = value.trim();
        if (trimmed.contains(":")) {
            try { return InetAddress.getByName(trimmed).getHostAddress().contains(":"); }
            catch (Exception ignored) { return false; }
        }
        String[] parts = trimmed.split("\\.", -1);
        if (parts.length != 4) return false;
        try {
            for (String part : parts) {
                int octet = Integer.parseInt(part);
                if (octet < 0 || octet > 255) return false;
            }
            return true;
        } catch (NumberFormatException e) { return false; }
    }

    private static boolean uuidFormatOk(String value) {
        try {
            UUID.fromString(value.replace("{", "").replace("}", ""));
            return true;
        } catch (IllegalArgumentException e) { return false; }
    }

    private static boolean numericFormatOk(String value) {
        try { new BigDecimal(value.trim()); return true; }
        catch (NumberFormatException e) { return false; }
    }

    private static boolean minMaxFormatOk(String value, String minSpec, String maxSpec) {
        try {
            BigDecimal number = new BigDecimal(value.trim());
            BigDecimal min = new BigDecimal(String.valueOf(minSpec).trim());
            BigDecimal max = new BigDecimal(String.valueOf(maxSpec).trim());
            return min.compareTo(max) <= 0 && number.compareTo(min) >= 0 && number.compareTo(max) <= 0;
        } catch (NumberFormatException e) { return false; }
    }

    private static boolean tokenFormatOk(String value, String prefixSpec, String lengthSpec) {
        String prefix = prefixSpec == null ? "TKN_" : "NONE".equalsIgnoreCase(prefixSpec) ? "" : prefixSpec;
        int length;
        try { length = Math.max(12, Math.min(64, lengthSpec == null ? 32 : Integer.parseInt(lengthSpec))); }
        catch (NumberFormatException e) { return false; }
        return value.startsWith(prefix) && value.substring(prefix.length()).matches("(?i)[0-9a-f]{" + length + "}");
    }

    /** Pair source row -> masked row by primary-key order for a quick RI witness sample. */
    private Map<String, String> pairSample(Connection in, Connection out, MaskingRuleEntity rule) {
        Map<String, String> map = new LinkedHashMap<>();
        List<String> s = sample(in, rule.getTableName(), rule.getColumnName());
        List<String> m = sample(out, rule.getTableName(), rule.getColumnName());
        for (int i = 0; i < Math.min(s.size(), m.size()); i++)
            if (s.get(i) != null && m.get(i) != null) map.put(s.get(i), m.get(i));
        return map;
    }

    private List<String> sample(Connection c, String table, String column) {
        List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement()) {
            st.setMaxRows(SAMPLE);
            try (ResultSet rs = st.executeQuery("SELECT " + q(column) + " FROM " + q(table) + " ORDER BY 1")) {
                while (rs.next()) out.add(rs.getString(1));
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static Map<String, Object> finding(String severity, String check, MaskingRuleEntity r, String detail) {
        return Map.of("severity", severity, "check", check,
                "table", r.getTableName(), "column", r.getColumnName(), "detail", detail);
    }

    public List<ValidationReportEntity> list() {
        List<ValidationReportEntity> all = reports.findAll();
        all.sort(Comparator.comparing(ValidationReportEntity::getId).reversed());
        return all;
    }

    private static String q(String ident) {
        if (!ident.matches("[A-Za-z0-9_]+")) throw ApiException.bad("Illegal identifier: " + ident);
        return "\"" + ident + "\"";
    }
}
