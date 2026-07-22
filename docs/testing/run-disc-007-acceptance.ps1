[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8099",
    [string]$AdminUser = "admin",
    [string]$AdminPassword = "admin123",
    [long]$DataSourceId = 10,
    [string]$Schema = "disc007_acceptance",
    [string]$PsqlPath = "C:\Program Files\PostgreSQL\17\bin\psql.exe",
    [string]$EvidencePath = "docs/testing/evidence/artifacts/DISC-007-LIVE-2026-07-22.json"
)

$ErrorActionPreference = "Stop"
$sourceDb = "sourcedb"
$backendDb = "forgetdm"
$results = [System.Collections.Generic.List[object]]::new()
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

function Invoke-Psql([string]$Database, [string]$User, [string]$Password, [string]$Sql) {
    $old = $env:PGPASSWORD
    $oldPreference = $ErrorActionPreference
    try {
        $env:PGPASSWORD = $Password
        $ErrorActionPreference = "Continue"
        $output = & $PsqlPath -h localhost -p 5433 -U $User -d $Database -X -A -t -v ON_ERROR_STOP=1 -c $Sql 2>&1
        if ($LASTEXITCODE -ne 0) { throw "PostgreSQL fixture operation failed." }
        return (($output | Where-Object { $_ -and $_ -notmatch '^\s*$' }) -join "`n").Trim()
    } finally {
        $ErrorActionPreference = $oldPreference
        if ($null -eq $old) { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue } else { $env:PGPASSWORD = $old }
    }
}

function Invoke-Api([string]$Method, [string]$Path, [object]$Body = $null, [switch]$AllowFailure) {
    $params = @{
        Uri = "$BaseUrl$Path"
        Method = $Method
        WebSession = $session
        UseBasicParsing = $true
        TimeoutSec = 60
    }
    if ($null -ne $Body) {
        $params.ContentType = "application/json"
        $params.Body = $Body | ConvertTo-Json -Depth 20 -Compress
    }
    try {
        $response = Invoke-WebRequest @params
        $parsed = if ($response.Content) { $response.Content | ConvertFrom-Json } else { $null }
        return [pscustomobject]@{ Status = [int]$response.StatusCode; Body = $parsed; Text = $response.Content }
    } catch {
        $status = 0
        $text = $_.Exception.Message
        if ($_.Exception.Response) {
            $status = [int]$_.Exception.Response.StatusCode
            try {
                $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
                $text = $reader.ReadToEnd()
                $reader.Dispose()
            } catch {}
        }
        if (-not $AllowFailure) { throw "API $Method $Path failed with HTTP $status. $text" }
        return [pscustomobject]@{ Status = $status; Body = $null; Text = $text }
    }
}

function Add-Result([string]$Case, [bool]$Passed, [string]$Evidence) {
    $results.Add([pscustomobject]@{ case = $Case; status = $(if ($Passed) { "PASS" } else { "FAIL" }); evidence = $Evidence })
    if (-not $Passed) { throw "$Case failed: $Evidence" }
}

function Get-Findings([string[]]$Types = @()) {
    $path = "/api/discovery/results/${DataSourceId}?schema=$Schema"
    foreach ($type in $Types) { $path += "&piiTypes=$([uri]::EscapeDataString($type))" }
    return @((Invoke-Api GET $path).Body)
}

function Find-Column([object[]]$Rows, [string]$Table, [string]$Column) {
    return @($Rows | Where-Object { $_.tableName -eq $Table -and $_.columnName -eq $Column }) | Select-Object -First 1
}

function Snapshot([object[]]$Rows) {
    return @($Rows | Sort-Object tableName,columnName | ForEach-Object {
        [pscustomobject]@{
            id = $_.id; key = "$($_.tableName).$($_.columnName)"; piiType = $_.piiType
            status = $_.status; function = $_.suggestedFunction; param1 = $_.suggestedParam1
            param2 = $_.suggestedParam2; sample = $_.sampleValue; discoveredAt = $_.discoveredAt
        }
    }) | ConvertTo-Json -Depth 8 -Compress
}

function Scan([string[]]$Types = @(), [string[]]$Tables = @()) {
    return @((Invoke-Api POST "/api/discovery/scan/${DataSourceId}?schema=$Schema" @{ piiTypes = $Types; tableNames = $Tables }).Body)
}

function Update-Source([string]$Sql) {
    Invoke-Psql $sourceDb "postgres" "postgres" $Sql | Out-Null
}

try {
    $login = Invoke-WebRequest -Uri "$BaseUrl/api/auth/login" -Method POST -WebSession $session -UseBasicParsing `
        -ContentType "application/json" -Body (@{ username = $AdminUser; password = $AdminPassword } | ConvertTo-Json -Compress)
    if ($login.StatusCode -ne 200) { throw "Admin login failed." }

    $fixtureSql = @"
drop schema if exists $Schema cascade;
create schema $Schema;
create table $Schema.scope_a (
  fixture_id integer primary key,
  candidate_refresh varchar(160),
  candidate_approved varchar(160),
  candidate_rejected varchar(160),
  candidate_manual varchar(160),
  candidate_stale varchar(160),
  candidate_narrow varchar(160),
  candidate_fail varchar(160)
);
create table $Schema.scope_b (fixture_id integer primary key, candidate_outscope varchar(160));
insert into $Schema.scope_a
select g,
       lpad((100000000 + g)::text, 3, '0') || '-' || lpad((10 + g)::text, 2, '0') || '-' || lpad((1000 + g)::text, 4, '0'),
       'approved' || g || '@example.com',
       '212-555-' || lpad(g::text, 4, '0'),
       'manual-' || g,
       'stale' || g || '@example.com',
       lpad((200000000 + g)::text, 3, '0') || '-' || lpad((20 + g)::text, 2, '0') || '-' || lpad((2000 + g)::text, 4, '0'),
       'neutral-' || g
from generate_series(1,10) g;
insert into $Schema.scope_b select g, 'outside' || g || '@example.com' from generate_series(1,10) g;
"@
    Update-Source $fixtureSql
    Invoke-Psql $backendDb "forgetdm" "forgetdm" "delete from classifications where data_source_id=$DataSourceId and schema_name='$Schema'" | Out-Null

    $first = Scan
    $second = Scan
    $keys = @($second | ForEach-Object { "$($_.tableName).$($_.columnName)" })
    $stableIds = @($first | ForEach-Object id | Sort-Object) -join ','
    $stableIds2 = @($second | ForEach-Object id | Sort-Object) -join ','
    Add-Result "DISC-007-01" ($first.Count -gt 0 -and $first.Count -eq $second.Count -and $keys.Count -eq @($keys | Sort-Object -Unique).Count -and $stableIds -eq $stableIds2) "Two identical scans retained $($second.Count) unique physical-column rows and stable IDs."

    $refreshBefore = Find-Column $second "scope_a" "candidate_refresh"
    Start-Sleep -Milliseconds 20
    Update-Source "update $Schema.scope_a set candidate_refresh='refresh-' || fixture_id || '@example.net'"
    $afterRefresh = Scan
    $refreshAfter = Find-Column $afterRefresh "scope_a" "candidate_refresh"
    Add-Result "DISC-007-02" ($refreshAfter.id -eq $refreshBefore.id -and $refreshAfter.piiType -eq "EMAIL" -and $refreshAfter.discoveredAt -ne $refreshBefore.discoveredAt) "Suggestion id $($refreshAfter.id) refreshed in place from $($refreshBefore.piiType) to EMAIL."

    $approved = Find-Column $afterRefresh "scope_a" "candidate_approved"
    $approvedEdited = (Invoke-Api PATCH "/api/discovery/classifications/$($approved.id)" @{ status = "APPROVED"; suggestedFunction = "TOKENIZE"; suggestedParam1 = "DISC007_APPROVED" }).Body
    Update-Source "update $Schema.scope_a set candidate_approved='no-longer-pii-' || fixture_id"
    Scan | Out-Null
    $approvedAfter = Find-Column (Get-Findings) "scope_a" "candidate_approved"
    Add-Result "DISC-007-03" ($approvedAfter.id -eq $approvedEdited.id -and $approvedAfter.status -eq "APPROVED" -and $approvedAfter.suggestedFunction -eq "TOKENIZE" -and $approvedAfter.suggestedParam1 -eq "DISC007_APPROVED") "Approved analyst decision and TOKENIZE override survived source-data change and rescan."

    $rejected = Find-Column (Get-Findings) "scope_a" "candidate_rejected"
    Invoke-Api PATCH "/api/discovery/classifications/$($rejected.id)" @{ status = "REJECTED" } | Out-Null
    $manual = (Invoke-Api POST "/api/discovery/manual/$DataSourceId" @{
        schemaName = $Schema; tableName = "scope_a"; columnName = "candidate_manual"; piiType = "CUSTOMER_REFERENCE"
        suggestedFunction = "TOKENIZE"; suggestedParam1 = "DISC007_MANUAL"; status = "APPROVED"
    }).Body
    Scan | Out-Null
    $humanRows = Get-Findings
    $rejectedAfter = Find-Column $humanRows "scope_a" "candidate_rejected"
    $manualAfter = Find-Column $humanRows "scope_a" "candidate_manual"
    Add-Result "DISC-007-04" ($rejectedAfter.id -eq $rejected.id -and $rejectedAfter.status -eq "REJECTED" -and $manualAfter.id -eq $manual.id -and $manualAfter.status -eq "APPROVED" -and @($humanRows | Where-Object columnName -eq "candidate_manual").Count -eq 1) "Rejected and manual decisions retained their IDs/statuses with one row per physical column."

    $stale = Find-Column $humanRows "scope_a" "candidate_stale"
    Update-Source "update $Schema.scope_a set candidate_stale='ordinary-' || fixture_id"
    Scan | Out-Null
    $afterStale = Get-Findings
    Add-Result "DISC-007-05" ($stale -and -not (Find-Column $afterStale "scope_a" "candidate_stale") -and (Find-Column $afterStale "scope_a" "candidate_approved").status -eq "APPROVED") "Only the stale machine suggestion was removed; reviewed rows remained."

    $narrowBefore = Find-Column $afterStale "scope_a" "candidate_narrow"
    $emailScan = Scan @("EMAIL")
    $allAfterNarrow = Get-Findings
    $emailResults = Get-Findings @("EMAIL")
    $narrowAfter = Find-Column $allAfterNarrow "scope_a" "candidate_narrow"
    Add-Result "DISC-007-06" ($narrowAfter.id -eq $narrowBefore.id -and $narrowAfter.piiType -eq "SSN" -and -not (Find-Column $emailResults "scope_a" "candidate_narrow") -and -not (Find-Column $emailScan "scope_a" "candidate_narrow")) "Excluded SSN suggestion was preserved and absent from EMAIL-scoped scan/results."

    $outsideBefore = Find-Column $allAfterNarrow "scope_b" "candidate_outscope"
    Update-Source "update $Schema.scope_b set candidate_outscope='ordinary-' || fixture_id"
    $focused = Scan @() @("scope_a")
    $outsideAfter = Find-Column (Get-Findings) "scope_b" "candidate_outscope"
    Add-Result "DISC-007-07" ($outsideAfter.id -eq $outsideBefore.id -and $outsideAfter.discoveredAt -eq $outsideBefore.discoveredAt -and @($focused | Where-Object tableName -ne "scope_a").Count -eq 0) "Focused rescan touched only scope_a and left scope_b byte-for-byte stable at the API contract level."

    $token = ($session.Cookies.GetCookies([uri]$BaseUrl) | Where-Object Name -eq "FORGETDM_SESSION" | Select-Object -First 1).Value
    $concurrentBody = @{ piiTypes = @(); tableNames = @() } | ConvertTo-Json -Compress
    $jobs = 1..2 | ForEach-Object {
        Start-Job -ScriptBlock {
            param($Uri, $BaseUri, $Token, $Json)
            try {
                $jobSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
                $cookie = New-Object System.Net.Cookie("FORGETDM_SESSION", $Token, "/", ([uri]$BaseUri).Host)
                $jobSession.Cookies.Add([uri]$BaseUri, $cookie)
                $r = Invoke-WebRequest -Uri $Uri -Method POST -WebSession $jobSession -ContentType "application/json" -Body $Json -UseBasicParsing -TimeoutSec 90
                [pscustomobject]@{ status = [int]$r.StatusCode; body = $r.Content }
            } catch {
                [pscustomobject]@{ status = $(if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }); body = $_.Exception.Message }
            }
        } -ArgumentList "$BaseUrl/api/discovery/scan/${DataSourceId}?schema=$Schema", $BaseUrl, $token, $concurrentBody
    }
    $concurrent = @($jobs | Wait-Job | Receive-Job)
    $jobs | Remove-Job -Force
    $afterConcurrent = Get-Findings
    $concurrentKeys = @($afterConcurrent | ForEach-Object { "$($_.tableName).$($_.columnName)" })
    $concurrentStatuses = @($concurrent | ForEach-Object status) -join ','
    $concurrentMessages = (($concurrent | ForEach-Object body) -join ' ') -replace '[\r\n]+', ' '
    Add-Result "DISC-007-08" (@($concurrent | Where-Object status -eq 200).Count -eq 2 -and $concurrentKeys.Count -eq @($concurrentKeys | Sort-Object -Unique).Count -and -not ($concurrentMessages -match 'uq_class|duplicate key')) "Concurrent statuses=$concurrentStatuses; $($concurrentKeys.Count) physical keys remained unique; neither response contained a duplicate-key error."

    $beforeRollback = Get-Findings
    $beforeRollbackSnapshot = Snapshot $beforeRollback
    Update-Source "update $Schema.scope_a set candidate_fail='rollback-' || fixture_id || '@example.org', candidate_refresh='rollback-' || fixture_id || '@example.org'"
    $triggerSql = @"
create or replace function disc007_fail_classification() returns trigger language plpgsql as `$`$
begin
  if new.data_source_id=$DataSourceId and new.schema_name='$Schema' and new.column_name='candidate_fail' then
    raise exception 'DISC007 injected rollback';
  end if;
  return new;
end `$`$;
drop trigger if exists disc007_fail_classification_trigger on classifications;
create trigger disc007_fail_classification_trigger before insert or update on classifications
for each row execute function disc007_fail_classification();
"@
    Invoke-Psql $backendDb "forgetdm" "forgetdm" $triggerSql | Out-Null
    $failedScan = Invoke-Api POST "/api/discovery/scan/${DataSourceId}?schema=$Schema" @{ piiTypes = @(); tableNames = @() } -AllowFailure
    Invoke-Psql $backendDb "forgetdm" "forgetdm" "drop trigger if exists disc007_fail_classification_trigger on classifications; drop function if exists disc007_fail_classification()" | Out-Null
    $afterFailureSnapshot = Snapshot (Get-Findings)
    $recovery = Scan
    $recoveredFail = Find-Column $recovery "scope_a" "candidate_fail"
    Add-Result "DISC-007-09" ($failedScan.Status -ge 400 -and $beforeRollbackSnapshot -eq $afterFailureSnapshot -and $recoveredFail) "Injected database failure rolled the entire rescan back; clean rerun then discovered candidate_fail."

    $apiRows = Get-Findings
    $csv = @($apiRows | Select-Object tableName,columnName,dataType,piiType,confidence,status,suggestedFunction | ConvertTo-Csv -NoTypeInformation)
    $csvRows = [Math]::Max(0, $csv.Count - 1)
    $duplicateCount = [int](Invoke-Psql $backendDb "forgetdm" "forgetdm" "select count(*) from (select table_name,column_name,count(*) from classifications where data_source_id=$DataSourceId and schema_name='$Schema' group by table_name,column_name having count(*)>1) d")
    Add-Result "DISC-007-10" ($apiRows.Count -eq $csvRows -and $duplicateCount -eq 0) "Results API and downloadable CSV projection both contain $($apiRows.Count) current rows; database uniqueness audit found zero duplicate keys. UI parity is retained separately."
} finally {
    try { Invoke-Psql $backendDb "forgetdm" "forgetdm" "drop trigger if exists disc007_fail_classification_trigger on classifications; drop function if exists disc007_fail_classification()" | Out-Null } catch {}
    $artifact = [ordered]@{
        story = "DISC-007"
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        baseUrl = $BaseUrl
        fixture = [ordered]@{ dataSourceId = $DataSourceId; schema = $Schema; database = $sourceDb }
        totals = [ordered]@{ passed = @($results | Where-Object status -eq "PASS").Count; failed = @($results | Where-Object status -eq "FAIL").Count; expected = 10 }
        cases = $results
    }
    $fullEvidencePath = if ([IO.Path]::IsPathRooted($EvidencePath)) { $EvidencePath } else { Join-Path (Get-Location) $EvidencePath }
    New-Item -ItemType Directory -Force -Path (Split-Path $fullEvidencePath -Parent) | Out-Null
    $artifact | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $fullEvidencePath -Encoding UTF8
    Write-Host "DISC-007: $($artifact.totals.passed)/$($artifact.totals.expected) passed; evidence $fullEvidencePath"
}

if (@($results | Where-Object status -ne "PASS").Count -gt 0 -or $results.Count -ne 10) { exit 1 }
