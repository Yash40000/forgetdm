package io.forgetdm.provision;

import io.forgetdm.core.mask.MaskContext;
import io.forgetdm.core.mask.MaskFunction;
import io.forgetdm.core.mask.MaskingEngine;
import io.forgetdm.policy.MaskingRuleEntity;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnabledIfSystemProperty(named = "forgetdm.mask004.live", matches = "true")
class MaskingCrossDatabaseJoinLiveTest {

    @Test
    void relatedKeysRetainIdenticalValuesAndJoinCountsAcrossConnectors() throws Exception {
        Dataset expected = dataset();
        Map<String, ConnectorResult> results = new LinkedHashMap<>();
        for (Fixture fixture : fixtures()) results.put(fixture.name(), verify(fixture, expected));

        String checksum = expected.checksum();
        for (Map.Entry<String, ConnectorResult> entry : results.entrySet()) {
            assertEquals(expected.children().size(), entry.getValue().joinCount(),
                    entry.getKey() + " lost parent/child joins");
            assertEquals(checksum, entry.getValue().checksum(),
                    entry.getKey() + " changed canonical relationship values");
            System.out.printf("MASK-004 %s joins=%d checksum=%s%n",
                    entry.getKey(), entry.getValue().joinCount(), entry.getValue().checksum());
        }
    }

    private static Dataset dataset() throws Exception {
        ProvisioningService.KeyEdge edge = new ProvisioningService.KeyEdge(
                "customers", "customer_id", "accounts", "customer_id");
        Map<String, String> salts = ProvisioningService.buildKeyConsistencySalts(List.of(edge));
        MaskingRuleEntity rule = new MaskingRuleEntity();
        rule.setFunction("FORMAT_PRESERVE");
        String parentSalt = ProvisioningService.saltFor(rule, "customers", "customer_id", salts);
        String childSalt = ProvisioningService.saltFor(rule, "accounts", "customer_id", salts);
        assertEquals(parentSalt, childSalt);

        MaskingEngine engine = new MaskingEngine(required("forgetdm.mask004.masking.secret"))
                .withSeed("mask004-cross-database");
        List<String> parents = new ArrayList<>();
        List<String> children = new ArrayList<>();
        for (int index = 1; index <= 16; index++) {
            String source = "CUST-" + String.format("%06d", index);
            String parent = engine.mask(MaskFunction.FORMAT_PRESERVE, parentSalt,
                    source, null, null, new MaskContext(index));
            String child = engine.mask(MaskFunction.FORMAT_PRESERVE, childSalt,
                    source, null, null, new MaskContext(index + 100));
            assertEquals(parent, child);
            parents.add(parent);
            children.add(child);
            children.add(child);
            children.add(child);
        }
        assertEquals(parents.size(), new LinkedHashSet<>(parents).size(), "masked parent collision in fixture");
        return new Dataset(List.copyOf(parents), List.copyOf(children));
    }

    private static ConnectorResult verify(Fixture fixture, Dataset expected) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                fixture.url(), fixture.user(), fixture.password())) {
            List<String> parents = queryValues(connection, fixture, "id", expected.parents());
            List<String> children = queryValues(connection, fixture, "parent_id", expected.children());
            long joins = queryJoinCount(connection, fixture, expected.parents(), expected.children());
            return new ConnectorResult(joins, new Dataset(parents, children).checksum());
        }
    }

    private static List<String> queryValues(Connection connection, Fixture fixture,
                                            String alias, List<String> values) throws Exception {
        String sql = "select " + alias + " from (" + rowsSql(fixture, alias, values.size())
                + ") values_in_db order by " + alias;
        List<String> read = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, 1, values);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) read.add(rows.getString(1));
            }
        }
        List<String> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        assertEquals(sorted, read, fixture.name() + " changed a key during JDBC/SQL round trip");
        return List.copyOf(read);
    }

    private static long queryJoinCount(Connection connection, Fixture fixture,
                                       List<String> parents, List<String> children) throws Exception {
        String sql = "select count(*) from (" + rowsSql(fixture, "id", parents.size()) + ") p join ("
                + rowsSql(fixture, "parent_id", children.size()) + ") c on p.id=c.parent_id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int next = bind(statement, 1, parents);
            bind(statement, next, children);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalStateException("No join count from " + fixture.name());
                return row.getLong(1);
            }
        }
    }

    private static String rowsSql(Fixture fixture, String alias, int count) {
        List<String> rows = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String select = "select cast(? as " + fixture.textType() + ") " + alias;
            if (fixture.oracle()) select += " from dual";
            rows.add(select);
        }
        return String.join(" union all ", rows);
    }

    private static int bind(PreparedStatement statement, int start, List<String> values) throws Exception {
        int position = start;
        for (String value : values) statement.setString(position++, value);
        return position;
    }

    private static List<Fixture> fixtures() {
        return List.of(
                new Fixture("PostgreSQL",
                        property("forgetdm.mask004.postgres.url", "jdbc:postgresql://127.0.0.1:5433/sourcedb"),
                        property("forgetdm.mask004.postgres.user", "postgres"),
                        required("forgetdm.mask002.postgres.password"), "varchar(80)", false),
                new Fixture("Oracle",
                        property("forgetdm.mask004.oracle.url", "jdbc:oracle:thin:@127.0.0.1:1521:XE"),
                        property("forgetdm.mask004.oracle.user", "BE_CARDS"),
                        required("forgetdm.mask002.oracle.password"), "varchar2(80 char)", true),
                new Fixture("MySQL",
                        property("forgetdm.mask004.mysql.url",
                                "jdbc:mysql://127.0.0.1:3306/digital_engagement?useSSL=false"
                                        + "&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8"),
                        property("forgetdm.mask004.mysql.user", "be_digital"),
                        required("forgetdm.mask002.mysql.password"), "char(80)", false));
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
                           String textType, boolean oracle) { }

    private record ConnectorResult(long joinCount, String checksum) { }

    private record Dataset(List<String> parents, List<String> children) {
        private String checksum() throws Exception {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                write(output, sorted(parents));
                write(output, sorted(children));
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        }

        private static List<String> sorted(List<String> values) {
            List<String> sorted = new ArrayList<>(values);
            Collections.sort(sorted);
            return sorted;
        }

        private static void write(DataOutputStream output, List<String> values) throws Exception {
            output.writeInt(values.size());
            for (String value : values) {
                byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
                output.writeInt(utf8.length);
                output.write(utf8);
            }
        }
    }
}
