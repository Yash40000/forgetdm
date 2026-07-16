package io.forgetdm.discovery;

import io.forgetdm.core.util.PiiPatterns;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscoveryMaskRecommendationTest {

    @Test void creditCardValueSignalRequiresLuhnInsteadOfAnyLongDigitString() {
        Pattern looseCardPattern = PiiPatterns.VALUE_HINTS.get("CREDIT_CARD");
        assertFalse(DiscoveryService.valueMatches("CREDIT_CARD", looseCardPattern, "00000000000001"));
        assertTrue(DiscoveryService.valueMatches("CREDIT_CARD", looseCardPattern, "4111111111111111"));
    }

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
        assertEquals("0:365", DiscoveryService.defaultParam1("DATE_SHIFT", "CARD_EXPIRY"));
        assertEquals("F|M|X", DiscoveryService.defaultParam1("SECURE_LOOKUP", "GENDER"));
        assertEquals("UPPER", DiscoveryService.defaultParam2("SECURE_LOOKUP", "GENDER"));
    }

    @Test void narrowerProfileLocksExistingOutOfScopeClassification() {
        assertTrue(DiscoveryService.shouldLockExistingClassification(false, "SUGGESTED"));
        assertTrue(DiscoveryService.shouldLockExistingClassification(true, "APPROVED"));
        assertFalse(DiscoveryService.shouldLockExistingClassification(true, "SUGGESTED"));
    }

    @Test void generatedPolicySkipsOneSidedRelationshipMask() {
        Map<String, ClassificationEntity> classifications = new HashMap<>();
        classifications.put("child.customer_id", classification("FORMAT_PRESERVE", null, null));
        Set<String> unsafe = new TreeSet<>();

        DiscoveryService.addUnsafeRiPair(classifications, unsafe,
                "parent", "id", "child", "customer_id");

        assertEquals(Set.of("child.customer_id"), unsafe);
    }

    @Test void generatedPolicySkipsBothSidesWhenRelationshipMasksDiffer() {
        Map<String, ClassificationEntity> classifications = new HashMap<>();
        classifications.put("parent.id", classification("TOKENIZE", "A", null));
        classifications.put("child.customer_id", classification("TOKENIZE", "B", null));
        Set<String> unsafe = new TreeSet<>();

        DiscoveryService.addUnsafeRiPair(classifications, unsafe,
                "parent", "id", "child", "customer_id");

        assertEquals(Set.of("parent.id", "child.customer_id"), unsafe);
    }

    @Test void generatedPolicyKeepsMatchingRelationshipMasks() {
        ClassificationEntity parent = classification("TOKENIZE", "CUSTOMER", null);
        ClassificationEntity child = classification("TOKENIZE", "CUSTOMER", null);
        Map<String, ClassificationEntity> classifications = Map.of(
                "parent.id", parent,
                "child.customer_id", child);
        Set<String> unsafe = new TreeSet<>();

        DiscoveryService.addUnsafeRiPair(classifications, unsafe,
                "parent", "id", "child", "customer_id");

        assertTrue(DiscoveryService.sameEffectiveMask(parent, child));
        assertTrue(unsafe.isEmpty());
    }

    private static ClassificationEntity classification(String function, String param1, String param2) {
        ClassificationEntity entity = new ClassificationEntity();
        entity.setSuggestedFunction(function);
        entity.setSuggestedParam1(param1);
        entity.setSuggestedParam2(param2);
        entity.setPiiType("BANK_ACCOUNT");
        return entity;
    }
}
