param(
    [string]$BaseUrl = 'http://localhost:8088',
    [string]$AdminUser = 'admin',
    [Parameter(Mandatory = $true)][string]$AdminPassword,
    [long]$OracleDataSourceId = 13
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.CookieContainer = [System.Net.CookieContainer]::new()
$client = [System.Net.Http.HttpClient]::new($handler)
$client.BaseAddress = [Uri]$BaseUrl

function Invoke-Forge([string]$Method, [string]$Path, $Body = $null) {
    $request = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::new($Method), $Path)
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 30 -Compress
        $request.Content = [System.Net.Http.StringContent]::new(
            $json, [Text.Encoding]::UTF8, 'application/json')
    }
    $response = $client.SendAsync($request).Result
    $content = $response.Content.ReadAsStringAsync().Result
    if (-not $response.IsSuccessStatusCode) {
        throw "$Method $Path failed ($([int]$response.StatusCode)): $content"
    }
    if ([string]::IsNullOrWhiteSpace($content)) { return $null }
    $parsed = $content | ConvertFrom-Json
    if ($parsed -is [Array]) {
        foreach ($item in $parsed) { Write-Output $item }
        return
    }
    return $parsed
}

Invoke-Forge POST '/api/auth/login' @{ username = $AdminUser; password = $AdminPassword } | Out-Null

$source = @(Invoke-Forge GET '/api/datasources') | Where-Object id -eq $OracleDataSourceId
if ($null -eq $source) { throw "Data source $OracleDataSourceId does not exist" }
$engineText = "$($source.kind) $($source.jdbcUrl)"
if ($engineText -notmatch '(?i)oracle') {
    throw "Data source $OracleDataSourceId is not Oracle: $($source.name)"
}

$script = Invoke-Forge POST '/api/policies/scripts' @{
    name = 'ftdm-name.masked-last-first'
    description = 'Compose an empty FULL_NAME from the exact masked LAST_NAME and FIRST_NAME values in the current row.'
    visibility = 'GLOBAL'
    luaSource = @'
local first = forge.masked("first_name")
local last = forge.masked("last_name")
if first == nil or last == nil then
  error("masked first_name and last_name are required before full_name")
end
return last .. ", " .. first
'@
}

$policyName = 'FTDM-Name-Composition-Masking'
$policy = @(Invoke-Forge GET '/api/policies') | Where-Object name -eq $policyName | Select-Object -First 1
if ($null -eq $policy) {
    $policy = Invoke-Forge POST '/api/policies' @{
        name = $policyName
        dataSourceId = $OracleDataSourceId
        schemaName = 'BE_CARDS'
        description = 'Masks first and last names, then composes FULL_NAME as masked LAST_NAME, masked FIRST_NAME.'
    }
} else {
    foreach ($rule in @(Invoke-Forge GET "/api/policies/$($policy.id)/rules")) {
        Invoke-Forge DELETE "/api/policies/rules/$($rule.id)" | Out-Null
    }
}

$rules = @(
    @{ schemaName='BE_CARDS'; tableName='FTDM_NAME_NULL_TEST'; columnName='FIRST_NAME'; function='FIRST_NAME'; deterministic=$true },
    @{ schemaName='BE_CARDS'; tableName='FTDM_NAME_NULL_TEST'; columnName='LAST_NAME'; function='LAST_NAME'; deterministic=$true },
    @{ schemaName='BE_CARDS'; tableName='FTDM_NAME_NULL_TEST'; columnName='FULL_NAME'; function='SCRIPT'; param1=$script.name; deterministic=$true }
)
foreach ($rule in $rules) {
    Invoke-Forge POST "/api/policies/$($policy.id)/rules" $rule | Out-Null
}

$blueprintName = 'FTDM-Name-Composition-Blueprint'
$blueprintBody = @{
    name = $blueprintName
    description = 'Oracle name mask-and-copy test with row-aware FULL_NAME composition.'
    dataSourceId = $OracleDataSourceId
    schemaName = 'BE_CARDS'
    targetDataSourceId = $OracleDataSourceId
    targetSchemaName = 'BE_CARDS'
    policyId = $policy.id
    driverTable = 'FTDM_NAME_NULL_TEST'
    driverFilter = $null
    globalQ1 = $false
    globalQ2 = $false
}
$blueprint = @(Invoke-Forge GET '/api/datasets') | Where-Object name -eq $blueprintName | Select-Object -First 1
if ($null -eq $blueprint) {
    $blueprint = Invoke-Forge POST '/api/datasets' $blueprintBody
} else {
    $blueprint = Invoke-Forge PUT "/api/datasets/$($blueprint.id)" $blueprintBody
}

Invoke-Forge POST "/api/datasets/$($blueprint.id)/profiles" @{
    datasetId = $blueprint.id
    sourceDataSourceId = $OracleDataSourceId
    sourceSchemaName = 'BE_CARDS'
    tableName = 'FTDM_NAME_NULL_TEST'
    targetTableName = 'FTDM_NAME_MASKED_TARGET'
    policyId = $policy.id
    included = $true
    referentialStrategy = 'INDEPENDENT'
    q1Mode = 'NO'
    q2Mode = 'NO'
    loadPriority = 1
} | Out-Null

$overrides = @('FIRST_NAME','LAST_NAME','FULL_NAME') | ForEach-Object {
    @{
        datasetId = $blueprint.id
        tableName = 'FTDM_NAME_NULL_TEST'
        columnName = $_
        sourceColumnName = $_
        overrideType = 'USE_POLICY'
    }
}
foreach ($override in $overrides) {
    Invoke-Forge POST "/api/datasets/$($blueprint.id)/overrides" $override | Out-Null
}

$jobName = 'FTDM-Name-Composition-Masked-Copy'
$jobSpec = @{
    name = $jobName
    jobType = 'SUBSET_MASK'
    sourceId = $OracleDataSourceId
    targetId = $OracleDataSourceId
    policyId = $policy.id
    datasetId = $blueprint.id
    specJson = (@{
        sourceSchema = 'BE_CARDS'
        targetSchema = 'BE_CARDS'
        loadAction = 'INSERT'
        targetPrep = 'NONE'
        batchSize = 500
        maskingSeed = 'FTDM-NAME-COMPOSE-20260716'
        maxDriverRows = 0
    } | ConvertTo-Json -Compress)
}
$jobRequest = @{
    name = $jobName
    description = 'Reusable Oracle mask-and-copy job: masked first/last and FULL_NAME = masked LAST_NAME, masked FIRST_NAME.'
    spec = $jobSpec
}
$job = @(Invoke-Forge GET '/api/datascope/saved-jobs') | Where-Object name -eq $jobName | Select-Object -First 1
if ($null -eq $job) {
    $job = Invoke-Forge POST '/api/datascope/saved-jobs' $jobRequest
} else {
    $job = Invoke-Forge PUT "/api/datascope/saved-jobs/$($job.id)" $jobRequest
}

$started = Invoke-Forge POST "/api/datascope/saved-jobs/$($job.id)/run"
$terminal = @('COMPLETED','FAILED','CANCELLED','REJECTED','AWAITING_APPROVAL')
$run = $null
for ($i = 0; $i -lt 300; $i++) {
    Start-Sleep -Milliseconds 500
    $run = Invoke-Forge GET "/api/jobs/$($started.runId)"
    if ($terminal -contains $run.status) { break }
}
if ($null -eq $run -or $terminal -notcontains $run.status) {
    throw "Provision run $($started.runId) did not reach a terminal state in time"
}

[pscustomobject]@{
    scriptId = $script.id
    policyId = $policy.id
    blueprintId = $blueprint.id
    savedJobId = $job.id
    runId = $started.runId
    status = $run.status
    progress = $run.progress
    message = $run.message
} | ConvertTo-Json -Depth 5

if ($run.status -ne 'COMPLETED') { exit 2 }
