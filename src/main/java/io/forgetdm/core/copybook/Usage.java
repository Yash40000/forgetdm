package io.forgetdm.core.copybook;

import java.util.Locale;

/**
 * COBOL USAGE — the physical storage format of an elementary item.
 *
 *  DISPLAY        one byte per character / digit (zoned decimal for numerics). EBCDIC on the wire.
 *  COMP / COMP-4  big-endian two's-complement binary. Byte width derived from declared digits.
 *  COMP-3         packed decimal (BCD): two digits per byte, sign in the final nibble.
 *  COMP-1         single-precision float (4 bytes).  Treated as RAW (opaque) — not re-encoded.
 *  COMP-2         double-precision float (8 bytes).  Treated as RAW (opaque) — not re-encoded.
 *  COMP-5         native binary; like COMP but without digit-based value clamping.
 *
 * COMP-1/COMP-2 on z/OS are historically IBM hex-float (HFP), not IEEE-754. Rather than risk a
 * lossy conversion we carry those fields as raw bytes so round-trip is always byte-perfect; they
 * are simply not maskable until/unless HFP support is added.
 */
public enum Usage {
    DISPLAY,
    COMP,      // a.k.a COMPUTATIONAL, COMP-4, BINARY
    COMP3,     // a.k.a COMPUTATIONAL-3, PACKED-DECIMAL
    COMP1,     // COMPUTATIONAL-1, single float (raw)
    COMP2,     // COMPUTATIONAL-2, double float (raw)
    COMP5;     // COMPUTATIONAL-5, native binary

    /** True when the item holds a numeric value this codec can decode/encode to a BigDecimal. */
    public boolean isNumericBinaryLike() {
        return this == COMP || this == COMP3 || this == COMP5;
    }

    /** True when we deliberately carry the bytes verbatim (no logical decode). */
    public boolean isRaw() {
        return this == COMP1 || this == COMP2;
    }

    /** Parse a USAGE keyword (with or without the COMPUTATIONAL spelling). Returns null if unknown. */
    public static Usage parse(String token) {
        if (token == null) return null;
        String t = token.trim().toUpperCase(Locale.ROOT);
        switch (t) {
            case "DISPLAY":                              return DISPLAY;
            case "COMP": case "COMPUTATIONAL":
            case "COMP-4": case "COMPUTATIONAL-4":
            case "BINARY":                               return COMP;
            case "COMP-3": case "COMPUTATIONAL-3":
            case "PACKED-DECIMAL":                       return COMP3;
            case "COMP-1": case "COMPUTATIONAL-1":       return COMP1;
            case "COMP-2": case "COMPUTATIONAL-2":       return COMP2;
            case "COMP-5": case "COMPUTATIONAL-5":       return COMP5;
            default:                                     return null;
        }
    }
}
