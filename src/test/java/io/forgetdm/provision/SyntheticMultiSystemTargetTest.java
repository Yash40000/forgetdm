package io.forgetdm.provision;

import io.forgetdm.audit.AuditService;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SyntheticMultiSystemTargetTest {

    private static final String TARGET_ONE_URL = "jdbc:h2:mem:synthetic_multi_target_one;DB_CLOSE_DELAY=-1";
    private static final String TARGET_TWO_URL = "jdbc:h2:mem:synthetic_multi_target_two;DB_CLOSE_DELAY=-1";
    private static final String TARGET_THREE_URL = "jdbc:h2:mem:synthetic_multi_target_three;DB_CLOSE_DELAY=-1";

    @Test
    void loadsOneLogicalDatasetIntoDifferentPhysicalTargets() throws Exception {
        resetTarget(TARGET_ONE_URL, """
                DROP TABLE IF EXISTS "pg_customer";
                CREATE TABLE "pg_customer" (
                  "cust_id" BIGINT PRIMARY KEY,
                  "full_nm" VARCHAR(120)
                );
                """);
        resetTarget(TARGET_TWO_URL, """
                DROP TABLE IF EXISTS "ora_party";
                CREATE TABLE "ora_party" (
                  "party_no" BIGINT PRIMARY KEY,
                  "party_name" VARCHAR(120)
                );
                """);

        DataSourceService dataSources = mock(DataSourceService.class);
        when(dataSources.get(101L)).thenReturn(target(101L, TARGET_ONE_URL));
        when(dataSources.get(202L)).thenReturn(target(202L, TARGET_TWO_URL));
        when(dataSources.getTargetCapable(101L)).thenReturn(target(101L, TARGET_ONE_URL));
        when(dataSources.getTargetCapable(202L)).thenReturn(target(202L, TARGET_TWO_URL));
        SyntheticGenService gen = new SyntheticGenService(
                dataSources,
                new ConnectionFactory(),
                new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:synthetic_multi_meta;DB_CLOSE_DELAY=-1")),
                mock(AuditService.class),
                2,
                2555);

        SyntheticGenService.GenTable customer = new SyntheticGenService.GenTable("customer", 25L, List.of(
                new SyntheticGenService.GenColumn("customer_id", "SEQUENCE", "", "", true, null, null, "BIGINT", null, null),
                new SyntheticGenService.GenColumn("full_name", "FULL_NAME", "US", "ANY", false, null, null, "VARCHAR", null, null)
        ));
        SyntheticGenService.GenPlan plan = new SyntheticGenService.GenPlan("multi-system", List.of(customer), 77L,
                "DB", null, null, false, false, null, "REPLACE", "DELETE", List.of(),
                500, 0, false, 1000, false, "SINGLE", null, null, List.of(
                new SyntheticGenService.TargetSystem("postgres-like", 101L, "PUBLIC",
                        false, false, null, "REPLACE", "DELETE", List.of(), 500, 0, false, 1000, false,
                        List.of(new SyntheticGenService.TableProjection("customer", "pg_customer", List.of(
                                new SyntheticGenService.ColumnProjection("customer_id", "cust_id", "BIGINT"),
                                new SyntheticGenService.ColumnProjection("full_name", "full_nm", "VARCHAR")
                        )))),
                new SyntheticGenService.TargetSystem("oracle-like", 202L, "PUBLIC",
                        false, false, null, "REPLACE", "DELETE", List.of(), 500, 0, false, 1000, false,
                        List.of(new SyntheticGenService.TableProjection("customer", "ora_party", List.of(
                                new SyntheticGenService.ColumnProjection("customer_id", "party_no", "BIGINT"),
                                new SyntheticGenService.ColumnProjection("full_name", "party_name", "VARCHAR")
                        ))))
        ));

        Map<String, Object> result = gen.generate(plan);

        assertEquals(true, result.get("multiSystem"));
        assertEquals(25L, count(TARGET_ONE_URL, "SELECT COUNT(*) FROM \"pg_customer\""));
        assertEquals(25L, count(TARGET_TWO_URL, "SELECT COUNT(*) FROM \"ora_party\""));
        assertEquals(
                rows(TARGET_ONE_URL, "SELECT \"cust_id\" id, \"full_nm\" name FROM \"pg_customer\" ORDER BY \"cust_id\""),
                rows(TARGET_TWO_URL, "SELECT \"party_no\" id, \"party_name\" name FROM \"ora_party\" ORDER BY \"party_no\"")
        );
        assertTrue(((List<?>) result.get("targets")).size() == 2);
    }

    @Test
    void remapsDerivedColumnsAndEnforcesTargetCheckRules() throws Exception {
        resetTarget(TARGET_THREE_URL, """
                DROP TABLE IF EXISTS "acct";
                CREATE TABLE "acct" (
                  "id" BIGINT PRIMARY KEY,
                  "fname" VARCHAR(80),
                  "lname" VARCHAR(80),
                  "stat_cd" VARCHAR(20) CHECK ("stat_cd" IN ('OPEN','CLOSED')),
                  "email_addr" VARCHAR(160)
                );
                """);

        DataSourceService dataSources = mock(DataSourceService.class);
        when(dataSources.get(303L)).thenReturn(target(303L, TARGET_THREE_URL));
        when(dataSources.getTargetCapable(303L)).thenReturn(target(303L, TARGET_THREE_URL));
        SyntheticGenService gen = service(dataSources);

        SyntheticGenService.GenTable account = new SyntheticGenService.GenTable("account", 40L, List.of(
                new SyntheticGenService.GenColumn("account_id", "SEQUENCE", "", "", true, null, null, "BIGINT", null, null),
                new SyntheticGenService.GenColumn("first_name", "FIRST_NAME", "US", "ANY", false, null, null, "VARCHAR", null, null),
                new SyntheticGenService.GenColumn("last_name", "LAST_NAME", "US", "", false, null, null, "VARCHAR", null, null),
                new SyntheticGenService.GenColumn("status", "LITERAL", "BAD", "", false, null, null, "VARCHAR", null, null),
                new SyntheticGenService.GenColumn("email", "TEMPLATE", "${first_name:lower}.${last_name:lower}@${domain}", "", false, null, null, "VARCHAR", null, null)
        ));
        SyntheticGenService.GenPlan plan = new SyntheticGenService.GenPlan("multi-derived", List.of(account), 91L,
                "DB", null, null, false, false, null, "REPLACE", "DELETE", List.of(),
                500, 0, false, 1000, false, "SINGLE", null, null, List.of(
                new SyntheticGenService.TargetSystem("h2-target", 303L, "PUBLIC",
                        false, false, null, "REPLACE", "DELETE", List.of(), 500, 0, false, 1000, false,
                        List.of(new SyntheticGenService.TableProjection("account", "acct", List.of(
                                new SyntheticGenService.ColumnProjection("account_id", "id", "BIGINT"),
                                new SyntheticGenService.ColumnProjection("first_name", "fname", "VARCHAR"),
                                new SyntheticGenService.ColumnProjection("last_name", "lname", "VARCHAR"),
                                new SyntheticGenService.ColumnProjection("status", "stat_cd", "VARCHAR"),
                                new SyntheticGenService.ColumnProjection("email", "email_addr", "VARCHAR")
                        ))))
        ));

        Map<String, Object> result = gen.generate(plan);

        assertEquals(true, result.get("multiSystem"));
        assertEquals(40L, count(TARGET_THREE_URL, "SELECT COUNT(*) FROM \"acct\""));
        assertEquals(0L, count(TARGET_THREE_URL, "SELECT COUNT(*) FROM \"acct\" WHERE \"stat_cd\" NOT IN ('OPEN','CLOSED')"));
        assertEquals(0L, count(TARGET_THREE_URL, "SELECT COUNT(*) FROM \"acct\" WHERE \"email_addr\" NOT LIKE '%@%'"));
    }

    @Test
    void planSummaryUsesStreamingReplayForLargeMultiTargetRuns() {
        DataSourceService dataSources = mock(DataSourceService.class);
        when(dataSources.get(101L)).thenReturn(target(101L, TARGET_ONE_URL));
        when(dataSources.getTargetCapable(101L)).thenReturn(target(101L, TARGET_ONE_URL));
        SyntheticGenService gen = service(dataSources);

        SyntheticGenService.GenTable customer = new SyntheticGenService.GenTable("customer", 600_000L, List.of(
                new SyntheticGenService.GenColumn("customer_id", "SEQUENCE", "", "", true, null, null, "BIGINT", null, null),
                new SyntheticGenService.GenColumn("full_name", "FULL_NAME", "US", "ANY", false, null, null, "VARCHAR", null, null)
        ));
        SyntheticGenService.GenPlan plan = new SyntheticGenService.GenPlan("large-multi-system", List.of(customer), 77L,
                "DB", null, null, false, false, null, "INSERT", "NONE", List.of(),
                5000, 0, false, 1000, false, "LOCAL_PARTITIONED", 4, null, List.of(
                new SyntheticGenService.TargetSystem("postgres-like", 101L, "PUBLIC",
                        false, false, null, "INSERT", "NONE", List.of(), 5000, 0, false, 1000, false,
                        List.of(new SyntheticGenService.TableProjection("customer", "pg_customer", List.of(
                                new SyntheticGenService.ColumnProjection("customer_id", "cust_id", "BIGINT"),
                                new SyntheticGenService.ColumnProjection("full_name", "full_nm", "VARCHAR")
                        ))))
        ));

        Map<String, Object> summary = gen.planSummary(plan);

        assertEquals(true, summary.get("multiSystem"));
        assertEquals("STREAMING_REPLAY", summary.get("memoryMode"));
        assertEquals("STREAMING_REPLAY_PER_TARGET", summary.get("writeMode"));
    }

    private static DataSourceEntity target(long id, String url) {
        DataSourceEntity ds = new DataSourceEntity();
        ds.setId(id);
        ds.setName("target-" + id);
        ds.setKind("H2");
        ds.setJdbcUrl(url);
        return ds;
    }

    private static SyntheticGenService service(DataSourceService dataSources) {
        return new SyntheticGenService(
                dataSources,
                new ConnectionFactory(),
                new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:synthetic_multi_meta_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")),
                mock(AuditService.class),
                2,
                2555);
    }

    private static void resetTarget(String url, String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) {
            for (String statement : sql.split(";")) {
                if (!statement.isBlank()) st.execute(statement);
            }
        }
    }

    private static long count(String url, String sql) {
        return new JdbcTemplate(new DriverManagerDataSource(url)).queryForObject(sql, Long.class);
    }

    private static List<Map<String, Object>> rows(String url, String sql) {
        return new JdbcTemplate(new DriverManagerDataSource(url)).queryForList(sql);
    }
}
