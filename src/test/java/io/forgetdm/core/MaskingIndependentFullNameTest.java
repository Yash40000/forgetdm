package io.forgetdm.core;

import io.forgetdm.core.mask.MaskContext;
import io.forgetdm.core.mask.MaskFunction;
import io.forgetdm.core.mask.MaskingEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MaskingIndependentFullNameTest {
    private final MaskingEngine engine = new MaskingEngine("mask006-test-secret");

    @Test
    void standaloneFullNameIsDeterministicAndDoesNotPassThroughSource() {
        String first = mask("Jane Q Doe", new MaskContext(1));
        String replay = mask("Jane Q Doe", new MaskContext(99));

        assertEquals(first, replay);
        assertNotEquals("Jane Q Doe", first);
    }

    @Test
    void unrelatedRowContextDoesNotChangeIndependentFullName() {
        String expected = mask("Jane Q Doe", new MaskContext(1));
        MaskContext context = new MaskContext(2);
        context.masked.put("email", "masked@example.test");
        context.masked.put("status", "ACTIVE");

        assertEquals(expected, mask("Jane Q Doe", context));
    }

    @Test
    void oneMissingNameComponentKeepsFullNameInIndependentMode() {
        String expected = mask("Jane Q Doe", new MaskContext(1));
        MaskContext firstOnly = new MaskContext(2);
        firstOnly.masked.put("first_name", "Avery");
        MaskContext lastOnly = new MaskContext(3);
        lastOnly.masked.put("last_name", "Stone");

        assertEquals(expected, mask("Jane Q Doe", firstOnly));
        assertEquals(expected, mask("Jane Q Doe", lastOnly));
    }

    @Test
    void nullAndEmptyRemainUnchangedEvenWhenSiblingNamesExist() {
        MaskContext context = new MaskContext(4);
        context.masked.put("first_name", "Avery");
        context.masked.put("last_name", "Stone");

        assertNull(engine.mask(MaskFunction.FULL_NAME, "name.full", null,
                "FIRST MIDDLE LAST", "PROPER", context));
        assertEquals("", engine.mask(MaskFunction.FULL_NAME, "name.full", "",
                "FIRST MIDDLE LAST", "PROPER", context));
    }

    @Test
    void independentModeStillHonorsFormatAndCase() {
        String value = engine.mask(MaskFunction.FULL_NAME, "name.full", "Jane Q Doe",
                "LAST, FIRST MIDDLE", "UPPER", new MaskContext(5));

        assertEquals(value.toUpperCase(), value);
        assertEquals(3, value.replace(",", "").split("\\s+").length);
    }

    @Test
    void differentSourceNamesDoNotCollapseInReferenceSet() {
        assertNotEquals(mask("Jane Q Doe", new MaskContext(1)),
                mask("Maria R Smith", new MaskContext(2)));
    }

    private String mask(String value, MaskContext context) {
        return engine.mask(MaskFunction.FULL_NAME, "name.full", value,
                "FIRST MIDDLE LAST", "PROPER", context);
    }
}
