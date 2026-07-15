package io.forgetdm.core.util;

import io.forgetdm.core.mask.MaskFunction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiiPatternsTest {

    @Test
    void everyDetectionTypeHasAValidPolicyFunction() {
        assertTrue(PiiPatterns.NAME_HINTS.keySet().stream().allMatch(PiiPatterns.SUGGESTED::containsKey));
        assertTrue(PiiPatterns.VALUE_HINTS.keySet().stream().allMatch(PiiPatterns.SUGGESTED::containsKey));
        PiiPatterns.SUGGESTED.values().forEach(function ->
                assertDoesNotThrow(() -> MaskFunction.valueOf(function)));
    }

    @Test
    void sensitiveAuthenticationDataIsNeverPreserved() {
        assertEquals("NULLIFY", PiiPatterns.SUGGESTED.get("CVV"));
        assertEquals("NULLIFY", PiiPatterns.SUGGESTED.get("FULL_TRACK_DATA"));
        assertEquals("NULLIFY", PiiPatterns.SUGGESTED.get("PIN_BLOCK"));
        assertTrue(PiiPatterns.NAME_HINTS.get("PIN_BLOCK").matcher("card_pin").find());
        assertFalse(PiiPatterns.NAME_HINTS.get("PIN_BLOCK").matcher("pincode").find());
    }
}
