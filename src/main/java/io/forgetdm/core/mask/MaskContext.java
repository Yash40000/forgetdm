package io.forgetdm.core.mask;

import java.util.HashMap;
import java.util.Map;

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
