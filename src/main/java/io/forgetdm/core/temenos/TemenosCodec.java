package io.forgetdm.core.temenos;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Temenos T24 (jBASE/Pick-lineage) multi-value field codec.
 *
 * <p>Temenos stores nested variable-length arrays inside a single physical column, delimited by
 * control marks (RFP §3.2.1):
 * <ul>
 *   <li>{@code FM}  — Field Mark,      byte 0xFE (254)</li>
 *   <li>{@code VM}  — Value Mark,      byte 0xFD (253)</li>
 *   <li>{@code SVM} — Sub-Value Mark,  byte 0xFC (252)</li>
 *   <li>{@code TM}  — Text/Sub-sub Mark, byte 0xFB (251)</li>
 * </ul>
 * Read through a JDBC driver with an ISO-8859-1 (Latin-1) column encoding, these bytes surface as
 * the Java chars {@code þ ý ü û}. The codec treats them as the mark hierarchy
 * (FM &gt; VM &gt; SVM &gt; TM).
 *
 * <p>The key guarantee for masking (RFP §3.2.1 "Structure Preservation"): {@link #mapLeaves} rebuilds
 * the string with the <em>exact same marks in the exact same positions</em>, transforming only the
 * leaf text between them. Empty segments are preserved, so the value count and sub-value count of the
 * field never shift and adjacent sub-values never move.
 */
public final class TemenosCodec {

    public static final char FM = 'þ';   // 254 — field mark
    public static final char VM = 'ý';   // 253 — value mark
    public static final char SVM = 'ü';  // 252 — sub-value mark
    public static final char TM = 'û';   // 251 — text mark (deepest)

    /** Mark hierarchy, highest to lowest. */
    private static final char[] ORDER = { FM, VM, SVM, TM };

    private TemenosCodec() {}

    /** True if the value contains any Temenos mark, i.e. it is a structured multi-value field. */
    public static boolean hasMarkers(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == FM || c == VM || c == SVM || c == TM) return true;
        }
        return false;
    }

    /**
     * Apply {@code fn} to every leaf (deepest segment) of a marked value, preserving every mark and
     * every empty segment exactly. A value with no marks is a single leaf. {@code fn} is never called
     * with an empty leaf (empties are kept verbatim), and a null result is treated as an empty leaf.
     */
    public static String mapLeaves(String s, UnaryOperator<String> fn) {
        if (s == null) return null;
        return mapLevel(s, 0, fn);
    }

    private static String mapLevel(String s, int level, UnaryOperator<String> fn) {
        if (level >= ORDER.length || s.indexOf(ORDER[level]) < 0) {
            // No further mark at or below this level to split on here: this is a leaf.
            if (level >= ORDER.length) return applyLeaf(s, fn);
            // Marks below this level may still exist — descend before treating as a leaf.
            return hasLowerMark(s, level + 1) ? mapLevel(s, level + 1, fn) : applyLeaf(s, fn);
        }
        char mark = ORDER[level];
        String[] parts = s.split(Pattern.quote(String.valueOf(mark)), -1);
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(mark);
            sb.append(mapLevel(parts[i], level + 1, fn));
        }
        return sb.toString();
    }

    private static boolean hasLowerMark(String s, int fromLevel) {
        for (int l = fromLevel; l < ORDER.length; l++) if (s.indexOf(ORDER[l]) >= 0) return true;
        return false;
    }

    private static String applyLeaf(String s, UnaryOperator<String> fn) {
        if (s == null || s.isEmpty()) return s;
        String out = fn.apply(s);
        return out == null ? "" : out;
    }

    // ---------------------------------------------------------------- structural view

    /**
     * Parse into fields → values → sub-values. Purely for inspection/addressing; masking should use
     * {@link #mapLeaves} which does not lose the TM level or empty-segment layout.
     */
    public static List<List<List<String>>> parse(String s) {
        List<List<List<String>>> fields = new ArrayList<>();
        if (s == null) return fields;
        for (String field : split(s, FM)) {
            List<List<String>> values = new ArrayList<>();
            for (String value : split(field, VM)) {
                values.add(split(value, SVM));
            }
            fields.add(values);
        }
        return fields;
    }

    /** Serialize a fields → values → sub-values structure back to a marked string (round-trips parse). */
    public static String format(List<List<List<String>>> fields) {
        StringBuilder out = new StringBuilder();
        for (int f = 0; f < fields.size(); f++) {
            if (f > 0) out.append(FM);
            List<List<String>> values = fields.get(f);
            for (int v = 0; v < values.size(); v++) {
                if (v > 0) out.append(VM);
                List<String> subs = values.get(v);
                for (int sv = 0; sv < subs.size(); sv++) {
                    if (sv > 0) out.append(SVM);
                    out.append(subs.get(sv));
                }
            }
        }
        return out.toString();
    }

    /** Number of top-level multi-values in the first field (e.g. NAME.1, NAME.2 → 2). */
    public static int valueCount(String s) {
        if (s == null || s.isEmpty()) return 0;
        int firstFieldEnd = s.indexOf(FM);
        String field = firstFieldEnd < 0 ? s : s.substring(0, firstFieldEnd);
        return split(field, VM).size();
    }

    private static List<String> split(String s, char mark) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == mark) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }
}
