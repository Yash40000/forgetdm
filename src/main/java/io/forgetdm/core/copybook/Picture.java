package io.forgetdm.core.copybook;

import java.util.Locale;

/**
 * A parsed PICTURE clause.
 *
 * Handles the symbols that affect physical layout and value:
 *   X  alphanumeric        A  alphabetic
 *   9  numeric digit       S  operational sign (no storage in DISPLAY unless SEPARATE)
 *   V  implied decimal point (no storage)
 *   P  decimal scaling position (no storage; shifts the implied point)
 *   Z  zero-suppression / 0 / , / . / B / / / + / - / $ / CR / DB  (edited fields)
 *
 * Repetition factors are expanded, e.g. {@code 9(5)V99} → 7 digits, scale 2.
 *
 * Edited pictures (containing Z , . $ etc.) are treated as alphanumeric for codec purposes:
 * their bytes are pure EBCDIC text, so we read/write them as text and never re-derive a number.
 */
public final class Picture {

    public enum Category { ALPHANUMERIC, NUMERIC, NUMERIC_EDITED, ALPHABETIC }

    private final String normalized;   // expanded symbol string, e.g. "S999V99"
    private final Category category;
    private final int digits;          // count of '9' positions (integer + fractional)
    private final int scale;           // fractional digit count (after V, plus P adjustment)
    private final boolean signed;      // an 'S' was present
    private final int displayLength;   // byte length when stored as DISPLAY (chars / zoned digits)

    private Picture(String normalized, Category category, int digits, int scale,
                    boolean signed, int displayLength) {
        this.normalized = normalized;
        this.category = category;
        this.digits = digits;
        this.scale = scale;
        this.signed = signed;
        this.displayLength = displayLength;
    }

    public Category category()    { return category; }
    public int digits()           { return digits; }
    public int scale()            { return scale; }
    public boolean signed()       { return signed; }
    public int displayLength()    { return displayLength; }
    public String normalized()    { return normalized; }
    public boolean isNumeric()    { return category == Category.NUMERIC; }

    public static Picture parse(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Empty PICTURE clause");
        String src = raw.trim().toUpperCase(Locale.ROOT);

        StringBuilder expanded = new StringBuilder();
        boolean hasX = false, hasA = false, hasNine = false, hasEdit = false;
        boolean signedFlag = false;
        boolean afterV = false;
        int digitCount = 0, scaleCount = 0;
        int pBeforeV = 0, pAfterV = 0;   // 'P' scaling positions

        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);

            // repetition factor: SYMBOL(n)
            int repeat = 1;
            char symbol = c;
            if (i + 1 < src.length() && src.charAt(i + 1) == '(') {
                int close = src.indexOf(')', i + 2);
                if (close < 0) throw new IllegalArgumentException("Unclosed repetition factor in PICTURE: " + raw);
                String num = src.substring(i + 2, close).trim();
                try { repeat = Integer.parseInt(num); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("Bad repetition factor '" + num + "' in PICTURE: " + raw); }
                if (repeat <= 0) throw new IllegalArgumentException("Non-positive repetition factor in PICTURE: " + raw);
                i = close;
            }

            switch (symbol) {
                case 'X': hasX = true;  for (int k = 0; k < repeat; k++) expanded.append('X'); break;
                case 'A': hasA = true;  for (int k = 0; k < repeat; k++) expanded.append('A'); break;
                case '9':
                    hasNine = true; digitCount += repeat;
                    if (afterV) scaleCount += repeat;
                    for (int k = 0; k < repeat; k++) expanded.append('9');
                    break;
                case 'S': signedFlag = true; expanded.append('S'); break;       // one S only, no storage
                case 'V': afterV = true; expanded.append('V'); break;           // implied point, no storage
                case 'P':                                                       // scaling, no storage
                    if (afterV) pAfterV += repeat; else pBeforeV += repeat;
                    for (int k = 0; k < repeat; k++) expanded.append('P');
                    break;
                // edited / floating symbols — render as alphanumeric text
                case 'Z': case '0': case 'B': case '/': case ',': case '.':
                case '+': case '-': case '*': case '$':
                    hasEdit = true; for (int k = 0; k < repeat; k++) expanded.append(symbol); break;
                case 'C': // CR
                case 'R':
                case 'D': // DB
                    hasEdit = true; expanded.append(symbol); break;
                default:
                    throw new IllegalArgumentException("Unsupported PICTURE symbol '" + symbol + "' in: " + raw);
            }
        }

        // 'P' positions extend the scale / magnitude without occupying storage.
        // P after V increases the fractional scale; P before V represents trailing integer zeros.
        int effectiveScale = scaleCount + pAfterV;

        Category cat;
        if (hasEdit && hasNine)               cat = Category.NUMERIC_EDITED;
        else if (hasX)                        cat = Category.ALPHANUMERIC;
        else if (hasA && !hasNine)            cat = Category.ALPHABETIC;
        else if (hasNine && !hasX && !hasEdit) cat = Category.NUMERIC;
        else                                   cat = Category.ALPHANUMERIC;

        // DISPLAY byte length: numeric = one byte per declared digit (sign overpunched, no extra byte);
        // alphanumeric/edited = one byte per rendered character position (excluding S/V which are zero-width).
        int dispLen;
        if (cat == Category.NUMERIC) {
            dispLen = digitCount; // P positions are not stored; sign is overpunched into a digit byte
        } else {
            int len = 0;
            for (int i = 0; i < expanded.length(); i++) {
                char c = expanded.charAt(i);
                if (c != 'S' && c != 'V') len++;   // S and V occupy no character position
            }
            dispLen = len;
        }

        return new Picture(expanded.toString(), cat, digitCount, effectiveScale, signedFlag, dispLen);
    }
}
