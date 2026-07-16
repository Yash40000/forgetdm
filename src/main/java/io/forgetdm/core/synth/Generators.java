package io.forgetdm.core.synth;

import io.forgetdm.core.util.Luhn;
import io.forgetdm.core.util.SeedLists;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Configurable row-generator library. Each generator: (rowIndex, rng) -> value.
 * Synthetic data carries zero PII by construction.
 */
public final class Generators {
    private Generators() {}

    public record GeneratorSpec(String name, String category, String description,
                                String param1, String param2, String example) {}

    private static final List<GeneratorSpec> SPECS = List.of(
            spec("FIRST_NAME", "Person", "Expanded seeded first name. Optional param1 locale US|IN|UK|GLOBAL; param2 gender M|F|ANY.", "locale", "gender", "Maya"),
            spec("LAST_NAME", "Person", "Expanded seeded last name. Optional param1 locale US|IN|UK|GLOBAL.", "locale", "", "Patel"),
            spec("FULL_NAME", "Person", "Expanded seeded first and last name. Optional param1 locale; param2 gender.", "locale", "gender", "Maya Patel"),
            spec("MALE_FIRST_NAME", "Person", "Expanded seeded male first name. Optional param1 locale US|IN|UK|GLOBAL.", "locale", "", "Aarav"),
            spec("FEMALE_FIRST_NAME", "Person", "Expanded seeded female first name. Optional param1 locale US|IN|UK|GLOBAL.", "locale", "", "Ananya"),
            spec("FIRST_NAME_BY_LOCALE", "Person", "Locale-aware first name. param1 locale US|IN|UK|GLOBAL; param2 gender M|F|ANY.", "locale", "gender", "Diya"),
            spec("LAST_NAME_BY_LOCALE", "Person", "Locale-aware last name. param1 locale US|IN|UK|GLOBAL.", "locale", "", "Sharma"),
            spec("FULL_NAME_BY_LOCALE", "Person", "Locale-aware full name. param1 locale US|IN|UK|GLOBAL; param2 gender M|F|ANY.", "locale", "gender", "Diya Sharma"),
            spec("USERNAME", "Person", "Lowercase username from expanded name parts and row number.", "locale", "gender", "m.patel42"),
            spec("EMAIL", "Person", "Safe synthetic email using expanded name parts and reserved/test seed domains.", "locale", "gender", "maya.patel42@example.test"),
            spec("PHONE_US", "Person", "US formatted phone number.", "", "", "(415) 555-0184"),
            spec("SSN", "Person", "Valid-looking SSN shape with invalid ranges avoided.", "", "", "318-42-0911"),
            spec("DOB_ADULT", "Person", "Adult date of birth from 18 to 79 years old.", "", "", "1984-02-19"),
            spec("AGE", "Person", "Integer age range.", "min age", "max age", "37"),
            spec("GENDER", "Person", "Simple gender code from M/F/X.", "", "", "F"),
            spec("GENDER_WEIGHTED", "Person", "Weighted gender pick. Example param1 F:40|M:60|X:0.", "F:40|M:60|X:0", "", "M"),

            spec("STREET_ADDRESS", "Location", "House number plus seeded street.", "", "", "42 Cedar Ave"),
            spec("CITY", "Location", "City from a coherent US city/state/zip row.", "", "", "Austin"),
            spec("STATE", "Location", "State from the same row-indexed city/state/zip set.", "", "", "TX"),
            spec("ZIP", "Location", "ZIP from the same row-indexed city/state/zip set.", "", "", "78701"),
            spec("GEO_TRIPLET", "Location", "City, state, ZIP in one value.", "", "", "Austin,TX,78701"),
            spec("LATITUDE", "Location", "Latitude in a configurable range.", "min", "max", "30.2672"),
            spec("LONGITUDE", "Location", "Longitude in a configurable range.", "min", "max", "-97.7431"),
            spec("COUNTRY_CODE", "Location", "Country code, default US.", "code", "", "US"),
            spec("CITY_BY_COUNTRY", "Location", "City by country and optional state list.", "US or IN", "TX|OH or MH|KA", "Columbus"),
            spec("STATE_BY_COUNTRY", "Location", "State/province by country and optional state list.", "US or IN", "TX|OH or MH|KA", "OH"),
            spec("POSTAL_BY_COUNTRY", "Location", "ZIP/PIN by country and optional state list.", "US or IN", "TX|OH or MH|KA", "43004"),
            spec("ADDRESS_BY_COUNTRY", "Location", "Street, city, state, postal by country and optional state list.", "US or IN", "TX|OH or MH|KA", "42 Cedar Ave, Columbus, OH 43004"),

            spec("CREDIT_CARD_VISA", "Finance", "Collision-free, Luhn-valid Visa test number (100 trillion per column).", "", "", "4..."),
            spec("CREDIT_CARD_MC", "Finance", "Collision-free, Luhn-valid Mastercard test number (50 trillion per column).", "", "", "5..."),
            spec("CREDIT_CARD_AMEX", "Finance", "Collision-free, Luhn-valid American Express style test number (2 trillion per column).", "", "", "37..."),
            spec("ACCOUNT_NUMBER", "Finance", "Numeric account identifier.", "length", "", "928104550127"),
            spec("ROUTING_NUMBER_US", "Finance", "ABA-style routing number with check digit.", "", "", "021000021"),
            spec("CURRENCY_USD", "Finance", "Decimal amount from 1 to max.", "max", "", "128.45"),
            spec("IBAN_LIKE", "Finance", "IBAN-shaped value for interface testing.", "", "", "DE89370400440532013000"),
            spec("BIC", "Finance", "BIC/SWIFT-shaped code.", "", "", "DEUTDEFF"),
            spec("RISK_SCORE", "Finance", "Risk score between 0 and 1.", "", "", "0.742"),

            spec("DATE_RECENT", "Date/Time", "Date within the last N days.", "days", "", "2026-05-20"),
            spec("DATE_BETWEEN", "Date/Time", "Date between ISO start and end.", "start yyyy-mm-dd", "end yyyy-mm-dd", "2026-06-11"),
            spec("DATE_FUTURE", "Date/Time", "Date within the next N days.", "days", "", "2026-07-01"),
            spec("TIMESTAMP_RECENT", "Date/Time", "ISO timestamp within the last N minutes.", "minutes", "", "2026-06-11T09:15:30"),
            spec("TIME", "Date/Time", "Random local time.", "", "", "13:45:09"),

            spec("UUID", "Technical", "Name-based UUID derived from row and seed.", "", "", "7d9..."),
            spec("SEQUENCE", "Technical", "Prefix plus row number.", "prefix", "", "C42"),

            spec("LITERAL", "Control", "Fixed literal value in every row (param1).", "value", "", "REDACTED"),
            spec("NULL", "Control", "Always null / empty.", "", "", ""),
            spec("API", "Control", "REST / microservice lookup, one call per row. param1 = URL (supports {row}); param2 = JSON field path, blank = whole response body.",
                    "https://svc.internal/account?seq={row}", "accountNumber", "928104550127"),
            spec("ALPHA", "Technical", "Random letters.", "length", "", "AbcDef"),
            spec("NUMERIC_STRING", "Technical", "Random digits preserving leading zeros.", "length", "", "004219"),
            spec("ALPHANUMERIC", "Technical", "Random letters and digits.", "length", "", "A7K9P2"),
            spec("BOOLEAN", "Technical", "True or false.", "", "", "true"),
            spec("BOOLEAN_WEIGHTED", "Technical", "Boolean with configurable true percentage.", "true %", "", "true"),
            spec("ENUM", "Technical", "Pick from pipe-delimited values.", "A|B|C", "", "B"),
            spec("STATUS", "Technical", "Status picklist; override with pipe-delimited values.", "ACTIVE|INACTIVE|PENDING", "", "ACTIVE"),
            spec("HTTP_STATUS", "Technical", "HTTP status suited to API tests.", "", "", "404"),
            spec("JSON_OBJECT", "Technical", "Small JSON object with row id and status.", "", "", "{\"id\":42,\"status\":\"ACTIVE\"}"),

            spec("COMPANY", "Business", "Seeded company name.", "", "", "Northwind Labs"),
            spec("PRODUCT_SKU", "Business", "SKU with prefix and random suffix.", "prefix", "", "SKU-8FK21Q"),
            spec("ORDER_STATUS", "Business", "Common order lifecycle status.", "", "", "SHIPPED"),
            spec("PAYMENT_STATUS", "Business", "Common payment lifecycle status.", "", "", "SETTLED"),
            spec("PERCENT", "Business", "Percentage with two decimals.", "", "", "73.42"),
            spec("LOREM_WORD", "Business", "Single lorem-style word.", "", "", "lorem"),
            spec("LOREM_SENTENCE", "Business", "Short lorem-style sentence.", "words", "", "lorem ipsum dolor"),

            spec("DOMAIN", "Network", "Synthetic domain from seed list.", "", "", "example.test"),
            spec("URL", "Network", "HTTPS URL using synthetic domain.", "path", "", "https://example.test/app"),
            spec("IPV4", "Network", "Private IPv4 address.", "", "", "10.42.7.9"),
            spec("IPV6", "Network", "IPv6-shaped address.", "", "", "fd00:..."),
            spec("MAC_ADDRESS", "Network", "Locally administered MAC address.", "", "", "02:42:ac:11:00:02"),
            spec("NORMAL_INT", "Distribution", "Integer from a normal distribution (mean, stddev).", "1000", "250", "1024"),
            spec("NORMAL_DECIMAL", "Distribution", "Decimal from a normal distribution (mean, stddev).", "500.0", "120.0", "517.42"),
            spec("WEIGHTED", "Distribution", "Pick a value by weight, e.g. ACTIVE:80|DORMANT:15|CLOSED:5.", "ACTIVE:80|DORMANT:15|CLOSED:5", "", "ACTIVE"),
            spec("PADDED_SEQUENCE", "Technical", "Zero-padded sequence for unique ids (width, prefix).", "10", "AC", "AC0000000001"),
            spec("TEMPLATE", "Derived", "${column} / ${column:mod} (lower,upper,slug,nospace,initial); ${domain} = random email domain; ${rand:a|b|c} = random pick.", "${full_name:slug}@${domain}", "", "andrew.chen@qa-mail.test"),
            spec("COPY", "Derived", "Copy another column's value from the same row.", "first_name", "", "Maya"),
            spec("LOOKUP", "Derived", "Copy a column from the referenced PARENT row (cross-table). param1=parent col (or col:first/last/rest/lower…), param2=FK column (optional).", "full_name:first", "customer_id", "Andrew"),
            spec("CASE", "Derived", "Map another column's value, e.g. CANCELLED=Y|*=N.", "status", "CANCELLED=Y|*=N", "N"),
            spec("DATE_AFTER", "Derived", "A date on/after another date column in the row (max days).", "open_date", "365", "2024-08-12")
    );

    private static GeneratorSpec spec(String name, String category, String description,
                                      String param1, String param2, String example) {
        return new GeneratorSpec(name, category, description, param1, param2, example);
    }

    public static List<String> catalog() {
        return SPECS.stream().map(GeneratorSpec::name).toList();
    }

    public static List<GeneratorSpec> catalogDetails() {
        return SPECS;
    }

    public static long fullNameSpace(String locale, String gender) {
        return SyntheticNameCatalog.fullNameSpace(locale, gender);
    }

    public static BiFunction<Long, Random, String> of(String name, String p1, String p2) {
        return of(name, p1, p2, 0L, "standalone");
    }

    /**
     * Seeded generator factory. The namespace should identify the logical column (normally table.column).
     * Card generators use both values to select a repeatable permutation of their complete PAN space.
     */
    public static BiFunction<Long, Random, String> of(String name, String p1, String p2,
                                                       long seed, String namespace) {
        switch (String.valueOf(name).toUpperCase(Locale.ROOT)) {
            case "FIRST_NAME":
            case "FIRST_NAME_BY_LOCALE": return firstName(p1, p2);
            case "MALE_FIRST_NAME": return firstName(p1, "M");
            case "FEMALE_FIRST_NAME": return firstName(p1, "F");
            case "LAST_NAME":
            case "LAST_NAME_BY_LOCALE": return lastName(p1);
            case "FULL_NAME":
            case "FULL_NAME_BY_LOCALE": return fullName(p1, p2);
            case "USERNAME": return username(p1, p2);
            case "EMAIL": return email(p1, p2);
            case "PHONE_US":   return (i, r) -> String.format(Locale.US, "(%03d) %03d-%04d", 200 + r.nextInt(799), 200 + r.nextInt(799), r.nextInt(10000));
            case "SSN":        return (i, r) -> ssn(r);
            case "DOB_ADULT":  return (i, r) -> LocalDate.now().minusYears(18 + r.nextInt(62)).minusDays(r.nextInt(365))
                                                .format(DateTimeFormatter.ISO_LOCAL_DATE);
            case "AGE": return (i, r) -> String.valueOf(between(r, intOr(p1, 18), intOr(p2, 79)));
            case "GENDER": return (i, r) -> pick(r, List.of("M", "F", "X"));
            case "GENDER_WEIGHTED": return (i, r) -> weighted(r, p1 == null || p1.isBlank() ? "F:50|M:50|X:0" : p1);

            case "CREDIT_CARD_VISA": return uniqueCardGenerator("CREDIT_CARD_VISA", new String[]{"4"}, 16, seed, namespace);
            case "CREDIT_CARD_MC":   return uniqueCardGenerator("CREDIT_CARD_MC", new String[]{"51", "52", "53", "54", "55"}, 16, seed, namespace);
            case "CREDIT_CARD_AMEX": return uniqueCardGenerator("CREDIT_CARD_AMEX", new String[]{"34", "37"}, 15, seed, namespace);
            case "ACCOUNT_NUMBER": return (i, r) -> digits(r, intOr(p1, 12));
            case "ROUTING_NUMBER_US": return (i, r) -> routingNumber(r);
            case "CURRENCY_USD": return (i, r) -> money(1, doubleOr(p1, 10000), r);
            case "IBAN_LIKE": return (i, r) -> String.format(Locale.US, "DE%02d%018d", 10 + r.nextInt(89), Math.abs(r.nextLong()) % 1_000_000_000_000_000_000L);
            case "BIC": return (i, r) -> letters(r, 4).toUpperCase(Locale.ROOT) + "DE" + letters(r, 2).toUpperCase(Locale.ROOT);
            case "RISK_SCORE": return (i, r) -> String.format(Locale.US, "%.3f", r.nextDouble());

            case "DATE_RECENT": return (i, r) -> LocalDate.now().minusDays(r.nextInt(Math.max(1, intOr(p1, 365))))
                                                .format(DateTimeFormatter.ISO_LOCAL_DATE);
            case "DATE_BETWEEN": return (i, r) -> dateBetween(r, p1, p2);
            case "DATE_FUTURE": return (i, r) -> LocalDate.now().plusDays(1 + r.nextInt(Math.max(1, intOr(p1, 365))))
                                                .format(DateTimeFormatter.ISO_LOCAL_DATE);
            case "TIMESTAMP_RECENT": return (i, r) -> LocalDateTime.now().minusMinutes(r.nextInt(Math.max(1, intOr(p1, 1440))))
                                                .withNano(0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            case "TIME": return (i, r) -> LocalTime.of(r.nextInt(24), r.nextInt(60), r.nextInt(60)).toString();

            case "STREET_ADDRESS": return (i, r) -> (1 + r.nextInt(9899)) + " " + pick(r, "streets.txt");
            case "CITY":  return (i, r) -> csz(i, 0);
            case "STATE": return (i, r) -> csz(i, 1);
            case "ZIP":   return (i, r) -> csz(i, 2);
            case "GEO_TRIPLET": return (i, r) -> csz(i, 0) + "," + csz(i, 1) + "," + csz(i, 2);
            case "LATITUDE": return (i, r) -> String.format(Locale.US, "%.6f", doubleBetween(r, doubleOr(p1, 24.5), doubleOr(p2, 49.5)));
            case "LONGITUDE": return (i, r) -> String.format(Locale.US, "%.6f", doubleBetween(r, doubleOr(p1, -124.8), doubleOr(p2, -66.9)));
            case "COUNTRY_CODE": return (i, r) -> (p1 == null || p1.isBlank()) ? "US" : p1.trim().toUpperCase(Locale.ROOT);
            case "CITY_BY_COUNTRY": return (i, r) -> location(i, r, p1, p2)[0];
            case "STATE_BY_COUNTRY": return (i, r) -> location(i, r, p1, p2)[1];
            case "POSTAL_BY_COUNTRY": return (i, r) -> location(i, r, p1, p2)[2];
            case "ADDRESS_BY_COUNTRY": return (i, r) -> {
                String[] loc = location(i, r, p1, p2);
                return (1 + r.nextInt(9899)) + " " + pick(r, "streets.txt") + ", "
                        + loc[0] + ", " + loc[1] + " " + loc[2];
            };

            case "COMPANY": return (i, r) -> pick(r, "companies.txt");
            case "PRODUCT_SKU": return (i, r) -> ((p1 == null || p1.isBlank()) ? "SKU" : p1.trim()) + "-" + alphanumeric(r, 6).toUpperCase(Locale.ROOT);
            case "ORDER_STATUS": return (i, r) -> pick(r, List.of("CREATED", "PAID", "PICKED", "SHIPPED", "DELIVERED", "CANCELLED"));
            case "PAYMENT_STATUS": return (i, r) -> pick(r, List.of("AUTHORIZED", "SETTLED", "DECLINED", "REFUNDED", "CHARGEBACK"));
            case "PERCENT": return (i, r) -> String.format(Locale.US, "%.2f", r.nextDouble() * 100);
            case "LOREM_WORD": return (i, r) -> pick(r, LOREM);
            case "LOREM_SENTENCE": return (i, r) -> sentence(r, intOr(p1, 8));

            case "UUID":  return (i, r) -> UUID.nameUUIDFromBytes(("fg" + i + r.nextLong()).getBytes(StandardCharsets.UTF_8)).toString();
            case "SEQUENCE": return (i, r) -> (p1 == null ? "" : p1) + i;
            case "LITERAL": return (i, r) -> p1 == null ? "" : p1;
            case "NULL": return (i, r) -> null;
            case "API": return (i, r) -> null;   // resolved live by SyntheticGenService (HTTP); blank in pure preview
            case "INT_RANGE": return (i, r) -> String.valueOf(between(r, intOr(p1, 0), intOr(p2, 100)));
            case "DECIMAL_RANGE": return (i, r) -> String.format(Locale.US, "%.2f", doubleBetween(r, doubleOr(p1, 0), doubleOr(p2, 1000)));
            case "ALPHA": return (i, r) -> letters(r, intOr(p1, 12));
            case "NUMERIC_STRING": return (i, r) -> digits(r, intOr(p1, 10));
            case "ALPHANUMERIC": return (i, r) -> alphanumeric(r, intOr(p1, 12));
            case "BOOLEAN": return (i, r) -> String.valueOf(r.nextBoolean());
            case "BOOLEAN_WEIGHTED": return (i, r) -> String.valueOf(r.nextInt(100) < Math.max(0, Math.min(100, intOr(p1, 50))));
            case "ENUM":
            case "STATUS":  return (i, r) -> { String s = (p1 == null || p1.isBlank()) ? "ACTIVE|INACTIVE|PENDING" : p1;
                                               String[] a = s.split("\\|"); return a[r.nextInt(a.length)].trim(); };
            case "HTTP_STATUS": return (i, r) -> pick(r, List.of("200", "201", "202", "400", "401", "403", "404", "409", "422", "500"));
            case "JSON_OBJECT": return (i, r) -> "{\"id\":" + i + ",\"status\":\"" + pick(r, List.of("ACTIVE", "PENDING", "CLOSED")) + "\"}";

            case "DOMAIN": return (i, r) -> pick(r, "email_domains.txt");
            case "URL": return (i, r) -> "https://" + pick(r, "email_domains.txt") + "/" + ((p1 == null || p1.isBlank()) ? "app" : cleanPath(p1));
            case "IPV4": return (i, r) -> "10." + r.nextInt(256) + "." + r.nextInt(256) + "." + (1 + r.nextInt(254));
            case "IPV6": return (i, r) -> "fd00:" + hex(r) + ":" + hex(r) + ":" + hex(r) + ":" + hex(r) + ":" + hex(r) + ":" + hex(r) + ":" + hex(r);
            case "MAC_ADDRESS": return (i, r) -> "02:" + macByte(r) + ":" + macByte(r) + ":" + macByte(r) + ":" + macByte(r) + ":" + macByte(r);
            case "NORMAL_INT": return (i, r) -> String.valueOf((int) Math.round(doubleOr(p1, 0) + r.nextGaussian() * doubleOr(p2, 1)));
            case "NORMAL_DECIMAL": return (i, r) -> String.format(Locale.US, "%.2f", doubleOr(p1, 0) + r.nextGaussian() * doubleOr(p2, 1));
            case "WEIGHTED": return (i, r) -> weighted(r, (p1 == null || p1.isBlank()) ? "A:1" : p1);
            case "PADDED_SEQUENCE": return (i, r) -> { int w = Math.max(1, intOr(p1, 8)); return (p2 == null ? "" : p2) + String.format("%0" + w + "d", i); };
            // Derived (row-aware) generators are resolved by SyntheticGenService against the in-progress row.
            case "TEMPLATE":
            case "COPY":
            case "CASE":
            case "DATE_AFTER":
            case "LOOKUP": return (i, r) -> null;
            default: return (i, r) -> "GEN(" + name + ")#" + i;
        }
    }

    private static final List<String> LOREM = List.of("lorem", "ipsum", "dolor", "sit", "amet", "nexus", "orbit", "vector", "nova", "atlas");
    private static final List<String> INDIA_LOCATIONS = List.of(
            "Mumbai,MH,400001", "Pune,MH,411001", "Bengaluru,KA,560001", "Mysuru,KA,570001",
            "Delhi,DL,110001", "New Delhi,DL,110011", "Chennai,TN,600001", "Coimbatore,TN,641001",
            "Hyderabad,TS,500001", "Jaipur,RJ,302001", "Ahmedabad,GJ,380001", "Kolkata,WB,700001");

    private static String pick(Random r, String list) { return pick(r, SeedLists.get(list)); }
    private static String pick(Random r, List<String> values) { return values.get(r.nextInt(values.size())); }

    private static BiFunction<Long, Random, String> firstName(String locale, String gender) {
        List<String> firstNames = SyntheticNameCatalog.firstNames(locale, gender);
        int realFirst = SyntheticNameCatalog.realFirstCount(locale, gender);
        return (i, r) -> SyntheticNameCatalog.pickPreferReal(r, firstNames, realFirst);
    }

    private static BiFunction<Long, Random, String> lastName(String locale) {
        List<String> lastNames = SyntheticNameCatalog.lastNames(locale);
        int realLast = SyntheticNameCatalog.realLastCount(locale);
        return (i, r) -> SyntheticNameCatalog.pickPreferReal(r, lastNames, realLast);
    }

    private static BiFunction<Long, Random, String> fullName(String locale, String gender) {
        List<String> firstNames = SyntheticNameCatalog.firstNames(locale, gender);
        List<String> lastNames = SyntheticNameCatalog.lastNames(locale);
        int realFirst = SyntheticNameCatalog.realFirstCount(locale, gender);
        int realLast = SyntheticNameCatalog.realLastCount(locale);
        return (i, r) -> SyntheticNameCatalog.pickPreferReal(r, firstNames, realFirst)
                + " " + SyntheticNameCatalog.pickPreferReal(r, lastNames, realLast);
    }

    private static BiFunction<Long, Random, String> username(String locale, String gender) {
        List<String> firstNames = SyntheticNameCatalog.firstNames(locale, gender);
        List<String> lastNames = SyntheticNameCatalog.lastNames(locale);
        int realFirst = SyntheticNameCatalog.realFirstCount(locale, gender);
        int realLast = SyntheticNameCatalog.realLastCount(locale);
        return (i, r) -> {
            String first = clean(SyntheticNameCatalog.pickPreferReal(r, firstNames, realFirst));
            String last = clean(SyntheticNameCatalog.pickPreferReal(r, lastNames, realLast));
            return (first.isBlank() ? "u" : first.substring(0, 1)) + "." + last + i;
        };
    }

    private static BiFunction<Long, Random, String> email(String locale, String gender) {
        List<String> firstNames = SyntheticNameCatalog.firstNames(locale, gender);
        List<String> lastNames = SyntheticNameCatalog.lastNames(locale);
        int realFirst = SyntheticNameCatalog.realFirstCount(locale, gender);
        int realLast = SyntheticNameCatalog.realLastCount(locale);
        return (i, r) -> clean(SyntheticNameCatalog.pickPreferReal(r, firstNames, realFirst)) + "."
                + clean(SyntheticNameCatalog.pickPreferReal(r, lastNames, realLast))
                + i + "@" + pick(r, "email_domains.txt");
    }

    private static String csz(long row, int idx) {
        List<String> l = SeedLists.get("cities_us.csv");
        int n = Math.floorMod(Long.hashCode(row * 1103515245L + 12345L), l.size());
        return l.get(n).split(",")[idx];
    }

    private static String[] location(long row, Random r, String country, String states) {
        String c = country == null || country.isBlank() ? "US" : country.trim().toUpperCase(Locale.ROOT);
        List<String> rows = c.equals("IN") || c.equals("INDIA") ? INDIA_LOCATIONS : SeedLists.get("cities_us.csv");
        List<String> filtered = filterStates(rows, states);
        if (!filtered.isEmpty()) rows = filtered;
        int n = Math.floorMod(Long.hashCode(row * 1103515245L + r.nextInt(9999)), rows.size());
        return rows.get(n).split(",");
    }

    private static List<String> filterStates(List<String> rows, String states) {
        if (states == null || states.isBlank() || states.equalsIgnoreCase("ALL")) return List.of();
        List<String> wanted = Arrays.stream(states.split("[|,]"))
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .filter(s -> !s.isBlank())
                .toList();
        if (wanted.isEmpty()) return List.of();
        return rows.stream().filter(row -> wanted.contains(row.split(",")[1].toUpperCase(Locale.ROOT))).toList();
    }

    private static String weighted(Random r, String spec) {
        String[] parts = spec.split("\\|");
        int total = 0;
        List<String> labels = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        for (String part : parts) {
            String[] p = part.split(":");
            if (p.length != 2) continue;
            int w = Math.max(0, intOr(p[1], 0));
            if (w == 0) continue;
            labels.add(p[0].trim());
            weights.add(w);
            total += w;
        }
        if (labels.isEmpty()) return "F";
        int pick = r.nextInt(total);
        for (int i = 0; i < labels.size(); i++) {
            pick -= weights.get(i);
            if (pick < 0) return labels.get(i);
        }
        return labels.get(labels.size() - 1);
    }

    private static int intOr(String s, int d) {
        try { return s == null || s.isBlank() ? d : Integer.parseInt(s.trim()); } catch (Exception e) { return d; }
    }

    private static double doubleOr(String s, double d) {
        try { return s == null || s.isBlank() ? d : Double.parseDouble(s.trim()); } catch (Exception e) { return d; }
    }

    private static int between(Random r, int min, int max) {
        int lo = Math.min(min, max), hi = Math.max(min, max);
        return lo + r.nextInt(Math.max(1, hi - lo + 1));
    }

    private static double doubleBetween(Random r, double min, double max) {
        double lo = Math.min(min, max), hi = Math.max(min, max);
        return lo + r.nextDouble() * (hi - lo);
    }

    private static String dateBetween(Random r, String p1, String p2) {
        LocalDate start = dateOr(p1, LocalDate.now().minusDays(30));
        LocalDate end = dateOr(p2, LocalDate.now().plusDays(30));
        if (end.isBefore(start)) {
            LocalDate tmp = start;
            start = end;
            end = tmp;
        }
        long days = ChronoUnit.DAYS.between(start, end);
        return start.plusDays(days <= 0 ? 0 : r.nextInt((int) Math.min(days + 1, Integer.MAX_VALUE))).format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    private static LocalDate dateOr(String s, LocalDate d) {
        try { return s == null || s.isBlank() ? d : LocalDate.parse(s.trim()); } catch (Exception e) { return d; }
    }

    private static String ssn(Random r) {
        int area;
        do { area = 1 + r.nextInt(899); } while (area == 666);
        return String.format(Locale.US, "%03d-%02d-%04d", area, 1 + r.nextInt(99), 1 + r.nextInt(9999));
    }

    /** Maximum number of distinct PANs emitted by a card generator for one logical column. */
    public static long cardCapacity(String generator) {
        return switch (String.valueOf(generator).trim().toUpperCase(Locale.ROOT)) {
            case "CREDIT_CARD_VISA" -> 100_000_000_000_000L;
            case "CREDIT_CARD_MC" -> 50_000_000_000_000L;
            case "CREDIT_CARD_AMEX" -> 2_000_000_000_000L;
            default -> 0L;
        };
    }

    private static BiFunction<Long, Random, String> uniqueCardGenerator(String name, String[] prefixes,
                                                                         int length, long seed, String namespace) {
        CardPermutation permutation = CardPermutation.create(name, prefixes, length, seed, namespace);
        return (rowIndex, ignored) -> permutation.card(rowIndex);
    }

    /**
     * A keyed, constant-memory permutation of the finite card-number domain. Every one-based row index maps
     * to exactly one PAN and retries map back to the same PAN. This remains collision-free across partitions
     * because workers receive the same seed/namespace and use the global row index.
     */
    private record CardPermutation(String name, String[] prefixes, int length, int variableDigits,
                                   long valuesPerPrefix, long capacity, long offset,
                                   int prefixOffset, int[] positions, int[] shifts) {
        static CardPermutation create(String name, String[] prefixes, int length, long seed, String namespace) {
            int variableDigits = length - prefixes[0].length() - 1;
            long valuesPerPrefix = pow10(variableDigits);
            long capacity = Math.multiplyExact(prefixes.length, valuesPerPrefix);
            long key = mix64(seed ^ stableHash(name + "|" + String.valueOf(namespace)));
            long offset = Math.floorMod(mix64(key ^ 0x6a09e667f3bcc909L), capacity);
            int prefixOffset = (int) Math.floorMod(mix64(key ^ 0xbb67ae8584caa73bL), prefixes.length);

            int[] positions = new int[variableDigits];
            int[] shifts = new int[variableDigits];
            Random keyed = new Random(mix64(key ^ 0x3c6ef372fe94f82bL));
            for (int i = 0; i < variableDigits; i++) positions[i] = i;
            for (int i = variableDigits - 1; i > 0; i--) {
                int j = keyed.nextInt(i + 1);
                int swap = positions[i];
                positions[i] = positions[j];
                positions[j] = swap;
            }
            boolean changed = false;
            for (int i = 0; i < variableDigits; i++) {
                shifts[i] = keyed.nextInt(10);
                changed |= shifts[i] != 0;
            }
            if (!changed && variableDigits > 0) shifts[0] = 1;
            return new CardPermutation(name, prefixes.clone(), length, variableDigits, valuesPerPrefix,
                    capacity, offset, prefixOffset, positions, shifts);
        }

        String card(long rowIndex) {
            if (rowIndex < 1 || rowIndex > capacity) {
                throw new IllegalArgumentException(name + " supports row indexes 1 through " + capacity
                        + "; requested " + rowIndex);
            }
            long domainIndex = (rowIndex - 1 + offset) % capacity;
            int prefixIndex = (int) ((domainIndex / valuesPerPrefix + prefixOffset) % prefixes.length);
            long serial = domainIndex % valuesPerPrefix;
            char[] raw = new char[variableDigits];
            Arrays.fill(raw, '0');
            for (int i = variableDigits - 1; i >= 0 && serial > 0; i--) {
                raw[i] = (char) ('0' + serial % 10);
                serial /= 10;
            }
            char[] permuted = new char[variableDigits];
            for (int i = 0; i < variableDigits; i++) {
                int digit = (raw[i] - '0' + shifts[i]) % 10;
                permuted[positions[i]] = (char) ('0' + digit);
            }
            String body = prefixes[prefixIndex] + new String(permuted);
            return body + Luhn.checkDigit(body);
        }
    }

    private static long pow10(int digits) {
        long value = 1;
        for (int i = 0; i < digits; i++) value = Math.multiplyExact(value, 10L);
        return value;
    }

    private static long stableHash(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private static String routingNumber(Random r) {
        int[] d = new int[9];
        for (int i = 0; i < 8; i++) d[i] = r.nextInt(10);
        int sum = 7 * (d[0] + d[3] + d[6]) + 3 * (d[1] + d[4] + d[7]) + 9 * (d[2] + d[5]);
        d[8] = (10 - (sum % 10)) % 10;
        StringBuilder out = new StringBuilder();
        for (int v : d) out.append(v);
        return out.toString();
    }

    private static String money(double min, double max, Random r) {
        return String.format(Locale.US, "%.2f", doubleBetween(r, min, max));
    }

    private static String letters(Random r, int len) {
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        return randomChars(r, alphabet, len);
    }

    private static String digits(Random r, int len) {
        return randomChars(r, "0123456789", len);
    }

    private static String alphanumeric(Random r, int len) {
        return randomChars(r, "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789", len);
    }

    private static String randomChars(Random r, String alphabet, int len) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.max(1, len); i++) out.append(alphabet.charAt(r.nextInt(alphabet.length())));
        return out.toString();
    }

    private static String sentence(Random r, int words) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.max(1, words); i++) {
            if (i > 0) out.append(' ');
            out.append(pick(r, LOREM));
        }
        return out.substring(0, 1).toUpperCase(Locale.ROOT) + out.substring(1) + ".";
    }

    private static String clean(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private static String cleanPath(String s) {
        String cleaned = s.trim().replaceAll("^/+", "").replaceAll("[^A-Za-z0-9_./-]+", "-");
        return cleaned.isBlank() ? "app" : cleaned;
    }

    private static String hex(Random r) {
        return String.format(Locale.US, "%04x", r.nextInt(0x10000));
    }

    private static String macByte(Random r) {
        return String.format(Locale.US, "%02x", r.nextInt(256));
    }
}
