param(
    [string]$BaseUrl = 'http://localhost:8088'
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$evidenceDir = Join-Path $repoRoot 'docs\guidewire-acceptance\evidence'
$manifest = @(Import-Csv (Join-Path $repoRoot 'docs\guidewire-acceptance\ddl\guidewire-150-table-manifest.csv'))
New-Item -ItemType Directory -Path $evidenceDir -Force | Out-Null

function Invoke-Forge([Microsoft.PowerShell.Commands.WebRequestSession]$Session, [string]$Method,
                      [string]$Path, $Body = $null) {
    $args = @{ Method = $Method; Uri = "$BaseUrl$Path"; WebSession = $Session; ErrorAction = 'Stop' }
    if ($null -ne $Body) {
        $args.ContentType = 'application/json'
        $args.Body = $Body | ConvertTo-Json -Depth 30 -Compress
    }
    try {
        $result = Invoke-RestMethod @args
        if ($result -is [System.Array]) {
            foreach ($item in $result) { Write-Output $item }
            return
        }
        return $result
    }
    catch {
        $detail = $_.Exception.Message
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $detail = $_.ErrorDetails.Message }
        throw "$Method $Path failed: $detail"
    }
}

function Save-Evidence([string]$Name, $Value) {
    $path = Join-Path $evidenceDir (($Name -replace '[^A-Za-z0-9._-]', '-') + '.json')
    $Value | ConvertTo-Json -Depth 50 | Set-Content -LiteralPath $path -Encoding UTF8
    return $path
}

function Wait-Scan($Session, [string]$JobId) {
    $deadline = (Get-Date).AddMinutes(20)
    do {
        $job = Invoke-Forge $Session GET "/api/discovery/scan-jobs/$JobId"
        Write-Host ("{0,3}%  {1,-10}  {2}/{3} tables  {4}.{5}" -f
            [int]$job.percent, $job.status, [int]$job.completedTables, [int]$job.totalTables,
            [string]$job.currentTable, [string]$job.currentColumn)
        if ($job.status -in @('COMPLETED', 'FAILED')) { break }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    if ($job.status -ne 'COMPLETED') { throw "Discovery $JobId ended as $($job.status): $($job.error)" }
    return $job
}

$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
Invoke-Forge $session POST '/api/auth/login' @{ username = 'admin'; password = 'admin123' } | Out-Null
$sources = @(Invoke-Forge $session GET '/api/datasources')
$policies = @(Invoke-Forge $session GET '/api/policies')

$definitions = @(
    @{ application = 'Policy Operations'; connection = 'GW-Policy-Oracle'; schema = 'BE_CARDS'; policy = 'GW-Policy-Masking-20260716-v5' },
    @{ application = 'Billing and Contacts'; connection = 'GW-Billing-PostgreSQL'; schema = 'gw_billing_src'; policy = 'GW-Billing-Masking-20260716-v5' }
)

$summary = @()
foreach ($definition in $definitions) {
    $source = $sources | Where-Object name -eq $definition.connection | Select-Object -First 1
    if (-not $source) { throw "GW source not found: $($definition.connection)" }
    $tableNames = @($manifest | Where-Object application -eq $definition.application |
        ForEach-Object { $_.source_table })
    if ($tableNames.Count -ne 50) { throw "$($definition.application) manifest has $($tableNames.Count) source tables" }
    Write-Host "`n== Scan $($definition.connection) / $($definition.schema) ==" -ForegroundColor Cyan
    $encodedSchema = [uri]::EscapeDataString($definition.schema)
    # Acceptance runs intentionally re-evaluate machine-approved fixtures after detector changes.
    # Normal product rescans continue preserving analyst APPROVED/REJECTED decisions.
    $existing = @(Invoke-Forge $session GET "/api/discovery/results/$($source.id)?schema=$encodedSchema")
    $reopen = @($existing | Where-Object { $tableNames -contains $_.tableName })
    if ($reopen.Count -gt 0) {
        Invoke-Forge $session POST '/api/discovery/classifications/bulk' @{
            ids = @($reopen | ForEach-Object { $_.id })
            status = 'SUGGESTED'
        } | Out-Null
    }
    $started = Invoke-Forge $session POST "/api/discovery/scan-jobs/$($source.id)?schema=$encodedSchema" @{
        piiTypes = @()
        tableNames = $tableNames
    }
    $completed = Wait-Scan $session ([string]$started.jobId)
    Save-Evidence "GW-discovery-$($definition.connection)" $completed | Out-Null

    $allFindings = @(Invoke-Forge $session GET "/api/discovery/results/$($source.id)?schema=$encodedSchema")
    $findings = @($allFindings | Where-Object { $tableNames -contains $_.tableName })
    $outside = @($allFindings | Where-Object { $tableNames -notcontains $_.tableName })
    if ($outside.Count -gt 0) {
        Invoke-Forge $session POST '/api/discovery/classifications/bulk' @{
            ids = @($outside | ForEach-Object { $_.id })
            status = 'REJECTED'
        } | Out-Null
    }
    if ($findings.Count -gt 0) {
        $approval = Invoke-Forge $session POST '/api/discovery/classifications/bulk' @{
            ids = @($findings | ForEach-Object { $_.id })
            status = 'APPROVED'
        }
    } else {
        throw "No PII findings for $($definition.connection) / $($definition.schema)"
    }
    $policy = $policies | Where-Object name -eq $definition.policy | Select-Object -First 1
    if ($policy) {
        Write-Host "Reusing immutable policy $($policy.name) (id $($policy.id))" -ForegroundColor DarkCyan
    } else {
        $policy = Invoke-Forge $session POST "/api/discovery/generate-policy/$($source.id)?schema=$encodedSchema" @{
            name = $definition.policy
        }
        $policies += $policy
    }
    $rules = @(Invoke-Forge $session GET "/api/policies/$($policy.id)/rules")
    $result = [ordered]@{
        connection = $definition.connection
        schema = $definition.schema
        scanJobId = $completed.jobId
        tables = [int]$completed.totalTables
        findings = $findings.Count
        approved = [int]$approval.count
        policy = $policy.name
        policyId = $policy.id
        rules = $rules.Count
        status = $completed.status
    }
    $summary += $result
}

$path = Save-Evidence 'GW-discovery-policy-summary' @{
    generatedAt = (Get-Date).ToUniversalTime().ToString('o')
    components = $summary
}
$summary | ForEach-Object { [pscustomobject]$_ } |
    Format-Table connection, schema, tables, findings, approved, policy, rules, status -AutoSize
Write-Host "Evidence: $path" -ForegroundColor Green
