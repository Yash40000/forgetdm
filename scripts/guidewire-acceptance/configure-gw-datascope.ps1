param(
    [switch]$RunSmoke,
    [switch]$RunFull,
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
        $args.Body = $Body | ConvertTo-Json -Depth 50 -Compress
    }
    try {
        $result = Invoke-RestMethod @args
        if ($result -is [System.Array]) {
            foreach ($item in $result) { Write-Output $item }
            return
        }
        return $result
    } catch {
        $detail = $_.Exception.Message
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $detail = $_.ErrorDetails.Message }
        throw "$Method $Path failed: $detail"
    }
}

function Save-Evidence([string]$Name, $Value) {
    $path = Join-Path $evidenceDir (($Name -replace '[^A-Za-z0-9._-]', '-') + '.json')
    $Value | ConvertTo-Json -Depth 60 | Set-Content -LiteralPath $path -Encoding UTF8
    return $path
}

function Wait-Provision($Session, [long]$RunId, [int]$TimeoutMinutes = 45) {
    $deadline = (Get-Date).AddMinutes($TimeoutMinutes)
    do {
        $job = Invoke-Forge $Session GET "/api/jobs/$RunId"
        Write-Host ("{0,-18} rows={1,-8} {2}" -f $job.status, [long]$job.rowsProcessed, [string]$job.message)
        if ($job.status -in @('COMPLETED', 'FAILED', 'CANCELED', 'REJECTED')) { break }
        if ($job.status -eq 'AWAITING_APPROVAL') {
            throw "Admin run $RunId was unexpectedly parked for approval"
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    if ($job.status -ne 'COMPLETED') { throw "Provision run $RunId ended as $($job.status): $($job.message)" }
    Save-Evidence "GW-provision-run-$RunId" $job | Out-Null
    return $job
}

function Ensure-Blueprint($Session, $definition, $source, $policy, $rows, [bool]$smoke,
                          $allDatasets, $allSavedJobs) {
    $suffix = if ($smoke) { 'Masking-Smoke' } else { 'Masked-Full-Copy' }
    $blueprintName = "GW-$($definition.short)-$suffix"
    $savedJobName = if ($smoke) { "GW-$($definition.short)-Provision-Smoke" } else { "GW-$($definition.short)-Provision-250K" }
    $sourceSchema = $definition.sourceSchema
    $targetSchema = $definition.targetSchema

    $datasetBody = @{
        name = $blueprintName
        description = "Guidewire-style $($definition.application) source-to-target masked copy. Managed by the GW acceptance runner."
        dataSourceId = [long]$source.id
        schemaName = $sourceSchema
        targetDataSourceId = [long]$source.id
        targetSchemaName = $targetSchema
        policyId = [long]$policy.id
        driverTable = ''
        driverFilter = ''
        globalQ1 = $smoke
        globalQ2 = $smoke
    }
    $existing = $allDatasets | Where-Object name -eq $blueprintName | Select-Object -First 1
    $dataset = if ($existing) {
        Invoke-Forge $Session PUT "/api/datasets/$($existing.id)" $datasetBody
    } else {
        Invoke-Forge $Session POST '/api/datasets' $datasetBody
    }

    $encodedSourceSchema = [uri]::EscapeDataString($sourceSchema)
    $encodedTargetSchema = [uri]::EscapeDataString($targetSchema)
    $sourceCatalog = @(Invoke-Forge $Session GET "/api/datasources/$($source.id)/tables?schema=$encodedSourceSchema")
    $targetCatalog = if ($sourceSchema -eq $targetSchema) { $sourceCatalog } else {
        @(Invoke-Forge $Session GET "/api/datasources/$($source.id)/tables?schema=$encodedTargetSchema")
    }
    $sourceByKey = @{}
    $targetByKey = @{}
    foreach ($table in $sourceCatalog) { $sourceByKey[([string]$table.table).ToLowerInvariant()] = [string]$table.table }
    foreach ($table in $targetCatalog) { $targetByKey[([string]$table.table).ToLowerInvariant()] = [string]$table.table }

    $includedTables = @{}
    foreach ($row in $rows) {
        $actual = $sourceByKey[([string]$row.source_table).ToLowerInvariant()]
        if ($actual) { $includedTables[$actual.ToLowerInvariant()] = $actual }
    }
    $independentRoots = @{}
    if ($smoke) {
        foreach ($sourceTable in $includedTables.Values) {
            $encodedTable = [uri]::EscapeDataString($sourceTable)
            $fks = @(Invoke-Forge $Session GET "/api/datasources/$($source.id)/tables/$encodedTable/fks?schema=$encodedSourceSchema")
            $hasIncludedParent = $false
            foreach ($fk in $fks) {
                $parent = [string]$fk.refTable
                if ($parent -and -not $parent.Equals($sourceTable, [System.StringComparison]::OrdinalIgnoreCase) -and
                    $includedTables.ContainsKey($parent.ToLowerInvariant())) {
                    $hasIncludedParent = $true
                    break
                }
            }
            if (-not $hasIncludedParent) { $independentRoots[$sourceTable.ToLowerInvariant()] = $true }
        }
        if ($independentRoots.Count -eq 0 -and $includedTables.Count -gt 0) {
            $first = @($includedTables.Values | Sort-Object)[0]
            $independentRoots[$first.ToLowerInvariant()] = $true
        }
        Write-Host ("Smoke roots: " + (($independentRoots.Keys | Sort-Object) -join ', ')) -ForegroundColor DarkCyan
    }

    $profiles = @()
    $overrides = @()
    $priority = 0
    foreach ($row in $rows) {
        $sourceTable = $sourceByKey[([string]$row.source_table).ToLowerInvariant()]
        $targetTable = $targetByKey[([string]$row.target_table).ToLowerInvariant()]
        if (-not $sourceTable) { throw "Source table missing: $sourceSchema.$($row.source_table)" }
        if (-not $targetTable) { throw "Target table missing: $targetSchema.$($row.target_table)" }
        $priority++
        $profiles += @{
            sourceDataSourceId = [long]$source.id
            sourceSchemaName = $sourceSchema
            tableName = $sourceTable
            targetTableName = $targetTable
            policyId = $null
            included = $true
            filterExpr = $null
            rowLimit = if ($smoke -and $independentRoots.ContainsKey($sourceTable.ToLowerInvariant())) { 5 } else { $null }
            referentialStrategy = if (-not $smoke -or $independentRoots.ContainsKey($sourceTable.ToLowerInvariant())) {
                'INDEPENDENT'
            } else {
                'INHERIT'
            }
            q1Override = $null
            q2Override = $null
            q1Mode = $null
            q2Mode = $null
            loadPriority = $priority
            note = 'GW acceptance source-to-target table map'
        }
        $encodedTable = [uri]::EscapeDataString($sourceTable)
        $columns = @(Invoke-Forge $Session GET "/api/datasources/$($source.id)/tables/$encodedTable/columns?schema=$encodedSourceSchema")
        foreach ($column in $columns) {
            $overrides += @{
                tableName = $sourceTable
                columnName = [string]$column.column
                sourceColumnName = [string]$column.column
                overrideType = 'USE_POLICY'
                literalValue = $null
                note = 'GW explicit same-name column map'
            }
        }
    }

    $savedProfiles = @(Invoke-Forge $Session PUT "/api/datasets/$($dataset.id)/profiles" $profiles)
    $savedOverrides = @(Invoke-Forge $Session PUT "/api/datasets/$($dataset.id)/overrides" $overrides)
    $drift = Invoke-Forge $Session GET "/api/datasets/$($dataset.id)/drift"
    $coverage = Invoke-Forge $Session GET "/api/datasets/$($dataset.id)/pii-coverage?policyId=$($policy.id)"

    $versionNote = 'GW acceptance RI-safe baseline 2026-07-15 v2'
    $versions = @(Invoke-Forge $Session GET "/api/datasets/$($dataset.id)/versions")
    $version = $versions | Where-Object note -eq $versionNote | Select-Object -First 1
    if (-not $version) {
        $version = Invoke-Forge $Session POST "/api/datasets/$($dataset.id)/versions" @{ note = $versionNote }
    }

    $specJson = @{
        sourceSchema = $sourceSchema
        targetSchema = $targetSchema
        maxDriverRows = 0
        loadAction = 'REPLACE'
        targetPrep = 'DELETE'
        maskingSeed = 'GW-ACCEPTANCE-20260715'
        batchSize = 500
    }
    $savedJobBody = @{
        name = $savedJobName
        description = "Reusable $blueprintName provision job"
        spec = @{
            name = $savedJobName
            jobType = 'SUBSET_MASK'
            sourceId = [long]$source.id
            targetId = [long]$source.id
            policyId = [long]$policy.id
            datasetId = [long]$dataset.id
            specJson = $specJson
        }
    }
    $existingJob = $allSavedJobs | Where-Object name -eq $savedJobName | Select-Object -First 1
    $savedJob = if ($existingJob) {
        Invoke-Forge $Session PUT "/api/datascope/saved-jobs/$($existingJob.id)" $savedJobBody
    } else {
        Invoke-Forge $Session POST '/api/datascope/saved-jobs' $savedJobBody
    }

    $evidence = [ordered]@{
        blueprint = $dataset
        profiles = $savedProfiles.Count
        columnMaps = $savedOverrides.Count
        policy = $policy.name
        policyId = $policy.id
        drift = $drift
        piiCoverage = $coverage
        version = $version
        savedJob = $savedJob
    }
    Save-Evidence $blueprintName $evidence | Out-Null
    return [ordered]@{
        application = $definition.application
        mode = if ($smoke) { 'SMOKE' } else { 'FULL' }
        blueprint = $blueprintName
        blueprintId = $dataset.id
        profiles = $savedProfiles.Count
        columnMaps = $savedOverrides.Count
        savedJob = $savedJobName
        savedJobId = $savedJob.id
        runId = $null
        status = 'SAVED'
    }
}

$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
Invoke-Forge $session POST '/api/auth/login' @{ username = 'admin'; password = 'admin123' } | Out-Null
$sources = @(Invoke-Forge $session GET '/api/datasources')
$policies = @(Invoke-Forge $session GET '/api/policies')
$datasets = @(Invoke-Forge $session GET '/api/datasets')
$savedJobs = @(Invoke-Forge $session GET '/api/datascope/saved-jobs')

$definitions = @(
    @{ application = 'Policy Operations'; short = 'Policy'; connection = 'GW-Policy-Oracle';
       sourceSchema = 'BE_CARDS'; targetSchema = 'BE_CARDS'; policy = 'GW-Policy-Masking-20260716-v5' },
    @{ application = 'Billing and Contacts'; short = 'Billing'; connection = 'GW-Billing-PostgreSQL';
       sourceSchema = 'gw_billing_src'; targetSchema = 'gw_billing_tgt'; policy = 'GW-Billing-Masking-20260716-v5' }
)

$components = @()
foreach ($definition in $definitions) {
    $source = $sources | Where-Object name -eq $definition.connection | Select-Object -First 1
    $policy = $policies | Where-Object name -eq $definition.policy | Select-Object -First 1
    if (-not $source) { throw "Source not found: $($definition.connection)" }
    if (-not $policy) { throw "Policy not found: $($definition.policy)" }
    $rows = @($manifest | Where-Object application -eq $definition.application)
    if ($rows.Count -ne 50) { throw "$($definition.application) needs 50 manifest rows" }

    Write-Host "`n== Configure GW-$($definition.short) DataScope assets ==" -ForegroundColor Cyan
    $smoke = Ensure-Blueprint $session $definition $source $policy $rows $true $datasets $savedJobs
    $full = Ensure-Blueprint $session $definition $source $policy $rows $false $datasets $savedJobs
    $components += $smoke
    $components += $full

    if ($RunSmoke) {
        Write-Host "`n== Run $($smoke.savedJob) ==" -ForegroundColor Cyan
        $started = Invoke-Forge $session POST "/api/datascope/saved-jobs/$($smoke.savedJobId)/run"
        $completed = Wait-Provision $session ([long]$started.runId) 20
        $smoke.runId = $started.runId
        $smoke.status = $completed.status
    }
    if ($RunFull) {
        Write-Host "`n== Run $($full.savedJob) ==" -ForegroundColor Cyan
        $started = Invoke-Forge $session POST "/api/datascope/saved-jobs/$($full.savedJobId)/run"
        $completed = Wait-Provision $session ([long]$started.runId) 60
        $full.runId = $started.runId
        $full.status = $completed.status
    }
}

$path = Save-Evidence 'GW-datascope-component-summary' @{
    generatedAt = (Get-Date).ToUniversalTime().ToString('o')
    components = $components
}
$components | ForEach-Object { [pscustomobject]$_ } |
    Format-Table application, mode, blueprint, profiles, columnMaps, savedJob, status -AutoSize
Write-Host "Evidence: $path" -ForegroundColor Green
