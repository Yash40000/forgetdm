package io.forgetdm.core.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Dual-signal PII detection (name-based + value-based).
 * confidence = 0.6 * nameSignal + 0.4 * valueSignal
 */
public final class PiiPatterns {
    private PiiPatterns() {}

    /** piiType -> column-name regex */
    public static final Map<String, Pattern> NAME_HINTS = new LinkedHashMap<>();
    /** piiType -> value regex */
    public static final Map<String, Pattern> VALUE_HINTS = new LinkedHashMap<>();
    /** piiType -> suggested MaskFunction name */
    public static final Map<String, String> SUGGESTED = new LinkedHashMap<>();

    static {
        name("EMAIL", "(^|_)(e?mail)(_|$)|email_address");
        name("SSN", "(^|_)(ssn|social_sec|social_security)(_|$)?");
        name("CREDIT_CARD", "(card_num|card_no|cc_num|ccn|credit_card|pan)(_|$)?");
        name("FIRST_NAME", "(^|_)(first_?name|fname|given_?name)(_|$)?");
        name("LAST_NAME", "(^|_)(last_?name|lname|sur_?name|family_?name)(_|$)?");
        name("FULL_NAME", "(^|_)(full_?name|customer_?name|name)($)");
        name("DOB", "(^|_)(dob|birth_?date|date_of_birth|birthdate)(_|$)?");
        name("PHONE", "(^|_)(phone|mobile|cell|contact_no|telephone)(_|$)?");
        name("FULL_ADDRESS", "(^|_)(full_?address|mailing_?address|billing_?address|shipping_?address|address_full)(_|$)?|^(address|addr)$");
        name("ADDRESS", "(^|_)(address|addr|street)(_|$)?|address_line");
        name("CITY", "(^|_)city(_|$)?");
        name("ZIP", "(^|_)(zip|postal|pincode|postcode)(_|$)?");
        name("STATE", "(^|_)state(_|$)?");
        name("COMPANY", "(^|_)(company|employer|organization|org_name)(_|$)?");
        // Banking / financial & other regulated identifiers
        name("IBAN", "(^|_)iban(_|$)?");
        name("SWIFT_BIC", "(^|_)(swift|bic|swift_?code|bic_?code)(_|$)?");
        name("BANK_ACCOUNT", "(^|_)(account_?no|account_?num|acct_?no|acct_?num|bank_?account|account_?number|iban)(_|$)?");
        name("ROUTING", "(^|_)(routing|aba|sort_?code|ifsc)(_|$)?");
        name("TAX_ID", "(^|_)(national_?id|tax_?id|tin|ein|vat|pan_?no|pan_?number)(_|$)?");
        name("IP_ADDRESS", "(^|_)(ip|ip_?addr|ip_?address|ipv4|ipv6)(_|$)?");
        name("PASSPORT", "(^|_)(passport|passport_?no|passport_?number)(_|$)?");
        name("DRIVER_LICENSE", "(^|_)(driver_?license|dl_?no|license_?no|licence_?no)(_|$)?");
        name("USERNAME", "(^|_)(username|user_?name|login|userid|user_?id)(_|$)?");
        name("PASSWORD", "(^|_)(password|passwd|pwd|secret|api_?key|token)(_|$)?");
        name("MAC_ADDRESS", "(^|_)(mac|mac_?addr|mac_?address)(_|$)?");
        name("GENDER", "(^|_)(gender|sex)(_|$)?");

        value("EMAIL", "^[\\w.+-]+@[\\w-]+\\.[\\w.]+$");
        value("SSN", "^\\d{3}-\\d{2}-\\d{4}$");                       // require the dashed shape (bare 9-digit is too id-like)
        value("CREDIT_CARD", "^[\\d][\\d -]{11,21}[\\d]$");
        value("DOB", "^\\d{4}-\\d{2}-\\d{2}$|^\\d{2}/\\d{2}/\\d{4}$");
        value("PHONE", "^\\+?[\\d().\\- ]{7,18}$");
        value("ZIP", "^\\d{5}-\\d{4}$");                              // dashed ZIP+4; bare 5/6 digits stay name-driven only
        value("IBAN", "^[A-Z]{2}\\d{2}[A-Z0-9]{10,30}$");
        value("SWIFT_BIC", "^[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?$");
        value("IP_ADDRESS", "^(\\d{1,3}\\.){3}\\d{1,3}$|^([0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}$");
        value("MAC_ADDRESS", "^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$");

        SUGGESTED.put("EMAIL", "EMAIL");
        SUGGESTED.put("SSN", "SSN");
        SUGGESTED.put("CREDIT_CARD", "CREDIT_CARD");
        SUGGESTED.put("FIRST_NAME", "FIRST_NAME");
        SUGGESTED.put("LAST_NAME", "LAST_NAME");
        SUGGESTED.put("FULL_NAME", "FULL_NAME");
        SUGGESTED.put("DOB", "DOB_AGE_BAND");
        SUGGESTED.put("PHONE", "PHONE");
        SUGGESTED.put("FULL_ADDRESS", "ADDRESS_US");
        SUGGESTED.put("ADDRESS", "ADDRESS_STREET");
        SUGGESTED.put("CITY", "CITY_STATE_ZIP");
        SUGGESTED.put("ZIP", "CITY_STATE_ZIP");
        SUGGESTED.put("STATE", "CITY_STATE_ZIP");
        SUGGESTED.put("COMPANY", "COMPANY");
        // No dedicated maskers for these yet — fall back to safe, format-aware functions.
        SUGGESTED.put("IBAN", "IBAN");
        SUGGESTED.put("SWIFT_BIC", "SWIFT_BIC");
        SUGGESTED.put("BANK_ACCOUNT", "BANK_ACCOUNT");
        SUGGESTED.put("ROUTING", "ABA_ROUTING");
        SUGGESTED.put("TAX_ID", "NATIONAL_ID");
        SUGGESTED.put("IP_ADDRESS", "IP_ADDRESS");
        SUGGESTED.put("PASSPORT", "CHARACTER_MAP");
        SUGGESTED.put("DRIVER_LICENSE", "CHARACTER_MAP");
        SUGGESTED.put("USERNAME", "TOKENIZE");
        SUGGESTED.put("PASSWORD", "NULLIFY");
        SUGGESTED.put("MAC_ADDRESS", "MAC_ADDRESS");
        SUGGESTED.put("GENDER", "SECURE_LOOKUP");
    }

    private static void name(String t, String re) { NAME_HINTS.put(t, Pattern.compile(re, Pattern.CASE_INSENSITIVE)); }
    private static void value(String t, String re) { VALUE_HINTS.put(t, Pattern.compile(re)); }

    /** Credit-card value check stronger than regex: digits length + Luhn. */
    public static boolean looksLikeCard(String v) {
        if (v == null) return false;
        String d = v.replaceAll("[ -]", "");
        return d.matches("\\d{12,19}") && Luhn.isValid(d);
    }
}
