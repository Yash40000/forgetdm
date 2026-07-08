package io.forgetdm.core.copybook;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Parses COBOL copybook source into a {@link Copybook} (field tree + computed layout).
 *
 * Supported: levels 01–49/66/77/88, FILLER, PIC/PICTURE, USAGE (DISPLAY, COMP/COMP-3/COMP-4/
 * COMP-5/BINARY/PACKED-DECIMAL/COMP-1/COMP-2) including group-inherited usage, OCCURS (fixed,
 * "n TO m", and DEPENDING ON), REDEFINES, RENAMES (66) with THRU/THROUGH, condition-names (88),
 * SIGN [IS] LEADING|TRAILING [SEPARATE], SYNCHRONIZED (parsed, not applied), VALUE (skipped).
 *
 * Input may be fixed-format (sequence area cols 1-6, indicator col 7, code cols 8-72) or free
 * format; comments (* or / in the indicator column, or a leading *) are ignored.
 */
public final class CopybookParser {

    private CopybookParser() {}

    public static Copybook parse(String source) {
        List<String> statements = splitStatements(preprocess(source));

        List<Field> records = new ArrayList<>();
        Deque<Field> stack = new ArrayDeque<>();   // storage fields only (levels 1-49, 77)
        Field currentRecord = null;

        for (String stmt : statements) {
            List<String> tokens = tokenize(stmt);
            if (tokens.isEmpty()) continue;

            Integer level = parseLevel(tokens.get(0));
            if (level == null) continue;            // not a data description entry

            if (level == 88) {                      // condition-name → attach to current field
                Field owner = stack.peek();
                if (owner != null) owner.conditions().add(parseCondition(tokens));
                continue;
            }
            if (level == 66) {                      // RENAMES → attach to current record
                Field rename = parseRename(tokens);
                if (currentRecord != null) { rename.setParent(currentRecord); currentRecord.children().add(rename); }
                continue;
            }

            Field field = parseEntry(level, tokens);

            while (!stack.isEmpty() && stack.peek().level() >= level) stack.pop();
            if (stack.isEmpty()) {
                records.add(field);
                currentRecord = field;
            } else {
                Field parent = stack.peek();
                field.setParent(parent);
                parent.children().add(field);
            }
            stack.push(field);
        }

        if (records.isEmpty()) throw new IllegalArgumentException("Copybook contained no data description entries");
        for (Field record : records) LayoutComputer.compute(record);
        return new Copybook(records);
    }

    // ----------------------------------------------------------- preprocessing

    private static String preprocess(String source) {
        if (source == null) throw new IllegalArgumentException("Copybook source is null");
        StringBuilder buf = new StringBuilder();
        for (String raw : source.split("\\r?\\n", -1)) {
            if (raw.isBlank()) continue;
            boolean fixedLike = raw.length() >= 7 && first6AreSeqArea(raw)
                    && (raw.charAt(6) == ' ' || raw.charAt(6) == '*' || raw.charAt(6) == '/');
            if (fixedLike && (raw.charAt(6) == '*' || raw.charAt(6) == '/')) continue;   // fixed comment
            String content;
            if (fixedLike) {
                content = raw.substring(7, Math.min(raw.length(), 72));
            } else {
                String t = raw.trim();
                if (t.startsWith("*") || t.startsWith("/")) continue;                    // free comment
                content = raw;
            }
            buf.append(content).append(' ');
        }
        return buf.toString();
    }

    private static boolean first6AreSeqArea(String line) {
        for (int i = 0; i < 6; i++) {
            char c = line.charAt(i);
            if (!Character.isDigit(c) && c != ' ') return false;
        }
        return true;
    }

    /** Split on '.' that are outside quoted strings; each piece is one data description entry. */
    private static List<String> splitStatements(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                cur.append(c);
                if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') {
                quote = c; cur.append(c);
            } else if (c == '.') {
                if (cur.toString().isBlank()) cur.setLength(0);
                else { out.add(cur.toString().trim()); cur.setLength(0); }
            } else {
                cur.append(c);
            }
        }
        if (!cur.toString().isBlank()) out.add(cur.toString().trim());
        return out;
    }

    /** Whitespace tokenizer that keeps quoted strings intact as a single token. */
    private static List<String> tokenize(String stmt) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < stmt.length(); i++) {
            char c = stmt.charAt(i);
            if (quote != 0) {
                cur.append(c);
                if (c == quote) { out.add(cur.toString()); cur.setLength(0); quote = 0; }
            } else if (c == '\'' || c == '"') {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
                quote = c; cur.append(c);
            } else if (Character.isWhitespace(c)) {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static Integer parseLevel(String t) {
        try {
            int n = Integer.parseInt(t.trim());
            boolean ok = (n >= 1 && n <= 49) || n == 66 || n == 77 || n == 88;
            return ok ? n : null;
        } catch (NumberFormatException e) { return null; }
    }

    // ------------------------------------------------------------- entry parse

    private static Field parseEntry(int level, List<String> t) {
        String name = t.size() > 1 ? stripName(t.get(1)) : "FILLER";
        Field f = new Field(level, name);

        int i = 2;
        while (i < t.size()) {
            String kw = t.get(i).toUpperCase(Locale.ROOT);
            switch (kw) {
                case "PIC": case "PICTURE": {
                    i++;
                    if (i < t.size() && t.get(i).equalsIgnoreCase("IS")) i++;
                    if (i >= t.size()) throw new IllegalArgumentException("PICTURE clause missing value on '" + name + "'");
                    f.setRawPicture(t.get(i));
                    f.setPicture(Picture.parse(t.get(i)));
                    i++;
                    break;
                }
                case "REDEFINES": {
                    i++;
                    if (i >= t.size()) throw new IllegalArgumentException("REDEFINES missing target on '" + name + "'");
                    f.setRedefines(stripName(t.get(i))); i++;
                    break;
                }
                case "OCCURS": {
                    i = parseOccurs(f, t, i + 1, name);
                    break;
                }
                case "USAGE": {
                    i++;
                    if (i < t.size() && t.get(i).equalsIgnoreCase("IS")) i++;
                    Usage u = i < t.size() ? Usage.parse(t.get(i)) : null;
                    if (u == null) throw new IllegalArgumentException("Unrecognized USAGE on '" + name + "': "
                            + (i < t.size() ? t.get(i) : "<end>"));
                    f.setUsage(u); i++;
                    break;
                }
                case "SIGN": {
                    i = parseSign(f, t, i + 1);
                    break;
                }
                case "SYNC": case "SYNCHRONIZED": {
                    f.setSync(true); i++;
                    if (i < t.size() && (t.get(i).equalsIgnoreCase("LEFT") || t.get(i).equalsIgnoreCase("RIGHT"))) i++;
                    break;
                }
                case "LEADING": case "TRAILING": {   // bare SIGN spec without the SIGN keyword
                    i = parseSign(f, t, i);
                    break;
                }
                case "VALUE": case "VALUES": {        // skip VALUE literal(s) for storage items
                    i++;
                    if (i < t.size() && (t.get(i).equalsIgnoreCase("IS") || t.get(i).equalsIgnoreCase("ARE"))) i++;
                    while (i < t.size() && !isClauseKeyword(t.get(i))) i++;
                    break;
                }
                case "JUST": case "JUSTIFIED": {
                    i++;
                    if (i < t.size() && t.get(i).equalsIgnoreCase("RIGHT")) i++;
                    break;
                }
                case "BLANK": {                       // BLANK WHEN ZERO
                    i++;
                    if (i < t.size() && t.get(i).equalsIgnoreCase("WHEN")) i++;
                    if (i < t.size() && t.get(i).equalsIgnoreCase("ZERO")) i++;
                    break;
                }
                default: {
                    Usage bare = Usage.parse(t.get(i));  // bare COMP/COMP-3/BINARY... usage
                    if (bare != null) f.setUsage(bare);
                    i++;                                  // ignore anything else (DEPENDING handled in OCCURS)
                }
            }
        }
        return f;
    }

    private static int parseOccurs(Field f, List<String> t, int i, String name) {
        if (i >= t.size()) throw new IllegalArgumentException("OCCURS missing count on '" + name + "'");
        int min, max;
        int first = parseInt(t.get(i), name); i++;
        if (i < t.size() && t.get(i).equalsIgnoreCase("TO")) {
            i++;
            if (i >= t.size()) throw new IllegalArgumentException("OCCURS .. TO missing upper bound on '" + name + "'");
            min = first; max = parseInt(t.get(i), name); i++;
        } else {
            min = first; max = first;
        }
        if (i < t.size() && t.get(i).equalsIgnoreCase("TIMES")) i++;
        if (i < t.size() && t.get(i).equalsIgnoreCase("DEPENDING")) {
            i++;
            if (i < t.size() && t.get(i).equalsIgnoreCase("ON")) i++;
            if (i >= t.size()) throw new IllegalArgumentException("OCCURS DEPENDING ON missing target on '" + name + "'");
            f.setDependingOn(stripName(t.get(i))); i++;
            if (min == max) min = 0;   // "OCCURS n DEPENDING ON x" → 0..n
        }
        f.setOccurs(min, max);
        return i;
    }

    private static int parseSign(Field f, List<String> t, int i) {
        if (i < t.size() && t.get(i).equalsIgnoreCase("IS")) i++;
        if (i < t.size() && t.get(i).equalsIgnoreCase("LEADING"))  { f.setSignLeading(true);  i++; }
        else if (i < t.size() && t.get(i).equalsIgnoreCase("TRAILING")) { f.setSignLeading(false); i++; }
        if (i < t.size() && t.get(i).equalsIgnoreCase("SEPARATE")) {
            f.setSignSeparate(true); i++;
            if (i < t.size() && t.get(i).equalsIgnoreCase("CHARACTER")) i++;
        }
        return i;
    }

    private static Field parseRename(List<String> t) {
        // 66 NEW-NAME RENAMES a [THRU|THROUGH b]
        String name = t.size() > 1 ? stripName(t.get(1)) : "FILLER";
        Field f = new Field(66, name);
        int i = 2;
        if (i < t.size() && t.get(i).equalsIgnoreCase("RENAMES")) i++;
        String from = i < t.size() ? stripName(t.get(i)) : null; i++;
        String thru = null;
        if (i < t.size() && (t.get(i).equalsIgnoreCase("THRU") || t.get(i).equalsIgnoreCase("THROUGH"))) {
            i++;
            thru = i < t.size() ? stripName(t.get(i)) : null;
        }
        f.setRenames(from, thru);
        return f;
    }

    private static Field.Condition parseCondition(List<String> t) {
        // 88 COND-NAME VALUE[S] [IS|ARE] v1 [THRU v2] v3 ...
        String name = t.size() > 1 ? stripName(t.get(1)) : "FILLER";
        List<String> values = new ArrayList<>();
        int i = 2;
        if (i < t.size() && (t.get(i).equalsIgnoreCase("VALUE") || t.get(i).equalsIgnoreCase("VALUES"))) i++;
        if (i < t.size() && (t.get(i).equalsIgnoreCase("IS") || t.get(i).equalsIgnoreCase("ARE"))) i++;
        for (; i < t.size(); i++) {
            String tok = t.get(i);
            if (tok.equalsIgnoreCase("THRU") || tok.equalsIgnoreCase("THROUGH")) continue;
            values.add(unquote(tok));
        }
        return new Field.Condition(name, values);
    }

    // ----------------------------------------------------------------- helpers

    private static boolean isClauseKeyword(String token) {
        switch (token.toUpperCase(Locale.ROOT)) {
            case "PIC": case "PICTURE": case "REDEFINES": case "OCCURS": case "USAGE":
            case "SIGN": case "SYNC": case "SYNCHRONIZED": case "JUST": case "JUSTIFIED":
            case "BLANK": case "VALUE": case "VALUES": case "LEADING": case "TRAILING":
                return true;
            default:
                return Usage.parse(token) != null;
        }
    }

    private static String stripName(String raw) {
        return raw == null ? "FILLER" : raw.trim();
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.charAt(0) == '\'' || s.charAt(0) == '"') && s.charAt(s.length() - 1) == s.charAt(0))
            return s.substring(1, s.length() - 1);
        return s;
    }

    private static int parseInt(String s, String name) {
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Expected a number in OCCURS on '" + name + "', got '" + s + "'"); }
    }
}
