package io.forgetdm.discovery;

import io.forgetdm.core.util.PiiPatterns;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscoveryMaskRecommendationTest {

    @Test void regulatedIdentifiersUseDedicatedMaskers() {
        assertEquals("IBAN", PiiPatterns.SUGGESTED.get("IBAN"));
        assertEquals("SWIFT_BIC", PiiPatterns.SUGGESTED.get("SWIFT_BIC"));
        assertEquals("BANK_ACCOUNT", PiiPatterns.SUGGESTED.get("BANK_ACCOUNT"));
        assertEquals("ABA_ROUTING", PiiPatterns.SUGGESTED.get("ROUTING"));
        assertEquals("NATIONAL_ID", PiiPatterns.SUGGESTED.get("TAX_ID"));
        assertEquals("TOKENIZE", PiiPatterns.SUGGESTED.get("USERNAME"));
    }

    @Test void taxIdentifiersDoNotGetMisclassifiedAsSsnByName() {
        assertFalse(PiiPatterns.NAME_HINTS.get("SSN").matcher("tax_id").find());
        assertTrue(PiiPatterns.NAME_HINTS.get("TAX_ID").matcher("tax_id").find());
        assertTrue(PiiPatterns.NAME_HINTS.get("TAX_ID").matcher("national_id").find());
    }

    @Test void typeSafetyKeepsSpecializedNumericOutputWritable() {
        assertEquals("ABA_ROUTING", DiscoveryService.typeSafeFunction("ABA_ROUTING", "BIGINT"));
        assertEquals("BANK_ACCOUNT", DiscoveryService.typeSafeFunction("BANK_ACCOUNT", "NUMERIC"));
        assertEquals("NUMERIC_NOISE", DiscoveryService.typeSafeFunction("NUMERIC_NOISE", "DECIMAL"));
        assertEquals("FORMAT_PRESERVE", DiscoveryService.typeSafeFunction("IBAN", "BIGINT"));
        assertEquals("IP_ADDRESS", DiscoveryService.typeSafeFunction("IP_ADDRESS", "VARCHAR"));
    }

    @Test void discoverySuppliesSafeUsableDefaults() {
        assertEquals("PRESERVE_COUNTRY", DiscoveryService.defaultParam1("IBAN", "IBAN"));
        assertEquals("PRESERVE_FORMAT", DiscoveryService.defaultParam2("IBAN", "IBAN"));
        assertEquals("SAFE_TEST_RANGE", DiscoveryService.defaultParam1("IP_ADDRESS", "IP_ADDRESS"));
        assertEquals("F|M|X", DiscoveryService.defaultParam1("SECURE_LOOKUP", "GENDER"));
        assertEquals("UPPER", DiscoveryService.defaultParam2("SECURE_LOOKUP", "GENDER"));
    }
}
