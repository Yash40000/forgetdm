CREATE SCHEMA IF NOT EXISTS "gw_billing_tgt";

CREATE TABLE "gw_billing_tgt"."bc_contact" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "party_key" VARCHAR(180) UNIQUE,
  "first_name" VARCHAR(180),
  "last_name" VARCHAR(180),
  "date_of_birth" DATE,
  "tax_identifier" VARCHAR(180),
  CONSTRAINT "pk_bc_1" PRIMARY KEY ("id")
);

CREATE TABLE "gw_billing_tgt"."bc_address" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "contact_id" BIGINT,
  "address_line1" VARCHAR(180),
  "city" VARCHAR(180),
  "state_code" VARCHAR(40),
  "postal_code" VARCHAR(40),
  CONSTRAINT "pk_bc_2" PRIMARY KEY ("id")
);

CREATE TABLE "gw_billing_tgt"."bc_account" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "primary_contact_id" BIGINT,
  "parent_account_id" BIGINT,
  CONSTRAINT "pk_bc_3" PRIMARY KEY ("id")
);

CREATE TABLE "gw_billing_tgt"."bc_account_contact" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "account_id" BIGINT,
  "contact_id" BIGINT,
  "party_key" VARCHAR(180) UNIQUE,
  "first_name" VARCHAR(180),
  "last_name" VARCHAR(180),
  "date_of_birth" DATE,
  "tax_identifier" VARCHAR(180),
  CONSTRAINT "pk_bc_4" PRIMARY KEY ("id")
);

CREATE TABLE "gw_billing_tgt"."bc_billing_plan" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  CONSTRAINT "pk_bc_5" PRIMARY KEY ("id")
);

CREATE TABLE "gw_billing_tgt"."bc_payment_plan" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "account_id" BIGINT,
  "billing_plan_id" BIGINT,
  "amount" NUMERIC(18,2),
  "currency_code" VARCHAR(40),
  CONSTRAINT "pk_bc_6" PRIMARY KEY ("id"),
  CONSTRAINT "ck_bc_amt_6" CHECK ("amount" IS NULL OR "amount" >= 0)
);

CREATE TABLE "gw_billing_tgt"."bc_invoice" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "account_id" BIGINT,
  "payment_plan_id" BIGINT,
  "amount" NUMERIC(18,2),
  "currency_code" VARCHAR(40),
  CONSTRAINT "pk_bc_7" PRIMARY KEY ("id"),
  CONSTRAINT "ck_bc_amt_7" CHECK ("amount" IS NULL OR "amount" >= 0)
);

CREATE TABLE "gw_billing_tgt"."bc_invoice_item" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "invoice_id" BIGINT,
  "amount" NUMERIC(18,2),
  "currency_code" VARCHAR(40),
  CONSTRAINT "pk_bc_8" PRIMARY KEY ("id"),
  CONSTRAINT "ck_bc_amt_8" CHECK ("amount" IS NULL OR "amount" >= 0)
);

CREATE TABLE "gw_billing_tgt"."bc_charge_pattern" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "amount" NUMERIC(18,2),
  "currency_code" VARCHAR(40),
  CONSTRAINT "pk_bc_9" PRIMARY KEY ("id"),
  CONSTRAINT "ck_bc_amt_9" CHECK ("amount" IS NULL OR "amount" >= 0)
);

CREATE TABLE "gw_billing_tgt"."bc_charge" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "account_id" BIGINT,
  "charge_pattern_id" BIGINT,
  "amount" NUMERIC(18,2),
  "currency_code" VARCHAR(40),
  CONSTRAINT "pk_bc_10" PRIMARY KEY ("id"),
  CONSTRAINT "ck_bc_amt_10" CHECK ("amount" IS NULL OR "amount" >= 0)
);

CREATE TABLE "gw_billing_tgt"."bc_policy" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "account_id" BIGINT,
  "policy_number" VARCHAR(180),
  "effective_date" DATE,
  "expiration_date" DATE,
  CONSTRAINT "pk_bc_11" PRIMARY KEY ("id"),
  CONSTRAINT "ck_bc_dates_11" CHECK ("expiration_date" IS NULL OR "effective_date" IS NULL OR "expiration_date" >= "effective_date")
);

CREATE TABLE "gw_billing_tgt"."bc_policy_period" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "policy_id" BIGINT,
  "policy_number" VARCHAR(180),
  "effective_date" DATE,
  "expiration_date" DATE,
  CONSTRAINT "pk_bc_12" PRIMARY KEY ("id"),
  CONSTRAINT "ck_bc_dates_12" CHECK ("expiration_date" IS NULL OR "effective_date" IS NULL OR "expiration_date" >= "effective_date")
);

CREATE TABLE "gw_billing_tgt"."bc_payment_instrument" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "account_id" BIGINT,
  "amount" NUMERIC(18,2),
  "currency_code" VARCHAR(40),
  CONSTRAINT "pk_bc_13" PRIMARY KEY ("id"),
  CONSTRAINT "ck_bc_amt_13" CHECK ("amount" IS NULL OR "amount" >= 0)
);

CREATE TABLE "gw_billing_tgt"."bc_bank_account" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "payment_instrument_id" BIGINT,
  "bank_account_number" VARCHAR(180) UNIQUE,
  "routing_number" VARCHAR(180),
  CONSTRAINT "pk_bc_14" PRIMARY KEY ("id")
);

CREATE TABLE "gw_billing_tgt"."bc_payment_card" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "payment_instrument_id" BIGINT,
  "amount" NUMERIC(18,2),
  "currency_code" VARCHAR(40),
  "card_number" VARCHAR(180) UNIQUE,
  "card_expiry" DATE,
  "card_brand" VARCHAR(40),
  CONSTRAINT "pk_bc_15" PRIMARY KEY ("id"),
  CONSTRAINT "ck_bc_amt_15" CHECK ("amount" IS NULL OR "amount" >= 0)
);

CREATE TABLE "gw_billing_tgt"."bc_payment" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "account_id" BIGINT,
  "payment_instrument_id" BIGINT,
  "amount" NUMERIC(18,2),
  "currency_code" VARCHAR(40),
  CONSTRAINT "pk_bc_16" PRIMARY KEY ("id"),
  CONSTRAINT "ck_bc_amt_16" CHECK ("amount" IS NULL OR "amount" >= 0)
);

CREATE TABLE "gw_billing_tgt"."bc_payment_allocation" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "payment_id" BIGINT,
  "invoice_id" BIGINT,
  "address_line1" VARCHAR(180),
  "city" VARCHAR(180),
  "state_code" VARCHAR(40),
  "postal_code" VARCHAR(40),
  "amount" NUMERIC(18,2),
  "currency_code" VARCHAR(40),
  CONSTRAINT "pk_bc_17" PRIMARY KEY ("id"),
  CONSTRAINT "ck_bc_amt_17" CHECK ("amount" IS NULL OR "amount" >= 0)
);

CREATE TABLE "gw_billing_tgt"."bc_refund" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "payment_id" BIGINT,
  "amount" NUMERIC(18,2),
  "currency_code" VARCHAR(40),
  CONSTRAINT "pk_bc_18" PRIMARY KEY ("id"),
  CONSTRAINT "ck_bc_amt_18" CHECK ("amount" IS NULL OR "amount" >= 0)
);

CREATE TABLE "gw_billing_tgt"."bc_disbursement" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "account_id" BIGINT,
  "amount" NUMERIC(18,2),
  "currency_code" VARCHAR(40),
  CONSTRAINT "pk_bc_19" PRIMARY KEY ("id"),
  CONSTRAINT "ck_bc_amt_19" CHECK ("amount" IS NULL OR "amount" >= 0)
);

CREATE TABLE "gw_billing_tgt"."bc_suspense_payment" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "payment_id" BIGINT,
  "amount" NUMERIC(18,2),
  "currency_code" VARCHAR(40),
  CONSTRAINT "pk_bc_20" PRIMARY KEY ("id"),
  CONSTRAINT "ck_bc_amt_20" CHECK ("amount" IS NULL OR "amount" >= 0)
);

CREATE TABLE "gw_billing_tgt"."bc_delinquency_plan" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  CONSTRAINT "pk_bc_21" PRIMARY KEY ("id")
);

CREATE TABLE "gw_billing_tgt"."bc_delinquency_process" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "account_id" BIGINT,
  "delinquency_plan_id" BIGINT,
  CONSTRAINT "pk_bc_22" PRIMARY KEY ("id")
);

CREATE TABLE "gw_billing_tgt"."bc_delinquency_event" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "delinquency_process_id" BIGINT,
  "detail_text" TEXT,
  "payload_json" JSONB,
  CONSTRAINT "pk_bc_23" PRIMARY KEY ("id")
);

CREATE TABLE "gw_billing_tgt"."bc_writeoff" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "account_id" BIGINT,
  "amount" NUMERIC(18,2),
  "currency_code" VARCHAR(40),
  CONSTRAINT "pk_bc_24" PRIMARY KEY ("id"),
  CONSTRAINT "ck_bc_amt_24" CHECK ("amount" IS NULL OR "amount" >= 0)
);

CREATE TABLE "gw_billing_tgt"."bc_commission_plan" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "amount" NUMERIC(18,2),
  "currency_code" VARCHAR(40),
  CONSTRAINT "pk_bc_25" PRIMARY KEY ("id"),
  CONSTRAINT "ck_bc_amt_25" CHECK ("amount" IS NULL OR "amount" >= 0)
);

CREATE TABLE "gw_billing_tgt"."bc_producer" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "contact_id" BIGINT,
  "commission_plan_id" BIGINT,
  "party_key" VARCHAR(180) UNIQUE,
  "first_name" VARCHAR(180),
  "last_name" VARCHAR(180),
  "date_of_birth" DATE,
  "tax_identifier" VARCHAR(180),
  CONSTRAINT "pk_bc_26" PRIMARY KEY ("id")
);

CREATE TABLE "gw_billing_tgt"."bc_producer_code" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "producer_id" BIGINT,
  "party_key" VARCHAR(180) UNIQUE,
  "first_name" VARCHAR(180),
  "last_name" VARCHAR(180),
  "date_of_birth" DATE,
  "tax_identifier" VARCHAR(180),
  CONSTRAINT "pk_bc_27" PRIMARY KEY ("id")
);

CREATE TABLE "gw_billing_tgt"."bc_commission" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "producer_id" BIGINT,
  "policy_period_id" BIGINT,
  "amount" NUMERIC(18,2),
  "currency_code" VARCHAR(40),
  CONSTRAINT "pk_bc_28" PRIMARY KEY ("id"),
  CONSTRAINT "ck_bc_amt_28" CHECK ("amount" IS NULL OR "amount" >= 0)
);

CREATE TABLE "gw_billing_tgt"."bc_agency_bill_plan" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "account_id" BIGINT,
  CONSTRAINT "pk_bc_29" PRIMARY KEY ("id")
);

CREATE TABLE "gw_billing_tgt"."bc_statement" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "account_id" BIGINT,
  CONSTRAINT "pk_bc_30" PRIMARY KEY ("id")
);

CREATE TABLE "gw_billing_tgt"."bc_statement_item" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "statement_id" BIGINT,
  "invoice_id" BIGINT,
  CONSTRAINT "pk_bc_31" PRIMARY KEY ("id")
);

CREATE TABLE "gw_billing_tgt"."bc_credit" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "account_id" BIGINT,
  "amount" NUMERIC(18,2),
  "currency_code" VARCHAR(40),
  CONSTRAINT "pk_bc_32" PRIMARY KEY ("id"),
  CONSTRAINT "ck_bc_amt_32" CHECK ("amount" IS NULL OR "amount" >= 0)
);

CREATE TABLE "gw_billing_tgt"."bc_debit" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "account_id" BIGINT,
  "amount" NUMERIC(18,2),
  "currency_code" VARCHAR(40),
  CONSTRAINT "pk_bc_33" PRIMARY KEY ("id"),
  CONSTRAINT "ck_bc_amt_33" CHECK ("amount" IS NULL OR "amount" >= 0)
);

CREATE TABLE "gw_billing_tgt"."bc_tax" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "invoice_item_id" BIGINT,
  "amount" NUMERIC(18,2),
  "currency_code" VARCHAR(40),
  CONSTRAINT "pk_bc_34" PRIMARY KEY ("id"),
  CONSTRAINT "ck_bc_amt_34" CHECK ("amount" IS NULL OR "amount" >= 0)
);

CREATE TABLE "gw_billing_tgt"."bc_fee" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "invoice_id" BIGINT,
  "amount" NUMERIC(18,2),
  "currency_code" VARCHAR(40),
  CONSTRAINT "pk_bc_35" PRIMARY KEY ("id"),
  CONSTRAINT "ck_bc_amt_35" CHECK ("amount" IS NULL OR "amount" >= 0)
);

CREATE TABLE "gw_billing_tgt"."bc_currency_rate" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  CONSTRAINT "pk_bc_36" PRIMARY KEY ("id")
);

CREATE TABLE "gw_billing_tgt"."bc_gl_account" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  CONSTRAINT "pk_bc_37" PRIMARY KEY ("id")
);

CREATE TABLE "gw_billing_tgt"."bc_gl_entry" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "gl_account_id" BIGINT,
  "account_id" BIGINT,
  "amount" NUMERIC(18,2),
  "currency_code" VARCHAR(40),
  CONSTRAINT "pk_bc_38" PRIMARY KEY ("id"),
  CONSTRAINT "ck_bc_amt_38" CHECK ("amount" IS NULL OR "amount" >= 0)
);

CREATE TABLE "gw_billing_tgt"."bc_batch" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  CONSTRAINT "pk_bc_39" PRIMARY KEY ("id")
);

CREATE TABLE "gw_billing_tgt"."bc_batch_item" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "batch_id" BIGINT,
  "payment_id" BIGINT,
  CONSTRAINT "pk_bc_40" PRIMARY KEY ("id")
);

CREATE TABLE "gw_billing_tgt"."bc_activity" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "account_id" BIGINT,
  CONSTRAINT "pk_bc_41" PRIMARY KEY ("id")
);

CREATE TABLE "gw_billing_tgt"."bc_note" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "account_id" BIGINT,
  "author_contact_id" BIGINT,
  "detail_text" TEXT,
  "payload_json" JSONB,
  CONSTRAINT "pk_bc_42" PRIMARY KEY ("id")
);

CREATE TABLE "gw_billing_tgt"."bc_document" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "account_id" BIGINT,
  "file_name" VARCHAR(180),
  "mime_type" VARCHAR(180),
  "document_body" BYTEA,
  CONSTRAINT "pk_bc_43" PRIMARY KEY ("id")
);

CREATE TABLE "gw_billing_tgt"."bc_contact_phone" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "contact_id" BIGINT,
  "party_key" VARCHAR(180) UNIQUE,
  "first_name" VARCHAR(180),
  "last_name" VARCHAR(180),
  "date_of_birth" DATE,
  "tax_identifier" VARCHAR(180),
  "phone_number" VARCHAR(180),
  CONSTRAINT "pk_bc_44" PRIMARY KEY ("id")
);

CREATE TABLE "gw_billing_tgt"."bc_contact_email" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "contact_id" BIGINT,
  "party_key" VARCHAR(180) UNIQUE,
  "first_name" VARCHAR(180),
  "last_name" VARCHAR(180),
  "date_of_birth" DATE,
  "tax_identifier" VARCHAR(180),
  "email_address" VARCHAR(180),
  CONSTRAINT "pk_bc_45" PRIMARY KEY ("id")
);

CREATE TABLE "gw_billing_tgt"."bc_identity" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "contact_id" BIGINT,
  CONSTRAINT "pk_bc_46" PRIMARY KEY ("id")
);

CREATE TABLE "gw_billing_tgt"."bc_audit" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "account_id" BIGINT,
  "detail_text" TEXT,
  "payload_json" JSONB,
  CONSTRAINT "pk_bc_47" PRIMARY KEY ("id")
);

CREATE TABLE "gw_billing_tgt"."bc_message_outbox" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "account_id" BIGINT,
  "detail_text" TEXT,
  "payload_json" JSONB,
  CONSTRAINT "pk_bc_48" PRIMARY KEY ("id")
);

CREATE TABLE "gw_billing_tgt"."bc_external_reference" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "account_id" BIGINT,
  CONSTRAINT "pk_bc_49" PRIMARY KEY ("id")
);

CREATE TABLE "gw_billing_tgt"."bc_change_event" (
  "id" BIGINT NOT NULL,
  "public_id" VARCHAR(180) NOT NULL UNIQUE,
  "tenant_id" VARCHAR(40),
  "status_code" VARCHAR(40),
  "version_no" INTEGER,
  "retired" BOOLEAN,
  "created_at" TIMESTAMP WITH TIME ZONE,
  "updated_at" TIMESTAMP WITH TIME ZONE,
  "account_id" BIGINT,
  "detail_text" TEXT,
  "payload_json" JSONB,
  CONSTRAINT "pk_bc_50" PRIMARY KEY ("id")
);

ALTER TABLE "gw_billing_tgt"."bc_address" ADD CONSTRAINT "fk_bc_2_1" FOREIGN KEY ("contact_id") REFERENCES "gw_billing_tgt"."bc_contact" ("id");

ALTER TABLE "gw_billing_tgt"."bc_account" ADD CONSTRAINT "fk_bc_3_1" FOREIGN KEY ("primary_contact_id") REFERENCES "gw_billing_tgt"."bc_contact" ("id");

ALTER TABLE "gw_billing_tgt"."bc_account" ADD CONSTRAINT "fk_bc_3_2" FOREIGN KEY ("parent_account_id") REFERENCES "gw_billing_tgt"."bc_account" ("id");

ALTER TABLE "gw_billing_tgt"."bc_account_contact" ADD CONSTRAINT "fk_bc_4_1" FOREIGN KEY ("account_id") REFERENCES "gw_billing_tgt"."bc_account" ("id");

ALTER TABLE "gw_billing_tgt"."bc_account_contact" ADD CONSTRAINT "fk_bc_4_2" FOREIGN KEY ("contact_id") REFERENCES "gw_billing_tgt"."bc_contact" ("id");

ALTER TABLE "gw_billing_tgt"."bc_payment_plan" ADD CONSTRAINT "fk_bc_6_1" FOREIGN KEY ("account_id") REFERENCES "gw_billing_tgt"."bc_account" ("id");

ALTER TABLE "gw_billing_tgt"."bc_payment_plan" ADD CONSTRAINT "fk_bc_6_2" FOREIGN KEY ("billing_plan_id") REFERENCES "gw_billing_tgt"."bc_billing_plan" ("id");

ALTER TABLE "gw_billing_tgt"."bc_invoice" ADD CONSTRAINT "fk_bc_7_1" FOREIGN KEY ("account_id") REFERENCES "gw_billing_tgt"."bc_account" ("id");

ALTER TABLE "gw_billing_tgt"."bc_invoice" ADD CONSTRAINT "fk_bc_7_2" FOREIGN KEY ("payment_plan_id") REFERENCES "gw_billing_tgt"."bc_payment_plan" ("id");

ALTER TABLE "gw_billing_tgt"."bc_invoice_item" ADD CONSTRAINT "fk_bc_8_1" FOREIGN KEY ("invoice_id") REFERENCES "gw_billing_tgt"."bc_invoice" ("id");

ALTER TABLE "gw_billing_tgt"."bc_charge" ADD CONSTRAINT "fk_bc_10_1" FOREIGN KEY ("account_id") REFERENCES "gw_billing_tgt"."bc_account" ("id");

ALTER TABLE "gw_billing_tgt"."bc_charge" ADD CONSTRAINT "fk_bc_10_2" FOREIGN KEY ("charge_pattern_id") REFERENCES "gw_billing_tgt"."bc_charge_pattern" ("id");

ALTER TABLE "gw_billing_tgt"."bc_policy" ADD CONSTRAINT "fk_bc_11_1" FOREIGN KEY ("account_id") REFERENCES "gw_billing_tgt"."bc_account" ("id");

ALTER TABLE "gw_billing_tgt"."bc_policy_period" ADD CONSTRAINT "fk_bc_12_1" FOREIGN KEY ("policy_id") REFERENCES "gw_billing_tgt"."bc_policy" ("id");

ALTER TABLE "gw_billing_tgt"."bc_payment_instrument" ADD CONSTRAINT "fk_bc_13_1" FOREIGN KEY ("account_id") REFERENCES "gw_billing_tgt"."bc_account" ("id");

ALTER TABLE "gw_billing_tgt"."bc_bank_account" ADD CONSTRAINT "fk_bc_14_1" FOREIGN KEY ("payment_instrument_id") REFERENCES "gw_billing_tgt"."bc_payment_instrument" ("id");

ALTER TABLE "gw_billing_tgt"."bc_payment_card" ADD CONSTRAINT "fk_bc_15_1" FOREIGN KEY ("payment_instrument_id") REFERENCES "gw_billing_tgt"."bc_payment_instrument" ("id");

ALTER TABLE "gw_billing_tgt"."bc_payment" ADD CONSTRAINT "fk_bc_16_1" FOREIGN KEY ("account_id") REFERENCES "gw_billing_tgt"."bc_account" ("id");

ALTER TABLE "gw_billing_tgt"."bc_payment" ADD CONSTRAINT "fk_bc_16_2" FOREIGN KEY ("payment_instrument_id") REFERENCES "gw_billing_tgt"."bc_payment_instrument" ("id");

ALTER TABLE "gw_billing_tgt"."bc_payment_allocation" ADD CONSTRAINT "fk_bc_17_1" FOREIGN KEY ("payment_id") REFERENCES "gw_billing_tgt"."bc_payment" ("id");

ALTER TABLE "gw_billing_tgt"."bc_payment_allocation" ADD CONSTRAINT "fk_bc_17_2" FOREIGN KEY ("invoice_id") REFERENCES "gw_billing_tgt"."bc_invoice" ("id");

ALTER TABLE "gw_billing_tgt"."bc_refund" ADD CONSTRAINT "fk_bc_18_1" FOREIGN KEY ("payment_id") REFERENCES "gw_billing_tgt"."bc_payment" ("id");

ALTER TABLE "gw_billing_tgt"."bc_disbursement" ADD CONSTRAINT "fk_bc_19_1" FOREIGN KEY ("account_id") REFERENCES "gw_billing_tgt"."bc_account" ("id");

ALTER TABLE "gw_billing_tgt"."bc_suspense_payment" ADD CONSTRAINT "fk_bc_20_1" FOREIGN KEY ("payment_id") REFERENCES "gw_billing_tgt"."bc_payment" ("id");

ALTER TABLE "gw_billing_tgt"."bc_delinquency_process" ADD CONSTRAINT "fk_bc_22_1" FOREIGN KEY ("account_id") REFERENCES "gw_billing_tgt"."bc_account" ("id");

ALTER TABLE "gw_billing_tgt"."bc_delinquency_process" ADD CONSTRAINT "fk_bc_22_2" FOREIGN KEY ("delinquency_plan_id") REFERENCES "gw_billing_tgt"."bc_delinquency_plan" ("id");

ALTER TABLE "gw_billing_tgt"."bc_delinquency_event" ADD CONSTRAINT "fk_bc_23_1" FOREIGN KEY ("delinquency_process_id") REFERENCES "gw_billing_tgt"."bc_delinquency_process" ("id");

ALTER TABLE "gw_billing_tgt"."bc_writeoff" ADD CONSTRAINT "fk_bc_24_1" FOREIGN KEY ("account_id") REFERENCES "gw_billing_tgt"."bc_account" ("id");

ALTER TABLE "gw_billing_tgt"."bc_producer" ADD CONSTRAINT "fk_bc_26_1" FOREIGN KEY ("contact_id") REFERENCES "gw_billing_tgt"."bc_contact" ("id");

ALTER TABLE "gw_billing_tgt"."bc_producer" ADD CONSTRAINT "fk_bc_26_2" FOREIGN KEY ("commission_plan_id") REFERENCES "gw_billing_tgt"."bc_commission_plan" ("id");

ALTER TABLE "gw_billing_tgt"."bc_producer_code" ADD CONSTRAINT "fk_bc_27_1" FOREIGN KEY ("producer_id") REFERENCES "gw_billing_tgt"."bc_producer" ("id");

ALTER TABLE "gw_billing_tgt"."bc_commission" ADD CONSTRAINT "fk_bc_28_1" FOREIGN KEY ("producer_id") REFERENCES "gw_billing_tgt"."bc_producer" ("id");

ALTER TABLE "gw_billing_tgt"."bc_commission" ADD CONSTRAINT "fk_bc_28_2" FOREIGN KEY ("policy_period_id") REFERENCES "gw_billing_tgt"."bc_policy_period" ("id");

ALTER TABLE "gw_billing_tgt"."bc_agency_bill_plan" ADD CONSTRAINT "fk_bc_29_1" FOREIGN KEY ("account_id") REFERENCES "gw_billing_tgt"."bc_account" ("id");

ALTER TABLE "gw_billing_tgt"."bc_statement" ADD CONSTRAINT "fk_bc_30_1" FOREIGN KEY ("account_id") REFERENCES "gw_billing_tgt"."bc_account" ("id");

ALTER TABLE "gw_billing_tgt"."bc_statement_item" ADD CONSTRAINT "fk_bc_31_1" FOREIGN KEY ("statement_id") REFERENCES "gw_billing_tgt"."bc_statement" ("id");

ALTER TABLE "gw_billing_tgt"."bc_statement_item" ADD CONSTRAINT "fk_bc_31_2" FOREIGN KEY ("invoice_id") REFERENCES "gw_billing_tgt"."bc_invoice" ("id");

ALTER TABLE "gw_billing_tgt"."bc_credit" ADD CONSTRAINT "fk_bc_32_1" FOREIGN KEY ("account_id") REFERENCES "gw_billing_tgt"."bc_account" ("id");

ALTER TABLE "gw_billing_tgt"."bc_debit" ADD CONSTRAINT "fk_bc_33_1" FOREIGN KEY ("account_id") REFERENCES "gw_billing_tgt"."bc_account" ("id");

ALTER TABLE "gw_billing_tgt"."bc_tax" ADD CONSTRAINT "fk_bc_34_1" FOREIGN KEY ("invoice_item_id") REFERENCES "gw_billing_tgt"."bc_invoice_item" ("id");

ALTER TABLE "gw_billing_tgt"."bc_fee" ADD CONSTRAINT "fk_bc_35_1" FOREIGN KEY ("invoice_id") REFERENCES "gw_billing_tgt"."bc_invoice" ("id");

ALTER TABLE "gw_billing_tgt"."bc_gl_entry" ADD CONSTRAINT "fk_bc_38_1" FOREIGN KEY ("gl_account_id") REFERENCES "gw_billing_tgt"."bc_gl_account" ("id");

ALTER TABLE "gw_billing_tgt"."bc_gl_entry" ADD CONSTRAINT "fk_bc_38_2" FOREIGN KEY ("account_id") REFERENCES "gw_billing_tgt"."bc_account" ("id");

ALTER TABLE "gw_billing_tgt"."bc_batch_item" ADD CONSTRAINT "fk_bc_40_1" FOREIGN KEY ("batch_id") REFERENCES "gw_billing_tgt"."bc_batch" ("id");

ALTER TABLE "gw_billing_tgt"."bc_batch_item" ADD CONSTRAINT "fk_bc_40_2" FOREIGN KEY ("payment_id") REFERENCES "gw_billing_tgt"."bc_payment" ("id");

ALTER TABLE "gw_billing_tgt"."bc_activity" ADD CONSTRAINT "fk_bc_41_1" FOREIGN KEY ("account_id") REFERENCES "gw_billing_tgt"."bc_account" ("id");

ALTER TABLE "gw_billing_tgt"."bc_note" ADD CONSTRAINT "fk_bc_42_1" FOREIGN KEY ("account_id") REFERENCES "gw_billing_tgt"."bc_account" ("id");

ALTER TABLE "gw_billing_tgt"."bc_note" ADD CONSTRAINT "fk_bc_42_2" FOREIGN KEY ("author_contact_id") REFERENCES "gw_billing_tgt"."bc_contact" ("id");

ALTER TABLE "gw_billing_tgt"."bc_document" ADD CONSTRAINT "fk_bc_43_1" FOREIGN KEY ("account_id") REFERENCES "gw_billing_tgt"."bc_account" ("id");

ALTER TABLE "gw_billing_tgt"."bc_contact_phone" ADD CONSTRAINT "fk_bc_44_1" FOREIGN KEY ("contact_id") REFERENCES "gw_billing_tgt"."bc_contact" ("id");

ALTER TABLE "gw_billing_tgt"."bc_contact_email" ADD CONSTRAINT "fk_bc_45_1" FOREIGN KEY ("contact_id") REFERENCES "gw_billing_tgt"."bc_contact" ("id");

ALTER TABLE "gw_billing_tgt"."bc_identity" ADD CONSTRAINT "fk_bc_46_1" FOREIGN KEY ("contact_id") REFERENCES "gw_billing_tgt"."bc_contact" ("id");

ALTER TABLE "gw_billing_tgt"."bc_audit" ADD CONSTRAINT "fk_bc_47_1" FOREIGN KEY ("account_id") REFERENCES "gw_billing_tgt"."bc_account" ("id");

ALTER TABLE "gw_billing_tgt"."bc_message_outbox" ADD CONSTRAINT "fk_bc_48_1" FOREIGN KEY ("account_id") REFERENCES "gw_billing_tgt"."bc_account" ("id");

ALTER TABLE "gw_billing_tgt"."bc_external_reference" ADD CONSTRAINT "fk_bc_49_1" FOREIGN KEY ("account_id") REFERENCES "gw_billing_tgt"."bc_account" ("id");

ALTER TABLE "gw_billing_tgt"."bc_change_event" ADD CONSTRAINT "fk_bc_50_1" FOREIGN KEY ("account_id") REFERENCES "gw_billing_tgt"."bc_account" ("id");
