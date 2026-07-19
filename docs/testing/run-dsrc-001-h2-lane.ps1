[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8099",
    [string]$AdminUser = "admin",
    [Parameter(Mandatory = $true)][string]$AdminPassword,
    [string]$MetadataDbHost = "localhost",
    [int]$MetadataDbPort = 5433,
    [string]$MetadataDbName = "forgetdm",
    [string]$MetadataDbUser = "forgetdm",
    [Parameter(Mandatory = $true)][string]$MetadataDbPassword,
    [string]$Psql = "C:\Program Files\PostgreSQL\17\bin\psql.exe",
    [string]$EvidencePath
)

$ErrorActionPreference = "Stop"
$suffix = (Get-Date).ToUniversalTime().ToString("yyyyMMddHHmmss")
$runtime = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..\target\test-runtime")).Path
$dbName = "dsrc001-h2-$suffix"
$dbBase = (Join-Path $runtime $dbName).Replace("\", "/")
$jdbcUrl = "jdbc:h2:file:$dbBase;MODE=PostgreSQL;DATABASE_TO_UPPER=true"
$h2Password = "dsrc001-test"
$results = [System.Collections.Generic.List[object]]::new()
$createdDataSourceId = $null
$createdJobId = $null
$oldPgPassword = $env:PGPASSWORD

function Add-Result {
    param([string]$Case, [bool]$Passed, [string]$Detail)
    $script:results.Add([pscustomobject]@{
        lane = "H2"
        case = $Case
        result = if ($Passed) { "PASS" } else { "FAIL" }
        detail = $Detail
    })
    Write-Host ("[{0}] H2 {1} - {2}" -f $(if ($Passed) { "PASS" } else { "FAIL" }), $Case, $Detail)
}

function Convert-Body {
    param([string]$Content)
    if ([string]::IsNullOrWhiteSpace($Content)) { return $null }
    try { return $Content | ConvertFrom-Json } catch { return $Content }
}

function Invoke-Api {
    param([string]$Method, [string]$Path, [object]$Body)
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

function Wait-Discovery {
    param([string]$JobId, [int]$TimeoutSeconds = 90)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        Start-Sleep -Milliseconds 400
        $snapshot = (Invoke-Api GET "/api/discovery/scan-jobs/$JobId" $null).Body
    } while ($null -ne $snapshot -and $snapshot.status -in @("PENDING", "RUNNING", "CANCEL_REQUESTED") -and (Get-Date) -lt $deadline)
    return $snapshot
}

function Wait-Provision {
    param([long]$JobId, [int]$TimeoutSeconds = 45)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        Start-Sleep -Milliseconds 350
        $job = (Invoke-Api GET "/api/jobs/$JobId" $null).Body
    } while ($null -ne $job -and $job.status -in @("PENDING", "RUNNING", "CANCEL_REQUESTED") -and (Get-Date) -lt $deadline)
    return $job
}

if (-not (Test-Path -LiteralPath $Psql)) { throw "psql was not found at $Psql" }
$h2Jar = Get-ChildItem -LiteralPath "$HOME\.m2\repository\com\h2database\h2" -Recurse -Filter "h2-*.jar" |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
if (-not $h2Jar) { throw "The H2 JDBC jar is not available in the Maven repository" }

try {
    & java -cp $h2Jar org.h2.tools.Shell -url $jdbcUrl -user sa -password $h2Password -sql `
        "CREATE SCHEMA IF NOT EXISTS APP; CREATE TABLE IF NOT EXISTS APP.CUSTOMERS(ID BIGINT PRIMARY KEY, EMAIL VARCHAR(128)); MERGE INTO APP.CUSTOMERS KEY(ID) VALUES(1, 'h2@example.test');" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "The H2 fixture could not be initialized" }

    $login = @{ username = $AdminUser; password = $AdminPassword } | ConvertTo-Json -Compress
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/auth/login" -ContentType "application/json" `
        -Body $login -SessionVariable session | Out-Null

    $create = Invoke-Api POST "/api/datasources" @{
        name = "DSRC-H2-$suffix"
        kind = "H2"
        jdbcUrl = $jdbcUrl
        username = "sa"
        password = $h2Password
        role = "BOTH"
        environment = "TEST"
        tags = "DSRC-001,h2,disposable"
    }
    $createdDataSourceId = [long]$create.Body.id
    $secretSafe = $create.Status -eq 200 -and $null -ne $create.Body.version -and
        $create.Raw -notmatch '"password"' -and $create.Raw -notmatch [Regex]::Escape($h2Password)
    Add-Result "create-version-redaction" $secretSafe "Create returned a version token without returning the fixture credential"

    $connection = Invoke-Api POST "/api/datasources/$createdDataSourceId/test" $null
    Add-Result "connection" ($connection.Status -eq 200 -and $connection.Body.ok -eq $true) "ForgeTDM opened the embedded H2 database"

    $schemas = Invoke-Api GET "/api/datasources/$createdDataSourceId/schemas" $null
    $schemaFound = $schemas.Status -eq 200 -and @($schemas.Body | Where-Object { [string]$_.schema -ieq "APP" }).Count -gt 0
    Add-Result "schema-browse" $schemaFound "APP was returned by JDBC metadata"

    $tables = Invoke-Api GET "/api/datasources/$createdDataSourceId/tables?schema=APP" $null
    $table = @($tables.Body | Where-Object { [string]$_.table -ieq "CUSTOMERS" } | Select-Object -First 1)
    $tableFound = $tables.Status -eq 200 -and $table.Count -eq 1
    Add-Result "table-browse" $tableFound "APP.CUSTOMERS was returned by JDBC metadata"

    $columns = Invoke-Api GET "/api/datasources/$createdDataSourceId/tables/CUSTOMERS/columns?schema=APP" $null
    Add-Result "column-browse" ($columns.Status -eq 200 -and @($columns.Body).Count -ge 2) "ID and EMAIL column metadata was returned"

    $sessionA = (Invoke-Api GET "/api/datasources/$createdDataSourceId" $null).Body
    $sessionB = (Invoke-Api GET "/api/datasources/$createdDataSourceId" $null).Body
    $saveA = Invoke-Api PUT "/api/datasources/$createdDataSourceId" (Update-Payload $sessionA ([long]$sessionA.version) "DSRC-001,h2,session-a")
    $saveB = Invoke-Api PUT "/api/datasources/$createdDataSourceId" (Update-Payload $sessionB ([long]$sessionB.version) "DSRC-001,h2,session-b-stale")
    $after = (Invoke-Api GET "/api/datasources/$createdDataSourceId" $null).Body
    $safeConflict = $saveB.Raw -notmatch '(?i)insert into|select |jdbc:|password|exception'
    Add-Result "optimistic-lock" ($saveA.Status -eq 200 -and $saveB.Status -eq 409 -and [string]$after.tags -match 'session-a$' -and $safeConflict) `
        "A stale editor received sanitized 409 without overwriting session A"

    $retest = Invoke-Api POST "/api/datasources/$createdDataSourceId/test" $null
    Add-Result "credential-retention" ($retest.Status -eq 200 -and $retest.Body.ok -eq $true) "Metadata-only edit retained the credential"

    $scan = Invoke-Api POST "/api/discovery/scan-jobs/$createdDataSourceId`?schema=APP" @{
        piiTypes = @("EMAIL")
        tableNames = @("CUSTOMERS")
    }
    $scanPassed = $scan.Status -eq 200
    if ($scanPassed) {
        $snapshot = Wait-Discovery ([string]$scan.Body.jobId)
        $scanPassed = $null -ne $snapshot -and $snapshot.status -eq "COMPLETED" -and [int]$snapshot.completedTables -eq 1
    }
    Add-Result "source-discovery" $scanPassed "BOTH completed a one-table H2 discovery scan"

    $target = Invoke-Api POST "/api/jobs" @{
        name = "DSRC H2 target $suffix"
        jobType = "SYNTHETIC_LOAD"
        targetId = $createdDataSourceId
    }
    $targetPassed = $target.Status -eq 200
    if ($targetPassed) {
        $createdJobId = [long]$target.Body.id
        $job = Wait-Provision $createdJobId
        $targetPassed = $null -ne $job -and $job.status -in @("FAILED", "COMPLETED")
    }
    Add-Result "target-preflight" $targetPassed "BOTH passed target-role validation and entered execution"
} finally {
    if ($null -ne $createdJobId) {
        try { Invoke-Api DELETE "/api/jobs/$createdJobId" $null | Out-Null } catch { }
    }
    if ($null -ne $createdDataSourceId) {
        try {
            $env:PGPASSWORD = $MetadataDbPassword
            & $Psql -h $MetadataDbHost -p $MetadataDbPort -U $MetadataDbUser -d $MetadataDbName -q -c `
                "delete from classifications where data_source_id = $createdDataSourceId;" | Out-Null
        } catch { }
        try { Invoke-Api DELETE "/api/datasources/$createdDataSourceId" $null | Out-Null } catch { }
    }
    $env:PGPASSWORD = $oldPgPassword

    $runtimeResolved = (Resolve-Path -LiteralPath $runtime).Path
    foreach ($file in Get-ChildItem -LiteralPath $runtime -Filter "$dbName*" -File -ErrorAction SilentlyContinue) {
        $resolved = $file.FullName
        if ($resolved.StartsWith($runtimeResolved, [System.StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $resolved -Force
        }
    }
}

$passed = @($results | Where-Object result -eq "PASS").Count
$failed = @($results | Where-Object result -eq "FAIL").Count
$evidence = [ordered]@{
    story = "DSRC-001"
    scope = "H2 embedded live JDBC lane"
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
