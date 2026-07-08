package io.forgetdm.core;

import io.forgetdm.core.synth.Generators;
import io.forgetdm.core.util.Luhn;
import io.forgetdm.core.util.SeedLists;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GeneratorsTest {
    @Test void visaCardsAreLuhnValid() {
        Random r = new Random(42);
        for (long i = 0; i < 50; i++) {
            String c = Generators.of("CREDIT_CARD_VISA", null, null).apply(i, r);
            assertTrue(c.startsWith("4") && c.length() == 16 && Luhn.isValid(c), c);
        }
    }
    @Test void intRangeRespectsBounds() {
        Random r = new Random(7);
        for (long i = 0; i < 100; i++) {
            int v = Integer.parseInt(Generators.of("INT_RANGE", "10", "20").apply(i, r));
            assertTrue(v >= 10 && v <= 20);
        }
    }
    @Test void sameSeedSameData() {
        String a = Generators.of("FULL_NAME", null, null).apply(1L, new Random(99));
        String b = Generators.of("FULL_NAME", null, null).apply(1L, new Random(99));
        assertEquals(a, b);
    }

    @Test void expandedNameCatalogHasGenRocketScaleCombinatorics() {
        assertTrue(Generators.fullNameSpace(null, null) >= 40_000_000L);
        assertTrue(Generators.fullNameSpace("US", "M") >= 40_000_000L);
        assertTrue(Generators.fullNameSpace("IN", "F") >= 40_000_000L);
    }

    @Test void expandedFullNamesAvoidTinyDictionaryRepeatPressure() {
        var gen = Generators.of("FULL_NAME", null, null);
        Random rng = new Random(123);
        Set<String> values = new HashSet<>();
        for (long i = 1; i <= 10_000; i++) values.add(gen.apply(i, rng));
        assertTrue(values.size() > 9_800, "distinct names: " + values.size());
    }

    @Test void genderAndLocaleAwareNameGeneratorsAreDistinctAndSeeded() {
        String male = Generators.of("MALE_FIRST_NAME", "IN", null).apply(1L, new Random(7));
        String female = Generators.of("FEMALE_FIRST_NAME", "IN", null).apply(1L, new Random(7));
        assertNotEquals(male, female);

        String inName = Generators.of("FULL_NAME_BY_LOCALE", "IN", "F").apply(1L, new Random(11));
        String usName = Generators.of("FULL_NAME_BY_LOCALE", "US", "F").apply(1L, new Random(11));
        assertNotEquals(inName, usName);
        assertEquals(inName, Generators.of("FULL_NAME_BY_LOCALE", "IN", "F").apply(1L, new Random(11)));
    }

    @Test void catalogIncludesEnterpriseGeneratorBreadth() {
        assertTrue(Generators.catalog().size() >= 50);
        assertTrue(Generators.catalog().contains("ACCOUNT_NUMBER"));
        assertTrue(Generators.catalog().contains("ROUTING_NUMBER_US"));
        assertTrue(Generators.catalog().contains("IPV4"));
        assertTrue(Generators.catalog().contains("JSON_OBJECT"));
    }

    @Test void rowIndexedGeoColumnsStayCoherent() {
        long row = 42L;
        String triplet = Generators.of("GEO_TRIPLET", null, null).apply(row, new Random(1));
        String city = Generators.of("CITY", null, null).apply(row, new Random(2));
        String state = Generators.of("STATE", null, null).apply(row, new Random(3));
        String zip = Generators.of("ZIP", null, null).apply(row, new Random(4));
        assertEquals(city + "," + state + "," + zip, triplet);
    }

    @Test void usAddressSeedPoolCoversEveryStateWithMultipleRows() {
        Set<String> expectedStates = Set.of(
                "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA",
                "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD",
                "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ",
                "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC",
                "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY");
        Map<String, Integer> counts = new HashMap<>();
        for (String row : SeedLists.get("cities_us.csv")) {
            String[] parts = row.split(",");
            assertEquals(3, parts.length, row);
            counts.merge(parts[1], 1, Integer::sum);
        }
        expectedStates.forEach(state -> assertTrue(counts.getOrDefault(state, 0) >= 5, state));
        assertTrue(counts.getOrDefault("DC", 0) >= 5);
        assertTrue(SeedLists.get("streets.txt").size() >= 100);
    }
}
