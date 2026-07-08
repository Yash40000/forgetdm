package io.forgetdm.provision;

import io.forgetdm.provision.SyntheticGenService.GenColumn;
import io.forgetdm.provision.SyntheticGenService.GenTable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pre-flight range validation: compares range-generator parameters against the TARGET column's real
 * numeric capacity so unrealistic data is flagged BEFORE generation instead of discovered as clamped
 * ceiling values afterwards. The classic trap: DECIMAL_RANGE's max defaults to 1000 when left blank,
 * which overflows a NUMERIC(5,2) column (capacity ±999.99).
 *
 * Warnings are advisory (values get clamped by fit() and the load survives). The one hard error is a
 * SEQUENCE primary key that overflows its column — clamping keys would silently produce duplicates.
 */
final class SyntheticRangeChecks {
    private SyntheticRangeChecks() {}

    /** A range problem on one column. {@code fatal} = would corrupt keys, not just skew values. */
    record RangeIssue(String table, String column, boolean fatal, String message) {}

    static List<RangeIssue> check(GenTable t, long rows,
                                  Map<String, Integer> types, Map<String, Integer> sizes, Map<String, Integer> scales) {
        List<RangeIssue> issues = new ArrayList<>();
        for (GenColumn c : t.columns() == null ? List.<GenColumn>of() : t.columns()) {
            if (c == null || c.name() == null) continue;
            String col = c.name().toLowerCase(Locale.ROOT);
            Integer type = types.get(col);
            if (type == null) continue;
            BigDecimal capacity = capacityOf(type, sizes.getOrDefault(col, 0), scales.getOrDefault(col, 0));
            if (capacity == null) continue;   // not a bounded numeric column

            String gen = c.generator() == null ? "" : c.generator().trim().toUpperCase(Locale.ROOT);
            switch (gen) {
                case "DECIMAL_RANGE" -> {
                    // Generators.of: p1 = min (default 0), p2 = max (default 1000!)
                    BigDecimal max = decimalOr(c.param2(), new BigDecimal(1000));
                    BigDecimal min = decimalOr(c.param1(), BigDecimal.ZERO);
                    BigDecimal worst = max.abs().max(min.abs());
                    if (worst.compareTo(capacity) > 0) {
                        issues.add(new RangeIssue(t.name(), c.name(), false,
                                t.name() + "." + c.name() + ": DECIMAL_RANGE " + min.toPlainString() + ".."
                                        + max.toPlainString() + (blank(c.param2()) ? " (max defaulted to 1000 — param2 is blank)" : "")
                                        + " exceeds the column capacity ±" + capacity.toPlainString()
                                        + "; out-of-range values will be clamped to the ceiling. Set a realistic max."));
                    }
                }
                case "INT_RANGE" -> {
                    BigDecimal max = decimalOr(c.param2(), new BigDecimal(100));
                    BigDecimal min = decimalOr(c.param1(), BigDecimal.ZERO);
                    BigDecimal worst = max.abs().max(min.abs());
                    if (worst.compareTo(capacity) > 0) {
                        issues.add(new RangeIssue(t.name(), c.name(), false,
                                t.name() + "." + c.name() + ": INT_RANGE " + min.toPlainString() + ".." + max.toPlainString()
                                        + " exceeds the column capacity ±" + capacity.toPlainString()
                                        + "; out-of-range values will be clamped. Set a realistic range."));
                    }
                }
                case "SEQUENCE" -> {
                    if (!blank(c.param1())) break;   // prefixed sequences are strings, not numbers
                    if (BigDecimal.valueOf(rows).compareTo(capacity) > 0) {
                        boolean key = Boolean.TRUE.equals(c.primaryKey());
                        issues.add(new RangeIssue(t.name(), c.name(), key,
                                t.name() + "." + c.name() + ": SEQUENCE reaches " + rows
                                        + " but the column holds at most " + capacity.toPlainString()
                                        + (key ? " — clamping a key column would create DUPLICATE KEYS. Reduce the row count or widen the column."
                                               : "; overflowing values will be clamped.")));
                    }
                }
                default -> { }
            }
        }
        return issues;
    }

    /** Largest absolute value the column can hold, or null when unbounded/not numeric. */
    private static BigDecimal capacityOf(int jdbcType, int precision, int scale) {
        return switch (jdbcType) {
            case java.sql.Types.NUMERIC, java.sql.Types.DECIMAL -> {
                if (precision <= 0) yield null;   // undeclared precision (Postgres bare numeric, Oracle NUMBER)
                int s = Math.max(0, scale);
                yield BigDecimal.TEN.pow(Math.max(0, precision - s)).subtract(BigDecimal.ONE.movePointLeft(s));
            }
            case java.sql.Types.TINYINT -> new BigDecimal(127);
            case java.sql.Types.SMALLINT -> new BigDecimal(32_767);
            case java.sql.Types.INTEGER -> new BigDecimal(Integer.MAX_VALUE);
            case java.sql.Types.BIGINT -> new BigDecimal(Long.MAX_VALUE);
            default -> null;
        };
    }

    private static BigDecimal decimalOr(String s, BigDecimal def) {
        try { return s == null || s.isBlank() ? def : new BigDecimal(s.trim()); }
        catch (NumberFormatException e) { return def; }
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
