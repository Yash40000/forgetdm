import fs from "node:fs";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "../../docs/guidewire-acceptance/ddl");
fs.mkdirSync(root, { recursive: true });

const policy = [
  ["pc_contact", [["pc_contact", "parent_contact_id"]]], ["pc_address", [["pc_contact", "contact_id"]]],
  ["pc_account", [["pc_contact", "primary_contact_id"]]], ["pc_account_contact", [["pc_account", "account_id"], ["pc_contact", "contact_id"]]],
  ["pc_policy", [["pc_account", "account_id"]]], ["pc_policy_period", [["pc_policy", "policy_id"]]],
  ["pc_policy_line", [["pc_policy_period", "policy_period_id"]]], ["pc_personal_auto_line", [["pc_policy_line", "policy_line_id"]]],
  ["pc_vehicle", [["pc_personal_auto_line", "auto_line_id"]]], ["pc_driver", [["pc_personal_auto_line", "auto_line_id"], ["pc_contact", "contact_id"]]],
  ["pc_vehicle_driver", [["pc_vehicle", "vehicle_id"], ["pc_driver", "driver_id"]]], ["pc_coverage_pattern", []],
  ["pc_coverage", [["pc_policy_line", "policy_line_id"], ["pc_coverage_pattern", "pattern_id"]]], ["pc_coverage_term", [["pc_coverage", "coverage_id"]]],
  ["pc_location", [["pc_account", "account_id"], ["pc_address", "address_id"]]], ["pc_building", [["pc_location", "location_id"]]],
  ["pc_homeowners_line", [["pc_policy_line", "policy_line_id"]]], ["pc_dwelling", [["pc_homeowners_line", "home_line_id"], ["pc_building", "building_id"]]],
  ["pc_policy_contact_role", [["pc_policy_period", "policy_period_id"], ["pc_contact", "contact_id"]]],
  ["pc_underwriting_issue", [["pc_policy_period", "policy_period_id"]]], ["pc_underwriting_referral", [["pc_underwriting_issue", "issue_id"]]],
  ["pc_quote", [["pc_policy_period", "policy_period_id"]]], ["pc_quote_cost", [["pc_quote", "quote_id"]]],
  ["pc_premium_transaction", [["pc_policy_period", "policy_period_id"]]], ["pc_rate_book", []],
  ["pc_rate_table", [["pc_rate_book", "rate_book_id"]]], ["pc_rate_factor", [["pc_rate_table", "rate_table_id"]]],
  ["pc_job", [["pc_account", "account_id"], ["pc_policy_period", "policy_period_id"]]], ["pc_submission", [["pc_job", "job_id"]]],
  ["pc_renewal", [["pc_job", "job_id"]]], ["pc_cancellation", [["pc_job", "job_id"]]], ["pc_reinstatement", [["pc_job", "job_id"]]],
  ["pc_document", [["pc_policy_period", "policy_period_id"]]], ["pc_note", [["pc_policy_period", "policy_period_id"], ["pc_contact", "author_contact_id"]]],
  ["pc_activity", [["pc_policy_period", "policy_period_id"]]], ["pc_workflow", [["pc_job", "job_id"]]],
  ["pc_workflow_step", [["pc_workflow", "workflow_id"]]], ["pc_question_set", []], ["pc_question", [["pc_question_set", "question_set_id"]]],
  ["pc_answer", [["pc_question", "question_id"], ["pc_policy_period", "policy_period_id"]]], ["pc_product", []],
  ["pc_product_line", [["pc_product", "product_id"]]], ["pc_form_pattern", [["pc_product", "product_id"]]],
  ["pc_policy_form", [["pc_policy_period", "policy_period_id"], ["pc_form_pattern", "form_pattern_id"]]], ["pc_producer", [["pc_contact", "contact_id"]]],
  ["pc_producer_code", [["pc_producer", "producer_id"]]], ["pc_territory", []], ["pc_audit", [["pc_policy_period", "policy_period_id"]]],
  ["pc_message_outbox", [["pc_policy_period", "policy_period_id"]]], ["pc_change_event", [["pc_policy_period", "policy_period_id"]]],
];

const claims = [
  ["cc_contact", []], ["cc_address", [["cc_contact", "contact_id"]]], ["cc_claim", [["cc_contact", "insured_contact_id"]]],
  ["cc_claim_contact", [["cc_claim", "claim_id"], ["cc_contact", "contact_id"]]], ["cc_incident", [["cc_claim", "claim_id"]]],
  ["cc_vehicle_incident", [["cc_incident", "incident_id"]]], ["cc_injury_incident", [["cc_incident", "incident_id"], ["cc_contact", "injured_contact_id"]]],
  ["cc_property_incident", [["cc_incident", "incident_id"], ["cc_address", "address_id"]]], ["cc_exposure", [["cc_claim", "claim_id"], ["cc_incident", "incident_id"]]],
  ["cc_reserve_line", [["cc_exposure", "exposure_id"]]], ["cc_reserve", [["cc_reserve_line", "reserve_line_id"]]],
  ["cc_payment", [["cc_claim", "claim_id"], ["cc_reserve_line", "reserve_line_id"]]], ["cc_check", [["cc_claim", "claim_id"]]],
  ["cc_check_payee", [["cc_check", "check_id"], ["cc_contact", "payee_contact_id"]]], ["cc_recovery", [["cc_claim", "claim_id"]]],
  ["cc_recovery_reserve", [["cc_recovery", "recovery_id"], ["cc_reserve_line", "reserve_line_id"]]], ["cc_service_request", [["cc_claim", "claim_id"]]],
  ["cc_vendor", [["cc_contact", "contact_id"]]], ["cc_litigation", [["cc_claim", "claim_id"]]], ["cc_matter", [["cc_litigation", "litigation_id"]]],
  ["cc_negotiation", [["cc_claim", "claim_id"]]], ["cc_settlement", [["cc_negotiation", "negotiation_id"]]],
  ["cc_subrogation", [["cc_claim", "claim_id"]]], ["cc_salvage", [["cc_claim", "claim_id"], ["cc_vehicle_incident", "vehicle_incident_id"]]],
  ["cc_catastrophe", []], ["cc_activity", [["cc_claim", "claim_id"]]], ["cc_note", [["cc_claim", "claim_id"], ["cc_contact", "author_contact_id"]]],
  ["cc_document", [["cc_claim", "claim_id"]]], ["cc_authority_limit", []], ["cc_approval", [["cc_claim", "claim_id"], ["cc_authority_limit", "authority_limit_id"]]],
  ["cc_assignment", [["cc_claim", "claim_id"]]], ["cc_group", [["cc_group", "parent_group_id"]]], ["cc_user", [["cc_contact", "contact_id"], ["cc_group", "group_id"]]],
  ["cc_role", []], ["cc_claim_user_role", [["cc_claim", "claim_id"], ["cc_user", "user_id"], ["cc_role", "role_id"]]],
  ["cc_policy_snapshot", [["cc_claim", "claim_id"]]], ["cc_policy_vehicle", [["cc_policy_snapshot", "policy_snapshot_id"]]],
  ["cc_policy_driver", [["cc_policy_snapshot", "policy_snapshot_id"], ["cc_contact", "contact_id"]]], ["cc_policy_location", [["cc_policy_snapshot", "policy_snapshot_id"], ["cc_address", "address_id"]]],
  ["cc_coverage_snapshot", [["cc_policy_snapshot", "policy_snapshot_id"]]], ["cc_deductible", [["cc_coverage_snapshot", "coverage_snapshot_id"]]],
  ["cc_fault_rating", [["cc_claim", "claim_id"], ["cc_contact", "contact_id"]]], ["cc_medical_treatment", [["cc_injury_incident", "injury_incident_id"]]],
  ["cc_contact_phone", [["cc_contact", "contact_id"]]], ["cc_contact_email", [["cc_contact", "contact_id"]]],
  ["cc_message_outbox", [["cc_claim", "claim_id"]]], ["cc_external_status", [["cc_claim", "claim_id"]]],
  ["cc_financial_transaction", [["cc_claim", "claim_id"]]], ["cc_history", [["cc_claim", "claim_id"]]], ["cc_change_event", [["cc_claim", "claim_id"]]],
];

const billing = [
  ["bc_contact", []], ["bc_address", [["bc_contact", "contact_id"]]], ["bc_account", [["bc_contact", "primary_contact_id"], ["bc_account", "parent_account_id"]]],
  ["bc_account_contact", [["bc_account", "account_id"], ["bc_contact", "contact_id"]]], ["bc_billing_plan", []],
  ["bc_payment_plan", [["bc_account", "account_id"], ["bc_billing_plan", "billing_plan_id"]]], ["bc_invoice", [["bc_account", "account_id"], ["bc_payment_plan", "payment_plan_id"]]],
  ["bc_invoice_item", [["bc_invoice", "invoice_id"]]], ["bc_charge_pattern", []], ["bc_charge", [["bc_account", "account_id"], ["bc_charge_pattern", "charge_pattern_id"]]],
  ["bc_policy", [["bc_account", "account_id"]]], ["bc_policy_period", [["bc_policy", "policy_id"]]], ["bc_payment_instrument", [["bc_account", "account_id"]]],
  ["bc_bank_account", [["bc_payment_instrument", "payment_instrument_id"]]], ["bc_payment_card", [["bc_payment_instrument", "payment_instrument_id"]]],
  ["bc_payment", [["bc_account", "account_id"], ["bc_payment_instrument", "payment_instrument_id"]]],
  ["bc_payment_allocation", [["bc_payment", "payment_id"], ["bc_invoice", "invoice_id"]]], ["bc_refund", [["bc_payment", "payment_id"]]],
  ["bc_disbursement", [["bc_account", "account_id"]]], ["bc_suspense_payment", [["bc_payment", "payment_id"]]], ["bc_delinquency_plan", []],
  ["bc_delinquency_process", [["bc_account", "account_id"], ["bc_delinquency_plan", "delinquency_plan_id"]]],
  ["bc_delinquency_event", [["bc_delinquency_process", "delinquency_process_id"]]], ["bc_writeoff", [["bc_account", "account_id"]]],
  ["bc_commission_plan", []], ["bc_producer", [["bc_contact", "contact_id"], ["bc_commission_plan", "commission_plan_id"]]],
  ["bc_producer_code", [["bc_producer", "producer_id"]]], ["bc_commission", [["bc_producer", "producer_id"], ["bc_policy_period", "policy_period_id"]]],
  ["bc_agency_bill_plan", [["bc_account", "account_id"]]], ["bc_statement", [["bc_account", "account_id"]]],
  ["bc_statement_item", [["bc_statement", "statement_id"], ["bc_invoice", "invoice_id"]]], ["bc_credit", [["bc_account", "account_id"]]],
  ["bc_debit", [["bc_account", "account_id"]]], ["bc_tax", [["bc_invoice_item", "invoice_item_id"]]], ["bc_fee", [["bc_invoice", "invoice_id"]]],
  ["bc_currency_rate", []], ["bc_gl_account", []], ["bc_gl_entry", [["bc_gl_account", "gl_account_id"], ["bc_account", "account_id"]]],
  ["bc_batch", []], ["bc_batch_item", [["bc_batch", "batch_id"], ["bc_payment", "payment_id"]]], ["bc_activity", [["bc_account", "account_id"]]],
  ["bc_note", [["bc_account", "account_id"], ["bc_contact", "author_contact_id"]]], ["bc_document", [["bc_account", "account_id"]]],
  ["bc_contact_phone", [["bc_contact", "contact_id"]]], ["bc_contact_email", [["bc_contact", "contact_id"]]], ["bc_identity", [["bc_contact", "contact_id"]]],
  ["bc_audit", [["bc_account", "account_id"]]], ["bc_message_outbox", [["bc_account", "account_id"]]],
  ["bc_external_reference", [["bc_account", "account_id"]]], ["bc_change_event", [["bc_account", "account_id"]]],
];

for (const [name, tables] of [["policy", policy], ["claims", claims], ["billing", billing]]) {
  if (tables.length !== 50) throw new Error(`${name} must have 50 tables, found ${tables.length}`);
}

function domainColumns(name) {
  const cols = [];
  if (/contact|producer|driver|user|vendor|payee/.test(name)) cols.push(["party_key", "string", true], ["first_name", "string"], ["last_name", "string"], ["date_of_birth", "date"], ["tax_identifier", "string"]);
  if (/address|location|building|dwelling/.test(name)) cols.push(["address_line1", "string"], ["city", "string"], ["state_code", "short"], ["postal_code", "short"]);
  if (/policy|period|quote|submission|renewal|cancellation|reinstatement|product/.test(name)) cols.push(["policy_number", "string"], ["effective_date", "date"], ["expiration_date", "date"]);
  if (/claim|incident|exposure|litigation|matter|subrogation|salvage|catastrophe/.test(name)) cols.push(["claim_number", "string"], ["loss_date", "date"], ["description_text", "text"]);
  if (/payment|invoice|charge|premium|reserve|recovery|settlement|commission|credit|debit|tax|fee|cost|financial|gl_entry|writeoff|disbursement|refund/.test(name)) cols.push(["amount", "money"], ["currency_code", "short"]);
  if (/card/.test(name)) cols.push(["card_number", "string", true], ["card_expiry", "date"], ["card_brand", "short"]);
  if (/bank_account/.test(name)) cols.push(["bank_account_number", "string", true], ["routing_number", "string"]);
  if (/email/.test(name)) cols.push(["email_address", "string"]);
  if (/phone/.test(name)) cols.push(["phone_number", "string"]);
  if (/vehicle/.test(name)) cols.push(["vin", "string", true], ["vehicle_year", "integer"], ["make_name", "string"], ["model_name", "string"]);
  if (/document/.test(name)) cols.push(["file_name", "string"], ["mime_type", "string"], ["document_body", "blob"]);
  if (/note|message|event|audit|history|workflow/.test(name)) cols.push(["detail_text", "text"], ["payload_json", "json"]);
  if (/rate_factor|fault_rating|deductible|coverage_term/.test(name)) cols.push(["factor_percent", "percent"]);
  return dedupe(cols);
}

function dedupe(cols) {
  const seen = new Set();
  return cols.filter(([n]) => !seen.has(n) && seen.add(n));
}

const types = {
  postgres: { string: "VARCHAR(180)", short: "VARCHAR(40)", integer: "INTEGER", money: "NUMERIC(18,2)", percent: "NUMERIC(5,2)", date: "DATE", text: "TEXT", blob: "BYTEA", json: "JSONB", bool: "BOOLEAN", ts: "TIMESTAMP WITH TIME ZONE" },
  sqlserver: { string: "NVARCHAR(180)", short: "NVARCHAR(40)", integer: "INT", money: "DECIMAL(18,2)", percent: "DECIMAL(5,2)", date: "DATE", text: "NVARCHAR(MAX)", blob: "VARBINARY(MAX)", json: "NVARCHAR(MAX)", bool: "BIT", ts: "DATETIMEOFFSET" },
  oracle: { string: "VARCHAR2(180)", short: "VARCHAR2(40)", integer: "NUMBER(10)", money: "NUMBER(18,2)", percent: "NUMBER(5,2)", date: "DATE", text: "CLOB", blob: "BLOB", json: "CLOB", bool: "NUMBER(1)", ts: "TIMESTAMP WITH TIME ZONE" },
};

function q(engine, value) {
  if (engine === "postgres") return `"${value}"`;
  if (engine === "sqlserver") return `[${value}]`;
  return `"${value.toUpperCase()}"`;
}

function qualified(engine, schema, table) {
  return `${q(engine, schema)}.${q(engine, table)}`;
}

function buildDdl(engine, schema, tables, rename = value => value) {
  const t = types[engine];
  const idType = engine === "oracle" ? "NUMBER(19)" : "BIGINT";
  const lines = [];
  if (engine === "postgres") lines.push(`CREATE SCHEMA IF NOT EXISTS ${q(engine, schema)};`);
  if (engine === "sqlserver") lines.push(`IF SCHEMA_ID(N'${schema}') IS NULL EXEC(N'CREATE SCHEMA ${q(engine, schema)}');`);
  tables.forEach(([sourceName, refs], i) => {
    const name = rename(sourceName);
    const constraintScope = name.split("_")[0];
    const columns = [
      ["id", idType, true], ["public_id", t.string, true], ["tenant_id", t.short], ["status_code", t.short],
      ["version_no", t.integer], ["retired", t.bool], ["created_at", t.ts], ["updated_at", t.ts],
      ...refs.map(([, fk]) => [fk, idType]),
      ...domainColumns(sourceName).map(([n, kind, unique]) => [n, t[kind], unique]),
    ];
    const rendered = dedupe(columns).map(([col, type, unique]) => `  ${q(engine, col)} ${type}${col === "id" || col === "public_id" ? " NOT NULL" : ""}${unique && col !== "id" ? " UNIQUE" : ""}`);
    rendered.push(`  CONSTRAINT ${q(engine, `pk_${constraintScope}_${i + 1}`)} PRIMARY KEY (${q(engine, "id")})`);
    if (columns.some(([c]) => c === "amount")) rendered.push(`  CONSTRAINT ${q(engine, `ck_${constraintScope}_amt_${i + 1}`)} CHECK (${q(engine, "amount")} IS NULL OR ${q(engine, "amount")} >= 0)`);
    if (columns.some(([c]) => c === "factor_percent")) rendered.push(`  CONSTRAINT ${q(engine, `ck_${constraintScope}_pct_${i + 1}`)} CHECK (${q(engine, "factor_percent")} IS NULL OR (${q(engine, "factor_percent")} >= 0 AND ${q(engine, "factor_percent")} <= 100))`);
    if (columns.some(([c]) => c === "expiration_date")) rendered.push(`  CONSTRAINT ${q(engine, `ck_${constraintScope}_dates_${i + 1}`)} CHECK (${q(engine, "expiration_date")} IS NULL OR ${q(engine, "effective_date")} IS NULL OR ${q(engine, "expiration_date")} >= ${q(engine, "effective_date")})`);
    lines.push(`CREATE TABLE ${qualified(engine, schema, name)} (\n${rendered.join(",\n")}\n);`);
  });
  tables.forEach(([sourceName, refs], i) => refs.forEach(([parent, fk], j) => {
    const name = rename(sourceName);
    const constraintScope = name.split("_")[0];
    lines.push(`ALTER TABLE ${qualified(engine, schema, name)} ADD CONSTRAINT ${q(engine, `fk_${constraintScope}_${i + 1}_${j + 1}`)} FOREIGN KEY (${q(engine, fk)}) REFERENCES ${qualified(engine, schema, rename(parent))} (${q(engine, "id")});`);
  }));
  return `${lines.join("\n\n")}\n`;
}

function rowCounts(tables, total = 250000) {
  const weights = tables.map(([name]) => /payment|transaction|event|history|audit|note|activity|invoice_item|charge|coverage|exposure/.test(name) ? 5 : /contact|account|policy|claim|invoice|vehicle|driver/.test(name) ? 3 : 1);
  const sum = weights.reduce((a, b) => a + b, 0);
  const counts = weights.map(w => Math.max(250, Math.floor((total * w / sum) / 50) * 50));
  counts[counts.length - 1] += total - counts.reduce((a, b) => a + b, 0);
  return counts;
}

const apps = [
  { app: "Policy Operations", engine: "oracle", source: "BE_CARDS", target: "BE_CARDS", tables: policy, targetRename: value => value.replace(/^pc_/, "pt_") },
  { app: "Claims Operations", engine: "sqlserver", source: "gw_claim_src", target: "gw_claim_tgt", tables: claims },
  { app: "Billing and Contacts", engine: "postgres", source: "gw_billing_src", target: "gw_billing_tgt", tables: billing },
];

const manifest = ["application,engine,source_schema,target_schema,source_table,target_table,planned_rows,fk_count"];
for (const app of apps) {
  const targetRename = app.targetRename ?? (value => value);
  fs.writeFileSync(path.join(root, `${app.engine}-${app.source}-source.sql`), buildDdl(app.engine, app.source, app.tables));
  fs.writeFileSync(path.join(root, `${app.engine}-${app.target}-target.sql`), buildDdl(app.engine, app.target, app.tables, targetRename));
  const counts = rowCounts(app.tables);
  app.tables.forEach(([table, refs], i) => manifest.push([app.app, app.engine, app.source, app.target, table, targetRename(table), counts[i], refs.length].join(",")));
}
fs.writeFileSync(path.join(root, "guidewire-150-table-manifest.csv"), `${manifest.join("\n")}\n`);

console.log(`Generated ${apps.length * 2} DDL files and ${manifest.length - 1} manifest rows in ${root}`);
