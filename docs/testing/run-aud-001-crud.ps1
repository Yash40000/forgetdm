param(
    [string]$BaseUrl = "http://localhost:8131",
    [string]$Username = "admin",
    [Parameter(Mandatory = $true)]
    [string]$Password
)

$ErrorActionPreference = "Stop"
$suffix = (Get-Date).ToUniversalTime().ToString("yyyyMMddHHmmss")
$expected = [System.Collections.Generic.List[object]]::new()

function Invoke-ForgeApi {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        [object]$Body,
        [Parameter(Mandatory = $true)][string]$CorrelationId
    )
    $request = @{
        Method = $Method
        Uri = "$BaseUrl$Path"
        WebSession = $script:session
        Headers = @{ "X-Correlation-ID" = $CorrelationId }
    }
    if ($null -ne $Body) {
        $request.ContentType = "application/json"
        $request.Body = $Body | ConvertTo-Json -Depth 40 -Compress
    }
    try {
        Invoke-RestMethod @request
    } catch {
        $status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
        $detail = $_.ErrorDetails.Message
        if ([string]::IsNullOrWhiteSpace($detail)) { $detail = $_.Exception.Message }
        throw "$Method $Path failed ($status): $detail"
    }
}

function Add-ExpectedEvent {
    param([string]$Action, [string]$ResourceType, [string]$ResourceId,
          [string]$ResourceName, [string]$CorrelationId)
    $script:expected.Add([pscustomobject]@{
        action = $Action
        resourceType = $ResourceType
        resourceId = $ResourceId
        resourceName = $ResourceName
        correlationId = $CorrelationId
    })
}

function Correlation([string]$label) {
    return "AUD001-$suffix-$label"
}

$loginBody = @{ username = $Username; password = $Password } | ConvertTo-Json -Compress
Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/auth/login" -ContentType "application/json" `
    -Body $loginBody -SessionVariable session | Out-Null

$dsName = "AUD_DS_$suffix"
$corr = Correlation "ds-create"
$dataSource = Invoke-ForgeApi POST "/api/datasources" @{
    name = $dsName
    kind = "H2"
    jdbcUrl = "jdbc:h2:mem:$suffix;DB_CLOSE_DELAY=-1"
    username = "sa"
    password = ""
    role = "BOTH"
    environment = "TEST"
    tags = "AUD-001,disposable"
} $corr
Add-ExpectedEvent "DATASOURCE_CREATED" "datasource" "$($dataSource.id)" $dsName $corr

$corr = Correlation "ds-update"
Invoke-ForgeApi PUT "/api/datasources/$($dataSource.id)" @{
    name = $dsName
    kind = "H2"
    jdbcUrl = "jdbc:h2:mem:$suffix;DB_CLOSE_DELAY=-1"
    username = "sa"
    role = "BOTH"
    environment = "QA"
    tags = "AUD-001,updated"
} $corr | Out-Null
Add-ExpectedEvent "DATASOURCE_UPDATED" "datasource" "$($dataSource.id)" $dsName $corr

$policyName = "AUD policy $suffix"
$corr = Correlation "policy-create"
$policy = Invoke-ForgeApi POST "/api/policies" @{
    name = $policyName
    description = "AUD-001 disposable policy"
    dataSourceId = $dataSource.id
    schemaName = "PUBLIC"
    visibility = "GROUP"
} $corr
Add-ExpectedEvent "POLICY_CREATED" "policy" "$($policy.id)" $policyName $corr

$corr = Correlation "policy-update"
Invoke-ForgeApi PUT "/api/policies/$($policy.id)" @{
    name = $policyName
    description = "AUD-001 updated disposable policy"
    dataSourceId = $dataSource.id
    schemaName = "PUBLIC"
    visibility = "GROUP"
} $corr | Out-Null
Add-ExpectedEvent "POLICY_UPDATED" "policy" "$($policy.id)" $policyName $corr

$datasetName = "AUD scope $suffix"
$corr = Correlation "dataset-create"
$dataset = Invoke-ForgeApi POST "/api/datasets" @{
    name = $datasetName
    description = "AUD-001 disposable blueprint"
    dataSourceId = $dataSource.id
    schemaName = "PUBLIC"
    targetDataSourceId = $dataSource.id
    targetSchemaName = "PUBLIC"
    policyId = $policy.id
    globalQ1 = $true
    globalQ2 = $true
    visibility = "GROUP"
} $corr
Add-ExpectedEvent "DATASET_CREATED" "dataset" "$($dataset.id)" $datasetName $corr

$corr = Correlation "dataset-update"
Invoke-ForgeApi PUT "/api/datasets/$($dataset.id)" @{
    name = $datasetName
    description = "AUD-001 updated disposable blueprint"
    dataSourceId = $dataSource.id
    schemaName = "PUBLIC"
    targetDataSourceId = $dataSource.id
    targetSchemaName = "PUBLIC"
    policyId = $policy.id
    globalQ1 = $false
    globalQ2 = $true
} $corr | Out-Null
Add-ExpectedEvent "DATASET_UPDATED" "dataset" "$($dataset.id)" $datasetName $corr

$mappingName = "AUD mapping $suffix"
$corr = Correlation "mapping-create"
$mapping = Invoke-ForgeApi POST "/api/mappings" @{
    name = $mappingName
    description = "AUD-001 disposable mapping"
    specJson = "{}"
} $corr
Add-ExpectedEvent "MAPPING_CREATED" "mapping" "$($mapping.id)" $mappingName $corr

$corr = Correlation "mapping-update"
Invoke-ForgeApi POST "/api/mappings" @{
    id = $mapping.id
    name = $mappingName
    description = "AUD-001 updated disposable mapping"
    specJson = "{`"description`":`"updated`"}"
} $corr | Out-Null
Add-ExpectedEvent "MAPPING_UPDATED" "mapping" "$($mapping.id)" $mappingName $corr

$savedJobName = "AUD job $suffix"
$savedPlan = @{
    dataset = "AUD-001-$suffix"
    seed = 42
    receiver = "CSV"
    executionMode = "SINGLE"
    tables = @(@{
        name = "AUD_TABLE"
        rowCount = 5
        columns = @(@{
            name = "ID"
            generator = "SEQUENCE"
            param1 = "1"
            param2 = "1"
            primaryKey = $true
            sqlType = "BIGINT"
        })
    })
}
$corr = Correlation "saved-create"
$savedJob = Invoke-ForgeApi POST "/api/synthetic/saved-jobs" @{
    name = $savedJobName
    description = "AUD-001 disposable saved job"
    plan = $savedPlan
} $corr
Add-ExpectedEvent "SYNTHETIC_JOB_SAVED" "synthetic-saved-job" "$($savedJob.id)" $savedJobName $corr

$corr = Correlation "saved-update"
Invoke-ForgeApi PUT "/api/synthetic/saved-jobs/$($savedJob.id)" @{
    name = $savedJobName
    description = "AUD-001 updated disposable saved job"
    plan = $savedPlan
} $corr | Out-Null
Add-ExpectedEvent "SYNTHETIC_JOB_UPDATED" "synthetic-saved-job" "$($savedJob.id)" $savedJobName $corr

$securityName = "aud_user_$suffix"
$corr = Correlation "user-create"
$securityUser = Invoke-ForgeApi POST "/api/security/users" @{
    username = $securityName
    displayName = "AUD-001 disposable user"
    password = "AUD-Test-123!"
    active = $true
    roles = @()
    groupIds = @()
} $corr
Add-ExpectedEvent "SECURITY_USER_CREATED" "security-user" "$($securityUser.id)" $securityName $corr

$corr = Correlation "user-update"
Invoke-ForgeApi PUT "/api/security/users/$($securityUser.id)" @{
    username = $securityName
    displayName = "AUD-001 updated user"
    active = $false
    roles = @()
    groupIds = @()
} $corr | Out-Null
Add-ExpectedEvent "SECURITY_USER_UPDATED" "security-user" "$($securityUser.id)" $securityName $corr

$corr = Correlation "dataset-delete"
Invoke-ForgeApi DELETE "/api/datasets/$($dataset.id)" $null $corr | Out-Null
Add-ExpectedEvent "DATASET_DELETED" "dataset" "$($dataset.id)" $datasetName $corr

$corr = Correlation "policy-delete"
Invoke-ForgeApi DELETE "/api/policies/$($policy.id)" $null $corr | Out-Null
Add-ExpectedEvent "POLICY_DELETED" "policy" "$($policy.id)" $policyName $corr

$corr = Correlation "mapping-delete"
Invoke-ForgeApi DELETE "/api/mappings/$($mapping.id)" $null $corr | Out-Null
Add-ExpectedEvent "MAPPING_DELETED" "mapping" "$($mapping.id)" $mappingName $corr

$corr = Correlation "saved-delete"
Invoke-ForgeApi DELETE "/api/synthetic/saved-jobs/$($savedJob.id)" $null $corr | Out-Null
Add-ExpectedEvent "SYNTHETIC_JOB_DELETED" "synthetic-saved-job" "$($savedJob.id)" $savedJobName $corr

$corr = Correlation "user-delete"
Invoke-ForgeApi DELETE "/api/security/users/$($securityUser.id)" $null $corr | Out-Null
Add-ExpectedEvent "SECURITY_USER_DELETED" "security-user" "$($securityUser.id)" $securityName $corr

$corr = Correlation "ds-delete"
Invoke-ForgeApi DELETE "/api/datasources/$($dataSource.id)" $null $corr | Out-Null
Add-ExpectedEvent "DATASOURCE_DELETED" "datasource" "$($dataSource.id)" $dsName $corr

Start-Sleep -Milliseconds 400
$checks = foreach ($want in $expected) {
    $action = [Uri]::EscapeDataString($want.action)
    $type = [Uri]::EscapeDataString($want.resourceType)
    $query = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/audit?action=$action&resourceType=$type&size=200" -WebSession $session
    $event = @($query.events) | Where-Object { "$($_.resourceId)" -eq $want.resourceId } | Select-Object -First 1
    $metadata = if ($null -ne $event -and $event.metadata) { $event.metadata | ConvertFrom-Json } else { $null }
    [pscustomobject]@{
        action = $want.action
        found = $null -ne $event
        actor = if ($event) { $event.actor } else { $null }
        outcome = if ($event) { $event.outcome } else { $null }
        resourceType = if ($event) { $event.resourceType } else { $null }
        resourceId = if ($event) { $event.resourceId } else { $null }
        resourceName = if ($event) { $event.resourceName } else { $null }
        correlationExpected = $want.correlationId
        correlationActual = if ($metadata) { $metadata.correlationId } else { $null }
        timestamp = if ($event) { $event.createdAt } else { $null }
        passed = $null -ne $event -and $event.actor -eq $Username -and $event.outcome -eq "SUCCESS" -and
            $event.resourceType -eq $want.resourceType -and "$($event.resourceId)" -eq $want.resourceId -and
            $event.resourceName -eq $want.resourceName -and $metadata.correlationId -eq $want.correlationId
    }
}

$failed = @($checks | Where-Object { -not $_.passed })
[pscustomobject]@{
    story = "AUD-001"
    case = "AUD-001-01"
    runId = $suffix
    totalChecks = @($checks).Count
    passedChecks = @($checks | Where-Object passed).Count
    failedChecks = $failed.Count
    passed = $failed.Count -eq 0
    checks = @($checks)
} | ConvertTo-Json -Depth 8

if ($failed.Count -gt 0) { exit 1 }
