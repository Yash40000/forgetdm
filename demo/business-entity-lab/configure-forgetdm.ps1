param(
  [string]$BaseUrl = 'http://localhost:8088',
  [string]$Username = 'admin',
  [string]$Password = 'admin123'
)

$ErrorActionPreference = 'Stop'
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

function Invoke-ForgeAs($RequestSession, [string]$Method, [string]$Path, $Body = $null) {
  $args = @{ Method = $Method; Uri = "$BaseUrl$Path"; WebSession = $RequestSession }
  if ($null -ne $Body) {
    $args.ContentType = 'application/json'
    $args.Body = ConvertTo-Json -InputObject $Body -Depth 40 -Compress
  }
  try {
    Invoke-RestMethod @args
  } catch {
    $detail = $_.Exception.Message
    if ($_.Exception.Response) {
      try {
        $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
        $responseBody = $reader.ReadToEnd()
        if ($responseBody) { $detail = $responseBody }
      } catch {}
    }
    throw "$Method $Path failed: $detail"
  }
}

function Invoke-Forge([string]$Method, [string]$Path, $Body = $null) {
  Invoke-ForgeAs $session $Method $Path $Body
}

function Items($Response) {
  if ($null -eq $Response) { return @() }
  if ($null -ne $Response.PSObject.Properties['value']) { return @($Response.value) }
  return @($Response)
}

function Rel([string]$Name, [string]$ParentTable, [string]$ParentColumns, [string]$ChildTable, [string]$ChildColumns) {
  @{ name=$Name; parentTable=$ParentTable; parentColumns=$ParentColumns; childTable=$ChildTable; childColumns=$ChildColumns } | ConvertTo-Json -Compress
}

function Member([string]$System, [long]$SourceId, [long]$DatasetId, [string]$Schema, [string]$Role,
                [string]$Table, [string]$Keys, [string]$JoinRole = $null, [string]$Relationship = $null) {
  @{
    systemName=$System; dataSourceId=$SourceId; datasetId=$DatasetId; schemaName=$Schema;
    logicalRole=$Role; tableName=$Table; tableAlias=$Role; keyColumns=$Keys;
    joinToRole=$JoinRole; relationshipJson=$Relationship;
    includeInSubset=$true; includeInSynthetic=$true
  }
}

Invoke-Forge POST '/api/auth/login' @{ username=$Username; password=$Password } | Out-Null

$checkerUsername = 'qa_checker'
$checkerPassword = 'QaChecker2026!'
$checkerBody = @{ username=$checkerUsername; displayName='BE Lab QA Checker'; password=$checkerPassword; active=$true; roles=@('TDM_ARCHITECT'); groupIds=@() }
$checker = Items (Invoke-Forge GET '/api/security/users') | Where-Object username -eq $checkerUsername | Select-Object -First 1
if ($checker) {
  Invoke-Forge PUT "/api/security/users/$($checker.id)" $checkerBody | Out-Null
} else {
  Invoke-Forge POST '/api/security/users' $checkerBody | Out-Null
}
$checkerSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
Invoke-ForgeAs $checkerSession POST '/api/auth/login' @{ username=$checkerUsername; password=$checkerPassword } | Out-Null

$sourceDefinitions = @(
  @{ name='BE Lab - Core Banking PostgreSQL'; kind='POSTGRES'; jdbcUrl='jdbc:postgresql://localhost:5433/sourcedb'; username='postgres'; password='postgres'; role='BOTH'; environment='QA'; tags='business-entity,acceptance,core-banking' },
  @{ name='BE Lab - Card Servicing Oracle'; kind='ORACLE'; jdbcUrl='jdbc:oracle:thin:@localhost:1521:XE'; username='BE_CARDS'; password='ForgeTdm2026'; role='BOTH'; environment='QA'; tags='business-entity,acceptance,card-servicing' },
  @{ name='BE Lab - Digital Engagement MySQL'; kind='MYSQL'; jdbcUrl='jdbc:mysql://localhost:3306/digital_engagement?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'; username='be_digital'; password='ForgeTdm2026!'; role='BOTH'; environment='QA'; tags='business-entity,acceptance,digital-engagement' }
)

$sourceIds = @{}
$existingSources = Items (Invoke-Forge GET '/api/datasources')
foreach ($definition in $sourceDefinitions) {
  $existing = $existingSources | Where-Object name -eq $definition.name | Select-Object -First 1
  $saved = if ($existing) { Invoke-Forge PUT "/api/datasources/$($existing.id)" $definition } else { Invoke-Forge POST '/api/datasources' $definition }
  $test = Invoke-Forge POST "/api/datasources/$($saved.id)/test"
  if (-not $test.ok) { throw "Connection failed for $($saved.name): $($test.message)" }
  $sourceIds[$definition.kind] = [long]$saved.id
}

$tableKeys = @{
  branches='branch_id'; households='household_id'; customers='customer_id'; customer_addresses='address_id';
  customer_phones='phone_id'; customer_emails='email_id'; customer_identifiers='identifier_id'; household_members='household_id,customer_id';
  accounts='account_id'; account_holders='account_id,customer_id'; account_balances='account_id'; account_transactions='transaction_id';
  loans='loan_id'; loan_payments='payment_id'; cards='card_id'; card_transactions='card_transaction_id'; beneficiaries='beneficiary_id';
  standing_orders='order_id'; kyc_reviews='review_id'; customer_consents='consent_id';
  card_customers='card_customer_id'; card_accounts='card_account_id'; payment_cards='payment_card_id'; card_tokens='token_id'; merchants='merchant_id';
  card_authorizations='authorization_id'; card_postings='posting_id'; card_statements='statement_id'; card_payments='card_payment_id';
  card_disputes='dispute_id'; fraud_cases='fraud_case_id'; customer_preferences='card_customer_id'; card_audit_events='audit_event_id';
  digital_customers='digital_customer_id'; user_profiles='profile_id'; devices='device_id'; login_events='login_event_id';
  notification_preferences='digital_customer_id'; support_cases='support_case_id'; chat_interactions='interaction_id';
  web_sessions='session_id'; marketing_events='marketing_event_id'; offer_assignments='offer_assignment_id'
}

function Ensure-Dataset([string]$Name, [string]$Description, [long]$SourceId, [string]$Schema, [string]$Driver,
                        [string[]]$Tables, [string[]]$Q2StopTables = @()) {
  $existing = Items (Invoke-Forge GET '/api/datasets') | Where-Object name -eq $Name | Select-Object -First 1
  $body = @{ name=$Name; description=$Description; dataSourceId=$SourceId; schemaName=$Schema; targetDataSourceId=$SourceId; targetSchemaName=$Schema; driverTable=$Driver; driverFilter=$null; globalQ1=$true; globalQ2=$true }
  $dataset = if ($existing) { Invoke-Forge PUT "/api/datasets/$($existing.id)" $body } else { Invoke-Forge POST '/api/datasets' $body }
  $profiles = @($Tables | ForEach-Object {
    $q2Mode = if ($Q2StopTables -contains $_) { 'NO' } else { 'YES' }
    $note = if ($q2Mode -eq 'NO') { 'Shared parent: pull for RI, but do not fan back out to unrelated entities.' } else { 'Business Entity acceptance fixture' }
    @{ datasetId=[long]$dataset.id; sourceDataSourceId=$SourceId; sourceSchemaName=$Schema; tableName=$_; targetTableName=$_; included=$true; referentialStrategy='FOLLOW_PARENT'; q1Mode='YES'; q2Mode=$q2Mode; note=$note }
  })
  Invoke-Forge PUT "/api/datasets/$($dataset.id)/profiles" $profiles | Out-Null
  $pks = @($Tables | ForEach-Object { @{ datasetId=[long]$dataset.id; tableName=$_; columnNames=$tableKeys[$_]; note='Acceptance fixture key' } })
  Invoke-Forge PUT "/api/datasets/$($dataset.id)/custom-pks" $pks | Out-Null
  return [long]$dataset.id
}

$pgTables = @('branches','households','customers','customer_addresses','customer_phones','customer_emails','customer_identifiers','household_members','accounts','account_holders','account_balances','account_transactions','loans','loan_payments','cards','card_transactions','beneficiaries','standing_orders','kyc_reviews','customer_consents')
$oracleTables = @('card_customers','card_accounts','payment_cards','card_tokens','merchants','card_authorizations','card_postings','card_statements','card_payments','card_disputes','fraud_cases','customer_preferences','card_audit_events')
$mysqlTables = @('digital_customers','user_profiles','devices','login_events','notification_preferences','support_cases','chat_interactions','web_sessions','marketing_events','offer_assignments')

$pgDataset = Ensure-Dataset 'BE Lab - Core Banking Scope' 'Customer-centered Core Banking slice used by the multi-application Business Entity acceptance lab.' $sourceIds.POSTGRES 'be_core' 'customers' $pgTables @('branches','households')
$oracleDataset = Ensure-Dataset 'BE Lab - Card Servicing Scope' 'Card servicing slice linked to the canonical customer through core_customer_no.' $sourceIds.ORACLE 'BE_CARDS' 'card_customers' $oracleTables @('merchants')
$mysqlDataset = Ensure-Dataset 'BE Lab - Digital Engagement Scope' 'Digital engagement slice linked to the canonical customer through core_customer_ref.' $sourceIds.MYSQL 'digital_engagement' 'digital_customers' $mysqlTables

Invoke-Forge PUT "/api/datasets/$pgDataset/traversal-rules" @(
  @{ parentTable='accounts'; childTable='account_holders'; relSource='DB'; traverseDirection='Q1_ONLY'; priority=10; note='Pull the referenced account for a selected holder, but do not expand into unrelated joint holders.' }
) | Out-Null
Invoke-Forge PUT "/api/datasets/$oracleDataset/traversal-rules" @(
  @{ parentTable='merchants'; childTable='card_authorizations'; relSource='DB'; traverseDirection='Q1_ONLY'; priority=10; note='Pull merchants used by selected authorizations without fanning back into every customer using that merchant.' }
) | Out-Null

$members = @(
  (Member 'Core Banking' $sourceIds.POSTGRES $pgDataset 'be_core' 'core_customer' 'customers' 'customer_no'),
  (Member 'Core Banking' $sourceIds.POSTGRES $pgDataset 'be_core' 'core_address' 'customer_addresses' 'address_id' 'core_customer' (Rel 'customer_address' 'customers' 'customer_id' 'customer_addresses' 'customer_id')),
  (Member 'Core Banking' $sourceIds.POSTGRES $pgDataset 'be_core' 'core_phone' 'customer_phones' 'phone_id' 'core_customer' (Rel 'customer_phone' 'customers' 'customer_id' 'customer_phones' 'customer_id')),
  (Member 'Core Banking' $sourceIds.POSTGRES $pgDataset 'be_core' 'core_email' 'customer_emails' 'email_id' 'core_customer' (Rel 'customer_email' 'customers' 'customer_id' 'customer_emails' 'customer_id')),
  (Member 'Core Banking' $sourceIds.POSTGRES $pgDataset 'be_core' 'core_identifier' 'customer_identifiers' 'identifier_id' 'core_customer' (Rel 'customer_identifier' 'customers' 'customer_id' 'customer_identifiers' 'customer_id')),
  (Member 'Core Banking' $sourceIds.POSTGRES $pgDataset 'be_core' 'core_household' 'households' 'household_id' 'core_customer' (Rel 'customer_household' 'customers' 'household_id' 'households' 'household_id')),
  (Member 'Core Banking' $sourceIds.POSTGRES $pgDataset 'be_core' 'core_household_member' 'household_members' 'household_id,customer_id' 'core_customer' (Rel 'customer_household_membership' 'customers' 'customer_id' 'household_members' 'customer_id')),
  (Member 'Core Banking' $sourceIds.POSTGRES $pgDataset 'be_core' 'core_account_holder' 'account_holders' 'account_id,customer_id' 'core_customer' (Rel 'customer_account_holder' 'customers' 'customer_id' 'account_holders' 'customer_id')),
  (Member 'Core Banking' $sourceIds.POSTGRES $pgDataset 'be_core' 'core_account' 'accounts' 'account_id' 'core_account_holder' (Rel 'holder_account' 'account_holders' 'account_id' 'accounts' 'account_id')),
  (Member 'Core Banking' $sourceIds.POSTGRES $pgDataset 'be_core' 'core_branch' 'branches' 'branch_id' 'core_account' (Rel 'account_branch' 'accounts' 'branch_id' 'branches' 'branch_id')),
  (Member 'Core Banking' $sourceIds.POSTGRES $pgDataset 'be_core' 'core_balance' 'account_balances' 'account_id' 'core_account' (Rel 'account_balance' 'accounts' 'account_id' 'account_balances' 'account_id')),
  (Member 'Core Banking' $sourceIds.POSTGRES $pgDataset 'be_core' 'core_transaction' 'account_transactions' 'transaction_id' 'core_account' (Rel 'account_transaction' 'accounts' 'account_id' 'account_transactions' 'account_id')),
  (Member 'Core Banking' $sourceIds.POSTGRES $pgDataset 'be_core' 'core_loan' 'loans' 'loan_id' 'core_customer' (Rel 'customer_loan' 'customers' 'customer_id' 'loans' 'customer_id')),
  (Member 'Core Banking' $sourceIds.POSTGRES $pgDataset 'be_core' 'core_loan_payment' 'loan_payments' 'payment_id' 'core_loan' (Rel 'loan_payment' 'loans' 'loan_id' 'loan_payments' 'loan_id')),
  (Member 'Core Banking' $sourceIds.POSTGRES $pgDataset 'be_core' 'core_card' 'cards' 'card_id' 'core_customer' (Rel 'customer_card' 'customers' 'customer_id' 'cards' 'customer_id')),
  (Member 'Core Banking' $sourceIds.POSTGRES $pgDataset 'be_core' 'core_card_transaction' 'card_transactions' 'card_transaction_id' 'core_card' (Rel 'card_transaction' 'cards' 'card_id' 'card_transactions' 'card_id')),
  (Member 'Core Banking' $sourceIds.POSTGRES $pgDataset 'be_core' 'core_beneficiary' 'beneficiaries' 'beneficiary_id' 'core_customer' (Rel 'customer_beneficiary' 'customers' 'customer_id' 'beneficiaries' 'customer_id')),
  (Member 'Core Banking' $sourceIds.POSTGRES $pgDataset 'be_core' 'core_standing_order' 'standing_orders' 'order_id' 'core_account' (Rel 'account_standing_order' 'accounts' 'account_id' 'standing_orders' 'account_id')),
  (Member 'Core Banking' $sourceIds.POSTGRES $pgDataset 'be_core' 'core_kyc_review' 'kyc_reviews' 'review_id' 'core_customer' (Rel 'customer_kyc' 'customers' 'customer_id' 'kyc_reviews' 'customer_id')),
  (Member 'Core Banking' $sourceIds.POSTGRES $pgDataset 'be_core' 'core_consent' 'customer_consents' 'consent_id' 'core_customer' (Rel 'customer_consent' 'customers' 'customer_id' 'customer_consents' 'customer_id')),

  (Member 'Card Servicing' $sourceIds.ORACLE $oracleDataset 'BE_CARDS' 'cards_customer' 'card_customers' 'core_customer_no' 'core_customer' (Rel 'core_to_card_customer' 'customers' 'customer_no' 'card_customers' 'core_customer_no')),
  (Member 'Card Servicing' $sourceIds.ORACLE $oracleDataset 'BE_CARDS' 'cards_account' 'card_accounts' 'card_account_id' 'cards_customer' (Rel 'card_customer_account' 'card_customers' 'card_customer_id' 'card_accounts' 'card_customer_id')),
  (Member 'Card Servicing' $sourceIds.ORACLE $oracleDataset 'BE_CARDS' 'cards_payment_card' 'payment_cards' 'payment_card_id' 'cards_account' (Rel 'card_account_card' 'card_accounts' 'card_account_id' 'payment_cards' 'card_account_id')),
  (Member 'Card Servicing' $sourceIds.ORACLE $oracleDataset 'BE_CARDS' 'cards_token' 'card_tokens' 'token_id' 'cards_payment_card' (Rel 'payment_card_token' 'payment_cards' 'payment_card_id' 'card_tokens' 'payment_card_id')),
  (Member 'Card Servicing' $sourceIds.ORACLE $oracleDataset 'BE_CARDS' 'cards_authorization' 'card_authorizations' 'authorization_id' 'cards_payment_card' (Rel 'payment_card_authorization' 'payment_cards' 'payment_card_id' 'card_authorizations' 'payment_card_id')),
  (Member 'Card Servicing' $sourceIds.ORACLE $oracleDataset 'BE_CARDS' 'cards_merchant' 'merchants' 'merchant_id' 'cards_authorization' (Rel 'authorization_merchant' 'card_authorizations' 'merchant_id' 'merchants' 'merchant_id')),
  (Member 'Card Servicing' $sourceIds.ORACLE $oracleDataset 'BE_CARDS' 'cards_posting' 'card_postings' 'posting_id' 'cards_authorization' (Rel 'authorization_posting' 'card_authorizations' 'authorization_id' 'card_postings' 'authorization_id')),
  (Member 'Card Servicing' $sourceIds.ORACLE $oracleDataset 'BE_CARDS' 'cards_statement' 'card_statements' 'statement_id' 'cards_account' (Rel 'card_account_statement' 'card_accounts' 'card_account_id' 'card_statements' 'card_account_id')),
  (Member 'Card Servicing' $sourceIds.ORACLE $oracleDataset 'BE_CARDS' 'cards_payment' 'card_payments' 'card_payment_id' 'cards_account' (Rel 'card_account_payment' 'card_accounts' 'card_account_id' 'card_payments' 'card_account_id')),
  (Member 'Card Servicing' $sourceIds.ORACLE $oracleDataset 'BE_CARDS' 'cards_dispute' 'card_disputes' 'dispute_id' 'cards_customer' (Rel 'card_customer_dispute' 'card_customers' 'card_customer_id' 'card_disputes' 'card_customer_id')),
  (Member 'Card Servicing' $sourceIds.ORACLE $oracleDataset 'BE_CARDS' 'cards_fraud_case' 'fraud_cases' 'fraud_case_id' 'cards_customer' (Rel 'card_customer_fraud' 'card_customers' 'card_customer_id' 'fraud_cases' 'card_customer_id')),
  (Member 'Card Servicing' $sourceIds.ORACLE $oracleDataset 'BE_CARDS' 'cards_preference' 'customer_preferences' 'card_customer_id' 'cards_customer' (Rel 'card_customer_preference' 'card_customers' 'card_customer_id' 'customer_preferences' 'card_customer_id')),
  (Member 'Card Servicing' $sourceIds.ORACLE $oracleDataset 'BE_CARDS' 'cards_audit' 'card_audit_events' 'audit_event_id' 'cards_customer' (Rel 'card_customer_audit' 'card_customers' 'card_customer_id' 'card_audit_events' 'card_customer_id')),

  (Member 'Digital Engagement' $sourceIds.MYSQL $mysqlDataset 'digital_engagement' 'digital_customer' 'digital_customers' 'core_customer_ref' 'core_customer' (Rel 'core_to_digital_customer' 'customers' 'customer_no' 'digital_customers' 'core_customer_ref')),
  (Member 'Digital Engagement' $sourceIds.MYSQL $mysqlDataset 'digital_engagement' 'digital_profile' 'user_profiles' 'profile_id' 'digital_customer' (Rel 'digital_customer_profile' 'digital_customers' 'digital_customer_id' 'user_profiles' 'digital_customer_id')),
  (Member 'Digital Engagement' $sourceIds.MYSQL $mysqlDataset 'digital_engagement' 'digital_device' 'devices' 'device_id' 'digital_customer' (Rel 'digital_customer_device' 'digital_customers' 'digital_customer_id' 'devices' 'digital_customer_id')),
  (Member 'Digital Engagement' $sourceIds.MYSQL $mysqlDataset 'digital_engagement' 'digital_login' 'login_events' 'login_event_id' 'digital_customer' (Rel 'digital_customer_login' 'digital_customers' 'digital_customer_id' 'login_events' 'digital_customer_id')),
  (Member 'Digital Engagement' $sourceIds.MYSQL $mysqlDataset 'digital_engagement' 'digital_notification' 'notification_preferences' 'digital_customer_id' 'digital_customer' (Rel 'digital_customer_notification' 'digital_customers' 'digital_customer_id' 'notification_preferences' 'digital_customer_id')),
  (Member 'Digital Engagement' $sourceIds.MYSQL $mysqlDataset 'digital_engagement' 'digital_support_case' 'support_cases' 'support_case_id' 'digital_customer' (Rel 'digital_customer_support' 'digital_customers' 'digital_customer_id' 'support_cases' 'digital_customer_id')),
  (Member 'Digital Engagement' $sourceIds.MYSQL $mysqlDataset 'digital_engagement' 'digital_chat' 'chat_interactions' 'interaction_id' 'digital_customer' (Rel 'digital_customer_chat' 'digital_customers' 'digital_customer_id' 'chat_interactions' 'digital_customer_id')),
  (Member 'Digital Engagement' $sourceIds.MYSQL $mysqlDataset 'digital_engagement' 'digital_session' 'web_sessions' 'session_id' 'digital_customer' (Rel 'digital_customer_session' 'digital_customers' 'digital_customer_id' 'web_sessions' 'digital_customer_id')),
  (Member 'Digital Engagement' $sourceIds.MYSQL $mysqlDataset 'digital_engagement' 'digital_marketing' 'marketing_events' 'marketing_event_id' 'digital_customer' (Rel 'digital_customer_marketing' 'digital_customers' 'digital_customer_id' 'marketing_events' 'digital_customer_id')),
  (Member 'Digital Engagement' $sourceIds.MYSQL $mysqlDataset 'digital_engagement' 'digital_offer' 'offer_assignments' 'offer_assignment_id' 'digital_customer' (Rel 'digital_customer_offer' 'digital_customers' 'digital_customer_id' 'offer_assignments' 'digital_customer_id'))
)

$entityName = 'BE Lab - Customer 360'
$entityBody = @{ name=$entityName; description='A realistic customer aggregate spanning Core Banking, Oracle Card Servicing, and MySQL Digital Engagement. Built for cross-application identity, freshness, snapshot, governance, and provisioning acceptance.'; domain='Retail Banking'; ownerUsername='admin'; primaryDatasetId=$pgDataset; rootTable='customers'; businessKeyColumns='customer_no'; status='ACTIVE' }
$existingEntity = Items (Invoke-Forge GET '/api/business-entities') | Where-Object name -eq $entityName | Select-Object -First 1
$entity = if ($existingEntity) { Invoke-Forge PUT "/api/business-entities/$($existingEntity.id)" $entityBody } else { Invoke-Forge POST '/api/business-entities' $entityBody }
for ($i=0; $i -lt $members.Count; $i++) { $members[$i].ordinalNo=$i }
Invoke-Forge PUT "/api/business-entities/$($entity.id)/members" $members | Out-Null
$detail = Invoke-Forge GET "/api/business-entities/$($entity.id)"
$memberByRole = @{}; foreach ($member in (Items $detail.members)) { $memberByRole[$member.logicalRole] = $member }

for ($i=1; $i -le 100; $i++) {
  $canonical = 'CUST-' + $i.ToString('000000')
  $identity = @{
    canonicalKey=$canonical; identityType='CUSTOMER'; status='ACTIVE'; confidence=1.0;
    attributes=@{ testCohort='BE_ACCEPTANCE'; customerOrdinal=$i; systems=3 };
    links=@(
      @{ memberId=[long]$memberByRole.core_customer.id; systemName='Core Banking'; dataSourceId=$sourceIds.POSTGRES; schemaName='be_core'; tableName='customers'; logicalRole='core_customer'; keyColumns='customer_no'; keyValues=@{customer_no=$canonical}; externalId=$canonical; confidence=1.0; matchRule='EXACT_CORE_CUSTOMER_NO'; status='ACTIVE'; source='ACCEPTANCE_SEED' },
      @{ memberId=[long]$memberByRole.cards_customer.id; systemName='Card Servicing'; dataSourceId=$sourceIds.ORACLE; schemaName='BE_CARDS'; tableName='card_customers'; logicalRole='cards_customer'; keyColumns='core_customer_no'; keyValues=@{core_customer_no=$canonical}; externalId=$canonical; confidence=1.0; matchRule='EXACT_CORE_CUSTOMER_NO'; status='ACTIVE'; source='ACCEPTANCE_SEED' },
      @{ memberId=[long]$memberByRole.digital_customer.id; systemName='Digital Engagement'; dataSourceId=$sourceIds.MYSQL; schemaName='digital_engagement'; tableName='digital_customers'; logicalRole='digital_customer'; keyColumns='core_customer_ref'; keyValues=@{core_customer_ref=$canonical}; externalId=$canonical; confidence=1.0; matchRule='EXACT_CORE_CUSTOMER_REF'; status='ACTIVE'; source='ACCEPTANCE_SEED' }
    )
  }
  Invoke-Forge POST "/api/business-entities/$($entity.id)/identities" $identity | Out-Null
}

$policies = Items (Invoke-Forge GET "/api/business-entities/$($entity.id)/sync-policies")
$policyExisting = $policies | Where-Object name -eq 'BE Lab - Cross-App Freshness' | Select-Object -First 1
$syncMembers = @(
  @{ memberId=[long]$memberByRole.core_customer.id; systemName='Core Banking'; dataSourceId=$sourceIds.POSTGRES; schemaName='be_core'; tableName='customers'; logicalRole='core_customer'; keyColumns='customer_no'; watermarkColumn='updated_at'; maxLagSeconds=15552000; syncMode='POLLING'; queryFilter="customer_status = 'ACTIVE'" },
  @{ memberId=[long]$memberByRole.cards_customer.id; systemName='Card Servicing'; dataSourceId=$sourceIds.ORACLE; schemaName='BE_CARDS'; tableName='CARD_CUSTOMERS'; logicalRole='cards_customer'; keyColumns='CORE_CUSTOMER_NO'; watermarkColumn='UPDATED_AT'; maxLagSeconds=15552000; syncMode='POLLING'; queryFilter="SERVICING_STATUS = 'ACTIVE'" },
  @{ memberId=[long]$memberByRole.digital_customer.id; systemName='Digital Engagement'; dataSourceId=$sourceIds.MYSQL; schemaName='digital_engagement'; tableName='digital_customers'; logicalRole='digital_customer'; keyColumns='core_customer_ref'; watermarkColumn='updated_at'; maxLagSeconds=15552000; syncMode='POLLING'; queryFilter="digital_status = 'ACTIVE'" }
)
$policyBody = @{ id=if($policyExisting){[long]$policyExisting.id}else{$null}; name='BE Lab - Cross-App Freshness'; syncMode='POLLING'; status='ACTIVE'; maxLagSeconds=15552000; scheduleCron='0 */30 * * * *'; syncStrategy='FRESHNESS_CHECK'; autoRefreshEnabled=$false; notes='Acceptance policy checks a real watermark in all three engines.'; members=$syncMembers }
$syncPolicy = Invoke-Forge POST "/api/business-entities/$($entity.id)/sync-policies" $policyBody
$freshnessRun = Invoke-Forge POST "/api/business-entities/sync-policies/$($syncPolicy.id)/check"

$snapshots = Items (Invoke-Forge GET "/api/business-entities/$($entity.id)/snapshots")
$snapshot = $snapshots | Where-Object name -eq 'BE Lab - Loaded Baseline' | Select-Object -First 1
if (-not $snapshot) {
  $snapshotResult = Invoke-Forge POST "/api/business-entities/$($entity.id)/snapshots" @{ name='BE Lab - Loaded Baseline'; snapshotType='ACCEPTANCE_BASELINE'; captureMode='EVIDENCE_ONLY'; note='Real row-count evidence immediately after the three-application load.'; retentionDays=365 }
  $snapshot = $snapshotResult.snapshot
}

$capsulePolicyName = 'BE Lab - Customer 360 Capsule Masking'
$capsulePolicy = Items (Invoke-Forge GET '/api/policies') | Where-Object name -eq $capsulePolicyName | Select-Object -First 1
if (-not $capsulePolicy) {
  $capsulePolicy = Invoke-Forge POST '/api/policies' @{
    name=$capsulePolicyName; dataSourceId=$sourceIds.POSTGRES; schemaName='MULTI_APP';
    description='Deterministic cross-application masking for encrypted Customer 360 Micro-DB capsules.'
  }
}
$capsuleRuleDefs = @(
  @{schema='be_core';table='customers';column='first_name';function='FIRST_NAME'},
  @{schema='be_core';table='customers';column='last_name';function='LAST_NAME'},
  @{schema='be_core';table='customers';column='date_of_birth';function='DATE_SHIFT';param1='120'},
  @{schema='be_core';table='customers';column='ssn';function='SSN'},
  @{schema='be_core';table='customer_addresses';column='line1';function='ADDRESS_STREET'},
  @{schema='be_core';table='customer_addresses';column='city';function='FORMAT_PRESERVE'},
  @{schema='be_core';table='customer_addresses';column='postal_code';function='FORMAT_PRESERVE'},
  @{schema='be_core';table='customer_phones';column='phone_number';function='PHONE'},
  @{schema='be_core';table='customer_emails';column='email_address';function='EMAIL';param1='USER_SAFE'},
  @{schema='be_core';table='customer_identifiers';column='identifier_value';function='FORMAT_PRESERVE'},
  @{schema='be_core';table='accounts';column='account_no';function='FORMAT_PRESERVE'},
  @{schema='be_core';table='cards';column='pan';function='CREDIT_CARD'},
  @{schema='be_core';table='beneficiaries';column='beneficiary_name';function='FULL_NAME'},
  @{schema='be_core';table='beneficiaries';column='bank_routing_no';function='ABA_ROUTING'},
  @{schema='be_core';table='beneficiaries';column='bank_account_no';function='BANK_ACCOUNT';param1='KEEP_LAST4'},
  @{schema='be_core';table='kyc_reviews';column='analyst';function='FORMAT_PRESERVE'},
  @{schema='BE_CARDS';table='card_customers';column='first_name';function='FIRST_NAME'},
  @{schema='BE_CARDS';table='card_customers';column='last_name';function='LAST_NAME'},
  @{schema='BE_CARDS';table='card_customers';column='date_of_birth';function='DATE_SHIFT';param1='120'},
  @{schema='BE_CARDS';table='card_customers';column='email_address';function='EMAIL';param1='USER_SAFE'},
  @{schema='BE_CARDS';table='card_customers';column='mobile_phone';function='PHONE'},
  @{schema='BE_CARDS';table='payment_cards';column='pan';function='CREDIT_CARD'},
  @{schema='BE_CARDS';table='card_tokens';column='token_reference';function='TOKENIZE';param1='TKN_';param2='32'},
  @{schema='BE_CARDS';table='card_tokens';column='device_reference';function='TOKENIZE';param1='DEV_';param2='32'},
  @{schema='BE_CARDS';table='fraud_cases';column='investigator';function='FORMAT_PRESERVE'},
  @{schema='BE_CARDS';table='card_audit_events';column='actor_id';function='TOKENIZE';param1='ACT_';param2='24'},
  @{schema='BE_CARDS';table='card_audit_events';column='event_detail';function='REDACT'},
  @{schema='digital_engagement';table='digital_customers';column='username';function='TOKENIZE';param1='USR_';param2='24'},
  @{schema='digital_engagement';table='digital_customers';column='email_address';function='EMAIL';param1='USER_SAFE'},
  @{schema='digital_engagement';table='digital_customers';column='mobile_phone';function='PHONE'},
  @{schema='digital_engagement';table='user_profiles';column='display_name';function='FULL_NAME'},
  @{schema='digital_engagement';table='devices';column='device_fingerprint';function='TOKENIZE';param1='DEV_';param2='32'},
  @{schema='digital_engagement';table='login_events';column='ip_address';function='IP_ADDRESS'},
  @{schema='digital_engagement';table='support_cases';column='subject';function='REDACT'},
  @{schema='digital_engagement';table='chat_interactions';column='transcript_excerpt';function='REDACT'}
)
$existingCapsuleRules = @(Items (Invoke-Forge GET "/api/policies/$($capsulePolicy.id)/rules"))
foreach ($ruleDef in $capsuleRuleDefs) {
  $existingRule = $existingCapsuleRules | Where-Object { $_.tableName -eq $ruleDef.table -and $_.columnName -eq $ruleDef.column } | Select-Object -First 1
  $ruleBody = @{ schemaName=$ruleDef.schema; tableName=$ruleDef.table; columnName=$ruleDef.column; function=$ruleDef.function; param1=$ruleDef.param1; param2=$ruleDef.param2; deterministic=$true }
  if ($existingRule) {
    Invoke-Forge PATCH "/api/policies/rules/$($existingRule.id)" @{ function=$ruleDef.function; param1=$ruleDef.param1; param2=$ruleDef.param2 } | Out-Null
  } else {
    Invoke-Forge POST "/api/policies/$($capsulePolicy.id)/rules" $ruleBody | Out-Null
  }
}

$capsuleKey = 'customer_no=CUST-000042'
$capsule = Items (Invoke-Forge GET "/api/business-entities/$($entity.id)/capsules") | Where-Object canonicalKey -eq $capsuleKey | Select-Object -First 1
if (-not $capsule) {
  $capsuleResult = Invoke-Forge POST "/api/business-entities/$($entity.id)/capsules/materialize" @{
    businessKey=@{customer_no='CUST-000042'}; policyId=[long]$capsulePolicy.id; syncMode='ON_DEMAND';
    staleAfterMinutes=1440; notes='Acceptance Micro-DB: masked, encrypted, versioned Customer 360 across three applications.'
  }
  $capsule = $capsuleResult.instance
}
$capsuleDetail = Invoke-Forge GET "/api/business-entities/capsules/$($capsule.id)"
$badFragments = @(Items $capsuleDetail.fragments | Where-Object { $_.fragmentType -in @('FAILED','SKIPPED') })
if ($badFragments.Count) {
  $capsuleResult = Invoke-Forge POST "/api/business-entities/$($entity.id)/capsules/materialize" @{
    businessKey=@{customer_no='CUST-000042'}; policyId=[long]$capsulePolicy.id; syncMode='ON_DEMAND';
    staleAfterMinutes=1440; notes='Acceptance Micro-DB refresh after relationship validation.'
  }
  $capsule = $capsuleResult.instance
  $capsuleDetail = Invoke-Forge GET "/api/business-entities/capsules/$($capsule.id)"
}
$checkerGrant = Items $capsuleDetail.grants | Where-Object { $_.grantee -eq $checkerUsername -and $_.scope -eq 'PROVISION' -and -not $_.revoked } | Select-Object -First 1
if (-not $checkerGrant) {
  Invoke-Forge POST "/api/business-entities/capsules/$($capsule.id)/access-grants" @{
    granteeType='USER'; grantee=$checkerUsername; scope='PROVISION'; ttlHours=168;
    note='QA checker may inspect and provision the acceptance capsule for seven days.'
  } | Out-Null
}

Invoke-Forge POST "/api/business-entities/$($entity.id)/catalog/sync" | Out-Null

$dashboard = Invoke-Forge GET "/api/business-entities/$($entity.id)/enterprise"
$issue = Items $dashboard.issuePackages | Where-Object issueKey -eq 'BE-LAB-001' | Select-Object -First 1
if (-not $issue) {
  $issue = Invoke-Forge POST "/api/business-entities/$($entity.id)/issue-packages" @{
    issueKey='BE-LAB-001'; title='Recreate a cross-application customer servicing defect'; severity='HIGH'; sourceEnvironment='QA'; targetEnvironment='UAT'; snapshotId=[long]$snapshot.id; recreationMode='EXACT_ENTITY'; privacyAction='MASK'; ttlHours=168;
    businessKeys=@(@{customer_no='CUST-000025'},@{customer_no='CUST-000042'},@{customer_no='CUST-000077'})
  }
}

$dashboard = Invoke-Forge GET "/api/business-entities/$($entity.id)/enterprise"
$approved = Items $dashboard.governanceRequests | Where-Object { $_.action -eq 'SUBSET_MASK' -and $_.status -eq 'APPROVED' } | Select-Object -First 1
if (-not $approved) {
  $request = Invoke-Forge POST "/api/business-entities/$($entity.id)/governance-requests" @{ objectType='BUSINESS_ENTITY'; objectId=[long]$entity.id; action='SUBSET_MASK'; reviewer='qa_checker'; riskLevel='MEDIUM'; comments='Acceptance approval for reusable cross-application plan.' }
  $approved = Invoke-ForgeAs $checkerSession POST "/api/business-entities/governance-requests/$($request.id)/approve" @{ reviewer='qa_checker'; comments='Model, identities, source connections, and row-count evidence reviewed.'; eSignature='QA-CHECKER-BE-LAB-2026' }
}

$dashboard = Invoke-Forge GET "/api/business-entities/$($entity.id)/enterprise"
$plan = Items $dashboard.executionPlans | Where-Object name -eq 'BE Lab - Customer 360 QA Provision' | Select-Object -First 1
if (-not $plan) {
  $plan = Invoke-Forge POST "/api/business-entities/$($entity.id)/execution-plans" @{ name='BE Lab - Customer 360 QA Provision'; operationType='SUBSET_MASK'; sourceEnvironment='QA'; targetEnvironment='UAT'; mode='PLAN_ONLY'; issuePackageId=[long]$issue.id }
}
$dashboard = Invoke-Forge GET "/api/business-entities/$($entity.id)/enterprise"
$microDbPlanName = "BE Lab - CUST-000042 Micro-DB v$($capsule.currentVersion) Provision"
$microDbPlan = Items $dashboard.executionPlans | Where-Object name -eq $microDbPlanName | Select-Object -First 1
if (-not $microDbPlan) {
  $microDbPlan = Invoke-Forge POST "/api/business-entities/$($entity.id)/execution-plans" @{
    name=$microDbPlanName; operationType='SUBSET_MASK'; sourceEnvironment='MICRO_DB';
    targetEnvironment='UAT'; mode='PLAN_ONLY'; issuePackageId=[long]$issue.id; capsuleInstanceId=[long]$capsule.id
  }
}

$flows = Items (Invoke-Forge GET "/api/business-entities/$($entity.id)/flows")
$flow = $flows | Where-Object name -eq 'BE Lab - Customer 360 Enterprise Flow' | Select-Object -First 1
$starter = Invoke-Forge GET "/api/business-entities/$($entity.id)/flows/starter"
$flowBody = @{ id=if($flow){[long]$flow.id}else{$null}; name='BE Lab - Customer 360 Enterprise Flow'; description='Published visual flow for approval, reservation checks, three-application fan-out, transform, prepare, execution, rollback, and evidence.'; status='DRAFT'; nodes=$starter.nodes; edges=$starter.edges; settings=$starter.settings }
$flow = Invoke-Forge POST "/api/business-entities/$($entity.id)/flows" $flowBody
$flowValidation = Invoke-Forge POST "/api/business-entities/flows/$($flow.id)/validate"
if ($flowValidation.status -ne 'PASS') { throw "Flow validation failed: $($flowValidation | ConvertTo-Json -Depth 10)" }
$flow = Invoke-Forge POST "/api/business-entities/flows/$($flow.id)/publish"
$debugRun = Invoke-Forge POST "/api/business-entities/flows/$($flow.id)/debug" @{ mode='DEBUG_DRY_RUN'; breakpoints=@('fanout','prepare'); inputs=@{ canonicalKey='CUST-000025'; issueKey='BE-LAB-001'; applicationCount=3 } }

$dashboard = Invoke-Forge GET "/api/business-entities/$($entity.id)/enterprise"
$package = Items $dashboard.operationalPackages | Where-Object name -eq 'BE Lab - Customer 360 Release Package' | Select-Object -First 1
if (-not $package) {
  $package = Invoke-Forge POST "/api/business-entities/$($entity.id)/operational-packages" @{ name='BE Lab - Customer 360 Release Package'; executionPlanId=[long]$plan.id; packageType='SELF_SERVICE'; targetEnvironment='UAT' }
}
$versions = Items (Invoke-Forge GET "/api/business-entities/operational-packages/$($package.id)/versions")
if (-not $versions.Count) {
  Invoke-Forge POST "/api/business-entities/operational-packages/$($package.id)/versions" @{ retentionPolicy='BANKING_ACCEPTANCE'; retentionDays=365; changeNote='Initial reusable acceptance package.' } | Out-Null
}
$dashboard = Invoke-Forge GET "/api/business-entities/$($entity.id)/enterprise"
$microDbPackageName = "BE Lab - CUST-000042 Micro-DB v$($capsule.currentVersion) Package"
$microDbPackage = Items $dashboard.operationalPackages | Where-Object name -eq $microDbPackageName | Select-Object -First 1
if (-not $microDbPackage) {
  $microDbPackage = Invoke-Forge POST "/api/business-entities/$($entity.id)/operational-packages" @{
    name=$microDbPackageName; executionPlanId=[long]$microDbPlan.id;
    packageType='SELF_SERVICE'; targetEnvironment='UAT'
  }
}
$microDbVersions = Items (Invoke-Forge GET "/api/business-entities/operational-packages/$($microDbPackage.id)/versions")
if (-not $microDbVersions.Count) {
  Invoke-Forge POST "/api/business-entities/operational-packages/$($microDbPackage.id)/versions" @{
    retentionPolicy='BANKING_ACCEPTANCE'; retentionDays=365; changeNote='Initial immutable Micro-DB-backed runner.'
  } | Out-Null
}

$resolved = Invoke-Forge POST "/api/business-entities/$($entity.id)/identities/resolve" @{ memberId=[long]$memberByRole.digital_customer.id; externalId='CUST-000042'; tableName='digital_customers'; systemName='Digital Engagement'; dataSourceId=$sourceIds.MYSQL; schemaName='digital_engagement'; keyColumns='core_customer_ref' }
$snapshotDetail = Invoke-Forge GET "/api/business-entities/snapshots/$($snapshot.id)"
$finalDashboard = Invoke-Forge GET "/api/business-entities/$($entity.id)/enterprise"

$summary = [ordered]@{
  entityId=[long]$entity.id; entityName=$entityName; memberCount=(Items $detail.members).Count; dataSourceIds=$sourceIds;
  datasetIds=@{postgres=$pgDataset;oracle=$oracleDataset;mysql=$mysqlDataset}; identitySubjects=(Items (Invoke-Forge GET "/api/business-entities/$($entity.id)/identities")).Count;
  identityResolveMatched=$resolved.matched; freshnessPolicyId=[long]$syncPolicy.id; freshnessStatus=$freshnessRun.status;
  snapshotId=[long]$snapshot.id; snapshotStatus=$snapshot.status; snapshotMemberCount=(Items $snapshotDetail.members).Count;
  issuePackageId=[long]$issue.id; governanceRequestId=[long]$approved.id; executionPlanId=[long]$plan.id; executionPlanStatus=$plan.status;
  flowId=[long]$flow.id; flowStatus=$flow.status; flowValidation=$flowValidation.status; debugRunStatus=$debugRun.status;
  operationalPackageId=[long]$package.id; catalogAssetCount=(Items $finalDashboard.catalogAssets).Count;
  capsuleId=[long]$capsule.id; capsuleVersion=[int]$capsule.currentVersion; capsuleFragments=(Items $capsuleDetail.fragments).Count;
  capsuleRows=[long]$capsule.totalRows; capsulePolicyId=[long]$capsulePolicy.id;
  microDbPlanId=[long]$microDbPlan.id; microDbPackageId=[long]$microDbPackage.id
}
$summary | ConvertTo-Json -Depth 10
