package io.forgetdm.core.mask;

import io.forgetdm.core.temenos.TemenosCodec;
import io.forgetdm.core.util.Determinism;
import io.forgetdm.core.util.Luhn;
import io.forgetdm.core.util.SeedLists;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
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

    /** Validate governed lookup syntax and references before a policy is persisted or executed. */
    public void validateLookupConfiguration(MaskFunction fn, String sourceSpec, String optionsSpec) {
        if (fn != MaskFunction.SECURE_LOOKUP && fn != MaskFunction.DIRECT_LOOKUP && fn != MaskFunction.HASH_LOOKUP) return;
        Map<String, String> options = lookupOptions(optionsSpec);
        String definition = lookupDefinition(sourceSpec, lookupUsesCache(options));
        if (fn == MaskFunction.DIRECT_LOOKUP) directLookupMappings(definition, options);
        else if (fn == MaskFunction.HASH_LOOKUP) hashLookupTable(definition);
        else {
            List<String> values = Arrays.stream(definition.split("\\|"))
                    .map(String::trim).filter(part -> !part.isBlank()).map(MaskingEngine::stripLookupWeight).toList();
            if (values.isEmpty()) throw new IllegalStateException("SECURE_LOOKUP has no usable replacement values");
        }
    }

    /**
     * Seeded variant: derives a new deterministic key from (project secret, user seed).
     * Same seed => identical results across runs and tables (referential integrity preserved);
     * a different seed => a completely different — but still deterministic — masked universe.
     * Blank/null seed returns this engine unchanged (default behaviour).
     */
    public MaskingEngine withSeed(String seed) {
        if (seed == null || seed.isBlank()) return this;
        MaskingEngine seeded = new MaskingEngine(secret + "::seed::" + seed.trim());
        seeded.scriptProvider = scriptProvider;
        seeded.lookupProvider = lookupProvider;
        return seeded;
    }

    /** Mask a single value. salt should be stable per logical attribute (e.g. "person.ssn" or just "ssn"). */
    public String mask(MaskFunction fn, String salt, String value, String param1, String param2, MaskContext ctx) {
        if (fn == MaskFunction.PASSTHROUGH) return value;
        if (fn == MaskFunction.NULLIFY) return null;
        if (fn == MaskFunction.FIXED) return applyCase(param1, caseMode(param1, param2));
        if (fn == MaskFunction.SEQUENCE) return (param1 == null ? "ID-" : param1) + (ctx == null ? 0 : ctx.rowIndex);
        if (fn == MaskFunction.DIRECT_LOOKUP) return directLookup(value, param1, param2, ctx);
        if (fn == MaskFunction.HASH_LOOKUP) return hashLookup(value, param1, param2, ctx);
        // Row-composition scripts may intentionally populate an empty source column from
        // previously masked sibling columns (for example: "last_name, first_name").
        if (fn == MaskFunction.SCRIPT) return script(salt, value, param1, param2, ctx);
        if (value == null || value.isEmpty()) return value;

        // Temenos T24 multi-value fields store nested arrays in one column, delimited by VM (253) /
        // SVM (252) / FM (254). Parse the structure and mask each sub-value in place, re-injecting the
        // exact marks so field and sub-value counts never shift (RFP §3.2.1). Every mask function is
        // thereby structure-aware for free; a value with no marks is a single leaf (unchanged path).
        if (TemenosCodec.hasMarkers(value)) {
            return TemenosCodec.mapLeaves(value, leaf -> maskScalar(fn, salt, leaf, param1, param2, ctx));
        }
        return maskScalar(fn, salt, value, param1, param2, ctx);
    }

    /** Mask a single scalar leaf value (no Temenos structure). */
    private String maskScalar(MaskFunction fn, String salt, String value, String param1, String param2, MaskContext ctx) {
        switch (fn) {
            case FIRST_NAME: return applyCase(pick("first_names.txt", salt, value), caseMode(param1, param2));
            case LAST_NAME:  return applyCase(pick("last_names.txt", salt, value), caseMode(param1, param2));
            case COMPANY:    return applyCase(pick("companies.txt", salt, value), caseMode(param1, param2));
            case FULL_NAME:  return fullName(salt, value, param1, param2);
            case EMAIL:      return email(salt, value, param1, param2, ctx);
            case PHONE:      return phone(salt, value, param1, param2);
            case SSN:        return ssn(salt, value, param1, param2);
            case CREDIT_CARD:return creditCard(salt, value, param1, param2);
            case DATE_SHIFT: return dateShift(salt, value, param1, param2, ctx);
            case DOB_AGE_BAND: return dobAgeBand(salt, value, parseIntOr(param1, 5), param2);
            case ADDRESS_STREET: return applyCase(street(salt, value), caseMode(param1, param2));
            case ADDRESS_US: return usAddress(salt, value, param1, param2, ctx);
            case CITY_STATE_ZIP: return cityStateZip(salt, value, param1, param2, ctx);
            case FORMAT_PRESERVE: return applyCase(formatPreserve(salt, value, false), caseMode(param1, param2));
            case CHARACTER_MAP: return applyCase(characterMap(salt, value, param1), caseMode(null, param2));
            case TOKENIZE: return tokenize(salt, value, param1, param2);
            case SECURE_LOOKUP: return secureLookup(salt, value, param1, param2);
            case REDACT: return redact(value, param1, param2);
            case REDACT_KEEP_LAST4: return redactKeepLast4(value);
            case NUMERIC_NOISE: return numericNoise(salt, value, param1, param2);
            case MIN_MAX: return minMax(salt, value, param1, param2);
            case BANK_ACCOUNT: return bankAccount(salt, value, param1);
            case IBAN: return iban(salt, value, param1, param2);
            case SWIFT_BIC: return swiftBic(salt, value, param1);
            case ABA_ROUTING: return abaRouting(salt, value, param1);
            case NATIONAL_ID: return nationalId(salt, value, param1, param2);
            case IP_ADDRESS: return ipAddress(salt, value, param1);
            case MAC_ADDRESS: return macAddress(salt, value, param1);
            case UUID: return uuid(salt, value);
            case HASH_LOV: return applyCase(pick(param1 == null ? "first_names.txt" : param1, salt, value), caseMode(null, param2));
            case BY_INDICATOR: return byIndicator(salt, value, param1, param2, ctx);
            case PARTIAL_MASK: return partialMask(salt, value, param1, param2, ctx);
            case PHONE_SPLIT: return splitDigits(value, param1, param2, ctx, "phone", false);
            case SSN_SPLIT:   return splitDigits(value, param1, param2, ctx, "ssn", true);
            case DATE_SPLIT:  return splitDate(value, param1, param2, ctx);
            case AGE:         return age(value, param1, param2);
            case SCRIPT:      throw new IllegalStateException("SCRIPT must be evaluated before null handling");
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
            case BANK_ACCOUNT -> "bank.account";
            case IBAN -> "iban";
            case SWIFT_BIC -> "swift.bic";
            case ABA_ROUTING -> "routing.aba";
            case NATIONAL_ID -> "national.id";
            case IP_ADDRESS -> "network.ip";
            case MAC_ADDRESS -> "network.mac";
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

    /**
     * Card: preserves the requested card domain, applies a keyed decimal permutation, and repairs
     * the Luhn digit. Unlike seeded random digit generation, a permutation cannot collapse two
     * distinct valid PANs to the same masked PAN.
     */
    private String creditCard(String salt, String value, String mode, String format) {
        String digits = value.replaceAll("\\D", "");
        // Fail closed: a malformed PAN is still sensitive and must never pass through unchanged.
        if (digits.length() < 12 || digits.length() > 19) return formatPreserve(salt + "|ccn-nonconform", value, false);
        if (!Luhn.isValid(digits)) {
            String maskedDigits = decimalPermutation(salt + "|ccn-non-luhn|length=" + digits.length(), digits);
            return formatCard(maskedDigits, value, format);
        }
        String m = mode == null || mode.isBlank() ? "VALID_PRESERVE_BIN" : mode.trim().toUpperCase(Locale.ROOT);
        String full;
        if ("VALID_KEEP_LAST4".equals(m) || "KEEP_LAST4".equals(m)) {
            full = validCardPreserveLast4(salt, digits);
        } else if ("VALID_RANDOM_BIN".equals(m) || "REDACT".equals(m)) {
            full = validCardPermutingBody(salt + "|random-bin", digits, cardNetworkPrefixLength(digits));
        } else if ("FORMAT_PRESERVE".equals(m)) {
            full = validCardPermutingBody(salt + "|format", digits, 1);
        } else {
            full = validCardPermutingBody(salt + "|preserve-bin", digits, 6);
        }
        return formatCard(full, value, format);
    }

    private String validCardPermutingBody(String salt, String digits, int prefixLength) {
        String prefix = digits.substring(0, prefixLength);
        String variableBody = digits.substring(prefixLength, digits.length() - 1);
        String permuted = decimalPermutation(
                salt + "|length=" + digits.length() + "|prefix=" + prefix,
                variableBody);
        String body = prefix + permuted;
        return body + Luhn.checkDigit(body);
    }

    private String validCardPreserveLast4(String salt, String digits) {
        String preservedBodyTail = digits.substring(digits.length() - 4, digits.length() - 1);
        char preservedCheck = digits.charAt(digits.length() - 1);
        int prefixLength = digits.length() - 4;
        String candidate = digits.substring(0, prefixLength);
        String domainSalt = salt + "|keep-last4|length=" + digits.length()
                + "|tail=" + preservedBodyTail + preservedCheck;

        // Cycle-walking a permutation over the subset whose computed check digit is the preserved
        // digit remains a permutation, so KEEP_LAST4 is collision-free for valid source PANs too.
        for (int attempt = 0; attempt < 10_000; attempt++) {
            candidate = decimalPermutation(domainSalt, candidate);
            String body = candidate + preservedBodyTail;
            if (Luhn.checkDigit(body) == preservedCheck) {
                return body + preservedCheck;
            }
        }
        throw new IllegalStateException("Unable to mask card while preserving its last four digits");
    }

    private static int cardNetworkPrefixLength(String digits) {
        if (digits.startsWith("34") || digits.startsWith("37")) return 2;
        if (digits.matches("5[1-5].*")) return 2;
        return 1;
    }

    /** Keyed, balanced decimal Feistel-style permutation. Each round is reversible with the key. */
    private String decimalPermutation(String salt, String digits) {
        if (digits == null || digits.isEmpty()) return digits;
        int leftLength = (digits.length() + 1) / 2;
        int rightLength = digits.length() - leftLength;
        BigInteger left = new BigInteger(digits.substring(0, leftLength));
        BigInteger right = rightLength == 0 ? BigInteger.ZERO : new BigInteger(digits.substring(leftLength));
        BigInteger leftModulus = BigInteger.TEN.pow(leftLength);
        BigInteger rightModulus = BigInteger.TEN.pow(rightLength);

        for (int round = 0; round < 8; round++) {
            if ((round & 1) == 0) {
                BigInteger delta = decimalRound(salt, round, decimalDigits(right, rightLength), leftModulus);
                left = left.add(delta).mod(leftModulus);
            } else if (rightLength > 0) {
                BigInteger delta = decimalRound(salt, round, decimalDigits(left, leftLength), rightModulus);
                right = right.add(delta).mod(rightModulus);
            }
        }
        return decimalDigits(left, leftLength) + decimalDigits(right, rightLength);
    }

    private BigInteger decimalRound(String salt, int round, String half, BigInteger modulus) {
        byte[] digest = Determinism.hmac(secret, salt + "|round=" + round, half);
        return new BigInteger(1, digest).mod(modulus);
    }

    private static String decimalDigits(BigInteger value, int width) {
        if (width == 0) return "";
        return String.format(Locale.ROOT, "%0" + width + "d", value);
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

    private String dateShift(String salt, String value, String shiftSpec, String fmt, MaskContext ctx) {
        String trimmed = value.trim();
        String datePart = trimmed.length() > 10 ? trimmed.substring(0, 10) : trimmed;
        String suffix = trimmed.length() > 10 ? trimmed.substring(10) : "";
        DateTimeFormatter f = formatter(fmt, datePart);
        try {
            LocalDate d = LocalDate.parse(datePart, f);
            int[] range = ctx != null && ctx.hasSharedDateShiftRange()
                    ? new int[]{ctx.sharedDateShiftMinDays(), ctx.sharedDateShiftMaxDays()}
                    : dateShiftRange(shiftSpec);
            long span = (long) range[1] - range[0] + 1L;
            long shift;
            if (ctx != null && ctx.hasSharedDateShiftRange()) {
                Long cached = ctx.sharedDateShiftDays();
                if (cached == null) {
                    String rowKey = ctx.row.isEmpty()
                            ? Long.toString(ctx.rowIndex)
                            : new java.util.TreeMap<>(ctx.row).toString();
                    cached = range[0] + (Determinism.hashLong(secret, "row|dshift", rowKey) % span);
                    if (cached == 0 && (range[0] != 0 || range[1] != 0))
                        cached = range[1] > 0 ? 1L : -1L;
                    ctx.sharedDateShiftDays(cached);
                }
                shift = cached;
            } else {
                shift = range[0] + (Determinism.hashLong(secret, salt + "|dshift", value) % span);
            }
            if (shift == 0 && (range[0] != 0 || range[1] != 0)) {
                shift = range[1] > 0 ? 1 : -1;
            }
            return d.plusDays(shift).format(f) + suffix;
        } catch (DateTimeParseException e) { return value; }
    }

    private static int[] dateShiftRange(String shiftSpec) {
        String spec = shiftSpec == null || shiftSpec.isBlank() ? "365" : shiftSpec.trim();
        try {
            if (!spec.contains(":")) {
                int max = Math.abs(Integer.parseInt(spec));
                return new int[]{-max, max};
            }
            String[] parts = spec.split(":", -1);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IllegalStateException("DATE_SHIFT range must be minDays:maxDays, e.g. 0:365");
            }
            int min = Integer.parseInt(parts[0].trim());
            int max = Integer.parseInt(parts[1].trim());
            if (min > max) throw new IllegalStateException("DATE_SHIFT minimum days must be <= maximum days");
            return new int[]{min, max};
        } catch (NumberFormatException e) {
            throw new IllegalStateException("DATE_SHIFT must be maxDays or minDays:maxDays", e);
        }
    }

    /**
     * Intersect the configured ranges for a table's DATE_SHIFT rules. Two or more temporal columns use
     * this shared range so one deterministic row-level shift preserves intervals and CHECK constraints.
     * A single rule keeps the legacy value/column-specific behavior.
     */
    public static int[] intersectDateShiftRanges(java.util.Collection<String> shiftSpecs) {
        if (shiftSpecs == null || shiftSpecs.size() < 2) return null;
        int min = Integer.MIN_VALUE;
        int max = Integer.MAX_VALUE;
        for (String spec : shiftSpecs) {
            int[] range = dateShiftRange(spec);
            min = Math.max(min, range[0]);
            max = Math.min(max, range[1]);
        }
        if (min > max) {
            throw new IllegalStateException("DATE_SHIFT ranges on related columns do not overlap; "
                    + "use a common range or AGE so temporal ordering can be preserved");
        }
        return new int[]{min, max};
    }

    /** DOB: random date but same age band => eligibility logic (18+, senior) keeps behaving. */
    private String dobAgeBand(String salt, String value, int bandYears, String fmt) {
        String trimmed = value.trim();
        String datePart = trimmed.length() > 10 ? trimmed.substring(0, 10) : trimmed;
        String suffix = trimmed.length() > 10 ? trimmed.substring(10) : "";
        DateTimeFormatter f = formatter(fmt, datePart);
        try {
            LocalDate d = LocalDate.parse(datePart, f);
            int year = d.getYear();
            int bandStart = year - Math.floorMod(year, Math.max(1, bandYears));
            Random r = Determinism.rng(secret, salt + "|dob", value);
            LocalDate out = LocalDate.of(bandStart + r.nextInt(Math.max(1, bandYears)), 1 + r.nextInt(12), 1 + r.nextInt(28));
            return out.format(f) + suffix;
        } catch (DateTimeParseException e) { return value; }
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

    /** Delphix-style whole-value character mapping with optional leading/trailing preserve ranges. */
    private String characterMap(String salt, String value, String preserveSpec) {
        String masked = formatPreserve(salt + "|character-map", value, false);
        int keepFirst = preserveCount(preserveSpec, "FIRST");
        int keepLast = preserveCount(preserveSpec, "LAST");
        if (keepFirst == 0 && keepLast == 0) return masked;
        int length = value.length();
        StringBuilder out = new StringBuilder(masked);
        for (int i = 0; i < Math.min(keepFirst, length); i++) out.setCharAt(i, value.charAt(i));
        for (int i = Math.max(0, length - keepLast); i < length; i++) out.setCharAt(i, value.charAt(i));
        return out.toString();
    }

    private static int preserveCount(String spec, String key) {
        if (spec == null || spec.isBlank()) return 0;
        Matcher matcher = Pattern.compile("(?:^|[,;])\\s*" + key + "\\s*[:=]\\s*(\\d+)", Pattern.CASE_INSENSITIVE)
                .matcher(spec);
        return matcher.find() ? Math.min(1024, Integer.parseInt(matcher.group(1))) : 0;
    }

    /** Collision-resistant, irreversible HMAC token. A 32-hex-character token carries 128 bits. */
    private String tokenize(String salt, String value, String prefixSpec, String lengthSpec) {
        String prefix = prefixSpec == null ? "TKN_" : prefixSpec;
        if ("NONE".equalsIgnoreCase(prefix)) prefix = "";
        int length = Math.max(12, Math.min(64, parseIntOr(lengthSpec, 32)));
        byte[] digest = Determinism.hmac(secret, salt + "|token", normalize(value));
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest) hex.append(String.format("%02x", b & 0xff));
        return prefix + hex.substring(0, length);
    }

    /** Cryptographic lookup from an inline pipe list or a packaged UTF-8 seedlist. */
    private String secureLookup(String salt, String value, String source, String outputCase) {
        if (source == null || source.isBlank())
            throw new IllegalStateException("SECURE_LOOKUP needs pipe-delimited values or a seedlist file in param1");
        String definition = lookupDefinition(source, true);
        List<String> values = Arrays.stream(definition.split("\\|"))
                .map(String::trim)
                .filter(part -> !part.isBlank())
                .map(MaskingEngine::stripLookupWeight)
                .toList();
        if (values.isEmpty()) throw new IllegalStateException("SECURE_LOOKUP has no usable replacement values");
        String picked = values.get(Determinism.pick(secret, salt + "|secure-lookup", normalize(value), values.size()));
        return applyCase(picked, caseMode(null, outputCase));
    }

    /** Exact Optim-style lookup: source keys map directly to governed replacement values. */
    private String directLookup(String value, String sourceSpec, String optionsSpec, MaskContext ctx) {
        Map<String, String> options = lookupOptions(optionsSpec);
        String definition = lookupDefinition(sourceSpec, lookupUsesCache(options));
        Map<String, String> mappings = directLookupMappings(definition, options);

        String sourceValue = lookupSourceValue(value, options, ctx);
        String key = specialLookupKey(sourceValue);
        if (key == null) key = lookupKey(sourceValue, options);
        String replacement = mappings.get(key);
        if (replacement == null) return lookupFallback(options.getOrDefault("NOT_FOUND", "ERROR"), value, options,
                "DIRECT_LOOKUP found no matching source key");
        return applyCase(replacement, options.get("OUTPUT"));
    }

    private static Map<String, String> directLookupMappings(String definition, Map<String, String> options) {
        Map<String, String> mappings = new LinkedHashMap<>();
        for (String part : definition.split("\\|", -1)) {
            if (part.isBlank()) continue;
            LookupPair pair = lookupPair(part);
            if (pair == null) throw new IllegalStateException("DIRECT_LOOKUP entries must use source=>replacement pairs");
            String key = lookupKey(pair.key(), options);
            if (key.isEmpty()) throw new IllegalStateException("DIRECT_LOOKUP contains an empty source key");
            if (mappings.putIfAbsent(key, pair.value().trim()) != null)
                throw new IllegalStateException("DIRECT_LOOKUP contains duplicate keys after trim/case rules");
        }
        if (mappings.isEmpty()) throw new IllegalStateException("DIRECT_LOOKUP has no usable source=>replacement pairs");
        return Map.copyOf(mappings);
    }

    /**
     * Optim-style hash lookup. A source value is hashed to a contiguous 1..N replacement row. Optional
     * -1/-2/-3 rows represent NULL, spaces, and zero-length input, matching Optim's reserved lookup keys.
     */
    private String hashLookup(String value, String sourceSpec, String optionsSpec, MaskContext ctx) {
        Map<String, String> options = lookupOptions(optionsSpec);
        String definition = lookupDefinition(sourceSpec, lookupUsesCache(options));
        HashLookupTable table = hashLookupTable(definition);
        String sourceValue = lookupSourceValue(value, options, ctx);
        int reservedKey = sourceValue == null ? -1 : sourceValue.isEmpty() ? -3 : sourceValue.trim().isEmpty() ? -2 : 0;
        if (reservedKey != 0) {
            String reserved = table.reserved().get(reservedKey);
            if (reserved != null) return applyCase(selectLookupColumn(reserved, options), options.get("OUTPUT"));
            String option = reservedKey == -1 ? "NULL" : reservedKey == -2 ? "SPACES" : "ZERO_LEN";
            return lookupFallback(options.getOrDefault(option, "ERROR"), value, options,
                    "HASH_LOOKUP needs reserved key " + reservedKey + " or " + option + "=PRESERVE/NULL/REDACT");
        }

        String prepared = lookupKey(sourceValue, options);
        if (prepared.isEmpty()) {
            String reserved = table.reserved().get(-2);
            if (reserved != null) return applyCase(selectLookupColumn(reserved, options), options.get("OUTPUT"));
            return lookupFallback(options.getOrDefault("SPACES", "ERROR"), value, options,
                    "HASH_LOOKUP input became blank after trim rules");
        }
        String identity = lookupIdentity(sourceSpec);
        String seed = options.getOrDefault("SEED", "0");
        int index = Determinism.pick(secret, "hash-lookup|" + identity + "|seed=" + seed, prepared, table.values().size());
        return applyCase(selectLookupColumn(table.values().get(index), options), options.get("OUTPUT"));
    }

    private String lookupDefinition(String sourceSpec, boolean useCache) {
        if (sourceSpec == null || sourceSpec.isBlank())
            throw new IllegalStateException("Lookup param1 needs inline values, a seedlist file, or @value-list");
        String source = sourceSpec.trim();
        if (source.startsWith("@")) {
            MaskLookupProvider provider = lookupProvider;
            if (provider == null) throw new IllegalStateException("Governed masking value-list registry is unavailable");
            return provider.valuesFor(source.substring(1), useCache);
        }
        if (source.contains("|") || source.contains("=>") || source.contains("=")) return source;
        return String.join("|", substitutionList(source));
    }

    private static Map<String, String> lookupOptions(String spec) {
        Map<String, String> options = new LinkedHashMap<>();
        if (spec == null || spec.isBlank()) return options;
        for (String raw : spec.split(";")) {
            String part = raw.trim();
            if (part.isEmpty()) continue;
            int equals = part.indexOf('=');
            if (equals < 0) options.put(part.toUpperCase(Locale.ROOT), "TRUE");
            else options.put(part.substring(0, equals).trim().toUpperCase(Locale.ROOT), part.substring(equals + 1).trim());
        }
        return options;
    }

    private static boolean lookupUsesCache(Map<String, String> options) {
        return !options.containsKey("NOCACHE") && !"OFF".equalsIgnoreCase(options.get("CACHE"));
    }

    private static String lookupSourceValue(String value, Map<String, String> options, MaskContext ctx) {
        String sourceColumns = options.get("SOURCE");
        if (sourceColumns == null || sourceColumns.isBlank()) return value;
        if (ctx == null) throw new IllegalStateException("Lookup SOURCE columns need row context");
        String join = options.getOrDefault("JOIN", "~");
        List<String> values = new ArrayList<>();
        for (String column : sourceColumns.split(",")) {
            String name = column.trim();
            if (name.isEmpty()) continue;
            String source = ctx.original(name);
            values.add(source == null ? "<NULL>" : source);
        }
        if (values.isEmpty()) throw new IllegalStateException("Lookup SOURCE must name at least one column");
        return String.join(join, values);
    }

    /**
     * Multi-column destination support (Optim HASH_LOOKUP dest=/values= equivalent). A lookup row may carry
     * several replacement columns separated by VCOLSEP (default '~'), e.g. "Olivia~Johnson". Each destination
     * column names its slice with VALUE=n (1-based); sibling HASH_LOOKUP rules using the same source, seed and
     * lookup rows all hash to the SAME row, so column 1 goes to one destination and column 2 to another and the
     * generated identity stays coherent across the row. VALUE absent (or =1 on a single-value row) is the
     * original single-column behaviour, so existing lookups are unaffected.
     */
    private static String selectLookupColumn(String rowValue, Map<String, String> options) {
        if (rowValue == null) return null;
        String selector = options.get("VALUE");
        if (selector == null || selector.isBlank()) return rowValue;
        int column;
        try { column = Integer.parseInt(selector.trim()); }
        catch (NumberFormatException e) { throw new IllegalStateException("HASH_LOOKUP VALUE must be a 1-based column number", e); }
        if (column < 1) throw new IllegalStateException("HASH_LOOKUP VALUE must be 1 or greater");
        String separator = options.getOrDefault("VCOLSEP", "~");
        String[] columns = rowValue.split(java.util.regex.Pattern.quote(separator), -1);
        if (column > columns.length)
            throw new IllegalStateException("HASH_LOOKUP VALUE=" + column + " but the lookup row has only " + columns.length + " column(s)");
        return columns[column - 1].trim();
    }

    private static String specialLookupKey(String value) {
        if (value == null) return "<NULL>";
        if (value.isEmpty()) return "<EMPTY>";
        if (value.trim().isEmpty()) return "<SPACES>";
        return null;
    }

    private static String lookupKey(String value, Map<String, String> options) {
        String special = specialLookupKey(value);
        if (special != null) return special;
        if (value.equalsIgnoreCase("<NULL>") || value.equalsIgnoreCase("<EMPTY>") || value.equalsIgnoreCase("<SPACES>"))
            return value.toUpperCase(Locale.ROOT);
        String out = value;
        String trim = options.getOrDefault("TRIM", "NONE").toUpperCase(Locale.ROOT);
        if (trim.equals("TRUE") || trim.equals("BOTH")) out = out.trim();
        else if (trim.equals("LEFT")) out = out.replaceFirst("^\\s+", "");
        else if (trim.equals("RIGHT")) out = out.replaceFirst("\\s+$", "");
        String trimChars = options.get("TRIM_CHARS");
        if (trimChars != null && !trimChars.isEmpty()) {
            String chars = trimChars.replace("<SPACE>", " ");
            StringBuilder kept = new StringBuilder(out.length());
            for (char c : out.toCharArray()) if (chars.indexOf(c) < 0) kept.append(c);
            out = kept.toString();
        }
        String caseMode = options.getOrDefault("CASE", options.containsKey("UPPER") ? "UPPER" : "SENSITIVE");
        if (caseMode.equalsIgnoreCase("UPPER") || caseMode.equalsIgnoreCase("INSENSITIVE")) out = out.toUpperCase(Locale.ROOT);
        else if (caseMode.equalsIgnoreCase("LOWER")) out = out.toLowerCase(Locale.ROOT);
        return out;
    }

    private static LookupPair lookupPair(String raw) {
        int arrow = raw.indexOf("=>");
        if (arrow >= 0) return new LookupPair(raw.substring(0, arrow).trim(), raw.substring(arrow + 2).trim());
        int equals = raw.indexOf('=');
        return equals < 0 ? null : new LookupPair(raw.substring(0, equals).trim(), raw.substring(equals + 1).trim());
    }

    private static String lookupFallback(String actionSpec, String original, Map<String, String> options, String error) {
        String action = actionSpec == null ? "ERROR" : actionSpec.trim().toUpperCase(Locale.ROOT);
        return switch (action) {
            case "PRESERVE", "IGNORE" -> original;
            case "NULL", "NULLIFY" -> null;
            case "REDACT" -> original == null ? null : redact(original, "*", "KEEP_FIRST:0");
            case "DEFAULT" -> {
                if (!options.containsKey("DEFAULT")) throw new IllegalStateException("Lookup DEFAULT action needs DEFAULT=value");
                yield options.get("DEFAULT");
            }
            case "ERROR" -> throw new IllegalStateException(error);
            default -> throw new IllegalStateException("Unknown lookup fallback action: " + actionSpec);
        };
    }

    private static HashLookupTable hashLookupTable(String definition) {
        List<String> entries = Arrays.stream(definition.split("\\|", -1)).map(String::trim).filter(v -> !v.isEmpty()).toList();
        if (entries.isEmpty()) throw new IllegalStateException("HASH_LOOKUP has no replacement rows");
        boolean keyed = entries.stream().map(MaskingEngine::lookupPair).anyMatch(pair -> pair != null && pair.key().matches("-?\\d+"));
        if (!keyed) {
            if (entries.stream().anyMatch(entry -> lookupPair(entry) != null))
                throw new IllegalStateException("HASH_LOOKUP keyed rows must use sequential numeric keys");
            return new HashLookupTable(entries.stream().map(MaskingEngine::stripLookupWeight).toList(), Map.of());
        }

        TreeMap<Integer, String> positive = new TreeMap<>();
        Map<Integer, String> reserved = new LinkedHashMap<>();
        for (String entry : entries) {
            LookupPair pair = lookupPair(entry);
            if (pair == null || !pair.key().matches("-?\\d+"))
                throw new IllegalStateException("HASH_LOOKUP cannot mix keyed and unkeyed rows");
            int key;
            try { key = Integer.parseInt(pair.key()); }
            catch (NumberFormatException e) { throw new IllegalStateException("HASH_LOOKUP key is outside integer range", e); }
            if (key > 0) {
                if (positive.putIfAbsent(key, pair.value()) != null) throw new IllegalStateException("HASH_LOOKUP contains duplicate key " + key);
            } else if (key == -1 || key == -2 || key == -3) {
                if (reserved.putIfAbsent(key, pair.value()) != null) throw new IllegalStateException("HASH_LOOKUP contains duplicate key " + key);
            } else throw new IllegalStateException("HASH_LOOKUP only supports positive keys and reserved keys -1, -2, -3");
        }
        if (positive.isEmpty()) throw new IllegalStateException("HASH_LOOKUP needs sequential positive rows 1..N");
        List<String> values = new ArrayList<>(positive.size());
        for (int key = 1; key <= positive.size(); key++) {
            String replacement = positive.get(key);
            if (replacement == null) throw new IllegalStateException("HASH_LOOKUP positive keys must be contiguous from 1 with no gaps");
            values.add(replacement);
        }
        return new HashLookupTable(List.copyOf(values), Map.copyOf(reserved));
    }

    private String lookupIdentity(String sourceSpec) {
        String identity = sourceSpec == null ? "" : sourceSpec.trim();
        if (identity.startsWith("@")) return "list:" + identity.substring(1).toLowerCase(Locale.ROOT);
        byte[] digest = Determinism.hmac(secret, "lookup-definition", identity);
        StringBuilder hex = new StringBuilder(16);
        for (int i = 0; i < 8; i++) hex.append(String.format("%02x", digest[i] & 0xff));
        return "inline:" + hex;
    }

    private record LookupPair(String key, String value) {}
    private record HashLookupTable(List<String> values, Map<Integer, String> reserved) {}

    private static String stripLookupWeight(String value) {
        int colon = value.lastIndexOf(':');
        return colon > 0 && value.substring(colon + 1).trim().matches("\\d+")
                ? value.substring(0, colon).trim() : value;
    }

    /** Configurable fail-closed redaction, including the common keep-first/keep-last modes. */
    private static String redact(String value, String maskSpec, String modeSpec) {
        char mask = maskSpec == null || maskSpec.isEmpty() ? '*' : maskSpec.charAt(0);
        String mode = modeSpec == null || modeSpec.isBlank() ? "FULL" : modeSpec.trim().toUpperCase(Locale.ROOT);
        if (mode.startsWith("STANDARD:")) {
            int length = parsePositiveSuffix(mode, 8);
            return String.valueOf(mask).repeat(Math.min(length, 4096));
        }
        if ("FULL".equals(mode)) return String.valueOf(mask).repeat(value.length());

        int keepFirst = mode.equals("KEEP_FIRST2") ? 2 : mode.equals("KEEP_FIRST2_LAST4") ? 2 :
                mode.startsWith("KEEP_FIRST:") ? parsePositiveSuffix(mode, 0) : 0;
        int keepLast = mode.equals("KEEP_LAST4") || mode.equals("KEEP_FIRST2_LAST4") ? 4 :
                mode.startsWith("KEEP_LAST:") ? parsePositiveSuffix(mode, 0) : 0;
        List<Integer> maskable = new java.util.ArrayList<>();
        for (int i = 0; i < value.length(); i++) if (Character.isLetterOrDigit(value.charAt(i))) maskable.add(i);
        Set<Integer> preserved = new java.util.HashSet<>();
        for (int i = 0; i < Math.min(keepFirst, maskable.size()); i++) preserved.add(maskable.get(i));
        for (int i = Math.max(0, maskable.size() - keepLast); i < maskable.size(); i++) preserved.add(maskable.get(i));
        StringBuilder out = new StringBuilder(value);
        for (int index : maskable) if (!preserved.contains(index)) out.setCharAt(index, mask);
        return out.toString();
    }

    private static int parsePositiveSuffix(String value, int fallback) {
        int colon = value.lastIndexOf(':');
        if (colon < 0) return fallback;
        try { return Math.max(0, Integer.parseInt(value.substring(colon + 1).trim())); }
        catch (Exception ignored) { return fallback; }
    }

    /** Deterministic numeric perturbation with source scale and optional regulatory bounds preserved. */
    private String numericNoise(String salt, String value, String noiseSpec, String clampSpec) {
        BigDecimal original;
        try {
            original = new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return characterMap(salt + "|numeric-fallback", value, null);
        }
        String spec = noiseSpec == null || noiseSpec.isBlank() ? "PERCENT:10" : noiseSpec.trim().toUpperCase(Locale.ROOT);
        if (!spec.startsWith("PERCENT") && !spec.startsWith("ABS"))
            throw new IllegalStateException("NUMERIC_NOISE param1 must be PERCENT:n or ABS:n");
        int colon = spec.indexOf(':');
        BigDecimal amount;
        try { amount = new BigDecimal(colon >= 0 ? spec.substring(colon + 1).trim() : "10").abs(); }
        catch (NumberFormatException e) { throw new IllegalStateException("NUMERIC_NOISE amount must be numeric", e); }
        BigDecimal maximum = spec.startsWith("ABS") ? amount : original.abs().multiply(amount).movePointLeft(2);
        Random random = Determinism.rng(secret, salt + "|numeric-noise", normalize(value));
        BigDecimal signed = BigDecimal.valueOf(random.nextDouble() * 2.0 - 1.0);
        BigDecimal result = original.add(maximum.multiply(signed));
        BigDecimal[] bounds = decimalBounds(clampSpec);
        if (bounds != null) result = result.max(bounds[0]).min(bounds[1]);
        return result.setScale(Math.max(0, original.scale()), RoundingMode.HALF_UP).toPlainString();
    }

    /** Delphix-style min/max replacement for numeric values. */
    private String minMax(String salt, String value, String minSpec, String maxSpec) {
        try {
            BigDecimal original = new BigDecimal(value.trim());
            BigDecimal min = new BigDecimal(String.valueOf(minSpec).trim());
            BigDecimal max = new BigDecimal(String.valueOf(maxSpec).trim());
            if (min.compareTo(max) > 0) throw new IllegalStateException("MIN_MAX param1 must be <= param2");
            Random random = Determinism.rng(secret, salt + "|min-max", normalize(value));
            BigDecimal result = min.add(max.subtract(min).multiply(BigDecimal.valueOf(random.nextDouble())));
            int scale = Math.max(0, original.scale());
            return result.setScale(scale, RoundingMode.HALF_UP).max(min).min(max).toPlainString();
        } catch (NumberFormatException e) {
            throw new IllegalStateException("MIN_MAX needs numeric value, param1 minimum, and param2 maximum", e);
        }
    }

    private static BigDecimal[] decimalBounds(String spec) {
        if (spec == null || spec.isBlank()) return null;
        String[] parts = spec.split(":", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank())
            throw new IllegalStateException("Numeric clamp must be min:max");
        BigDecimal min;
        BigDecimal max;
        try {
            min = new BigDecimal(parts[0].trim());
            max = new BigDecimal(parts[1].trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Numeric clamp bounds must be numbers", e);
        }
        if (min.compareTo(max) > 0) throw new IllegalStateException("Numeric clamp minimum must be <= maximum");
        return new BigDecimal[]{min, max};
    }

    private String bankAccount(String salt, String value, String modeSpec) {
        String mode = modeSpec == null || modeSpec.isBlank() ? "KEEP_LAST4" : modeSpec.trim().toUpperCase(Locale.ROOT);
        if ("REDACT".equals(mode)) return redact(value, "*", "KEEP_FIRST:0");
        if ("FORMAT_PRESERVE".equals(mode)) return formatPreserve(salt + "|account", value, false);
        String digits = value.replaceAll("\\D", "");
        if (digits.isEmpty()) return characterMap(salt + "|account", value, null);
        int keep = Math.min(4, digits.length());
        Random random = Determinism.rng(secret, salt + "|account", digits);
        StringBuilder generated = new StringBuilder(digits.length());
        for (int i = 0; i < digits.length() - keep; i++) generated.append((char) ('0' + random.nextInt(10)));
        generated.append(digits.substring(digits.length() - keep));
        return reinsertDigits(value, generated.toString());
    }

    private String iban(String salt, String value, String modeSpec, String formatSpec) {
        String compact = value.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        if (compact.length() < 15 || compact.length() > 34 || !compact.substring(0, 2).matches("[A-Z]{2}"))
            return characterMap(salt + "|iban-fallback", value, null);
        String country = compact.substring(0, 2);
        if ("RANDOM_COUNTRY".equalsIgnoreCase(modeSpec)) {
            String[] safe = {"GB", "DE", "FR", "ES", "IT", "NL"};
            country = safe[Determinism.pick(secret, salt + "|iban-country", compact, safe.length)];
        }
        Random random = Determinism.rng(secret, salt + "|iban", compact);
        StringBuilder bban = new StringBuilder(compact.length() - 4);
        for (int i = 4; i < compact.length(); i++) {
            char original = compact.charAt(i);
            bban.append(Character.isLetter(original) ? (char) ('A' + random.nextInt(26)) : (char) ('0' + random.nextInt(10)));
        }
        String checkBase = bban + country + "00";
        int check = 98 - ibanMod97(checkBase);
        String generated = country + String.format("%02d", check) + bban;
        return "COMPACT".equalsIgnoreCase(formatSpec) ? generated : reinsertAlnum(value, generated);
    }

    private static int ibanMod97(String value) {
        int remainder = 0;
        for (char c : value.toCharArray()) {
            String digits = Character.isLetter(c) ? String.valueOf(Character.toUpperCase(c) - 'A' + 10) : String.valueOf(c);
            for (char d : digits.toCharArray()) remainder = (remainder * 10 + (d - '0')) % 97;
        }
        return remainder;
    }

    private String swiftBic(String salt, String value, String modeSpec) {
        String compact = value.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        if (!compact.matches("[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?"))
            return characterMap(salt + "|bic-fallback", value, null);
        Random random = Determinism.rng(secret, salt + "|bic", compact);
        StringBuilder generated = new StringBuilder();
        for (int i = 0; i < 4; i++) generated.append((char) ('A' + random.nextInt(26)));
        if ("RANDOM_COUNTRY".equalsIgnoreCase(modeSpec)) {
            String[] safe = {"US", "GB", "DE", "FR", "CA", "AU"};
            generated.append(safe[random.nextInt(safe.length)]);
        } else generated.append(compact, 4, 6);
        while (generated.length() < compact.length()) generated.append(randomAlphaNumeric(random));
        return generated.toString();
    }

    private String abaRouting(String salt, String value, String modeSpec) {
        String digits = value.replaceAll("\\D", "");
        if (digits.length() != 9) return formatPreserve(salt + "|aba-fallback", value, false);
        Random random = Determinism.rng(secret, salt + "|aba", digits);
        String prefix = digits.substring(0, 2);
        if (!"PRESERVE_FED_DISTRICT".equalsIgnoreCase(modeSpec) || !validAbaPrefix(prefix)) {
            String[] prefixes = {"01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11", "12",
                    "21", "22", "23", "24", "25", "26", "27", "28", "29", "30", "31", "32"};
            prefix = prefixes[random.nextInt(prefixes.length)];
        }
        StringBuilder body = new StringBuilder(prefix);
        while (body.length() < 8) body.append((char) ('0' + random.nextInt(10)));
        int[] weights = {3, 7, 1, 3, 7, 1, 3, 7};
        int sum = 0;
        for (int i = 0; i < 8; i++) sum += (body.charAt(i) - '0') * weights[i];
        body.append((char) ('0' + ((10 - sum % 10) % 10)));
        return reinsertDigits(value, body.toString());
    }

    private static boolean validAbaPrefix(String prefix) {
        int value = Integer.parseInt(prefix);
        return value <= 12 || (value >= 21 && value <= 32) || (value >= 61 && value <= 72) || value == 80;
    }

    private String nationalId(String salt, String value, String countrySpec, String formatSpec) {
        String country = countrySpec == null || countrySpec.isBlank() ? "GENERIC" : countrySpec.trim().toUpperCase(Locale.ROOT);
        if ("US".equals(country)) return ssn(salt + "|nid.us", value, "VALID_RANDOM_AREA", formatSpec);
        String compact = value.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        Random random = Determinism.rng(secret, salt + "|nid." + country.toLowerCase(Locale.ROOT), compact);
        if ("CA".equals(country) && compact.matches("\\d{9}")) {
            StringBuilder body = new StringBuilder();
            for (int i = 0; i < 8; i++) body.append((char) ('0' + random.nextInt(10)));
            String generated = body.toString() + Luhn.checkDigit(body.toString());
            return reinsertDigits(value, generated);
        }
        if ("UK".equals(country) && compact.matches("[A-Z]{2}\\d{6}[A-D]")) {
            String first = "ABCEGHJKLMNPRSTWXYZ";
            String second = "ABCEGHJKLMNPRSTWXYZ";
            StringBuilder generated = new StringBuilder()
                    .append(first.charAt(random.nextInt(first.length())))
                    .append(second.charAt(random.nextInt(second.length())));
            for (int i = 0; i < 6; i++) generated.append((char) ('0' + random.nextInt(10)));
            generated.append("ABCD".charAt(random.nextInt(4)));
            return reinsertAlnum(value, generated.toString());
        }
        return characterMap(salt + "|nid.generic", value, null);
    }

    private String ipAddress(String salt, String value, String modeSpec) {
        String trimmed = value.trim();
        Random random = Determinism.rng(secret, salt + "|ip", trimmed.toLowerCase(Locale.ROOT));
        if (trimmed.contains(":")) {
            if (!trimmed.matches("[0-9A-Fa-f:]+")) return characterMap(salt + "|ip-fallback", value, null);
            return String.format("2001:db8:%x:%x:%x:%x:%x:%x", random.nextInt(65536), random.nextInt(65536),
                    random.nextInt(65536), random.nextInt(65536), random.nextInt(65536), random.nextInt(65536));
        }
        String[] parts = trimmed.split("\\.", -1);
        if (parts.length != 4 || !validIpv4(parts)) return characterMap(salt + "|ip-fallback", value, null);
        int first = Integer.parseInt(parts[0]), second = Integer.parseInt(parts[1]);
        boolean preservePrivate = !"SAFE_TEST_RANGE".equalsIgnoreCase(modeSpec) &&
                (first == 10 || (first == 172 && second >= 16 && second <= 31) || (first == 192 && second == 168));
        if (preservePrivate && first == 10)
            return "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + (1 + random.nextInt(254));
        if (preservePrivate && first == 172)
            return "172." + (16 + random.nextInt(16)) + "." + random.nextInt(256) + "." + (1 + random.nextInt(254));
        if (preservePrivate)
            return "192.168." + random.nextInt(256) + "." + (1 + random.nextInt(254));
        String[] documentation = {"192.0.2", "198.51.100", "203.0.113"};
        return documentation[random.nextInt(documentation.length)] + "." + (1 + random.nextInt(254));
    }

    private static boolean validIpv4(String[] parts) {
        try {
            for (String part : parts) {
                if (part.isBlank() || Integer.parseInt(part) < 0 || Integer.parseInt(part) > 255) return false;
            }
            return true;
        } catch (NumberFormatException e) { return false; }
    }

    private String macAddress(String salt, String value, String modeSpec) {
        String compact = value.replaceAll("[:-]", "");
        if (!compact.matches("(?i)[0-9a-f]{12}")) return characterMap(salt + "|mac-fallback", value, null);
        Random random = Determinism.rng(secret, salt + "|mac", compact.toLowerCase(Locale.ROOT));
        int[] bytes = new int[6];
        for (int i = 0; i < bytes.length; i++) bytes[i] = random.nextInt(256);
        if ("PRESERVE_OUI".equalsIgnoreCase(modeSpec)) {
            for (int i = 0; i < 3; i++) bytes[i] = Integer.parseInt(compact.substring(i * 2, i * 2 + 2), 16);
        } else bytes[0] = (bytes[0] | 0x02) & 0xfe; // locally administered, unicast
        char separator = value.contains("-") ? '-' : ':';
        boolean upper = value.equals(value.toUpperCase(Locale.ROOT));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) out.append(separator);
            out.append(String.format(upper ? "%02X" : "%02x", bytes[i]));
        }
        return out.toString();
    }

    private String uuid(String salt, String value) {
        String compact = value.replaceAll("[{}-]", "");
        if (!compact.matches("(?i)[0-9a-f]{32}")) return characterMap(salt + "|uuid-fallback", value, null);
        byte[] bytes = java.util.Arrays.copyOf(Determinism.hmac(secret, salt + "|uuid", compact.toLowerCase(Locale.ROOT)), 16);
        bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x40);
        bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
        StringBuilder hex = new StringBuilder(32);
        for (byte b : bytes) hex.append(String.format("%02x", b & 0xff));
        String raw = hex.toString();
        String formatted = raw.substring(0, 8) + "-" + raw.substring(8, 12) + "-" + raw.substring(12, 16) + "-" +
                raw.substring(16, 20) + "-" + raw.substring(20);
        if (value.equals(value.toUpperCase(Locale.ROOT))) formatted = formatted.toUpperCase(Locale.ROOT);
        return value.startsWith("{") && value.endsWith("}") ? "{" + formatted + "}" : formatted;
    }

    private static char randomAlphaNumeric(Random random) {
        int n = random.nextInt(36);
        return n < 26 ? (char) ('A' + n) : (char) ('0' + n - 26);
    }

    private static String reinsertAlnum(String original, String generated) {
        StringBuilder out = new StringBuilder(original.length());
        int index = 0;
        for (int i = 0; i < original.length(); i++) {
            char source = original.charAt(i);
            if (!Character.isLetterOrDigit(source)) out.append(source);
            else if (index < generated.length()) {
                char replacement = generated.charAt(index++);
                out.append(Character.isLowerCase(source) ? Character.toLowerCase(replacement) : replacement);
            }
        }
        return index == generated.length() ? out.toString() : generated;
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

    // ---------- governed lookup and user-defined Lua registries ----------

    private volatile MaskLookupProvider lookupProvider;

    /** Wired by the host application (DB-backed governed Value Lists). */
    public void setLookupProvider(MaskLookupProvider provider) { this.lookupProvider = provider; }

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
