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

INSERT INTO customers (first_name,last_name,email,phone,ssn,dob,street_addr,city,state,zip,vip) VALUES
('Alice','Henderson','alice.henderson@gmail.com','+1 (415) 555-0182','545-12-9876','1987-06-15','12 Rosewood Ave','Austin','TX','73301',true),
('Bob','Martinez','bob.martinez@yahoo.com','+1 (212) 555-7733','123-45-6789','1972-11-02','99 Lakeshore Dr','Dallas','TX','75201',false),
('Carol','Nguyen','carol.nguyen@outlook.com','+1 (646) 555-2210','321-54-9870','1995-03-28','7 Sunset Blvd','Houston','TX','77001',true),
('David','Okafor','d.okafor@gmail.com','+1 (312) 555-9911','456-78-1234','1980-09-09','220 Pine Hill Rd','Chicago','IL','60601',false),
('Eva','Brandt','eva.brandt@gmx.com','+49 89 555 2211','654-32-1098','1990-01-21','5 Elmwood Ct','Miami','FL','33101',true),
('Frank','Russo','frank.russo@gmail.com','+1 (305) 555-4040','789-01-2345','1965-07-30','310 Harbor View','Orlando','FL','32801',false);

INSERT INTO accounts (customer_id,account_no,card_number,balance,status) VALUES
(1,'ACC-100001','4111 1111 1111 1111', 15200.55,'ACTIVE'),
(1,'ACC-100002','5500 0000 0000 0004',   980.10,'ACTIVE'),
(2,'ACC-100003','4012 8888 8888 1881',  4300.00,'FROZEN'),
(3,'ACC-100004','4222 2222 2222 2220', 87100.99,'ACTIVE'),
(4,'ACC-100005','5105 1051 0510 5100',   120.00,'ACTIVE'),
(5,'ACC-100006','4111 1111 1111 1111',  6600.42,'ACTIVE'),
(6,'ACC-100007','4716 9999 1234 5678',   310.77,'CLOSED');

INSERT INTO transactions (account_id,amount,tx_date,memo) VALUES
(1, -120.00,'2026-05-01','Grocery - payment from Alice Henderson'),
(1, 2500.00,'2026-05-03','Salary credit'),
(2,  -45.20,'2026-05-04','Coffee subscription'),
(3, -900.00,'2026-05-06','Rent'),
(4, 1200.00,'2026-05-08','Invoice settlement'),
(5,  -60.00,'2026-05-09','Utilities'),
(6, -310.00,'2026-05-10','Card payment'),
(7,  -19.99,'2026-05-11','Streaming service');
