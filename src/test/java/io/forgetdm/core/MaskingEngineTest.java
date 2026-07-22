package io.forgetdm.core;

import io.forgetdm.core.mask.MaskContext;
import io.forgetdm.core.mask.MaskFunction;
import io.forgetdm.core.mask.MaskingEngine;
import io.forgetdm.core.util.Luhn;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

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
        String nonconforming = engine.mask(MaskFunction.CREDIT_CARD, "ccn", "4111 1111 1111 1112", "VALID_RANDOM_BIN", "DIGITS_ONLY", ctx);
        assertNotEquals("4111 1111 1111 1112", nonconforming); // fail closed: malformed PANs never pass through
        assertTrue(nonconforming.matches("\\d{16}"));
    }

    @Test void creditCardPreserveBinIsCollisionFreeAtScale() {
        Set<String> masked = new HashSet<>();
        for (int i = 0; i < 100_000; i++) {
            String body = "411111" + String.format("%09d", i);
            String source = body + Luhn.checkDigit(body);
            String value = engine.mask(MaskFunction.CREDIT_CARD, "cards.pan", source,
                    "VALID_PRESERVE_BIN", "DIGITS_ONLY", ctx);
            assertTrue(value.startsWith("411111"));
            assertTrue(Luhn.isValid(value));
            assertTrue(masked.add(value), "collision for " + source + " -> " + value);
        }
    }

    @Test void creditCardRandomBinIsCollisionFreeAtScale() {
        Set<String> masked = new HashSet<>();
        for (int i = 0; i < 50_000; i++) {
            String body = "411111" + String.format("%09d", i);
            String source = body + Luhn.checkDigit(body);
            String value = engine.mask(MaskFunction.CREDIT_CARD, "cards.pan", source,
                    "VALID_RANDOM_BIN", "DIGITS_ONLY", ctx);
            assertTrue(value.startsWith("4"));
            assertTrue(Luhn.isValid(value));
            assertTrue(masked.add(value), "collision for " + source + " -> " + value);
        }
    }

    @Test void creditCardKeepLastFourIsCollisionFreeAtScale() {
        Set<String> masked = new HashSet<>();
        int generated = 0;
        for (int i = 0; generated < 5_000; i++) {
            String body = "411111" + String.format("%06d", i) + "123";
            if (Luhn.checkDigit(body) != '4') continue;
            String source = body + "4";
            String value = engine.mask(MaskFunction.CREDIT_CARD, "cards.pan", source,
                    "VALID_KEEP_LAST4", "DIGITS_ONLY", ctx);
            assertTrue(value.endsWith("1234"));
            assertTrue(Luhn.isValid(value));
            assertTrue(masked.add(value), "collision for " + source + " -> " + value);
            generated++;
        }
    }

    @Test void malformedCardShapedIdentifiersRemainMaskedAndCollisionFree() {
        Set<String> masked = new HashSet<>();
        for (int i = 1; i <= 20_000; i++) {
            String source = String.format("%014d", i);
            String value = engine.mask(MaskFunction.CREDIT_CARD, "party.key", source,
                    "VALID_PRESERVE_BIN", "DIGITS_ONLY", ctx);
            assertEquals(source.length(), value.length());
            assertNotEquals(source, value);
            assertTrue(masked.add(value), "collision for " + source + " -> " + value);
        }
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

    @Test void temporalMasksAcceptOracleTimestampTextWithoutCreatingInvalidDates() {
        String dob = engine.mask(MaskFunction.DOB_AGE_BAND, "dob", "1987-06-15 00:00:00.0", "5", null, ctx);
        assertDoesNotThrow(() -> java.time.LocalDate.parse(dob.substring(0, 10)));
        assertEquals(" 00:00:00.0", dob.substring(10));

        String shifted = engine.mask(MaskFunction.DATE_SHIFT, "expiry", "2026-01-31 13:14:15.0", "365", null, ctx);
        assertDoesNotThrow(() -> java.time.LocalDate.parse(shifted.substring(0, 10)));
        assertEquals(" 13:14:15.0", shifted.substring(10));
    }

    @Test void temporalMasksDoNotDigitScrambleUnknownFormats() {
        assertEquals("not-a-date", engine.mask(MaskFunction.DATE_SHIFT, "date", "not-a-date", "30", null, ctx));
        assertEquals("not-a-date", engine.mask(MaskFunction.DOB_AGE_BAND, "dob", "not-a-date", "5", null, ctx));
    }

    @Test void dateShiftSupportsDirectionalRangesWithoutBreakingLegacyMaxDays() {
        LocalDate source = LocalDate.parse("2026-01-31");
        LocalDate forward = LocalDate.parse(engine.mask(MaskFunction.DATE_SHIFT, "expiry",
                source.toString(), "0:365", null, ctx));
        LocalDate backward = LocalDate.parse(engine.mask(MaskFunction.DATE_SHIFT, "effective",
                source.toString(), "-365:0", null, ctx));
        LocalDate legacy = LocalDate.parse(engine.mask(MaskFunction.DATE_SHIFT, "legacy",
                source.toString(), "365", null, ctx));

        assertTrue(forward.isAfter(source));
        assertTrue(backward.isBefore(source));
        assertTrue(Math.abs(java.time.temporal.ChronoUnit.DAYS.between(source, legacy)) <= 365);
        assertThrows(IllegalStateException.class, () -> engine.mask(MaskFunction.DATE_SHIFT, "bad",
                source.toString(), "365:0", null, ctx));
    }

    @Test void sharedDateShiftPreservesIntervalsAcrossRelatedColumns() {
        MaskContext row = new MaskContext(42);
        row.row.put("policy_id", "2001");
        row.row.put("effective_date", "2028-06-01");
        row.row.put("expiration_date", "2029-06-01");
        int[] common = MaskingEngine.intersectDateShiftRanges(java.util.List.of("365", "0:365"));
        row.useSharedDateShiftRange(common[0], common[1]);

        LocalDate effective = LocalDate.parse(engine.mask(MaskFunction.DATE_SHIFT, "effective",
                "2028-06-01", "365", null, row));
        LocalDate expiration = LocalDate.parse(engine.mask(MaskFunction.DATE_SHIFT, "expiration",
                "2029-06-01", "0:365", null, row));

        assertEquals(365, java.time.temporal.ChronoUnit.DAYS.between(effective, expiration));
        assertTrue(expiration.isAfter(effective));
        assertArrayEquals(new int[]{0, 365}, common);
        assertThrows(IllegalStateException.class, () ->
                MaskingEngine.intersectDateShiftRanges(java.util.List.of("-365:-1", "1:365")));
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

    @Test void characterMappingAndRedactionPreserveRequestedShape() {
        String mapped = engine.mask(MaskFunction.CHARACTER_MAP, "customer.ref", "AB-1234-SECRET", "FIRST:2,LAST:4", "UPPER", ctx);
        assertEquals("AB", mapped.substring(0, 2));
        assertTrue(mapped.endsWith("CRET"));
        assertEquals('-', mapped.charAt(2));
        assertNotEquals("AB-1234-SECRET", mapped);

        assertEquals("##-####-##CRET", engine.mask(MaskFunction.REDACT, "customer.ref", "AB-1234-SECRET", "#", "KEEP_LAST4", ctx));
        assertEquals("XXXXXXXX", engine.mask(MaskFunction.REDACT, "customer.ref", "anything", "X", "STANDARD:8", ctx));
    }

    @Test void tokenAndSecureLookupAreStableAndConfigurable() {
        String token = engine.mask(MaskFunction.TOKENIZE, "customer.id", "CUST-10025", "CUS_", "24", ctx);
        assertTrue(token.matches("CUS_[0-9a-f]{24}"));
        assertEquals(token, engine.mask(MaskFunction.TOKENIZE, "customer.id", "CUST-10025", "CUS_", "24", ctx));
        assertNotEquals(token, engine.mask(MaskFunction.TOKENIZE, "customer.id", "CUST-10026", "CUS_", "24", ctx));

        String lookup = engine.mask(MaskFunction.SECURE_LOOKUP, "status", "VIP", "GOLD|SILVER|BRONZE", "UPPER", ctx);
        assertTrue(Set.of("GOLD", "SILVER", "BRONZE").contains(lookup));
        assertEquals(lookup, engine.mask(MaskFunction.SECURE_LOOKUP, "status", "VIP", "GOLD|SILVER|BRONZE", "UPPER", ctx));
    }

    @Test void directLookupSupportsExactCompositeAndFailClosedMappings() {
        String pairs = "A=>Alpha|B=>Beta|<NULL>=>Unknown|US~VIP=>Priority";
        assertEquals("Alpha", engine.mask(MaskFunction.DIRECT_LOOKUP, "ignored", " a ", pairs,
                "TRIM=BOTH;CASE=UPPER;NOT_FOUND=ERROR", ctx));
        assertEquals("Unknown", engine.mask(MaskFunction.DIRECT_LOOKUP, "ignored", null, pairs,
                "NOT_FOUND=ERROR", ctx));
        assertThrows(IllegalStateException.class, () -> engine.mask(MaskFunction.DIRECT_LOOKUP, "ignored", "C", pairs,
                "NOT_FOUND=ERROR", ctx));
        assertEquals("C", engine.mask(MaskFunction.DIRECT_LOOKUP, "ignored", "C", pairs,
                "NOT_FOUND=PRESERVE", ctx));

        MaskContext composite = new MaskContext(2);
        composite.row.put("region", "US");
        composite.row.put("tier", "VIP");
        assertEquals("Priority", engine.mask(MaskFunction.DIRECT_LOOKUP, "ignored", "source-value", pairs,
                "SOURCE=region,tier;JOIN=~", composite));
    }

    @Test void hashLookupSupportsSequentialAndReservedOptimKeys() {
        String table = "-1=>Unknown|-2=>Blank|-3=>Empty|1=>Avery|2=>Jordan|3=>Taylor";
        String first = engine.mask(MaskFunction.HASH_LOOKUP, "table.one", "Customer-10025", table, "SEED=7", ctx);
        String second = engine.mask(MaskFunction.HASH_LOOKUP, "table.two", "Customer-10025", table, "SEED=7", ctx);
        assertEquals(first, second); // lookup identity + seed, not physical table salt, controls consistency
        assertTrue(Set.of("Avery", "Jordan", "Taylor").contains(first));
        assertEquals("Unknown", engine.mask(MaskFunction.HASH_LOOKUP, "ignored", null, table, null, ctx));
        assertEquals("Blank", engine.mask(MaskFunction.HASH_LOOKUP, "ignored", "   ", table, null, ctx));
        assertEquals("Empty", engine.mask(MaskFunction.HASH_LOOKUP, "ignored", "", table, null, ctx));
        assertThrows(IllegalStateException.class, () -> engine.mask(MaskFunction.HASH_LOOKUP, "ignored", "x",
                "1=>A|3=>C", null, ctx));
    }

    @Test void hashLookupSupportsMultiColumnDestination() {
        String table = "-1=>None~None|1=>Olivia~Johnson|2=>Liam~Smith|3=>Ava~Brown";
        // Two destination columns hashing the SAME source land on the same row and take different value columns.
        String firstName = engine.mask(MaskFunction.HASH_LOOKUP, "table.first_name", "Customer-10025", table, "SEED=7;VALUE=1", ctx);
        String lastName = engine.mask(MaskFunction.HASH_LOOKUP, "table.last_name", "Customer-10025", table, "SEED=7;VALUE=2", ctx);
        // The default (no VALUE) form returns the whole row, proving both slices come from ONE coherent record.
        String wholeRow = engine.mask(MaskFunction.HASH_LOOKUP, "table.whole", "Customer-10025", table, "SEED=7", ctx);
        assertEquals(wholeRow, firstName + "~" + lastName);
        assertTrue(Set.of("Olivia", "Liam", "Ava").contains(firstName));
        assertTrue(Set.of("Johnson", "Smith", "Brown").contains(lastName));
        // Reserved rows honor the column selector too.
        assertEquals("None", engine.mask(MaskFunction.HASH_LOOKUP, "ignored", null, table, "VALUE=2", ctx));
        // A custom value separator is supported.
        assertEquals("Johnson", engine.mask(MaskFunction.HASH_LOOKUP, "ignored", "x", "1=>Olivia#Johnson", "VCOLSEP=#;VALUE=2", ctx));
        // Selecting a column beyond the row's width fails closed.
        assertThrows(IllegalStateException.class, () -> engine.mask(MaskFunction.HASH_LOOKUP, "ignored", "Customer-10025",
                table, "VALUE=3", ctx));
    }

    @Test void governedLookupProviderSupportsCacheControlAndSeededEngines() {
        java.util.concurrent.atomic.AtomicBoolean cacheRequested = new java.util.concurrent.atomic.AtomicBoolean();
        MaskingEngine governed = new MaskingEngine("lookup-secret");
        governed.setLookupProvider((name, useCache) -> {
            assertEquals("customer-tier", name);
            cacheRequested.set(useCache);
            return "A=>STANDARD|B=>PRIORITY";
        });
        assertEquals("PRIORITY", governed.withSeed("release-9").mask(MaskFunction.DIRECT_LOOKUP, "ignored", "B",
                "@customer-tier", "CACHE=ON", ctx));
        assertTrue(cacheRequested.get());
        assertEquals("STANDARD", governed.mask(MaskFunction.DIRECT_LOOKUP, "ignored", "A",
                "@customer-tier", "NOCACHE", ctx));
        assertFalse(cacheRequested.get());
    }

    @Test void numericFunctionsPreserveScaleAndBounds() {
        String noisy = engine.mask(MaskFunction.NUMERIC_NOISE, "balance", "1000.00", "PERCENT:10", "950:1050", ctx);
        assertTrue(noisy.matches("\\d+\\.\\d{2}"));
        assertTrue(new java.math.BigDecimal(noisy).compareTo(new java.math.BigDecimal("950")) >= 0);
        assertTrue(new java.math.BigDecimal(noisy).compareTo(new java.math.BigDecimal("1050")) <= 0);

        String ranged = engine.mask(MaskFunction.MIN_MAX, "risk.score", "987.50", "10", "20", ctx);
        assertTrue(ranged.matches("\\d+\\.\\d{2}"));
        assertTrue(new java.math.BigDecimal(ranged).compareTo(new java.math.BigDecimal("10")) >= 0);
        assertTrue(new java.math.BigDecimal(ranged).compareTo(new java.math.BigDecimal("20")) <= 0);

        assertThrows(IllegalStateException.class,
                () -> engine.mask(MaskFunction.NUMERIC_NOISE, "balance", "1000.00", "PERCENT:ten", null, ctx));
        assertThrows(IllegalStateException.class,
                () -> engine.mask(MaskFunction.NUMERIC_NOISE, "balance", "1000.00", "PERCENT:10", "high:low", ctx));
    }

    @Test void bankingIdentifiersRemainStructurallyValid() {
        String account = engine.mask(MaskFunction.BANK_ACCOUNT, "bank.account", "0012-3456-7890", "KEEP_LAST4", null, ctx);
        assertTrue(account.endsWith("7890"));
        assertEquals("0012-3456-7890".length(), account.length());
        assertNotEquals("0012-3456-7890", account);
        assertEquals("****-****-****", engine.mask(MaskFunction.BANK_ACCOUNT, "bank.account", "0012-3456-7890", "REDACT", null, ctx));

        String iban = engine.mask(MaskFunction.IBAN, "iban", "GB82 WEST 1234 5698 7654 32", "PRESERVE_COUNTRY", "PRESERVE_FORMAT", ctx);
        assertTrue(iban.startsWith("GB"));
        assertEquals(1, ibanRemainder(iban));
        assertEquals("GB82 WEST 1234 5698 7654 32".length(), iban.length());

        String bic = engine.mask(MaskFunction.SWIFT_BIC, "swift.bic", "DEUTDEFF500", "PRESERVE_COUNTRY", null, ctx);
        assertTrue(bic.matches("[A-Z]{6}[A-Z0-9]{5}"));
        assertEquals("DE", bic.substring(4, 6));

        String routing = engine.mask(MaskFunction.ABA_ROUTING, "routing.aba", "021000021", "PRESERVE_FED_DISTRICT", null, ctx);
        assertTrue(routing.matches("\\d{9}"));
        assertEquals(0, abaChecksum(routing));
    }

    @Test void nationalAndNetworkIdentifiersAreSafeAndValid() {
        String sin = engine.mask(MaskFunction.NATIONAL_ID, "national.id", "046 454 286", "CA", "PRESERVE_FORMAT", ctx);
        assertTrue(sin.matches("\\d{3} \\d{3} \\d{3}"));
        assertTrue(luhnAny(sin.replaceAll("\\D", "")));

        String ipv4 = engine.mask(MaskFunction.IP_ADDRESS, "network.ip", "8.8.8.8", "SAFE_TEST_RANGE", null, ctx);
        assertTrue(ipv4.matches("(192\\.0\\.2|198\\.51\\.100|203\\.0\\.113)\\.\\d{1,3}"));
        String ipv6 = engine.mask(MaskFunction.IP_ADDRESS, "network.ip", "2001:4860:4860::8888", "SAFE_TEST_RANGE", null, ctx);
        assertTrue(ipv6.startsWith("2001:db8:"));

        String mac = engine.mask(MaskFunction.MAC_ADDRESS, "network.mac", "00:1A:2B:3C:4D:5E", "LOCAL_ADMIN", null, ctx);
        assertTrue(mac.matches("[0-9A-F]{2}(:[0-9A-F]{2}){5}"));
        assertEquals(2, Integer.parseInt(mac.substring(0, 2), 16) & 0x03); // local-admin + unicast

        String uuid = engine.mask(MaskFunction.UUID, "customer.uuid", "550e8400-e29b-41d4-a716-446655440000", null, null, ctx);
        assertTrue(uuid.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));
        assertNotEquals("550e8400-e29b-41d4-a716-446655440000", uuid);
    }

    @Test void seededEngineKeepsScriptRegistryWiring() {
        MaskingEngine scripted = new MaskingEngine("script-secret");
        scripted.setScriptProvider(name -> "return value .. '-' .. param");
        assertEquals("abc-ok", scripted.withSeed("release-7")
                .mask(MaskFunction.SCRIPT, "custom", "abc", "suffix", "ok", ctx));
    }

    @Test void formatPreserveMasksUnicodeWithoutChangingScriptCaseOrPunctuation() {
        String original = "\u00C9lodie \u03A9\u03BC\u03AD\u03B3\u03B1 \u041F\u0440\u0438\u0432\u0435\u0442 "
                + "\u0928\u092E\u0938\u094D\u0924\u0947 \u6771\u4EAC \u0661\u0662\u0663 "
                + "\uD801\uDC00\uD801\uDC28\uD801\uDCA0 \uD83D\uDE00-_/";

        String masked = engine.mask(MaskFunction.FORMAT_PRESERVE, "unicode.pii", original, null, null, ctx);
        assertEquals(masked,
                engine.mask(MaskFunction.FORMAT_PRESERVE, "unicode.pii", original, null, null, ctx));
        assertEquals(original.length(), masked.length(), "UTF-16 width must remain stable");

        int[] sourcePoints = original.codePoints().toArray();
        int[] maskedPoints = masked.codePoints().toArray();
        assertEquals(sourcePoints.length, maskedPoints.length, "code-point count must remain stable");
        for (int i = 0; i < sourcePoints.length; i++) {
            int source = sourcePoints[i];
            int replacement = maskedPoints[i];
            if (Character.isLetterOrDigit(source)) {
                assertNotEquals(source, replacement, "sensitive code point leaked at index " + i);
                assertEquals(Character.UnicodeScript.of(source), Character.UnicodeScript.of(replacement));
                assertEquals(Character.getType(source), Character.getType(replacement));
                assertEquals(Character.charCount(source), Character.charCount(replacement));
            } else {
                assertEquals(source, replacement, "punctuation or symbol changed at index " + i);
            }
        }
        assertTrue(hasOnlyWellFormedSurrogates(masked));
    }

    @Test void formatPreserveNeverLeavesAsciiLettersOrDigitsUnchanged() {
        String original = "AaZz-0199@example.test";
        String masked = engine.mask(MaskFunction.FORMAT_PRESERVE, "ascii.pii", original, null, null, ctx);
        assertEquals(original.length(), masked.length());
        for (int i = 0; i < original.length(); i++) {
            char source = original.charAt(i);
            char replacement = masked.charAt(i);
            if (Character.isLetterOrDigit(source)) assertNotEquals(source, replacement);
            else assertEquals(source, replacement);
        }
    }

    @Test void formatPreserveHandlesLargeMixedUnicodeValuesDeterministically() {
        String unit = "Acct-\u00C9\u03A9\u0416\u6771\u0667-\uD801\uDC00-\uD83D\uDE00|";
        String original = unit.repeat(2_000);
        String masked = engine.mask(MaskFunction.FORMAT_PRESERVE, "large.unicode", original, null, null, ctx);

        assertEquals(original.length(), masked.length());
        assertEquals(original.codePointCount(0, original.length()), masked.codePointCount(0, masked.length()));
        assertNotEquals(original, masked);
        assertEquals(masked,
                engine.mask(MaskFunction.FORMAT_PRESERVE, "large.unicode", original, null, null, ctx));
        assertTrue(hasOnlyWellFormedSurrogates(masked));
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

    private static int ibanRemainder(String formatted) {
        String compact = formatted.replaceAll("\\s", "").toUpperCase();
        String rearranged = compact.substring(4) + compact.substring(0, 4);
        int remainder = 0;
        for (char c : rearranged.toCharArray()) {
            String digits = Character.isLetter(c) ? String.valueOf(c - 'A' + 10) : String.valueOf(c);
            for (char digit : digits.toCharArray()) remainder = (remainder * 10 + digit - '0') % 97;
        }
        return remainder;
    }

    private static int abaChecksum(String digits) {
        int[] weights = {3, 7, 1, 3, 7, 1, 3, 7, 1};
        int sum = 0;
        for (int i = 0; i < 9; i++) sum += (digits.charAt(i) - '0') * weights[i];
        return sum % 10;
    }

    private static boolean luhnAny(String digits) {
        int sum = 0;
        boolean alternate = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int value = digits.charAt(i) - '0';
            if (alternate) { value *= 2; if (value > 9) value -= 9; }
            sum += value;
            alternate = !alternate;
        }
        return sum % 10 == 0;
    }

    private static boolean hasOnlyWellFormedSurrogates(String value) {
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i))) return false;
            } else if (Character.isLowSurrogate(current)) {
                return false;
            }
        }
        return true;
    }
}
