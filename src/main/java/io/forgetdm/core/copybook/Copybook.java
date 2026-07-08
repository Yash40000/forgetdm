package io.forgetdm.core.copybook;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A parsed copybook: one or more top-level records (usually a single level-01), each with its
 * field tree and computed byte layout. Level-66 RENAMES entries are attached to their record.
 */
public final class Copybook {

    private final List<Field> records;

    Copybook(List<Field> records) {
        this.records = records;
    }

    public List<Field> records() { return records; }

    /** The first (typically only) record description. */
    public Field primaryRecord() {
        if (records.isEmpty()) throw new IllegalArgumentException("Copybook has no record description");
        return records.get(0);
    }

    public Field record(String name) {
        for (Field r : records) if (r.name().equalsIgnoreCase(name)) return r;
        throw new IllegalArgumentException("No record named '" + name + "' in copybook");
    }

    /** Total fixed record length of a record (uses OCCURS max for any ODO tables). */
    public int recordLength(Field record) {
        return record.length() < 0 ? 0 : record.length();
    }

    /** Flatten a record's elementary fields (groups excluded, OCCURS expanded with subscripts). */
    public List<FlatField> flatten(Field record) {
        List<FlatField> out = new ArrayList<>();
        flatten(record, "", record.offset(), new int[0], out);
        return out;
    }

    private void flatten(Field f, String path, int baseOffset, int[] indices, List<FlatField> out) {
        if (f.isRename()) return;
        String name = path.isEmpty() ? f.name() : path + "." + f.name();
        int occ = Math.max(1, f.occursMax());
        for (int k = 0; k < occ; k++) {
            int offsetK = baseOffset + k * f.length();
            int[] idxK = f.occurs() ? append(indices, k + 1) : indices;
            String labelK = f.occurs() ? name + "(" + (k + 1) + ")" : name;
            if (f.isElementary()) {
                out.add(new FlatField(labelK, f, offsetK, f.length(), idxK));
            } else {
                int childBase = offsetK;
                for (Field c : f.children()) {
                    if (c.redefines() != null) {
                        // redefiners overlay; expose them too, anchored at the target's offset
                        flatten(c, labelK, childBase, idxK, out);
                    } else {
                        flatten(c, labelK, childBase, idxK, out);
                        childBase += c.length() * Math.max(1, c.occursMax());
                    }
                }
            }
        }
    }

    private static int[] append(int[] a, int v) {
        int[] b = new int[a.length + 1];
        System.arraycopy(a, 0, b, 0, a.length);
        b[a.length] = v;
        return b;
    }

    /** Build a name → field index for a record (first definition wins). */
    public static Map<String, Field> nameIndex(Field record) {
        Map<String, Field> idx = new LinkedHashMap<>();
        indexInto(record, idx);
        return idx;
    }

    private static void indexInto(Field f, Map<String, Field> idx) {
        if (!f.isFiller()) idx.putIfAbsent(f.name().toUpperCase(java.util.Locale.ROOT), f);
        for (Field c : f.children()) indexInto(c, idx);
    }

    /** An elementary field resolved to a concrete byte position (one OCCURS occurrence). */
    public record FlatField(String path, Field field, int offset, int length, int[] indices) {}
}
