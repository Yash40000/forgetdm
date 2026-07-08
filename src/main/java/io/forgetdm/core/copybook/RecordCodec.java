package io.forgetdm.core.copybook;

import io.forgetdm.core.copybook.codec.BinaryInt;
import io.forgetdm.core.copybook.codec.Ebcdic;
import io.forgetdm.core.copybook.codec.PackedDecimal;
import io.forgetdm.core.copybook.codec.ZonedDecimal;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Decodes a physical record into logical field values and re-encodes masked values back into the
 * exact byte layout.
 *
 * The walk is dynamic: OCCURS DEPENDING ON counts are resolved from earlier fields in the same
 * record, so fields after a variable table land at the correct offset. REDEFINES are overlaid at
 * the redefined sibling's position. OCCURS items are expanded with 1-based subscripts.
 *
 * Encoding uses an OVERLAY strategy: start from the original record bytes and rewrite only the
 * fields that changed. This guarantees byte-for-byte fidelity for everything not masked (FILLER,
 * alignment slack, untouched redefine views, RAW float fields) and is exactly what a masking
 * pass needs.
 */
public final class RecordCodec {

    private final Field record;
    private final Ebcdic ebcdic;

    public RecordCodec(Field record, Ebcdic ebcdic) {
        this.record = record;
        this.ebcdic = ebcdic == null ? Ebcdic.defaultPage() : ebcdic;
    }

    public RecordCodec(Field record) { this(record, Ebcdic.defaultPage()); }

    public Field record() { return record; }

    // ----------------------------------------------------------------- decode

    public RecordValue decode(byte[] rec) {
        RecordValue out = new RecordValue(rec);
        Ctx ctx = new Ctx(rec, out);
        decodeInstance(record, 0, "", ctx);
        return out;
    }

    private static final class Ctx {
        final byte[] rec;
        final RecordValue out;
        final Map<String, Long> numericByName = new HashMap<>();   // for OCCURS DEPENDING ON
        Ctx(byte[] rec, RecordValue out) { this.rec = rec; this.out = out; }
    }

    /** Decode a field that may OCCUR; returns bytes consumed from {@code start}. */
    private int decodeOccurring(Field f, int start, String parentPath, Ctx ctx) {
        int count = f.dependingOn() != null ? resolveOdo(f, ctx) : Math.max(1, f.occursMax());
        int cursor = start;
        String base = join(parentPath, f.name());
        for (int k = 0; k < count; k++) {
            String label = f.occurs() ? base + "(" + (k + 1) + ")" : base;
            cursor += decodeInstance(f, cursor, label, ctx);
        }
        return cursor - start;
    }

    /** Decode ONE occurrence (elementary value, or a group's children); returns bytes consumed. */
    private int decodeInstance(Field f, int base, String label, Ctx ctx) {
        if (f.isElementary()) {
            String path = label.isEmpty() ? f.name() : label;
            String value = decodeElementary(f, ctx.rec, base);
            boolean numeric = isNumeric(f);
            ctx.out.add(new RecordValue.DecodedField(path, f, base, f.length(), value, numeric));
            if (numeric && scaleOf(f) == 0) rememberForOdo(f, value, ctx);
            return f.length();
        }
        // group: walk children, tracking each non-redefining child's start for REDEFINES overlay
        Map<String, Integer> siblingStart = new HashMap<>();
        int cursor = base;
        for (Field child : f.children()) {
            if (child.isRename() || child.level() == 88) continue;
            if (child.redefines() != null) {
                Integer tgt = siblingStart.get(child.redefines().toUpperCase(Locale.ROOT));
                int cstart = tgt != null ? tgt : cursor;
                decodeOccurring(child, cstart, label, ctx);   // overlay: does not advance cursor
            } else {
                siblingStart.put(child.name().toUpperCase(Locale.ROOT), cursor);
                cursor += decodeOccurring(child, cursor, label, ctx);
            }
        }
        return cursor - base;
    }

    private int resolveOdo(Field f, Ctx ctx) {
        Long n = ctx.numericByName.get(f.dependingOn().toUpperCase(Locale.ROOT));
        int count = n == null ? f.occursMax() : (int) Math.max(0, Math.min(n, f.occursMax()));
        return count;
    }

    private void rememberForOdo(Field f, String value, Ctx ctx) {
        try { ctx.numericByName.put(f.name().toUpperCase(Locale.ROOT), new BigDecimal(value).longValueExact()); }
        catch (Exception ignored) { /* not an integer we can use for ODO */ }
    }

    // ----------------------------------------------------------------- encode

    /**
     * Produce a new record: copy the original bytes and rewrite only the changed fields.
     * {@code changes} maps decoded field paths to new logical values. Unknown paths are rejected.
     */
    public byte[] encodeOverlay(RecordValue decoded, byte[] original, Map<String, String> changes) {
        byte[] out = original.clone();
        for (Map.Entry<String, String> e : changes.entrySet()) {
            RecordValue.DecodedField df = decoded.get(e.getKey());
            if (df == null) throw new IllegalArgumentException("Cannot encode unknown field path: " + e.getKey());
            byte[] enc = encodeElementary(df.field(), e.getValue(), df.length());
            System.arraycopy(enc, 0, out, df.offset(), df.length());
        }
        return out;
    }

    // ------------------------------------------------------- field-level codec

    private String decodeElementary(Field f, byte[] rec, int base) {
        Usage u = f.effectiveUsage();
        int len = f.length();
        if (u.isRaw()) return "0x" + hex(rec, base, len);

        int scale = scaleOf(f);
        boolean signed = f.picture() != null && f.picture().signed();
        switch (u) {
            case COMP:
            case COMP5:
                return BinaryInt.decode(rec, base, len, scale, signed).toPlainString();
            case COMP3:
                return PackedDecimal.decode(rec, base, len, scale).toPlainString();
            case DISPLAY:
            default:
                if (isNumeric(f)) {
                    return ZonedDecimal.decode(rec, base, len, scale, signed,
                            f.signLeading(), f.signSeparate()).toPlainString();
                }
                return ebcdic.decode(rec, base, len);
        }
    }

    private byte[] encodeElementary(Field f, String value, int len) {
        Usage u = f.effectiveUsage();
        if (u.isRaw()) throw new IllegalArgumentException("Field '" + f.name() + "' is a RAW float (COMP-1/2) and cannot be masked");

        int scale = scaleOf(f);
        boolean signed = f.picture() != null && f.picture().signed();
        switch (u) {
            case COMP:
            case COMP5:
                return BinaryInt.encode(new BigDecimal(value), len, scale, signed);
            case COMP3:
                return PackedDecimal.encode(new BigDecimal(value), len, scale, signed);
            case DISPLAY:
            default:
                if (isNumeric(f)) {
                    return ZonedDecimal.encode(new BigDecimal(value), len, scale, signed,
                            f.signLeading(), f.signSeparate());
                }
                return ebcdic.encode(value, len);
        }
    }

    // ----------------------------------------------------------------- helpers

    private static boolean isNumeric(Field f) {
        Usage u = f.effectiveUsage();
        if (u == Usage.COMP || u == Usage.COMP3 || u == Usage.COMP5) return true;
        return f.picture() != null && f.picture().category() == Picture.Category.NUMERIC;
    }

    private static int scaleOf(Field f) {
        return f.picture() == null ? 0 : f.picture().scale();
    }

    private static String join(String parent, String name) {
        return parent == null || parent.isEmpty() ? name : parent + "." + name;
    }

    private static String hex(byte[] data, int off, int len) {
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = 0; i < len; i++) sb.append(String.format("%02X", data[off + i] & 0xFF));
        return sb.toString();
    }
}
