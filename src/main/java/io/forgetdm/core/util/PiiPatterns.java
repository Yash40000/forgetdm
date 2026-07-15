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
        // Healthcare / payment / privacy-regulation scope identifiers.
        name("MEDICAL_RECORD_NUMBER", "(^|_)(mrn|medical_?record|patient_?record|health_?record)(_|$)?");
        name("HEALTH_PLAN_ID", "(^|_)(health_?plan|member_?health_?id|insurance_?member|beneficiary_?id)(_|$)?");
        name("DIAGNOSIS_CODE", "(^|_)(diagnosis|diagnostic|icd_?10?|icd_?code)(_|$)?");
        name("PRESCRIPTION_ID", "(^|_)(prescription|rx_?id|rx_?number|medication_?order)(_|$)?");
        name("BIOMETRIC_ID", "(^|_)(biometric|fingerprint|face_?template|voice_?print|retina)(_|$)?");
        name("GENETIC_DATA", "(^|_)(genetic|genomic|dna_?profile|gene_?sequence)(_|$)?");
        name("CVV", "(^|_)(cvv|cvc|card_?security_?code)(_|$)?");
        name("CARD_EXPIRY", "(^|_)(card_?expiry|card_?expiration|expiry_?date|expiration_?date)(_|$)?");
        name("DEVICE_ID", "(^|_)(device_?id|advertising_?id|mobile_?device_?id)(_|$)?");
        name("COOKIE_ID", "(^|_)(cookie_?id|tracking_?id|browser_?id)(_|$)?");
        name("GEOLOCATION", "(^|_)(geo_?location|latitude|longitude|gps|location_?coordinates)(_|$)?");
        name("RACE_ETHNICITY", "(^|_)(race|ethnicity|ethnic_?origin)(_|$)?");
        name("RELIGION", "(^|_)(religion|religious_?belief)(_|$)?");
        // Additional regulated-data identifiers used by HIPAA, PCI DSS and privacy-law scope profiles.
        name("FAX", "(^|_)(fax|facsimile|fax_?number)(_|$)?");
        name("AGE", "(^|_)(age|patient_?age|member_?age|age_?years)(_|$)?");
        name("HEALTH_DATE", "(^|_)(admission|discharge|service|treatment|procedure|death)_?date(_|$)?");
        name("HEALTH_DATA", "(^|_)(medical_?condition|health_?condition|clinical_?note|lab_?result|allergy|treatment_?detail)(_|$)?");
        name("PERSON_ID", "(^|_)(patient|person|consumer|subject|resident)_?(id|identifier|number)(_|$)?");
        name("CERTIFICATE_LICENSE", "(^|_)(certificate|certification|professional_?license|licence|license)_?(id|no|num|number)?(_|$)?");
        name("VEHICLE_ID", "(^|_)(vin|vehicle_?(id|number)|license_?plate|licence_?plate|vehicle_?registration)(_|$)?");
        name("URL", "(^|_)(url|uri|website|web_?address|profile_?url)(_|$)?");
        name("PHOTO_IMAGE", "(^|_)(photo|photograph|facial_?image|profile_?image|patient_?image)(_|$)?");
        name("SIGNATURE", "(^|_)(signature|digital_?signature|signature_?image)(_|$)?");
        name("CARD_SERVICE_CODE", "(^|_)(card_?service_?code|service_?code)(_|$)?");
        name("FULL_TRACK_DATA", "(^|_)(full_?track|track_?1|track_?2|magnetic_?stripe|chip_?track)(_|$)?");
        name("PIN_BLOCK", "(^|_)(pin|pin_?block|encrypted_?pin|card_?pin)(_|$)");
        name("ACCOUNT_CREDENTIAL", "(^|_)(account_?login|login_?credential|authentication_?credential|access_?credential)(_|$)?");
        name("POLITICAL_OPINION", "(^|_)(political_?opinion|political_?affiliation|party_?affiliation)(_|$)?");
        name("UNION_MEMBERSHIP", "(^|_)(union_?membership|trade_?union|labor_?union)(_|$)?");
        name("SEXUAL_ORIENTATION", "(^|_)(sexual_?orientation|sex_?life)(_|$)?");
        name("CRIMINAL_RECORD", "(^|_)(criminal_?record|criminal_?conviction|offense|offence)(_|$)?");
        name("EDUCATION_RECORD", "(^|_)(education_?record|student_?record|transcript|academic_?record)(_|$)?");
        name("EMPLOYMENT_DATA", "(^|_)(employment_?history|employee_?record|job_?history|salary|compensation)(_|$)?");

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
        value("URL", "^(?i:https?://|www\\.)[^\\s]+$");
        value("VEHICLE_ID", "^[A-HJ-NPR-Z0-9]{17}$");

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
        SUGGESTED.put("MEDICAL_RECORD_NUMBER", "TOKENIZE");
        SUGGESTED.put("HEALTH_PLAN_ID", "TOKENIZE");
        SUGGESTED.put("DIAGNOSIS_CODE", "CHARACTER_MAP");
        SUGGESTED.put("PRESCRIPTION_ID", "TOKENIZE");
        SUGGESTED.put("BIOMETRIC_ID", "TOKENIZE");
        SUGGESTED.put("GENETIC_DATA", "TOKENIZE");
        SUGGESTED.put("CVV", "NULLIFY");
        SUGGESTED.put("CARD_EXPIRY", "CHARACTER_MAP");
        SUGGESTED.put("DEVICE_ID", "TOKENIZE");
        SUGGESTED.put("COOKIE_ID", "TOKENIZE");
        SUGGESTED.put("GEOLOCATION", "REDACT");
        SUGGESTED.put("RACE_ETHNICITY", "REDACT");
        SUGGESTED.put("RELIGION", "REDACT");
        SUGGESTED.put("FAX", "PHONE");
        SUGGESTED.put("AGE", "NUMERIC_NOISE");
        SUGGESTED.put("HEALTH_DATE", "DATE_SHIFT");
        SUGGESTED.put("HEALTH_DATA", "REDACT");
        SUGGESTED.put("PERSON_ID", "TOKENIZE");
        SUGGESTED.put("CERTIFICATE_LICENSE", "CHARACTER_MAP");
        SUGGESTED.put("VEHICLE_ID", "TOKENIZE");
        SUGGESTED.put("URL", "TOKENIZE");
        SUGGESTED.put("PHOTO_IMAGE", "NULLIFY");
        SUGGESTED.put("SIGNATURE", "NULLIFY");
        SUGGESTED.put("CARD_SERVICE_CODE", "CHARACTER_MAP");
        SUGGESTED.put("FULL_TRACK_DATA", "NULLIFY");
        SUGGESTED.put("PIN_BLOCK", "NULLIFY");
        SUGGESTED.put("ACCOUNT_CREDENTIAL", "NULLIFY");
        SUGGESTED.put("POLITICAL_OPINION", "REDACT");
        SUGGESTED.put("UNION_MEMBERSHIP", "REDACT");
        SUGGESTED.put("SEXUAL_ORIENTATION", "REDACT");
        SUGGESTED.put("CRIMINAL_RECORD", "REDACT");
        SUGGESTED.put("EDUCATION_RECORD", "REDACT");
        SUGGESTED.put("EMPLOYMENT_DATA", "REDACT");
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
