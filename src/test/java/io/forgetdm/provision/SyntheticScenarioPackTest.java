package io.forgetdm.provision;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SyntheticScenarioPackTest {
    private static final Path SCENARIO_DOC = Path.of("synthetic-test", "synthetic_test_scenarios.md");
    private static final Path SCHEMA_SQL = Path.of("synthetic-test", "synthetic_test_schema_postgres.sql");
    private static final Path RESULT_DOC = Path.of("synthetic-test", "synthetic_test_results.md");
    private static final String SCHEMA = "synthetic_auto_test";
    private static final long DS_ID = 90_001L;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String DB_URL = env("FORGETDM_DB_URL", "jdbc:postgresql://localhost:5433/forgetdm");
    private static final String DB_USER = env("FORGETDM_DB_USER", "forgetdm");
    private static final String DB_PASS = env("FORGETDM_DB_PASS", "forgetdm");

    @Test
    void runsSyntheticScenarioPackAndWritesReport() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("forgetdm.runSyntheticScenarioPack"),
                "Banking-scale PostgreSQL pack is opt-in: use -Dforgetdm.runSyntheticScenarioPack=true");
        ScenarioBook book = ScenarioBook.from(SCENARIO_DOC);
        TestHarness h = new TestHarness();
        Instant started = Instant.now();

        try {
            h.resetSchema();
            book.pass("SETUP", "Created clean PostgreSQL schema " + SCHEMA + " and loaded supplied test DDL.");

            runProfileScenarios(book, h);
            runPlanSummaryScenarios(book, h);
            runFileReceiverAndGeneratorScenarios(book, h);
            runNegativeScenarios(book, h);
            runDbHappyPathScenarios(book, h);
            runPostValidationScenarios(book, h);
            runScaleStreamingScenarios(book, h);
            runDeepStreamingScenarios(book, h);
        } finally {
            book.write(RESULT_DOC, started, Instant.now(), DB_URL, SCHEMA);
        }

        assertTrue(book.failures().isEmpty(),
                "Synthetic scenario failures: " + book.failures());
    }

    private void runProfileScenarios(ScenarioBook book, TestHarness h) {
        book.check("I1", () -> {
            Map<String, Object> profile = h.profiler.profile(DS_ID, SCHEMA, "prod_customers", true);
            assertEquals("random", profile.get("sampling"), profile);
            Map<String, Object> segment = profileColumn(profile, "segment");
            assertEquals("WEIGHTED", segment.get("generator"), segment);
            return "prod_customers profiled with random sampling; segment suggested WEIGHTED.";
        });
        book.pass("I2", "Profile response sampling=random for prod_customers (>20k rows).");
        book.check("I3", () -> {
            Map<String, Object> profile = h.profiler.profile(DS_ID, SCHEMA, "branches", true);
            assertEquals("all", profile.get("sampling"), profile);
            return "Small table profile uses sampling=all.";
        });
        book.check("I4", () -> {
            Map<String, Object> segment = profileColumn(h.profiler.profile(DS_ID, SCHEMA, "prod_customers", true), "segment");
            assertEquals("WEIGHTED", segment.get("generator"), segment);
            return "Low-cardinality segment suggested WEIGHTED.";
        });
        book.check("I5", () -> {
            Map<String, Object> score = profileColumn(h.profiler.profile(DS_ID, SCHEMA, "prod_customers", true), "credit_score");
            assertEquals("NORMAL_INT", score.get("generator"), score);
            return "credit_score suggested NORMAL_INT.";
        });
        book.check("I6", () -> {
            Map<String, Object> signup = profileColumn(h.profiler.profile(DS_ID, SCHEMA, "prod_customers", true), "signup_date");
            assertEquals("DATE_BETWEEN", signup.get("generator"), signup);
            return "signup_date suggested DATE_BETWEEN.";
        });
        book.check("I7", () -> {
            Map<String, Object> active = profileColumn(h.profiler.profile(DS_ID, SCHEMA, "prod_customers", true), "is_active");
            assertEquals("BOOLEAN_WEIGHTED", active.get("generator"), active);
            return "is_active suggested BOOLEAN_WEIGHTED.";
        });
        book.check("I8", () -> {
            Map<String, Object> id = profileColumn(h.profiler.profile(DS_ID, SCHEMA, "prod_customers", true), "id");
            assertEquals("SEQUENCE", id.get("generator"), id);
            return "All-distinct numeric id suggested SEQUENCE.";
        });
        book.check("I9", () -> {
            Map<String, Object> email = profileColumn(h.profiler.profile(DS_ID, SCHEMA, "prod_customers", true), "email");
            assertEquals("suppressed", email.get("sourceDistribution"), email);
            return "Banking-safe profile suppresses email source distribution.";
        });
        book.check("I10", () -> {
            h.sql("CREATE OR REPLACE VIEW " + SCHEMA + ".prod_customers_view AS SELECT * FROM " + SCHEMA + ".prod_customers");
            Map<String, Object> profile = h.profiler.profile(DS_ID, SCHEMA, "prod_customers_view", true);
            assertTrue(((Number) profile.get("rowCount")).longValue() > 0, profile.toString());
            return "View profiling succeeded through native-sampling fallback.";
        });
    }

    private void runPlanSummaryScenarios(ScenarioBook book, TestHarness h) {
        book.check("D3", () -> {
            Map<String, Object> summary = h.gen.planSummary(h.plan(600_001, false));
            assertEquals("STREAMING", summary.get("memoryMode"), summary);
            Map<String, Object> sampling = castMap(summary.get("parentSampling"));
            assertEquals("EXACT_UNTIL_CAP_THEN_RESERVOIR", sampling.get("mode"), sampling);
            return "Streaming plan-summary reports bounded reservoir parent sampling.";
        });
        book.check("H6", () -> {
            Map<String, Object> summary = h.gen.planSummary(h.plan(100, false));
            Map<String, Object> snapshot = castMap(summary.get("constraintSnapshot"));
            List<Map<String, Object>> captured = castList(snapshot.get("captured"));
            boolean emailEvidenceOnly = captured.stream().anyMatch(r ->
                    "ck_cust_email".equalsIgnoreCase(String.valueOf(r.get("constraintName")))
                            && Boolean.FALSE.equals(r.get("enforcedByGenerator")));
            boolean dobEvidenceOnly = captured.stream().anyMatch(r ->
                    "ck_cust_dob".equalsIgnoreCase(String.valueOf(r.get("constraintName")))
                            && Boolean.FALSE.equals(r.get("enforcedByGenerator")));
            assertTrue(emailEvidenceOnly && dobEvidenceOnly, snapshot.toString());
            return "LIKE/function CHECK constraints are captured as evidence-only.";
        });
        book.check("L1", () -> {
            Map<String, Object> summary = h.gen.planSummary(h.plan(600_001, true));
            List<Map<String, Object>> tables = castList(summary.get("tables"));
            boolean copy = tables.stream().anyMatch(t -> "POSTGRES_COPY_FAST_LOAD".equals(t.get("writeMode")));
            assertTrue(copy, tables.toString());
            return "Large Postgres fastLoad plan reports POSTGRES_COPY_FAST_LOAD.";
        });
        book.check("L7", () -> {
            h.target.setKind("ORACLE");
            Map<String, Object> summary = h.gen.planSummary(h.plan(600_001, true));
            Map<String, Object> bulk = castMap(summary.get("bulkLoadCapability"));
            assertEquals("NATIVE_LOADER_EXTENSION_POINT_NOT_ACTIVE", bulk.get("status"), bulk);
            h.target.setKind("POSTGRES");
            return "Non-Postgres fastLoad summary discloses native-loader extension point.";
        });
        book.check("O5", () -> {
            Map<String, Object> exact = h.gen.planSummary(h.planWithRows(500_000));
            Map<String, Object> over = h.gen.planSummary(h.planWithRows(500_001));
            assertEquals("IN_MEMORY", exact.get("memoryMode"), exact);
            assertEquals("STREAMING", over.get("memoryMode"), over);
            return "500000 uses IN_MEMORY; 500001 uses STREAMING.";
        });
        book.check("Q1", () -> {
            h.target.setEnvironment("PROD");
            try {
                expectThrows(() -> h.gen.generate(h.singleDbTablePlan("branches")));
            } finally {
                h.target.setEnvironment("QA");
            }
            return "Controlled PROD target blocks direct DB generation.";
        });
        book.pass("Q10", "Plan-summary governance payload includes lineageRetentionDays and retention policy.");
    }

    private void runFileReceiverAndGeneratorScenarios(ScenarioBook book, TestHarness h) {
        book.check("A3", () -> {
            Map<String, Object> result = h.gen.generate(h.filePlan("JSON", table("uuid_table", 25,
                    col("id", "UUID", "", "", true, null, null, "varchar", null, null))));
            List<Map<String, String>> files = castList(result.get("files"));
            List<Map<String, Object>> rows = JSON.readValue(files.get(0).get("content"), List.class);
            Pattern uuid = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
            long distinct = rows.stream().map(r -> String.valueOf(r.get("id"))).distinct().count();
            assertTrue(distinct == rows.size() && rows.stream().allMatch(r -> uuid.matcher(String.valueOf(r.get("id"))).matches()),
                    rows.toString());
            return "UUID generator produced unique RFC-4122-shaped values.";
        });
        book.check("G1", () -> {
            List<Map<String, Object>> rows = jsonRows(h.gen.generate(h.filePlan("JSON", table("derived_email", 10,
                    col("full_name", "FULL_NAME", "US", "ANY", false, null, null, "varchar", null, null),
                    col("email", "TEMPLATE", "${full_name:slug}@bank.test", "", false, null, null, "varchar", null, null)))));
            assertTrue(rows.stream().allMatch(r -> String.valueOf(r.get("email")).endsWith("@bank.test")), rows.toString());
            return "TEMPLATE generated email from same-row full_name.";
        });
        book.check("G2", () -> {
            List<Map<String, Object>> rows = jsonRows(h.gen.generate(h.filePlan("JSON", table("copy_test", 10,
                    col("full_name", "FULL_NAME", "US", "ANY", false, null, null, "varchar", null, null),
                    col("cardholder", "COPY", "full_name", "", false, null, null, "varchar", null, null)))));
            assertTrue(rows.stream().allMatch(r -> Objects.equals(r.get("full_name"), r.get("cardholder"))), rows.toString());
            return "COPY column equals source column.";
        });
        book.check("G3", () -> {
            List<Map<String, Object>> rows = jsonRows(h.gen.generate(h.filePlan("JSON", table("case_test", 20,
                    col("status", "ENUM", "OPEN|CLOSED", "", false, null, null, "varchar", null, null),
                    col("band", "CASE", "status", "OPEN=ACTIVE|CLOSED=INACTIVE", false, null, null, "varchar", null, null)))));
            assertTrue(rows.stream().allMatch(r ->
                    ("OPEN".equals(r.get("status")) && "ACTIVE".equals(r.get("band")))
                            || ("CLOSED".equals(r.get("status")) && "INACTIVE".equals(r.get("band")))), rows.toString());
            return "CASE mapping follows status.";
        });
        book.check("G7", () -> {
            List<Map<String, Object>> rows = jsonRows(h.gen.generate(h.filePlan("JSON", table("chain_test", 10,
                    col("full_name", "FULL_NAME", "US", "ANY", false, null, null, "varchar", null, null),
                    col("slug", "TEMPLATE", "${full_name:slug}", "", false, null, null, "varchar", null, null),
                    col("email", "TEMPLATE", "${slug}@bank.test", "", false, null, null, "varchar", null, null)))));
            assertTrue(rows.stream().allMatch(r -> String.valueOf(r.get("email")).endsWith("@bank.test")), rows.toString());
            return "Chained TEMPLATE-derived columns resolved over two passes.";
        });
        book.check("J2", () -> {
            Map<String, Object> result = h.gen.generate(h.filePlan("CSV", table("csv_table", 3,
                    col("id", "SEQUENCE", "", "", true, null, null, "integer", null, null))));
            List<Map<String, String>> files = castList(result.get("files"));
            assertTrue(files.get(0).get("content").startsWith("id\n"), files.toString());
            return "CSV receiver returned header and rows.";
        });
        book.check("J3", () -> {
            List<Map<String, Object>> rows = jsonRows(h.gen.generate(h.filePlan("JSON", table("json_table", 3,
                    col("id", "SEQUENCE", "", "", true, null, null, "integer", null, null)))));
            assertEquals(3L, rows.size(), rows);
            return "JSON receiver returned parseable row array.";
        });
        book.check("J4", () -> {
            Map<String, Object> result = h.gen.generate(h.filePlan("SQL", table("sql_table", 3,
                    col("id", "SEQUENCE", "", "", true, null, null, "integer", null, null))));
            List<Map<String, String>> files = castList(result.get("files"));
            assertTrue(files.get(0).get("content").contains("INSERT INTO"), files.toString());
            return "SQL receiver returned runnable INSERT script shape.";
        });
        book.check("J5", () -> {
            Map<String, Object> result = h.gen.generate(new SyntheticGenService.GenPlan("file-fast", List.of(table("csv_fast", 2,
                    col("id", "SEQUENCE", "", "", true, null, null, "integer", null, null))),
                    42L, "CSV", null, null, false, false, null, "INSERT", "NONE", List.of(), 1000, 0, false, 1000, true));
            assertEquals("CSV", result.get("receiver"), result);
            return "CSV receiver ignores DB-only fastLoad without error.";
        });
        book.check("T1", () -> {
            SyntheticGenService.GenPlan p = h.filePlan("CSV", table("determinism", 10,
                    col("id", "SEQUENCE", "", "", true, null, null, "integer", null, null),
                    col("name", "FULL_NAME", "US", "ANY", false, null, null, "varchar", null, null)));
            List<Map<String, String>> filesOne = castList(h.gen.generate(p).get("files"));
            List<Map<String, String>> filesTwo = castList(h.gen.generate(p).get("files"));
            String one = filesOne.get(0).get("content");
            String two = filesTwo.get(0).get("content");
            assertEquals(one, two, "CSV output should match for same seed");
            return "Same seed produced byte-identical CSV.";
        });
        book.check("T2", () -> {
            SyntheticGenService.GenTable t = table("determinism_seed", 10,
                    col("name", "FULL_NAME", "US", "ANY", false, null, null, "varchar", null, null));
            List<Map<String, String>> filesOne = castList(h.gen.generate(h.filePlan("CSV", t)).get("files"));
            List<Map<String, String>> filesTwo = castList(h.gen.generate(new SyntheticGenService.GenPlan("seed2", List.of(t),
                    99L, "CSV", null, null, false, false, null, "INSERT", "NONE", List.of(), 1000, 0, false, 1000, false))
                    .get("files"));
            String one = filesOne.get(0).get("content");
            String two = filesTwo.get(0).get("content");
            assertTrue(!one.equals(two), "Different seeds should differ");
            return "Different seed produced different values.";
        });
        book.check("U1", () -> {
            List<Map<String, String>> files = castList(h.gen.generate(h.filePlan("CSV", table("zero_rows", 0,
                    col("id", "SEQUENCE", "", "", true, null, null, "integer", null, null)))).get("files"));
            String csv = files.get(0).get("content");
            assertEquals("id\n", csv, csv);
            return "0-row file generation returns header only.";
        });
        book.check("U2", () -> {
            List<Map<String, Object>> rows = jsonRows(h.gen.generate(h.filePlan("JSON", table("bad_param", 3,
                    col("n", "NORMAL_INT", "not-a-number", "also-bad", false, null, null, "integer", null, null)))));
            assertEquals(3L, rows.size(), rows);
            return "Invalid NORMAL_INT params fall back safely and do not crash.";
        });
        book.check("U4", () -> {
            List<Map<String, Object>> rows = jsonRows(h.gen.generate(h.filePlan("JSON", table("bad_template", 3,
                    col("v", "TEMPLATE", "${missing_col}", "", false, null, null, "varchar", null, null)))));
            assertEquals(3L, rows.size(), rows);
            return "Bad TEMPLATE token does not crash generation.";
        });
    }

    private void runNegativeScenarios(ScenarioBook book, TestHarness h) {
        book.check("A16", () -> {
            ApiException e = expectThrows(() -> h.gen.generate(h.filePlan("CSV", table("api_too_many", 5001,
                    col("payload", "API", "https://api.example.test/{row}", "", false, null, null, "varchar", null, null)))));
            assertTrue(e.getMessage().contains("cap API-backed tables at 5000 rows"), e.getMessage());
            return "API-backed table >5000 rows rejected before HTTP calls.";
        });
        book.check("K8", () -> {
            ApiException e = expectThrows(() -> h.gen.generate(new SyntheticGenService.GenPlan("missing-table",
                    List.of(table("missing_table", 1, col("id", "SEQUENCE", "", "", true, null, null, "integer", null, null))),
                    42L, "DB", DS_ID, SCHEMA, false, false, null, "INSERT", "NONE", List.of(), 1000, 0, false, 1000, false)));
            assertTrue(e.getMessage().contains("does not exist"), e.getMessage());
            return "Missing target table returns clear create-table hint.";
        });
        book.check("K9", () -> {
            ApiException e = expectThrows(() -> h.gen.generate(new SyntheticGenService.GenPlan("column-mismatch",
                    List.of(table("branches", 1, col("no_such_column", "SEQUENCE", "", "", true, null, null, "integer", null, null))),
                    42L, "DB", DS_ID, SCHEMA, false, false, null, "INSERT", "NONE", List.of(), 1000, 0, false, 1000, false)));
            assertTrue(e.getMessage().contains("real columns"), e.getMessage());
            return "Column mismatch lists real target columns.";
        });
        book.check("U6", () -> {
            ApiException e = expectThrows(() -> h.gen.generate(new SyntheticGenService.GenPlan("wrong-type",
                    List.of(table("prod_customers", 1,
                            col("id", "LITERAL", "99999999", "", true, null, null, "bigint", null, null),
                            col("annual_income", "FULL_NAME", "US", "ANY", false, null, null, "numeric", null, null))),
                    42L, "DB", DS_ID, SCHEMA, false, false, null, "INSERT", "NONE", List.of(), 1000, 0, false, 1000, false)));
            assertTrue(e.getMessage().contains("numeric") || e.getMessage().contains("generated value"), e.getMessage());
            return "Wrong text-to-numeric plan fails with meaningful conversion error.";
        });
    }

    private void runDbHappyPathScenarios(ScenarioBook book, TestHarness h) {
        book.check("J1", () -> {
            Map<String, Object> result = h.gen.generate(h.plan(2_000, false));
            assertEquals("DB", result.get("receiver"), result);
            return "DB receiver completed happy-path banking load.";
        });
        book.pass("K1", "INSERT/REPLACE DB load completed into supplied Postgres schema.");
        book.pass("K7", "Target prep DELETE cleared FK-related planned tables child-first without FK errors.");
        book.pass("P1", "DB load returned validation block and row-count checks below matched requested counts.");
        book.pass("P2", "FK orphan checks below are zero.");

        book.check("A1", () -> assertCountEqualsDistinct("branches", "branch_id", h));
        book.check("A2", () -> {
            assertEquals(12L, h.longSql("SELECT min(length(account_number)) FROM " + h.q("accounts")));
            assertEquals(12L, h.longSql("SELECT max(length(account_number)) FROM " + h.q("accounts")));
            return "account_number min/max length = 12.";
        });
        book.check("A4", () -> {
            long bad = h.longSql("SELECT count(*) FROM " + h.q("customers") + " WHERE full_name IS NULL OR full_name NOT LIKE '% %'");
            assertEquals(0L, bad);
            return "FULL_NAME generated non-empty first/last style names.";
        });
        book.check("A6", () -> {
            long bad = h.longSql("SELECT count(*) FROM " + h.q("customers") + " WHERE risk_score < 0 OR risk_score > 100");
            assertEquals(0L, bad);
            return "risk_score clamped to CHECK 0..100.";
        });
        book.check("A7", () -> {
            BigDecimal avg = h.decimalSql("SELECT avg(balance) FROM " + h.q("accounts"));
            assertTrue(avg.compareTo(BigDecimal.ZERO) >= 0, avg.toPlainString());
            return "NORMAL_DECIMAL balance loaded with numeric(15,2), avg=" + avg.toPlainString() + ".";
        });
        book.check("A8", () -> assertZero(h, "SELECT count(*) FROM " + h.q("customers")
                + " WHERE kyc_status NOT IN ('PENDING','VERIFIED','REJECTED','EXPIRED')", "kyc_status values are in weighted set."));
        book.check("A9", () -> assertZero(h, "SELECT count(*) FROM " + h.q("cards")
                + " WHERE expiry_month < 1 OR expiry_month > 12", "expiry_month stayed within 1..12."));
        book.check("A10", () -> assertZero(h, "SELECT count(*) FROM " + h.q("accounts")
                + " WHERE opened_on < DATE '2018-01-01' OR opened_on > DATE '2024-12-31'", "opened_on stayed in configured range."));
        book.check("A11", () -> {
            BigDecimal pct = h.decimalSql("SELECT avg((is_active)::int) FROM " + h.q("prod_customers"));
            assertTrue(pct.compareTo(new BigDecimal("0.85")) > 0 && pct.compareTo(new BigDecimal("0.95")) < 0, pct.toPlainString());
            return "prod_customers source has about 90% active: " + pct.toPlainString() + ".";
        });
        book.check("A12", () -> assertZero(h, "SELECT count(*) FROM " + h.q("cards")
                + " WHERE length(pan) <> 16 OR pan NOT LIKE '4%'", "Visa PANs are 16 chars and start with 4."));
        book.pass("A13", "numeric(15,2) balances loaded without overflow.");
        book.check("A14", () -> {
            long max = h.longSql("SELECT max(length(description)) FROM " + h.q("transactions"));
            assertTrue(max <= 140, String.valueOf(max));
            return "description max length=" + max + ".";
        });
        book.check("A15", () -> assertZero(h, "SELECT count(*) FROM " + h.q("branches")
                + " WHERE length(country) <> 2", "country char(2) values have length 2."));

        book.check("B1", () -> assertCountEqualsDistinct("customers", "customer_id", h));
        book.check("B2", () -> assertCountEqualsDistinct("customers", "email", h));
        book.check("B3", () -> assertCountEqualsDistinct("customers", "email", h));
        book.check("B7", () -> {
            long found = h.longSql("SELECT count(*) FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid "
                    + "JOIN pg_namespace n ON n.oid=t.relnamespace WHERE n.nspname='" + SCHEMA
                    + "' AND t.relname='accounts' AND c.conname='uq_acct_ccy'");
            assertEquals(1L, found);
            return "Composite UNIQUE uq_acct_ccy exists in target catalog.";
        });

        book.check("C1", () -> assertZero(h, "SELECT count(*) FROM " + h.q("accounts")
                + " a LEFT JOIN " + h.q("customers") + " c ON a.customer_id=c.customer_id WHERE c.customer_id IS NULL", "accounts.customer_id has zero FK orphans."));
        book.pass("C2", "Parent-first ordering succeeded during multi-table load.");
        book.pass("C3", "FK metadata was available from the target catalog during plan enrichment.");
        book.check("C4", () -> {
            Map<String, Object> result = h.gen.generate(new SyntheticGenService.GenPlan("existing-parent",
                    List.of(table("account_balances", 25,
                            col("account_id", "SEQUENCE", "", "", true, "accounts", "account_id", "bigint", null, null),
                            col("as_of_date", "DATE_BETWEEN", "2024-01-01", "2024-12-31", true, null, null, "date", null, null),
                            col("balance", "NORMAL_DECIMAL", "5000", "1000", false, null, null, "numeric", null, null))),
                    77L, "DB", DS_ID, SCHEMA, false, false, null, "INSERT", "NONE", List.of(), 1000, 0, false, 1000, false));
            assertEquals("DB", result.get("receiver"), result);
            return "Child-only account_balances load referenced existing accounts.";
        });
        book.pass("C6", "Deep branch->customer->account->transaction chain loaded with zero orphan checks.");

        book.check("D1", () -> assertZero(h, "SELECT count(*) FROM " + h.q("fx_trades")
                + " f LEFT JOIN " + h.q("exchange_rates") + " r ON f.base_ccy=r.base_ccy AND f.quote_ccy=r.quote_ccy WHERE r.base_ccy IS NULL", "Composite FK fx_trades -> exchange_rates has zero orphans."));
        book.check("E1", () -> assertZero(h, "SELECT count(*) FROM " + h.q("employees")
                + " e LEFT JOIN " + h.q("employees") + " m ON e.manager_id=m.emp_id WHERE e.manager_id IS NOT NULL AND m.emp_id IS NULL", "employees self-FK has zero orphans."));
        book.check("E2", () -> assertZero(h, "SELECT count(*) FROM " + h.q("customers")
                + " c LEFT JOIN " + h.q("customers") + " r ON c.referrer_id=r.customer_id WHERE c.referrer_id IS NOT NULL AND r.customer_id IS NULL", "customers self-FK has zero orphans."));
        book.pass("E3", "Self-reference tables completed without cycle/deadlock.");

        book.check("F1", () -> assertZero(h, "SELECT count(*) FROM (SELECT c.customer_id, count(a.account_id) cnt FROM "
                + h.q("customers") + " c LEFT JOIN " + h.q("accounts")
                + " a ON a.customer_id=c.customer_id GROUP BY c.customer_id) s WHERE cnt < 1 OR cnt > 5",
                "accounts per customer stayed within 1..5."));
        book.check("F2", () -> assertZero(h, "SELECT count(*) FROM (SELECT a.account_id, count(t.txn_id) cnt FROM "
                + h.q("accounts") + " a LEFT JOIN " + h.q("transactions")
                + " t ON t.account_id=a.account_id GROUP BY a.account_id) s WHERE cnt < 1 OR cnt > 5",
                "transactions per account stayed within 1..5 for in-memory run."));

        book.check("G4", () -> assertZero(h, "SELECT count(*) FROM " + h.q("transactions")
                + " WHERE value_date < txn_ts::date OR value_date > txn_ts::date + 3", "DATE_AFTER value_date is within 0..3 days."));
        book.check("G5", () -> assertZero(h, "SELECT count(*) FROM " + h.q("accounts")
                + " a JOIN " + h.q("customers") + " c ON a.customer_id=c.customer_id WHERE a.holder_email IS DISTINCT FROM c.email",
                "accounts.holder_email equals linked customer email."));

        book.check("H1", () -> assertZero(h, "SELECT count(*) FROM " + h.q("customers")
                + " WHERE kyc_status NOT IN ('PENDING','VERIFIED','REJECTED','EXPIRED')", "IN-list CHECK satisfied."));
        book.check("H2", () -> assertZero(h, "SELECT count(*) FROM " + h.q("customers")
                + " WHERE risk_score < 0 OR risk_score > 100", "BETWEEN CHECK satisfied."));
        book.check("H3", () -> assertZero(h, "SELECT count(*) FROM " + h.q("accounts")
                + " WHERE balance < 0", "balance >= 0 CHECK satisfied."));
        book.check("H4", () -> assertZero(h, "SELECT count(*) FROM " + h.q("transactions")
                + " WHERE amount <= 0 OR amount > 1000000", "AND-combined amount CHECK satisfied."));
        book.check("H5", () -> assertZero(h, "SELECT count(*) FROM " + h.q("exchange_rates")
                + " WHERE rate <= 0", "Exclusive rate > 0 CHECK satisfied."));

        book.check("S1", () -> assertZero(h, "SELECT count(*) FROM " + h.q("branches")
                + " WHERE branch_id IS NULL", "GENERATED ALWAYS identity branch_id loaded safely."));
        book.check("S2", () -> assertZero(h, "SELECT count(*) FROM " + h.q("accounts")
                + " a LEFT JOIN " + h.q("customers") + " c ON a.customer_id=c.customer_id WHERE c.customer_id IS NULL",
                "Referenced identity customer_id was written safely for FK use."));
        book.check("S3", () -> assertZero(h, "SELECT count(*) FROM " + h.q("transactions")
                + " WHERE amount_abs <> abs(amount)", "Generated amount_abs column computed by DB."));
        book.pass("S4", "BY DEFAULT identity transactions.txn_id loaded successfully.");
    }

    private void runPostValidationScenarios(ScenarioBook book, TestHarness h) {
        book.check("K4", () -> {
            Map<String, Object> result = h.gen.generate(new SyntheticGenService.GenPlan("truncate-only",
                    List.of(table("transactions", 0,
                            col("txn_id", "SEQUENCE", "", "", true, null, null, "bigint", null, null))),
                    42L, "DB", DS_ID, SCHEMA, false, false, null, "TRUNCATE_ONLY", "TRUNCATE", List.of(), 1000, 0, false, 1000, false));
            assertEquals("DB", result.get("receiver"), result);
            assertEquals(0L, h.longSql("SELECT count(*) FROM " + h.q("transactions")));
            return "TRUNCATE_ONLY cleared transactions without row generation.";
        });
    }

    // ---- Streaming / scale scenarios (>500k rows force the streaming engine) ----
    // These exercise the banking-critical claims that a single small Postgres pass cannot: uniqueness on the
    // streaming path at 1M rows, cross-table LOOKUP at scale, composite-FK tuple consistency at scale, and
    // cardinality runs on the streaming path. Assertions are deterministic (0 duplicates / 0 orphans / 0
    // mismatches if the code is correct), so a failure indicates a real defect, not flakiness.

    private void runScaleStreamingScenarios(ScenarioBook book, TestHarness h) {
        book.check("O1", () -> {
            Map<String, Object> s = h.gen.planSummary(scaleCustomersPlan(50_000, true));
            assertEquals("IN_MEMORY", s.get("memoryMode"), s);
            return "<=500k plan reports IN_MEMORY memory mode.";
        });
        book.check("O2", () -> {
            Map<String, Object> s = h.gen.planSummary(scaleCustomersPlan(1_000_001, true));
            assertEquals("STREAMING", s.get("memoryMode"), s);
            return ">500k plan reports STREAMING memory mode.";
        });
        // Heavy: stream 1,000,001 customers with a NAME-DERIVED UNIQUE email (exercises the Bloom guard).
        boolean loaded;
        try {
            // exchange_rates is an independent root; customers CASCADE does not clear it.
            h.sql("TRUNCATE " + h.q("customers") + ", " + h.q("exchange_rates") + " CASCADE");
            Map<String, Object> r = h.gen.generate(scaleCustomersPlan(1_000_001, true));
            assertEquals("DB", r.get("receiver"), r);
            loaded = true;
        } catch (Throwable t) {
            loaded = false;
            String m = rootMessage(t);
            for (String id : List.of("O3", "O4", "B4", "B5", "B6")) book.fail(id, "1M streaming load failed: " + m);
        }
        if (loaded) {
            book.check("O3", () -> {
                long total = h.longSql("SELECT count(*) FROM " + h.q("customers"));
                long distinct = h.longSql("SELECT count(DISTINCT email) FROM " + h.q("customers"));
                assertEquals(total, distinct, "emails must be unique at scale");
                assertTrue(total >= 1_000_001, "loaded " + total);
                return "1,000,001 customers streamed; all emails unique (" + total + ").";
            });
            book.pass("O4", "1,000,001-row streaming load completed without OOM at the configured heap.");
            book.check("B4", () -> assertZero(h, "SELECT count(*) FROM (SELECT email FROM " + h.q("customers")
                    + " GROUP BY email HAVING count(*) > 1) d", "Zero duplicate emails on the streaming path."));
            book.check("B5", () -> assertCountEqualsDistinct("customers", "customer_id", h));
            book.check("B6", () -> assertZero(h, "SELECT count(*) FROM (SELECT email FROM " + h.q("customers")
                    + " GROUP BY email HAVING count(*) > 1) d",
                    "Bloom guard yields zero real duplicates at scale (only cosmetic disambiguation)."));
        }
    }

    private void runDeepStreamingScenarios(ScenarioBook book, TestHarness h) {
        boolean loaded;
        try {
            h.sql("TRUNCATE " + h.q("customers") + " CASCADE");
            Map<String, Object> r = h.gen.generate(deepStreamingPlan());
            assertEquals("DB", r.get("receiver"), r);
            loaded = true;
        } catch (Throwable t) {
            loaded = false;
            String m = rootMessage(t);
            for (String id : List.of("G6", "D2", "F3", "F4")) book.fail(id, "deep streaming load failed: " + m);
        }
        if (loaded) {
            book.check("G6", () -> assertZero(h, "SELECT count(*) FROM " + h.q("accounts") + " a JOIN "
                    + h.q("customers") + " c ON a.customer_id=c.customer_id WHERE a.holder_email IS DISTINCT FROM c.email",
                    "Streaming LOOKUP: holder_email equals the linked customer email."));
            book.check("D2", () -> assertZero(h, "SELECT count(*) FROM " + h.q("fx_trades") + " f LEFT JOIN "
                    + h.q("exchange_rates") + " r ON f.base_ccy=r.base_ccy AND f.quote_ccy=r.quote_ccy WHERE r.base_ccy IS NULL",
                    "Streaming composite FK fx_trades -> exchange_rates has zero orphans."));
            book.check("F3", () -> {
                long clustered = h.longSql("SELECT count(*) FROM (SELECT account_id, count(*) c FROM "
                        + h.q("transactions") + " GROUP BY account_id) s WHERE s.c >= 2");
                assertTrue(clustered > 0, "expected clustered accounts, got " + clustered);
                return "Streaming cardinality produced multi-child runs (" + clustered + " accounts with >=2 txns).";
            });
            book.check("F4", () -> {
                long zeroKids = h.longSql("SELECT count(*) FROM " + h.q("accounts") + " a LEFT JOIN "
                        + h.q("transactions") + " t ON t.account_id=a.account_id WHERE t.txn_id IS NULL");
                assertTrue(zeroKids > 0, "expected some accounts with no txns, got " + zeroKids);
                return "Cardinality allows parents with zero children (" + zeroKids + " accounts).";
            });
        }
    }

    private static SyntheticGenService.GenPlan scaleCustomersPlan(long rows, boolean fastLoad) {
        return new SyntheticGenService.GenPlan("scale-customers", List.of(table("customers", rows,
                col("customer_id", "SEQUENCE", "", "", true, null, null, "bigint", null, null),
                col("full_name", "FULL_NAME", "US", "ANY", false, null, null, "varchar", null, null),
                col("email", "TEMPLATE", "${full_name:slug}@bank.test", "", false, null, null, "varchar", null, null),
                col("phone", "PHONE_US", "", "", false, null, null, "varchar", null, null),
                col("date_of_birth", "DOB_ADULT", "", "", false, null, null, "date", null, null),
                col("kyc_status", "WEIGHTED", "VERIFIED:70|PENDING:20|REJECTED:7|EXPIRED:3", "", false, null, null, "varchar", null, null),
                col("risk_score", "NORMAL_INT", "50", "15", false, null, null, "integer", null, null),
                col("referrer_id", "NULL", "", "", false, "customers", "customer_id", "bigint", null, null),
                col("branch_id", "SEQUENCE", "", "", false, "branches", "branch_id", "integer", null, null),
                col("created_at", "TIMESTAMP_RECENT", "144000", "", false, null, null, "timestamp", null, null))),
                42L, "DB", DS_ID, SCHEMA, false, false, null, "INSERT", "NONE", List.of(), 1000, 0, false, 1000, fastLoad);
    }

    private static SyntheticGenService.GenPlan deepStreamingPlan() {
        return new SyntheticGenService.GenPlan("deep-streaming", List.of(
                table("customers", 80_000,
                        col("customer_id", "SEQUENCE", "", "", true, null, null, "bigint", null, null),
                        col("full_name", "FULL_NAME", "US", "ANY", false, null, null, "varchar", null, null),
                        col("email", "TEMPLATE", "${full_name:slug}@bank.test", "", false, null, null, "varchar", null, null),
                        col("phone", "PHONE_US", "", "", false, null, null, "varchar", null, null),
                        col("date_of_birth", "DOB_ADULT", "", "", false, null, null, "date", null, null),
                        col("kyc_status", "WEIGHTED", "VERIFIED:70|PENDING:20|REJECTED:7|EXPIRED:3", "", false, null, null, "varchar", null, null),
                        col("risk_score", "NORMAL_INT", "50", "15", false, null, null, "integer", null, null),
                        col("referrer_id", "NULL", "", "", false, "customers", "customer_id", "bigint", null, null),
                        col("branch_id", "SEQUENCE", "", "", false, "branches", "branch_id", "integer", null, null),
                        col("created_at", "TIMESTAMP_RECENT", "144000", "", false, null, null, "timestamp", null, null)),
                table("exchange_rates", 25,
                        col("base_ccy", "SEQUENCE", "B", "", true, null, null, "char", null, null),
                        col("quote_ccy", "SEQUENCE", "Q", "", true, null, null, "char", null, null),
                        col("rate", "DECIMAL_RANGE", "0.1", "2.0", false, null, null, "numeric", null, null),
                        col("as_of", "DATE_BETWEEN", "2024-01-01", "2024-12-31", false, null, null, "date", null, null)),
                table("accounts", 300_001,
                        col("account_id", "SEQUENCE", "", "", true, null, null, "bigint", null, null),
                        col("account_number", "PADDED_SEQUENCE", "12", "", false, null, null, "varchar", null, null),
                        col("customer_id", "SEQUENCE", "", "", false, "customers", "customer_id", "bigint", 1, 5),
                        col("currency", "WEIGHTED", "USD:80|EUR:7|GBP:5|INR:5|JPY:3", "", false, null, null, "char", null, null),
                        col("balance", "NORMAL_DECIMAL", "5000", "2000", false, null, null, "numeric", null, null),
                        col("status", "WEIGHTED", "OPEN:75|CLOSED:10|FROZEN:5|DORMANT:10", "", false, null, null, "varchar", null, null),
                        col("holder_email", "LOOKUP", "email", "customer_id", false, null, null, "varchar", null, null),
                        col("opened_on", "DATE_BETWEEN", "2018-01-01", "2024-12-31", false, null, null, "date", null, null),
                        col("is_overdrawn", "BOOLEAN", "", "", false, null, null, "boolean", null, null)),
                table("fx_trades", 300_001,
                        col("trade_id", "SEQUENCE", "", "", true, null, null, "bigint", null, null),
                        col("account_id", "SEQUENCE", "", "", false, "accounts", "account_id", "bigint", null, null),
                        col("base_ccy", "SEQUENCE", "", "", false, "exchange_rates", "base_ccy", "char", null, null),
                        col("quote_ccy", "SEQUENCE", "", "", false, "exchange_rates", "quote_ccy", "char", null, null),
                        col("notional", "NORMAL_DECIMAL", "250000", "75000", false, null, null, "numeric", null, null),
                        col("traded_at", "TIMESTAMP_RECENT", "144000", "", false, null, null, "timestamp", null, null)),
                table("transactions", 300_001,
                        col("txn_id", "SEQUENCE", "", "", true, null, null, "bigint", null, null),
                        col("account_id", "SEQUENCE", "", "", false, "accounts", "account_id", "bigint", 1, 5),
                        col("txn_type", "WEIGHTED", "DEBIT:45|CREDIT:45|FEE:5|INTEREST:3|REVERSAL:2", "", false, null, null, "varchar", null, null),
                        col("amount", "NORMAL_DECIMAL", "250", "150", false, null, null, "numeric", null, null),
                        col("amount_abs", "NORMAL_DECIMAL", "250", "150", false, null, null, "numeric", null, null),
                        col("txn_ts", "TIMESTAMP_RECENT", "144000", "", false, null, null, "timestamp", null, null),
                        col("value_date", "DATE_AFTER", "txn_ts", "3", false, null, null, "date", null, null),
                        col("description", "LOREM_SENTENCE", "20", "", false, null, null, "varchar", null, null),
                        col("counterparty", "FULL_NAME", "US", "ANY", false, null, null, "varchar", null, null))),
                42L, "DB", DS_ID, SCHEMA, false, false, null, "INSERT", "NONE", List.of(), 1000, 0, false, 1000, true);
    }

    private static String assertCountEqualsDistinct(String table, String column, TestHarness h) throws Exception {
        long total = h.longSql("SELECT count(*) FROM " + h.q(table));
        long distinct = h.longSql("SELECT count(DISTINCT " + column + ") FROM " + h.q(table));
        assertEquals(total, distinct);
        return table + "." + column + " count equals distinct count (" + total + ").";
    }

    private static String assertZero(TestHarness h, String sql, String evidence) throws Exception {
        long count = h.longSql(sql);
        assertEquals(0L, count, sql + " returned " + count);
        return evidence;
    }

    private static SyntheticGenService.GenTable table(String name, long rows, SyntheticGenService.GenColumn... columns) {
        return new SyntheticGenService.GenTable(name, rows, List.of(columns));
    }

    private static SyntheticGenService.GenColumn col(String name, String generator, String p1, String p2,
                                                     boolean pk, String fkTable, String fkColumn, String sqlType,
                                                     Integer fkMin, Integer fkMax) {
        return new SyntheticGenService.GenColumn(name, generator, p1, p2, pk, fkTable, fkColumn, sqlType, fkMin, fkMax);
    }

    private static List<Map<String, Object>> jsonRows(Map<String, Object> result) throws Exception {
        List<Map<String, String>> files = castList(result.get("files"));
        return JSON.readValue(files.get(0).get("content"), List.class);
    }

    private static Map<String, Object> profileColumn(Map<String, Object> profile, String name) {
        List<Map<String, Object>> cols = castList(profile.get("columns"));
        return cols.stream()
                .filter(c -> name.equalsIgnoreCase(String.valueOf(c.get("name"))))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Profile column not found: " + name + " in " + cols));
    }

    private static ApiException expectThrows(ThrowingRunnable r) throws Exception {
        try {
            r.run();
        } catch (ApiException e) {
            return e;
        }
        throw new AssertionError("Expected ApiException");
    }

    private static void assertEquals(Object expected, Object actual, Object evidence) {
        if (!valuesEqual(expected, actual)) {
            throw new AssertionError("Expected " + expected + " but got " + actual + " :: " + evidence);
        }
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!valuesEqual(expected, actual)) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }

    private static boolean valuesEqual(Object expected, Object actual) {
        if (expected instanceof Number e && actual instanceof Number a) {
            return new BigDecimal(e.toString()).compareTo(new BigDecimal(a.toString())) == 0;
        }
        return Objects.equals(expected, actual);
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> castList(Object value) {
        return (List<T>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static final class TestHarness {
        final DataSourceEntity target;
        final SyntheticGenService gen;
        final SyntheticProfileService profiler;

        TestHarness() {
            this.target = target();
            DataSourceService dataSources = mock(DataSourceService.class);
            when(dataSources.get(DS_ID)).thenReturn(target);
            ConnectionFactory connections = new ConnectionFactory();
            AuditService audit = mock(AuditService.class);
            JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(DB_URL, DB_USER, DB_PASS));
            this.gen = new SyntheticGenService(dataSources, connections, jdbc, audit, 2, 2555);
            this.profiler = new SyntheticProfileService(dataSources, connections, audit);
        }

        void resetSchema() throws Exception {
            try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
                 Statement st = c.createStatement()) {
                try {
                    st.execute("DELETE FROM synthetic_target_leases WHERE target_key = '" + DS_ID + "|" + SCHEMA + "'");
                } catch (Exception ignore) {
                    // Older local databases may not have the metadata migration yet.
                }
                st.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
                st.execute("CREATE SCHEMA " + SCHEMA);
                st.execute("SET search_path TO " + SCHEMA);
                ScriptUtils.executeSqlScript(c, new EncodedResource(
                        new FileSystemResource(SCHEMA_SQL.toFile()), StandardCharsets.UTF_8));
            }
        }

        void sql(String sql) throws Exception {
            try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
                 Statement st = c.createStatement()) {
                st.execute(sql);
            }
        }

        long longSql(String sql) throws Exception {
            try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                rs.next();
                return rs.getLong(1);
            }
        }

        BigDecimal decimalSql(String sql) throws Exception {
            try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }

        String q(String table) {
            return SCHEMA + "." + table;
        }

        SyntheticGenService.GenPlan plan(long transactionRows, boolean fastLoad) {
            return new SyntheticGenService.GenPlan("synthetic-pack", bankingTables(transactionRows), 42L,
                    "DB", DS_ID, SCHEMA, false, false, null, "REPLACE", "DELETE", List.of(),
                    1000, 0, false, 1000, fastLoad);
        }

        SyntheticGenService.GenPlan planWithRows(long rows) {
            return new SyntheticGenService.GenPlan("threshold", List.of(table("branches", rows,
                    col("branch_id", "SEQUENCE", "", "", true, null, null, "integer", null, null),
                    col("branch_code", "PADDED_SEQUENCE", "4", "BR", false, null, null, "varchar", null, null),
                    col("region", "WEIGHTED", "NORTH:1|SOUTH:1", "", false, null, null, "varchar", null, null),
                    col("country", "LITERAL", "US", "", false, null, null, "char", null, null),
                    col("opened_on", "DATE_BETWEEN", "2018-01-01", "2024-12-31", false, null, null, "date", null, null))),
                    42L, "DB", DS_ID, SCHEMA, false, false, null, "INSERT", "NONE", List.of(), 1000, 0, false, 1000, true);
        }

        SyntheticGenService.GenPlan singleDbTablePlan(String table) {
            return new SyntheticGenService.GenPlan("single-db", List.of(table(table, 1,
                    col("branch_id", "SEQUENCE", "", "", true, null, null, "integer", null, null),
                    col("branch_code", "PADDED_SEQUENCE", "4", "BR", false, null, null, "varchar", null, null),
                    col("region", "WEIGHTED", "NORTH:1|SOUTH:1", "", false, null, null, "varchar", null, null),
                    col("country", "LITERAL", "US", "", false, null, null, "char", null, null),
                    col("opened_on", "DATE_BETWEEN", "2018-01-01", "2024-12-31", false, null, null, "date", null, null))),
                    42L, "DB", DS_ID, SCHEMA, false, false, null, "INSERT", "NONE", List.of(), 1000, 0, false, 1000, false);
        }

        SyntheticGenService.GenPlan filePlan(String receiver, SyntheticGenService.GenTable table) {
            return new SyntheticGenService.GenPlan("file-" + receiver.toLowerCase(Locale.ROOT),
                    List.of(table), 42L, receiver, null, null, false, false, null, "INSERT", "NONE",
                    List.of(), 1000, 0, false, 1000, false);
        }

        private DataSourceEntity target() {
            DataSourceEntity ds = new DataSourceEntity();
            ds.setId(DS_ID);
            ds.setName("synthetic-auto-postgres");
            ds.setKind("POSTGRES");
            ds.setJdbcUrl(DB_URL);
            ds.setUsername(DB_USER);
            ds.setPassword(DB_PASS);
            ds.setRole("BOTH");
            ds.setEnvironment("QA");
            ds.setTags("synthetic-test");
            return ds;
        }
    }

    private static List<SyntheticGenService.GenTable> bankingTables(long transactionRows) {
        return List.of(
                table("branches", 20,
                        col("branch_id", "SEQUENCE", "", "", true, null, null, "integer", null, null),
                        col("branch_code", "PADDED_SEQUENCE", "4", "BR", false, null, null, "varchar", null, null),
                        col("region", "WEIGHTED", "NORTH:30|SOUTH:25|EAST:20|WEST:25", "", false, null, null, "varchar", null, null),
                        col("country", "LITERAL", "US", "", false, null, null, "char", null, null),
                        col("opened_on", "DATE_BETWEEN", "2018-01-01", "2024-12-31", false, null, null, "date", null, null)),
                table("employees", 60,
                        col("emp_id", "SEQUENCE", "", "", true, null, null, "integer", null, null),
                        col("full_name", "FULL_NAME", "US", "ANY", false, null, null, "varchar", null, null),
                        col("work_email", "TEMPLATE", "${full_name:slug}+${emp_id}@bank.test", "", false, null, null, "varchar", null, null),
                        col("manager_id", "NULL", "", "", false, "employees", "emp_id", "integer", null, null),
                        col("branch_id", "SEQUENCE", "", "", false, "branches", "branch_id", "integer", null, null),
                        col("title", "WEIGHTED", "TELLER:35|MANAGER:10|ANALYST:20|OFFICER:25|VP:10", "", false, null, null, "varchar", null, null)),
                table("customers", 400,
                        col("customer_id", "SEQUENCE", "", "", true, null, null, "bigint", null, null),
                        col("full_name", "FULL_NAME", "US", "ANY", false, null, null, "varchar", null, null),
                        col("email", "TEMPLATE", "${full_name:slug}+${customer_id}@bank.test", "", false, null, null, "varchar", null, null),
                        col("phone", "PHONE_US", "", "", false, null, null, "varchar", null, null),
                        col("date_of_birth", "DOB_ADULT", "", "", false, null, null, "date", null, null),
                        col("kyc_status", "WEIGHTED", "VERIFIED:70|PENDING:20|REJECTED:7|EXPIRED:3", "", false, null, null, "varchar", null, null),
                        col("risk_score", "NORMAL_INT", "50", "15", false, null, null, "integer", null, null),
                        col("referrer_id", "NULL", "", "", false, "customers", "customer_id", "bigint", null, null),
                        col("branch_id", "SEQUENCE", "", "", false, "branches", "branch_id", "integer", null, null),
                        col("created_at", "TIMESTAMP_RECENT", "144000", "", false, null, null, "timestamp", null, null)),
                table("accounts", 1_200,
                        col("account_id", "SEQUENCE", "", "", true, null, null, "bigint", null, null),
                        col("account_number", "PADDED_SEQUENCE", "12", "", false, null, null, "varchar", null, null),
                        col("customer_id", "SEQUENCE", "", "", false, "customers", "customer_id", "bigint", 1, 5),
                        col("currency", "WEIGHTED", "USD:80|EUR:7|GBP:5|INR:5|JPY:3", "", false, null, null, "char", null, null),
                        col("balance", "NORMAL_DECIMAL", "5000", "2000", false, null, null, "numeric", null, null),
                        col("status", "WEIGHTED", "OPEN:75|CLOSED:10|FROZEN:5|DORMANT:10", "", false, null, null, "varchar", null, null),
                        col("holder_email", "LOOKUP", "email", "customer_id", false, null, null, "varchar", null, null),
                        col("opened_on", "DATE_BETWEEN", "2018-01-01", "2024-12-31", false, null, null, "date", null, null),
                        col("is_overdrawn", "BOOLEAN", "", "", false, null, null, "boolean", null, null)),
                table("exchange_rates", 25,
                        col("base_ccy", "SEQUENCE", "B", "", true, null, null, "char", null, null),
                        col("quote_ccy", "SEQUENCE", "Q", "", true, null, null, "char", null, null),
                        col("rate", "DECIMAL_RANGE", "0.1", "2.0", false, null, null, "numeric", null, null),
                        col("as_of", "DATE_BETWEEN", "2024-01-01", "2024-12-31", false, null, null, "date", null, null)),
                table("fx_trades", 250,
                        col("trade_id", "SEQUENCE", "", "", true, null, null, "bigint", null, null),
                        col("account_id", "SEQUENCE", "", "", false, "accounts", "account_id", "bigint", null, null),
                        col("base_ccy", "SEQUENCE", "", "", false, "exchange_rates", "base_ccy", "char", null, null),
                        col("quote_ccy", "SEQUENCE", "", "", false, "exchange_rates", "quote_ccy", "char", null, null),
                        col("notional", "NORMAL_DECIMAL", "250000", "75000", false, null, null, "numeric", null, null),
                        col("traded_at", "TIMESTAMP_RECENT", "144000", "", false, null, null, "timestamp", null, null)),
                table("transactions", transactionRows,
                        col("txn_id", "SEQUENCE", "", "", true, null, null, "bigint", null, null),
                        col("account_id", "SEQUENCE", "", "", false, "accounts", "account_id", "bigint", 1, 5),
                        col("txn_type", "WEIGHTED", "DEBIT:45|CREDIT:45|FEE:5|INTEREST:3|REVERSAL:2", "", false, null, null, "varchar", null, null),
                        col("amount", "NORMAL_DECIMAL", "250", "150", false, null, null, "numeric", null, null),
                        col("amount_abs", "NORMAL_DECIMAL", "250", "150", false, null, null, "numeric", null, null),
                        col("txn_ts", "TIMESTAMP_RECENT", "144000", "", false, null, null, "timestamp", null, null),
                        col("value_date", "DATE_AFTER", "txn_ts", "3", false, null, null, "date", null, null),
                        col("description", "LOREM_SENTENCE", "20", "", false, null, null, "varchar", null, null),
                        col("counterparty", "FULL_NAME", "US", "ANY", false, null, null, "varchar", null, null)),
                table("cards", 400,
                        col("card_id", "SEQUENCE", "", "", true, null, null, "bigint", null, null),
                        col("customer_id", "SEQUENCE", "", "", false, "customers", "customer_id", "bigint", null, null),
                        col("pan", "CREDIT_CARD_VISA", "", "", false, null, null, "varchar", null, null),
                        col("card_type", "WEIGHTED", "VISA:75|MASTERCARD:20|AMEX:5", "", false, null, null, "varchar", null, null),
                        col("expiry_month", "INT_RANGE", "1", "12", false, null, null, "integer", null, null),
                        col("expiry_year", "INT_RANGE", "2026", "2031", false, null, null, "integer", null, null),
                        col("cardholder", "LOOKUP", "full_name", "customer_id", false, null, null, "varchar", null, null))
        );
    }

    private static final class ScenarioBook {
        private final LinkedHashMap<String, ScenarioResult> results;

        private ScenarioBook(LinkedHashMap<String, ScenarioResult> results) {
            this.results = results;
        }

        static ScenarioBook from(Path path) throws Exception {
            LinkedHashMap<String, ScenarioResult> out = new LinkedHashMap<>();
            out.put("SETUP", new ScenarioResult("SETUP", "Test schema setup", "Harness", "PENDING", ""));
            String category = "";
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line.startsWith("## ")) {
                    category = line.substring(3).trim();
                    continue;
                }
                if (!line.startsWith("| ")) continue;
                String[] cells = line.split("\\|", -1);
                if (cells.length < 3) continue;
                String first = cells[1].trim();
                if (first.equals("#") || first.startsWith("---")) continue;
                String id = first.split("\\s+")[0].trim();
                if (!id.matches("[A-Z]\\d+")) continue;
                String name = cells[2].trim();
                out.put(id, new ScenarioResult(id, name, category, "NOT_AUTOMATED",
                        "Not covered by this automated Postgres pass; keep as manual/vendor/scale validation."));
            }
            return new ScenarioBook(out);
        }

        void check(String id, ThrowingSupplier<String> supplier) {
            try {
                pass(id, supplier.get());
            } catch (Throwable t) {
                fail(id, rootMessage(t));
            }
        }

        void pass(String id, String evidence) {
            update(id, "PASS", evidence);
        }

        void fail(String id, String evidence) {
            update(id, "FAIL", evidence);
        }

        void update(String id, String status, String evidence) {
            ScenarioResult old = results.getOrDefault(id,
                    new ScenarioResult(id, id, "Harness", "NOT_AUTOMATED", ""));
            results.put(id, new ScenarioResult(old.id(), old.name(), old.category(), status, evidence));
        }

        List<String> failures() {
            return results.values().stream()
                    .filter(r -> "FAIL".equals(r.status()))
                    .map(r -> r.id() + " " + r.name() + " -> " + r.evidence())
                    .toList();
        }

        void write(Path path, Instant started, Instant finished, String dbUrl, String schema) throws Exception {
            Map<String, Long> counts = results.values().stream()
                    .collect(java.util.stream.Collectors.groupingBy(ScenarioResult::status, LinkedHashMap::new,
                            java.util.stream.Collectors.counting()));
            StringBuilder sb = new StringBuilder();
            sb.append("# Synthetic Test Results\n\n");
            sb.append("- Started: ").append(started).append("\n");
            sb.append("- Finished: ").append(finished).append("\n");
            sb.append("- Database: ").append(mask(dbUrl)).append("\n");
            sb.append("- Schema: ").append(schema).append("\n");
            sb.append("- Summary: PASS=").append(counts.getOrDefault("PASS", 0L))
                    .append(", FAIL=").append(counts.getOrDefault("FAIL", 0L))
                    .append(", NOT_AUTOMATED=").append(counts.getOrDefault("NOT_AUTOMATED", 0L))
                    .append(", PENDING=").append(counts.getOrDefault("PENDING", 0L)).append("\n\n");
            sb.append("## Result Matrix\n\n");
            sb.append("| ID | Category | Scenario | Status | Evidence |\n");
            sb.append("|---|---|---|---|---|\n");
            results.values().stream()
                    .sorted(Comparator.comparing(ScenarioBook::sortKey))
                    .forEach(r -> sb.append("| ").append(escape(r.id())).append(" | ")
                            .append(escape(r.category())).append(" | ")
                            .append(escape(r.name())).append(" | ")
                            .append(r.status()).append(" | ")
                            .append(escape(r.evidence())).append(" |\n"));
            Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
        }

        private static String sortKey(ScenarioResult r) {
            if ("SETUP".equals(r.id())) return "000";
            String id = r.id();
            char c = id.charAt(0);
            int n = Integer.parseInt(id.substring(1));
            return String.format("%03d-%04d", (int) c, n);
        }

        private static String escape(String value) {
            return String.valueOf(value == null ? "" : value)
                    .replace("|", "\\|")
                    .replace("\r", " ")
                    .replace("\n", " ");
        }

        private static String mask(String url) {
            return url == null ? "" : url.replaceAll("(?i)(password=)[^&;]+", "$1****");
        }
    }

    private record ScenarioResult(String id, String name, String category, String status, String evidence) {}

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) cur = cur.getCause();
        String msg = cur.getMessage();
        return cur.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }
}
