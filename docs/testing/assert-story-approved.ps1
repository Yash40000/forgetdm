param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Z]+-[0-9]{3}$')]
    [string]$StoryId,

    [Parameter(Mandatory = $true)]
    [ValidateSet(
        'SOURCE_INSPECTION',
        'UNIT_TEST',
        'LOCAL_BROWSER',
        'LOCAL_TEST_DATABASE',
        'LOCAL_TEST_FIXTURE_MUTATION',
        'PRODUCTION',
        'CUSTOMER_DATA',
        'PAID_SERVICE',
        'EXTERNAL_NOTIFICATION',
        'UNSCOPED_DESTRUCTIVE_ACTION'
    )]
    [string[]]$Scope,

    [string]$EvidencePath
)

$ErrorActionPreference = 'Stop'

$testingRoot = Split-Path -Parent $PSCommandPath
$approvalPath = Join-Path $testingRoot 'STORY_EXECUTION_APPROVALS.json'
$storyPath = Join-Path $testingRoot "cases/ready/$StoryId.md"

if (-not (Test-Path -LiteralPath $storyPath)) {
    throw "$StoryId is not in the approved Ready test pack."
}

$ledger = Get-Content -LiteralPath $approvalPath -Raw | ConvertFrom-Json
$storyApproval = $ledger.stories.PSObject.Properties[$StoryId].Value
if ($null -eq $storyApproval -or $storyApproval.status -ne 'APPROVED') {
    throw "$StoryId has no active execution approval."
}

$campaignDenied = @($ledger.campaign.prohibitedScopes)
$storyAllowed = @($storyApproval.allowedScopes)
$deniedRequested = @($Scope | Where-Object { $campaignDenied -contains $_ })
$unapprovedRequested = @($Scope | Where-Object { $storyAllowed -notcontains $_ })

if ($deniedRequested.Count -gt 0) {
    throw "$StoryId requires separate explicit approval for: $($deniedRequested -join ', ')."
}
if ($unapprovedRequested.Count -gt 0) {
    throw "$StoryId approval does not include: $($unapprovedRequested -join ', ')."
}

$result = [ordered]@{
    schemaVersion = 1
    storyId = $StoryId
    decision = 'APPROVED'
    checkedAt = (Get-Date).ToString('o')
    approvalRecordedAt = $storyApproval.approvedAt
    approvedBy = $storyApproval.approvedBy
    requestedScopes = @($Scope)
    storySpecification = (Resolve-Path -LiteralPath $storyPath).Path
    conditions = @($storyApproval.conditions)
}

$json = $result | ConvertTo-Json -Depth 6
if ($EvidencePath) {
    $resolvedEvidencePath = if ([System.IO.Path]::IsPathRooted($EvidencePath)) {
        $EvidencePath
    } else {
        Join-Path (Get-Location) $EvidencePath
    }
    $parent = Split-Path -Parent $resolvedEvidencePath
    if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    Set-Content -LiteralPath $resolvedEvidencePath -Value $json -Encoding utf8
}

$json
