package io.forgetdm.core.mask;

import io.forgetdm.core.util.Determinism;
import io.forgetdm.core.util.Luhn;
import io.forgetdm.core.util.SeedLists;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The ForgeTDM masking engine. Pure Java, stateless, thread-safe, horizontally scalable.
 *
 * Guarantees:
 *  1. Deterministic & repeatable   (HMAC keyed on project secret + column salt)
 *  2. Irreversible                  (one-way HMAC; no decrypt path exists)
 *  3. Format-preserving             (16-digit cards stay 16 digits, dates stay dates)
 *  4. Referentially intact          (same source value -> same masked value everywhere)
 *  5. Semantically coherent         (city/state/zip move together; email matches masked name)
 *  6. Never emits deliverable email domains or real-looking SSN groups where avoidable
 */
public class MaskingEngine {

    private static final Set<String> US_STATES = Set.of(
            "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "DC", "FL", "GA", "HI", "ID", "IL", "IN",
            "IA", "KS", "KY", "LA", "ME", "MD", "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH",
            "NJ", "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC", "SD", "TN", "TX", "UT",
            "VT", "VA", "WA", "WV", "WI", "WY");

    private final String secret;

    public MaskingEngine(String secret) {
        if (secret == null || secret.isBlank()) throw new IllegalArgumentException("masking secret required");
        this.secret = secret;
    }

    /**
     * The effective HMAC key for this engine, exposed ONLY so the database-side pushdown path can install a
     * function that produces byte-identical output. Treat as a secret; it is never logged.
     */
    public String pushdownKey() { return secret; }

    /**
     * Seeded variant: derives a new deterministic key from (project secret, user seed).
     * Same seed => identical results across runs and tables (referential integrity preserved);
     * a different seed => a completely different — but still deterministic — masked universe.
     * Blank/null seed returns this engine unchanged (default behaviour).
     */
    public MaskingEngine withSeed(String seed) {
        if (seed == null || seed.isBlank()) return this;
        return new MaskingEngine(secret + "::seed::" + seed.trim());
    }

    /** Mask a single value. salt should be stable per logical attribute (e.g. "person.ssn" or just "ssn"). */
    public String mask(MaskFunction fn, String salt, String value, String param1, String param2, MaskContext ctx) {
        if (fn == MaskFunction.PASSTHROUGH) return value;
        if (fn == MaskFunction.NULLIFY) return null;
        if (fn == MaskFunction.FIXED) return applyCase(param1, caseMode(param1, param2));
        if (fn == MaskFunction.SEQUENCE) return (param1 == null ? "ID-" : param1) + (ctx == null ? 0 : ctx.rowIndex);
        if (value == null || value.isEmpty()) return value;

        switch (fn) {
            case FIRST_NAME: return applyCase(pick("first_names.txt", salt, value), caseMode(param1, param2));
            case LAST_NAME:  return applyCase(pick("last_names.txt", salt, value), caseMode(param1, param2));
            case COMPANY:    return applyCase(pick("companies.txt", salt, value), caseMode(param1, param2));
            case FULL_NAME:  return fullName(salt, value, param1, param2);
            case EMAIL:      return email(salt, value, param1, param2, ctx);
            case PHONE:      return phone(salt, value, param1, param2);
            case SSN:        return ssn(salt, value, param1, param2);
            case CREDIT_CARD:return creditCard(salt, value, param1, param2);
            case DATE_SHIFT: return dateShift(salt, value, parseIntOr(param1, 365), param2);
            case DOB_AGE_BAND: return dobAgeBand(salt, value, parseIntOr(param1, 5), param2);
            case ADDRESS_STREET: return applyCase(street(salt, value), caseMode(param1, param2));
            case ADDRESS_US: return usAddress(salt, value, param1, param2, ctx);
            case CITY_STATE_ZIP: return cityStateZip(salt, value, param1, param2, ctx);
            case FORMAT_PRESERVE: return applyCase(formatPreserve(salt, value, false), caseMode(param1, param2));
            case REDACT_KEEP_LAST4: return redactKeepLast4(value);
            case HASH_LOV: return applyCase(pick(param1 == null ? "first_names.txt" : param1, salt, value), caseMode(null, param2));
            case BY_INDICATOR: return byIndicator(salt, value, param1, param2, ctx);
            case PARTIAL_MASK: return partialMask(salt, value, param1, param2, ctx);
            case PHONE_SPLIT: return splitDigits(value, param1, param2, ctx, "phone", false);
            case SSN_SPLIT:   return splitDigits(value, param1, param2, ctx, "ssn", true);
            case DATE_SPLIT:  return splitDate(value, param1, param2, ctx);
            case AGE:         return age(value, param1, param2);
            case SCRIPT:      return script(salt, value, param1, param2, ctx);
            default: return value;
        }
    }

    // ---------- field implementations ----------

    /**
     * Polymorphic column: the value's semantic type depends on ANOTHER column in the same row
     * (e.g. ref_num holds a phone when type_ind='P', an email when 'E'). param1 = indicator column,
     * param2 = "P=PHONE|E=EMAIL|*=FORMAT_PRESERVE" ('*' = fallback; no match and no '*' = passthrough).
     * Each branch masks with its canonical salt, so a phone masked here equals the same phone masked
     * by a plain PHONE rule elsewhere — referential consistency is preserved across the branches.
     */
    private String byIndicator(String salt, String value, String indicatorCol, String mapping, MaskContext ctx) {
        if (indicatorCol == null || indicatorCol.isBlank() || mapping == null || mapping.isBlank() || ctx == null)
            return value;
        String indicator = ctx.original(indicatorCol.trim());
        String norm = indicator == null ? "" : indicator.trim();
        String chosen = null, fallback = null;
        for (String part : mapping.split("\\|")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String key = part.substring(0, eq).trim();
            String fnName = part.substring(eq + 1).trim();
            if (key.equals("*")) { fallback = fnName; continue; }
            if (key.equalsIgnoreCase(norm)) { chosen = fnName; break; }
        }
        String fnName = chosen != null ? chosen : fallback;
        if (fnName == null || fnName.isEmpty()) return value;
        MaskFunction fn;
        try { fn = MaskFunction.valueOf(fnName.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return value; }
        if (fn == MaskFunction.BY_INDICATOR) return value;   // no recursion
        return mask(fn, branchSalt(fn, salt), value, null, null, ctx);
    }

    /**
     * Partial masking: mask ONLY the substrings matching a regex, keep everything else verbatim —
     * "yash1234" with defaults becomes "kim1234" (letter runs masked as names, digits untouched).
     * param1 = regex of what to mask (default [A-Za-z]+); param2 = function applied to each match
     * (default FIRST_NAME). Each segment masks with the branch function's canonical salt, so "yash"
     * here maps to the same masked name as a plain FIRST_NAME rule would produce — consistent joins.
     * The original segment's letter case is preserved (yash → kim, YASH → KIM, Yash → Kim).
     */
    private String partialMask(String salt, String value, String pattern, String fnName, MaskContext ctx) {
        Pattern p;
        try { p = Pattern.compile(pattern == null || pattern.isBlank() ? "[A-Za-z]+" : pattern); }
        catch (Exception e) { return value; }   // bad regex — fail safe to passthrough
        MaskFunction fn;
        try { fn = MaskFunction.valueOf((fnName == null || fnName.isBlank() ? "FIRST_NAME" : fnName.trim().toUpperCase(Locale.ROOT))); }
        catch (IllegalArgumentException e) { return value; }
        if (fn == MaskFunction.PARTIAL_MASK || fn == MaskFunction.BY_INDICATOR) return value;   // no nesting

        Matcher m = p.matcher(value);
        StringBuilder out = new StringBuilder(value.length());
        int last = 0;
        boolean any = false;
        while (m.find()) {
            if (m.end() == m.start()) { if (!m.find(m.end() + 1)) break; }   // guard zero-width matches
            any = true;
            out.append(value, last, m.start());
            String segment = m.group();
            String masked = mask(fn, branchSalt(fn, salt), segment, null, null, ctx);
            out.append(matchSegmentCase(segment, masked == null ? "" : masked));
            last = m.end();
        }
        if (!any) return value;
        out.append(value.substring(last));
        return out.toString();
    }

    /** Carry the original segment's case pattern onto the replacement (yash→kim, YASH→KIM, Yash→Kim).
     *  A proper-case original title-cases the replacement so a substitution value with its own internal capitals
     *  (e.g. a catalog name like "DeVvani" or "McBrook") still comes out single-leading-cap. */
    private static String matchSegmentCase(String original, String replacement) {
        if (original.isEmpty() || replacement.isEmpty()) return replacement;
        boolean hasLetter = original.chars().anyMatch(Character::isLetter);
        if (!hasLetter) return replacement;
        boolean allUpper = original.equals(original.toUpperCase(Locale.ROOT)) && !original.equals(original.toLowerCase(Locale.ROOT));
        boolean allLower = original.equals(original.toLowerCase(Locale.ROOT)) && !original.equals(original.toUpperCase(Locale.ROOT));
        if (allUpper) return replacement.toUpperCase(Locale.ROOT);
        if (allLower) return replacement.toLowerCase(Locale.ROOT);
        if (isProperCase(original)) return titleCase(replacement);   // Yash → Devvani (single leading cap)
        return replacement;   // truly mixed (yAsH): keep the function's natural casing
    }

    /** First letter upper, all remaining letters lower. */
    private static boolean isProperCase(String s) {
        if (s.isEmpty() || !Character.isUpperCase(s.charAt(0))) return false;
        String rest = s.substring(1);
        return rest.equals(rest.toLowerCase(Locale.ROOT));
    }

    private static String titleCase(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    /**
     * Split digit fields (phone across area/exchange/line columns, SSN across area/group/serial):
     * compose the digits of all sibling columns in order, mask the COMPOSED value once with the
     * canonical salt (so every sibling in the row derives the identical masked whole, and a combined
     * column holding the same digits masks to the same result), then emit only this column's slice,
     * re-inserted into this column's own formatting. selfCol names which listed column THIS rule is on.
     */
    private String splitDigits(String value, String selfCol, String colsCsv, MaskContext ctx,
                               String canonicalSalt, boolean ssnStyle) {
        if (selfCol == null || selfCol.isBlank() || colsCsv == null || colsCsv.isBlank() || ctx == null) return value;
        String self = selfCol.trim();
        StringBuilder composed = new StringBuilder();
        int selfStart = -1, selfLen = 0;
        for (String raw : colsCsv.split(",")) {
            String col = raw.trim();
            if (col.isEmpty()) continue;
            String v = col.equalsIgnoreCase(self) ? value : ctx.original(col);
            String digits = v == null ? "" : v.replaceAll("\\D", "");
            if (col.equalsIgnoreCase(self)) { selfStart = composed.length(); selfLen = digits.length(); }
            composed.append(digits);
        }
        if (selfStart < 0 || selfLen == 0) return value;   // this column not in the list, or holds no digits
        String maskedFull = ssnStyle
                ? ssn(canonicalSalt, composed.toString(), null, "DIGITS_ONLY")
                : phone(canonicalSalt, composed.toString(), null, null);
        String maskedDigits = maskedFull == null ? "" : maskedFull.replaceAll("\\D", "");
        if (maskedDigits.length() != composed.length())    // defensive: composed value didn't mask 1:1
            return formatPreserve(canonicalSalt + "|split", value, false);
        return reinsertDigits(value, maskedDigits.substring(selfStart, selfStart + selfLen));
    }

    /**
     * Split date (DOB as dd / mm / yyyy columns): compose the date from the role-mapped siblings,
     * mask it ONCE with DOB age-band semantics on the canonical "dob" salt (same output as a combined
     * ISO yyyy-MM-dd column masked with DOB_AGE_BAND), then emit only this column's part, preserving
     * zero-padding. spec = "dd=day_col,mm=month_col,yyyy=year_col"; selfCol = THIS column's name.
     */
    private String splitDate(String value, String selfCol, String spec, MaskContext ctx) {
        if (selfCol == null || selfCol.isBlank() || spec == null || spec.isBlank() || ctx == null) return value;
        String self = selfCol.trim();
        String dayCol = null, monthCol = null, yearCol = null;
        for (String part : spec.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String role = part.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String col = part.substring(eq + 1).trim();
            switch (role) {
                case "dd" -> dayCol = col;
                case "mm" -> monthCol = col;
                case "yyyy", "yy" -> yearCol = col;
            }
        }
        if (dayCol == null || monthCol == null || yearCol == null) return value;
        String dayV = dayCol.equalsIgnoreCase(self) ? value : ctx.original(dayCol);
        String monV = monthCol.equalsIgnoreCase(self) ? value : ctx.original(monthCol);
        String yearV = yearCol.equalsIgnoreCase(self) ? value : ctx.original(yearCol);
        LocalDate original;
        try {
            original = LocalDate.of(Integer.parseInt(yearV.trim()), Integer.parseInt(monV.trim()), Integer.parseInt(dayV.trim()));
        } catch (Exception e) {
            return value;   // incomplete/invalid parts — leave untouched rather than corrupt
        }
        String masked = dobAgeBand("dob", original.format(DateTimeFormatter.ISO_LOCAL_DATE), 5, "yyyy-MM-dd");
        LocalDate m;
        try { m = LocalDate.parse(masked); } catch (Exception e) { return value; }
        if (dayCol.equalsIgnoreCase(self))   return padLike(value, m.getDayOfMonth());
        if (monthCol.equalsIgnoreCase(self)) return padLike(value, m.getMonthValue());
        if (yearCol.equalsIgnoreCase(self))  return String.valueOf(m.getYear());
        return value;   // this column isn't one of the mapped roles
    }

    /** Keep the original's zero-padding width ("05" stays two chars). */
    private static String padLike(String original, int part) {
        int width = original == null ? 0 : original.trim().length();
        return width >= 2 ? String.format("%0" + width + "d", part) : String.valueOf(part);
    }

    /** Semantic functions keep their canonical identity salts so branch output matches plain rules. */
    private static String branchSalt(MaskFunction fn, String fallback) {
        return switch (fn) {
            case FIRST_NAME -> "name.first";
            case LAST_NAME -> "name.last";
            case FULL_NAME -> "name.full";
            case EMAIL -> "email";
            case SSN -> "ssn";
            case CREDIT_CARD -> "ccn";
            case PHONE -> "phone";
            case CITY_STATE_ZIP -> "geo";
            case ADDRESS_STREET -> "addr";
            case ADDRESS_US -> "addr.us";
            case COMPANY -> "company";
            case DOB_AGE_BAND -> "dob";
            default -> fallback;
        };
    }

    private String pick(String seedlist, String salt, String value) {
        List<String> list = substitutionList(seedlist);
        return list.get(Determinism.pick(secret, salt + "|" + seedlist, normalize(value), list.size()));
    }

    /**
     * Substitution dictionary for a seedlist. Name masks draw from the large expanded catalog (~4,000 first /
     * ~10,000 last names — the same source the synthetic generator uses), giving K2View/Delphix-scale variety so
     * masked names collide far less. All other seedlists (companies, domains, custom lists) are unchanged.
     *
     * The hash key still includes the original seedlist id ("first_names.txt"/"last_names.txt"), so canonical
     * salts, split-field consistency and FK-consistent key masking are all preserved — only the pool (and thus the
     * modulus) grows. Note: this changes the specific masked name/email a given input maps to (a bigger dictionary),
     * so re-masking existing data will produce new — still deterministic — values.
     */
    private List<String> substitutionList(String seedlist) {
        if ("first_names.txt".equals(seedlist)) return io.forgetdm.core.synth.SyntheticNameCatalog.firstNames("GLOBAL", "ANY");
        if ("last_names.txt".equals(seedlist))  return io.forgetdm.core.synth.SyntheticNameCatalog.lastNames("GLOBAL");
        return SeedLists.get(seedlist);
    }

    private String fullName(String salt, String value, String format, String outputCase) {
        String firstRaw = firstToken(value);
        String middleRaw = middleToken(value);
        String lastRaw = lastToken(value);
        String first = pick("first_names.txt", "name.first", firstRaw);
        String last  = pick("last_names.txt",  "name.last",  lastRaw);
        String middleSeed = middleRaw.isBlank() ? firstRaw + "|" + lastRaw : middleRaw;
        String middle = pick("first_names.txt", "name.middle", middleSeed);
        String out = formatName(format, first, middleRaw.isBlank() ? "" : middle, last);
        return applyCase(out, caseMode(null, outputCase));
    }

    /**
     * Email modes:
     * NAME_SAFE (default), USER_SAFE, HASH_LOCAL, REDACT_LOCAL, PRESERVE_DOMAIN.
     * param2 controls domain handling: SAFE_DOMAIN (default) or PRESERVE_DOMAIN.
     * For backward compatibility, a param1 ending in .txt is treated as the safe domain seedlist.
     */
    private String email(String salt, String value, String modeOrDomainList, String domainMode, MaskContext ctx) {
        String mode = emailMode(modeOrDomainList);
        String domainList = isEmailMode(modeOrDomainList) ? null : modeOrDomainList;
        boolean preserveDomain = "PRESERVE_DOMAIN".equalsIgnoreCase(domainMode) || "PRESERVE_DOMAIN".equals(mode);
        String originalDomain = value.contains("@") ? value.substring(value.indexOf('@') + 1) : null;
        String domain = preserveDomain && originalDomain != null && !originalDomain.isBlank()
                ? originalDomain
                : safeEmailDomain(salt, value, domainList);
        if ("REDACT_LOCAL".equals(mode)) return "masked@" + domain;
        if ("HASH_LOCAL".equals(mode)) {
            long h = Determinism.hashLong(secret, salt + "|email.hash", normalize(value)) % 1_000_000;
            return String.format("user%06d@%s", h, domain);
        }

        String local;
        String f = ctx == null ? null : ctx.maskedOf("first_name");
        String l = ctx == null ? null : ctx.maskedOf("last_name");
        if ("NAME_SAFE".equals(mode) && f != null && l != null) {
            local = (f + "." + l).toLowerCase();
        } else if ("USER_SAFE".equals(mode)) {
            String lp = value.contains("@") ? value.substring(0, value.indexOf('@')) : value;
            local = pick("first_names.txt", "email.user", lp).toLowerCase();
        } else {
            String lp = value.contains("@") ? value.substring(0, value.indexOf('@')) : value;
            String mf = pick("first_names.txt", "name.first", lp);
            String ml = pick("last_names.txt",  "name.last",  lp);
            local = (mf + "." + ml).toLowerCase();
        }
        // collision-resistant uniqueness suffix derived from the original value
        long suffix = Determinism.hashLong(secret, salt + "|email.sfx", normalize(value)) % 10000;
        return local + suffix + "@" + domain;
    }

    private String safeEmailDomain(String salt, String value, String domainList) {
        List<String> domains = SeedLists.get(domainList == null || domainList.isBlank() ? "email_domains.txt" : domainList);
        return domains.get(Determinism.pick(secret, salt + "|email.dom", normalize(value), domains.size()));
    }

    private static boolean isEmailMode(String mode) {
        if (mode == null || mode.isBlank()) return false;
        return switch (mode.trim().toUpperCase(Locale.ROOT)) {
            case "NAME_SAFE", "USER_SAFE", "HASH_LOCAL", "REDACT_LOCAL", "PRESERVE_DOMAIN" -> true;
            default -> false;
        };
    }

    private static String emailMode(String mode) {
        if (mode == null || mode.isBlank() || !isEmailMode(mode)) return "NAME_SAFE";
        String m = mode.trim().toUpperCase(Locale.ROOT);
        return "PRESERVE_DOMAIN".equals(m) ? "NAME_SAFE" : m;
    }

    private String phone(String salt, String value, String mode, String handling) {
        String m = mode == null || mode.isBlank() ? "FORMAT_PRESERVE" : mode.trim().toUpperCase(Locale.ROOT);
        if ("REDACT".equals(m)) return value.replaceAll("\\d", "*");
        if ("KEEP_LAST4".equals(m)) return maskDigitsExceptLast(value, 4, "X");
        if ("DIGITS_ONLY".equals(m)) return formatPreserve(salt, value.replaceAll("\\D", ""), false);
        if ("PRESERVE_AREA".equals(m)) return preservePhoneArea(salt, value);
        boolean preserveCountry = handling == null || handling.isBlank() || handling.equalsIgnoreCase("PRESERVE_COUNTRY");
        return formatPreserve(salt, value, preserveCountry);
    }

    private String preservePhoneArea(String salt, String value) {
        String digits = value.replaceAll("\\D", "");
        int keepDigits = value.startsWith("+") ? Math.min(4, digits.length()) : Math.min(3, digits.length());
        Random r = Determinism.rng(secret, salt + "|phone.area", digits);
        StringBuilder replacement = new StringBuilder(digits.substring(0, keepDigits));
        for (int i = keepDigits; i < digits.length(); i++) replacement.append((char) ('0' + r.nextInt(10)));
        return reinsertDigits(value, replacement.toString());
    }

    /** SSN: default keeps area number, regenerates group+serial, valid-by-rule. */
    private String ssn(String salt, String value, String mode, String format) {
        String digits = value.replaceAll("\\D", "");
        boolean dashed = value.contains("-");
        if (digits.length() != 9) return formatPreserve(salt, value, false);
        String m = mode == null || mode.isBlank() ? "VALID_PRESERVE_AREA" : mode.trim().toUpperCase(Locale.ROOT);
        if ("KEEP_LAST4".equals(m)) return formatSsn("*****" + digits.substring(5), format, dashed).replaceFirst("^(\\*{3})(\\*{2})", "$1-$2");
        if ("REDACT".equals(m)) return dashed || "DASHED".equalsIgnoreCase(format) ? "***-**-****" : "*********";
        if ("FORMAT_PRESERVE".equals(m)) return formatPreserve(salt, value, false);
        String area = digits.substring(0, 3);
        Random r = Determinism.rng(secret, salt + "|ssn", digits);
        if ("VALID_RANDOM_AREA".equals(m)) area = validSsnArea(r);
        else if (area.equals("000") || area.equals("666") || area.startsWith("9")) area = "899"; // never emit invalid area as-is
        int group = 1 + r.nextInt(99);          // 01-99, never 00
        int serial = 1 + r.nextInt(9999);       // 0001-9999, never 0000
        String out = String.format("%s%02d%04d", area, group, serial);
        return formatSsn(out, format, dashed);
    }

    private String validSsnArea(Random r) {
        int area;
        do { area = 1 + r.nextInt(899); }
        while (area == 666);
        return String.format("%03d", area);
    }

    private static String formatSsn(String digits, String format, boolean originalDashed) {
        String f = format == null || format.isBlank() ? "PRESERVE_FORMAT" : format.trim().toUpperCase(Locale.ROOT);
        if ("DIGITS_ONLY".equals(f)) return digits.replace("-", "");
        if ("DASHED".equals(f) || originalDashed || "PRESERVE_FORMAT".equals(f))
            return digits.substring(0, 3) + "-" + digits.substring(3, 5) + "-" + digits.substring(5);
        return digits;
    }

    /** Card: default preserves BIN(6) + length + separators, regenerates middle, repairs Luhn digit. */
    private String creditCard(String salt, String value, String mode, String format) {
        String digits = value.replaceAll("\\D", "");
        if (digits.length() < 12 || digits.length() > 19 || !Luhn.isValid(digits)) return value;
        String m = mode == null || mode.isBlank() ? "VALID_PRESERVE_BIN" : mode.trim().toUpperCase(Locale.ROOT);
        Random r = Determinism.rng(secret, salt + "|ccn", digits);
        String full;
        if ("VALID_KEEP_LAST4".equals(m) || "KEEP_LAST4".equals(m)) {
            full = validCardPreserveLast4(r, digits);
        } else if ("VALID_RANDOM_BIN".equals(m) || "REDACT".equals(m)) {
            full = validCardWithPrefix(r, randomCardBin(r, digits), digits.length());
        } else if ("FORMAT_PRESERVE".equals(m)) {
            full = validCardWithPrefix(r, String.valueOf(digits.charAt(0)), digits.length());
        } else {
            full = validCardWithPrefix(r, digits.substring(0, 6), digits.length());
        }
        return formatCard(full, value, format);
    }

    private String validCardWithPrefix(Random r, String prefix, int length) {
        StringBuilder body = new StringBuilder(prefix);
        while (body.length() < length - 1) body.append((char) ('0' + r.nextInt(10)));
        return body.toString() + Luhn.checkDigit(body.toString());
    }

    private String validCardPreserveLast4(Random r, String digits) {
        String preservedBodyTail = digits.substring(digits.length() - 4, digits.length() - 1);
        char preservedCheck = digits.charAt(digits.length() - 1);
        int prefixLength = digits.length() - 4;
        for (int attempt = 0; attempt < 100; attempt++) {
            StringBuilder prefix = new StringBuilder(randomCardBin(r, digits));
            while (prefix.length() < prefixLength) prefix.append((char) ('0' + r.nextInt(10)));
            if (prefix.length() > prefixLength) prefix.setLength(prefixLength);
            for (char candidate = '0'; candidate <= '9'; candidate++) {
                String body = prefix.substring(0, Math.max(0, prefix.length() - 1)) + candidate + preservedBodyTail;
                if (body.length() == digits.length() - 1 && Luhn.checkDigit(body) == preservedCheck) {
                    return body + preservedCheck;
                }
            }
        }
        return validCardWithPrefix(r, randomCardBin(r, digits), digits.length());
    }

    private String randomCardBin(Random r, String originalDigits) {
        String[] bins = originalDigits.startsWith("3")
                ? new String[]{"378282", "371449", "341111"}
                : originalDigits.startsWith("5")
                ? new String[]{"555555", "545454", "510510"}
                : new String[]{"411111", "424242", "400000"};
        return bins[r.nextInt(bins.length)];
    }

    private static String formatCard(String digits, String original, String format) {
        String f = format == null || format.isBlank() ? "PRESERVE_FORMAT" : format.trim().toUpperCase(Locale.ROOT);
        if ("DIGITS_ONLY".equals(f)) return digits;
        if ("SPACES".equals(f)) return groupDigits(digits, " ");
        if ("DASHES".equals(f)) return groupDigits(digits, "-");
        return reinsertDigits(original, digits);
    }

    /**
     * IBM-Optim-style AGE: shift every date by the SAME fixed amount, so intervals between rows and
     * columns are preserved exactly — the classic way to make a copied environment's dates current
     * ("age forward 1 year") or to de-identify DOBs while keeping cohort spacing. spec tokens:
     * "+1y -2m +3w +10d" (any subset, any order; no sign = plus). Applied in y→m→w→d order with
     * calendar clamping (Jan 31 +1m → Feb 28/29), matching Optim's month-end rules. Values that don't
     * parse as dates pass through unchanged — aging must never scramble non-date data. A timestamp's
     * time-of-day suffix is preserved verbatim.
     */
    private String age(String value, String spec, String fmt) {
        if (spec == null || spec.isBlank())
            throw new IllegalStateException("AGE needs a shift spec in param1, e.g. \"+1y\", \"-2m\", \"+1y -2m +10d\"");
        long years = 0, months = 0, weeks = 0, days = 0;
        Matcher m = Pattern.compile("([+-]?\\d+)\\s*([ymwd])", Pattern.CASE_INSENSITIVE).matcher(spec.trim());
        boolean anyToken = false;
        while (m.find()) {
            anyToken = true;
            long n = Long.parseLong(m.group(1));
            switch (Character.toLowerCase(m.group(2).charAt(0))) {
                case 'y' -> years += n;
                case 'm' -> months += n;
                case 'w' -> weeks += n;
                case 'd' -> days += n;
            }
        }
        if (!anyToken)
            throw new IllegalStateException("AGE shift spec '" + spec + "' has no y/m/w/d tokens — e.g. \"+1y -2m +10d\"");

        String trimmed = value.trim();
        DateTimeFormatter f = formatter(fmt, trimmed);
        // full value first; else the leading date part of a timestamp (suffix kept verbatim)
        String datePart = trimmed, suffix = "";
        try {
            LocalDate d = LocalDate.parse(datePart, f);
            return d.plusYears(years).plusMonths(months).plusWeeks(weeks).plusDays(days).format(f);
        } catch (DateTimeParseException first) {
            if (trimmed.length() > 10) {
                datePart = trimmed.substring(0, 10);
                suffix = trimmed.substring(10);
                try {
                    LocalDate d = LocalDate.parse(datePart, formatter(fmt, datePart));
                    return d.plusYears(years).plusMonths(months).plusWeeks(weeks).plusDays(days)
                            .format(formatter(fmt, datePart)) + suffix;
                } catch (DateTimeParseException ignored) { /* fall through */ }
            }
            return value;   // not a date — never scramble
        }
    }

    private String dateShift(String salt, String value, int maxDays, String fmt) {
        DateTimeFormatter f = formatter(fmt, value);
        try {
            LocalDate d = LocalDate.parse(value.trim(), f);
            long shift = (Determinism.hashLong(secret, salt + "|dshift", value) % (2L * maxDays + 1)) - maxDays;
            if (shift == 0) shift = maxDays / 2 + 1;
            return d.plusDays(shift).format(f);
        } catch (DateTimeParseException e) { return formatPreserve(salt, value, false); }
    }

    /** DOB: random date but same age band => eligibility logic (18+, senior) keeps behaving. */
    private String dobAgeBand(String salt, String value, int bandYears, String fmt) {
        DateTimeFormatter f = formatter(fmt, value);
        try {
            LocalDate d = LocalDate.parse(value.trim(), f);
            int year = d.getYear();
            int bandStart = year - Math.floorMod(year, Math.max(1, bandYears));
            Random r = Determinism.rng(secret, salt + "|dob", value);
            LocalDate out = LocalDate.of(bandStart + r.nextInt(Math.max(1, bandYears)), 1 + r.nextInt(12), 1 + r.nextInt(28));
            return out.format(f);
        } catch (DateTimeParseException e) { return formatPreserve(salt, value, false); }
    }

    private String street(String salt, String value) {
        Random r = Determinism.rng(secret, salt + "|street", normalize(value));
        int no = 1 + r.nextInt(9899);
        List<String> streets = SeedLists.get("streets.txt");
        return no + " " + streets.get(r.nextInt(streets.size()));
    }

    /**
     * Coherent US address. param1 selects FULL | LINE1 | LINE2 | CITY | STATE | ZIP | COUNTRY.
     * param2=PRESERVE_STATE keeps the original state when it can be read from the value or row context,
     * while obfuscating the rest of the address to a valid city/state/ZIP tuple for that state.
     */
    private String usAddress(String salt, String value, String part, String mode, MaskContext ctx) {
        String[] geo = cityTriplet(value, mode, ctx);
        String line1 = street(salt + ".line1", value);
        Random r = Determinism.rng(secret, salt + "|line2", normalize(value));
        String line2 = "Apt " + (100 + r.nextInt(899));
        String which = part == null || part.isBlank() ? "FULL" : part.trim().toUpperCase(Locale.ROOT);
        return switch (which) {
            case "LINE1", "STREET", "ADDRESS1", "ADDRESS_LINE1" -> line1;
            case "LINE2", "ADDRESS2", "ADDRESS_LINE2" -> line2;
            case "CITY" -> geo[0];
            case "STATE" -> geo[1];
            case "ZIP", "POSTAL", "POSTAL_CODE" -> geo[2];
            case "COUNTRY" -> "USA";
            default -> line1 + ", " + line2 + ", " + geo[0] + ", " + geo[1] + " " + geo[2] + ", USA";
        };
    }

    /**
     * Coherent city/state/zip (semantic integrity). param1 selects which part to emit:
     * CITY | STATE | ZIP | FULL. The *same* source key (e.g., original zip) must be used as salt input
     * across the three columns to keep them aligned — pass the shared key as the value for each column,
     * or simply mask one combined column with FULL.
     */
    private String cityStateZip(String salt, String value, String part, String mode, MaskContext ctx) {
        String[] p = cityTriplet(value, mode, ctx);
        String which = part == null ? "FULL" : part.toUpperCase();
        switch (which) {
            case "CITY": return p[0];
            case "STATE": return p[1];
            case "ZIP": return p[2];
            default: return p[0] + ", " + p[1] + " " + p[2];
        }
    }

    private String[] cityTriplet(String value, String mode, MaskContext ctx) {
        List<String> rows = SeedLists.get("cities_us.csv");
        String preserveState = stateToPreserve(value, mode, ctx);
        List<String> candidates = preserveState == null ? rows : rows.stream()
                .filter(r -> r.split(",")[1].equalsIgnoreCase(preserveState))
                .toList();
        if (candidates.isEmpty()) candidates = rows;
        String row = candidates.get(Determinism.pick(secret, "geo.triplet|" + (preserveState == null ? "any" : preserveState),
                normalize(value), candidates.size()));
        String[] p = row.split(",");
        return new String[]{p[0], preserveState == null ? p[1].toUpperCase(Locale.ROOT) : preserveState.toUpperCase(Locale.ROOT), p[2]};
    }

    private static String stateToPreserve(String value, String mode, MaskContext ctx) {
        if (mode == null || !mode.equalsIgnoreCase("PRESERVE_STATE")) return null;
        String direct = value == null ? "" : value.trim();
        if (direct.matches("[A-Za-z]{2}") && US_STATES.contains(direct.toUpperCase(Locale.ROOT))) return direct.toUpperCase(Locale.ROOT);
        Matcher m = Pattern.compile("(?:^|[\\s,])([A-Za-z]{2})(?:\\s+\\d{5}(?:-\\d{4})?|[\\s,]|$)").matcher(direct);
        while (m.find()) {
            String state = m.group(1).toUpperCase(Locale.ROOT);
            if (US_STATES.contains(state)) return state;
        }
        if (ctx != null) {
            String country = firstPresent(ctx, "country", "country_code", "billing_country", "shipping_country");
            if (country != null && !isUsCountry(country)) return null;
            String state = firstPresent(ctx, "state", "state_code", "province", "region", "billing_state", "shipping_state");
            if (state != null && state.trim().matches("[A-Za-z]{2}")) {
                String upper = state.trim().toUpperCase(Locale.ROOT);
                if (US_STATES.contains(upper)) return upper;
            }
        }
        return null;
    }

    private static String firstPresent(MaskContext ctx, String... columns) {
        for (String col : columns) {
            String value = ctx.row.get(col);
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    private static boolean isUsCountry(String country) {
        String c = country.trim().toUpperCase(Locale.ROOT).replace(".", "");
        return c.equals("US") || c.equals("USA") || c.equals("UNITED STATES") || c.equals("UNITED STATES OF AMERICA");
    }

    /** FPE-style: every digit -> digit, letter -> letter (case preserved); punctuation/length untouched.
     *  Phone mode (keepCountryCode) preserves a leading '+' AND its country-code digit group so
     *  routing/locale logic in the application under test keeps working. */
    private String formatPreserve(String salt, String value, boolean keepCountryCode) {
        Random r = Determinism.rng(secret, salt + "|fpe", value);
        int preserveUpTo = 0;
        if (keepCountryCode && value.startsWith("+")) {
            preserveUpTo = 1;
            while (preserveUpTo < value.length() && Character.isDigit(value.charAt(preserveUpTo))) preserveUpTo++;
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (i < preserveUpTo) { out.append(c); continue; }
            if (c >= '0' && c <= '9') out.append((char) ('0' + r.nextInt(10)));
            else if (c >= 'a' && c <= 'z') out.append((char) ('a' + r.nextInt(26)));
            else if (c >= 'A' && c <= 'Z') out.append((char) ('A' + r.nextInt(26)));
            else out.append(c);
        }
        return out.toString();
    }

    private String redactKeepLast4(String value) {
        if (value.length() <= 4) return "****";
        String last4 = value.substring(value.length() - 4);
        return "*".repeat(Math.min(value.length() - 4, 12)) + last4;
    }

    // ---------- user-defined Lua scripts (IBM-Optim-style exits) ----------

    private volatile MaskScriptProvider scriptProvider;

    /** Wired by the host application (DB-backed script registry). */
    public void setScriptProvider(MaskScriptProvider provider) { this.scriptProvider = provider; }

    // LuaJ Globals are not thread-safe → one sandbox + compiled-chunk cache per thread (partitioned loads).
    private static final ThreadLocal<org.luaj.vm2.Globals> LUA = ThreadLocal.withInitial(MaskingEngine::luaSandbox);
    private static final ThreadLocal<java.util.Map<String, org.luaj.vm2.LuaValue>> LUA_CACHE =
            ThreadLocal.withInitial(java.util.HashMap::new);

    /** Sandbox: base/string/table/math only — no os, no io, no require, no file loading, no Java access. */
    private static org.luaj.vm2.Globals luaSandbox() {
        org.luaj.vm2.Globals g = new org.luaj.vm2.Globals();
        g.load(new org.luaj.vm2.lib.jse.JseBaseLib());
        g.load(new org.luaj.vm2.lib.PackageLib());            // required: other libs register into package.loaded
        g.load(new org.luaj.vm2.lib.StringLib());
        g.load(new org.luaj.vm2.lib.TableLib());
        g.load(new org.luaj.vm2.lib.jse.JseMathLib());
        org.luaj.vm2.LoadState.install(g);
        org.luaj.vm2.compiler.LuaC.install(g);
        g.finder = filename -> null;                          // no filesystem lookups
        // libs are installed — now remove every loading/OS entry point from script reach
        g.set("dofile", org.luaj.vm2.LuaValue.NIL);
        g.set("loadfile", org.luaj.vm2.LuaValue.NIL);
        g.set("require", org.luaj.vm2.LuaValue.NIL);
        g.set("package", org.luaj.vm2.LuaValue.NIL);
        return g;
    }

    /**
     * Compile-check a script without running it. @return null when the syntax is valid, otherwise the
     * Lua error message including the line number (e.g. "script:3: 'end' expected"). Used at save time
     * so authors see syntax problems immediately instead of at job run time.
     */
    public String checkScriptSyntax(String source) {
        if (source == null || source.isBlank()) return "script is empty";
        try {
            LUA.get().load(source, "script");
            return null;
        } catch (org.luaj.vm2.LuaError e) {
            return e.getMessage();
        }
    }

    /**
     * Run a user-defined masking script. The script sees: {@code value} (string), {@code param}
     * (rule param2), {@code rowIndex}, {@code row} (original values by lower-cased column), and the
     * {@code forge} helper table whose functions are DETERMINISTIC (HMAC-keyed on the project secret),
     * so custom masks preserve referential integrity like the built-ins. The script must
     * {@code return} the masked value (nil = SQL NULL). Errors FAIL the run loudly — silently passing
     * PII through on a script bug would be a leak.
     */
    private String script(String salt, String value, String name, String param, MaskContext ctx) {
        if (name == null || name.isBlank())
            throw new IllegalStateException("SCRIPT masking needs the script name in param1");
        MaskScriptProvider provider = scriptProvider;
        if (provider == null)
            throw new IllegalStateException("Masking scripts are not available in this runtime");
        String source = provider.source(name.trim());
        if (source == null)
            throw new IllegalStateException("Masking script '" + name.trim() + "' not found (or not GLOBAL)");

        String key = name.trim().toLowerCase(Locale.ROOT) + "#" + source.hashCode();
        try {
            org.luaj.vm2.Globals g = LUA.get();
            java.util.Map<String, org.luaj.vm2.LuaValue> cache = LUA_CACHE.get();
            org.luaj.vm2.LuaValue chunk = cache.get(key);
            if (chunk == null) {
                if (cache.size() > 64) cache.clear();
                chunk = g.load(source, "@" + name.trim());
                cache.put(key, chunk);
            }
            bindScriptGlobals(g, salt, value, param, ctx);
            org.luaj.vm2.LuaValue out = chunk.call();
            return out.isnil() ? null : out.tojstring();
        } catch (org.luaj.vm2.LuaError e) {
            throw new IllegalStateException("Masking script '" + name.trim() + "' failed: " + e.getMessage(), e);
        }
    }

    private void bindScriptGlobals(org.luaj.vm2.Globals g, String salt, String value, String param, MaskContext ctx) {
        g.set("value", value == null ? org.luaj.vm2.LuaValue.NIL : org.luaj.vm2.LuaValue.valueOf(value));
        g.set("param", param == null ? org.luaj.vm2.LuaValue.NIL : org.luaj.vm2.LuaValue.valueOf(param));
        g.set("rowIndex", org.luaj.vm2.LuaValue.valueOf(ctx == null ? 1 : ctx.rowIndex));
        org.luaj.vm2.LuaTable row = new org.luaj.vm2.LuaTable();
        if (ctx != null) ctx.row.forEach((k, v) -> { if (v != null) row.set(k, v); });
        g.set("row", row);

        org.luaj.vm2.LuaTable forge = new org.luaj.vm2.LuaTable();
        // forge.hash(s, mod) → deterministic 0..mod-1 for the same input, everywhere
        forge.set("hash", new org.luaj.vm2.lib.TwoArgFunction() {
            public org.luaj.vm2.LuaValue call(org.luaj.vm2.LuaValue a, org.luaj.vm2.LuaValue b) {
                long mod = Math.max(1, b.optlong(1_000_000L));
                return valueOf(Math.floorMod(Determinism.hashLong(secret, "script.hash", normalize(a.tojstring())), mod));
            }
        });
        // forge.pick(seedlist, s) → deterministic seedlist substitution (same lists the built-ins use)
        forge.set("pick", new org.luaj.vm2.lib.TwoArgFunction() {
            public org.luaj.vm2.LuaValue call(org.luaj.vm2.LuaValue list, org.luaj.vm2.LuaValue s) {
                List<String> values = SeedLists.get(list.tojstring());
                return valueOf(values.get(Determinism.pick(secret, "script.pick|" + list.tojstring(),
                        normalize(s.tojstring()), values.size())));
            }
        });
        // forge.fpe(s) → format-preserving scramble (digit→digit, letter→letter)
        forge.set("fpe", new org.luaj.vm2.lib.OneArgFunction() {
            public org.luaj.vm2.LuaValue call(org.luaj.vm2.LuaValue s) {
                return valueOf(formatPreserve("script.fpe", s.tojstring(), false));
            }
        });
        // forge.mask(fnName, s) → any built-in function with its canonical salt (join-consistent)
        forge.set("mask", new org.luaj.vm2.lib.TwoArgFunction() {
            public org.luaj.vm2.LuaValue call(org.luaj.vm2.LuaValue fnName, org.luaj.vm2.LuaValue s) {
                MaskFunction fn;
                try { fn = MaskFunction.valueOf(fnName.tojstring().trim().toUpperCase(Locale.ROOT)); }
                catch (IllegalArgumentException e) { throw new org.luaj.vm2.LuaError("unknown mask function: " + fnName.tojstring()); }
                if (fn == MaskFunction.SCRIPT) throw new org.luaj.vm2.LuaError("forge.mask cannot call SCRIPT (no recursion)");
                String masked = mask(fn, branchSalt(fn, salt), s.isnil() ? null : s.tojstring(), null, null, ctx);
                return masked == null ? NIL : valueOf(masked);
            }
        });
        // forge.masked(col) → a sibling column's ALREADY-masked value in this row (e.g. masked first_name)
        forge.set("masked", new org.luaj.vm2.lib.OneArgFunction() {
            public org.luaj.vm2.LuaValue call(org.luaj.vm2.LuaValue col) {
                String v = ctx == null ? null : ctx.maskedOf(col.tojstring());
                return v == null ? NIL : valueOf(v);
            }
        });
        g.set("forge", forge);
    }

    // ---------- helpers ----------
    private static String reinsertDigits(String original, String digits) {
        StringBuilder out = new StringBuilder(original.length());
        int di = 0;
        for (char c : original.toCharArray()) {
            if (Character.isDigit(c) && di < digits.length()) out.append(digits.charAt(di++));
            else out.append(c);
        }
        while (di < digits.length()) out.append(digits.charAt(di++));
        return out.toString();
    }

    private static String groupDigits(String digits, String sep) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && i % 4 == 0) out.append(sep);
            out.append(digits.charAt(i));
        }
        return out.toString();
    }

    private static String maskDigitsExceptLast(String value, int keep, String mask) {
        int total = (int) value.chars().filter(Character::isDigit).count();
        int seen = 0;
        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            if (Character.isDigit(c)) out.append(++seen <= total - keep ? mask : c);
            else out.append(c);
        }
        return out.toString();
    }

    private static String normalize(String v) { return v == null ? "" : v.trim().toLowerCase(); }
    private static String firstToken(String v) { String t = v.trim(); int i = t.indexOf(' '); return i < 0 ? t : t.substring(0, i); }
    private static String lastToken(String v)  { String t = v.trim(); int i = t.lastIndexOf(' '); return i < 0 ? t : t.substring(i + 1); }
    private static String middleToken(String v) {
        String[] parts = v == null ? new String[0] : v.trim().split("\\s+");
        if (parts.length <= 2) return "";
        return String.join(" ", java.util.Arrays.copyOfRange(parts, 1, parts.length - 1));
    }

    private static String formatName(String format, String first, String middle, String last) {
        String pattern = format == null || format.isBlank() ? "FIRST LAST" : format.trim();
        String out = pattern
                .replaceAll("(?i)\\bMID\\b", "MIDDLE")
                .replaceAll("(?i)\\bFIRST\\b", first)
                .replaceAll("(?i)\\bMIDDLE\\b", middle)
                .replaceAll("(?i)\\bLAST\\b", last);
        return cleanName(out);
    }

    private static String cleanName(String value) {
        String out = value.replaceAll("\\s+", " ").replaceAll("\\s+,", ",").replaceAll(",\\s*", ", ").trim();
        out = out.replaceAll("(,\\s*)+$", "").replaceAll("\\s+,", ",");
        return out.replaceAll("\\s{2,}", " ").trim();
    }

    private static String caseMode(String param1, String param2) {
        if (isCaseMode(param2)) return param2.trim().toUpperCase(Locale.ROOT);
        if (isCaseMode(param1)) return param1.trim().toUpperCase(Locale.ROOT);
        return null;
    }

    private static boolean isCaseMode(String mode) {
        if (mode == null || mode.isBlank()) return false;
        String m = mode.trim().toUpperCase(Locale.ROOT).replace("-", "_").replace(" ", "_");
        return m.equals("LOWER") || m.equals("LOWERCASE")
                || m.equals("UPPER") || m.equals("UPPERCASE")
                || m.equals("PROPER") || m.equals("TITLE") || m.equals("TITLE_CASE")
                || m.equals("AS_IS") || m.equals("PRESERVE") || m.equals("ORIGINAL");
    }

    private static String applyCase(String value, String mode) {
        if (value == null || mode == null || mode.isBlank()) return value;
        String m = mode.trim().toUpperCase(Locale.ROOT).replace("-", "_").replace(" ", "_");
        if (m.equals("LOWER") || m.equals("LOWERCASE")) return value.toLowerCase(Locale.ROOT);
        if (m.equals("UPPER") || m.equals("UPPERCASE")) return value.toUpperCase(Locale.ROOT);
        if (m.equals("PROPER") || m.equals("TITLE") || m.equals("TITLE_CASE")) return properCase(value);
        return value;
    }

    private static String properCase(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        boolean nextUpper = true;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetter(c)) {
                out.append(nextUpper ? Character.toUpperCase(c) : c);
                nextUpper = false;
            } else {
                out.append(c);
                nextUpper = c == ' ' || c == '-' || c == '\'' || c == ',';
            }
        }
        return out.toString();
    }

    private static int parseIntOr(String s, int def) { try { return s == null ? def : Integer.parseInt(s.trim()); } catch (Exception e) { return def; } }

    // ---- Julian (ordinal) date formatters. Built explicitly: ofPattern("yyyyDDD") cannot parse
    //      adjacent variable-width numeric fields, so the pattern string alone would fail at parse time.
    /** yyyyDDD — mainframe/DB2 Julian, e.g. 2026309 = day 309 of 2026. */
    private static final DateTimeFormatter JULIAN_YYYYDDD = new java.time.format.DateTimeFormatterBuilder()
            .appendValue(java.time.temporal.ChronoField.YEAR, 4)
            .appendValue(java.time.temporal.ChronoField.DAY_OF_YEAR, 3)
            .toFormatter();
    /** yyDDD — 5-digit packed Julian, e.g. 26309 (two-digit year pivots at 2000). */
    private static final DateTimeFormatter JULIAN_YYDDD = new java.time.format.DateTimeFormatterBuilder()
            .appendValueReduced(java.time.temporal.ChronoField.YEAR, 2, 2, 2000)
            .appendValue(java.time.temporal.ChronoField.DAY_OF_YEAR, 3)
            .toFormatter();
    /** CYYDDD — JD Edwards Julian: (year-1900) as 3 digits + day-of-year, e.g. 126309 = 2026 day 309. */
    private static final DateTimeFormatter JULIAN_CYYDDD = new java.time.format.DateTimeFormatterBuilder()
            .appendValueReduced(java.time.temporal.ChronoField.YEAR, 3, 3, 1900)
            .appendValue(java.time.temporal.ChronoField.DAY_OF_YEAR, 3)
            .toFormatter();

    private static DateTimeFormatter julianFormatter(String fmt) {
        return switch (fmt.trim().toUpperCase(Locale.ROOT)) {
            case "YYYYDDD", "JULIAN" -> JULIAN_YYYYDDD;
            case "YYDDD" -> JULIAN_YYDDD;
            case "CYYDDD", "JDE" -> JULIAN_CYYDDD;
            default -> null;
        };
    }

    private static DateTimeFormatter formatter(String fmt, String sample) {
        if (fmt != null && !fmt.isBlank()) {
            DateTimeFormatter julian = julianFormatter(fmt);
            if (julian != null) return julian;
            return DateTimeFormatter.ofPattern(fmt.trim());
        }
        String s = sample.trim();
        if (s.matches("\\d{4}-\\d{2}-\\d{2}.*")) return DateTimeFormatter.ofPattern("yyyy-MM-dd");
        if (s.matches("\\d{2}/\\d{2}/\\d{4}"))   return DateTimeFormatter.ofPattern("dd/MM/yyyy");
        if (s.matches("\\d{2}-\\d{2}-\\d{4}"))   return DateTimeFormatter.ofPattern("dd-MM-yyyy");
        // auto-detect 7-digit Julian: plausible year + day-of-year 001-366 (yyDDD/CYYDDD are too
        // ambiguous to auto-detect — select them explicitly in the format dropdown)
        if (s.matches("(19|20)\\d{5}")) {
            int ddd = Integer.parseInt(s.substring(4));
            if (ddd >= 1 && ddd <= 366) return JULIAN_YYYYDDD;
        }
        return DateTimeFormatter.ISO_LOCAL_DATE;
    }
}
