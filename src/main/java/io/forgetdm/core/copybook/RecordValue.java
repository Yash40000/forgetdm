package io.forgetdm.core.copybook;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The decoded view of one physical record: an ordered list of elementary field values, each
 * with its resolved byte position. Paths are fully qualified and OCCURS-subscripted, e.g.
 * {@code ADDRESSES(2).ZIP}.
 */
public final class RecordValue {

    /**
     * One decoded elementary field.
     * {@code value} is the logical text (numbers as plain decimal strings; text as decoded EBCDIC;
     * RAW float fields as {@code 0x..} hex). {@code numeric} marks fields the masking layer may
     * treat as numbers.
     */
    public record DecodedField(String path, Field field, int offset, int length,
                               String value, boolean numeric) {}

    private final List<DecodedField> fields = new ArrayList<>();
    private final Map<String, DecodedField> byPath = new LinkedHashMap<>();
    private final byte[] raw;

    RecordValue(byte[] raw) { this.raw = raw; }

    void add(DecodedField f) {
        fields.add(f);
        byPath.put(f.path(), f);
    }

    public List<DecodedField> fields() { return fields; }
    public DecodedField get(String path) { return byPath.get(path); }
    public boolean has(String path) { return byPath.containsKey(path); }
    public byte[] raw() { return raw; }

    /** Convenience: path → value map in declaration order. */
    public Map<String, String> asMap() {
        Map<String, String> m = new LinkedHashMap<>();
        for (DecodedField f : fields) m.put(f.path(), f.value());
        return m;
    }
}
