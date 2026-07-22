package io.forgetdm.policy;

import io.forgetdm.common.ApiException;
import io.forgetdm.core.mask.MaskContext;
import io.forgetdm.core.mask.MaskFunction;
import io.forgetdm.core.mask.MaskingEngine;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MaskingFunctionCatalogAcceptanceTest {
    private final MaskingEngine engine = scriptedEngine();

    @Test
    void everyBuiltInExecutesWithAValidReferenceConfiguration() {
        Map<MaskFunction, ValidCase> cases = validCases();
        assertEquals(EnumSet.allOf(MaskFunction.class), cases.keySet(),
                "Every catalog function needs a maintained reference case");

        cases.forEach((function, testCase) -> {
            MaskContext context = referenceContext();
            assertDoesNotThrow(() -> PolicyController.validateRuleConfig(
                    function, testCase.param1(), testCase.param2()), function.name());
            String output = assertDoesNotThrow(() -> engine.mask(function, "mask001." + function.name().toLowerCase(),
                    testCase.value(), testCase.param1(), testCase.param2(), context), function.name());
            if (function == MaskFunction.NULLIFY) assertNull(output);
            else assertNotNull(output, function.name() + " returned null for its valid reference case");
        });
    }

    @Test
    void everyFunctionIsExplicitlyClassifiedForInvalidParameterHandling() {
        Map<MaskFunction, Runnable> invalid = invalidCases();
        Set<MaskFunction> parameterFree = EnumSet.of(
                MaskFunction.REDACT_KEEP_LAST4,
                MaskFunction.UUID,
                MaskFunction.NULLIFY,
                MaskFunction.SEQUENCE,
                MaskFunction.PASSTHROUGH);
        Set<MaskFunction> classified = EnumSet.copyOf(parameterFree);
        classified.addAll(invalid.keySet());
        assertEquals(EnumSet.allOf(MaskFunction.class), classified,
                "Each function must reject malformed parameters or be explicitly parameter-free");
        invalid.forEach((function, invocation) -> assertThrows(RuntimeException.class,
                invocation::run, function.name() + " accepted malformed parameters"));
    }

    @Test
    void everyFunctionHasAnExplicitNullAndEmptyInputContract() {
        EnumSet<MaskFunction> sourceCreating = EnumSet.of(
                MaskFunction.FIXED,
                MaskFunction.NULLIFY,
                MaskFunction.SEQUENCE,
                MaskFunction.SCRIPT);
        EnumSet<MaskFunction> lookupReserved = EnumSet.of(
                MaskFunction.DIRECT_LOOKUP,
                MaskFunction.HASH_LOOKUP);
        EnumSet<MaskFunction> sourcePreserving = EnumSet.allOf(MaskFunction.class);
        sourcePreserving.removeAll(sourceCreating);
        sourcePreserving.removeAll(lookupReserved);

        EnumSet<MaskFunction> classified = EnumSet.copyOf(sourcePreserving);
        classified.addAll(sourceCreating);
        classified.addAll(lookupReserved);
        assertEquals(EnumSet.allOf(MaskFunction.class), classified,
                "Every function must belong to exactly one null/empty contract");

        Map<MaskFunction, ValidCase> cases = validCases();
        sourcePreserving.forEach(function -> {
            ValidCase testCase = cases.get(function);
            assertNull(engine.mask(function, "mask002.null", null,
                    testCase.param1(), testCase.param2(), referenceContext()), function.name());
            assertEquals("", engine.mask(function, "mask002.empty", "",
                    testCase.param1(), testCase.param2(), referenceContext()), function.name());
        });

        assertEquals("MASKED", engine.mask(MaskFunction.FIXED, "mask002.fixed", null,
                "MASKED", null, referenceContext()));
        assertNull(engine.mask(MaskFunction.NULLIFY, "mask002.nullify", "",
                null, null, referenceContext()));
        assertEquals("CUS-7", engine.mask(MaskFunction.SEQUENCE, "mask002.sequence", null,
                "CUS-", null, referenceContext()));
        assertEquals("", engine.mask(MaskFunction.SCRIPT, "mask002.script", "",
                "mask001-script", "reference", referenceContext()));

        String direct = "<NULL>=>NULL_ROW|<EMPTY>=>EMPTY_ROW|<SPACES>=>SPACE_ROW|A=>ALPHA";
        assertEquals("NULL_ROW", engine.mask(MaskFunction.DIRECT_LOOKUP, "mask002.direct", null,
                direct, null, referenceContext()));
        assertEquals("EMPTY_ROW", engine.mask(MaskFunction.DIRECT_LOOKUP, "mask002.direct", "",
                direct, null, referenceContext()));
        assertEquals("SPACE_ROW", engine.mask(MaskFunction.DIRECT_LOOKUP, "mask002.direct", "   ",
                direct, null, referenceContext()));

        String hashed = "-1=>NULL_ROW|-2=>SPACE_ROW|-3=>EMPTY_ROW|1=>ALPHA|2=>BETA";
        assertEquals("NULL_ROW", engine.mask(MaskFunction.HASH_LOOKUP, "mask002.hash", null,
                hashed, null, referenceContext()));
        assertEquals("EMPTY_ROW", engine.mask(MaskFunction.HASH_LOOKUP, "mask002.hash", "",
                hashed, null, referenceContext()));
        assertEquals("SPACE_ROW", engine.mask(MaskFunction.HASH_LOOKUP, "mask002.hash", "   ",
                hashed, null, referenceContext()));
    }

    private Map<MaskFunction, ValidCase> validCases() {
        Map<MaskFunction, ValidCase> cases = new EnumMap<>(MaskFunction.class);
        add(cases, MaskFunction.FIRST_NAME, "Jane", null, "PROPER");
        add(cases, MaskFunction.LAST_NAME, "Doe", null, "UPPER");
        add(cases, MaskFunction.FULL_NAME, "Jane Q Doe", "FIRST MIDDLE LAST", "PROPER");
        add(cases, MaskFunction.EMAIL, "jane.doe@example.com", "NAME_SAFE", "SAFE_DOMAIN");
        add(cases, MaskFunction.PHONE, "+1 (415) 555-0182", "FORMAT_PRESERVE", "PRESERVE_COUNTRY");
        add(cases, MaskFunction.SSN, "123-45-6789", "VALID_PRESERVE_AREA", "PRESERVE_FORMAT");
        add(cases, MaskFunction.CREDIT_CARD, "4111 1111 1111 1111", "VALID_PRESERVE_BIN", "PRESERVE_FORMAT");
        add(cases, MaskFunction.DATE_SHIFT, "2026-01-31", "-30:30", "yyyy-MM-dd");
        add(cases, MaskFunction.DOB_AGE_BAND, "1987-04-12", "5", "yyyy-MM-dd");
        add(cases, MaskFunction.ADDRESS_STREET, "12 Rosewood Ave", null, "PROPER");
        add(cases, MaskFunction.ADDRESS_US, "12 Rosewood Ave, Austin, TX 73301, USA", "FULL", "PRESERVE_STATE");
        add(cases, MaskFunction.CITY_STATE_ZIP, "Austin, TX 73301", "FULL", "PRESERVE_STATE");
        add(cases, MaskFunction.COMPANY, "Forge Bank", null, "PROPER");
        add(cases, MaskFunction.FORMAT_PRESERVE, "AB-1234", null, "AS_IS");
        add(cases, MaskFunction.CHARACTER_MAP, "AB-1234", "FIRST:2,LAST:2", "AS_IS");
        add(cases, MaskFunction.TOKENIZE, "customer-10025", "TKN_", "32");
        add(cases, MaskFunction.SECURE_LOOKUP, "Preferred", "ALPHA|BETA|GAMMA", "UPPER");
        add(cases, MaskFunction.DIRECT_LOOKUP, "A", "A=>Alpha|B=>Beta", "NOT_FOUND=ERROR;TRIM=BOTH;CASE=UPPER");
        add(cases, MaskFunction.HASH_LOOKUP, "customer-10025", "1=>Avery|2=>Jordan|3=>Taylor", "SEED=7");
        add(cases, MaskFunction.REDACT, "99887766", "*", "KEEP_LAST4");
        add(cases, MaskFunction.REDACT_KEEP_LAST4, "99887766", null, null);
        add(cases, MaskFunction.NUMERIC_NOISE, "924.41", "PERCENT:10", "0:1000");
        add(cases, MaskFunction.MIN_MAX, "924.41", "10", "20");
        add(cases, MaskFunction.BANK_ACCOUNT, "0012-3456-7890", "KEEP_LAST4", null);
        add(cases, MaskFunction.IBAN, "GB82 WEST 1234 5698 7654 32", "PRESERVE_COUNTRY", "PRESERVE_FORMAT");
        add(cases, MaskFunction.SWIFT_BIC, "BOFAUS3NXXX", "PRESERVE_COUNTRY", null);
        add(cases, MaskFunction.ABA_ROUTING, "021000021", "PRESERVE_FED_DISTRICT", null);
        add(cases, MaskFunction.NATIONAL_ID, "123-45-6789", "US", "PRESERVE_FORMAT");
        add(cases, MaskFunction.IP_ADDRESS, "192.168.10.24", "SAFE_TEST_RANGE", null);
        add(cases, MaskFunction.MAC_ADDRESS, "00:1A:2B:3C:4D:5E", "LOCAL_ADMIN", null);
        add(cases, MaskFunction.UUID, "550e8400-e29b-41d4-a716-446655440000", null, null);
        add(cases, MaskFunction.HASH_LOV, "Jane", "first_names.txt", "PROPER");
        add(cases, MaskFunction.FIXED, "source", "MASKED", "UPPER");
        add(cases, MaskFunction.NULLIFY, "source", null, null);
        add(cases, MaskFunction.SEQUENCE, "source", "CUS-", null);
        add(cases, MaskFunction.PASSTHROUGH, "source", null, null);
        add(cases, MaskFunction.BY_INDICATOR, "+1 (415) 555-0182", "type_ind", "P=PHONE|*=FORMAT_PRESERVE");
        add(cases, MaskFunction.PARTIAL_MASK, "Jane1234", "[A-Za-z]+", "FIRST_NAME");
        add(cases, MaskFunction.PHONE_SPLIT, "415", "area_code", "area_code,exchange,line_no");
        add(cases, MaskFunction.SSN_SPLIT, "123", "ssn_area", "ssn_area,ssn_group,ssn_serial");
        add(cases, MaskFunction.DATE_SPLIT, "12", "dob_day", "dd=dob_day,mm=dob_month,yyyy=dob_year");
        add(cases, MaskFunction.AGE, "2026-01-31", "+1y -2m +3d", "yyyy-MM-dd");
        add(cases, MaskFunction.SCRIPT, "Jane1234", "mask001-script", "reference");
        return cases;
    }

    private Map<MaskFunction, Runnable> invalidCases() {
        Map<MaskFunction, Runnable> cases = new EnumMap<>(MaskFunction.class);
        invalidRule(cases, MaskFunction.FIRST_NAME, null, "SIDEWAYS");
        invalidRule(cases, MaskFunction.LAST_NAME, null, "SIDEWAYS");
        invalidRule(cases, MaskFunction.FULL_NAME, "FIRST UNKNOWN", "PROPER");
        invalidRule(cases, MaskFunction.EMAIL, "BOGUS", "SAFE_DOMAIN");
        invalidRule(cases, MaskFunction.PHONE, "BOGUS", null);
        invalidRule(cases, MaskFunction.SSN, "BOGUS", null);
        invalidRule(cases, MaskFunction.CREDIT_CARD, "BOGUS", null);
        invalidRule(cases, MaskFunction.DATE_SHIFT, "30:0", null);
        invalidRule(cases, MaskFunction.DOB_AGE_BAND, "zero", null);
        invalidRule(cases, MaskFunction.ADDRESS_STREET, null, "SIDEWAYS");
        invalidRule(cases, MaskFunction.ADDRESS_US, "PLANET", null);
        invalidRule(cases, MaskFunction.CITY_STATE_ZIP, "PLANET", null);
        invalidRule(cases, MaskFunction.COMPANY, null, "SIDEWAYS");
        invalidRule(cases, MaskFunction.FORMAT_PRESERVE, null, "SIDEWAYS");
        invalidRule(cases, MaskFunction.CHARACTER_MAP, "FIRST:abc", null);
        invalidRule(cases, MaskFunction.TOKENIZE, "TKN_", "8");
        invalidRule(cases, MaskFunction.SECURE_LOOKUP, null, null);
        invalidRule(cases, MaskFunction.DIRECT_LOOKUP, "not-a-pair", null);
        invalidRule(cases, MaskFunction.HASH_LOOKUP, "1=>A|3=>C", null);
        invalidRule(cases, MaskFunction.REDACT, "TOO-LONG", "FULL");
        invalidRule(cases, MaskFunction.NUMERIC_NOISE, "PERCENT:ten", null);
        invalidRule(cases, MaskFunction.MIN_MAX, "20", "10");
        invalidRule(cases, MaskFunction.BANK_ACCOUNT, "BOGUS", null);
        invalidRule(cases, MaskFunction.IBAN, "BOGUS", null);
        invalidRule(cases, MaskFunction.SWIFT_BIC, "BOGUS", null);
        invalidRule(cases, MaskFunction.ABA_ROUTING, "BOGUS", null);
        invalidRule(cases, MaskFunction.NATIONAL_ID, "XX", null);
        invalidRule(cases, MaskFunction.IP_ADDRESS, "BOGUS", null);
        invalidRule(cases, MaskFunction.MAC_ADDRESS, "BOGUS", null);
        cases.put(MaskFunction.HASH_LOV, () -> engine.mask(MaskFunction.HASH_LOV, "x", "value", "missing-list.txt", null, referenceContext()));
        invalidRule(cases, MaskFunction.FIXED, null, null);
        invalidRule(cases, MaskFunction.BY_INDICATOR, "type_ind", "P=NOT_A_FUNCTION");
        invalidRule(cases, MaskFunction.PARTIAL_MASK, "[", "FIRST_NAME");
        invalidRule(cases, MaskFunction.PHONE_SPLIT, "area_code", "area_code");
        invalidRule(cases, MaskFunction.SSN_SPLIT, "ssn_area", "ssn_area,ssn_group");
        invalidRule(cases, MaskFunction.DATE_SPLIT, "dob_day", "dd=dob_day,mm=dob_month");
        cases.put(MaskFunction.AGE, () -> engine.mask(MaskFunction.AGE, "x", "2026-01-31", "tomorrow", null, referenceContext()));
        invalidRule(cases, MaskFunction.SCRIPT, null, null);
        return cases;
    }

    private void invalidRule(Map<MaskFunction, Runnable> cases, MaskFunction function, String param1, String param2) {
        cases.put(function, () -> {
            PolicyController.validateRuleConfig(function, param1, param2);
            engine.mask(function, "mask001.invalid", "sample", param1, param2, referenceContext());
        });
    }

    private static void add(Map<MaskFunction, ValidCase> cases, MaskFunction function,
                            String value, String param1, String param2) {
        cases.put(function, new ValidCase(value, param1, param2));
    }

    private static MaskingEngine scriptedEngine() {
        MaskingEngine engine = new MaskingEngine("mask001-reference-secret");
        engine.setScriptProvider(name -> "return forge.fpe(value)");
        return engine;
    }

    private static MaskContext referenceContext() {
        MaskContext context = new MaskContext(7);
        context.row.put("type_ind", "P");
        context.row.put("area_code", "415");
        context.row.put("exchange", "555");
        context.row.put("line_no", "0182");
        context.row.put("ssn_area", "123");
        context.row.put("ssn_group", "45");
        context.row.put("ssn_serial", "6789");
        context.row.put("dob_day", "12");
        context.row.put("dob_month", "04");
        context.row.put("dob_year", "1987");
        return context;
    }

    private record ValidCase(String value, String param1, String param2) { }
}
