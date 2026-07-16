param(
    [ValidateSet('Oracle', 'Postgres', 'SqlServer')]
    [string[]]$Applications = @('Oracle', 'Postgres'),
    [switch]$RunJobs,
    [switch]$SmokeOnly,
    [switch]$SkipSmoke,
    [string]$BaseUrl = 'http://localhost:8088'
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$manifestPath = Join-Path $repoRoot 'docs\guidewire-acceptance\ddl\guidewire-150-table-manifest.csv'
$evidenceDir = Join-Path $repoRoot 'docs\guidewire-acceptance\evidence'
New-Item -ItemType Directory -Path $evidenceDir -Force | Out-Null

function Write-Step([string]$Message) {
    Write-Host "`n== $Message ==" -ForegroundColor Cyan
}

function Convert-ToJsonBody($Body) {
    if ($null -eq $Body) { return $null }
    return $Body | ConvertTo-Json -Depth 40 -Compress
}

function Invoke-Forge {
    param(
        [Parameter(Mandatory)] [Microsoft.PowerShell.Commands.WebRequestSession]$Session,
        [Parameter(Mandatory)] [string]$Method,
        [Parameter(Mandatory)] [string]$Path,
        $Body = $null
    )
    $params = @{
        Method = $Method
        Uri = "$BaseUrl$Path"
        WebSession = $Session
        ErrorAction = 'Stop'
    }
    if ($null -ne $Body) {
        $params.ContentType = 'application/json'
        $params.Body = Convert-ToJsonBody $Body
    }
    try {
        $result = Invoke-RestMethod @params
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

function New-ForgeSession([string]$Username, [string]$Password) {
    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    Invoke-Forge -Session $session -Method POST -Path '/api/auth/login' -Body @{
        username = $Username
        password = $Password
    } | Out-Null
    return $session
}

function Save-Evidence([string]$Name, $Value) {
    $safe = $Name -replace '[^A-Za-z0-9._-]', '-'
    $path = Join-Path $evidenceDir "$safe.json"
    $Value | ConvertTo-Json -Depth 50 | Set-Content -LiteralPath $path -Encoding UTF8
    return $path
}

function Ensure-GwReviewer($AdminSession) {
    $username = 'GW-reviewer'
    $password = 'GW-Reviewer-2026!'
    $users = @(Invoke-Forge -Session $AdminSession -Method GET -Path '/api/security/users')
    $existing = $users | Where-Object { $_.username -eq $username } | Select-Object -First 1
    $body = @{
        username = $username
        displayName = 'GW Acceptance Reviewer'
        password = $password
        active = $true
        roles = @('TDM_ARCHITECT')
        groupIds = @()
    }
    if ($existing) {
        Invoke-Forge -Session $AdminSession -Method PUT -Path "/api/security/users/$($existing.id)" -Body $body | Out-Null
    } else {
        Invoke-Forge -Session $AdminSession -Method POST -Path '/api/security/users' -Body $body | Out-Null
    }
    return New-ForgeSession -Username $username -Password $password
}

function Ensure-Connection($Session, [hashtable]$Definition) {
    if (-not $Definition.name.StartsWith('GW-')) {
        throw "Guidewire connection name must start with GW-: $($Definition.name)"
    }
    $connections = @(Invoke-Forge -Session $Session -Method GET -Path '/api/datasources')
    $existing = $connections | Where-Object { $_.name -eq $Definition.name } | Select-Object -First 1
    if ($existing) {
        $saved = Invoke-Forge -Session $Session -Method PUT -Path "/api/datasources/$($existing.id)" -Body $Definition
    } else {
        $saved = Invoke-Forge -Session $Session -Method POST -Path '/api/datasources' -Body $Definition
    }
    $test = Invoke-Forge -Session $Session -Method POST -Path "/api/datasources/$($saved.id)/test"
    Save-Evidence -Name "$($Definition.name)-connection-test" -Value $test | Out-Null
    if ($test.ok -eq $false) { throw "Connection $($Definition.name) failed: $($test.message)" }
    return $saved
}

function Get-ApplicationDefinition([string]$Application) {
    switch ($Application) {
        'Oracle' {
            return @{
                application = 'Policy Operations'
                connection = @{
                    name = 'GW-Policy-Oracle'
                    kind = 'ORACLE'
                    jdbcUrl = 'jdbc:oracle:thin:@localhost:1521:XE'
                    username = 'BE_CARDS'
                    password = 'ForgeTdm2026'
                    role = 'BOTH'
                    environment = 'QA'
                    tags = 'guidewire,acceptance,policy,gw'
                }
                sourceSchema = 'BE_CARDS'
                tablePrefix = 'PC_'
                jobName = 'GW-Policy-Synthetic-250K'
                dataset = 'GW-Policy-Source-250K'
                seed = 42001L
            }
        }
        'Postgres' {
            return @{
                application = 'Billing and Contacts'
                connection = @{
                    name = 'GW-Billing-PostgreSQL'
                    kind = 'POSTGRES'
                    jdbcUrl = 'jdbc:postgresql://localhost:5433/sourcedb'
                    username = 'postgres'
                    password = 'postgres'
                    role = 'BOTH'
                    environment = 'QA'
                    tags = 'guidewire,acceptance,billing,gw'
                }
                sourceSchema = 'gw_billing_src'
                tablePrefix = 'bc_'
                jobName = 'GW-Billing-Synthetic-250K'
                dataset = 'GW-Billing-Source-250K'
                seed = 42002L
            }
        }
        'SqlServer' {
            return @{
                application = 'Claims Operations'
                connection = @{
                    name = 'GW-Claims-SQLServer'
                    kind = 'SQLSERVER'
                    jdbcUrl = 'jdbc:sqlserver://localhost:1433;databaseName=GWClaims;encrypt=true;trustServerCertificate=true'
                    username = 'sa'
                    password = 'GW-SqlServer-2026!'
                    role = 'BOTH'
                    environment = 'QA'
                    tags = 'guidewire,acceptance,claims,gw'
                }
                sourceSchema = 'gw_claim_src'
                tablePrefix = 'cc_'
                jobName = 'GW-Claims-Synthetic-250K'
                dataset = 'GW-Claims-Source-250K'
                seed = 42003L
            }
        }
    }
}

function Get-CharacterLength([object]$Column) {
    $size = 20
    if ($null -ne $Column.size) {
        try { $size = [int]$Column.size } catch { $size = 20 }
    }
    if ($size -le 0 -or $size -gt 80) { $size = 24 }
    return [Math]::Max(4, [Math]::Min(40, $size))
}

function New-GeneratorColumn($Column, $ForeignKey, [string]$TableName) {
    $name = [string]$Column.column
    $upper = $name.ToUpperInvariant()
    $type = ([string]$Column.type).ToUpperInvariant()
    $primary = $upper -eq 'ID'
    $generator = 'ALPHANUMERIC'
    $param1 = [string](Get-CharacterLength $Column)
    $param2 = $null

    if ($ForeignKey) {
        $generator = 'SEQUENCE'
        $param1 = $null
    } elseif ($primary) {
        $generator = 'SEQUENCE'
        $param1 = $null
    } elseif ($upper -eq 'PUBLIC_ID') {
        $generator = 'UUID'
        $param1 = $null
    } elseif ($upper -eq 'TENANT_ID') {
        $generator = 'LITERAL'; $param1 = 'GW-DEMO'
    } elseif ($upper -match 'STATUS|STATE$|LIFECYCLE') {
        $generator = 'STATUS'; $param1 = 'ACTIVE|PENDING|CLOSED|CANCELLED'
    } elseif ($upper -eq 'VERSION_NO') {
        $generator = 'LITERAL'; $param1 = '1'
    } elseif ($upper -eq 'RETIRED') {
        $generator = 'LITERAL'; $param1 = $(if ($type -match 'BOOL|BIT') { 'false' } else { '0' })
    } elseif ($upper -match 'DOCUMENT_BODY|BINARY|BLOB|BYTEA|VARBINARY') {
        $generator = 'NULL'; $param1 = $null
    } elseif ($upper -match 'PAYLOAD_JSON|JSON') {
        if ($type -match 'JSONB|OTHER') { $generator = 'NULL'; $param1 = $null }
        else { $generator = 'JSON_OBJECT'; $param1 = $null }
    } elseif ($upper -match 'DETAIL_TEXT|DESCRIPTION|COMMENT|NOTE|MESSAGE|REASON') {
        $generator = 'LOREM_SENTENCE'; $param1 = '12'
    } elseif ($upper -match 'FIRST_NAME|GIVEN_NAME') {
        $generator = 'FIRST_NAME'; $param1 = 'US'
    } elseif ($upper -match 'LAST_NAME|SURNAME|FAMILY_NAME') {
        $generator = 'LAST_NAME'; $param1 = 'US'
    } elseif ($upper -match 'FULL_NAME|DISPLAY_NAME|CONTACT_NAME|BENEFICIARY_NAME') {
        $generator = 'FULL_NAME'; $param1 = 'US'; $param2 = 'ANY'
    } elseif ($upper -match 'EMAIL') {
        $generator = 'EMAIL'; $param1 = $null
    } elseif ($upper -match 'PHONE|MOBILE|FAX') {
        $generator = 'PHONE_US'; $param1 = $null
    } elseif ($upper -match 'SSN|SOCIAL_SECURITY|TAX_IDENTIFIER') {
        $generator = 'SSN'; $param1 = $null
    } elseif ($upper -match 'DATE_OF_BIRTH|BIRTH_DATE|DOB') {
        $generator = 'DOB_ADULT'; $param1 = $null
    } elseif ($upper -match 'ADDRESS_LINE|STREET') {
        $generator = 'STREET_ADDRESS'; $param1 = $null
    } elseif ($upper -eq 'CITY' -or $upper -match '_CITY$') {
        $generator = 'CITY'; $param1 = $null
    } elseif ($upper -match 'STATE_CODE|PROVINCE') {
        $generator = 'STATE'; $param1 = $null
    } elseif ($upper -match 'POSTAL_CODE|ZIP') {
        $generator = 'ZIP'; $param1 = $null
    } elseif ($upper -match 'COUNTRY_CODE') {
        $generator = 'LITERAL'; $param1 = 'US'
    } elseif ($upper -match 'CURRENCY') {
        $generator = 'LITERAL'; $param1 = 'USD'
    } elseif ($upper -match 'ROUTING') {
        $generator = 'ROUTING_NUMBER_US'; $param1 = $null
    } elseif ($upper -match 'CARD_NUMBER|CREDIT_CARD') {
        $generator = 'CREDIT_CARD_VISA'; $param1 = $null
    } elseif ($upper -match 'ACCOUNT_NUMBER') {
        $generator = 'ACCOUNT_NUMBER'; $param1 = '12'
    } elseif ($upper -match 'EXPIRATION_DATE|EXPIRY_DATE|END_DATE|CLOSE_DATE') {
        $generator = 'DATE_FUTURE'; $param1 = '730'
    } elseif ($upper -match 'EFFECTIVE_DATE|START_DATE|OPEN_DATE|LOSS_DATE|DUE_DATE|POSTED_DATE|TRANSACTION_DATE|_DATE$') {
        $generator = 'DATE_BETWEEN'; $param1 = '2024-01-01'; $param2 = '2026-01-01'
    } elseif ($type -match 'TIMESTAMP|DATETIME') {
        $generator = 'TIMESTAMP_RECENT'; $param1 = '525600'
    } elseif ($type -match 'DATE') {
        $generator = 'DATE_BETWEEN'; $param1 = '2024-01-01'; $param2 = '2026-01-01'
    } elseif ($type -match 'BOOL|BIT') {
        $generator = 'BOOLEAN_WEIGHTED'; $param1 = '10'
    } elseif ($upper -match 'PERCENT|RATE|FACTOR') {
        $generator = 'PERCENT'; $param1 = $null
    } elseif ($type -match 'NUMBER|NUMERIC|DECIMAL|MONEY|FLOAT|DOUBLE|REAL') {
        if ($upper -match 'AMOUNT|BALANCE|PREMIUM|PAYMENT|COST|LIMIT|RESERVE|EXPOSURE|DEDUCTIBLE|TOTAL') {
            $generator = 'NORMAL_DECIMAL'; $param1 = '5000'; $param2 = '500'
        } else {
            $generator = 'NORMAL_INT'; $param1 = '25'; $param2 = '5'
        }
    } elseif ($type -match 'INT') {
        $generator = 'NORMAL_INT'; $param1 = '25'; $param2 = '5'
    } elseif ($upper -match 'POLICY_NUMBER|CLAIM_NUMBER|INVOICE_NUMBER|PARTY_KEY|EXTERNAL_REF|REFERENCE|_KEY$|_NUMBER$') {
        $generator = 'PADDED_SEQUENCE'; $param1 = '14'; $param2 = $null
    }

    return [ordered]@{
        name = $name
        generator = $generator
        param1 = $param1
        param2 = $param2
        primaryKey = $primary
        fkTable = $(if ($ForeignKey) { [string]$ForeignKey.refTable } else { $null })
        fkColumn = $(if ($ForeignKey) { [string]$ForeignKey.refColumn } else { $null })
        sqlType = [string]$Column.type
        fkMin = $null
        fkMax = $null
    }
}

function New-ApplicationPlan($Session, $Connection, [hashtable]$Definition, [object[]]$ManifestRows, [switch]$Smoke) {
    $schema = $Definition.sourceSchema
    $catalog = @(Invoke-Forge -Session $Session -Method GET -Path "/api/datasources/$($Connection.id)/tables?schema=$([uri]::EscapeDataString($schema))")
    $manifestByTable = @{}
    foreach ($row in $ManifestRows) { $manifestByTable[$row.source_table.ToLowerInvariant()] = $row }
    $selected = @($catalog | Where-Object {
        $_.table -and $manifestByTable.ContainsKey(([string]$_.table).ToLowerInvariant())
    } | Sort-Object table)
    if ($selected.Count -ne 50) {
        throw "$($Definition.connection.name) exposes $($selected.Count) of 50 expected source tables in $schema"
    }

    $tables = @()
    foreach ($table in $selected) {
        $tableName = [string]$table.table
        $escaped = [uri]::EscapeDataString($tableName)
        $columns = @(Invoke-Forge -Session $Session -Method GET -Path "/api/datasources/$($Connection.id)/tables/$escaped/columns?schema=$([uri]::EscapeDataString($schema))")
        $fks = @(Invoke-Forge -Session $Session -Method GET -Path "/api/datasources/$($Connection.id)/tables/$escaped/fks?schema=$([uri]::EscapeDataString($schema))")
        $fkByColumn = @{}
        foreach ($fk in $fks) { $fkByColumn[([string]$fk.column).ToLowerInvariant()] = $fk }
        $generatedColumns = @()
        foreach ($column in ($columns | Sort-Object ordinal)) {
            $fk = $fkByColumn[([string]$column.column).ToLowerInvariant()]
            $generatedColumns += New-GeneratorColumn -Column $column -ForeignKey $fk -TableName $tableName
        }
        $plannedRows = if ($Smoke) { 5L } else { [long]$manifestByTable[$tableName.ToLowerInvariant()].planned_rows }
        $tables += [ordered]@{
            name = $tableName
            rowCount = $plannedRows
            columns = $generatedColumns
        }
    }

    return [ordered]@{
        dataset = $(if ($Smoke) { "$($Definition.dataset)-Smoke" } else { $Definition.dataset })
        tables = $tables
        seed = [long]$Definition.seed
        receiver = 'DB'
        targetDataSourceId = [long]$Connection.id
        targetSchema = $schema
        createTable = $false
        dropTable = $false
        prepMode = 'DELETE'
        loadAction = 'REPLACE'
        targetPrep = 'DELETE'
        keyColumns = @('ID')
        batchSize = 1000
        commitEveryRows = 0
        continueOnError = $false
        maxRejects = 0
        fastLoad = $false
        executionMode = $(if ($Smoke) { 'SINGLE' } else { 'LOCAL_PARTITIONED' })
        partitionCount = $(if ($Smoke) { $null } else { 4 })
        partitionSize = $(if ($Smoke) { $null } else { 2000L })
        targetSystems = @()
    }
}

function Save-AndApproveJob($AdminSession, $ReviewerSession, [hashtable]$Definition, $Plan) {
    if (-not $Definition.jobName.StartsWith('GW-')) { throw "Saved job must start with GW-" }
    $request = @{
        name = $Definition.jobName
        description = "Guidewire-style $($Definition.application) source generation. Saved by the GW acceptance runner."
        plan = $Plan
    }
    $jobs = @(Invoke-Forge -Session $AdminSession -Method GET -Path '/api/synthetic/saved-jobs')
    $existing = $jobs | Where-Object { $_.name -eq $Definition.jobName } | Select-Object -First 1
    if ($existing) {
        $saved = Invoke-Forge -Session $AdminSession -Method PUT -Path "/api/synthetic/saved-jobs/$($existing.id)" -Body $request
    } else {
        $saved = Invoke-Forge -Session $AdminSession -Method POST -Path '/api/synthetic/saved-jobs' -Body $request
    }
    Invoke-Forge -Session $AdminSession -Method POST -Path "/api/synthetic/saved-jobs/$($saved.id)/approval/request" -Body @{
        note = "GW acceptance maker request for $($Definition.application)"
    } | Out-Null
    $approved = Invoke-Forge -Session $ReviewerSession -Method POST -Path "/api/synthetic/saved-jobs/$($saved.id)/approval/approve" -Body @{
        note = "GW acceptance schema, RI, generator, and row-count plan reviewed"
    }
    Save-Evidence -Name "$($Definition.jobName)-saved" -Value $approved | Out-Null
    return $approved
}

function Wait-GenerationJob($Session, [string]$JobId, [int]$TimeoutMinutes = 45) {
    $deadline = (Get-Date).AddMinutes($TimeoutMinutes)
    $last = $null
    do {
        $last = Invoke-Forge -Session $Session -Method GET -Path "/api/synthetic/jobs/$JobId"
        Write-Host ("{0,3}%  {1,-24} {2}" -f [int]$last.percent, [string]$last.stage, [string]$last.detail)
        if ($last.status -in @('COMPLETED', 'FAILED', 'CANCELLED')) { break }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    if ($last.status -notin @('COMPLETED', 'FAILED', 'CANCELLED')) { throw "Generation job $JobId timed out" }
    Save-Evidence -Name "GW-run-$JobId" -Value $last | Out-Null
    if ($last.status -ne 'COMPLETED') { throw "Generation job $JobId ended as $($last.status): $($last.error)" }
    return $last
}

if (-not (Test-Path -LiteralPath $manifestPath)) { throw "Manifest not found: $manifestPath" }
$manifest = @(Import-Csv -LiteralPath $manifestPath)

Write-Step 'Login and maker-checker setup'
$admin = New-ForgeSession -Username 'admin' -Password 'admin123'
$reviewer = Ensure-GwReviewer -AdminSession $admin

$results = @()
foreach ($application in $Applications) {
    $definition = Get-ApplicationDefinition $application
    Write-Step "Configure $($definition.connection.name)"
    $connection = Ensure-Connection -Session $admin -Definition $definition.connection
    $manifestRows = @($manifest | Where-Object { $_.application -eq $definition.application })
    if ($manifestRows.Count -ne 50) { throw "$($definition.application) has $($manifestRows.Count) manifest rows, expected 50" }

    Write-Step "Build metadata-driven plan for $($definition.application)"
    $plan = New-ApplicationPlan -Session $admin -Connection $connection -Definition $definition -ManifestRows $manifestRows
    Save-Evidence -Name "$($definition.jobName)-plan" -Value $plan | Out-Null
    $summary = Invoke-Forge -Session $admin -Method POST -Path '/api/synthetic/plan-summary' -Body $plan
    Save-Evidence -Name "$($definition.jobName)-plan-summary" -Value $summary | Out-Null
    if ([long]$summary.plannedRows -ne 250000L) {
        throw "$($definition.jobName) planned $($summary.plannedRows) rows instead of 250000"
    }

    Write-Step "Save and approve $($definition.jobName)"
    $saved = Save-AndApproveJob -AdminSession $admin -ReviewerSession $reviewer -Definition $definition -Plan $plan
    $result = [ordered]@{
        application = $definition.application
        connection = $definition.connection.name
        connectionId = $connection.id
        syntheticJob = $definition.jobName
        savedJobId = $saved.id
        plannedRows = [long]$summary.plannedRows
        approval = $saved.approvalStatus
        runId = $null
        status = 'SAVED'
    }

    if ($RunJobs -or $SmokeOnly) {
        if (-not $SkipSmoke) {
            Write-Step "Smoke test all 50 $($definition.application) tables"
            $smokePlan = New-ApplicationPlan -Session $admin -Connection $connection -Definition $definition -ManifestRows $manifestRows -Smoke
            $smokeStart = Invoke-Forge -Session $admin -Method POST -Path '/api/synthetic/generate/start' -Body $smokePlan
            Wait-GenerationJob -Session $admin -JobId ([string]$smokeStart.id) -TimeoutMinutes 15 | Out-Null
            $result.status = 'SMOKE_COMPLETED'
        }
    }
    if ($RunJobs -and -not $SmokeOnly) {
        Write-Step "Run $($definition.jobName)"
        $started = Invoke-Forge -Session $admin -Method POST -Path "/api/synthetic/saved-jobs/$($saved.id)/run"
        $completed = Wait-GenerationJob -Session $admin -JobId ([string]$started.id)
        $result.runId = $started.id
        $result.status = $completed.status
    }
    $results += $result
}

$summaryPath = Save-Evidence -Name 'GW-component-summary' -Value @{
    generatedAt = (Get-Date).ToUniversalTime().ToString('o')
    prefix = 'GW-'
    applications = $results
}

Write-Step 'GW acceptance component result'
$results | ForEach-Object { [pscustomobject]$_ } |
    Format-Table application, connection, syntheticJob, plannedRows, approval, status -AutoSize
Write-Host "Evidence: $summaryPath" -ForegroundColor Green
