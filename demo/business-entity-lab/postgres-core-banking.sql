\set ON_ERROR_STOP on

DROP SCHEMA IF EXISTS be_core CASCADE;
CREATE SCHEMA be_core AUTHORIZATION CURRENT_USER;
SET search_path TO be_core;

CREATE TABLE branches (
  branch_id integer PRIMARY KEY,
  branch_code varchar(12) NOT NULL UNIQUE,
  branch_name varchar(100) NOT NULL,
  state_code char(2) NOT NULL,
  opened_on date NOT NULL
);
CREATE TABLE households (
  household_id bigint PRIMARY KEY,
  household_ref varchar(20) NOT NULL UNIQUE,
  segment varchar(20) NOT NULL,
  created_at timestamp NOT NULL
);
CREATE TABLE customers (
  customer_id bigint PRIMARY KEY,
  customer_no varchar(20) NOT NULL UNIQUE,
  crm_party_id varchar(20) NOT NULL UNIQUE,
  household_id bigint REFERENCES households(household_id),
  first_name varchar(60) NOT NULL,
  last_name varchar(60) NOT NULL,
  date_of_birth date NOT NULL,
  ssn varchar(11) NOT NULL UNIQUE,
  customer_status varchar(20) NOT NULL,
  risk_rating varchar(12) NOT NULL,
  created_at timestamp NOT NULL,
  updated_at timestamp NOT NULL
);
CREATE TABLE customer_addresses (
  address_id bigint PRIMARY KEY,
  customer_id bigint NOT NULL REFERENCES customers(customer_id),
  address_type varchar(15) NOT NULL,
  line1 varchar(120) NOT NULL,
  city varchar(60) NOT NULL,
  state_code char(2) NOT NULL,
  postal_code varchar(10) NOT NULL,
  is_primary boolean NOT NULL
);
CREATE TABLE customer_phones (
  phone_id bigint PRIMARY KEY,
  customer_id bigint NOT NULL REFERENCES customers(customer_id),
  phone_type varchar(15) NOT NULL,
  phone_number varchar(20) NOT NULL,
  verified_at timestamp
);
CREATE TABLE customer_emails (
  email_id bigint PRIMARY KEY,
  customer_id bigint NOT NULL REFERENCES customers(customer_id),
  email_address varchar(160) NOT NULL UNIQUE,
  is_primary boolean NOT NULL,
  verified_at timestamp
);
CREATE TABLE customer_identifiers (
  identifier_id bigint PRIMARY KEY,
  customer_id bigint NOT NULL REFERENCES customers(customer_id),
  identifier_type varchar(30) NOT NULL,
  identifier_value varchar(80) NOT NULL,
  issuing_country char(2) NOT NULL,
  expires_on date,
  UNIQUE(identifier_type, identifier_value)
);
CREATE TABLE household_members (
  household_id bigint NOT NULL REFERENCES households(household_id),
  customer_id bigint NOT NULL REFERENCES customers(customer_id),
  member_role varchar(20) NOT NULL,
  joined_on date NOT NULL,
  PRIMARY KEY(household_id, customer_id)
);
CREATE TABLE accounts (
  account_id bigint PRIMARY KEY,
  account_no varchar(24) NOT NULL UNIQUE,
  branch_id integer NOT NULL REFERENCES branches(branch_id),
  product_code varchar(20) NOT NULL,
  account_status varchar(20) NOT NULL,
  opened_on date NOT NULL,
  closed_on date,
  currency_code char(3) NOT NULL
);
CREATE TABLE account_holders (
  account_id bigint NOT NULL REFERENCES accounts(account_id),
  customer_id bigint NOT NULL REFERENCES customers(customer_id),
  holder_role varchar(20) NOT NULL,
  ownership_pct numeric(5,2) NOT NULL,
  PRIMARY KEY(account_id, customer_id)
);
CREATE TABLE account_balances (
  account_id bigint PRIMARY KEY REFERENCES accounts(account_id),
  ledger_balance numeric(16,2) NOT NULL,
  available_balance numeric(16,2) NOT NULL,
  as_of timestamp NOT NULL
);
CREATE TABLE account_transactions (
  transaction_id bigint PRIMARY KEY,
  account_id bigint NOT NULL REFERENCES accounts(account_id),
  transaction_ts timestamp NOT NULL,
  transaction_type varchar(20) NOT NULL,
  amount numeric(14,2) NOT NULL,
  merchant_description varchar(120),
  channel varchar(20) NOT NULL,
  reference_no varchar(36) NOT NULL UNIQUE
);
CREATE TABLE loans (
  loan_id bigint PRIMARY KEY,
  customer_id bigint NOT NULL REFERENCES customers(customer_id),
  account_id bigint REFERENCES accounts(account_id),
  loan_type varchar(20) NOT NULL,
  principal_amount numeric(16,2) NOT NULL,
  outstanding_amount numeric(16,2) NOT NULL,
  interest_rate numeric(7,4) NOT NULL,
  originated_on date NOT NULL,
  maturity_on date NOT NULL,
  loan_status varchar(20) NOT NULL
);
CREATE TABLE loan_payments (
  payment_id bigint PRIMARY KEY,
  loan_id bigint NOT NULL REFERENCES loans(loan_id),
  payment_date date NOT NULL,
  principal_paid numeric(14,2) NOT NULL,
  interest_paid numeric(14,2) NOT NULL,
  payment_status varchar(20) NOT NULL
);
CREATE TABLE cards (
  card_id bigint PRIMARY KEY,
  account_id bigint NOT NULL REFERENCES accounts(account_id),
  customer_id bigint NOT NULL REFERENCES customers(customer_id),
  card_ref varchar(24) NOT NULL UNIQUE,
  pan varchar(19) NOT NULL UNIQUE,
  expiry_month integer NOT NULL,
  expiry_year integer NOT NULL,
  card_status varchar(20) NOT NULL
);
CREATE TABLE card_transactions (
  card_transaction_id bigint PRIMARY KEY,
  card_id bigint NOT NULL REFERENCES cards(card_id),
  posted_at timestamp NOT NULL,
  merchant_name varchar(120) NOT NULL,
  merchant_category varchar(8) NOT NULL,
  amount numeric(14,2) NOT NULL,
  currency_code char(3) NOT NULL,
  authorization_code varchar(12) NOT NULL
);
CREATE TABLE beneficiaries (
  beneficiary_id bigint PRIMARY KEY,
  customer_id bigint NOT NULL REFERENCES customers(customer_id),
  beneficiary_name varchar(120) NOT NULL,
  bank_routing_no varchar(12) NOT NULL,
  bank_account_no varchar(30) NOT NULL,
  relationship_type varchar(20) NOT NULL,
  active boolean NOT NULL
);
CREATE TABLE standing_orders (
  order_id bigint PRIMARY KEY,
  account_id bigint NOT NULL REFERENCES accounts(account_id),
  beneficiary_id bigint NOT NULL REFERENCES beneficiaries(beneficiary_id),
  amount numeric(14,2) NOT NULL,
  frequency varchar(20) NOT NULL,
  next_run_date date NOT NULL,
  order_status varchar(20) NOT NULL
);
CREATE TABLE kyc_reviews (
  review_id bigint PRIMARY KEY,
  customer_id bigint NOT NULL REFERENCES customers(customer_id),
  review_date date NOT NULL,
  review_type varchar(20) NOT NULL,
  outcome varchar(20) NOT NULL,
  analyst varchar(80) NOT NULL,
  next_review_date date NOT NULL
);
CREATE TABLE customer_consents (
  consent_id bigint PRIMARY KEY,
  customer_id bigint NOT NULL REFERENCES customers(customer_id),
  consent_type varchar(30) NOT NULL,
  consent_status varchar(20) NOT NULL,
  captured_at timestamp NOT NULL,
  source_channel varchar(20) NOT NULL
);

INSERT INTO branches
SELECT i, 'BR-' || lpad(i::text, 3, '0'), 'Retail Branch ' || i,
       (ARRAY['NY','NJ','CA','TX','FL','IL','WA','MA','GA','NC'])[1 + ((i - 1) % 10)],
       current_date - (1000 + i * 17)
FROM generate_series(1,25) i;

INSERT INTO households
SELECT i, 'HH-' || lpad(i::text, 6, '0'),
       (ARRAY['MASS_AFFLUENT','RETAIL','PRIVATE','STUDENT'])[1 + ((i - 1) % 4)],
       current_timestamp - ((i % 700) || ' days')::interval
FROM generate_series(1,800) i;

INSERT INTO customers
SELECT i,
       'CUST-' || lpad(i::text, 6, '0'),
       'CRM-' || lpad(i::text, 6, '0'),
       1 + ((i - 1) % 800),
       (ARRAY['James','Mary','Robert','Patricia','John','Jennifer','Michael','Linda','David','Elizabeth','William','Susan'])[1 + ((i - 1) % 12)],
       (ARRAY['Smith','Johnson','Williams','Brown','Jones','Garcia','Miller','Davis','Wilson','Anderson','Taylor','Thomas'])[1 + (((i * 7) - 1) % 12)],
       date '1950-01-01' + ((i * 37) % 19000),
       '9' || lpad((i % 100)::text,2,'0') || '-' || lpad((i % 100)::text,2,'0') || '-' || lpad(i::text,4,'0'),
       CASE WHEN i % 41 = 0 THEN 'RESTRICTED' WHEN i % 29 = 0 THEN 'DORMANT' ELSE 'ACTIVE' END,
       CASE WHEN i % 17 = 0 THEN 'HIGH' WHEN i % 5 = 0 THEN 'MEDIUM' ELSE 'LOW' END,
       current_timestamp - ((900 + i % 1200) || ' days')::interval,
       current_timestamp - ((i % 180) || ' days')::interval
FROM generate_series(1,2000) i;

INSERT INTO customer_addresses
SELECT i, 1 + ((i - 1) % 2000), CASE WHEN i <= 2000 THEN 'HOME' ELSE 'MAILING' END,
       (100 + (i % 9800)) || ' ' || (ARRAY['Main','Oak','Maple','Cedar','Park','Lake','Hill','Washington'])[1 + ((i - 1) % 8)] || ' Street',
       (ARRAY['New York','Jersey City','Los Angeles','Austin','Miami','Chicago','Seattle','Boston'])[1 + ((i - 1) % 8)],
       (ARRAY['NY','NJ','CA','TX','FL','IL','WA','MA'])[1 + ((i - 1) % 8)],
       lpad((10000 + (i * 37) % 89999)::text,5,'0'), i <= 2000
FROM generate_series(1,4000) i;

INSERT INTO customer_phones
SELECT i, 1 + ((i - 1) % 2000), CASE WHEN i <= 2000 THEN 'MOBILE' ELSE 'HOME' END,
       '+1-212-' || lpad(((i * 17) % 1000)::text,3,'0') || '-' || lpad(i::text,4,'0'),
       CASE WHEN i % 7 = 0 THEN NULL ELSE current_timestamp - ((i % 300) || ' days')::interval END
FROM generate_series(1,3000) i;

INSERT INTO customer_emails
SELECT i, i, lower('customer' || i || '@forgetdm-bank.example'), true,
       CASE WHEN i % 13 = 0 THEN NULL ELSE current_timestamp - ((i % 300) || ' days')::interval END
FROM generate_series(1,2000) i;

INSERT INTO customer_identifiers
SELECT i, 1 + ((i - 1) % 2000), CASE WHEN i <= 2000 THEN 'DRIVER_LICENSE' ELSE 'PASSPORT' END,
       CASE WHEN i <= 2000 THEN 'DL-NY-' || lpad(i::text,8,'0') ELSE 'P' || lpad(i::text,9,'0') END,
       'US', current_date + (365 + i % 1800)
FROM generate_series(1,4000) i;

INSERT INTO household_members
SELECT 1 + ((i - 1) % 800), i, CASE WHEN i <= 800 THEN 'PRIMARY' ELSE 'MEMBER' END,
       current_date - (300 + i % 1800)
FROM generate_series(1,2000) i;

INSERT INTO accounts
SELECT i, '100200' || lpad(i::text,10,'0'), 1 + ((i - 1) % 25),
       (ARRAY['CHECKING','SAVINGS','MONEY_MARKET','BROKERAGE'])[1 + ((i - 1) % 4)],
       CASE WHEN i % 67 = 0 THEN 'FROZEN' WHEN i % 43 = 0 THEN 'DORMANT' ELSE 'OPEN' END,
       current_date - (180 + i % 2500), NULL, 'USD'
FROM generate_series(1,4000) i;

INSERT INTO account_holders
SELECT i, 1 + ((i - 1) % 2000), 'PRIMARY', 100.00 FROM generate_series(1,4000) i;
INSERT INTO account_holders
SELECT i, 1 + ((i + 799) % 2000), 'JOINT', 50.00 FROM generate_series(1,1000) i;

INSERT INTO account_balances
SELECT i, round((500 + (i * 97 % 125000))::numeric,2), round((450 + (i * 91 % 120000))::numeric,2),
       current_timestamp - ((i % 1440) || ' minutes')::interval
FROM generate_series(1,4000) i;

INSERT INTO account_transactions
SELECT i, 1 + ((i - 1) % 4000), current_timestamp - ((i % 525600) || ' minutes')::interval,
       (ARRAY['DEBIT','CREDIT','TRANSFER','FEE','ACH'])[1 + ((i - 1) % 5)],
       round((5 + (i * 13 % 499500) / 100.0)::numeric,2),
       'Merchant ' || (1 + i % 1200),
       (ARRAY['CARD','ONLINE','BRANCH','ATM','MOBILE'])[1 + ((i - 1) % 5)],
       'TXN-' || lpad(i::text,12,'0')
FROM generate_series(1,80000) i;

INSERT INTO loans
SELECT i, 1 + ((i - 1) % 2000), 1 + ((i - 1) % 4000),
       (ARRAY['MORTGAGE','AUTO','PERSONAL','HELOC'])[1 + ((i - 1) % 4)],
       10000 + (i * 791 % 490000), 5000 + (i * 613 % 250000),
       2.7500 + ((i % 500) / 100.0), current_date - (200 + i % 1600), current_date + (900 + i % 8000),
       CASE WHEN i % 53 = 0 THEN 'DELINQUENT' ELSE 'ACTIVE' END
FROM generate_series(1,1000) i;

INSERT INTO loan_payments
SELECT i, 1 + ((i - 1) % 1000), current_date - (i % 900),
       round((100 + (i * 19 % 2000))::numeric,2), round((10 + (i * 7 % 400))::numeric,2),
       CASE WHEN i % 79 = 0 THEN 'RETURNED' ELSE 'POSTED' END
FROM generate_series(1,12000) i;

INSERT INTO cards
SELECT i, 1 + ((i - 1) % 4000), 1 + ((i - 1) % 2000), 'CARD-' || lpad(i::text,8,'0'),
       '411111' || lpad(i::text,10,'0'), 1 + (i % 12), 2027 + (i % 5),
       CASE WHEN i % 71 = 0 THEN 'BLOCKED' ELSE 'ACTIVE' END
FROM generate_series(1,3000) i;

INSERT INTO card_transactions
SELECT i, 1 + ((i - 1) % 3000), current_timestamp - ((i % 525600) || ' minutes')::interval,
       'Merchant ' || (1 + i % 1200), lpad((5000 + i % 500)::text,4,'0'),
       round((2 + (i * 11 % 250000) / 100.0)::numeric,2), 'USD', lpad((i % 1000000)::text,6,'0')
FROM generate_series(1,60000) i;

INSERT INTO beneficiaries
SELECT i, 1 + ((i - 1) % 2000), 'Beneficiary ' || i, lpad((21000000 + i % 70000000)::text,9,'0'),
       lpad((7000000000::bigint + i)::text,12,'0'), (ARRAY['FAMILY','VENDOR','OWN_ACCOUNT','CHARITY'])[1 + ((i - 1) % 4)], i % 23 <> 0
FROM generate_series(1,4000) i;

INSERT INTO standing_orders
SELECT i, 1 + ((i - 1) % 4000), 1 + ((i - 1) % 4000), round((25 + i % 1500)::numeric,2),
       (ARRAY['WEEKLY','BIWEEKLY','MONTHLY'])[1 + ((i - 1) % 3)], current_date + (1 + i % 30),
       CASE WHEN i % 31 = 0 THEN 'PAUSED' ELSE 'ACTIVE' END
FROM generate_series(1,2000) i;

INSERT INTO kyc_reviews
SELECT i, i, current_date - (i % 720), CASE WHEN i % 5 = 0 THEN 'ENHANCED' ELSE 'PERIODIC' END,
       CASE WHEN i % 37 = 0 THEN 'ESCALATED' ELSE 'APPROVED' END, 'Analyst ' || (1 + i % 25), current_date + (180 + i % 540)
FROM generate_series(1,2000) i;

INSERT INTO customer_consents
SELECT i, 1 + ((i - 1) % 2000), CASE WHEN i <= 2000 THEN 'PRIVACY' ELSE 'MARKETING' END,
       CASE WHEN i % 19 = 0 THEN 'REVOKED' ELSE 'GRANTED' END,
       current_timestamp - ((i % 600) || ' days')::interval,
       (ARRAY['WEB','MOBILE','BRANCH','CALL_CENTER'])[1 + ((i - 1) % 4)]
FROM generate_series(1,4000) i;

CREATE INDEX ix_addresses_customer ON customer_addresses(customer_id);
CREATE INDEX ix_accounts_branch ON accounts(branch_id);
CREATE INDEX ix_account_holders_customer ON account_holders(customer_id);
CREATE INDEX ix_account_transactions_account ON account_transactions(account_id, transaction_ts);
CREATE INDEX ix_loans_customer ON loans(customer_id);
CREATE INDEX ix_cards_customer ON cards(customer_id);
CREATE INDEX ix_card_transactions_card ON card_transactions(card_id, posted_at);
CREATE INDEX ix_beneficiaries_customer ON beneficiaries(customer_id);
ANALYZE be_core.customers;
