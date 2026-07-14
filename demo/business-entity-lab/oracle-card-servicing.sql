whenever sqlerror exit sql.sqlcode
set define off
set serveroutput on

declare
  n number;
begin
  select count(*) into n from dba_users where username = 'BE_CARDS';
  if n > 0 then execute immediate 'drop user BE_CARDS cascade'; end if;
end;
/
create user BE_CARDS identified by ForgeTdm2026 default tablespace USERS temporary tablespace TEMP quota unlimited on USERS;
grant create session, create table, create view, create sequence, create procedure to BE_CARDS;
connect BE_CARDS/ForgeTdm2026@XE

create table card_customers (
  card_customer_id number(12) primary key,
  party_ref varchar2(20) not null unique,
  core_customer_no varchar2(20) not null unique,
  first_name varchar2(60) not null,
  last_name varchar2(60) not null,
  date_of_birth date not null,
  email_address varchar2(160),
  mobile_phone varchar2(24),
  servicing_status varchar2(20) not null,
  created_at timestamp not null,
  updated_at timestamp not null
);
create table card_accounts (
  card_account_id number(12) primary key,
  card_customer_id number(12) not null references card_customers(card_customer_id),
  core_account_ref varchar2(24) not null,
  account_status varchar2(20) not null,
  credit_limit number(14,2) not null,
  current_balance number(14,2) not null,
  opened_on date not null,
  billing_cycle_day number(2) not null
);
create table payment_cards (
  payment_card_id number(12) primary key,
  card_account_id number(12) not null references card_accounts(card_account_id),
  card_customer_id number(12) not null references card_customers(card_customer_id),
  core_card_ref varchar2(24) not null unique,
  pan varchar2(19) not null unique,
  card_type varchar2(20) not null,
  expiry_month number(2) not null,
  expiry_year number(4) not null,
  card_status varchar2(20) not null
);
create table card_tokens (
  token_id number(12) primary key,
  payment_card_id number(12) not null references payment_cards(payment_card_id),
  token_reference varchar2(64) not null unique,
  wallet_provider varchar2(30) not null,
  device_reference varchar2(60),
  token_status varchar2(20) not null,
  created_at timestamp not null
);
create table merchants (
  merchant_id number(12) primary key,
  merchant_name varchar2(120) not null,
  merchant_category varchar2(8) not null,
  city varchar2(60) not null,
  state_code char(2) not null,
  risk_tier varchar2(12) not null
);
create table card_authorizations (
  authorization_id number(14) primary key,
  payment_card_id number(12) not null references payment_cards(payment_card_id),
  merchant_id number(12) not null references merchants(merchant_id),
  authorization_ts timestamp not null,
  authorization_code varchar2(12) not null,
  amount number(14,2) not null,
  currency_code char(3) not null,
  response_code varchar2(5) not null,
  channel varchar2(20) not null
);
create table card_postings (
  posting_id number(14) primary key,
  authorization_id number(14) references card_authorizations(authorization_id),
  card_account_id number(12) not null references card_accounts(card_account_id),
  posted_at timestamp not null,
  posting_type varchar2(20) not null,
  amount number(14,2) not null,
  description varchar2(160) not null
);
create table card_statements (
  statement_id number(14) primary key,
  card_account_id number(12) not null references card_accounts(card_account_id),
  cycle_start date not null,
  cycle_end date not null,
  statement_balance number(14,2) not null,
  minimum_due number(14,2) not null,
  due_date date not null,
  statement_status varchar2(20) not null
);
create table card_payments (
  card_payment_id number(14) primary key,
  card_account_id number(12) not null references card_accounts(card_account_id),
  payment_ts timestamp not null,
  amount number(14,2) not null,
  payment_method varchar2(20) not null,
  source_account_last4 char(4),
  payment_status varchar2(20) not null
);
create table card_disputes (
  dispute_id number(12) primary key,
  card_customer_id number(12) not null references card_customers(card_customer_id),
  posting_id number(14) not null references card_postings(posting_id),
  opened_at timestamp not null,
  dispute_reason varchar2(40) not null,
  disputed_amount number(14,2) not null,
  dispute_status varchar2(20) not null,
  resolved_at timestamp
);
create table fraud_cases (
  fraud_case_id number(12) primary key,
  card_customer_id number(12) not null references card_customers(card_customer_id),
  payment_card_id number(12) references payment_cards(payment_card_id),
  opened_at timestamp not null,
  case_type varchar2(30) not null,
  risk_score number(5,2) not null,
  case_status varchar2(20) not null,
  investigator varchar2(80)
);
create table customer_preferences (
  card_customer_id number(12) primary key references card_customers(card_customer_id),
  statement_delivery varchar2(20) not null,
  sms_alerts char(1) not null,
  email_alerts char(1) not null,
  travel_notice_country char(2),
  preferred_language varchar2(10) not null,
  updated_at timestamp not null
);
create table card_audit_events (
  audit_event_id number(14) primary key,
  card_customer_id number(12) references card_customers(card_customer_id),
  event_ts timestamp not null,
  event_type varchar2(30) not null,
  actor_id varchar2(40) not null,
  source_system varchar2(30) not null,
  event_detail varchar2(240)
);

insert into card_customers
select 500000 + level,
       'CARDPARTY-' || lpad(level,6,'0'),
       'CUST-' || lpad(level,6,'0'),
       decode(mod(level-1,8),0,'James',1,'Mary',2,'Robert',3,'Patricia',4,'John',5,'Jennifer',6,'Michael','Linda'),
       decode(mod(level*7-1,8),0,'Smith',1,'Johnson',2,'Williams',3,'Brown',4,'Jones',5,'Garcia',6,'Miller','Davis'),
       date '1950-01-01' + mod(level * 37,19000),
       'customer' || level || '@forgetdm-bank.example',
       '+1-212-' || lpad(mod(level*17,1000),3,'0') || '-' || lpad(level,4,'0'),
       case when mod(level,41)=0 then 'RESTRICTED' else 'ACTIVE' end,
       systimestamp - numtodsinterval(900 + mod(level,1200),'DAY'),
       systimestamp - numtodsinterval(mod(level,180),'DAY')
from dual connect by level <= 2000;

insert into card_accounts
select 600000 + level, 500000 + mod(level-1,2000) + 1,
       '100200' || lpad(mod(level-1,4000)+1,10,'0'),
       case when mod(level,53)=0 then 'SUSPENDED' else 'OPEN' end,
       2500 + mod(level*137,47500), mod(level*97,3000000)/100,
       trunc(sysdate) - (180 + mod(level,2200)), 1 + mod(level,28)
from dual connect by level <= 3000;

insert into payment_cards
select 700000 + level, 600000 + level, 500000 + mod(level-1,2000) + 1,
       'CARD-' || lpad(level,8,'0'), '411111' || lpad(level,10,'0'),
       decode(mod(level-1,3),0,'VISA',1,'MASTERCARD','DEBIT'),
       1 + mod(level,12), 2027 + mod(level,5),
       case when mod(level,71)=0 then 'BLOCKED' else 'ACTIVE' end
from dual connect by level <= 3000;

insert into card_tokens
select 800000 + level, 700000 + level, 'TOK-' || lpad(level,20,'0'),
       decode(mod(level-1,3),0,'APPLE_PAY',1,'GOOGLE_PAY','SAMSUNG_PAY'),
       'DEVICE-' || lpad(level,10,'0'), case when mod(level,73)=0 then 'SUSPENDED' else 'ACTIVE' end,
       systimestamp - numtodsinterval(mod(level,500),'DAY')
from dual connect by level <= 3000;

insert into merchants
select 900000 + level, 'Merchant ' || level, lpad(5000 + mod(level,500),4,'0'),
       decode(mod(level-1,8),0,'New York',1,'Jersey City',2,'Los Angeles',3,'Austin',4,'Miami',5,'Chicago',6,'Seattle','Boston'),
       decode(mod(level-1,8),0,'NY',1,'NJ',2,'CA',3,'TX',4,'FL',5,'IL',6,'WA','MA'),
       case when mod(level,23)=0 then 'HIGH' when mod(level,5)=0 then 'MEDIUM' else 'LOW' end
from dual connect by level <= 1200;

insert into card_authorizations
select 1000000 + level, 700000 + mod(level-1,3000) + 1, 900000 + mod(level-1,1200) + 1,
       systimestamp - numtodsinterval(mod(level,525600),'MINUTE'), lpad(mod(level,1000000),6,'0'),
       2 + mod(level*11,250000)/100, 'USD', case when mod(level,89)=0 then '05' else '00' end,
       decode(mod(level-1,4),0,'ECOMMERCE',1,'CHIP',2,'CONTACTLESS','MOBILE')
from dual connect by level <= 50000;

insert into card_postings
select 1100000 + level, 1000000 + level, 600000 + mod(level-1,3000) + 1,
       systimestamp - numtodsinterval(mod(level,525600),'MINUTE'),
       decode(mod(level-1,3),0,'PURCHASE',1,'REFUND','FEE'),
       2 + mod(level*13,300000)/100, 'Card posting ' || level
from dual connect by level <= 50000;

insert into card_statements
select 1200000 + level, 600000 + mod(level-1,3000) + 1,
       trunc(sysdate,'MM') - (30 * mod(level,24)), trunc(sysdate,'MM') - (30 * mod(level,24)) + 29,
       mod(level*211,5000000)/100, mod(level*17,100000)/100,
       trunc(sysdate) + mod(level,28), case when mod(level,17)=0 then 'PAST_DUE' else 'ISSUED' end
from dual connect by level <= 12000;

insert into card_payments
select 1300000 + level, 600000 + mod(level-1,3000) + 1,
       systimestamp - numtodsinterval(mod(level,525600),'MINUTE'), 25 + mod(level*29,250000)/100,
       decode(mod(level-1,4),0,'ACH',1,'DEBIT_CARD',2,'BRANCH','MOBILE'),
       lpad(mod(level,10000),4,'0'), case when mod(level,97)=0 then 'RETURNED' else 'POSTED' end
from dual connect by level <= 12000;

insert into card_disputes
select 1400000 + level, 500000 + mod(level-1,2000) + 1, 1100000 + level,
       systimestamp - numtodsinterval(mod(level,365),'DAY'),
       decode(mod(level-1,4),0,'FRAUD',1,'DUPLICATE',2,'SERVICE_NOT_RECEIVED','INCORRECT_AMOUNT'),
       10 + mod(level*31,150000)/100, decode(mod(level-1,3),0,'OPEN',1,'INVESTIGATING','RESOLVED'),
       case when mod(level-1,3)=2 then systimestamp - numtodsinterval(mod(level,30),'DAY') else null end
from dual connect by level <= 600;

insert into fraud_cases
select 1500000 + level,
       500000 + mod(mod(level*7-1,3000),2000) + 1,
       700000 + mod(level*7-1,3000) + 1,
       systimestamp - numtodsinterval(mod(level,365),'DAY'),
       decode(mod(level-1,3),0,'ACCOUNT_TAKEOVER',1,'CARD_NOT_PRESENT','LOST_STOLEN'),
       25 + mod(level*17,7500)/100, decode(mod(level-1,3),0,'OPEN',1,'REVIEW','CLOSED'), 'Investigator ' || (1 + mod(level,20))
from dual connect by level <= 400;

insert into customer_preferences
select 500000 + level, decode(mod(level,2),0,'ELECTRONIC','PAPER'),
       case when mod(level,7)=0 then 'N' else 'Y' end, case when mod(level,11)=0 then 'N' else 'Y' end,
       case when mod(level,19)=0 then 'GB' else null end, decode(mod(level-1,3),0,'EN',1,'ES','FR'),
       systimestamp - numtodsinterval(mod(level,180),'DAY')
from dual connect by level <= 2000;

insert into card_audit_events
select 1600000 + level, 500000 + mod(level-1,2000) + 1,
       systimestamp - numtodsinterval(mod(level,525600),'MINUTE'),
       decode(mod(level-1,5),0,'PROFILE_VIEW',1,'CARD_UPDATE',2,'PAYMENT_POSTED',3,'ALERT_SENT','CASE_REVIEW'),
       'USER-' || lpad(1 + mod(level,75),4,'0'), 'CARD_SERVICING', 'Acceptance event ' || level
from dual connect by level <= 5000;

create index ix_card_accounts_customer on card_accounts(card_customer_id);
create index ix_payment_cards_customer on payment_cards(card_customer_id);
create index ix_authorizations_card on card_authorizations(payment_card_id, authorization_ts);
create index ix_postings_account on card_postings(card_account_id, posted_at);
create index ix_disputes_customer on card_disputes(card_customer_id);
create index ix_fraud_customer on fraud_cases(card_customer_id);
commit;

begin
  dbms_stats.gather_schema_stats('BE_CARDS', cascade => true);
end;
/
exit
