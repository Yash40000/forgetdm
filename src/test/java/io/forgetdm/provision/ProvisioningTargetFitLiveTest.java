package io.forgetdm.provision;

import io.forgetdm.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "forgetdm.mask002.live", matches = "true")
class ProvisioningTargetFitLiveTest {

    @Test
    void postgresAcceptsValuesFittedFromLiveMetadata() throws Exception {
        verify(new Fixture(
                "jdbc:postgresql://127.0.0.1:5433/sourcedb", "postgres", required("postgres.password"),
                null, "public", "ftdm_mask002_fit",
                "create table public.ftdm_mask002_fit (id integer, short_text varchar(4), amount numeric(5,2), active boolean, event_date date)",
                "drop table if exists public.ftdm_mask002_fit", false));
    }

    @Test
    void oracleAcceptsValuesFittedFromLiveMetadata() throws Exception {
        verify(new Fixture(
                "jdbc:oracle:thin:@127.0.0.1:1521:XE", "BE_CARDS", required("oracle.password"),
                null, "BE_CARDS", "FTDM_MASK002_FIT",
                "create table BE_CARDS.FTDM_MASK002_FIT (ID number(10), SHORT_TEXT varchar2(4 char), AMOUNT number(5,2), ACTIVE number(1), EVENT_DATE date)",
                "drop table BE_CARDS.FTDM_MASK002_FIT purge", true));
    }

    @Test
    void mysqlAcceptsValuesFittedFromLiveMetadata() throws Exception {
        String url = "jdbc:mysql://127.0.0.1:3306/digital_engagement?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        try (Connection connection = DriverManager.getConnection(url, "be_digital", required("mysql.password"))) {
            connection.setAutoCommit(false);
            Fixture fixture = new Fixture(url, "be_digital", "", "digital_engagement", null,
                    "user_profiles", null, null, false);
            try {
                Map<String, Shape> shapes = shapes(connection.getMetaData(), fixture);
                long profileId;
                try (Statement statement = connection.createStatement();
                     ResultSet rows = statement.executeQuery("select profile_id from digital_engagement.user_profiles limit 1")) {
                    rows.next();
                    profileId = rows.getLong(1);
                }
                String longName = "\uD83D\uDE00".repeat(121);
                Object fittedName = fit(longName, fixture, shapes.get("display_name"), "display_name");
                assertEquals(120, fittedName.toString().codePointCount(0, fittedName.toString().length()));

                try (PreparedStatement update = connection.prepareStatement(
                        "update digital_engagement.user_profiles set "
                                + "display_name=?,preferred_language=?,time_zone=?,accessibility_mode=?,last_profile_update=? "
                                + "where profile_id=?")) {
                    update.setObject(1, fittedName);
                    update.setObject(2, fit("en", fixture, shapes.get("preferred_language"), "preferred_language"));
                    update.setObject(3, fit("America/New_York", fixture, shapes.get("time_zone"), "time_zone"));
                    update.setObject(4, fit("STANDARD", fixture, shapes.get("accessibility_mode"), "accessibility_mode"));
                    update.setObject(5, fit("2026-07-22 12:34:56", fixture, shapes.get("last_profile_update"), "last_profile_update"));
                    update.setObject(6, fit(Long.toString(profileId), fixture, shapes.get("profile_id"), "profile_id"));
                    assertEquals(1, update.executeUpdate());
                }
                try (PreparedStatement query = connection.prepareStatement(
                        "select display_name from digital_engagement.user_profiles where profile_id=?")) {
                    query.setLong(1, profileId);
                    try (ResultSet rows = query.executeQuery()) {
                        rows.next();
                        assertEquals(120, rows.getString(1).codePointCount(0, rows.getString(1).length()));
                    }
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private static void verify(Fixture fixture) throws Exception {
        try (Connection connection = DriverManager.getConnection(fixture.url(), fixture.user(), fixture.password())) {
            drop(connection, fixture);
            try (Statement statement = connection.createStatement()) {
                statement.execute(fixture.createSql());
            }
            try {
                Map<String, Shape> shapes = shapes(connection.getMetaData(), fixture);
                Object id = fit("42", fixture, shapes.get("id"), "id");
                Object text = fit("ab\uD83D\uDE00\u00E7de", fixture, shapes.get("short_text"), "short_text");
                Object amount = fit("924.41", fixture, shapes.get("amount"), "amount");
                Object active = fit("1", fixture, shapes.get("active"), "active");
                Object date = fit("2026-07-22", fixture, shapes.get("event_date"), "event_date");

                assertEquals("ab\uD83D\uDE00\u00E7", text);
                assertThrows(ApiException.class,
                        () -> fit("1000.00", fixture, shapes.get("amount"), "amount"));
                assertThrows(ApiException.class,
                        () -> fit("DECLINED", fixture, shapes.get("id"), "id"));

                String qualified = qualified(fixture);
                try (PreparedStatement insert = connection.prepareStatement(
                        "insert into " + qualified + " (id, short_text, amount, active, event_date) values (?,?,?,?,?)")) {
                    insert.setObject(1, id);
                    insert.setObject(2, text);
                    insert.setObject(3, amount);
                    insert.setObject(4, active);
                    insert.setObject(5, date);
                    assertEquals(1, insert.executeUpdate());
                }
                try (Statement statement = connection.createStatement();
                     ResultSet rows = statement.executeQuery("select short_text, amount from " + qualified)) {
                    rows.next();
                    assertEquals("ab\uD83D\uDE00\u00E7", rows.getString(1));
                    assertEquals("924.41", rows.getBigDecimal(2).toPlainString());
                }
            } finally {
                drop(connection, fixture);
            }
        }
    }

    private static Map<String, Shape> shapes(DatabaseMetaData metadata, Fixture fixture) throws Exception {
        Map<String, Shape> result = new LinkedHashMap<>();
        try (ResultSet columns = metadata.getColumns(
                fixture.catalog(), fixture.schema(), fixture.table(), "%")) {
            while (columns.next()) {
                result.put(columns.getString("COLUMN_NAME").toLowerCase(Locale.ROOT), new Shape(
                        columns.getInt("DATA_TYPE"), columns.getInt("COLUMN_SIZE"), columns.getInt("DECIMAL_DIGITS")));
            }
        }
        assertTrue(result.size() >= 5, "live metadata column count for " + fixture.url());
        return result;
    }

    private static Object fit(String value, Fixture fixture, Shape shape, String column) {
        return ProvisioningService.fitValueForTarget(value,
                fixture.schema() == null ? fixture.catalog() : fixture.schema(), fixture.table(), column,
                shape.sqlType(), shape.size(), shape.scale());
    }

    private static void drop(Connection connection, Fixture fixture) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(fixture.dropSql());
        } catch (Exception error) {
            if (!fixture.ignoreMissingDrop()) throw error;
        }
    }

    private static String qualified(Fixture fixture) {
        String owner = fixture.schema() == null ? fixture.catalog() : fixture.schema();
        return owner + "." + fixture.table();
    }

    private static String required(String name) {
        String value = System.getProperty("forgetdm.mask002." + name);
        if (value == null || value.isBlank())
            throw new IllegalStateException("Missing -Dforgetdm.mask002." + name);
        return value;
    }

    private record Shape(int sqlType, int size, int scale) { }

    private record Fixture(String url, String user, String password, String catalog, String schema,
                           String table, String createSql, String dropSql, boolean ignoreMissingDrop) { }
}
