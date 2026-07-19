[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8099",
    [string]$AdminUser = "admin",
    [Parameter(Mandatory = $true)]
    [string]$AdminPassword,
    [string]$MetadataDbHost = "localhost",
    [int]$MetadataDbPort = 5433,
    [string]$MetadataDbName = "forgetdm",
    [string]$MetadataDbUser = "forgetdm",
    [Parameter(Mandatory = $true)]
    [string]$MetadataDbPassword,
    [string]$Psql = "C:\Program Files\PostgreSQL\17\bin\psql.exe",
    [string]$OracleJdbcUrl = "jdbc:oracle:thin:@localhost:1521:XE",
    [string]$OracleUser = "BE_CARDS",
    [Parameter(Mandatory = $true)]
    [string]$OraclePassword,
    [string]$OracleSchema = "BE_CARDS",
    [string]$OracleTable = "CARD_CUSTOMERS",
    [string]$MySqlJdbcUrl = "jdbc:mysql://localhost:3306/digital_engagement?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
    [string]$MySqlUser = "be_digital",
    [Parameter(Mandatory = $true)]
    [string]$MySqlPassword,
    [string]$MySqlSchema = "digital_engagement",
    [string]$MySqlTable = "digital_customers",
    [string]$EvidencePath
)

$ErrorActionPreference = "Stop"
$suffix = (Get-Date).ToUniversalTime().ToString("yyyyMMddHHmmss")
$results = [System.Collections.Generic.List[object]]::new()
$createdDataSources = [System.Collections.Generic.List[long]]::new()
$createdJobs = [System.Collections.Generic.List[long]]::new()
$oldPgPassword = $env:PGPASSWORD

function Add-Result {
    param([string]$Lane, [string]$Case, [bool]$Passed, [string]$Detail)
    $script:results.Add([pscustomobject]@{
        lane = $Lane
        case = $Case
        result = if ($Passed) { "PASS" } else { "FAIL" }
        detail = $Detail
    })
    $color = if ($Passed) { "Green" } else { "Red" }
    Write-Host ("[{0}] {1} {2} - {3}" -f $(if ($Passed) { "PASS" } else { "FAIL" }), $Lane, $Case, $Detail) -ForegroundColor $color
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
    param([object]$Lane)
    $response = Invoke-Api POST "/api/datasources" @{
        name = "DSRC-$($Lane.Name)-$suffix"
        kind = $Lane.Kind
        jdbcUrl = $Lane.JdbcUrl
        username = $Lane.Username
        password = $Lane.Password
        role = "BOTH"
        environment = "TEST"
        tags = "DSRC-001,$($Lane.Name.ToLowerInvariant()),disposable"
    }
    if ($response.Status -ne 200) {
        throw "The $($Lane.Name) data source could not be created (HTTP $($response.Status))."
    }
    $script:createdDataSources.Add([long]$response.Body.id)
    return $response
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
    param([string]$JobId, [int]$TimeoutSeconds = 120)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        Start-Sleep -Milliseconds 500
        $snapshot = (Invoke-Api GET "/api/discovery/scan-jobs/$JobId").Body
    } while ($null -ne $snapshot -and $snapshot.status -in @("PENDING", "RUNNING", "CANCEL_REQUESTED") -and (Get-Date) -lt $deadline)
    return $snapshot
}

function Wait-Provision {
    param([long]$JobId, [int]$TimeoutSeconds = 45)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        Start-Sleep -Milliseconds 350
        $job = (Invoke-Api GET "/api/jobs/$JobId").Body
    } while ($null -ne $job -and $job.status -in @("PENDING", "RUNNING", "CANCEL_REQUESTED") -and (Get-Date) -lt $deadline)
    return $job
}

function Test-Lane {
    param([object]$Lane)

    $laneName = [string]$Lane.Name
    try {
        $create = New-DataSource $Lane
        $source = $create.Body
        $secretSafe = $create.Raw -notmatch '"password"' -and $create.Raw -notmatch [Regex]::Escape([string]$Lane.Password)
        Add-Result $laneName "create-version-redaction" ($null -ne $source.version -and $secretSafe) `
            "Create returned a version token without returning the connector secret"

        $connection = Invoke-Api POST "/api/datasources/$($source.id)/test" $null
        Add-Result $laneName "connection" ($connection.Status -eq 200 -and $connection.Body.ok -eq $true) `
            "ForgeTDM opened a real JDBC connection"

        $encodedSchema = [Uri]::EscapeDataString([string]$Lane.Schema)
        $schemas = Invoke-Api GET "/api/datasources/$($source.id)/schemas" $null
        $schemaFound = $schemas.Status -eq 200 -and @($schemas.Body | Where-Object { [string]$_.schema -ieq [string]$Lane.Schema }).Count -gt 0
        Add-Result $laneName "schema-browse" $schemaFound "Expected application schema was returned by JDBC metadata"

        $tables = Invoke-Api GET "/api/datasources/$($source.id)/tables?schema=$encodedSchema" $null
        $tableRow = @($tables.Body | Where-Object { [string]$_.table -ieq [string]$Lane.Table } | Select-Object -First 1)
        $tableFound = $tables.Status -eq 200 -and $tableRow.Count -eq 1
        Add-Result $laneName "table-browse" $tableFound "Expected application table was returned by JDBC metadata"

        $actualTable = if ($tableFound) { [string]$tableRow[0].table } else { [string]$Lane.Table }
        $encodedTable = [Uri]::EscapeDataString($actualTable)
        $columns = Invoke-Api GET "/api/datasources/$($source.id)/tables/$encodedTable/columns?schema=$encodedSchema" $null
        Add-Result $laneName "column-browse" ($columns.Status -eq 200 -and @($columns.Body).Count -gt 0) `
            "Column metadata was returned for the selected table"

        $sessionA = (Invoke-Api GET "/api/datasources/$($source.id)" $null).Body
        $sessionB = (Invoke-Api GET "/api/datasources/$($source.id)" $null).Body
        $saveA = Invoke-Api PUT "/api/datasources/$($source.id)" (Update-Payload $sessionA ([long]$sessionA.version) "DSRC-001,$($laneName.ToLowerInvariant()),session-a")
        $saveB = Invoke-Api PUT "/api/datasources/$($source.id)" (Update-Payload $sessionB ([long]$sessionB.version) "DSRC-001,$($laneName.ToLowerInvariant()),session-b-stale")
        $after = (Invoke-Api GET "/api/datasources/$($source.id)" $null).Body
        $conflictSafe = $saveB.Raw -notmatch '(?i)insert into|select |jdbc:|password|exception'
        Add-Result $laneName "optimistic-lock" `
            ($saveA.Status -eq 200 -and $saveB.Status -eq 409 -and [string]$after.tags -match 'session-a$' -and $conflictSafe) `
            "A stale editor received a sanitized 409 and did not overwrite the saved row"

        $retest = Invoke-Api POST "/api/datasources/$($source.id)/test" $null
        Add-Result $laneName "credential-retention" ($retest.Status -eq 200 -and $retest.Body.ok -eq $true) `
            "Metadata-only edit retained the encrypted credential"

        if ($tableFound) {
            $scan = Invoke-Api POST "/api/discovery/scan-jobs/$($source.id)?schema=$encodedSchema" @{
                piiTypes = @("EMAIL")
                tableNames = @($actualTable)
            }
            $scanPassed = $scan.Status -eq 200
            if ($scanPassed) {
                $snapshot = Wait-Discovery ([string]$scan.Body.jobId)
                $scanPassed = $null -ne $snapshot -and $snapshot.status -eq "COMPLETED" -and [int]$snapshot.completedTables -eq 1
            }
            Add-Result $laneName "source-discovery" $scanPassed "BOTH completed a live one-table discovery scan"
        } else {
            Add-Result $laneName "source-discovery" $false "Discovery was not attempted because table browsing failed"
        }

        $target = Invoke-Api POST "/api/jobs" @{
            name = "DSRC $laneName target $suffix"
            jobType = "SYNTHETIC_LOAD"
            targetId = [long]$source.id
        }
        $targetPassed = $target.Status -eq 200
        if ($targetPassed) {
            $jobId = [long]$target.Body.id
            $script:createdJobs.Add($jobId)
            $job = Wait-Provision $jobId
            $targetPassed = $null -ne $job -and $job.status -in @("FAILED", "COMPLETED")
        }
        Add-Result $laneName "target-preflight" $targetPassed "BOTH passed target-role validation and entered execution"
    } catch {
        Add-Result $laneName "lane-execution" $false "The lane stopped before all checks completed; inspect the retained server log"
    }
}

if (-not (Test-Path -LiteralPath $Psql)) { throw "psql was not found at $Psql" }

$lanes = @(
    [pscustomobject]@{
        Name = "ORACLE"
        Kind = "ORACLE"
        JdbcUrl = $OracleJdbcUrl
        Username = $OracleUser
        Password = $OraclePassword
        Schema = $OracleSchema
        Table = $OracleTable
    },
    [pscustomobject]@{
        Name = "MYSQL"
        Kind = "MYSQL"
        JdbcUrl = $MySqlJdbcUrl
        Username = $MySqlUser
        Password = $MySqlPassword
        Schema = $MySqlSchema
        Table = $MySqlTable
    }
)

try {
    $loginBody = @{ username = $AdminUser; password = $AdminPassword } | ConvertTo-Json -Compress
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/auth/login" -ContentType "application/json" `
        -Body $loginBody -SessionVariable session | Out-Null

    foreach ($lane in $lanes) { Test-Lane $lane }
} finally {
    foreach ($jobId in $createdJobs) {
        try { Invoke-Api DELETE "/api/jobs/$jobId" $null | Out-Null } catch { }
    }
    if ($createdDataSources.Count -gt 0) {
        $env:PGPASSWORD = $MetadataDbPassword
        $ids = ($createdDataSources | ForEach-Object { [string]$_ }) -join ','
        try {
            & $Psql -h $MetadataDbHost -p $MetadataDbPort -U $MetadataDbUser -d $MetadataDbName -q -c `
                "delete from classifications where data_source_id in ($ids);" | Out-Null
        } catch { }
        foreach ($id in $createdDataSources) {
            try { Invoke-Api DELETE "/api/datasources/$id" $null | Out-Null } catch { }
        }
    }
    $env:PGPASSWORD = $oldPgPassword
}

$passed = @($results | Where-Object result -eq "PASS").Count
$failed = @($results | Where-Object result -eq "FAIL").Count
$evidence = [ordered]@{
    story = "DSRC-001"
    scope = "Oracle and MySQL live JDBC lanes"
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
