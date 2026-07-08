package io.forgetdm.core.mask;

/**
 * ForgeTDM masking function catalog — synthesis of format-preserving masking, referential-order load,
 * convenience routines, and entity-consistent masking substitution. All functions are deterministic
 * by default (same input + same project secret => same output) which guarantees referential integrity
 * across tables, databases, and runs WITHOUT any shared cross-reference state.
 */
public enum MaskFunction {
    FIRST_NAME,        // seedlist substitution (locale pack)
    LAST_NAME,
    FULL_NAME,         // composed masked first + last
    EMAIL,             // rebuilt from masked name parts @ safe test domain (never deliverable)
    PHONE,             // format preserving digits, keeps country/area shape
    SSN,               // keeps area (first 3), regenerates rest; avoids invalid 000/666 groups
    CREDIT_CARD,       // preserves BIN(6) + length, regenerates middle, repairs Luhn check digit
    DATE_SHIFT,        // +- N days deterministic shift (param1 = max days, default 365); preserves format
    DOB_AGE_BAND,      // random date keeping the same age band (param1 = band years, default 5)
    ADDRESS_STREET,    // deterministic house number + street seedlist
    ADDRESS_US,        // coherent US address, optional state preservation
    CITY_STATE_ZIP,    // coherent triplet from cities_us.csv (semantic integrity)
    COMPANY,
    FORMAT_PRESERVE,   // digit->digit, letter->letter (case kept), punctuation untouched (FPE-style)
    REDACT_KEEP_LAST4, // ****1234
    HASH_LOV,          // generic deterministic seedlist pick (param1 = seedlist name)
    FIXED,             // constant (param1)
    NULLIFY,
    SEQUENCE,          // PREFIX + rowIndex (param1 = prefix)
    PASSTHROUGH,
    BY_INDICATOR,      // polymorphic column: dispatch per row on another column's value.
                       // param1 = indicator column, param2 = map "P=PHONE|E=EMAIL|*=FORMAT_PRESERVE"
    PARTIAL_MASK,      // mask only the parts matching a regex, keep the rest verbatim ("yash1234" -> "kim1234").
                       // param1 = regex of what to mask (default [A-Za-z]+), param2 = function per match (default FIRST_NAME)

    // ---- split fields: one logical value spread across several physical columns. Each column's rule
    //      composes the full value from its siblings, masks it ONCE (canonical salt), and emits only its
    //      own slice — so all parts of a row stay mutually coherent and match a combined column elsewhere.
    PHONE_SPLIT,       // param1 = THIS column's name, param2 = ordered sibling columns, e.g. "area_code,exchange,line_no"
    SSN_SPLIT,         // param1 = THIS column's name, param2 = ordered sibling columns, e.g. "ssn_area,ssn_group,ssn_serial"
    DATE_SPLIT,        // param1 = THIS column's name, param2 = role map "dd=dob_day,mm=dob_month,yyyy=dob_year" (age-band preserved)

    AGE,               // IBM-Optim-style date aging: shift every date by a FIXED amount so relative gaps are
                       // preserved (unlike DATE_SHIFT's per-value pseudo-random shift). param1 = shift spec
                       // "+1y -2m +3w +10d" (no sign = plus); param2 = date format (blank = auto-detect)

    SCRIPT             // user-defined Lua (Optim-style exit) for anything not covered out of the box.
                       // param1 = script name from the Masking Scripts registry, param2 = extra arg exposed
                       // to the script as "param". Sandboxed (no os/io/files); deterministic helpers via forge.*
}
