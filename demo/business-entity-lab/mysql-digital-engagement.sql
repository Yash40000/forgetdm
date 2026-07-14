SET SESSION cte_max_recursion_depth = 100000;
DROP DATABASE IF EXISTS digital_engagement;
CREATE DATABASE digital_engagement CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
DROP USER IF EXISTS 'be_digital'@'localhost';
CREATE USER 'be_digital'@'localhost' IDENTIFIED BY 'ForgeTdm2026!';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON digital_engagement.* TO 'be_digital'@'localhost';
USE digital_engagement;

CREATE TABLE seed_numbers (n int PRIMARY KEY);
INSERT INTO seed_numbers(n)
WITH RECURSIVE seq AS (
  SELECT 1 AS n
  UNION ALL
  SELECT n + 1 FROM seq WHERE n < 100000
)
SELECT n FROM seq;

CREATE TABLE digital_customers (
  digital_customer_id bigint PRIMARY KEY,
  crm_party_id varchar(20) NOT NULL UNIQUE,
  core_customer_ref varchar(20) NOT NULL UNIQUE,
  username varchar(80) NOT NULL UNIQUE,
  email_address varchar(160) NOT NULL,
  mobile_phone varchar(24),
  registration_channel varchar(20) NOT NULL,
  digital_status varchar(20) NOT NULL,
  created_at timestamp NOT NULL,
  updated_at timestamp NOT NULL
) ENGINE=InnoDB;
CREATE TABLE user_profiles (
  profile_id bigint PRIMARY KEY,
  digital_customer_id bigint NOT NULL UNIQUE,
  display_name varchar(120) NOT NULL,
  preferred_language varchar(10) NOT NULL,
  time_zone varchar(40) NOT NULL,
  accessibility_mode varchar(20),
  last_profile_update timestamp NOT NULL,
  CONSTRAINT fk_profile_customer FOREIGN KEY (digital_customer_id) REFERENCES digital_customers(digital_customer_id)
) ENGINE=InnoDB;
CREATE TABLE devices (
  device_id bigint PRIMARY KEY,
  digital_customer_id bigint NOT NULL,
  device_fingerprint varchar(80) NOT NULL UNIQUE,
  device_type varchar(20) NOT NULL,
  operating_system varchar(30) NOT NULL,
  trusted_flag boolean NOT NULL,
  first_seen_at timestamp NOT NULL,
  last_seen_at timestamp NOT NULL,
  CONSTRAINT fk_device_customer FOREIGN KEY (digital_customer_id) REFERENCES digital_customers(digital_customer_id)
) ENGINE=InnoDB;
CREATE TABLE login_events (
  login_event_id bigint PRIMARY KEY,
  digital_customer_id bigint NOT NULL,
  device_id bigint,
  event_ts timestamp NOT NULL,
  ip_address varchar(45) NOT NULL,
  authentication_method varchar(30) NOT NULL,
  event_result varchar(20) NOT NULL,
  risk_score decimal(5,2) NOT NULL,
  CONSTRAINT fk_login_customer FOREIGN KEY (digital_customer_id) REFERENCES digital_customers(digital_customer_id),
  CONSTRAINT fk_login_device FOREIGN KEY (device_id) REFERENCES devices(device_id)
) ENGINE=InnoDB;
CREATE TABLE notification_preferences (
  digital_customer_id bigint PRIMARY KEY,
  email_enabled boolean NOT NULL,
  sms_enabled boolean NOT NULL,
  push_enabled boolean NOT NULL,
  transaction_alert_threshold decimal(14,2) NOT NULL,
  quiet_hours_start time,
  quiet_hours_end time,
  updated_at timestamp NOT NULL,
  CONSTRAINT fk_notification_customer FOREIGN KEY (digital_customer_id) REFERENCES digital_customers(digital_customer_id)
) ENGINE=InnoDB;
CREATE TABLE support_cases (
  support_case_id bigint PRIMARY KEY,
  digital_customer_id bigint NOT NULL,
  opened_at timestamp NOT NULL,
  case_category varchar(30) NOT NULL,
  case_priority varchar(12) NOT NULL,
  case_status varchar(20) NOT NULL,
  assigned_team varchar(40) NOT NULL,
  subject varchar(160) NOT NULL,
  closed_at timestamp NULL,
  CONSTRAINT fk_support_customer FOREIGN KEY (digital_customer_id) REFERENCES digital_customers(digital_customer_id)
) ENGINE=InnoDB;
CREATE TABLE chat_interactions (
  interaction_id bigint PRIMARY KEY,
  support_case_id bigint,
  digital_customer_id bigint NOT NULL,
  interaction_ts timestamp NOT NULL,
  channel varchar(20) NOT NULL,
  agent_id varchar(30),
  sentiment varchar(20) NOT NULL,
  transcript_excerpt varchar(500),
  CONSTRAINT fk_chat_case FOREIGN KEY (support_case_id) REFERENCES support_cases(support_case_id),
  CONSTRAINT fk_chat_customer FOREIGN KEY (digital_customer_id) REFERENCES digital_customers(digital_customer_id)
) ENGINE=InnoDB;
CREATE TABLE web_sessions (
  session_id bigint PRIMARY KEY,
  digital_customer_id bigint NOT NULL,
  device_id bigint,
  session_started_at timestamp NOT NULL,
  session_ended_at timestamp NULL,
  entry_page varchar(120) NOT NULL,
  session_outcome varchar(30) NOT NULL,
  CONSTRAINT fk_session_customer FOREIGN KEY (digital_customer_id) REFERENCES digital_customers(digital_customer_id),
  CONSTRAINT fk_session_device FOREIGN KEY (device_id) REFERENCES devices(device_id)
) ENGINE=InnoDB;
CREATE TABLE marketing_events (
  marketing_event_id bigint PRIMARY KEY,
  digital_customer_id bigint NOT NULL,
  campaign_code varchar(30) NOT NULL,
  event_ts timestamp NOT NULL,
  event_type varchar(20) NOT NULL,
  channel varchar(20) NOT NULL,
  consent_basis varchar(30) NOT NULL,
  CONSTRAINT fk_marketing_customer FOREIGN KEY (digital_customer_id) REFERENCES digital_customers(digital_customer_id)
) ENGINE=InnoDB;
CREATE TABLE offer_assignments (
  offer_assignment_id bigint PRIMARY KEY,
  digital_customer_id bigint NOT NULL,
  offer_code varchar(30) NOT NULL,
  assigned_at timestamp NOT NULL,
  expires_at timestamp NOT NULL,
  offer_status varchar(20) NOT NULL,
  propensity_score decimal(5,4) NOT NULL,
  CONSTRAINT fk_offer_customer FOREIGN KEY (digital_customer_id) REFERENCES digital_customers(digital_customer_id)
) ENGINE=InnoDB;

INSERT INTO digital_customers
SELECT 700000 + n,
       CONCAT('CRM-', LPAD(n,6,'0')),
       CONCAT('CUST-', LPAD(n,6,'0')),
       CONCAT('customer',n),
       CONCAT('customer',n,'@forgetdm-bank.example'),
       CONCAT('+1-212-',LPAD(MOD(n*17,1000),3,'0'),'-',LPAD(n,4,'0')),
       ELT(1 + MOD(n-1,4),'MOBILE','WEB','BRANCH_ENROLLMENT','CALL_CENTER'),
       IF(MOD(n,41)=0,'LOCKED','ACTIVE'),
       TIMESTAMPADD(DAY,-(900 + MOD(n,1200)),CURRENT_TIMESTAMP),
       TIMESTAMPADD(DAY,-MOD(n,180),CURRENT_TIMESTAMP)
FROM seed_numbers WHERE n <= 2000;

INSERT INTO user_profiles
SELECT 800000 + n, 700000 + n, CONCAT('Customer ',n),
       ELT(1 + MOD(n-1,3),'en-US','es-US','fr-CA'),
       ELT(1 + MOD(n-1,4),'America/New_York','America/Chicago','America/Denver','America/Los_Angeles'),
       IF(MOD(n,29)=0,'HIGH_CONTRAST',NULL), TIMESTAMPADD(DAY,-MOD(n,180),CURRENT_TIMESTAMP)
FROM seed_numbers WHERE n <= 2000;

INSERT INTO devices
SELECT 900000 + n, 700000 + MOD(n-1,2000) + 1, CONCAT('FP-',SHA2(CONCAT('device-',n),256)),
       ELT(1 + MOD(n-1,3),'PHONE','TABLET','DESKTOP'),
       ELT(1 + MOD(n-1,4),'IOS','ANDROID','WINDOWS','MACOS'), MOD(n,17)<>0,
       TIMESTAMPADD(DAY,-MOD(n,700),CURRENT_TIMESTAMP), TIMESTAMPADD(MINUTE,-MOD(n,525600),CURRENT_TIMESTAMP)
FROM seed_numbers WHERE n <= 4000;

INSERT INTO login_events
SELECT 1000000 + n, 700000 + MOD(n-1,2000) + 1, 900000 + MOD(n-1,4000) + 1,
       TIMESTAMPADD(MINUTE,-MOD(n,525600),CURRENT_TIMESTAMP),
       CONCAT('10.',MOD(n,255),'.',MOD(n*7,255),'.',MOD(n*13,255)),
       ELT(1 + MOD(n-1,4),'PASSWORD_MFA','BIOMETRIC','PASSKEY','OTP'),
       IF(MOD(n,97)=0,'DENIED','SUCCESS'), ROUND(5 + MOD(n*19,9500)/100,2)
FROM seed_numbers WHERE n <= 30000;

INSERT INTO notification_preferences
SELECT 700000 + n, MOD(n,11)<>0, MOD(n,7)<>0, MOD(n,13)<>0,
       ROUND(25 + MOD(n*23,250000)/100,2), '22:00:00', '07:00:00', TIMESTAMPADD(DAY,-MOD(n,180),CURRENT_TIMESTAMP)
FROM seed_numbers WHERE n <= 2000;

INSERT INTO support_cases
SELECT 1100000 + n, 700000 + MOD(n-1,2000) + 1, TIMESTAMPADD(DAY,-MOD(n,540),CURRENT_TIMESTAMP),
       ELT(1 + MOD(n-1,5),'LOGIN','PAYMENT','CARD','ACCOUNT','FRAUD'),
       ELT(1 + MOD(n-1,3),'LOW','MEDIUM','HIGH'),
       ELT(1 + MOD(n-1,4),'OPEN','PENDING','RESOLVED','CLOSED'),
       CONCAT('Digital Support ',1 + MOD(n,12)), CONCAT('Acceptance case ',n),
       IF(MOD(n-1,4)>=2,TIMESTAMPADD(DAY,-MOD(n,30),CURRENT_TIMESTAMP),NULL)
FROM seed_numbers WHERE n <= 4000;

INSERT INTO chat_interactions
SELECT 1200000 + n, 1100000 + MOD(n-1,4000) + 1, 700000 + MOD(n-1,2000) + 1,
       TIMESTAMPADD(MINUTE,-MOD(n,525600),CURRENT_TIMESTAMP),
       ELT(1 + MOD(n-1,3),'CHAT','PHONE','SECURE_MESSAGE'), CONCAT('AGENT-',LPAD(1 + MOD(n,75),4,'0')),
       ELT(1 + MOD(n-1,3),'POSITIVE','NEUTRAL','NEGATIVE'), CONCAT('Synthetic acceptance interaction ',n)
FROM seed_numbers WHERE n <= 10000;

INSERT INTO web_sessions
SELECT 1300000 + n, 700000 + MOD(n-1,2000) + 1, 900000 + MOD(n-1,4000) + 1,
       TIMESTAMPADD(MINUTE,-MOD(n,525600),CURRENT_TIMESTAMP),
       TIMESTAMPADD(MINUTE,-MOD(n,525600)+MOD(n,45),CURRENT_TIMESTAMP),
       ELT(1 + MOD(n-1,5),'/accounts','/cards','/payments','/support','/offers'),
       ELT(1 + MOD(n-1,4),'VIEWED','TRANSACTED','ABANDONED','ERROR')
FROM seed_numbers WHERE n <= 20000;

INSERT INTO marketing_events
SELECT 1400000 + n, 700000 + MOD(n-1,2000) + 1, CONCAT('CMP-',LPAD(1 + MOD(n,40),4,'0')),
       TIMESTAMPADD(MINUTE,-MOD(n,525600),CURRENT_TIMESTAMP),
       ELT(1 + MOD(n-1,4),'DELIVERED','OPENED','CLICKED','UNSUBSCRIBED'),
       ELT(1 + MOD(n-1,3),'EMAIL','PUSH','SMS'), 'RECORDED_CONSENT'
FROM seed_numbers WHERE n <= 15000;

INSERT INTO offer_assignments
SELECT 1500000 + n, 700000 + MOD(n-1,2000) + 1, CONCAT('OFFER-',LPAD(1 + MOD(n,80),4,'0')),
       TIMESTAMPADD(DAY,-MOD(n,120),CURRENT_TIMESTAMP), TIMESTAMPADD(DAY,30-MOD(n,20),CURRENT_TIMESTAMP),
       ELT(1 + MOD(n-1,4),'ASSIGNED','VIEWED','ACCEPTED','EXPIRED'), ROUND(MOD(n*37,10000)/10000,4)
FROM seed_numbers WHERE n <= 5000;

CREATE INDEX ix_devices_customer ON devices(digital_customer_id);
CREATE INDEX ix_login_customer_ts ON login_events(digital_customer_id,event_ts);
CREATE INDEX ix_support_customer ON support_cases(digital_customer_id);
CREATE INDEX ix_chat_customer_ts ON chat_interactions(digital_customer_id,interaction_ts);
CREATE INDEX ix_sessions_customer_ts ON web_sessions(digital_customer_id,session_started_at);
CREATE INDEX ix_marketing_customer_ts ON marketing_events(digital_customer_id,event_ts);
DROP TABLE seed_numbers;
ANALYZE TABLE digital_customers, devices, login_events, support_cases, chat_interactions, web_sessions, marketing_events;
