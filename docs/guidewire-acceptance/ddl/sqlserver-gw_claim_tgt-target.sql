IF SCHEMA_ID(N'gw_claim_tgt') IS NULL EXEC(N'CREATE SCHEMA [gw_claim_tgt]');

CREATE TABLE [gw_claim_tgt].[cc_contact] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [party_key] NVARCHAR(180) UNIQUE,
  [first_name] NVARCHAR(180),
  [last_name] NVARCHAR(180),
  [date_of_birth] DATE,
  [tax_identifier] NVARCHAR(180),
  CONSTRAINT [pk_cc_1] PRIMARY KEY ([id])
);

CREATE TABLE [gw_claim_tgt].[cc_address] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [contact_id] BIGINT,
  [address_line1] NVARCHAR(180),
  [city] NVARCHAR(180),
  [state_code] NVARCHAR(40),
  [postal_code] NVARCHAR(40),
  CONSTRAINT [pk_cc_2] PRIMARY KEY ([id])
);

CREATE TABLE [gw_claim_tgt].[cc_claim] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [insured_contact_id] BIGINT,
  [claim_number] NVARCHAR(180),
  [loss_date] DATE,
  [description_text] NVARCHAR(MAX),
  CONSTRAINT [pk_cc_3] PRIMARY KEY ([id])
);

CREATE TABLE [gw_claim_tgt].[cc_claim_contact] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [claim_id] BIGINT,
  [contact_id] BIGINT,
  [party_key] NVARCHAR(180) UNIQUE,
  [first_name] NVARCHAR(180),
  [last_name] NVARCHAR(180),
  [date_of_birth] DATE,
  [tax_identifier] NVARCHAR(180),
  [claim_number] NVARCHAR(180),
  [loss_date] DATE,
  [description_text] NVARCHAR(MAX),
  CONSTRAINT [pk_cc_4] PRIMARY KEY ([id])
);

CREATE TABLE [gw_claim_tgt].[cc_incident] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [claim_id] BIGINT,
  [claim_number] NVARCHAR(180),
  [loss_date] DATE,
  [description_text] NVARCHAR(MAX),
  CONSTRAINT [pk_cc_5] PRIMARY KEY ([id])
);

CREATE TABLE [gw_claim_tgt].[cc_vehicle_incident] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [incident_id] BIGINT,
  [claim_number] NVARCHAR(180),
  [loss_date] DATE,
  [description_text] NVARCHAR(MAX),
  [vin] NVARCHAR(180) UNIQUE,
  [vehicle_year] INT,
  [make_name] NVARCHAR(180),
  [model_name] NVARCHAR(180),
  CONSTRAINT [pk_cc_6] PRIMARY KEY ([id])
);

CREATE TABLE [gw_claim_tgt].[cc_injury_incident] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [incident_id] BIGINT,
  [injured_contact_id] BIGINT,
  [claim_number] NVARCHAR(180),
  [loss_date] DATE,
  [description_text] NVARCHAR(MAX),
  CONSTRAINT [pk_cc_7] PRIMARY KEY ([id])
);

CREATE TABLE [gw_claim_tgt].[cc_property_incident] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [incident_id] BIGINT,
  [address_id] BIGINT,
  [claim_number] NVARCHAR(180),
  [loss_date] DATE,
  [description_text] NVARCHAR(MAX),
  CONSTRAINT [pk_cc_8] PRIMARY KEY ([id])
);

CREATE TABLE [gw_claim_tgt].[cc_exposure] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [claim_id] BIGINT,
  [incident_id] BIGINT,
  [claim_number] NVARCHAR(180),
  [loss_date] DATE,
  [description_text] NVARCHAR(MAX),
  CONSTRAINT [pk_cc_9] PRIMARY KEY ([id])
);

CREATE TABLE [gw_claim_tgt].[cc_reserve_line] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [exposure_id] BIGINT,
  [amount] DECIMAL(18,2),
  [currency_code] NVARCHAR(40),
  CONSTRAINT [pk_cc_10] PRIMARY KEY ([id]),
  CONSTRAINT [ck_cc_amt_10] CHECK ([amount] IS NULL OR [amount] >= 0)
);

CREATE TABLE [gw_claim_tgt].[cc_reserve] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [reserve_line_id] BIGINT,
  [amount] DECIMAL(18,2),
  [currency_code] NVARCHAR(40),
  CONSTRAINT [pk_cc_11] PRIMARY KEY ([id]),
  CONSTRAINT [ck_cc_amt_11] CHECK ([amount] IS NULL OR [amount] >= 0)
);

CREATE TABLE [gw_claim_tgt].[cc_payment] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [claim_id] BIGINT,
  [reserve_line_id] BIGINT,
  [amount] DECIMAL(18,2),
  [currency_code] NVARCHAR(40),
  CONSTRAINT [pk_cc_12] PRIMARY KEY ([id]),
  CONSTRAINT [ck_cc_amt_12] CHECK ([amount] IS NULL OR [amount] >= 0)
);

CREATE TABLE [gw_claim_tgt].[cc_check] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [claim_id] BIGINT,
  CONSTRAINT [pk_cc_13] PRIMARY KEY ([id])
);

CREATE TABLE [gw_claim_tgt].[cc_check_payee] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [check_id] BIGINT,
  [payee_contact_id] BIGINT,
  [party_key] NVARCHAR(180) UNIQUE,
  [first_name] NVARCHAR(180),
  [last_name] NVARCHAR(180),
  [date_of_birth] DATE,
  [tax_identifier] NVARCHAR(180),
  CONSTRAINT [pk_cc_14] PRIMARY KEY ([id])
);

CREATE TABLE [gw_claim_tgt].[cc_recovery] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [claim_id] BIGINT,
  [amount] DECIMAL(18,2),
  [currency_code] NVARCHAR(40),
  CONSTRAINT [pk_cc_15] PRIMARY KEY ([id]),
  CONSTRAINT [ck_cc_amt_15] CHECK ([amount] IS NULL OR [amount] >= 0)
);

CREATE TABLE [gw_claim_tgt].[cc_recovery_reserve] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [recovery_id] BIGINT,
  [reserve_line_id] BIGINT,
  [amount] DECIMAL(18,2),
  [currency_code] NVARCHAR(40),
  CONSTRAINT [pk_cc_16] PRIMARY KEY ([id]),
  CONSTRAINT [ck_cc_amt_16] CHECK ([amount] IS NULL OR [amount] >= 0)
);

CREATE TABLE [gw_claim_tgt].[cc_service_request] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [claim_id] BIGINT,
  CONSTRAINT [pk_cc_17] PRIMARY KEY ([id])
);

CREATE TABLE [gw_claim_tgt].[cc_vendor] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [contact_id] BIGINT,
  [party_key] NVARCHAR(180) UNIQUE,
  [first_name] NVARCHAR(180),
  [last_name] NVARCHAR(180),
  [date_of_birth] DATE,
  [tax_identifier] NVARCHAR(180),
  CONSTRAINT [pk_cc_18] PRIMARY KEY ([id])
);

CREATE TABLE [gw_claim_tgt].[cc_litigation] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [claim_id] BIGINT,
  [claim_number] NVARCHAR(180),
  [loss_date] DATE,
  [description_text] NVARCHAR(MAX),
  CONSTRAINT [pk_cc_19] PRIMARY KEY ([id])
);

CREATE TABLE [gw_claim_tgt].[cc_matter] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [litigation_id] BIGINT,
  [claim_number] NVARCHAR(180),
  [loss_date] DATE,
  [description_text] NVARCHAR(MAX),
  CONSTRAINT [pk_cc_20] PRIMARY KEY ([id])
);

CREATE TABLE [gw_claim_tgt].[cc_negotiation] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [claim_id] BIGINT,
  CONSTRAINT [pk_cc_21] PRIMARY KEY ([id])
);

CREATE TABLE [gw_claim_tgt].[cc_settlement] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [negotiation_id] BIGINT,
  [amount] DECIMAL(18,2),
  [currency_code] NVARCHAR(40),
  CONSTRAINT [pk_cc_22] PRIMARY KEY ([id]),
  CONSTRAINT [ck_cc_amt_22] CHECK ([amount] IS NULL OR [amount] >= 0)
);

CREATE TABLE [gw_claim_tgt].[cc_subrogation] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [claim_id] BIGINT,
  [claim_number] NVARCHAR(180),
  [loss_date] DATE,
  [description_text] NVARCHAR(MAX),
  CONSTRAINT [pk_cc_23] PRIMARY KEY ([id])
);

CREATE TABLE [gw_claim_tgt].[cc_salvage] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [claim_id] BIGINT,
  [vehicle_incident_id] BIGINT,
  [claim_number] NVARCHAR(180),
  [loss_date] DATE,
  [description_text] NVARCHAR(MAX),
  CONSTRAINT [pk_cc_24] PRIMARY KEY ([id])
);

CREATE TABLE [gw_claim_tgt].[cc_catastrophe] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [claim_number] NVARCHAR(180),
  [loss_date] DATE,
  [description_text] NVARCHAR(MAX),
  CONSTRAINT [pk_cc_25] PRIMARY KEY ([id])
);

CREATE TABLE [gw_claim_tgt].[cc_activity] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [claim_id] BIGINT,
  CONSTRAINT [pk_cc_26] PRIMARY KEY ([id])
);

CREATE TABLE [gw_claim_tgt].[cc_note] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [claim_id] BIGINT,
  [author_contact_id] BIGINT,
  [detail_text] NVARCHAR(MAX),
  [payload_json] NVARCHAR(MAX),
  CONSTRAINT [pk_cc_27] PRIMARY KEY ([id])
);

CREATE TABLE [gw_claim_tgt].[cc_document] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [claim_id] BIGINT,
  [file_name] NVARCHAR(180),
  [mime_type] NVARCHAR(180),
  [document_body] VARBINARY(MAX),
  CONSTRAINT [pk_cc_28] PRIMARY KEY ([id])
);

CREATE TABLE [gw_claim_tgt].[cc_authority_limit] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  CONSTRAINT [pk_cc_29] PRIMARY KEY ([id])
);

CREATE TABLE [gw_claim_tgt].[cc_approval] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [claim_id] BIGINT,
  [authority_limit_id] BIGINT,
  CONSTRAINT [pk_cc_30] PRIMARY KEY ([id])
);

CREATE TABLE [gw_claim_tgt].[cc_assignment] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [claim_id] BIGINT,
  CONSTRAINT [pk_cc_31] PRIMARY KEY ([id])
);

CREATE TABLE [gw_claim_tgt].[cc_group] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [parent_group_id] BIGINT,
  CONSTRAINT [pk_cc_32] PRIMARY KEY ([id])
);

CREATE TABLE [gw_claim_tgt].[cc_user] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [contact_id] BIGINT,
  [group_id] BIGINT,
  [party_key] NVARCHAR(180) UNIQUE,
  [first_name] NVARCHAR(180),
  [last_name] NVARCHAR(180),
  [date_of_birth] DATE,
  [tax_identifier] NVARCHAR(180),
  CONSTRAINT [pk_cc_33] PRIMARY KEY ([id])
);

CREATE TABLE [gw_claim_tgt].[cc_role] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  CONSTRAINT [pk_cc_34] PRIMARY KEY ([id])
);

CREATE TABLE [gw_claim_tgt].[cc_claim_user_role] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [claim_id] BIGINT,
  [user_id] BIGINT,
  [role_id] BIGINT,
  [party_key] NVARCHAR(180) UNIQUE,
  [first_name] NVARCHAR(180),
  [last_name] NVARCHAR(180),
  [date_of_birth] DATE,
  [tax_identifier] NVARCHAR(180),
  [claim_number] NVARCHAR(180),
  [loss_date] DATE,
  [description_text] NVARCHAR(MAX),
  CONSTRAINT [pk_cc_35] PRIMARY KEY ([id])
);

CREATE TABLE [gw_claim_tgt].[cc_policy_snapshot] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [claim_id] BIGINT,
  [policy_number] NVARCHAR(180),
  [effective_date] DATE,
  [expiration_date] DATE,
  CONSTRAINT [pk_cc_36] PRIMARY KEY ([id]),
  CONSTRAINT [ck_cc_dates_36] CHECK ([expiration_date] IS NULL OR [effective_date] IS NULL OR [expiration_date] >= [effective_date])
);

CREATE TABLE [gw_claim_tgt].[cc_policy_vehicle] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [policy_snapshot_id] BIGINT,
  [policy_number] NVARCHAR(180),
  [effective_date] DATE,
  [expiration_date] DATE,
  [vin] NVARCHAR(180) UNIQUE,
  [vehicle_year] INT,
  [make_name] NVARCHAR(180),
  [model_name] NVARCHAR(180),
  CONSTRAINT [pk_cc_37] PRIMARY KEY ([id]),
  CONSTRAINT [ck_cc_dates_37] CHECK ([expiration_date] IS NULL OR [effective_date] IS NULL OR [expiration_date] >= [effective_date])
);

CREATE TABLE [gw_claim_tgt].[cc_policy_driver] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [policy_snapshot_id] BIGINT,
  [contact_id] BIGINT,
  [party_key] NVARCHAR(180) UNIQUE,
  [first_name] NVARCHAR(180),
  [last_name] NVARCHAR(180),
  [date_of_birth] DATE,
  [tax_identifier] NVARCHAR(180),
  [policy_number] NVARCHAR(180),
  [effective_date] DATE,
  [expiration_date] DATE,
  CONSTRAINT [pk_cc_38] PRIMARY KEY ([id]),
  CONSTRAINT [ck_cc_dates_38] CHECK ([expiration_date] IS NULL OR [effective_date] IS NULL OR [expiration_date] >= [effective_date])
);

CREATE TABLE [gw_claim_tgt].[cc_policy_location] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [policy_snapshot_id] BIGINT,
  [address_id] BIGINT,
  [address_line1] NVARCHAR(180),
  [city] NVARCHAR(180),
  [state_code] NVARCHAR(40),
  [postal_code] NVARCHAR(40),
  [policy_number] NVARCHAR(180),
  [effective_date] DATE,
  [expiration_date] DATE,
  CONSTRAINT [pk_cc_39] PRIMARY KEY ([id]),
  CONSTRAINT [ck_cc_dates_39] CHECK ([expiration_date] IS NULL OR [effective_date] IS NULL OR [expiration_date] >= [effective_date])
);

CREATE TABLE [gw_claim_tgt].[cc_coverage_snapshot] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [policy_snapshot_id] BIGINT,
  CONSTRAINT [pk_cc_40] PRIMARY KEY ([id])
);

CREATE TABLE [gw_claim_tgt].[cc_deductible] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [coverage_snapshot_id] BIGINT,
  [factor_percent] DECIMAL(5,2),
  CONSTRAINT [pk_cc_41] PRIMARY KEY ([id]),
  CONSTRAINT [ck_cc_pct_41] CHECK ([factor_percent] IS NULL OR ([factor_percent] >= 0 AND [factor_percent] <= 100))
);

CREATE TABLE [gw_claim_tgt].[cc_fault_rating] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [claim_id] BIGINT,
  [contact_id] BIGINT,
  [factor_percent] DECIMAL(5,2),
  CONSTRAINT [pk_cc_42] PRIMARY KEY ([id]),
  CONSTRAINT [ck_cc_pct_42] CHECK ([factor_percent] IS NULL OR ([factor_percent] >= 0 AND [factor_percent] <= 100))
);

CREATE TABLE [gw_claim_tgt].[cc_medical_treatment] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [injury_incident_id] BIGINT,
  CONSTRAINT [pk_cc_43] PRIMARY KEY ([id])
);

CREATE TABLE [gw_claim_tgt].[cc_contact_phone] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [contact_id] BIGINT,
  [party_key] NVARCHAR(180) UNIQUE,
  [first_name] NVARCHAR(180),
  [last_name] NVARCHAR(180),
  [date_of_birth] DATE,
  [tax_identifier] NVARCHAR(180),
  [phone_number] NVARCHAR(180),
  CONSTRAINT [pk_cc_44] PRIMARY KEY ([id])
);

CREATE TABLE [gw_claim_tgt].[cc_contact_email] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [contact_id] BIGINT,
  [party_key] NVARCHAR(180) UNIQUE,
  [first_name] NVARCHAR(180),
  [last_name] NVARCHAR(180),
  [date_of_birth] DATE,
  [tax_identifier] NVARCHAR(180),
  [email_address] NVARCHAR(180),
  CONSTRAINT [pk_cc_45] PRIMARY KEY ([id])
);

CREATE TABLE [gw_claim_tgt].[cc_message_outbox] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [claim_id] BIGINT,
  [detail_text] NVARCHAR(MAX),
  [payload_json] NVARCHAR(MAX),
  CONSTRAINT [pk_cc_46] PRIMARY KEY ([id])
);

CREATE TABLE [gw_claim_tgt].[cc_external_status] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [claim_id] BIGINT,
  CONSTRAINT [pk_cc_47] PRIMARY KEY ([id])
);

CREATE TABLE [gw_claim_tgt].[cc_financial_transaction] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [claim_id] BIGINT,
  [amount] DECIMAL(18,2),
  [currency_code] NVARCHAR(40),
  CONSTRAINT [pk_cc_48] PRIMARY KEY ([id]),
  CONSTRAINT [ck_cc_amt_48] CHECK ([amount] IS NULL OR [amount] >= 0)
);

CREATE TABLE [gw_claim_tgt].[cc_history] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [claim_id] BIGINT,
  [detail_text] NVARCHAR(MAX),
  [payload_json] NVARCHAR(MAX),
  CONSTRAINT [pk_cc_49] PRIMARY KEY ([id])
);

CREATE TABLE [gw_claim_tgt].[cc_change_event] (
  [id] BIGINT NOT NULL,
  [public_id] NVARCHAR(180) NOT NULL UNIQUE,
  [tenant_id] NVARCHAR(40),
  [status_code] NVARCHAR(40),
  [version_no] INT,
  [retired] BIT,
  [created_at] DATETIMEOFFSET,
  [updated_at] DATETIMEOFFSET,
  [claim_id] BIGINT,
  [detail_text] NVARCHAR(MAX),
  [payload_json] NVARCHAR(MAX),
  CONSTRAINT [pk_cc_50] PRIMARY KEY ([id])
);

ALTER TABLE [gw_claim_tgt].[cc_address] ADD CONSTRAINT [fk_cc_2_1] FOREIGN KEY ([contact_id]) REFERENCES [gw_claim_tgt].[cc_contact] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_claim] ADD CONSTRAINT [fk_cc_3_1] FOREIGN KEY ([insured_contact_id]) REFERENCES [gw_claim_tgt].[cc_contact] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_claim_contact] ADD CONSTRAINT [fk_cc_4_1] FOREIGN KEY ([claim_id]) REFERENCES [gw_claim_tgt].[cc_claim] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_claim_contact] ADD CONSTRAINT [fk_cc_4_2] FOREIGN KEY ([contact_id]) REFERENCES [gw_claim_tgt].[cc_contact] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_incident] ADD CONSTRAINT [fk_cc_5_1] FOREIGN KEY ([claim_id]) REFERENCES [gw_claim_tgt].[cc_claim] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_vehicle_incident] ADD CONSTRAINT [fk_cc_6_1] FOREIGN KEY ([incident_id]) REFERENCES [gw_claim_tgt].[cc_incident] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_injury_incident] ADD CONSTRAINT [fk_cc_7_1] FOREIGN KEY ([incident_id]) REFERENCES [gw_claim_tgt].[cc_incident] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_injury_incident] ADD CONSTRAINT [fk_cc_7_2] FOREIGN KEY ([injured_contact_id]) REFERENCES [gw_claim_tgt].[cc_contact] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_property_incident] ADD CONSTRAINT [fk_cc_8_1] FOREIGN KEY ([incident_id]) REFERENCES [gw_claim_tgt].[cc_incident] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_property_incident] ADD CONSTRAINT [fk_cc_8_2] FOREIGN KEY ([address_id]) REFERENCES [gw_claim_tgt].[cc_address] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_exposure] ADD CONSTRAINT [fk_cc_9_1] FOREIGN KEY ([claim_id]) REFERENCES [gw_claim_tgt].[cc_claim] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_exposure] ADD CONSTRAINT [fk_cc_9_2] FOREIGN KEY ([incident_id]) REFERENCES [gw_claim_tgt].[cc_incident] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_reserve_line] ADD CONSTRAINT [fk_cc_10_1] FOREIGN KEY ([exposure_id]) REFERENCES [gw_claim_tgt].[cc_exposure] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_reserve] ADD CONSTRAINT [fk_cc_11_1] FOREIGN KEY ([reserve_line_id]) REFERENCES [gw_claim_tgt].[cc_reserve_line] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_payment] ADD CONSTRAINT [fk_cc_12_1] FOREIGN KEY ([claim_id]) REFERENCES [gw_claim_tgt].[cc_claim] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_payment] ADD CONSTRAINT [fk_cc_12_2] FOREIGN KEY ([reserve_line_id]) REFERENCES [gw_claim_tgt].[cc_reserve_line] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_check] ADD CONSTRAINT [fk_cc_13_1] FOREIGN KEY ([claim_id]) REFERENCES [gw_claim_tgt].[cc_claim] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_check_payee] ADD CONSTRAINT [fk_cc_14_1] FOREIGN KEY ([check_id]) REFERENCES [gw_claim_tgt].[cc_check] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_check_payee] ADD CONSTRAINT [fk_cc_14_2] FOREIGN KEY ([payee_contact_id]) REFERENCES [gw_claim_tgt].[cc_contact] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_recovery] ADD CONSTRAINT [fk_cc_15_1] FOREIGN KEY ([claim_id]) REFERENCES [gw_claim_tgt].[cc_claim] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_recovery_reserve] ADD CONSTRAINT [fk_cc_16_1] FOREIGN KEY ([recovery_id]) REFERENCES [gw_claim_tgt].[cc_recovery] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_recovery_reserve] ADD CONSTRAINT [fk_cc_16_2] FOREIGN KEY ([reserve_line_id]) REFERENCES [gw_claim_tgt].[cc_reserve_line] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_service_request] ADD CONSTRAINT [fk_cc_17_1] FOREIGN KEY ([claim_id]) REFERENCES [gw_claim_tgt].[cc_claim] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_vendor] ADD CONSTRAINT [fk_cc_18_1] FOREIGN KEY ([contact_id]) REFERENCES [gw_claim_tgt].[cc_contact] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_litigation] ADD CONSTRAINT [fk_cc_19_1] FOREIGN KEY ([claim_id]) REFERENCES [gw_claim_tgt].[cc_claim] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_matter] ADD CONSTRAINT [fk_cc_20_1] FOREIGN KEY ([litigation_id]) REFERENCES [gw_claim_tgt].[cc_litigation] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_negotiation] ADD CONSTRAINT [fk_cc_21_1] FOREIGN KEY ([claim_id]) REFERENCES [gw_claim_tgt].[cc_claim] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_settlement] ADD CONSTRAINT [fk_cc_22_1] FOREIGN KEY ([negotiation_id]) REFERENCES [gw_claim_tgt].[cc_negotiation] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_subrogation] ADD CONSTRAINT [fk_cc_23_1] FOREIGN KEY ([claim_id]) REFERENCES [gw_claim_tgt].[cc_claim] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_salvage] ADD CONSTRAINT [fk_cc_24_1] FOREIGN KEY ([claim_id]) REFERENCES [gw_claim_tgt].[cc_claim] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_salvage] ADD CONSTRAINT [fk_cc_24_2] FOREIGN KEY ([vehicle_incident_id]) REFERENCES [gw_claim_tgt].[cc_vehicle_incident] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_activity] ADD CONSTRAINT [fk_cc_26_1] FOREIGN KEY ([claim_id]) REFERENCES [gw_claim_tgt].[cc_claim] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_note] ADD CONSTRAINT [fk_cc_27_1] FOREIGN KEY ([claim_id]) REFERENCES [gw_claim_tgt].[cc_claim] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_note] ADD CONSTRAINT [fk_cc_27_2] FOREIGN KEY ([author_contact_id]) REFERENCES [gw_claim_tgt].[cc_contact] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_document] ADD CONSTRAINT [fk_cc_28_1] FOREIGN KEY ([claim_id]) REFERENCES [gw_claim_tgt].[cc_claim] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_approval] ADD CONSTRAINT [fk_cc_30_1] FOREIGN KEY ([claim_id]) REFERENCES [gw_claim_tgt].[cc_claim] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_approval] ADD CONSTRAINT [fk_cc_30_2] FOREIGN KEY ([authority_limit_id]) REFERENCES [gw_claim_tgt].[cc_authority_limit] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_assignment] ADD CONSTRAINT [fk_cc_31_1] FOREIGN KEY ([claim_id]) REFERENCES [gw_claim_tgt].[cc_claim] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_group] ADD CONSTRAINT [fk_cc_32_1] FOREIGN KEY ([parent_group_id]) REFERENCES [gw_claim_tgt].[cc_group] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_user] ADD CONSTRAINT [fk_cc_33_1] FOREIGN KEY ([contact_id]) REFERENCES [gw_claim_tgt].[cc_contact] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_user] ADD CONSTRAINT [fk_cc_33_2] FOREIGN KEY ([group_id]) REFERENCES [gw_claim_tgt].[cc_group] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_claim_user_role] ADD CONSTRAINT [fk_cc_35_1] FOREIGN KEY ([claim_id]) REFERENCES [gw_claim_tgt].[cc_claim] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_claim_user_role] ADD CONSTRAINT [fk_cc_35_2] FOREIGN KEY ([user_id]) REFERENCES [gw_claim_tgt].[cc_user] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_claim_user_role] ADD CONSTRAINT [fk_cc_35_3] FOREIGN KEY ([role_id]) REFERENCES [gw_claim_tgt].[cc_role] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_policy_snapshot] ADD CONSTRAINT [fk_cc_36_1] FOREIGN KEY ([claim_id]) REFERENCES [gw_claim_tgt].[cc_claim] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_policy_vehicle] ADD CONSTRAINT [fk_cc_37_1] FOREIGN KEY ([policy_snapshot_id]) REFERENCES [gw_claim_tgt].[cc_policy_snapshot] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_policy_driver] ADD CONSTRAINT [fk_cc_38_1] FOREIGN KEY ([policy_snapshot_id]) REFERENCES [gw_claim_tgt].[cc_policy_snapshot] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_policy_driver] ADD CONSTRAINT [fk_cc_38_2] FOREIGN KEY ([contact_id]) REFERENCES [gw_claim_tgt].[cc_contact] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_policy_location] ADD CONSTRAINT [fk_cc_39_1] FOREIGN KEY ([policy_snapshot_id]) REFERENCES [gw_claim_tgt].[cc_policy_snapshot] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_policy_location] ADD CONSTRAINT [fk_cc_39_2] FOREIGN KEY ([address_id]) REFERENCES [gw_claim_tgt].[cc_address] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_coverage_snapshot] ADD CONSTRAINT [fk_cc_40_1] FOREIGN KEY ([policy_snapshot_id]) REFERENCES [gw_claim_tgt].[cc_policy_snapshot] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_deductible] ADD CONSTRAINT [fk_cc_41_1] FOREIGN KEY ([coverage_snapshot_id]) REFERENCES [gw_claim_tgt].[cc_coverage_snapshot] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_fault_rating] ADD CONSTRAINT [fk_cc_42_1] FOREIGN KEY ([claim_id]) REFERENCES [gw_claim_tgt].[cc_claim] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_fault_rating] ADD CONSTRAINT [fk_cc_42_2] FOREIGN KEY ([contact_id]) REFERENCES [gw_claim_tgt].[cc_contact] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_medical_treatment] ADD CONSTRAINT [fk_cc_43_1] FOREIGN KEY ([injury_incident_id]) REFERENCES [gw_claim_tgt].[cc_injury_incident] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_contact_phone] ADD CONSTRAINT [fk_cc_44_1] FOREIGN KEY ([contact_id]) REFERENCES [gw_claim_tgt].[cc_contact] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_contact_email] ADD CONSTRAINT [fk_cc_45_1] FOREIGN KEY ([contact_id]) REFERENCES [gw_claim_tgt].[cc_contact] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_message_outbox] ADD CONSTRAINT [fk_cc_46_1] FOREIGN KEY ([claim_id]) REFERENCES [gw_claim_tgt].[cc_claim] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_external_status] ADD CONSTRAINT [fk_cc_47_1] FOREIGN KEY ([claim_id]) REFERENCES [gw_claim_tgt].[cc_claim] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_financial_transaction] ADD CONSTRAINT [fk_cc_48_1] FOREIGN KEY ([claim_id]) REFERENCES [gw_claim_tgt].[cc_claim] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_history] ADD CONSTRAINT [fk_cc_49_1] FOREIGN KEY ([claim_id]) REFERENCES [gw_claim_tgt].[cc_claim] ([id]);

ALTER TABLE [gw_claim_tgt].[cc_change_event] ADD CONSTRAINT [fk_cc_50_1] FOREIGN KEY ([claim_id]) REFERENCES [gw_claim_tgt].[cc_claim] ([id]);
