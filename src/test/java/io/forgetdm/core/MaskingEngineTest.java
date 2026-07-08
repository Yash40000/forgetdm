package io.forgetdm.core;

import io.forgetdm.core.mask.MaskContext;
import io.forgetdm.core.mask.MaskFunction;
import io.forgetdm.core.mask.MaskingEngine;
import io.forgetdm.core.util.Luhn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MaskingEngineTest {
    private final MaskingEngine engine = new MaskingEngine("unit-test-secret");
    private final MaskContext ctx = new MaskContext(1);

    @Test void firstNameIsDeterministicAndNormalized() {
        String a = engine.mask(MaskFunction.FIRST_NAME, "name.first", "Rajesh", null, null, ctx);
        String b = engine.mask(MaskFunction.FIRST_NAME, "name.first", " rajesh ", null, null, ctx);
        assertEquals(a, b);
        assertNotEquals("Rajesh", a);
    }

    @Test void fullNamePreservesSplitNameIntegrity() {
        String first = engine.mask(MaskFunction.FIRST_NAME, "name.first", "yash", null, null, ctx);
        String full = engine.mask(MaskFunction.FULL_NAME, "name.full", "yash singh", null, null, ctx);
        assertEquals(first, full.split(" ")[0]);
    }

    @Test void fullNameSupportsFormatsAndCase() {
        String upper = engine.mask(MaskFunction.FULL_NAME, "name.full", "yash kumar singh", "LAST, FIRST MIDDLE", "UPPER", ctx);
        assertTrue(upper.contains(", "));
        assertEquals(upper.toUpperCase(), upper);
        assertEquals(3, upper.replace(",", "").split("\\s+").length);

        String firstLast = engine.mask(MaskFunction.FULL_NAME, "name.full", "yash kumar singh", "FIRST LAST", "PROPER", ctx);
        assertFalse(firstLast.contains(","));
        assertEquals(2, firstLast.split("\\s+").length);
        assertEquals(Character.toUpperCase(firstLast.charAt(0)), firstLast.charAt(0));
    }

    @Test void nameCaseCanBeSelectedForAtomicNameFields() {
        String lower = engine.mask(MaskFunction.FIRST_NAME, "name.first", "Yash", null, "LOWER", ctx);
        String upper = engine.mask(MaskFunction.FIRST_NAME, "name.first", "Yash", null, "UPPER", ctx);
        String proper = engine.mask(MaskFunction.FIRST_NAME, "name.first", "Yash", null, "PROPER", ctx);
        assertEquals(lower.toLowerCase(), lower);
        assertEquals(upper.toUpperCase(), upper);
        assertEquals(Character.toUpperCase(proper.charAt(0)), proper.charAt(0));
        assertEquals(lower, upper.toLowerCase());
    }

    @Test void ssnKeepsAreaAndFormatAndValidGroups() {
        String m = engine.mask(MaskFunction.SSN, "ssn", "123-45-6789", null, null, ctx);
        assertTrue(m.startsWith("123-"));
        assertTrue(m.matches("\\d{3}-\\d{2}-\\d{4}"));
        assertNotEquals("123-45-6789", m);
        assertFalse(engine.mask(MaskFunction.SSN, "ssn", "666-12-3456", null, null, ctx).startsWith("666"));
    }

    @Test void ssnSupportsMaskingModes() {
        assertEquals("***-**-6789", engine.mask(MaskFunction.SSN, "ssn", "123-45-6789", "KEEP_LAST4", "DASHED", ctx));
        assertEquals("***-**-****", engine.mask(MaskFunction.SSN, "ssn", "123-45-6789", "REDACT", "DASHED", ctx));
        String randomArea = engine.mask(MaskFunction.SSN, "ssn", "123456789", "VALID_RANDOM_AREA", "DIGITS_ONLY", ctx);
        assertTrue(randomArea.matches("\\d{9}"));
        assertFalse(randomArea.startsWith("123"));
    }

    @Test void creditCardKeepsBinAndLuhnAndSeparators() {
        String m = engine.mask(MaskFunction.CREDIT_CARD, "ccn", "4111 1111 1111 1111", null, null, ctx);
        assertTrue(m.startsWith("4111 11"));
        assertEquals(' ', m.charAt(4));
        assertTrue(Luhn.isValid(m.replaceAll("\\D", "")));
        assertNotEquals("4111 1111 1111 1111", m);
    }

    @Test void creditCardSupportsMaskingModes() {
        String keepLast4 = engine.mask(MaskFunction.CREDIT_CARD, "ccn", "4111 1111 1111 1111", "VALID_KEEP_LAST4", null, ctx);
        assertTrue(keepLast4.endsWith("1111"));
        assertTrue(Luhn.isValid(keepLast4.replaceAll("\\D", "")));
        String generated = engine.mask(MaskFunction.CREDIT_CARD, "ccn", "4111 1111 1111 1111", "VALID_RANDOM_BIN", "DIGITS_ONLY", ctx);
        assertTrue(generated.matches("\\d{16}"));
        assertTrue(Luhn.isValid(generated));
        assertFalse(generated.startsWith("411111"));
        assertEquals("4111 1111 1111 1112",
                engine.mask(MaskFunction.CREDIT_CARD, "ccn", "4111 1111 1111 1112", "VALID_RANDOM_BIN", "DIGITS_ONLY", ctx));
    }

    @Test void emailIsSafeValidAndDeterministic() {
        String m = engine.mask(MaskFunction.EMAIL, "email", "jane.doe@gmail.com", null, null, ctx);
        assertTrue(m.matches("^[\\w.+-]+@[\\w.-]+$"));
        assertTrue(m.endsWith(".test"));
        assertEquals(m, engine.mask(MaskFunction.EMAIL, "email", "jane.doe@gmail.com", null, null, ctx));
    }

    @Test void emailSupportsDomainAndLocalPartModes() {
        String preserved = engine.mask(MaskFunction.EMAIL, "email", "jane.doe@gmail.com", "HASH_LOCAL", "PRESERVE_DOMAIN", ctx);
        assertTrue(preserved.endsWith("@gmail.com"));
        assertTrue(preserved.startsWith("user"));
        String redacted = engine.mask(MaskFunction.EMAIL, "email", "jane.doe@gmail.com", "REDACT_LOCAL", "SAFE_DOMAIN", ctx);
        assertTrue(redacted.startsWith("masked@"));
        assertTrue(redacted.endsWith(".test"));
    }

    @Test void dobStaysInAgeBand() {
        String m = engine.mask(MaskFunction.DOB_AGE_BAND, "dob", "1987-06-15", "5", null, ctx);
        int year = Integer.parseInt(m.substring(0, 4));
        assertTrue(year >= 1985 && year <= 1989);
    }

    @Test void geoTripletIsCoherent() {
        String city = engine.mask(MaskFunction.CITY_STATE_ZIP, "geo", "60601", "CITY", null, ctx);
        String full = engine.mask(MaskFunction.CITY_STATE_ZIP, "geo", "60601", "FULL", null, ctx);
        assertTrue(full.startsWith(city + ", "));
    }

    @Test void geoCanPreserveState() {
        ctx.row.put("state", "OH");
        assertEquals("OH", engine.mask(MaskFunction.CITY_STATE_ZIP, "geo", "43004", "STATE", "PRESERVE_STATE", ctx));
        assertTrue(engine.mask(MaskFunction.CITY_STATE_ZIP, "geo", "43004", "FULL", "PRESERVE_STATE", ctx).contains(", OH "));
    }

    @Test void usAddressCanPreserveStateAndStayCoherent() {
        ctx.row.put("country", "US");
        ctx.row.put("state", "OH");
        String full = engine.mask(MaskFunction.ADDRESS_US, "addr.us", "12 Rosewood Ave, Columbus, OH 43004, USA", "FULL", "PRESERVE_STATE", ctx);
        assertTrue(full.matches("\\d+ .+, Apt \\d+, .+, OH \\d{5}, USA"));
        assertFalse(full.contains("Rosewood"));
        assertEquals("OH", engine.mask(MaskFunction.ADDRESS_US, "addr.us", "12 Rosewood Ave, Columbus, OH 43004, USA", "STATE", "PRESERVE_STATE", ctx));
    }

    @Test void phoneKeepsCountryCode() {
        String m = engine.mask(MaskFunction.PHONE, "phone", "+1 (415) 555-0182", null, null, ctx);
        assertTrue(m.startsWith("+1 ("));
        assertTrue(m.matches("\\+1 \\(\\d{3}\\) \\d{3}-\\d{4}"));
    }

    @Test void phoneSupportsMaskingModes() {
        assertEquals("+X (XXX) XXX-0182", engine.mask(MaskFunction.PHONE, "phone", "+1 (415) 555-0182", "KEEP_LAST4", null, ctx));
        assertEquals("+* (***) ***-****", engine.mask(MaskFunction.PHONE, "phone", "+1 (415) 555-0182", "REDACT", null, ctx));
        String area = engine.mask(MaskFunction.PHONE, "phone", "+1 (415) 555-0182", "PRESERVE_AREA", null, ctx);
        assertTrue(area.startsWith("+1 (415)"));
        assertNotEquals("+1 (415) 555-0182", area);
    }

    @Test void secretRotationChangesOutput() {
        MaskingEngine other = new MaskingEngine("another-secret");
        assertNotEquals(
            engine.mask(MaskFunction.SSN, "ssn", "123-45-6789", null, null, ctx),
            other.mask(MaskFunction.SSN, "ssn", "123-45-6789", null, null, ctx));
    }

    @Test void seedChangesOutputButStaysDeterministic() {
        MaskingEngine seeded = engine.withSeed("run-7");
        // different seed => different masked universe
        assertNotEquals(
            engine.mask(MaskFunction.FIRST_NAME, "name.first", "Rajesh", null, null, ctx),
            seeded.mask(MaskFunction.FIRST_NAME, "name.first", "Rajesh", null, null, ctx));
        // same seed => repeatable (referential integrity preserved within a seed)
        assertEquals(
            seeded.mask(MaskFunction.FIRST_NAME, "name.first", "Rajesh", null, null, ctx),
            engine.withSeed("run-7").mask(MaskFunction.FIRST_NAME, "name.first", "Rajesh", null, null, ctx));
        // blank/null seed => default engine behaviour unchanged
        assertEquals(
            engine.mask(MaskFunction.SSN, "ssn", "123-45-6789", null, null, ctx),
            engine.withSeed("").mask(MaskFunction.SSN, "ssn", "123-45-6789", null, null, ctx));
        assertSame(engine, engine.withSeed(null));
    }
}
