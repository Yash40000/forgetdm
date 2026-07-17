param(
    [int]$Start = 0,
    [int]$Take = 250,
    [string]$Repository = "Yash40000/forgetdm"
)

$ErrorActionPreference = "Stop"
$git = "C:\Program Files\Git\cmd\git.exe"
$catalog = Join-Path $PSScriptRoot "..\..\docs\testing\FORGETDM_TEST_CASE_CATALOG.csv"
$readyIds = @(
    "AUTH-001",
    "AUTH-003",
    "RBAC-001",
    "RBAC-002",
    "AUD-001",
    "DSRC-001",
    "DSRC-002",
    "DSRC-003",
    "DISC-006",
    "DISC-007"
)

if (-not (Test-Path -LiteralPath $catalog)) {
    throw "Test catalog not found: $catalog"
}

$credentialInput = "protocol=https`nhost=github.com`n`n"
$credential = @{}
@($credentialInput | & $git credential fill) | ForEach-Object {
    if ($_ -match '^([^=]+)=(.*)$') {
        $credential[$matches[1]] = $matches[2]
    }
}
if (-not $credential.password) {
    throw "GitHub credential unavailable. Authenticate Git for github.com first."
}

$headers = @{
    Authorization = "Bearer $($credential.password)"
    Accept = "application/vnd.github+json"
    "X-GitHub-Api-Version" = "2022-11-28"
    "User-Agent" = "ForgeTDM-story-manager"
}
$baseUrl = "https://api.github.com/repos/$Repository"

function Invoke-GitHub {
    param(
        [Parameter(Mandatory)] [string]$Method,
        [Parameter(Mandatory)] [string]$Uri,
        [object]$Body = $null
    )

    for ($attempt = 1; $attempt -le 5; $attempt++) {
        try {
            if ($null -eq $Body) {
                return Invoke-RestMethod -Method $Method -Uri $Uri -Headers $headers
            }
            return Invoke-RestMethod -Method $Method -Uri $Uri -Headers $headers `
                -ContentType "application/json" -Body ($Body | ConvertTo-Json -Depth 10)
        } catch {
            $statusCode = $_.Exception.Response.StatusCode.value__
            if ($statusCode -eq 422 -and $_.ErrorDetails.Message -match 'already_exists') {
                return $null
            }
            if ($attempt -eq 5) { throw }
            Start-Sleep -Seconds ([math]::Min(15, 2 * $attempt))
        }
    }
}

function Get-AreaLabel {
    param([Parameter(Mandatory)] [string]$Area)
    $slug = ($Area.ToLowerInvariant() -replace '[^a-z0-9]+', '-').Trim('-')
    return "area:$slug"
}

$allRows = @(Import-Csv -LiteralPath $catalog)
$duplicates = @($allRows | Group-Object Test_ID | Where-Object Count -gt 1)
if ($duplicates.Count -gt 0) {
    throw "Catalog contains duplicate test IDs: $($duplicates.Name -join ', ')"
}

$requiredLabels = @(
    @{ name = "type:test-story"; color = "5319e7"; description = "Traceable ForgeTDM test story" },
    @{ name = "status:ready"; color = "0e8a16"; description = "Ready to start" },
    @{ name = "status:in-progress"; color = "1d76db"; description = "Test execution in progress" },
    @{ name = "status:blocked"; color = "b60205"; description = "Blocked by a defect or environment dependency" },
    @{ name = "status:done"; color = "006b75"; description = "Passed with reviewed evidence" },
    @{ name = "status:backlog"; color = "d4c5f9"; description = "Prioritized backlog" },
    @{ name = "priority:p0"; color = "b60205"; description = "Release-blocking priority" },
    @{ name = "priority:p1"; color = "fbca04"; description = "Important non-blocking priority" }
)
foreach ($area in ($allRows.Area | Sort-Object -Unique)) {
    $requiredLabels += @{
        name = Get-AreaLabel -Area $area
        color = "1d76db"
        description = "ForgeTDM area: $area"
    }
}

$existingLabels = @{}
# Do not wrap Invoke-RestMethod arrays in @(...). PowerShell treats the function's
# returned array as one nested item in that form, which breaks idempotent lookups.
$labelsResponse = Invoke-GitHub -Method GET -Uri "$baseUrl/labels?per_page=100"
foreach ($label in $labelsResponse) {
    if ($label.name) { $existingLabels[$label.name.ToLowerInvariant()] = $true }
}
foreach ($label in $requiredLabels) {
    if (-not $existingLabels.ContainsKey($label.name.ToLowerInvariant())) {
        [void](Invoke-GitHub -Method POST -Uri "$baseUrl/labels" -Body $label)
    }
}

$existingIssues = @{}
for ($page = 1; $page -le 10; $page++) {
    $issues = Invoke-GitHub -Method GET -Uri "$baseUrl/issues?state=all&per_page=100&page=$page"
    foreach ($issue in $issues) {
        if ($null -ne $issue -and $issue.title -and -not $issue.pull_request) {
            $existingIssues[$issue.title] = $issue
        }
    }
    if ($issues.Count -lt 100) { break }
}

$rows = @($allRows | Select-Object -Skip $Start -First $Take)
$created = 0
$skipped = 0
foreach ($row in $rows) {
    $title = "[$($row.Test_ID)] $($row.Scenario)"
    if ($title.Length -gt 240) {
        $title = $title.Substring(0, 237) + "..."
    }
    if ($existingIssues.ContainsKey($title)) {
        $skipped++
        continue
    }

    $statusLabel = if ($readyIds -contains $row.Test_ID) { "status:ready" } else { "status:backlog" }
    $body = @"
## User story

As a ForgeTDM user or operator, I need **$($row.Scenario)** so that the product behavior is safe, predictable, and independently verifiable.

## Scope

- Test ID: ``$($row.Test_ID)``
- Area: $($row.Area)
- Priority: ``$($row.Priority)``
- Required lane: $($row.Required_Lane)
- Initial status: ``$statusLabel``

## Acceptance criteria

- [ ] The stated scenario passes in the required lane with an immutable ForgeTDM build.
- [ ] Happy-path, boundary, invalid-input, permission, timeout, and retry behavior are covered where applicable.
- [ ] UI and API produce consistent results where both surfaces exist.
- [ ] Database, file, or target-system results are validated independently rather than inferred from job status.
- [ ] Audit, actor, correlation, error sanitization, and clear-PII leakage checks pass where applicable.
- [ ] Stable behavior is automated and the affected regression suite passes after any fix.

## Required evidence

$($row.Primary_Evidence)

Record build commit, environment, connector/driver versions, sanitized fixture, expected versus actual result, run/job IDs, counts/checksums, defects, rerun, and reviewer.

## Definition of done

- [ ] All acceptance criteria pass.
- [ ] No open S0/S1 defect; any S2 exception has formal approval and expiry.
- [ ] Evidence is linked without secrets, credentials, customer data, or clear PII.
- [ ] The story is relabeled ``status:done`` only after regression and evidence review.

Catalog: https://github.com/$Repository/blob/main/docs/testing/FORGETDM_TEST_CASE_CATALOG.csv
Master plan: https://github.com/$Repository/blob/main/docs/testing/FORGETDM_MASTER_TEST_PLAN.md
"@
    $payload = @{
        title = $title
        body = $body
        labels = @(
            "type:test-story",
            $statusLabel,
            "priority:$($row.Priority.ToLowerInvariant())",
            (Get-AreaLabel -Area $row.Area)
        )
    }
    $issue = Invoke-GitHub -Method POST -Uri "$baseUrl/issues" -Body $payload
    $existingIssues[$title] = $issue
    $created++
}

[pscustomobject]@{
    CatalogStories = $allRows.Count
    Range = "$Start-$($Start + $rows.Count - 1)"
    Created = $created
    Skipped = $skipped
    ReadyInRange = @($rows | Where-Object { $readyIds -contains $_.Test_ID }).Count
    BacklogInRange = @($rows | Where-Object { $readyIds -notcontains $_.Test_ID }).Count
}
