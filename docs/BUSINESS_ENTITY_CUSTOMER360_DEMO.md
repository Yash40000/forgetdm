# ForgeTDM Business Entity Demo: Customer 360 Provisioning

This is a concrete example you can use to understand and demo how Business Entity helps provisioning.

## The Story

A tester does not want to build table-by-table jobs every time. They want this:

> Give me 3 complete active customer records in UAT, with all related accounts, cards, addresses, and transactions, masked correctly and loaded together.

That full object is the Business Entity.

In this demo, the Business Entity is:

**Customer 360 - Active VIP**

It represents one customer plus everything needed to test that customer safely.

## Multiple Applications And Different Databases

In a real banking project, Customer 360 is usually not inside one database.

It can look like this:

| Application | Database Type | Example Data | Link To Customer |
| --- | --- | --- | --- |
| Core Banking | DB2 UDB | customer, deposit account, balance | `customer_id` |
| Card Platform | Oracle | card, card account, authorization | `customer_id` or `card_customer_ref` |
| CRM | PostgreSQL | email, phone, consent, address | `customer_id` or `crm_party_id` |
| Digital Banking | SQL Server | login, device, channel preference | `customer_id` or `digital_user_id` |
| Analytics | Snowflake | derived segments and risk bands | `customer_id` or hashed party key |

Business Entity helps because it does not force all of this into one database. It models the business object above the applications.

The important part is the cross-application identity map:

| Business Entity Key | Core DB2 | Card Oracle | CRM Postgres | Digital SQL Server |
| --- | --- | --- | --- | --- |
| `CUST-10025` | `customer_id=10025` | `card_customer_ref=778812` | `crm_party_id=P44281` | `digital_user_id=D99120` |

That crosswalk lets ForgeTDM understand that these different technical keys are the same business customer.

Then one provisioning request can mean:

```text
Provision Customer 360 CUST-10025 to UAT
  -> Core Banking UAT DB2
  -> Card Platform UAT Oracle
  -> CRM UAT PostgreSQL
  -> Digital Banking UAT SQL Server
```

Each target can still use its own database, schema, load mode, masking rules, and native loader.

## How Multi-Database Provisioning Works

For multiple applications, think of the Business Entity as the master plan.

```text
Business Entity: Customer 360
  Root business key: customer identity
  Application slices:
    Core Banking slice
    Card slice
    CRM slice
    Digital slice
    Analytics slice
```

Each slice has:

| Slice Setting | Example |
| --- | --- |
| Source connection | `core-db2-prod`, `cards-oracle-prod`, `crm-postgres-prod` |
| Target connection | `core-db2-uat`, `cards-oracle-uat`, `crm-postgres-uat` |
| Local tables | Tables owned by that application |
| Local join rules | How tables join inside that application |
| Crosswalk rule | How that app maps to the enterprise customer key |
| Masking rules | Rules that apply to that app's columns |
| Load mode | Insert, replace, truncate, native loader, or in-place |
| Evidence | Row counts, rejects, loader logs, approval, lineage |

Execution flow:

```text
1. User requests Customer 360 data.
2. ForgeTDM resolves the customer business keys.
3. ForgeTDM expands each key into application-specific keys using the crosswalk.
4. Each application slice extracts its own rows from its own source database.
5. Masking uses the same seed and entity key so repeated values stay consistent.
6. Target loads run in the right dependency order.
7. Evidence is stored per application slice and at the parent Business Entity level.
```

Example consistency rule:

```text
Same real customer email appears in CRM and Digital.
ForgeTDM masks it once using Customer 360 key + seed.
Both target apps receive the same masked email.
```

That is how multi-application consistency is maintained.

## Demo Tables

Use this relationship model:

| Role | Table | Key | Parent |
| --- | --- | --- | --- |
| Customer | `customers` | `id` | root |
| Address | `addresses` | `id` | `addresses.customer_id -> customers.id` |
| Account | `accounts` | `id` | `accounts.customer_id -> customers.id` |
| Card | `cards` | `id` | `cards.account_id -> accounts.id` |
| Transaction | `transactions` | `id` | `transactions.account_id -> accounts.id` |

The root table is `customers`.

Recommended filter for the demo:

```sql
status = 'ACTIVE'
```

Optional smaller business filter:

```sql
status = 'ACTIVE' AND vip = true
```

## What You Build First

Before the Business Entity is useful, build the provisioning foundation once.

1. Go to **Data Sources**.
2. Register the source database.
   - Name: `bank-demo`
   - Example URL: `jdbc:postgresql://localhost:5433/bankdemo`
   - User: `demo`
   - Password: `demo`
3. Register the target database.
   - Name: `bank-qa`
   - Example URL: `jdbc:postgresql://localhost:5434/bankqa`
   - User: `demo`
   - Password: `demo`
4. Go to **PII Discovery**.
5. Scan the source tables.
6. Review discovered PII and generate or select masking rules.
7. Go to **DataScope**.
8. Create a blueprint/table map for the five tables.
9. Apply column maps and masking rules.
10. Save the blueprint.

At this point, DataScope knows how to subset, mask, and load the tables. Business Entity now wraps that into a reusable business object.

## Business Entity Click Path

Go to **Business Entities**.

### 1. Create The Entity

Create a Business Entity:

| Field | Value |
| --- | --- |
| Name | `Customer 360 - Active VIP` |
| Domain | `Retail Banking` |
| Root table | `customers` |
| Business key | `id` |
| Source environment | `PROD` |
| Target environment | `UAT` |

If the UI offers **Create from DataScope**, use the DataScope blueprint you created. This is the fastest path because the table map and column map are already known.

### 2. Confirm The Model Tab

The model should show:

```text
Customer
  addresses
  accounts
    cards
    transactions
```

This tells you ForgeTDM understands the full customer object, not just a single table.

### 3. Use Time And Reserve

Create a snapshot or reservation:

| Field | Value |
| --- | --- |
| Purpose | `Payments regression` |
| Consumer | `UAT team` |
| Entity count | `3` |
| Expiry | `7 days` |

This lets the team control who is using which test customers and why.

### 4. Use Build Data

For masked subset:

| Field | Value |
| --- | --- |
| Package name | `PAY-1027 Customer360 masked subset` |
| Data type | `Subset + Mask` |
| Count | `3 customers` |

For synthetic look-alike:

| Field | Value |
| --- | --- |
| Package name | `PAY-1027 Customer360 synthetic lookalike` |
| Data type | `Synthetic Look-Alike` |
| Count | `1000 customers` |

### 5. Use Governance

Request approval:

| Field | Value |
| --- | --- |
| Action | `RUN` |
| Reason | `Payments regression UAT data refresh` |
| Approver | `TDM Admin` |

Approve it before running if your mode requires maker-checker.

### 6. Use Run And Packages

Create the execution plan:

| Field | Value |
| --- | --- |
| Plan name | `Customer 360 UAT subset` |
| Operation | `SUBSET_MASK` |
| Source | `bank-demo` |
| Target | `bank-qa` |
| Target schema | `public` |
| Entity count | `3` |
| Seed | `uat-customer360-001` |
| Load mode | `REPLACE` |
| Prep action | `TRUNCATE` or `DELETE` |

Before launch, the confirmation screen should explain the flow:

```text
1. Read customers where status = 'ACTIVE'
2. Pull addresses for those customers
3. Pull accounts for those customers
4. Pull cards for those accounts
5. Pull transactions for those accounts
6. Apply column masking rules from the DataScope map
7. Load the masked rows into bank-qa
8. Save run evidence and audit details
```

Launch the plan after confirmation.

### 7. Use Evidence

After the run, check:

| Evidence | What It Proves |
| --- | --- |
| Execution run | Who ran it, when, and with which parameters |
| Package version | Which approved data package was used |
| Loader evidence | How the target was loaded |
| Masking evidence | Which rules were applied |
| Validation results | Whether row counts and relationships passed |

## What Success Looks Like

Target database should contain a complete, consistent customer object:

```text
customers:      3 masked customers
addresses:      related addresses for those customers
accounts:       related accounts for those customers
cards:          cards for those accounts
transactions:   transactions for those accounts
```

Foreign keys should still make sense:

```text
addresses.customer_id    -> customers.id
accounts.customer_id     -> customers.id
cards.account_id         -> accounts.id
transactions.account_id  -> accounts.id
```

Sensitive fields should be masked:

```text
customers.ssn
customers.email
customers.phone
cards.card_number
cards.cardholder_name
```

## Why This Helps Provisioning

Without Business Entity, the user has to remember:

```text
Which tables are needed
How the tables join
Which columns are sensitive
Which target to load
Which masking rules to use
Which load mode is safe
Which approval is needed
Where the evidence is
```

With Business Entity, the admin models this once. After that, the user requests:

```text
Customer 360 - Active VIP
3 entities
UAT target
Run approved package
```

ForgeTDM turns that business request into the correct DataScope or Synthetic execution underneath.

## How To Demo It To Your Team

Use this talk track:

1. "DataScope knows the technical table map."
2. "Business Entity turns the table map into a business object."
3. "Here the business object is Customer 360."
4. "In a bank, that customer can span DB2, Oracle, Postgres, SQL Server, and Snowflake."
5. "The Business Entity keeps the cross-application identity map."
6. "A tester does not need to know all source systems and joins."
7. "They request complete customers for UAT."
8. "ForgeTDM pulls the related rows, applies masking consistently, loads each target, and keeps evidence."
9. "This is how we move from table-level TDM to enterprise-style provisioning."

## Demo Video File

Open this local demo page in Chrome or Edge:

```text
D:\forgetdm - Copy\docs\business-entity-customer360-demo.html
```

Use **Play** to watch the animated walkthrough.

Use **Export WebM Video** to generate a browser-recorded video file you can share.
