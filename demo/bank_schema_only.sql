-- ForgeTDM demo: a realistic "bank" source schema with PII + an identical empty target schema.
-- Run against the bankdemo (source) and bankqa (target) databases created by docker-compose.

CREATE TABLE customers (
  id           SERIAL PRIMARY KEY,
  first_name   VARCHAR(60)  NOT NULL,
  last_name    VARCHAR(60)  NOT NULL,
  email        VARCHAR(120) NOT NULL,
  phone        VARCHAR(30),
  ssn          VARCHAR(11)  NOT NULL,
  dob          DATE         NOT NULL,
  street_addr  VARCHAR(120),
  city         VARCHAR(60),
  state        VARCHAR(2),
  zip          VARCHAR(10),
  vip          BOOLEAN DEFAULT FALSE
);

CREATE TABLE accounts (
  id           SERIAL PRIMARY KEY,
  customer_id  INT NOT NULL REFERENCES customers(id),
  account_no   VARCHAR(20) NOT NULL,
  card_number  VARCHAR(25),
  balance      NUMERIC(12,2) DEFAULT 0,
  status       VARCHAR(15) DEFAULT 'ACTIVE'
);

CREATE TABLE transactions (
  id           SERIAL PRIMARY KEY,
  account_id   INT NOT NULL REFERENCES accounts(id),
  amount       NUMERIC(12,2) NOT NULL,
  tx_date      DATE NOT NULL,
  memo         VARCHAR(200)
);

