package io.forgetdm.core.mask;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Opt-in proof that seeded masking is byte-identical after real JDBC round trips.
 *
 * Run with -Dforgetdm.mask003.live=true, the three MASK-002 password properties,
 * and -Dforgetdm.mask003.masking.secret=... . Passwords and the masking secret are
 * deliberately never given defaults or written to test output.
 */
@EnabledIfSystemProperty(named = "forgetdm.mask003.live", matches = "true")
class MaskingCrossConnectorChecksumLiveTest {

    private static final String BASE_SEED = "mask003-replay-seed";
    private static final String DIFFERENT_SEED = "mask003-different-seed";

    @Test
    void seededMaskingHasIdenticalChecksumsAcrossReplaysAndConnectors() throws Exception {
        MaskingEngine productionEngine = new MaskingEngine(required("forgetdm.mask003.masking.secret"));
        List<MaskedRow> firstReplay = maskDataset(productionEngine.withSeed(BASE_SEED));
        List<MaskedRow> secondReplay = maskDataset(productionEngine.withSeed(BASE_SEED));
        List<MaskedRow> differentSeed = maskDataset(productionEngine.withSeed(DIFFERENT_SEED));

        Map<String, ReplayChecksums> results = new LinkedHashMap<>();
        for (Fixture fixture : fixtures()) {
            results.put(fixture.name(), roundTrip(fixture, firstReplay, secondReplay, differentSeed));
        }

        String expectedBase = null;
        String expectedDifferent = null;
        for (Map.Entry<String, ReplayChecksums> entry : results.entrySet()) {
            ReplayChecksums checksums = entry.getValue();
            System.out.printf("MASK-003 %s base=%s alternate=%s%n",
                    entry.getKey(), checksums.first(), checksums.differentSeed());
            assertEquals(checksums.first(), checksums.second(),
                    entry.getKey() + " changed checksum on identical-seed replay");
            assertNotEquals(checksums.first(), checksums.differentSeed(),
                    entry.getKey() + " did not change checksum for a different seed");
            if (expectedBase == null) {
                expectedBase = checksums.first();
                expectedDifferent = checksums.differentSeed();
            } else {
                assertEquals(expectedBase, checksums.first(),
                        entry.getKey() + " base checksum differs from another connector");
                assertEquals(expectedDifferent, checksums.differentSeed(),
                        entry.getKey() + " alternate-seed checksum differs from another connector");
            }
        }

        assertEquals(checksum(firstReplay), expectedBase,
                "JDBC round trips changed the canonical masked dataset");
        assertEquals(checksum(differentSeed), expectedDifferent,
                "JDBC round trips changed the alternate-seed dataset");
    }

    private static ReplayChecksums roundTrip(Fixture fixture, List<MaskedRow> first,
                                               List<MaskedRow> second,
                                               List<MaskedRow> differentSeed) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                fixture.url(), fixture.user(), fixture.password())) {
            dropIfPresent(connection, fixture.dropSql());
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(fixture.createSql());
                }
            } catch (SQLException createError) {
                if (fixture.fallback() == null || !isPermissionDenied(createError)) throw createError;
                return roundTripViaRollbackSlot(connection, fixture.fallback(), first, second, differentSeed);
            }
            connection.setAutoCommit(false);
            try {
                String firstChecksum = writeReadChecksum(connection, fixture.tableName(), first);
                String secondChecksum = writeReadChecksum(connection, fixture.tableName(), second);
                String differentChecksum = writeReadChecksum(connection, fixture.tableName(), differentSeed);
                return new ReplayChecksums(firstChecksum, secondChecksum, differentChecksum);
            } finally {
                connection.rollback();
                try (Statement statement = connection.createStatement()) {
                    statement.execute(fixture.dropSql());
                }
            }
        }
    }

    private static ReplayChecksums roundTripViaRollbackSlot(Connection connection, FallbackSlot slot,
                                                              List<MaskedRow> first,
                                                              List<MaskedRow> second,
                                                              List<MaskedRow> differentSeed) throws Exception {
        connection.setAutoCommit(false);
        Object key = null;
        String originalValue = null;
        try {
            try (Statement query = connection.createStatement();
                 ResultSet result = query.executeQuery(slot.selectKeySql())) {
                if (!result.next()) throw new IllegalStateException("MASK-003 rollback slot has no rows: " + slot.table());
                key = result.getObject(1);
            }
            originalValue = readSlotValue(connection, slot, key);
            return new ReplayChecksums(
                    writeReadSlotChecksum(connection, slot, key, first),
                    writeReadSlotChecksum(connection, slot, key, second),
                    writeReadSlotChecksum(connection, slot, key, differentSeed));
        } finally {
            connection.rollback();
            if (key != null) {
                assertEquals(originalValue, readSlotValue(connection, slot, key),
                        "rollback-only fixture retained a MASK-003 value in " + slot.table());
            }
        }
    }

    private static String readSlotValue(Connection connection, FallbackSlot slot, Object key) throws Exception {
        String sql = "select " + slot.valueColumn() + " from " + slot.table() + " where "
                + slot.keyColumn() + "=?";
        try (PreparedStatement read = connection.prepareStatement(sql)) {
            read.setObject(1, key);
            try (ResultSet result = read.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("MASK-003 rollback slot row disappeared");
                return result.getString(1);
            }
        }
    }

    private static String writeReadSlotChecksum(Connection connection, FallbackSlot slot, Object key,
                                                 List<MaskedRow> rows) throws Exception {
        String updateSql = "update " + slot.table() + " set " + slot.valueColumn() + "=? where "
                + slot.keyColumn() + "=?";
        String readSql = "select " + slot.valueColumn() + " from " + slot.table() + " where "
                + slot.keyColumn() + "=?";
        List<MaskedRow> stored = new ArrayList<>(rows.size());
        try (PreparedStatement update = connection.prepareStatement(updateSql);
             PreparedStatement read = connection.prepareStatement(readSql)) {
            for (MaskedRow row : rows) {
                List<String> values = new ArrayList<>(row.values().size());
                for (String value : row.values()) {
                    update.setString(1, value);
                    update.setObject(2, key);
                    assertEquals(1, update.executeUpdate(), "rollback-slot update count for " + slot.table());
                    read.setObject(1, key);
                    try (ResultSet result = read.executeQuery()) {
                        if (!result.next()) throw new IllegalStateException("MASK-003 rollback slot row disappeared");
                        values.add(result.getString(1));
                    }
                }
                stored.add(new MaskedRow(row.rowId(), List.copyOf(values)));
            }
        }
        assertEquals(rows, stored, "rollback-only JDBC readback changed masked values in " + slot.table());
        return checksum(stored);
    }

    private static boolean isPermissionDenied(SQLException error) {
        String state = error.getSQLState();
        return "42501".equals(state)
                || error.getErrorCode() == 1031
                || error.getErrorCode() == 1044
                || error.getErrorCode() == 1142;
    }

    private static void dropIfPresent(Connection connection, String dropSql) {
        try (Statement statement = connection.createStatement()) {
            statement.execute(dropSql);
        } catch (Exception ignored) {
            // Disposable fixture may not exist on a clean run.
        }
    }

    private static String writeReadChecksum(Connection connection, String table, List<MaskedRow> rows)
            throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("delete from " + table);
        }
        String insertSql = "insert into " + table + " (row_id,customer_ref,first_name,last_name,email_address,"
                + "phone_number,ssn,card_number,account_number,birth_date,street_address,unicode_ref) "
                + "values (?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
            for (MaskedRow row : rows) {
                insert.setInt(1, row.rowId());
                for (int index = 0; index < row.values().size(); index++) {
                    insert.setString(index + 2, row.values().get(index));
                }
                insert.addBatch();
            }
            int[] counts = insert.executeBatch();
            assertEquals(rows.size(), counts.length, "JDBC batch result count for " + table);
        }

        List<MaskedRow> stored = new ArrayList<>();
        try (Statement query = connection.createStatement();
             ResultSet result = query.executeQuery("select row_id,customer_ref,first_name,last_name,email_address,"
                     + "phone_number,ssn,card_number,account_number,birth_date,street_address,unicode_ref "
                     + "from " + table + " order by row_id")) {
            while (result.next()) {
                List<String> values = new ArrayList<>(11);
                for (int column = 2; column <= 12; column++) values.add(result.getString(column));
                stored.add(new MaskedRow(result.getInt(1), List.copyOf(values)));
            }
        }
        assertEquals(rows, stored, "JDBC readback changed masked values in " + table);
        return checksum(stored);
    }

    private static List<MaskedRow> maskDataset(MaskingEngine engine) {
        List<SourceRow> source = List.of(
                new SourceRow(1, "CUST-000042", "Yeshpal", "Solanki", "yeshpal.solanki@example.com",
                        "+1 (212) 555-0142", "123-45-6789", "4111111111111111", "000123456789",
                        "1987-06-14", "742 Evergreen Terrace", "München-Данные-東京"),
                new SourceRow(2, "CUST-000043", "Amelia", "Johnson", "amelia.johnson@example.org",
                        "+44 20 7946 0958", "987-65-4321", "5555555555554444", "991234567890",
                        "1974-11-03", "1600 Pennsylvania Avenue NW", "Zürich-Δοκιμή-लेख"),
                new SourceRow(3, "CUST-009999", "Mateo", "Garcia", "mateo.garcia@example.net",
                        "+34 612 34 56 78", "219-09-9999", "378282246310005", "445566778899",
                        "1999-02-28", "10 Downing Street", "SãoPaulo-Пример-大阪"));

        List<MaskedRow> result = new ArrayList<>(source.size());
        for (SourceRow row : source) {
            List<String> values = List.of(
                    engine.mask(MaskFunction.FORMAT_PRESERVE, "customer.reference", row.customerRef(), null, null, null),
                    engine.mask(MaskFunction.FIRST_NAME, "person.first_name", row.firstName(), null, null, null),
                    engine.mask(MaskFunction.LAST_NAME, "person.last_name", row.lastName(), null, null, null),
                    engine.mask(MaskFunction.EMAIL, "person.email", row.email(), "USER_SAFE", null, null),
                    engine.mask(MaskFunction.PHONE, "person.phone", row.phone(), null, null, null),
                    engine.mask(MaskFunction.SSN, "person.ssn", row.ssn(), null, null, null),
                    engine.mask(MaskFunction.CREDIT_CARD, "payment.card", row.card(), null, null, null),
                    engine.mask(MaskFunction.BANK_ACCOUNT, "account.number", row.account(), null, null, null),
                    engine.mask(MaskFunction.DATE_SHIFT, "person.birth_date", row.birthDate(), "365", null, null),
                    engine.mask(MaskFunction.ADDRESS_STREET, "person.street", row.street(), null, null, null),
                    engine.mask(MaskFunction.FORMAT_PRESERVE, "customer.unicode_reference", row.unicode(), null, null, null));
            result.add(new MaskedRow(row.rowId(), values));
        }
        return List.copyOf(result);
    }

    private static String checksum(List<MaskedRow> rows) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream canonical = new DataOutputStream(bytes)) {
            canonical.writeInt(rows.size());
            for (MaskedRow row : rows) {
                canonical.writeInt(row.rowId());
                canonical.writeInt(row.values().size());
                for (String value : row.values()) writeCanonical(canonical, value);
            }
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    }

    private static void writeCanonical(DataOutputStream output, String value) throws Exception {
        if (value == null) {
            output.writeInt(-1);
            return;
        }
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(utf8.length);
        output.write(utf8);
    }

    private static List<Fixture> fixtures() {
        return List.of(
                new Fixture("PostgreSQL",
                        property("forgetdm.mask003.postgres.url", "jdbc:postgresql://127.0.0.1:5433/sourcedb"),
                        property("forgetdm.mask003.postgres.user", "postgres"),
                        required("forgetdm.mask002.postgres.password"),
                        "ftdm_mask003_checksum",
                        "create temporary table ftdm_mask003_checksum (" + portableColumns("varchar(512)")
                                + ") on commit preserve rows",
                        "drop table if exists ftdm_mask003_checksum", null),
                new Fixture("Oracle",
                        property("forgetdm.mask003.oracle.url", "jdbc:oracle:thin:@127.0.0.1:1521:XE"),
                        property("forgetdm.mask003.oracle.user", "BE_CARDS"),
                        required("forgetdm.mask002.oracle.password"),
                        "FTDM_MASK003_CHECKSUM",
                        "create global temporary table FTDM_MASK003_CHECKSUM ("
                                + portableColumns("varchar2(512 char)") + ") on commit delete rows",
                        "drop table FTDM_MASK003_CHECKSUM purge", null),
                new Fixture("MySQL",
                        property("forgetdm.mask003.mysql.url",
                                "jdbc:mysql://127.0.0.1:3306/digital_engagement?useSSL=false"
                                        + "&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8"),
                        property("forgetdm.mask003.mysql.user", "be_digital"),
                        required("forgetdm.mask002.mysql.password"),
                        "ftdm_mask003_checksum",
                        "create temporary table ftdm_mask003_checksum (" + portableColumns("varchar(512)")
                                + ") character set utf8mb4",
                        "drop temporary table ftdm_mask003_checksum",
                        new FallbackSlot("digital_engagement.user_profiles", "profile_id", "display_name",
                                "select profile_id from digital_engagement.user_profiles order by profile_id limit 1")));
    }

    private static String portableColumns(String textType) {
        return "row_id integer not null primary key,customer_ref " + textType + ",first_name " + textType
                + ",last_name " + textType + ",email_address " + textType + ",phone_number " + textType
                + ",ssn " + textType + ",card_number " + textType + ",account_number " + textType
                + ",birth_date " + textType + ",street_address " + textType + ",unicode_ref " + textType;
    }

    private static String property(String name, String fallback) {
        String value = System.getProperty(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String required(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing -D" + name);
        return value;
    }

    private record Fixture(String name, String url, String user, String password,
                           String tableName, String createSql, String dropSql, FallbackSlot fallback) { }

    private record FallbackSlot(String table, String keyColumn, String valueColumn, String selectKeySql) { }

    private record ReplayChecksums(String first, String second, String differentSeed) { }

    private record MaskedRow(int rowId, List<String> values) { }

    private record SourceRow(int rowId, String customerRef, String firstName, String lastName,
                             String email, String phone, String ssn, String card, String account,
                             String birthDate, String street, String unicode) { }
}
