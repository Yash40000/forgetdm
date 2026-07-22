package io.forgetdm.core.mask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Row-level context: lets composite functions (EMAIL, FULL_NAME) stay consistent with sibling columns. */
public class MaskContext {
    public final long rowIndex;
    public final Map<String, String> row = new HashMap<>();      // original values by lower-cased column
    public final Map<String, String> masked = new HashMap<>();   // already-masked values this row
    private Integer sharedDateShiftMinDays;
    private Integer sharedDateShiftMaxDays;
    private Long sharedDateShiftDays;

    public MaskContext(long rowIndex) { this.rowIndex = rowIndex; }

    public String original(String col) { return col == null ? null : row.get(col.toLowerCase()); }
    public String maskedOf(String col) { return col == null ? null : masked.get(col.toLowerCase()); }

    public String maskedFirstName() {
        return maskedNamePart(List.of("first_name", "firstname", "fname", "given_name", "givenname"));
    }

    public String maskedLastName() {
        return maskedNamePart(List.of("last_name", "lastname", "lname", "surname", "family_name", "familyname"));
    }

    private String maskedNamePart(List<String> aliases) {
        for (String alias : aliases) {
            String value = masked.get(alias);
            if (value != null && !value.isBlank()) return value;
        }
        // Target columns often carry a business prefix (customer_first_name, party_surname).
        // Sorting removes HashMap iteration order from the selection when more than one alias exists.
        for (Map.Entry<String, String> entry : new TreeMap<>(masked).entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (value == null || value.isBlank()) continue;
            for (String alias : aliases) {
                if (key.endsWith("_" + alias)) return value;
            }
        }
        return null;
    }

    public void useSharedDateShiftRange(int minDays, int maxDays) {
        this.sharedDateShiftMinDays = minDays;
        this.sharedDateShiftMaxDays = maxDays;
        this.sharedDateShiftDays = null;
    }

    public boolean hasSharedDateShiftRange() {
        return sharedDateShiftMinDays != null && sharedDateShiftMaxDays != null;
    }

    public int sharedDateShiftMinDays() { return sharedDateShiftMinDays; }
    public int sharedDateShiftMaxDays() { return sharedDateShiftMaxDays; }
    public Long sharedDateShiftDays() { return sharedDateShiftDays; }
    public void sharedDateShiftDays(long days) { this.sharedDateShiftDays = days; }
}
