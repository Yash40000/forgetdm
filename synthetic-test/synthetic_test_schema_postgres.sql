-- ============================================================================
-- ForgeTDM - Synthetic Data Generation TEST SCHEMA (PostgreSQL)
-- ----------------------------------------------------------------------------
-- Purpose: a single banking-flavoured schema that exercises EVERY feature of
-- the synthetic generator so you can run the scenarios in
-- synthetic_test_scenarios.md against real tables.
--
-- Features covered by this DDL:
--   * Identity PKs (GENERATED ALWAYS / BY DEFAULT AS IDENTITY)
--   * Natural single-column UNIQUE keys (email, account_number, pan, code)
--   * Single-column foreign keys + parent-first ordering
--   * COMPOSITE primary key (exchange_rates, account_balances)
--   * COMPOSITE foreign key (fx_trades -> exchange_rates)
--   * SELF-REFERENCING foreign keys (employees.manager_id, customers.referrer_id)
--   * CHECK constraints: IN-list, BETWEEN, comparison (>/>=), AND-combined,
--     and complex/unsupported (LIKE / function) that must be EVIDENCE-ONLY
--   * GENERATED (computed) columns the loader must SKIP
--   * NOT NULL, length-limited varchar, char, numeric(p,s), date, timestamp, boolean
--   * Cardinality chains (branch -> customer -> account -> transaction)
--   * Cross-table LOOKUP target column (accounts.holder_email)
--   * A production-like SOURCE table (prod_customers) seeded with a SKEWED,
--     physically-ordered distribution to test the profiler's RANDOM sampling.
--
-- Usage:
--   CREATE SCHEMA IF NOT EXISTS tdm_test;
--   SET search_path TO tdm_test;
--   \i synthetic_test_schema_postgres.sql
--
-- Dialect notes for Oracle / SQL Server / DB2 / MySQL are at the BOTTOM.
-- ============================================================================

-- Uncomment to isolate everything in its own schema:
-- CREATE SCHEMA IF NOT EXISTS tdm_test;
-- SET search_path TO tdm_test;

DROP TABLE IF EXISTS fx_trades, account_balances, transactions, cards, accounts,
                     exchange_rates, customers, employees, branches, prod_customers CASCADE;

-- ----------------------------------------------------------------------------
-- 1) branches  - small parent; identity PK; single-column UNIQUE code
--    (acts as the top of a cardinality chain: branch -> customers -> accounts)
-- ----------------------------------------------------------------------------
CREATE TABLE branches (
    branch_id    integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    branch_code  varchar(8)  NOT NULL UNIQUE,                 -- single-col UNIQUE
    region       varchar(20) NOT NULL,
    country      char(2)     NOT NULL,
    opened_on    date        NOT NULL
);

-- ----------------------------------------------------------------------------
-- 2) employees - SELF-REFERENCING FK (manager_id -> employees); IN CHECK; UNIQUE email
-- ----------------------------------------------------------------------------
CREATE TABLE employees (
    emp_id      integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    full_name   varchar(80)  NOT NULL,
    work_email  varchar(120) NOT NULL UNIQUE,                 -- UNIQUE (often derived from name)
    manager_id  integer REFERENCES employees(emp_id),         -- self FK (nullable -> root rows)
    branch_id   integer NOT NULL REFERENCES branches(branch_id),
    title       varchar(40)  NOT NULL,
    CONSTRAINT ck_emp_title CHECK (title IN ('TELLER','MANAGER','ANALYST','OFFICER','VP'))
);

-- ----------------------------------------------------------------------------
-- 3) customers - FK -> branches; SELF-REF referrer; rich CHECKs; PII columns
-- ----------------------------------------------------------------------------
CREATE TABLE customers (
    customer_id   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    full_name     varchar(80)  NOT NULL,
    email         varchar(120) NOT NULL UNIQUE,               -- UNIQUE; typically derived from name
    phone         varchar(20),
    date_of_birth date         NOT NULL,
    kyc_status    varchar(10)  NOT NULL,
    risk_score    integer      NOT NULL,
    referrer_id   bigint REFERENCES customers(customer_id),   -- self FK (nullable)
    branch_id     integer NOT NULL REFERENCES branches(branch_id),
    created_at    timestamp    NOT NULL,
    CONSTRAINT ck_cust_kyc   CHECK (kyc_status IN ('PENDING','VERIFIED','REJECTED','EXPIRED')),  -- IN
    CONSTRAINT ck_cust_risk  CHECK (risk_score BETWEEN 0 AND 100),                               -- RANGE
    CONSTRAINT ck_cust_email CHECK (email LIKE '%@%'),                  -- COMPLEX -> evidence-only
    CONSTRAINT ck_cust_dob   CHECK (date_of_birth <= CURRENT_DATE)      -- FUNCTION -> evidence-only
);

-- ----------------------------------------------------------------------------
-- 4) accounts - FK -> customers; holder_email = LOOKUP target; balance/status CHECKs;
--    composite UNIQUE; GENERATED column the loader must skip
-- ----------------------------------------------------------------------------
CREATE TABLE accounts (
    account_id     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_number varchar(20)  NOT NULL UNIQUE,              -- UNIQUE natural key
    customer_id    bigint       NOT NULL REFERENCES customers(customer_id),
    currency       char(3)      NOT NULL,
    balance        numeric(15,2) NOT NULL,
    status         varchar(10)  NOT NULL,
    holder_email   varchar(120),                             -- fill via cross-table LOOKUP (customers.email)
    opened_on      date         NOT NULL,
    is_overdrawn   boolean GENERATED ALWAYS AS (balance < 0) STORED,  -- GENERATED -> loader must SKIP
    CONSTRAINT ck_acct_ccy    CHECK (currency IN ('USD','EUR','GBP','INR','JPY')),  -- IN
    CONSTRAINT ck_acct_status CHECK (status   IN ('OPEN','CLOSED','FROZEN','DORMANT')),  -- IN
    CONSTRAINT ck_acct_bal    CHECK (balance >= 0),           -- MIN (inclusive)
    CONSTRAINT uq_acct_ccy    UNIQUE (account_id, currency)   -- COMPOSITE UNIQUE (detected, not value-enforced)
);

-- ----------------------------------------------------------------------------
-- 5) exchange_rates - COMPOSITE PRIMARY KEY parent for the composite FK below
-- ----------------------------------------------------------------------------
CREATE TABLE exchange_rates (
    base_ccy   char(3) NOT NULL,
    quote_ccy  char(3) NOT NULL,
    rate       numeric(12,6) NOT NULL,
    as_of      date NOT NULL,
    PRIMARY KEY (base_ccy, quote_ccy),
    CONSTRAINT ck_fx_rate CHECK (rate > 0)                    -- MIN (exclusive)
);

-- ----------------------------------------------------------------------------
-- 6) fx_trades - COMPOSITE FK -> exchange_rates(base_ccy, quote_ccy); single FK -> accounts
-- ----------------------------------------------------------------------------
CREATE TABLE fx_trades (
    trade_id    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id  bigint  NOT NULL REFERENCES accounts(account_id),
    base_ccy    char(3) NOT NULL,
    quote_ccy   char(3) NOT NULL,
    notional    numeric(18,2) NOT NULL,
    traded_at   timestamp NOT NULL,
    CONSTRAINT fk_fx_rate FOREIGN KEY (base_ccy, quote_ccy)
        REFERENCES exchange_rates(base_ccy, quote_ccy),       -- COMPOSITE FK (tuple must stay consistent)
    CONSTRAINT ck_fx_notional CHECK (notional > 0 AND notional <= 100000000)  -- AND -> MIN + MAX
);

-- ----------------------------------------------------------------------------
-- 7) account_balances - COMPOSITE PK (account_id, as_of_date); single FK -> accounts
--    (use to test composite-PK uniqueness / reject handling for tuple collisions)
-- ----------------------------------------------------------------------------
CREATE TABLE account_balances (
    account_id bigint NOT NULL REFERENCES accounts(account_id),
    as_of_date date   NOT NULL,
    balance    numeric(15,2) NOT NULL,
    PRIMARY KEY (account_id, as_of_date)
);

-- ----------------------------------------------------------------------------
-- 8) transactions - HIGH-VOLUME child (use for streaming + cardinality + DATE_AFTER)
-- ----------------------------------------------------------------------------
CREATE TABLE transactions (
    txn_id       bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,  -- BY DEFAULT identity
    account_id   bigint   NOT NULL REFERENCES accounts(account_id),
    txn_type     varchar(10) NOT NULL,
    amount       numeric(15,2) NOT NULL,
    amount_abs   numeric(15,2) GENERATED ALWAYS AS (abs(amount)) STORED,  -- GENERATED -> loader must SKIP
    txn_ts       timestamp NOT NULL,
    value_date   date      NOT NULL,                          -- DATE_AFTER(txn_ts) candidate
    description  varchar(140),
    counterparty varchar(80),
    CONSTRAINT ck_txn_type CHECK (txn_type IN ('DEBIT','CREDIT','FEE','INTEREST','REVERSAL')),  -- IN
    CONSTRAINT ck_txn_amt  CHECK (amount > 0 AND amount <= 1000000)        -- AND -> MIN(exclusive) + MAX
);

-- ----------------------------------------------------------------------------
-- 9) cards - PCI/PII columns for banking-safe profiling & masking checks
-- ----------------------------------------------------------------------------
CREATE TABLE cards (
    card_id      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id  bigint  NOT NULL REFERENCES customers(customer_id),
    pan          varchar(19) NOT NULL UNIQUE,                 -- PCI; UNIQUE
    card_type    varchar(12) NOT NULL,
    expiry_month integer NOT NULL,
    expiry_year  integer NOT NULL,
    cardholder   varchar(80) NOT NULL,
    CONSTRAINT ck_card_type CHECK (card_type IN ('VISA','MASTERCARD','AMEX')),  -- IN
    CONSTRAINT ck_card_mon  CHECK (expiry_month BETWEEN 1 AND 12)               -- RANGE
);

-- ----------------------------------------------------------------------------
-- 10) prod_customers - PRODUCTION-LIKE SOURCE for the profiler ("learn distributions").
--     The FIRST 5000 rows are deliberately UNREPRESENTATIVE (all VIP / US / very high
--     income / score 820). A LIMIT-based (first-N) sampler would be badly biased toward
--     those; the RANDOM sampler should instead report ~realistic proportions.
-- ----------------------------------------------------------------------------
CREATE TABLE prod_customers (
    id            bigint PRIMARY KEY,
    full_name     varchar(80),
    email         varchar(120),
    gender        char(1),
    country       char(2),
    segment       varchar(12),
    credit_score  integer,
    annual_income numeric(12,2),
    is_active     boolean,
    signup_date   date
);

INSERT INTO prod_customers
SELECT g,
       'Customer ' || g,
       'customer' || g || '@example.test',
       CASE WHEN g % 2 = 0 THEN 'M' ELSE 'F' END,
       CASE WHEN g <= 5000 THEN 'US'
            ELSE (ARRAY['US','GB','IN','DE','FR','JP'])[1 + (g % 6)] END,
       CASE WHEN g <= 5000 THEN 'VIP'
            ELSE (ARRAY['RETAIL','RETAIL','RETAIL','SME','PRIVATE','VIP'])[1 + (g % 6)] END,
       CASE WHEN g <= 5000 THEN 820
            ELSE 300 + (g * 7) % 550 END,
       CASE WHEN g <= 5000 THEN 950000.00
            ELSE round((20000 + (g * 13) % 180000)::numeric, 2) END,
       (g % 10) <> 0,                                         -- ~90% active
       DATE '2015-01-01' + (g % 3650)
FROM generate_series(1, 50000) AS g;

-- Quick sanity: the TRUE country mix (random sample should approximate this,
-- a first-5000 sample would wrongly say ~100% US):
--   SELECT country, count(*) FROM prod_customers GROUP BY country ORDER BY 2 DESC;

-- ============================================================================
-- VERIFICATION HELPERS (run after a generation job)
-- ============================================================================
-- Row counts:
--   SELECT 'customers' t, count(*) FROM customers
--   UNION ALL SELECT 'accounts', count(*) FROM accounts
--   UNION ALL SELECT 'transactions', count(*) FROM transactions;
--
-- FK orphans (must all be 0):
--   SELECT count(*) FROM accounts a LEFT JOIN customers c ON a.customer_id=c.customer_id WHERE c.customer_id IS NULL;
--   SELECT count(*) FROM transactions t LEFT JOIN accounts a ON t.account_id=a.account_id WHERE a.account_id IS NULL;
--
-- Uniqueness (must return 0 rows):
--   SELECT email, count(*) FROM customers GROUP BY email HAVING count(*)>1;
--   SELECT account_number, count(*) FROM accounts GROUP BY account_number HAVING count(*)>1;
--
-- CHECK compliance (must all be 0):
--   SELECT count(*) FROM customers WHERE kyc_status NOT IN ('PENDING','VERIFIED','REJECTED','EXPIRED');
--   SELECT count(*) FROM customers WHERE risk_score < 0 OR risk_score > 100;
--   SELECT count(*) FROM accounts  WHERE balance < 0;
--   SELECT count(*) FROM transactions WHERE amount <= 0 OR amount > 1000000;
--
-- LOOKUP consistency (must be 0 - holder_email should equal the customer's email):
--   SELECT count(*) FROM accounts a JOIN customers c ON a.customer_id=c.customer_id
--   WHERE a.holder_email IS DISTINCT FROM c.email;
--
-- Cardinality (children per parent within configured min/max):
--   SELECT customer_id, count(*) FROM accounts GROUP BY customer_id ORDER BY 2 DESC LIMIT 20;
--
-- Composite FK validity (must be 0):
--   SELECT count(*) FROM fx_trades f LEFT JOIN exchange_rates r
--     ON f.base_ccy=r.base_ccy AND f.quote_ccy=r.quote_ccy WHERE r.base_ccy IS NULL;

-- ============================================================================
-- DIALECT NOTES (adapt the types/affixes; the generator handles the rest)
-- ----------------------------------------------------------------------------
-- ORACLE:
--   * Identity:  GENERATED ALWAYS AS IDENTITY  (12c+); boolean -> NUMBER(1) or CHAR(1) with CHECK IN (0,1)
--   * Generated col:  col GENERATED ALWAYS AS (expr) VIRTUAL
--   * timestamp -> TIMESTAMP; date -> DATE; varchar -> VARCHAR2
--   * CHECK metadata is read from ALL_CONSTRAINTS.SEARCH_CONDITION
-- SQL SERVER:
--   * Identity:  IDENTITY(1,1); boolean -> bit; timestamp -> datetime2
--   * Generated col:  col AS (expr) PERSISTED
--   * CURRENT_DATE -> CAST(GETDATE() AS date)
--   * CHECK metadata is read from sys.check_constraints.definition
-- DB2:
--   * Identity:  GENERATED ALWAYS AS IDENTITY; boolean -> SMALLINT/CHAR with CHECK
--   * Generated col:  col GENERATED ALWAYS AS (expr)
--   * CHECK metadata is read from SYSCAT.CHECKS.TEXT
-- MYSQL / MARIADB:
--   * Identity:  AUTO_INCREMENT; boolean -> TINYINT(1)
--   * Generated col:  col AS (expr) STORED
--   * CHECK enforced only on MySQL 8.0.16+ / MariaDB 10.2+
-- ============================================================================
