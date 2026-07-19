[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8099",
    [string]$AdminUser = "admin",
    [Parameter(Mandatory = $true)]
    [string]$AdminPassword,
    [string]$DbHost = "localhost",
    [int]$DbPort = 5433,
    [string]$DbName = "forgetdm",
    [string]$DbUser = "forgetdm",
    [Parameter(Mandatory = $true)]
    [string]$DbPassword,
    [string]$Psql = "C:\Program Files\PostgreSQL\17\bin\psql.exe",
    [string]$EvidencePath
)

$ErrorActionPreference = "Stop"
$suffix = (Get-Date).ToUniversalTime().ToString("yyyyMMddHHmmss")
$schema = "dsrc001_$suffix"
$results = [System.Collections.Generic.List[object]]::new()
$createdDataSources = [System.Collections.Generic.List[long]]::new()
$createdJobId = $null
$oldPgPassword = $env:PGPASSWORD

function Add-Result {
    param([string]$Case, [bool]$Passed, [string]$Detail)
    $script:results.Add([pscustomobject]@{
        case = $Case
        result = if ($Passed) { "PASS" } else { "FAIL" }
        detail = $Detail
    })
    $color = if ($Passed) { "Green" } else { "Red" }
    Write-Host ("[{0}] {1} - {2}" -f $(if ($Passed) { "PASS" } else { "FAIL" }), $Case, $Detail) -ForegroundColor $color
}

function Convert-Body {
    param([string]$Content)
    if ([string]::IsNullOrWhiteSpace($Content)) { return $null }
    try { return $Content | ConvertFrom-Json } catch { return $Content }
}

function Invoke-Api {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        [object]$Body
    )
    $request = @{
        Method = $Method
        Uri = "$BaseUrl$Path"
        WebSession = $script:session
        UseBasicParsing = $true
    }
    if ($null -ne $Body) {
        $request.ContentType = "application/json"
        $request.Body = $Body | ConvertTo-Json -Depth 20 -Compress
    }
    try {
        $response = Invoke-WebRequest @request
        return [pscustomobject]@{
            Status = [int]$response.StatusCode
            Body = Convert-Body $response.Content
            Raw = [string]$response.Content
        }
    } catch {
        $status = 0
        $raw = ""
        if ($null -ne $_.Exception.Response) {
            $status = [int]$_.Exception.Response.StatusCode
            try {
                $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
                $raw = $reader.ReadToEnd()
                $reader.Dispose()
            } catch { $raw = [string]$_.ErrorDetails.Message }
        } else {
            $raw = [string]$_.Exception.Message
        }
        return [pscustomobject]@{ Status = $status; Body = Convert-Body $raw; Raw = $raw }
    }
}

function New-DataSource {
    param([string]$Name, [string]$Role, [string]$Kind, [string]$JdbcUrl, [string]$Username, [string]$Password)
    $response = Invoke-Api POST "/api/datasources" @{
        name = $Name
        kind = $Kind
        jdbcUrl = $JdbcUrl
        username = $Username
        password = $Password
        role = $Role
        environment = "TEST"
        tags = "DSRC-001,disposable"
    }
    if ($response.Status -ne 200) { throw "Create $Role data source failed ($($response.Status)): $($response.Raw)" }
    $script:createdDataSources.Add([long]$response.Body.id)
    return $response.Body
}

function Update-Payload {
    param([object]$Source, [long]$Version, [string]$Tags)
    return @{
        version = $Version
        name = [string]$Source.name
        kind = [string]$Source.kind
        jdbcUrl = [string]$Source.jdbcUrl
        username = [string]$Source.username
        role = [string]$Source.role
        environment = [string]$Source.environment
        tags = $Tags
    }
}

if (-not (Test-Path -LiteralPath $Psql)) { throw "psql was not found at $Psql" }

try {
    $loginBody = @{ username = $AdminUser; password = $AdminPassword } | ConvertTo-Json -Compress
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/auth/login" -ContentType "application/json" `
        -Body $loginBody -SessionVariable session | Out-Null

    $env:PGPASSWORD = $DbPassword
    $migration = & $Psql -h $DbHost -p $DbPort -U $DbUser -d $DbName -v ON_ERROR_STOP=1 -Atc `
        "select count(*) from flyway_schema_history where version='64' and success=true; select count(*) from information_schema.columns where table_schema='public' and table_name='data_sources' and column_name='lock_version' and is_nullable='NO';"
    if ($LASTEXITCODE -ne 0) { throw "Unable to verify V64" }
    $migrationValues = @($migration | Where-Object { $_ -match '^\d+$' })
    Add-Result "DSRC-001-07 migration" ($migrationValues.Count -ge 2 -and $migrationValues[0] -eq "1" -and $migrationValues[1] -eq "1") `
        "V64 succeeded and lock_version is non-null"

    & $Psql -h $DbHost -p $DbPort -U $DbUser -d $DbName -v ON_ERROR_STOP=1 -q -c `
        "create schema $schema; create table $schema.probe_customer(id bigint primary key, email varchar(120)); insert into $schema.probe_customer values (1, 'alpha@example.test'), (2, 'beta@example.test');"
    if ($LASTEXITCODE -ne 0) { throw "Unable to create the live discovery fixture" }

    $source = New-DataSource "DSRC-SOURCE-$suffix" "SOURCE" "H2" "jdbc:h2:mem:dsrc_source_$suffix;DB_CLOSE_DELAY=-1" "sa" ""
    $target = New-DataSource "DSRC-TARGET-$suffix" "TARGET" "H2" "jdbc:h2:mem:dsrc_target_$suffix;DB_CLOSE_DELAY=-1" "sa" ""
    $both = New-DataSource "DSRC-BOTH-$suffix" "BOTH" "POSTGRES" "jdbc:postgresql://${DbHost}:$DbPort/$DbName" $DbUser $DbPassword

    Add-Result "DSRC-001-01 version token" ($null -ne $source.version) "Create response exposes a non-secret version token"

    $sessionA = (Invoke-Api GET "/api/datasources/$($source.id)").Body
    $sessionB = (Invoke-Api GET "/api/datasources/$($source.id)").Body
    $saveA = Invoke-Api PUT "/api/datasources/$($source.id)" (Update-Payload $sessionA ([long]$sessionA.version) "DSRC-001,session-a")
    $saveB = Invoke-Api PUT "/api/datasources/$($source.id)" (Update-Payload $sessionB ([long]$sessionB.version) "DSRC-001,session-b-stale")
    $after = (Invoke-Api GET "/api/datasources/$($source.id)").Body
    $staleSafe = $saveB.Raw -notmatch '(?i)insert into|select |jdbc:|password|forgetdm'
    Add-Result "DSRC-001-07 stale update" `
        ($saveA.Status -eq 200 -and $saveB.Status -eq 409 -and $after.tags -eq "DSRC-001,session-a" -and $staleSafe) `
        "Session B received sanitized 409 and did not overwrite session A"

    $missingPayload = Update-Payload $after ([long]$after.version) "DSRC-001,missing-version"
    $missingPayload.Remove("version")
    $missing = Invoke-Api PUT "/api/datasources/$($source.id)" $missingPayload
    Add-Result "DSRC-001-07 missing version" ($missing.Status -eq 409) "Versionless update was rejected before save"

    $scanCountBefore = @((Invoke-Api GET "/api/discovery/scan-jobs?dataSourceId=$($target.id)&schema=PUBLIC").Body).Count
    $targetScan = Invoke-Api POST "/api/discovery/scan-jobs/$($target.id)?schema=PUBLIC" @{ piiTypes = @(); tableNames = @() }
    $scanCountAfter = @((Invoke-Api GET "/api/discovery/scan-jobs?dataSourceId=$($target.id)&schema=PUBLIC").Body).Count
    Add-Result "DSRC-001-04 target-only discovery" `
        ($targetScan.Status -eq 400 -and $targetScan.Raw -match 'not source-capable' -and $scanCountBefore -eq $scanCountAfter) `
        "Rejected before a discovery job was created"

    $jobsBefore = @((Invoke-Api GET "/api/jobs").Body).Count
    $badTarget = Invoke-Api POST "/api/jobs" @{
        name = "DSRC invalid target $suffix"
        jobType = "MASK_COPY"
        sourceId = [long]$source.id
        targetId = [long]$source.id
    }
    $jobsAfterBadTarget = @((Invoke-Api GET "/api/jobs").Body).Count
    Add-Result "DSRC-001-04 source-only provision target" `
        ($badTarget.Status -eq 400 -and $badTarget.Raw -match 'not target-capable' -and $jobsBefore -eq $jobsAfterBadTarget) `
        "Rejected before a provisioning job was persisted or queued"

    $badSource = Invoke-Api POST "/api/jobs" @{
        name = "DSRC invalid source $suffix"
        jobType = "MASK_COPY"
        sourceId = [long]$target.id
        targetId = [long]$target.id
    }
    $jobsAfterBadSource = @((Invoke-Api GET "/api/jobs").Body).Count
    Add-Result "DSRC-001-04 target-only provision source" `
        ($badSource.Status -eq 400 -and $badSource.Raw -match 'not source-capable' -and $jobsBefore -eq $jobsAfterBadSource) `
        "Rejected before a provisioning job was persisted or queued"

    $encodedSchema = [Uri]::EscapeDataString($schema)
    $bothScan = Invoke-Api POST "/api/discovery/scan-jobs/$($both.id)?schema=$encodedSchema" `
        @{ piiTypes = @("EMAIL"); tableNames = @("probe_customer") }
    $bothScanPassed = $bothScan.Status -eq 200
    if ($bothScanPassed) {
        $scanId = [string]$bothScan.Body.jobId
        $deadline = (Get-Date).AddSeconds(45)
        do {
            Start-Sleep -Milliseconds 400
            $snapshot = (Invoke-Api GET "/api/discovery/scan-jobs/$scanId").Body
        } while ($snapshot.status -in @("PENDING", "RUNNING", "CANCEL_REQUESTED") -and (Get-Date) -lt $deadline)
        $bothScanPassed = $snapshot.status -eq "COMPLETED" -and [int]$snapshot.completedTables -eq 1
    }
    Add-Result "DSRC-001-04 BOTH as source" $bothScanPassed "BOTH completed a live one-table discovery scan"

    $bothTarget = Invoke-Api POST "/api/jobs" @{
        name = "DSRC BOTH target $suffix"
        jobType = "SYNTHETIC_LOAD"
        targetId = [long]$both.id
    }
    $bothTargetPassed = $bothTarget.Status -eq 200
    if ($bothTargetPassed) {
        $createdJobId = [long]$bothTarget.Body.id
        $deadline = (Get-Date).AddSeconds(30)
        do {
            Start-Sleep -Milliseconds 300
            $job = (Invoke-Api GET "/api/jobs/$createdJobId").Body
        } while ($job.status -in @("PENDING", "RUNNING", "CANCEL_REQUESTED") -and (Get-Date) -lt $deadline)
        $bothTargetPassed = $job.status -in @("FAILED", "COMPLETED")
    }
    Add-Result "DSRC-001-04 BOTH as target" $bothTargetPassed "BOTH passed target preflight and entered execution"
} finally {
    if ($null -ne $createdJobId) { Invoke-Api DELETE "/api/jobs/$createdJobId" | Out-Null }
    if ($createdDataSources.Count -gt 0) {
        $ids = ($createdDataSources | ForEach-Object { [string]$_ }) -join ','
        try {
            & $Psql -h $DbHost -p $DbPort -U $DbUser -d $DbName -q -c `
                "delete from classifications where data_source_id in ($ids);" | Out-Null
        } catch { }
        foreach ($id in $createdDataSources) { try { Invoke-Api DELETE "/api/datasources/$id" | Out-Null } catch { } }
    }
    try { & $Psql -h $DbHost -p $DbPort -U $DbUser -d $DbName -q -c "drop schema if exists $schema cascade;" | Out-Null } catch { }
    $env:PGPASSWORD = $oldPgPassword
}

$passed = @($results | Where-Object result -eq "PASS").Count
$failed = @($results | Where-Object result -eq "FAIL").Count
$evidence = [ordered]@{
    story = "DSRC-001"
    executedAt = (Get-Date).ToUniversalTime().ToString("o")
    baseUrl = $BaseUrl
    passed = $passed
    failed = $failed
    results = $results
}
$json = $evidence | ConvertTo-Json -Depth 10
if (-not [string]::IsNullOrWhiteSpace($EvidencePath)) {
    $parent = Split-Path -Parent $EvidencePath
    if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    [System.IO.File]::WriteAllText($EvidencePath, $json, (New-Object System.Text.UTF8Encoding($false)))
}
$json
if ($failed -gt 0) { exit 1 }
