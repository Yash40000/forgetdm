package io.forgetdm.core;

import io.forgetdm.core.mask.MaskContext;
import io.forgetdm.core.mask.MaskFunction;
import io.forgetdm.core.mask.MaskingEngine;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class MaskingSeedContractTest {

    private static final String SECRET = "mask003-project-secret";
    private static final String SEED_A = "MASK003-Replay-A";
    private static final String SEED_B = "MASK003-Replay-B";

    private static final EnumSet<MaskFunction> SEED_INVARIANT = EnumSet.of(
            MaskFunction.DIRECT_LOOKUP,
            MaskFunction.REDACT,
            MaskFunction.REDACT_KEEP_LAST4,
            MaskFunction.FIXED,
            MaskFunction.NULLIFY,
            MaskFunction.SEQUENCE,
            MaskFunction.PASSTHROUGH,
            MaskFunction.AGE);

    @Test
    void sameSeedAndInputsAreByteIdenticalAcrossFreshInstancesAndRepeatedRuns() {
        Map<MaskFunction, SeedCase> cases = cases();
        assertEquals(EnumSet.allOf(MaskFunction.class), cases.keySet(),
                "Every mask function needs an explicit deterministic replay case");

        MaskingEngine repeated = engine().withSeed(SEED_A);
        cases.forEach((function, testCase) -> {
            byte[] firstFresh = bytes(run(engine().withSeed(SEED_A), function, testCase));
            byte[] secondFresh = bytes(run(engine().withSeed(SEED_A), function, testCase));
            byte[] firstRepeat = bytes(run(repeated, function, testCase));
            byte[] secondRepeat = bytes(run(repeated, function, testCase));

            assertArrayEquals(firstFresh, secondFresh, function + " differs across fresh engines");
            assertArrayEquals(firstFresh, firstRepeat, function + " differs between fresh and reused engines");
            assertArrayEquals(firstRepeat, secondRepeat, function + " differs across repeated runs");
        });
    }

    @Test
    void differentSeedsChangeEveryFunctionWhereSeedVariationIsMeaningful() {
        Map<MaskFunction, SeedCase> cases = cases();
        EnumSet<MaskFunction> sensitive = EnumSet.allOf(MaskFunction.class);
        sensitive.removeAll(SEED_INVARIANT);

        sensitive.forEach(function -> assertNotEquals(
                run(engine().withSeed(SEED_A), function, cases.get(function)),
                run(engine().withSeed(SEED_B), function, cases.get(function)),
                function + " ignored a different nonblank seed"));

        SEED_INVARIANT.forEach(function -> assertEquals(
                run(engine().withSeed(SEED_A), function, cases.get(function)),
                run(engine().withSeed(SEED_B), function, cases.get(function)),
                function + " should be explicitly seed-invariant"));
    }

    @Test
    void canonicalSeedTrimmingIsPreservedWhileCaseAndInternalWhitespaceRemainSignificant() {
        String value = "customer-10025";

        assertEquals(token("Seed", value), token(" Seed", value), "leading whitespace is normalized");
        assertEquals(token("Seed", value), token("Seed ", value), "trailing whitespace is normalized");
        assertEquals(token("Seed", value), token("  Seed  ", value), "surrounding whitespace is normalized");
        assertNotEquals(token("Seed", value), token("seed", value), "seed case is significant");
        assertNotEquals(token("Seed A", value), token("Seed  A", value), "internal whitespace is significant");
    }

    @Test
    void nullAndWhitespaceOnlySeedsUseTheProjectDefault() {
        String value = "customer-10025";
        String expected = defaultToken(value);

        assertEquals(expected, token(null, value));
        assertEquals(expected, token("", value));
        assertEquals(expected, token("   ", value));
        assertEquals(expected, token("\t\r\n", value));
    }

    @Test
    void rowContextSplitScriptAndLookupReplayWithReconstructedContext() {
        Map<MaskFunction, SeedCase> cases = cases();
        EnumSet<MaskFunction> contextual = EnumSet.of(
                MaskFunction.EMAIL,
                MaskFunction.DATE_SHIFT,
                MaskFunction.BY_INDICATOR,
                MaskFunction.PHONE_SPLIT,
                MaskFunction.SSN_SPLIT,
                MaskFunction.DATE_SPLIT,
                MaskFunction.SCRIPT,
                MaskFunction.DIRECT_LOOKUP,
                MaskFunction.HASH_LOOKUP);

        contextual.forEach(function -> {
            String first = run(engine().withSeed(SEED_A), function, cases.get(function));
            String second = run(engine().withSeed(SEED_A), function, cases.get(function));
            assertArrayEquals(bytes(first), bytes(second), function + " did not replay with reconstructed row context");
        });
    }

    @Test
    void valueMaskingIsIndependentOfExecutionOrderAndRowContextIsStable() {
        MaskingEngine engine = engine().withSeed(SEED_A);
        SeedCase firstName = cases().get(MaskFunction.FIRST_NAME);
        SeedCase email = cases().get(MaskFunction.EMAIL);

        String firstNameBefore = run(engine, MaskFunction.FIRST_NAME, firstName);
        String emailBefore = run(engine, MaskFunction.EMAIL, email);
        run(engine, MaskFunction.CREDIT_CARD, cases().get(MaskFunction.CREDIT_CARD));
        run(engine, MaskFunction.DATE_SHIFT, cases().get(MaskFunction.DATE_SHIFT));
        String emailAfter = run(engine, MaskFunction.EMAIL, email);
        String firstNameAfter = run(engine, MaskFunction.FIRST_NAME, firstName);

        assertEquals(firstNameBefore, firstNameAfter, "value mask changed after reordered execution");
        assertEquals(emailBefore, emailAfter, "row-context mask changed for the same reconstructed context");
        assertEquals(
                engine.mask(MaskFunction.SEQUENCE, "mask003.sequence", "source", "CUS-", null, new MaskContext(17)),
                engine.mask(MaskFunction.SEQUENCE, "mask003.sequence", "source", "CUS-", null, new MaskContext(17)),
                "row-index function changed for the same row context");
    }

    private static String run(MaskingEngine engine, MaskFunction function, SeedCase testCase) {
        if (function == MaskFunction.PHONE_SPLIT) {
            return split(engine, function,
                    new String[]{"area_code", "exchange", "line_no"},
                    new String[]{"415", "555", "0182"}, "area_code,exchange,line_no");
        }
        if (function == MaskFunction.SSN_SPLIT) {
            return split(engine, function,
                    new String[]{"ssn_area", "ssn_group", "ssn_serial"},
                    new String[]{"123", "45", "6789"}, "ssn_area,ssn_group,ssn_serial");
        }
        if (function == MaskFunction.DATE_SPLIT) {
            return split(engine, function,
                    new String[]{"dob_day", "dob_month", "dob_year"},
                    new String[]{"12", "04", "1987"}, "dd=dob_day,mm=dob_month,yyyy=dob_year");
        }
        return engine.mask(function, "mask003." + function.name().toLowerCase(), testCase.value(),
                testCase.param1(), testCase.param2(), context());
    }

    private static String split(MaskingEngine engine, MaskFunction function, String[] columns,
                                String[] values, String specification) {
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < columns.length; i++) {
            if (i > 0) output.append('|');
            output.append(engine.mask(function, "mask003." + function.name().toLowerCase(), values[i],
                    columns[i], specification, context()));
        }
        return output.toString();
    }

    private static Map<MaskFunction, SeedCase> cases() {
        Map<MaskFunction, SeedCase> cases = new EnumMap<>(MaskFunction.class);
        add(cases, MaskFunction.FIRST_NAME, "Jane", null, "PROPER");
        add(cases, MaskFunction.LAST_NAME, "Doe", null, "UPPER");
        add(cases, MaskFunction.FULL_NAME, "Jane Q Doe", "FIRST MIDDLE LAST", "PROPER");
        add(cases, MaskFunction.EMAIL, "jane.doe@example.com", "NAME_SAFE", "SAFE_DOMAIN");
        add(cases, MaskFunction.PHONE, "+1 (415) 555-0182", "FORMAT_PRESERVE", "PRESERVE_COUNTRY");
        add(cases, MaskFunction.SSN, "123-45-6789", "VALID_PRESERVE_AREA", "PRESERVE_FORMAT");
        add(cases, MaskFunction.CREDIT_CARD, "4111 1111 1111 1111", "VALID_PRESERVE_BIN", "PRESERVE_FORMAT");
        add(cases, MaskFunction.DATE_SHIFT, "2026-01-31", "-5000:5000", "yyyy-MM-dd");
        add(cases, MaskFunction.DOB_AGE_BAND, "1987-04-12", "5", "yyyy-MM-dd");
        add(cases, MaskFunction.ADDRESS_STREET, "12 Rosewood Ave", null, "PROPER");
        add(cases, MaskFunction.ADDRESS_US, "12 Rosewood Ave, Austin, TX 73301, USA", "FULL", "PRESERVE_STATE");
        add(cases, MaskFunction.CITY_STATE_ZIP, "Austin, TX 73301", "FULL", "PRESERVE_STATE");
        add(cases, MaskFunction.COMPANY, "Forge Bank", null, "PROPER");
        add(cases, MaskFunction.FORMAT_PRESERVE, "AB-1234-xy-9876", null, "AS_IS");
        add(cases, MaskFunction.CHARACTER_MAP, "AB-1234-xy-9876", "FIRST:2,LAST:2", "AS_IS");
        add(cases, MaskFunction.TOKENIZE, "customer-10025", "TKN_", "32");
        add(cases, MaskFunction.SECURE_LOOKUP, "Preferred", lookupValues(), "UPPER");
        add(cases, MaskFunction.DIRECT_LOOKUP, "A", "A=>Alpha|B=>Beta", "NOT_FOUND=ERROR;TRIM=BOTH;CASE=UPPER");
        add(cases, MaskFunction.HASH_LOOKUP, "customer-10025", hashLookupValues(), "SEED=7");
        add(cases, MaskFunction.REDACT, "99887766", "*", "KEEP_LAST4");
        add(cases, MaskFunction.REDACT_KEEP_LAST4, "99887766", null, null);
        add(cases, MaskFunction.NUMERIC_NOISE, "924.41", "ABS:500", "0:5000");
        add(cases, MaskFunction.MIN_MAX, "924.41", "10", "5000");
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
        add(cases, MaskFunction.SSN_SPLIT, "6789", "ssn_serial", "ssn_area,ssn_group,ssn_serial");
        add(cases, MaskFunction.DATE_SPLIT, "1987", "dob_year", "dd=dob_day,mm=dob_month,yyyy=dob_year");
        add(cases, MaskFunction.AGE, "2026-01-31", "+1y -2m +3d", "yyyy-MM-dd");
        add(cases, MaskFunction.SCRIPT, "Jane1234", "mask003-script", "reference");
        return cases;
    }

    private static String lookupValues() {
        StringBuilder values = new StringBuilder();
        for (int i = 1; i <= 64; i++) {
            if (i > 1) values.append('|');
            values.append("VALUE_").append(i);
        }
        return values.toString();
    }

    private static String hashLookupValues() {
        StringBuilder values = new StringBuilder();
        for (int i = 1; i <= 64; i++) {
            if (i > 1) values.append('|');
            values.append(i).append("=>VALUE_").append(i);
        }
        return values.toString();
    }

    private static MaskingEngine engine() {
        MaskingEngine engine = new MaskingEngine(SECRET);
        engine.setScriptProvider(name -> "return forge.fpe(value) .. ':' .. forge.hash(value, 1000000)");
        return engine;
    }

    private static MaskContext context() {
        MaskContext context = new MaskContext(7);
        context.row.put("type_ind", "P");
        context.row.put("first_name", "Jane");
        context.row.put("last_name", "Doe");
        context.masked.put("first_name", "Avery");
        context.masked.put("last_name", "Stone");
        context.row.put("area_code", "415");
        context.row.put("exchange", "555");
        context.row.put("line_no", "0182");
        context.row.put("ssn_area", "123");
        context.row.put("ssn_group", "45");
        context.row.put("ssn_serial", "6789");
        context.row.put("dob_day", "12");
        context.row.put("dob_month", "04");
        context.row.put("dob_year", "1987");
        context.useSharedDateShiftRange(-5000, 5000);
        return context;
    }

    private static void add(Map<MaskFunction, SeedCase> cases, MaskFunction function,
                            String value, String param1, String param2) {
        cases.put(function, new SeedCase(value, param1, param2));
    }

    private static byte[] bytes(String value) {
        return value == null ? new byte[]{0} : value.getBytes(StandardCharsets.UTF_8);
    }

    private static String defaultToken(String value) {
        return engine().mask(MaskFunction.TOKENIZE, "customer.id", value,
                "TKN_", "32", new MaskContext(7));
    }

    private static String token(String seed, String value) {
        return engine().withSeed(seed).mask(MaskFunction.TOKENIZE, "customer.id", value,
                "TKN_", "32", new MaskContext(7));
    }

    private record SeedCase(String value, String param1, String param2) { }
}
