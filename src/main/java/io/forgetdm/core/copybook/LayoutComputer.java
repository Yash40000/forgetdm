package io.forgetdm.core.copybook;

import io.forgetdm.core.copybook.codec.BinaryInt;
import io.forgetdm.core.copybook.codec.PackedDecimal;

import java.util.Locale;
import java.util.Map;

/**
 * Assigns byte offsets and (single-occurrence) lengths to every field in a record tree.
 *
 *  - groups: length is the sum of their children, REDEFINES overlaid at the redefined offset
 *  - OCCURS: a field's {@link Field#length()} is ONE occurrence; total storage = length * occursMax
 *  - REDEFINES: the redefining item is placed at the redefined sibling's offset
 *  - SYNCHRONIZED is parsed but NOT applied (mainframe data files are normally unaligned)
 *  - level-66 RENAMES ranges are resolved from their referenced fields after the main pass
 */
public final class LayoutComputer {

    private LayoutComputer() {}

    public static void compute(Field record) {
        layout(record, 0);
        resolveRenames(record);
    }

    /** Returns total bytes consumed by {@code f} (single length * occursMax). */
    private static int layout(Field f, int start) {
        if (f.isElementary()) {
            f.setOffset(start);
            f.setLength(elementaryLength(f));
            return f.length() * Math.max(1, f.occursMax());
        }
        // group
        f.setOffset(start);
        int cursor = start;
        for (Field child : f.children()) {
            if (child.isRename() || child.level() == 88) continue;
            if (child.redefines() != null) {
                Field target = sibling(f, child.redefines());
                if (target == null)
                    throw new IllegalArgumentException("REDEFINES target '" + child.redefines()
                            + "' not found as a sibling of '" + child.name() + "'");
                int used = layout(child, target.offset());
                cursor = Math.max(cursor, target.offset() + used);
            } else {
                int used = layout(child, cursor);
                cursor += used;
            }
        }
        f.setLength(cursor - start);
        return f.length() * Math.max(1, f.occursMax());
    }

    private static int elementaryLength(Field f) {
        Usage u = f.effectiveUsage();
        if (u.isRaw()) return u == Usage.COMP1 ? 4 : 8;

        Picture pic = f.picture();
        if (pic == null)
            throw new IllegalArgumentException("Elementary item '" + f.name() + "' has no PICTURE and is not COMP-1/COMP-2");

        switch (u) {
            case COMP:
            case COMP5:
                return BinaryInt.byteLength(pic.digits());
            case COMP3:
                return PackedDecimal.byteLength(pic.digits());
            case DISPLAY:
            default:
                if (pic.category() == Picture.Category.NUMERIC) {
                    return pic.displayLength() + (f.signSeparate() ? 1 : 0);
                }
                return pic.displayLength();
        }
    }

    private static Field sibling(Field parent, String name) {
        for (Field c : parent.children()) {
            if (!c.isFiller() && c.name().equalsIgnoreCase(name)) return c;
        }
        return null;
    }

    /** Resolve level-66 RENAMES ranges to concrete offset/length using the referenced fields. */
    private static void resolveRenames(Field record) {
        Map<String, Field> idx = Copybook.nameIndex(record);
        for (Field c : record.children()) {
            if (!c.isRename()) continue;
            Field from = idx.get(c.renamesFrom().toUpperCase(Locale.ROOT));
            if (from == null) throw new IllegalArgumentException("RENAMES source '" + c.renamesFrom() + "' not found");
            int start = from.offset();
            int end;
            if (c.renamesThru() != null) {
                Field thru = idx.get(c.renamesThru().toUpperCase(Locale.ROOT));
                if (thru == null) throw new IllegalArgumentException("RENAMES THRU target '" + c.renamesThru() + "' not found");
                end = thru.offset() + thru.length() * Math.max(1, thru.occursMax());
            } else {
                end = from.offset() + from.length() * Math.max(1, from.occursMax());
            }
            c.setOffset(start);
            c.setLength(Math.max(0, end - start));
        }
    }
}
