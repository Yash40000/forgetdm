param(
  [string]$BaseUrl = 'http://localhost:8088',
  [string]$Username = 'admin',
  [string]$Password = 'admin123'
)

$ErrorActionPreference = 'Stop'
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$checks = New-Object System.Collections.Generic.List[object]

function Invoke-Forge([string]$Method, [string]$Path, $Body = $null) {
  $args = @{ Method=$Method; Uri="$BaseUrl$Path"; WebSession=$session }
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
        $body = $reader.ReadToEnd()
        if ($body) { $detail = $body }
      } catch {}
    }
    throw "$Method $Path failed: $detail"
  }
}

function Invoke-ForgeExpectedFailure([string]$Method, [string]$Path, $Body) {
  try {
    Invoke-Forge $Method $Path $Body | Out-Null
    return @{ statusCode=200; body='Request unexpectedly succeeded.' }
  } catch {
    $message = $_.Exception.Message
    $status = 0
    if ($message -match 'failed: \{.*\}') {
      $bodyText = $message.Substring($message.IndexOf('{'))
    } else {
      $bodyText = $message
    }
    if ($message -match '\(400\)' -or $message -match 'Reviewer must match') { $status = 400 }
    return @{ statusCode=$status; body=$bodyText }
  }
}

function Items($Response) {
  if ($null -eq $Response) { return @() }
  if ($null -ne $Response.PSObject.Properties['value']) { return @($Response.value) }
  return @($Response)
}

function Add-Check([string]$Name, [bool]$Passed, [string]$Evidence) {
  $checks.Add([ordered]@{ name=$Name; passed=$Passed; evidence=$Evidence })
  $label = if ($Passed) { 'PASS' } else { 'FAIL' }
  Write-Host "[$label] $Name - $Evidence"
}

function Require([string]$Name, [bool]$Condition, [string]$Evidence) {
  Add-Check $Name $Condition $Evidence
  if (-not $Condition) { throw "Acceptance check failed: $Name ($Evidence)" }
}

Invoke-Forge POST '/api/auth/login' @{ username=$Username; password=$Password } | Out-Null

$entity = Items (Invoke-Forge GET '/api/business-entities') |
  Where-Object name -eq 'BE Lab - Customer 360' | Select-Object -First 1
Require 'Business Entity exists' ($null -ne $entity) "entityId=$($entity.id)"

$entityId = [long]$entity.id
$detail = Invoke-Forge GET "/api/business-entities/$entityId"
$members = @(Items $detail.members)
$sourceIds = @($members | Select-Object -ExpandProperty dataSourceId -Unique)
Require 'Business Entity spans three applications' ($members.Count -eq 43 -and $sourceIds.Count -eq 3) "members=$($members.Count), sources=$($sourceIds.Count)"

$sourceRows = @(Items (Invoke-Forge GET '/api/datasources') | Where-Object { $sourceIds -contains [long]$_.id })
$sourceEvidence = @()
foreach ($source in $sourceRows) {
  $test = Invoke-Forge POST "/api/datasources/$($source.id)/test"
  $schema = switch ($source.kind) { 'POSTGRES' {'be_core'} 'ORACLE' {'BE_CARDS'} 'MYSQL' {'digital_engagement'} }
  $diag = Invoke-Forge GET "/api/datasources/$($source.id)/diagnostics?schema=$schema&maxTables=100"
  $ready = [bool]$test.ok -and $diag.status -eq 'READY'
  Require "Connector ready: $($source.name)" $ready "engine=$($source.kind), status=$($diag.status)"
  $sourceEvidence += [ordered]@{
    id=[long]$source.id; name=$source.name; engine=$source.kind; schema=$schema;
    diagnosticStatus=$diag.status; readinessScore=$diag.readinessScore;
    tables=$diag.schemaShape.tableCount; columns=$diag.schemaShape.columnCount
  }
}

$memberByRole = @{}
foreach ($member in $members) { $memberByRole[$member.logicalRole] = $member }
$resolveCases = @(
  @{ role='core_customer'; system='Core Banking'; externalId='CUST-000042' },
  @{ role='cards_customer'; system='Card Servicing'; externalId='CUST-000042' },
  @{ role='digital_customer'; system='Digital Engagement'; externalId='CUST-000042' }
)
$identityEvidence = @()
foreach ($case in $resolveCases) {
  $member = $memberByRole[$case.role]
  $resolved = Invoke-Forge POST "/api/business-entities/$entityId/identities/resolve" @{
    memberId=[long]$member.id; externalId=$case.externalId; tableName=$member.tableName;
    systemName=$case.system; dataSourceId=[long]$member.dataSourceId; schemaName=$member.schemaName;
    keyColumns=$member.keyColumns
  }
  Require "Identity resolves from $($case.system)" ([bool]$resolved.matched -and $resolved.crosswalk.canonicalKey -eq 'CUST-000042') "canonical=$($resolved.crosswalk.canonicalKey)"
  $identityEvidence += [ordered]@{ system=$case.system; matched=[bool]$resolved.matched; canonicalKey=$resolved.crosswalk.canonicalKey }
}
$identities = @(Items (Invoke-Forge GET "/api/business-entities/$entityId/identities"))
Require 'Seeded identity crosswalk is reusable' ($identities.Count -eq 100) "identitySubjects=$($identities.Count), linksPerSubject=3"

$policies = @(Items (Invoke-Forge GET "/api/business-entities/$entityId/sync-policies"))
$policy = $policies | Where-Object name -eq 'BE Lab - Cross-App Freshness' | Select-Object -First 1
$freshness = Invoke-Forge POST "/api/business-entities/sync-policies/$($policy.id)/check"
$freshMembers = @($freshness.result.members)
Require 'Cross-application freshness policy passes' ($freshness.status -eq 'FRESH' -and $freshMembers.Count -eq 3 -and @($freshMembers | Where-Object status -ne 'FRESH').Count -eq 0) "status=$($freshness.status), systems=$($freshMembers.Count)"

$snapshots = @(Items (Invoke-Forge GET "/api/business-entities/$entityId/snapshots"))
$snapshot = $snapshots | Where-Object name -eq 'BE Lab - Loaded Baseline' | Select-Object -First 1
$snapshotDetail = Invoke-Forge GET "/api/business-entities/snapshots/$($snapshot.id)"
$snapshotMembers = @($snapshotDetail.members)
$snapshotTotal = [long](($snapshotMembers | Measure-Object -Property rowCount -Sum).Sum)
$badSnapshotMembers = @($snapshotMembers | Where-Object {
  $evidence = $_.evidenceJson | ConvertFrom-Json
  $_.status -ne 'CAPTURED' -or -not [bool]$evidence.countVerified
})
Require 'Immutable snapshot has verified row evidence' ($snapshot.immutable -and $snapshot.status -eq 'AVAILABLE' -and $snapshotMembers.Count -eq 43 -and $snapshotTotal -eq 437025 -and $badSnapshotMembers.Count -eq 0) "snapshotId=$($snapshot.id), members=$($snapshotMembers.Count), rows=$snapshotTotal"

$rowsBySource = @{}
foreach ($source in $sourceRows) {
  $rowsBySource[$source.name] = [long](($snapshotMembers | Where-Object dataSourceId -eq $source.id | Measure-Object -Property rowCount -Sum).Sum)
}
Require 'PostgreSQL fixture row count' ($rowsBySource['BE Lab - Core Banking PostgreSQL'] -eq 198825) "rows=$($rowsBySource['BE Lab - Core Banking PostgreSQL'])"
Require 'Oracle fixture row count' ($rowsBySource['BE Lab - Card Servicing Oracle'] -eq 144200) "rows=$($rowsBySource['BE Lab - Card Servicing Oracle'])"
Require 'MySQL fixture row count' ($rowsBySource['BE Lab - Digital Engagement MySQL'] -eq 94000) "rows=$($rowsBySource['BE Lab - Digital Engagement MySQL'])"

$capsule = @(Items (Invoke-Forge GET "/api/business-entities/$entityId/capsules") |
  Where-Object canonicalKey -eq 'customer_no=CUST-000042' | Select-Object -First 1)[0]
Require 'Customer 360 Micro-DB exists and is active' ($null -ne $capsule -and $capsule.status -eq 'ACTIVE') "capsuleId=$($capsule.id), version=$($capsule.currentVersion)"
$capsuleDetail = Invoke-Forge GET "/api/business-entities/capsules/$($capsule.id)"
$capsuleFragments = @(Items $capsuleDetail.fragments)
$capsuleBad = @($capsuleFragments | Where-Object { $_.fragmentType -ne 'MASKED' -or -not $_.encrypted -or $_.status -ne 'CURRENT' })
$capsuleEmpty = @($capsuleFragments | Where-Object { [long]$_.rowCount -eq 0 })
Require 'Micro-DB captures every member as an encrypted masked fragment' ($capsuleFragments.Count -eq 43 -and $capsuleBad.Count -eq 0 -and $capsuleEmpty.Count -eq 0) "fragments=$($capsuleFragments.Count), rows=$($capsule.totalRows), invalid=$($capsuleBad.Count), empty=$($capsuleEmpty.Count)"
$capsuleSystems = @($capsuleFragments | Group-Object systemName | ForEach-Object {
  [ordered]@{ system=$_.Name; fragments=$_.Count; rows=[long](($_.Group | Measure-Object rowCount -Sum).Sum) }
})
Require 'Micro-DB spans all three physical applications' ($capsuleSystems.Count -eq 3 -and [long]$capsule.totalRows -eq 265) (($capsuleSystems | ForEach-Object { "$($_.system)=$($_.fragments) fragments/$($_.rows) rows" }) -join '; ')
Require 'Micro-DB keeps version, freshness, access and lineage evidence' (@($capsuleDetail.versions).Count -ge 1 -and @($capsuleDetail.watermarks).Count -eq 3 -and @($capsuleDetail.lineage).Count -ge 3) "versions=$(@($capsuleDetail.versions).Count), watermarks=$(@($capsuleDetail.watermarks).Count), lineage=$(@($capsuleDetail.lineage).Count)"
$ownerGrant = @($capsuleDetail.grants | Where-Object { -not $_.revoked -and $_.grantee -eq 'admin' -and $_.scope -eq 'OWNER' })
$checkerGrant = @($capsuleDetail.grants | Where-Object { -not $_.revoked -and $_.grantee -eq 'qa_checker' -and $_.scope -eq 'PROVISION' })
Require 'Micro-DB access grants enforce owner and delegated provision scopes' ($ownerGrant.Count -ge 1 -and $checkerGrant.Count -ge 1) "ownerGrants=$($ownerGrant.Count), checkerProvisionGrants=$($checkerGrant.Count)"

$coreRow = @((@($capsuleFragments | Where-Object tableName -eq 'customers')[0].payloadJson | ConvertFrom-Json))[0]
$coreEmailRow = @((@($capsuleFragments | Where-Object tableName -eq 'customer_emails')[0].payloadJson | ConvertFrom-Json))[0]
$corePhoneRow = @((@($capsuleFragments | Where-Object tableName -eq 'customer_phones')[0].payloadJson | ConvertFrom-Json))[0]
$cardRow = @((@($capsuleFragments | Where-Object tableName -eq 'card_customers')[0].payloadJson | ConvertFrom-Json))[0]
$digitalRow = @((@($capsuleFragments | Where-Object tableName -eq 'digital_customers')[0].payloadJson | ConvertFrom-Json))[0]
$keysPreserved = $coreRow.customer_no -eq 'CUST-000042' -and $cardRow.core_customer_no -eq 'CUST-000042' -and $digitalRow.core_customer_ref -eq 'CUST-000042'
$piiChanged = $coreRow.first_name -ne 'Jennifer' -and $coreRow.ssn -ne '942-42-0042'
$consistent = $coreEmailRow.email_address -eq $cardRow.email_address -and $cardRow.email_address -eq $digitalRow.email_address -and $corePhoneRow.phone_number -eq $cardRow.mobile_phone -and $cardRow.mobile_phone -eq $digitalRow.mobile_phone
Require 'Micro-DB masks PII while preserving canonical identity' ($keysPreserved -and $piiChanged) "keysPreserved=$keysPreserved, rootPiiChanged=$piiChanged"
Require 'Micro-DB masking is deterministic across database engines' $consistent "emailConsistent=$($coreEmailRow.email_address -eq $cardRow.email_address -and $cardRow.email_address -eq $digitalRow.email_address), phoneConsistent=$($corePhoneRow.phone_number -eq $cardRow.mobile_phone -and $cardRow.mobile_phone -eq $digitalRow.mobile_phone)"

$enterprise = Invoke-Forge GET "/api/business-entities/$entityId/enterprise"
$governance = @($enterprise.governanceRequests | Where-Object status -eq 'APPROVED' | Select-Object -First 1)
$governance = $governance[0]
Require 'Maker-checker approval is signed by a separate user' ($governance.requestedBy -eq 'admin' -and $governance.reviewer -eq 'qa_checker' -and $governance.signedBy -eq 'qa_checker') "maker=$($governance.requestedBy), checker=$($governance.signedBy)"
$spoof = Invoke-ForgeExpectedFailure POST "/api/business-entities/governance-requests/$($governance.id)/approve" @{ reviewer='qa_checker'; comments='negative acceptance check'; eSignature='INVALID' }
Require 'Governance actor spoof is rejected' ($spoof.body -match 'Reviewer must match the authenticated user') 'admin cannot approve as qa_checker'

$plan = @($enterprise.executionPlans | Where-Object name -eq 'BE Lab - Customer 360 QA Provision' | Select-Object -First 1)[0]
$planModel = $plan.planJson | ConvertFrom-Json
$slices = @($planModel.applicationSlices)
$sliceMembers = [int](($slices | Measure-Object -Property memberCount -Sum).Sum)
Require 'Approved saved execution plan fans out by application' ($plan.status -eq 'APPROVED' -and $plan.mode -eq 'PLAN_ONLY' -and $slices.Count -eq 3 -and $sliceMembers -eq 43) "planId=$($plan.id), slices=$($slices.Count), mappedMembers=$sliceMembers"

$package = @($enterprise.operationalPackages | Where-Object name -eq 'BE Lab - Customer 360 Release Package' | Select-Object -First 1)[0]
$versions = @(Items (Invoke-Forge GET "/api/business-entities/operational-packages/$($package.id)/versions"))
Require 'Reusable saved job package has an immutable version' ($package.status -eq 'READY' -and $versions.Count -ge 1 -and @($versions | Where-Object status -ne 'IMMUTABLE').Count -eq 0) "packageId=$($package.id), versions=$($versions.Count)"
$microPlanName = "BE Lab - CUST-000042 Micro-DB v$($capsule.currentVersion) Provision"
$microPlan = @($enterprise.executionPlans | Where-Object name -eq $microPlanName | Select-Object -First 1)[0]
$microValidation = $microPlan.validationJson | ConvertFrom-Json
$capsuleEvidence = $microValidation.capsuleEvidence
Require 'Approved execution plan is pinned to the current Micro-DB version' ($microPlan.status -eq 'APPROVED' -and $microPlan.mode -eq 'PLAN_ONLY' -and [long]$capsuleEvidence.capsuleInstanceId -eq [long]$capsule.id -and [int]$capsuleEvidence.capsuleVersion -eq [int]$capsule.currentVersion) "planId=$($microPlan.id), capsuleId=$($capsuleEvidence.capsuleInstanceId), version=$($capsuleEvidence.capsuleVersion)"
$microPackageName = "BE Lab - CUST-000042 Micro-DB v$($capsule.currentVersion) Package"
$microPackage = @($enterprise.operationalPackages | Where-Object name -eq $microPackageName | Select-Object -First 1)[0]
$microVersions = @(Items (Invoke-Forge GET "/api/business-entities/operational-packages/$($microPackage.id)/versions"))
Require 'Micro-DB saved job package is reusable and immutable' ($microPackage.status -eq 'READY' -and [long]$microPackage.executionPlanId -eq [long]$microPlan.id -and $microVersions.Count -ge 1 -and @($microVersions | Where-Object status -ne 'IMMUTABLE').Count -eq 0) "packageId=$($microPackage.id), planId=$($microPlan.id), versions=$($microVersions.Count)"
Require 'Business Entity catalog is complete' (@($enterprise.catalogAssets).Count -eq 44) "catalogAssets=$(@($enterprise.catalogAssets).Count)"

$flows = @(Items (Invoke-Forge GET "/api/business-entities/$entityId/flows"))
$flow = $flows | Where-Object name -eq 'BE Lab - Customer 360 Enterprise Flow' | Select-Object -First 1
$flowValidation = Invoke-Forge POST "/api/business-entities/flows/$($flow.id)/validate"
$debugRun = Invoke-Forge POST "/api/business-entities/flows/$($flow.id)/debug" @{
  mode='DEBUG_DRY_RUN'; breakpoints=@(); inputs=@{ canonicalKey='CUST-000042'; applicationCount=3 }
}
Require 'Published visual flow validates and completes a dry run' ($flow.status -eq 'ACTIVE' -and $flowValidation.status -eq 'PASS' -and $debugRun.status -eq 'COMPLETED') "flowId=$($flow.id), validation=$($flowValidation.status), debug=$($debugRun.status)"

$previewExpected = @{ 32=939; 33=295; 34=144 }
$previewEvidence = @()
foreach ($datasetId in 32,33,34) {
  $preview = Invoke-Forge POST "/api/datasets/$datasetId/preview" @{ maxDriverRows=3 }
  $warningCount = @($preview.warnings).Count
  Require "DataScope preview is bounded and valid: dataset $datasetId" ([long]$preview.totalRows -eq [long]$previewExpected[$datasetId] -and $warningCount -eq 0) "rows=$($preview.totalRows), warnings=$warningCount"
  $previewEvidence += [ordered]@{ datasetId=$datasetId; driverTable=$preview.driverTable; totalRows=[long]$preview.totalRows; warnings=$warningCount; loadOrder=@($preview.loadOrder) }
}

$tableRows = @($snapshotMembers | ForEach-Object {
  $source = $sourceRows | Where-Object id -eq $_.dataSourceId | Select-Object -First 1
  [ordered]@{
    application=$source.name; engine=$source.kind; schema=$_.schemaName; table=$_.tableName;
    rowCount=[long]$_.rowCount; status=$_.status; countVerified=[bool](($_.evidenceJson | ConvertFrom-Json).countVerified)
  }
} | Sort-Object application, table)

$result = [ordered]@{
  generatedAt=(Get-Date).ToUniversalTime().ToString('o')
  overallStatus=if (@($checks | Where-Object { -not $_.passed }).Count -eq 0) { 'PASS' } else { 'FAIL' }
  entity=[ordered]@{ id=$entityId; name=$entity.name; domain=$entity.domain; members=$members.Count; dataSources=$sourceIds.Count; identitySubjects=$identities.Count }
  totals=[ordered]@{ applications=3; schemas=3; tables=43; rows=$snapshotTotal; postgresRows=$rowsBySource['BE Lab - Core Banking PostgreSQL']; oracleRows=$rowsBySource['BE Lab - Card Servicing Oracle']; mysqlRows=$rowsBySource['BE Lab - Digital Engagement MySQL'] }
  sources=$sourceEvidence
  identityResolution=$identityEvidence
  freshness=[ordered]@{ policyId=[long]$policy.id; status=$freshness.status; members=$freshMembers }
  snapshot=[ordered]@{ id=[long]$snapshot.id; status=$snapshot.status; immutable=[bool]$snapshot.immutable; retentionUntil=$snapshot.retentionUntil; memberCount=$snapshotMembers.Count; totalRows=$snapshotTotal }
  tableRows=$tableRows
  governance=[ordered]@{ requestId=[long]$governance.id; status=$governance.status; maker=$governance.requestedBy; checker=$governance.signedBy; spoofRejected=$true }
  savedExecution=[ordered]@{ planId=[long]$plan.id; planStatus=$plan.status; mode=$plan.mode; slices=$slices; packageId=[long]$package.id; packageStatus=$package.status; packageVersions=$versions.Count }
  microDb=[ordered]@{ capsuleId=[long]$capsule.id; canonicalKey=$capsule.canonicalKey; version=[int]$capsule.currentVersion; status=$capsule.status; fragments=$capsuleFragments.Count; totalRows=[long]$capsule.totalRows; encryptedFragments=@($capsuleFragments | Where-Object encrypted).Count; systems=$capsuleSystems; policyId=[long]$capsule.policyId; planId=[long]$microPlan.id; packageId=[long]$microPackage.id; packageVersions=$microVersions.Count; sourceReadOnLaunch=$false }
  flow=[ordered]@{ id=[long]$flow.id; status=$flow.status; validation=$flowValidation.status; debugRunId=[long]$debugRun.id; debugStatus=$debugRun.status }
  previews=$previewEvidence
  checks=$checks
}

$output = Join-Path $scriptRoot 'acceptance-results.json'
$result | ConvertTo-Json -Depth 30 | Set-Content -Path $output -Encoding UTF8
Write-Host "Acceptance result: $($result.overallStatus)"
Write-Host "Evidence written to $output"
