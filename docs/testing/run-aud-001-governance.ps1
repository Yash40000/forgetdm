param(
    [string]$BaseUrl = "http://localhost:8131",
    [string]$AdminUser = "admin",
    [Parameter(Mandatory = $true)]
    [string]$AdminPassword,
    [string]$TestPassword = "RbacTest123!",
    [string]$EvidencePath
)

$ErrorActionPreference = "Stop"
$suffix = (Get-Date).ToUniversalTime().ToString("yyyyMMddHHmmss")
$checks = [System.Collections.Generic.List[object]]::new()

function Correlation([string]$label) { "AUD001-GOV-$suffix-$label" }

function Login([string]$username, [string]$password) {
    $body = @{ username = $username; password = $password } | ConvertTo-Json -Compress
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/auth/login" -ContentType "application/json" `
        -Body $body -SessionVariable session | Out-Null
    return $session
}

function Invoke-ForgeApi {
    param(
        [Microsoft.PowerShell.Commands.WebRequestSession]$Session,
        [string]$Method,
        [string]$Path,
        [object]$Body,
        [string]$CorrelationId
    )
    $request = @{
        Method = $Method
        Uri = "$BaseUrl$Path"
        WebSession = $Session
        Headers = @{ "X-Correlation-ID" = $CorrelationId }
    }
    if ($null -ne $Body) {
        $request.ContentType = "application/json"
        $request.Body = $Body | ConvertTo-Json -Depth 30 -Compress
    }
    try { return Invoke-RestMethod @request }
    catch {
        $status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
        $detail = if ($_.ErrorDetails.Message) { $_.ErrorDetails.Message } else { $_.Exception.Message }
        throw "$Method $Path failed ($status): $detail"
    }
}

function Invoke-ExpectedFailure {
    param(
        [Microsoft.PowerShell.Commands.WebRequestSession]$Session,
        [string]$Method,
        [string]$Path,
        [object]$Body,
        [string]$CorrelationId
    )
    $request = @{
        Method = $Method
        Uri = "$BaseUrl$Path"
        WebSession = $Session
        Headers = @{ "X-Correlation-ID" = $CorrelationId }
        ContentType = "application/json"
        Body = $Body | ConvertTo-Json -Depth 30 -Compress
    }
    try {
        $response = Invoke-RestMethod @request
        return [pscustomobject]@{ status = 200; body = ($response | ConvertTo-Json -Depth 10 -Compress) }
    } catch {
        $status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
        $detail = if ($_.ErrorDetails.Message) { $_.ErrorDetails.Message } else { $_.Exception.Message }
        return [pscustomobject]@{ status = $status; body = $detail }
    }
}

function Check([string]$case, [bool]$passed, [string]$evidence) {
    $script:checks.Add([pscustomobject]@{ case = $case; passed = $passed; evidence = $evidence })
}

function Find-AuditEvent([string]$action, [string]$resourceId) {
    $encoded = [Uri]::EscapeDataString($action)
    $response = Invoke-ForgeApi $script:adminSession GET "/api/audit?action=$encoded&size=200" $null (Correlation "audit")
    return @($response.events) | Where-Object { "$($_.resourceId)" -eq $resourceId } | Select-Object -First 1
}

$adminSession = Login $AdminUser $AdminPassword

$entities = @(Invoke-ForgeApi $adminSession GET "/api/business-entities" $null (Correlation "entities"))
$entity = $entities | Where-Object { $_.name -eq "BE Lab - Customer 360" } | Select-Object -First 1
if ($null -eq $entity) { $entity = $entities | Select-Object -First 1 }
if ($null -eq $entity) { throw "AUD-001 governance test needs one Business Entity." }

$dashboard = Invoke-ForgeApi $adminSession GET "/api/business-entities/$($entity.id)/enterprise" $null (Correlation "dashboard")
$package = @($dashboard.operationalPackages) | Sort-Object { [long]$_.id } -Descending | Select-Object -First 1
if ($null -eq $package) { throw "Business Entity $($entity.id) needs one operational package for version/promotion evidence." }

$group = Invoke-ForgeApi $adminSession POST "/api/security/groups" @{
    name = "AUD-001-GOV-$suffix"
    description = "Disposable maker-checker evidence group"
    roles = @("TDM_ARCHITECT")
} (Correlation "group-create")

$makerName = "aud_maker_$suffix"
$checkerName = "aud_checker_$suffix"
$maker = Invoke-ForgeApi $adminSession POST "/api/security/users" @{
    username = $makerName; displayName = "AUD-001 Maker"; password = $TestPassword; active = $true
    roles = @(); groupIds = @([long]$group.id)
} (Correlation "maker-create")
$checker = Invoke-ForgeApi $adminSession POST "/api/security/users" @{
    username = $checkerName; displayName = "AUD-001 Checker"; password = $TestPassword; active = $true
    roles = @(); groupIds = @([long]$group.id)
} (Correlation "checker-create")

$makerSession = Login $makerName $TestPassword
$checkerSession = Login $checkerName $TestPassword

$releaseSecret = "AUD-SECRET-MAKER-$suffix"
$release = Invoke-ForgeApi $makerSession POST "/api/business-entities/$($entity.id)/governance-requests" @{
    objectType = "BUSINESS_ENTITY"; objectId = [long]$entity.id; action = "RELEASE"
    reviewer = $checkerName; riskLevel = "HIGH"; comments = $releaseSecret
} (Correlation "release-request")
Check "governance request is pending and attributed to maker" `
    ($release.status -eq "PENDING" -and $release.requestedBy -eq $makerName) `
    "request=$($release.id); status=$($release.status); maker=$($release.requestedBy)"

$selfApproval = Invoke-ExpectedFailure $makerSession POST "/api/business-entities/governance-requests/$($release.id)/approve" @{
    reviewer = $makerName; comments = "self approval must fail"; eSignature = "self-signature"
} (Correlation "self-approve")
Check "maker cannot approve own request" ($selfApproval.status -eq 409 -and $selfApproval.body -match "Maker-checker") `
    "status=$($selfApproval.status); response=$($selfApproval.body)"

$approvalSecret = "AUD-SECRET-APPROVAL-$suffix"
$signatureSecret = "AUD-SECRET-SIGNATURE-$suffix"
$approved = Invoke-ForgeApi $checkerSession POST "/api/business-entities/governance-requests/$($release.id)/approve" @{
    reviewer = $checkerName; comments = $approvalSecret; eSignature = $signatureSecret
} (Correlation "release-approve")
Check "checker approval records signer and signature hash" `
    ($approved.status -eq "APPROVED" -and $approved.signedBy -eq $checkerName -and -not [string]::IsNullOrWhiteSpace("$($approved.eSignatureHash)")) `
    "request=$($release.id); status=$($approved.status); signedBy=$($approved.signedBy); signatureHashPresent=$(-not [string]::IsNullOrWhiteSpace("$($approved.eSignatureHash)"))"

$rejectedRequest = Invoke-ForgeApi $makerSession POST "/api/business-entities/$($entity.id)/governance-requests" @{
    objectType = "BUSINESS_ENTITY"; objectId = [long]$entity.id; action = "RELEASE"
    reviewer = $checkerName; riskLevel = "MEDIUM"; comments = "AUD-SECRET-REJECT-REQUEST-$suffix"
} (Correlation "reject-request")
$rejected = Invoke-ForgeApi $checkerSession POST "/api/business-entities/governance-requests/$($rejectedRequest.id)/reject" @{
    reviewer = $checkerName; comments = "AUD-SECRET-REJECT-$suffix"; eSignature = "AUD-SECRET-REJECT-SIGN-$suffix"
} (Correlation "reject-decision")
Check "checker rejection is persisted" ($rejected.status -eq "REJECTED" -and $rejected.signedBy -eq $checkerName) `
    "request=$($rejected.id); status=$($rejected.status); signedBy=$($rejected.signedBy)"

$promoteApproval = Invoke-ForgeApi $makerSession POST "/api/business-entities/$($entity.id)/governance-requests" @{
    objectType = "OPERATIONAL_PACKAGE"; objectId = [long]$package.id; action = "PROMOTE"
    reviewer = $checkerName; riskLevel = "HIGH"; comments = "AUD-SECRET-PROMOTE-REQUEST-$suffix"
} (Correlation "promote-request")
$promoteApproval = Invoke-ForgeApi $checkerSession POST "/api/business-entities/governance-requests/$($promoteApproval.id)/approve" @{
    reviewer = $checkerName; comments = "AUD-SECRET-PROMOTE-APPROVAL-$suffix"; eSignature = "AUD-SECRET-PROMOTE-SIGN-$suffix"
} (Correlation "promote-approve")
Check "promotion approval is checker-signed" ($promoteApproval.status -eq "APPROVED" -and $promoteApproval.signedBy -eq $checkerName) `
    "request=$($promoteApproval.id); signedBy=$($promoteApproval.signedBy)"

$version = Invoke-ForgeApi $makerSession POST "/api/business-entities/operational-packages/$($package.id)/versions" @{
    retentionPolicy = "STANDARD_7_YEAR"; retentionDays = 2555; changeNote = "AUD-SECRET-VERSION-$suffix"
} (Correlation "version-create")
Check "immutable package version is created" `
    ($version.status -eq "IMMUTABLE" -and $version.createdBy -eq $makerName -and "$($version.artifactHash)" -match "^[0-9a-f]{64}$") `
    "version=$($version.id); number=$($version.versionNumber); actor=$($version.createdBy); hash=$($version.artifactHash)"

$promotion = Invoke-ForgeApi $makerSession POST "/api/business-entities/operational-packages/$($package.id)/promotions" @{
    versionId = [long]$version.id; fromEnvironment = "DEV"; toEnvironment = "QA"
    approver = $checkerName; comments = "AUD-SECRET-PROMOTION-$suffix"
} (Correlation "promotion-create")
Check "approved package promotion records actual maker and checker" `
    ($promotion.status -eq "PROMOTED" -and $promotion.requestedBy -eq $makerName -and
        $promotion.approvedBy -eq $checkerName -and [long]$promotion.approvedRequestId -eq [long]$promoteApproval.id) `
    "promotion=$($promotion.id); status=$($promotion.status); maker=$($promotion.requestedBy); checker=$($promotion.approvedBy); approval=$($promotion.approvedRequestId)"

Start-Sleep -Milliseconds 500
$expectedAudit = @(
    @{ action = "BUSINESS_ENTITY_GOVERNANCE_REQUEST"; id = "$($release.id)"; actor = $makerName; category = "GOVERNANCE"; type = "business-entity-governance-request" },
    @{ action = "BUSINESS_ENTITY_GOVERNANCE_APPROVED"; id = "$($release.id)"; actor = $checkerName; category = "GOVERNANCE"; type = "business-entity-governance-request" },
    @{ action = "BUSINESS_ENTITY_GOVERNANCE_REJECTED"; id = "$($rejected.id)"; actor = $checkerName; category = "GOVERNANCE"; type = "business-entity-governance-request" },
    @{ action = "BUSINESS_ENTITY_PACKAGE_VERSION_CREATE"; id = "$($version.id)"; actor = $makerName; category = "RELEASE"; type = "business-entity-package-version" },
    @{ action = "BUSINESS_ENTITY_PACKAGE_PROMOTION"; id = "$($promotion.id)"; actor = $makerName; category = "RELEASE"; type = "business-entity-package-promotion" }
)
$auditEvents = [System.Collections.Generic.List[object]]::new()
foreach ($want in $expectedAudit) {
    $event = Find-AuditEvent $want.action $want.id
    if ($null -ne $event) { $auditEvents.Add($event) }
    $passed = $null -ne $event -and $event.actor -eq $want.actor -and $event.category -eq $want.category -and
        $event.resourceType -eq $want.type -and $event.outcome -eq "SUCCESS" -and $event.resourceName -and $event.createdAt
    Check "structured audit $($want.action)" $passed $(if ($event) {
        "actor=$($event.actor); category=$($event.category); resource=$($event.resourceType)/$($event.resourceId); outcome=$($event.outcome)"
    } else { "event not found for resource $($want.id)" })
}

$auditJson = $auditEvents | ConvertTo-Json -Depth 30 -Compress
$secretLeak = $auditJson -match "AUD-SECRET-"
Check "governance audit metadata contains no comments or signatures" (-not $secretLeak) `
    $(if ($secretLeak) { "AUD-SECRET marker found in audit event payload" } else { "No rationale, change-note, promotion-comment, or e-signature marker found" })

$result = [ordered]@{
    story = "AUD-001"
    lane = "governance-package-lifecycle"
    runId = $suffix
    executedAt = (Get-Date).ToUniversalTime().ToString("o")
    entity = @{ id = $entity.id; name = $entity.name }
    actors = @{ maker = $makerName; checker = $checkerName; groupId = $group.id }
    artifacts = @{
        releaseRequestId = $release.id; rejectedRequestId = $rejected.id; promotionApprovalId = $promoteApproval.id
        packageId = $package.id; versionId = $version.id; promotionId = $promotion.id
    }
    passed = @($checks | Where-Object passed).Count
    failed = @($checks | Where-Object { -not $_.passed }).Count
    checks = $checks
}

if ([string]::IsNullOrWhiteSpace($EvidencePath)) {
    $EvidencePath = Join-Path (Resolve-Path (Join-Path $PSScriptRoot "..\..")) "test-results\AUD-001-governance-$suffix.json"
}
$parent = Split-Path -Parent $EvidencePath
if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
$result | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $EvidencePath -Encoding UTF8
$result | ConvertTo-Json -Depth 30

if ($result.failed -gt 0) { exit 1 }
