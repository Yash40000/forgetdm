param(
    [string]$BaseUrl = "http://localhost:8131",
    [string]$Username = "admin",
    [Parameter(Mandatory = $true)]
    [string]$Password,
    [string]$EvidencePath
)

$ErrorActionPreference = "Stop"
$suffix = (Get-Date).ToUniversalTime().ToString("yyyyMMddHHmmss")
$checks = [System.Collections.Generic.List[object]]::new()

function Correlation([string]$label) { "AUD001-LIFE-$suffix-$label" }

function Invoke-ForgeApi {
    param([string]$Method, [string]$Path, [object]$Body, [string]$CorrelationId)
    $request = @{
        Method = $Method
        Uri = "$BaseUrl$Path"
        WebSession = $script:session
        Headers = @{ "X-Correlation-ID" = $CorrelationId }
    }
    if ($null -ne $Body) {
        $request.ContentType = "application/json"
        $request.Body = $Body | ConvertTo-Json -Depth 50 -Compress
    }
    try { Invoke-RestMethod @request }
    catch {
        $status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
        $detail = if ($_.ErrorDetails.Message) { $_.ErrorDetails.Message } else { $_.Exception.Message }
        throw "$Method $Path failed ($status): $detail"
    }
}

function Wait-ForgeJob {
    param([string]$Path, [string[]]$Terminal, [int]$TimeoutSeconds = 90, [string]$Property)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $response = Invoke-ForgeApi GET $Path $null (Correlation "poll")
        $job = if ($Property) { $response.$Property } else { $response }
        if ($Terminal -contains "$($job.status)") { return $job }
        Start-Sleep -Milliseconds 200
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for $Path; last status was $($job.status)"
}

function Add-Result([string]$Case, [bool]$Passed, [string]$Evidence) {
    $script:checks.Add([pscustomobject]@{ case = $Case; passed = $Passed; evidence = $Evidence })
}

function Assert-AuditEvent {
    param([string]$Action, [string]$ResourceId, [string]$ExpectedOutcome = "SUCCESS")
    $query = Invoke-ForgeApi GET ("/api/audit?action=" + [Uri]::EscapeDataString($Action) + "&size=200") $null (Correlation "audit")
    $event = @($query.events) | Where-Object { "$($_.resourceId)" -eq $ResourceId } | Select-Object -First 1
    $passed = $null -ne $event -and $event.actor -and $event.outcome -eq $ExpectedOutcome -and
        $event.resourceType -and $event.resourceName -and $event.createdAt
    Add-Result "audit:${Action}:$ResourceId" $passed $(if ($event) {
        "actor=$($event.actor); outcome=$($event.outcome); resource=$($event.resourceType)/$($event.resourceName)"
    } else { "event not found" })
}

function Start-UnstructuredJob {
    param([byte[]]$Bytes, [string]$FileName)
    Add-Type -AssemblyName System.Net.Http
    $handler = New-Object System.Net.Http.HttpClientHandler
    $handler.UseCookies = $false
    $client = New-Object System.Net.Http.HttpClient($handler)
    $cookie = @($script:session.Cookies.GetCookies([Uri]$BaseUrl) | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join "; "
    $client.DefaultRequestHeaders.Add("Cookie", $cookie)
    $client.DefaultRequestHeaders.Add("X-Correlation-ID", (Correlation "unstructured-start"))
    $content = New-Object System.Net.Http.MultipartFormDataContent
    $file = New-Object System.Net.Http.ByteArrayContent -ArgumentList (,$Bytes)
    $file.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("text/plain")
    $content.Add($file, "file", $FileName)
    $content.Add((New-Object System.Net.Http.StringContent("1")), "profileId")
    $content.Add((New-Object System.Net.Http.StringContent("AUD-001-seed")), "seed")
    try {
        $response = $client.PostAsync("$BaseUrl/api/unstructured/jobs", $content).GetAwaiter().GetResult()
        $payload = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) { throw "Unstructured upload failed ($([int]$response.StatusCode)): $payload" }
        return $payload | ConvertFrom-Json
    } finally {
        $content.Dispose(); $client.Dispose(); $handler.Dispose()
    }
}

$login = @{ username = $Username; password = $Password } | ConvertTo-Json -Compress
Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/auth/login" -ContentType "application/json" `
    -Body $login -SessionVariable session | Out-Null

# A private in-process database makes mapping and provisioning deterministic and disposable.
$dsName = "AUD_LIFECYCLE_$suffix"
$ds = Invoke-ForgeApi POST "/api/datasources" @{
    name = $dsName; kind = "H2"; jdbcUrl = "jdbc:h2:mem:$suffix;DB_CLOSE_DELAY=-1"
    username = "sa"; password = ""; role = "BOTH"; environment = "TEST"; tags = "AUD-001,lifecycle"
} (Correlation "datasource")
Invoke-ForgeApi POST "/api/mappings/load" @{
    dataSourceId = $ds.id; sql = "CREATE TABLE AUD_SOURCE AS SELECT CAST(1 AS BIGINT) AS ID"
} (Correlation "source-table") | Out-Null
Invoke-ForgeApi POST "/api/mappings/load" @{
    dataSourceId = $ds.id; sql = "CREATE TABLE AUD_PROVISION AS SELECT CAST(0 AS BIGINT) AS ID WHERE 1=0"
} (Correlation "provision-table") | Out-Null

$source = @{ type = "DATABASE"; dataSourceId = $ds.id; alias = "src"; schema = "PUBLIC"; table = "AUD_SOURCE" }
$columns = @(@{ action = "COPY"; source = "ID"; target = "ID" })
$failedSpec = @{ specVersion = 2; sources = @($source); columns = $columns; target = @{
    type = "DATABASE"; dataSourceId = $ds.id; schema = "PUBLIC"; table = "MISSING_TARGET"; preAction = "NONE"
} }
$mappingName = "AUD lifecycle mapping $suffix"
$mapping = Invoke-ForgeApi POST "/api/mappings" @{
    name = $mappingName; description = "AUD-001 lifecycle mapping"; specJson = ($failedSpec | ConvertTo-Json -Depth 30 -Compress)
} (Correlation "mapping-create")
$failedRun = Invoke-ForgeApi POST "/api/mappings/$($mapping.id)/runs" @{} (Correlation "mapping-fail")
$failedRun = Wait-ForgeJob "/api/mappings/runs/$($failedRun.id)" @("FAILED") 60
Add-Result "mapping failure" ($failedRun.status -eq "FAILED") "run=$($failedRun.id); status=$($failedRun.status)"

$fileSpec = @{ specVersion = 2; sources = @($source); columns = $columns; target = @{
    type = "FILE"; format = "CSV"; name = "aud-mapping-$suffix.csv"
} }
Invoke-ForgeApi POST "/api/mappings" @{
    id = $mapping.id; name = $mappingName; description = "AUD-001 retry route"; specJson = ($fileSpec | ConvertTo-Json -Depth 30 -Compress)
} (Correlation "mapping-fix") | Out-Null
$retryRun = Invoke-ForgeApi POST "/api/mappings/runs/$($failedRun.id)/retry" @{} (Correlation "mapping-retry")
$retryRun = Wait-ForgeJob "/api/mappings/runs/$($retryRun.id)" @("COMPLETED", "FAILED") 60
Add-Result "mapping retry completes" ($retryRun.status -eq "COMPLETED") "run=$($retryRun.id); status=$($retryRun.status)"

$slowSpec = @{ specVersion = 2; sources = @(@{
    type = "DATABASE"; dataSourceId = $ds.id; alias = "src"; sql = "SELECT X AS ID FROM SYSTEM_RANGE(1, 5000000)"
}); columns = $columns; target = @{ type = "FILE"; format = "CSV"; name = "aud-cancel-$suffix.csv" } }
$slowMapping = Invoke-ForgeApi POST "/api/mappings" @{
    name = "AUD cancel mapping $suffix"; description = "AUD-001 cancellation"; specJson = ($slowSpec | ConvertTo-Json -Depth 30 -Compress)
} (Correlation "mapping-slow-create")
$cancelRun = Invoke-ForgeApi POST "/api/mappings/$($slowMapping.id)/runs" @{} (Correlation "mapping-cancel-start")
Invoke-ForgeApi POST "/api/mappings/runs/$($cancelRun.id)/cancel" @{} (Correlation "mapping-cancel") | Out-Null
$cancelRun = Wait-ForgeJob "/api/mappings/runs/$($cancelRun.id)" @("CANCELED", "COMPLETED", "FAILED") 60
Add-Result "mapping cancel" ($cancelRun.status -eq "CANCELED") "run=$($cancelRun.id); status=$($cancelRun.status)"

$provisionSpec = @{
    table = "AUD_PROVISION"; targetSchema = "PUBLIC"; rowCount = 25; seed = 42
    columns = @(@{ name = "ID"; generator = "SEQUENCE"; param1 = "1"; param2 = "1" })
}
$provision = Invoke-ForgeApi POST "/api/jobs" @{
    name = "AUD provision complete $suffix"; jobType = "SYNTHETIC_LOAD"; targetId = $ds.id
    specJson = ($provisionSpec | ConvertTo-Json -Depth 20 -Compress)
} (Correlation "provision-complete")
$provision = Wait-ForgeJob "/api/jobs/$($provision.id)" @("COMPLETED", "FAILED") 60
Add-Result "provision complete" ($provision.status -eq "COMPLETED" -and $provision.rowsProcessed -eq 25) "job=$($provision.id); status=$($provision.status); rows=$($provision.rowsProcessed)"

$retryProvisionSpec = $provisionSpec.Clone()
$retryProvisionSpec.table = "AUD_PROVISION_RETRY"
$badProvision = Invoke-ForgeApi POST "/api/jobs" @{
    name = "AUD provision fail $suffix"; jobType = "SYNTHETIC_LOAD"; targetId = $ds.id
    specJson = ($retryProvisionSpec | ConvertTo-Json -Depth 20 -Compress)
} (Correlation "provision-fail")
$badProvision = Wait-ForgeJob "/api/jobs/$($badProvision.id)" @("FAILED") 60
Invoke-ForgeApi POST "/api/mappings/load" @{
    dataSourceId = $ds.id; sql = "CREATE TABLE AUD_PROVISION_RETRY AS SELECT CAST(0 AS BIGINT) AS ID WHERE 1=0"
} (Correlation "provision-retry-table") | Out-Null
$retriedProvision = Invoke-ForgeApi POST "/api/jobs/$($badProvision.id)/retry" @{} (Correlation "provision-retry")
$retriedProvision = Wait-ForgeJob "/api/jobs/$($retriedProvision.id)" @("COMPLETED", "FAILED") 60
Add-Result "provision fail and retry" ($badProvision.status -eq "FAILED" -and $retriedProvision.status -eq "COMPLETED" -and $retriedProvision.rowsProcessed -eq 25) "failed=$($badProvision.id); retry=$($retriedProvision.id); retryStatus=$($retriedProvision.status); rows=$($retriedProvision.rowsProcessed)"

$cancelSpec = $provisionSpec.Clone()
$cancelSpec.rowCount = 5000000
$cancelProvision = Invoke-ForgeApi POST "/api/jobs" @{
    name = "AUD provision cancel $suffix"; jobType = "SYNTHETIC_LOAD"; targetId = $ds.id
    specJson = ($cancelSpec | ConvertTo-Json -Depth 20 -Compress)
} (Correlation "provision-cancel-start")
Invoke-ForgeApi POST "/api/jobs/$($cancelProvision.id)/cancel" @{} (Correlation "provision-cancel") | Out-Null
$cancelProvision = Wait-ForgeJob "/api/jobs/$($cancelProvision.id)" @("CANCELED", "COMPLETED", "FAILED") 60
Add-Result "provision cancel" ($cancelProvision.status -eq "CANCELED") "job=$($cancelProvision.id); status=$($cancelProvision.status)"

$syntheticPlan = @{
    dataset = "AUD lifecycle synthetic $suffix"; seed = 42; receiver = "CSV"; executionMode = "SINGLE"
    tables = @(@{ name = "AUD_ROWS"; rowCount = 1000; columns = @(@{
        name = "ID"; generator = "SEQUENCE"; param1 = "1"; param2 = "1"; primaryKey = $true; sqlType = "BIGINT"
    }) })
}
$synthetic = Invoke-ForgeApi POST "/api/synthetic/generate/start" $syntheticPlan (Correlation "synthetic-complete")
$synthetic = Wait-ForgeJob "/api/synthetic/jobs/$($synthetic.id)" @("COMPLETED", "FAILED") 60
Add-Result "synthetic complete" ($synthetic.status -eq "COMPLETED") "job=$($synthetic.id); status=$($synthetic.status)"

$cancelSyntheticPlan = $syntheticPlan.Clone()
$cancelSyntheticPlan.dataset = "AUD cancel synthetic $suffix"
$cancelSyntheticPlan.tables = @(@{ name = "AUD_ROWS"; rowCount = 2000000; columns = $syntheticPlan.tables[0].columns })
$cancelSynthetic = Invoke-ForgeApi POST "/api/synthetic/generate/start" $cancelSyntheticPlan (Correlation "synthetic-cancel-start")
Invoke-ForgeApi POST "/api/synthetic/jobs/$($cancelSynthetic.id)/cancel" @{} (Correlation "synthetic-cancel") | Out-Null
$cancelSynthetic = Wait-ForgeJob "/api/synthetic/jobs/$($cancelSynthetic.id)" @("CANCELLED", "CANCELED", "COMPLETED", "FAILED") 60
Add-Result "synthetic cancel" ($cancelSynthetic.status -in @("CANCELLED", "CANCELED")) "job=$($cancelSynthetic.id); status=$($cancelSynthetic.status)"

$smallDocument = [Text.Encoding]::UTF8.GetBytes("Customer email jane.doe@example.com and SSN 123-45-6789")
$unstructured = Start-UnstructuredJob $smallDocument "aud-$suffix.txt"
$unstructured = Wait-ForgeJob "/api/unstructured/jobs/$($unstructured.id)" @("COMPLETED", "FAILED") 60
Add-Result "unstructured complete" ($unstructured.status -eq "COMPLETED" -and $unstructured.findingsCount -ge 2) "job=$($unstructured.id); status=$($unstructured.status); findings=$($unstructured.findingsCount)"

$largeText = ("Customer jane.doe@example.com SSN 123-45-6789`n" * 90000)
$cancelUnstructured = Start-UnstructuredJob ([Text.Encoding]::UTF8.GetBytes($largeText)) "aud-cancel-$suffix.txt"
Invoke-ForgeApi POST "/api/unstructured/jobs/$($cancelUnstructured.id)/cancel" @{} (Correlation "unstructured-cancel") | Out-Null
$cancelUnstructured = Wait-ForgeJob "/api/unstructured/jobs/$($cancelUnstructured.id)" @("CANCELED", "COMPLETED", "FAILED") 60
Add-Result "unstructured cancel" ($cancelUnstructured.status -eq "CANCELED") "job=$($cancelUnstructured.id); status=$($cancelUnstructured.status)"

$runtimeDir = Join-Path (Resolve-Path (Join-Path $PSScriptRoot "..\..")) "target\aud-mainframe-$suffix"
New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null
$mfConnection = Invoke-ForgeApi POST "/api/mainframe/connections" @{
    name = "AUD local LPAR $suffix"; type = "LOCAL"; baseDir = $runtimeDir; codePage = "Cp037"
} (Correlation "mf-connection")
Invoke-ForgeApi POST "/api/mainframe/generate-file" @{
    copybookId = 1; codePage = "Cp037"; recfm = "FB"; seed = 42; rowCount = 200000
    columns = @(); output = "TARGET"; targetConnectionId = $mfConnection.id; targetName = "AUD.INPUT"
} (Correlation "mf-generate") | Out-Null
$mf = Invoke-ForgeApi POST "/api/mainframe/jobs" @{
    name = "AUD mainframe lifecycle $suffix"; sourceConnectionId = $mfConnection.id
    targetConnectionId = $mfConnection.id; maskingSeed = "AUD-001"
    files = @(@{ sourceName = "AUD.INPUT"; copybookId = 1; recfm = "FB"; lrecl = 39; codePage = "Cp037"; targetName = "AUD.OUTPUT" })
} (Correlation "mf-start")
Invoke-ForgeApi POST "/api/mainframe/jobs/$($mf.job.id)/cancel" @{} (Correlation "mf-cancel") | Out-Null
$mfCanceled = Wait-ForgeJob "/api/mainframe/jobs/$($mf.job.id)" @("CANCELED", "COMPLETED", "FAILED", "COMPLETED_WITH_ERRORS") 90 "job"
Add-Result "mainframe cancel" ($mfCanceled.status -eq "CANCELED") "job=$($mfCanceled.id); status=$($mfCanceled.status)"
if ($mfCanceled.status -eq "CANCELED") {
    $mfRetry = Invoke-ForgeApi POST "/api/mainframe/jobs/$($mfCanceled.id)/retry" @{} (Correlation "mf-retry")
    $mfRetryDone = Wait-ForgeJob "/api/mainframe/jobs/$($mfRetry.job.id)" @("COMPLETED", "FAILED", "COMPLETED_WITH_ERRORS") 180 "job"
    Add-Result "mainframe retry" ($mfRetryDone.status -eq "COMPLETED" -and $mfRetryDone.recordsProcessed -eq 200000) "job=$($mfRetryDone.id); status=$($mfRetryDone.status); records=$($mfRetryDone.recordsProcessed)"
} else {
    Add-Result "mainframe retry" $false "Cancellation did not produce a retryable job"
}

Start-Sleep -Milliseconds 800
Assert-AuditEvent "MAPPING_RUN_FAILED" "$($failedRun.id)" "FAILURE"
Assert-AuditEvent "MAPPING_RUN_RETRIED" "$($retryRun.id)"
Assert-AuditEvent "MAPPING_RUN_COMPLETED" "$($retryRun.id)"
Assert-AuditEvent "MAPPING_RUN_CANCELED" "$($cancelRun.id)"
Assert-AuditEvent "PROVISION_JOB_COMPLETED" "$($provision.id)"
Assert-AuditEvent "PROVISION_JOB_FAILED" "$($badProvision.id)" "FAILURE"
Assert-AuditEvent "PROVISION_JOB_RETRIED" "$($retriedProvision.id)"
Assert-AuditEvent "PROVISION_JOB_CANCELED" "$($cancelProvision.id)"
Assert-AuditEvent "SYNTHETIC_JOB_COMPLETED" "$($synthetic.id)"
Assert-AuditEvent "SYNTHETIC_JOB_CANCELLED" "$($cancelSynthetic.id)"
Assert-AuditEvent "UNSTRUCTURED_JOB_COMPLETED" "$($unstructured.id)"
Assert-AuditEvent "UNSTRUCTURED_JOB_CANCELED" "$($cancelUnstructured.id)"
Assert-AuditEvent "MAINFRAME_JOB_CANCELED" "$($mfCanceled.id)"
if ($mfRetryDone) {
    Assert-AuditEvent "MAINFRAME_JOB_RETRIED" "$($mfRetryDone.id)"
    Assert-AuditEvent "MAINFRAME_JOB_COMPLETED" "$($mfRetryDone.id)"
}

$failed = @($checks | Where-Object { -not $_.passed })
$result = [pscustomobject]@{
    runId = $suffix
    passed = $checks.Count - $failed.Count
    failed = $failed.Count
    checks = $checks
}
$resultJson = $result | ConvertTo-Json -Depth 8
if (-not $EvidencePath) {
    $repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
    $EvidencePath = Join-Path $repoRoot "test-results\AUD-001-lifecycle-$suffix.json"
}
$evidenceDirectory = Split-Path -Parent $EvidencePath
if ($evidenceDirectory) { New-Item -ItemType Directory -Force -Path $evidenceDirectory | Out-Null }
Set-Content -LiteralPath $EvidencePath -Value $resultJson -Encoding UTF8
$resultJson

if ($failed.Count -gt 0) { exit 1 }
