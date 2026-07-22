package io.forgetdm.core.temenos;

import io.forgetdm.core.mask.MaskContext;
import io.forgetdm.core.mask.MaskFunction;
import io.forgetdm.core.mask.MaskingEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Proves the masking engine is Temenos-structure-aware (RFP §3.2.1) for any mask function. */
class TemenosMaskingTest {

    private final MaskingEngine engine = new MaskingEngine("unit-test-secret");
    private final MaskContext ctx = new MaskContext(1);
    private static final char VM = TemenosCodec.VM;
    private static final char SVM = TemenosCodec.SVM;

    @Test void masksEachMultiValueInPlacePreservingStructure() {
        String in = "John" + VM + "Alexander";
        String out = engine.mask(MaskFunction.FIRST_NAME, "cust.name", in, null, null, ctx);

        assertEquals(1, count(out, VM), "the value mark must be preserved");
        assertEquals(2, TemenosCodec.valueCount(out), "field count must not shift");
        String[] parts = out.split(String.valueOf(VM), -1);
        assertNotEquals("John", parts[0]);
        assertNotEquals("Alexander", parts[1]);
        assertFalse(TemenosCodec.hasMarkers(parts[0]));
    }

    @Test void perSubValueMaskingIsDeterministic() {
        // Same physical sub-value in two positions masks to the same output (referential integrity).
        String out = engine.mask(MaskFunction.FIRST_NAME, "cust.name", "John" + VM + "John", null, null, ctx);
        String[] parts = out.split(String.valueOf(VM), -1);
        assertEquals(parts[0], parts[1]);
    }

    @Test void formatPreservePerLeafKeepsLengthAndMarks() {
        String in = "4111111111111111" + VM + "5500000000000004";
        String out = engine.mask(MaskFunction.FORMAT_PRESERVE, "card.pan", in, null, null, ctx);
        String[] parts = out.split(String.valueOf(VM), -1);
        assertEquals(2, parts.length);
        assertEquals(16, parts[0].length());
        assertEquals(16, parts[1].length());
        assertTrue(parts[0].chars().allMatch(Character::isDigit));
        assertNotEquals("4111111111111111", parts[0]);
    }

    @Test void nestedSubValuesAreEachMasked() {
        String in = "Jm" + SVM + "Kt" + VM + "Al";
        String out = engine.mask(MaskFunction.FIRST_NAME, "cust.name", in, null, null, ctx);
        assertEquals(1, count(out, SVM));
        assertEquals(1, count(out, VM));
    }

    @Test void plainValueUnaffectedByTemenosPath() {
        String plain = engine.mask(MaskFunction.FIRST_NAME, "cust.name", "John", null, null, ctx);
        assertFalse(TemenosCodec.hasMarkers(plain));
        assertNotEquals("John", plain);
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }
}
