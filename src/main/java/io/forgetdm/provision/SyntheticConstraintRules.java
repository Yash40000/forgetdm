package io.forgetdm.provision;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small, deterministic CHECK-constraint interpreter for rules we can enforce safely during generation.
 * Unsupported DB expressions are still captured as evidence, but are left to DB validation.
 */
final class SyntheticConstraintRules {
    private SyntheticConstraintRules() {}

    record Rule(String table, String constraintName, String expression, String column, String ruleType,
                List<String> allowedValues, BigDecimal min, BigDecimal max) {
        Map<String, Object> evidence() {
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            out.put("table", table);
            out.put("constraintName", constraintName);
            out.put("expression", expression);
            out.put("column", column);
            out.put("ruleType", ruleType);
            if (allowedValues != null && !allowedValues.isEmpty()) out.put("allowedValues", allowedValues);
            if (min != null) out.put("min", min.toPlainString());
            if (max != null) out.put("max", max.toPlainString());
            out.put("enforced", true);
            return out;
        }
    }

    private static final String IDENT = "\"?([A-Za-z_][A-Za-z0-9_]*)\"?";
    private static final String NUM = "(-?\\d+(?:\\.\\d+)?)";
    private static final String CAST = "(?:\\s*::\\s*[A-Za-z_][A-Za-z0-9_ ]*(?:\\([^)]*\\))?)";
    private static final Pattern IN_PATTERN = Pattern.compile("^" + IDENT + "\\s+IN\\s*\\(([^)]*)\\)$", Pattern.CASE_INSENSITIVE);
    // PostgreSQL rewrites "col IN ('A','B')" to "((col)::text = ANY ((ARRAY['A'::character varying, ...])::text[]))"
    private static final Pattern ANY_ARRAY_PATTERN = Pattern.compile(
            "^\\(?\\s*" + IDENT + "\\s*\\)?" + CAST + "?\\s*=\\s*ANY\\s*\\(\\s*\\(?\\s*ARRAY\\s*\\[(.*)\\]\\s*\\)?" + CAST + "?(?:\\s*\\[\\s*\\])?\\s*\\)\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern BETWEEN_PATTERN = Pattern.compile("^" + IDENT + "\\s+BETWEEN\\s+" + NUM + "\\s+AND\\s+" + NUM + "$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMP_PATTERN = Pattern.compile("^" + IDENT + "\\s*(>=|>|<=|<)\\s*" + NUM + "$", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNSAFE_TOKEN_PATTERN = Pattern.compile(
            "\\b(OR|LIKE|ILIKE|REGEXP|RLIKE|SIMILAR|IS\\s+NULL|IS\\s+NOT\\s+NULL)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FUNCTION_PATTERN = Pattern.compile(
            "\\b(REGEXP_LIKE|COALESCE|NVL|UPPER|LOWER|LENGTH|LEN|SUBSTR|SUBSTRING|TRIM|CAST|CONVERT|DATE|CURRENT_DATE)\\s*\\(",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LITERAL_PATTERN = Pattern.compile("'(?:''|[^'])*'|[^,]+");

    static List<Rule> parseAll(String table, String constraintName, String expression) {
        if (expression == null || expression.isBlank()) return List.of();
        String normalized = normalizeExpression(expression);
        if (normalized.isBlank() || isEvidenceOnly(normalized)) return List.of();

        Rule single = parseSingleRule(table, constraintName, expression, normalized);
        if (single != null) return List.of(single);

        List<String> clauses = splitTopLevelAnd(normalized);
        if (clauses.size() <= 1) return List.of();

        List<Rule> out = new ArrayList<>();
        String column = null;
        for (String rawClause : clauses) {
            String clause = stripOuterParens(rawClause.trim());
            Matcher comp = COMP_PATTERN.matcher(clause);
            if (!comp.matches()) return List.of();
            if (column == null) column = comp.group(1);
            else if (!column.equalsIgnoreCase(comp.group(1))) return List.of();
            Optional<BigDecimal> n = decimal(comp.group(3));
            if (n.isEmpty()) return List.of();
            String op = comp.group(2);
            if (op.startsWith(">")) {
                BigDecimal min = ">".equals(op) ? nextInside(n.get(), true) : n.get();
                out.add(new Rule(table, constraintName, expression, comp.group(1), "MIN", List.of(), min, null));
            } else {
                BigDecimal max = "<".equals(op) ? nextInside(n.get(), false) : n.get();
                out.add(new Rule(table, constraintName, expression, comp.group(1), "MAX", List.of(), null, max));
            }
        }
        return out;
    }

    private static Rule parseSingleRule(String table, String constraintName, String expression, String normalized) {
        Matcher in = IN_PATTERN.matcher(normalized);
        if (in.matches()) {
            List<String> values = parseList(in.group(2));
            return values.isEmpty() ? null
                    : new Rule(table, constraintName, expression, in.group(1), "IN", values, null, null);
        }

        Matcher between = BETWEEN_PATTERN.matcher(normalized);
        if (between.matches()) {
            Optional<BigDecimal> min = decimal(between.group(2));
            Optional<BigDecimal> max = decimal(between.group(3));
            if (min.isEmpty() || max.isEmpty()) return null;
            return new Rule(table, constraintName, expression, between.group(1), "RANGE", List.of(), min.get(), max.get());
        }

        Matcher comp = COMP_PATTERN.matcher(normalized);
        if (comp.matches()) {
            Optional<BigDecimal> n = decimal(comp.group(3));
            if (n.isEmpty()) return null;
            String op = comp.group(2);
            if (op.startsWith(">")) {
                BigDecimal min = ">".equals(op) ? nextInside(n.get(), true) : n.get();
                return new Rule(table, constraintName, expression, comp.group(1), "MIN", List.of(), min, null);
            }
            BigDecimal max = "<".equals(op) ? nextInside(n.get(), false) : n.get();
            return new Rule(table, constraintName, expression, comp.group(1), "MAX", List.of(), null, max);
        }
        return null;
    }

    private static String normalizeExpression(String expression) {
        String s = expression == null ? "" : expression.trim();
        if (s.regionMatches(true, 0, "CHECK", 0, 5)) s = s.substring(5).trim();
        s = stripOuterParens(s);
        s = s.replaceAll("(?i)\\((-?\\d+(?:\\.\\d+)?)\\)\\s*::\\s*[A-Za-z_][A-Za-z0-9_]*(?:\\([^)]*\\))?", "$1");
        s = s.replaceAll("(?i)(-?\\d+(?:\\.\\d+)?)\\s*::\\s*[A-Za-z_][A-Za-z0-9_]*(?:\\([^)]*\\))?", "$1");
        return rewriteAnyArrayToIn(stripOuterParens(s));
    }

    /** Convert Postgres's "= ANY (ARRAY[...])" rewriting of an IN-list back to canonical "col IN (...)" so it can be enforced. */
    private static String rewriteAnyArrayToIn(String expression) {
        Matcher m = ANY_ARRAY_PATTERN.matcher(expression);
        if (!m.matches()) return expression;
        List<String> literals = new ArrayList<>();
        for (String part : splitTopLevelCommas(m.group(2))) {
            String v = stripTrailingCast(part.trim());
            if (v.isBlank()) return expression;   // unexpected element shape — leave for DB validation
            literals.add(v);
        }
        if (literals.isEmpty()) return expression;
        return m.group(1) + " IN (" + String.join(", ", literals) + ")";
    }

    private static List<String> splitTopLevelCommas(String s) {
        List<String> out = new ArrayList<>();
        boolean quoted = false;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\'') {
                if (quoted && i + 1 < s.length() && s.charAt(i + 1) == '\'') { i++; continue; }
                quoted = !quoted;
            } else if (!quoted && ch == ',') {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }

    /** "'PENDING'::character varying" -> "'PENDING'";  "1::numeric" -> "1". Empty when the element isn't a plain literal. */
    private static String stripTrailingCast(String value) {
        if (value.startsWith("'")) {
            int i = 1;
            while (i < value.length()) {
                if (value.charAt(i) == '\'') {
                    if (i + 1 < value.length() && value.charAt(i + 1) == '\'') { i += 2; continue; }
                    String rest = value.substring(i + 1).trim();
                    return rest.isEmpty() || rest.startsWith("::") ? value.substring(0, i + 1) : "";
                }
                i++;
            }
            return "";   // unterminated literal
        }
        int cast = value.indexOf("::");
        return (cast >= 0 ? value.substring(0, cast) : value).trim();
    }

    private static boolean isEvidenceOnly(String expression) {
        if (UNSAFE_TOKEN_PATTERN.matcher(expression).find()) return true;
        if (FUNCTION_PATTERN.matcher(expression).find()) return true;
        return expression.matches(".*[+*/%].*");
    }

    private static List<String> splitTopLevelAnd(String expression) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        boolean quoted = false;
        int start = 0;
        for (int i = 0; i < expression.length(); i++) {
            char ch = expression.charAt(i);
            if (ch == '\'') {
                quoted = !quoted;
                if (quoted && i + 1 < expression.length() && expression.charAt(i + 1) == '\'') i++;
            } else if (!quoted) {
                if (ch == '(') depth++;
                else if (ch == ')' && depth > 0) depth--;
                else if (depth == 0 && isAndAt(expression, i)) {
                    out.add(expression.substring(start, i).trim());
                    i += 2;
                    start = i + 1;
                }
            }
        }
        out.add(expression.substring(start).trim());
        return out.stream().filter(s -> !s.isBlank()).toList();
    }

    private static boolean isAndAt(String s, int idx) {
        if (idx + 3 > s.length() || !s.regionMatches(true, idx, "AND", 0, 3)) return false;
        boolean left = idx == 0 || !Character.isLetterOrDigit(s.charAt(idx - 1));
        boolean right = idx + 3 == s.length() || !Character.isLetterOrDigit(s.charAt(idx + 3));
        return left && right;
    }

    private static String stripOuterParens(String value) {
        String s = value == null ? "" : value.trim();
        while (s.startsWith("(") && s.endsWith(")") && wrapsWholeExpression(s)) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    private static boolean wrapsWholeExpression(String s) {
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\'') {
                quoted = !quoted;
                if (quoted && i + 1 < s.length() && s.charAt(i + 1) == '\'') i++;
            } else if (!quoted) {
                if (ch == '(') depth++;
                else if (ch == ')') {
                    depth--;
                    if (depth == 0 && i < s.length() - 1) return false;
                }
            }
        }
        return depth == 0;
    }

    static void apply(LinkedHashMap<String, String> row, List<Rule> rules, long rowNum) {
        if (row == null || rules == null || rules.isEmpty()) return;
        for (Rule rule : rules) {
            String key = matchKey(row, rule.column());
            if (key == null) continue;
            switch (rule.ruleType()) {
                case "IN" -> applyIn(row, key, rule, rowNum);
                case "RANGE", "MIN", "MAX" -> applyRange(row, key, rule);
                default -> { }
            }
        }
    }

    private static void applyIn(LinkedHashMap<String, String> row, String key, Rule rule, long rowNum) {
        List<String> values = rule.allowedValues();
        if (values == null || values.isEmpty()) return;
        String current = row.get(key);
        for (String value : values) {
            if (value.equals(current) || (current != null && value.equalsIgnoreCase(current))) return;
        }
        int idx = Math.floorMod(Long.hashCode(rowNum + ObjectsHash.stable(rule.table(), rule.column())), values.size());
        row.put(key, values.get(idx));
    }

    private static void applyRange(LinkedHashMap<String, String> row, String key, Rule rule) {
        String current = row.get(key);
        BigDecimal value = decimal(current).orElse(null);
        if (value == null) value = rule.min() != null ? rule.min() : rule.max();
        if (value == null) return;
        if (rule.min() != null && value.compareTo(rule.min()) < 0) value = rule.min();
        if (rule.max() != null && value.compareTo(rule.max()) > 0) value = rule.max();
        row.put(key, value.stripTrailingZeros().toPlainString());
    }

    private static Optional<BigDecimal> decimal(String value) {
        try {
            if (value == null || value.isBlank()) return Optional.empty();
            return Optional.of(new BigDecimal(value.trim()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static BigDecimal nextInside(BigDecimal boundary, boolean above) {
        int scale = Math.max(0, boundary.scale());
        BigDecimal step = BigDecimal.ONE.movePointLeft(scale);
        if (BigDecimal.ONE.compareTo(step) > 0 && scale == 0) step = BigDecimal.ONE;
        return above ? boundary.add(step) : boundary.subtract(step);
    }

    private static List<String> parseList(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        Matcher m = LITERAL_PATTERN.matcher(raw);
        while (m.find()) {
            String v = m.group().trim();
            if (v.isBlank()) continue;
            if (v.startsWith("'") && v.endsWith("'") && v.length() >= 2) {
                v = v.substring(1, v.length() - 1).replace("''", "'");
            }
            if (!v.isBlank()) out.add(v);
        }
        return out;
    }

    private static String matchKey(Map<String, String> row, String column) {
        if (column == null) return null;
        if (row.containsKey(column)) return column;
        for (String key : row.keySet()) {
            if (key.equalsIgnoreCase(column)) return key;
        }
        return null;
    }

    private static final class ObjectsHash {
        private ObjectsHash() {}

        static int stable(String a, String b) {
            return String.valueOf(a).toLowerCase(Locale.ROOT).hashCode() * 31
                    + String.valueOf(b).toLowerCase(Locale.ROOT).hashCode();
        }
    }
}
