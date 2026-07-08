import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

public class Postgres10xSeeder {
    private static final String SOURCE_DB = "tdm_masking_source";
    private static final String TARGET_DB = "tdm_masking_target";
    private static final String APP_USER = "tdm_demo";
    private static final String APP_PASS = "tdm_demo";
    private static final int ROWS = 10_500;

    record Candidate(String host, int port, String db, String user, String pass) {
        String url(String database) {
            return "jdbc:postgresql://" + host + ":" + port + "/" + database;
        }
    }

    public static void main(String[] args) throws Exception {
        Class.forName("org.postgresql.Driver");
        Candidate chosen = null;
        List<Candidate> candidates = List.of(
                new Candidate("127.0.0.1", 55432, "postgres", "postgres", ""),
                new Candidate("127.0.0.1", 5432, "postgres", "forgetdm", "forgetdm"),
                new Candidate("127.0.0.1", 5432, "forgetdm", "forgetdm", "forgetdm"),
                new Candidate("127.0.0.1", 5433, "postgres", "demo", "demo"),
                new Candidate("127.0.0.1", 5433, "bankdemo", "demo", "demo"),
                new Candidate("127.0.0.1", 5432, "postgres", "postgres", "postgres")
        );
        for (Candidate c : candidates) {
            try (Connection admin = DriverManager.getConnection(c.url(c.db), c.user, c.pass)) {
                admin.setAutoCommit(true);
                ensureLogin(admin, APP_USER, APP_PASS);
                recreateDatabase(admin, SOURCE_DB, APP_USER);
                recreateDatabase(admin, TARGET_DB, APP_USER);
                chosen = c;
                break;
            } catch (Exception e) {
                System.out.println("Skipping " + c.url(c.db) + " as " + c.user + ": " + e.getMessage());
            }
        }
        if (chosen == null) throw new IllegalStateException("No usable local Postgres admin connection found.");

        try (Connection source = DriverManager.getConnection(chosen.url(SOURCE_DB), APP_USER, APP_PASS);
             Connection target = DriverManager.getConnection(chosen.url(TARGET_DB), APP_USER, APP_PASS)) {
            source.setAutoCommit(true);
            target.setAutoCommit(true);
            execScript(source, ddl());
            execScript(target, ddl());
            execScript(source, inserts());
            grantSchema(source, APP_USER);
            grantSchema(target, APP_USER);
        }

        try (Connection admin = DriverManager.getConnection(chosen.url(chosen.db), chosen.user, chosen.pass)) {
            admin.setAutoCommit(true);
            grantDatabase(admin, SOURCE_DB, APP_USER);
            grantDatabase(admin, TARGET_DB, APP_USER);
        }

        System.out.println("Created Postgres masking demo databases");
        System.out.println("source_url=" + chosen.url(SOURCE_DB));
        System.out.println("target_url=" + chosen.url(TARGET_DB));
        System.out.println("username=" + APP_USER);
        System.out.println("password=" + APP_PASS);
        System.out.println("tables=10");
        System.out.println("source_rows_per_table=" + ROWS);
        System.out.println("target_rows_per_table=0");
    }

    private static void recreateDatabase(Connection admin, String db, String owner) throws SQLException {
        String qDb = quoteIdent(db);
        try (Statement st = admin.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '" + db + "'")) {
                while (rs.next()) { /* drain */ }
            }
            st.executeUpdate("DROP DATABASE IF EXISTS " + qDb);
            st.executeUpdate("CREATE DATABASE " + qDb + " OWNER " + quoteIdent(owner));
        }
    }

    private static void ensureLogin(Connection admin, String user, String pass) throws SQLException {
        try (Statement st = admin.createStatement()) {
            boolean exists;
            try (ResultSet rs = st.executeQuery("SELECT 1 FROM pg_roles WHERE rolname = '" + literal(user) + "'")) {
                exists = rs.next();
            }
            String password = " PASSWORD '" + literal(pass) + "'";
            if (exists) st.executeUpdate("ALTER ROLE " + quoteIdent(user) + " WITH LOGIN CREATEDB" + password);
            else st.executeUpdate("CREATE ROLE " + quoteIdent(user) + " WITH LOGIN CREATEDB" + password);
        }
    }

    private static void grantDatabase(Connection admin, String db, String user) throws SQLException {
        try (Statement st = admin.createStatement()) {
            st.executeUpdate("GRANT ALL PRIVILEGES ON DATABASE " + quoteIdent(db) + " TO " + quoteIdent(user));
        }
    }

    private static void grantSchema(Connection c, String user) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.executeUpdate("GRANT USAGE, CREATE ON SCHEMA public TO " + quoteIdent(user));
            st.executeUpdate("GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO " + quoteIdent(user));
            st.executeUpdate("GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO " + quoteIdent(user));
            st.executeUpdate("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO " + quoteIdent(user));
            st.executeUpdate("ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO " + quoteIdent(user));
        }
    }

    private static void execScript(Connection c, String script) throws SQLException {
        try (Statement st = c.createStatement()) {
            for (String raw : script.split(";\\s*(\\r?\\n|$)")) {
                String sql = raw.trim();
                if (!sql.isEmpty()) st.execute(sql);
            }
        }
    }

    private static String quoteIdent(String ident) {
        if (!ident.matches("[A-Za-z0-9_]+")) throw new IllegalArgumentException("Unsafe identifier: " + ident);
        return "\"" + ident + "\"";
    }

    private static String literal(String value) {
        return value.replace("'", "''");
    }

    private static String ddl() {
        return """
CREATE TABLE customers (
  customer_id INTEGER PRIMARY KEY,
  first_name VARCHAR(80),
  middle_name VARCHAR(40),
  last_name VARCHAR(80),
  full_name VARCHAR(180),
  gender VARCHAR(20),
  email VARCHAR(180),
  phone VARCHAR(40),
  ssn VARCHAR(20),
  dob DATE,
  loyalty_id VARCHAR(40),
  created_at TIMESTAMP
);
CREATE TABLE customer_addresses (
  address_id INTEGER PRIMARY KEY,
  customer_id INTEGER NOT NULL REFERENCES customers(customer_id),
  address_line1 VARCHAR(160),
  address_line2 VARCHAR(120),
  city VARCHAR(80),
  state VARCHAR(2),
  zip VARCHAR(10),
  country VARCHAR(40),
  is_primary BOOLEAN
);
CREATE TABLE accounts (
  account_id INTEGER PRIMARY KEY,
  customer_id INTEGER NOT NULL REFERENCES customers(customer_id),
  account_number VARCHAR(24),
  routing_number VARCHAR(16),
  account_type VARCHAR(30),
  opened_at DATE,
  balance NUMERIC(12,2)
);
CREATE TABLE cards (
  card_id INTEGER PRIMARY KEY,
  account_id INTEGER NOT NULL REFERENCES accounts(account_id),
  customer_id INTEGER NOT NULL REFERENCES customers(customer_id),
  card_number VARCHAR(24),
  card_type VARCHAR(20),
  cardholder_name VARCHAR(180),
  expiration_month INTEGER,
  expiration_year INTEGER,
  cvv VARCHAR(4)
);
CREATE TABLE transactions (
  transaction_id INTEGER PRIMARY KEY,
  account_id INTEGER NOT NULL REFERENCES accounts(account_id),
  card_id INTEGER REFERENCES cards(card_id),
  merchant_name VARCHAR(120),
  merchant_city VARCHAR(80),
  merchant_state VARCHAR(2),
  amount NUMERIC(10,2),
  transaction_date TIMESTAMP,
  description VARCHAR(220)
);
CREATE TABLE loans (
  loan_id INTEGER PRIMARY KEY,
  customer_id INTEGER NOT NULL REFERENCES customers(customer_id),
  loan_number VARCHAR(30),
  loan_type VARCHAR(30),
  origination_date DATE,
  principal NUMERIC(12,2),
  collateral_address VARCHAR(220)
);
CREATE TABLE beneficiaries (
  beneficiary_id INTEGER PRIMARY KEY,
  customer_id INTEGER NOT NULL REFERENCES customers(customer_id),
  beneficiary_first_name VARCHAR(80),
  beneficiary_last_name VARCHAR(80),
  beneficiary_email VARCHAR(180),
  beneficiary_phone VARCHAR(40),
  beneficiary_ssn VARCHAR(20),
  relationship VARCHAR(30)
);
CREATE TABLE devices (
  device_id INTEGER PRIMARY KEY,
  customer_id INTEGER NOT NULL REFERENCES customers(customer_id),
  device_fingerprint VARCHAR(120),
  ip_address VARCHAR(45),
  user_agent VARCHAR(160),
  last_login TIMESTAMP,
  trusted BOOLEAN
);
CREATE TABLE support_tickets (
  ticket_id INTEGER PRIMARY KEY,
  customer_id INTEGER NOT NULL REFERENCES customers(customer_id),
  contact_email VARCHAR(180),
  contact_phone VARCHAR(40),
  issue_type VARCHAR(60),
  issue_text VARCHAR(300),
  opened_at TIMESTAMP,
  status VARCHAR(30)
);
CREATE TABLE employers (
  employer_id INTEGER PRIMARY KEY,
  customer_id INTEGER NOT NULL REFERENCES customers(customer_id),
  company_name VARCHAR(140),
  work_email VARCHAR(180),
  employer_phone VARCHAR(40),
  tax_id VARCHAR(20),
  employer_address VARCHAR(220),
  annual_income NUMERIC(12,2)
);
CREATE INDEX idx_customer_addresses_customer ON customer_addresses(customer_id);
CREATE INDEX idx_accounts_customer ON accounts(customer_id);
CREATE INDEX idx_cards_account ON cards(account_id);
CREATE INDEX idx_transactions_account ON transactions(account_id);
CREATE INDEX idx_loans_customer ON loans(customer_id);
CREATE INDEX idx_beneficiaries_customer ON beneficiaries(customer_id);
CREATE INDEX idx_devices_customer ON devices(customer_id);
CREATE INDEX idx_tickets_customer ON support_tickets(customer_id);
CREATE INDEX idx_employers_customer ON employers(customer_id);
""";
    }

    private static String inserts() {
        return """
WITH src AS (
  SELECT g::int AS id,
         (ARRAY['Yash','Aarav','Maya','Sophia','Liam','Olivia','Noah','Emma','Ethan','Ava'])[(g % 10 + 1)::int] AS first_name,
         (ARRAY['K','M','R','S','P'])[(g % 5 + 1)::int] AS middle_name,
         (ARRAY['Singh','Patel','Johnson','Garcia','Brown','Williams','Khan','Miller','Davis','Wilson'])[(g % 10 + 1)::int] AS last_name,
         (ARRAY['Female','Male','Nonbinary','Female','Male'])[(g % 5 + 1)::int] AS gender
  FROM generate_series(1, 10500) g
)
INSERT INTO customers
SELECT id, first_name, CASE WHEN id % 3 = 0 THEN middle_name ELSE NULL END, last_name,
       first_name || CASE WHEN id % 3 = 0 THEN ' ' || middle_name ELSE '' END || ' ' || last_name,
       gender,
       lower(first_name || '.' || last_name || id || '@examplebank.test'),
       '+1 (212) 555-' || lpad((id % 10000)::text, 4, '0'),
       lpad(((id % 665) + 1)::text, 3, '0') || '-' || lpad(((id % 99) + 1)::text, 2, '0') || '-' || lpad((id % 10000)::text, 4, '0'),
       DATE '1970-01-01' + ((id % 12000) * INTERVAL '1 day'),
       'LOY-' || lpad(id::text, 8, '0'),
       now() - ((id % 1200) * INTERVAL '1 hour')
FROM src;
INSERT INTO customer_addresses
SELECT g::int, g::int,
       (100 + g) || ' ' || (ARRAY['Market St','Oak Ave','Lake Dr','Maple Rd','Cedar Ln','Main St'])[(g % 6 + 1)::int],
       CASE WHEN g % 4 = 0 THEN 'Apt ' || (g % 80 + 1) ELSE NULL END,
       (ARRAY['Columbus','Austin','Seattle','Charlotte','Phoenix','Denver'])[(g % 6 + 1)::int],
       (ARRAY['OH','TX','WA','NC','AZ','CO'])[(g % 6 + 1)::int],
       lpad((43000 + (g % 9000))::text, 5, '0'),
       'US',
       true
FROM generate_series(1, 10500) g;
INSERT INTO accounts
SELECT g::int, g::int,
       'AC' || lpad(g::text, 14, '0'),
       lpad((110000000 + (g % 89999999))::text, 9, '0'),
       (ARRAY['CHECKING','SAVINGS','MONEY_MARKET'])[(g % 3 + 1)::int],
       DATE '2015-01-01' + ((g % 3000) * INTERVAL '1 day'),
       round((1000 + (g * 17.43))::numeric, 2)
FROM generate_series(1, 10500) g;
INSERT INTO cards
SELECT g::int, g::int, g::int,
       CASE WHEN g % 3 = 0 THEN '4111111111111111' WHEN g % 3 = 1 THEN '5555555555554444' ELSE '378282246310005' END,
       CASE WHEN g % 3 = 2 THEN 'AMEX' WHEN g % 3 = 1 THEN 'MASTERCARD' ELSE 'VISA' END,
       c.full_name,
       ((g % 12) + 1)::int,
       2028 + (g % 5)::int,
       lpad((g % 1000)::text, 3, '0')
FROM generate_series(1, 10500) g
JOIN customers c ON c.customer_id = g;
INSERT INTO transactions
SELECT g::int, g::int, g::int,
       (ARRAY['Northwind Pharmacy','Blue Market','City Transit','Green Grocer','Metro Fuel','Cloud Books'])[(g % 6 + 1)::int],
       (ARRAY['Columbus','Austin','Seattle','Charlotte','Phoenix','Denver'])[(g % 6 + 1)::int],
       (ARRAY['OH','TX','WA','NC','AZ','CO'])[(g % 6 + 1)::int],
       round((5 + (g % 500) * 1.31)::numeric, 2),
       now() - ((g % 365) * INTERVAL '1 day'),
       'Purchase authorization ' || g
FROM generate_series(1, 10500) g;
INSERT INTO loans
SELECT g::int, g::int,
       'LN' || lpad(g::text, 12, '0'),
       (ARRAY['AUTO','MORTGAGE','PERSONAL','STUDENT'])[(g % 4 + 1)::int],
       DATE '2018-01-01' + ((g % 2000) * INTERVAL '1 day'),
       round((5000 + (g % 8000) * 9.25)::numeric, 2),
       a.address_line1 || ', ' || a.city || ', ' || a.state || ' ' || a.zip || ', US'
FROM generate_series(1, 10500) g
JOIN customer_addresses a ON a.customer_id = g;
INSERT INTO beneficiaries
SELECT g::int, g::int,
       (ARRAY['Nina','Robert','Isha','Miguel','Priya','Daniel'])[(g % 6 + 1)::int],
       (ARRAY['Shah','Thomas','Rao','Lopez','Mehta','Anderson'])[(g % 6 + 1)::int],
       'beneficiary' || g || '@examplebank.test',
       '+1 (614) 555-' || lpad((g % 10000)::text, 4, '0'),
       lpad(((g % 665) + 1)::text, 3, '0') || '-' || lpad(((g % 99) + 1)::text, 2, '0') || '-' || lpad(((g + 700) % 10000)::text, 4, '0'),
       (ARRAY['SPOUSE','PARENT','SIBLING','CHILD','FRIEND'])[(g % 5 + 1)::int]
FROM generate_series(1, 10500) g;
INSERT INTO devices
SELECT g::int, g::int,
       md5('device-' || g),
       '10.' || (g % 200) || '.' || ((g / 200) % 200) || '.' || (g % 250 + 1),
       (ARRAY['Chrome Windows','Safari iOS','Firefox Linux','Edge Windows'])[(g % 4 + 1)::int],
       now() - ((g % 720) * INTERVAL '1 hour'),
       g % 5 <> 0
FROM generate_series(1, 10500) g;
INSERT INTO support_tickets
SELECT g::int, g::int,
       c.email,
       c.phone,
       (ARRAY['CARD','LOGIN','ADDRESS_CHANGE','WIRE','LOAN'])[(g % 5 + 1)::int],
       'Customer ' || c.full_name || ' asked to verify account ' || a.account_number || ' and address ' || ad.address_line1 || ', ' || ad.city || '.',
       now() - ((g % 400) * INTERVAL '1 hour'),
       (ARRAY['OPEN','PENDING','RESOLVED'])[(g % 3 + 1)::int]
FROM generate_series(1, 10500) g
JOIN customers c ON c.customer_id = g
JOIN accounts a ON a.customer_id = g
JOIN customer_addresses ad ON ad.customer_id = g;
INSERT INTO employers
SELECT g::int, g::int,
       (ARRAY['Acme Health','Globex Retail','Initech Systems','Umbrella Logistics','Stark Energy','Wayne Finance'])[(g % 6 + 1)::int],
       lower('employee' || g || '@corp-example.test'),
       '+1 (303) 555-' || lpad((g % 10000)::text, 4, '0'),
       lpad(((g % 89) + 10)::text, 2, '0') || '-' || lpad((g % 10000000)::text, 7, '0'),
       (200 + g) || ' Corporate Plaza, Denver, CO ' || lpad((80000 + (g % 9000))::text, 5, '0') || ', US',
       round((45000 + (g % 90000) * 0.91)::numeric, 2)
FROM generate_series(1, 10500) g;
""";
    }
}
