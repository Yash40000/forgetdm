param(
    [string]$BaseUrl = "http://localhost:8088",
    [string]$Username = "admin",
    [string]$Password = "admin123",
    [long]$DatabaseSeed = 881177,
    [long]$MainframeSeed = 991177,
    [int]$TransactionRows = 60000
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$resultDir = Join-Path $root "test-results\hybrid-banking-generation"
$downloadDir = Join-Path $env:USERPROFILE "Downloads\ForgeTDM-Hybrid-Banking-Test"
New-Item -ItemType Directory -Force -Path $resultDir, $downloadDir | Out-Null

function Invoke-Forge {
    param(
        [Microsoft.PowerShell.Commands.WebRequestSession]$Session,
        [string]$Method,
        [string]$Path,
        $Body = $null
    )
    $args = @{
        Uri = "$BaseUrl$Path"
        Method = $Method
        WebSession = $Session
        ErrorAction = "Stop"
    }
    if ($null -ne $Body) {
        $args.ContentType = "application/json"
        $args.Body = $Body | ConvertTo-Json -Depth 100 -Compress
    }
    try {
        $result = Invoke-RestMethod @args
        if ($result -is [Array]) {
            foreach ($item in $result) { Write-Output $item }
            return
        }
        return $result
    } catch {
        $detail = $_.ErrorDetails.Message
        if (-not $detail) { $detail = $_.Exception.Message }
        throw "ForgeTDM $Method $Path failed: $detail"
    }
}

function New-Column {
    param(
        [string]$Name,
        [string]$Generator,
        [string]$Param1 = $null,
        [string]$Param2 = $null,
        [bool]$PrimaryKey = $false,
        [string]$FkTable = $null,
        [string]$FkColumn = $null,
        [string]$SqlType = "VARCHAR(120)",
        [Nullable[int]]$FkMin = $null,
        [Nullable[int]]$FkMax = $null
    )
    [ordered]@{
        name = $Name
        generator = $Generator
        param1 = $Param1
        param2 = $Param2
        primaryKey = $PrimaryKey
        fkTable = $FkTable
        fkColumn = $FkColumn
        sqlType = $SqlType
        fkMin = $FkMin
        fkMax = $FkMax
    }
}

function New-AccountTable {
    param(
        [string]$Name,
        [long]$Rows,
        [string]$ProductCode,
        [string]$AccountPrefix,
        [array]$ExtraColumns = @()
    )
    $columns = @(
        (New-Column -Name "account_id" -Generator "SEQUENCE" -PrimaryKey $true -SqlType "BIGINT"),
        (New-Column -Name "account_number" -Generator "PADDED_SEQUENCE" -Param1 "12" -Param2 $AccountPrefix -SqlType "VARCHAR(18)"),
        (New-Column -Name "customer_id" -Generator "SEQUENCE" -FkTable "ftdm_bank_customer" -FkColumn "customer_id" -SqlType "BIGINT"),
        (New-Column -Name "branch_id" -Generator "SEQUENCE" -FkTable "ftdm_bank_branch" -FkColumn "branch_id" -SqlType "BIGINT"),
        (New-Column -Name "crf_key" -Generator "LOOKUP" -Param1 "crf_key" -Param2 "customer_id" -SqlType "VARCHAR(20)"),
        (New-Column -Name "product_code" -Generator "LITERAL" -Param1 $ProductCode -SqlType "VARCHAR(20)"),
        (New-Column -Name "account_status" -Generator "WEIGHTED" -Param1 "ACTIVE:88|DORMANT:7|CLOSED:5" -SqlType "VARCHAR(12)"),
        (New-Column -Name "opened_on" -Generator "DATE_BETWEEN" -Param1 "2001-01-01" -Param2 "2026-07-15" -SqlType "DATE"),
        (New-Column -Name "current_balance" -Generator "DECIMAL_RANGE" -Param1 "0" -Param2 "250000" -SqlType "NUMERIC(15,2)")
    )
    $columns += $ExtraColumns
    [ordered]@{ name = $Name; rowCount = $Rows; columns = $columns }
}

function Query-Rows {
    param($Session, [string]$Sql)
    $response = Invoke-Forge -Session $Session -Method POST -Path "/api/query/run" -Body @{ dataSourceId = 3; sql = $Sql }
    $propertyNames = @($response.PSObject.Properties.Name)
    if ($propertyNames -contains "rows" -and $propertyNames -contains "columns") {
        foreach ($rawRow in $response.rows) {
            $mapped = [ordered]@{}
            for ($index = 0; $index -lt $response.columns.Count; $index++) {
                $mapped[[string]$response.columns[$index]] = $rawRow[$index]
            }
            Write-Output ([pscustomobject]$mapped)
        }
        return
    }
    $items = if ($response.data) { $response.data } else { $response }
    if ($items -is [Array]) {
        foreach ($item in $items) { Write-Output $item }
        return
    }
    return $items
}

function Scalar {
    param($Session, [string]$Sql)
    $rows = @(Query-Rows -Session $Session -Sql $Sql)
    if ($rows.Count -eq 0) { return $null }
    $property = $rows[0].PSObject.Properties | Select-Object -First 1
    return $property.Value
}

function Wait-SyntheticJob {
    param($Session, [string]$JobId, [int]$TimeoutSeconds = 420)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $job = Invoke-Forge -Session $Session -Method GET -Path "/api/synthetic/jobs/$JobId"
        if ($job.status -in @("COMPLETED", "FAILED", "CANCELLED")) { return $job }
        Start-Sleep -Milliseconds 750
    } while ((Get-Date) -lt $deadline)
    throw "Synthetic job $JobId did not finish within $TimeoutSeconds seconds"
}

function Test-Luhn {
    param([string]$Value)
    if ($Value -notmatch '^\d+$') { return $false }
    $sum = 0
    $alternate = $false
    for ($i = $Value.Length - 1; $i -ge 0; $i--) {
        $digit = [int]::Parse($Value.Substring($i, 1))
        if ($alternate) {
            $digit *= 2
            if ($digit -gt 9) { $digit -= 9 }
        }
        $sum += $digit
        $alternate = -not $alternate
    }
    return ($sum % 10) -eq 0
}

function Get-EbcdicText {
    param([byte[]]$Bytes, [int]$Offset, [int]$Length)
    return $script:Ebcdic.GetString($Bytes, $Offset, $Length).Trim()
}

function Get-PackedDecimal {
    param([byte[]]$Bytes, [int]$Offset, [int]$Length, [int]$Scale)
    $digits = New-Object System.Text.StringBuilder
    for ($i = 0; $i -lt $Length; $i++) {
        $b = $Bytes[$Offset + $i]
        $high = ($b -shr 4) -band 0x0F
        $low = $b -band 0x0F
        if ($high -gt 9) { throw "Invalid packed decimal high nibble at record byte $($Offset + $i)" }
        [void]$digits.Append($high)
        if ($i -lt ($Length - 1)) {
            if ($low -gt 9) { throw "Invalid packed decimal low nibble at record byte $($Offset + $i)" }
            [void]$digits.Append($low)
        } elseif ($low -notin @(0x0C, 0x0D, 0x0F)) {
            throw "Invalid packed decimal sign nibble $low"
        }
    }
    $raw = [decimal]::Parse($digits.ToString())
    $value = $raw / [decimal][math]::Pow(10, $Scale)
    $sign = $Bytes[$Offset + $Length - 1] -band 0x0F
    if ($sign -eq 0x0D) { $value = -$value }
    return $value
}

$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
Invoke-Forge -Session $session -Method POST -Path "/api/auth/login" -Body @{ username = $Username; password = $Password } | Out-Null

$branch = [ordered]@{
    name = "ftdm_bank_branch"
    rowCount = 50
    columns = @(
        (New-Column -Name "branch_id" -Generator "SEQUENCE" -PrimaryKey $true -SqlType "BIGINT"),
        (New-Column -Name "branch_code" -Generator "PADDED_SEQUENCE" -Param1 "5" -Param2 "BR-" -SqlType "VARCHAR(10)"),
        (New-Column -Name "branch_name" -Generator "TEMPLATE" -Param1 'Forge Bank ${branch_code}' -SqlType "VARCHAR(80)"),
        (New-Column -Name "city" -Generator "CITY" -SqlType "VARCHAR(60)"),
        (New-Column -Name "state_code" -Generator "STATE" -SqlType "VARCHAR(2)"),
        (New-Column -Name "postal_code" -Generator "ZIP" -SqlType "VARCHAR(10)"),
        (New-Column -Name "status" -Generator "LITERAL" -Param1 "ACTIVE" -SqlType "VARCHAR(12)")
    )
}

$customer = [ordered]@{
    name = "ftdm_bank_customer"
    rowCount = 10000
    columns = @(
        (New-Column -Name "customer_id" -Generator "SEQUENCE" -PrimaryKey $true -SqlType "BIGINT"),
        (New-Column -Name "crf_key" -Generator "PADDED_SEQUENCE" -Param1 "12" -Param2 "CRF-" -SqlType "VARCHAR(20)"),
        (New-Column -Name "first_name" -Generator "FIRST_NAME" -Param1 "US" -Param2 "ANY" -SqlType "VARCHAR(60)"),
        (New-Column -Name "last_name" -Generator "LAST_NAME" -Param1 "US" -SqlType "VARCHAR(60)"),
        (New-Column -Name "full_name" -Generator "TEMPLATE" -Param1 '${first_name} ${last_name}' -SqlType "VARCHAR(130)"),
        (New-Column -Name "email" -Generator "TEMPLATE" -Param1 '${first_name:lower}.${last_name:lower}.${customer_id}@example.test' -SqlType "VARCHAR(160)"),
        (New-Column -Name "phone" -Generator "PHONE_US" -SqlType "VARCHAR(24)"),
        (New-Column -Name "date_of_birth" -Generator "DOB_ADULT" -SqlType "DATE"),
        (New-Column -Name "tax_id" -Generator "SSN" -SqlType "VARCHAR(11)"),
        (New-Column -Name "kyc_status" -Generator "WEIGHTED" -Param1 "VERIFIED:94|REVIEW:5|BLOCKED:1" -SqlType "VARCHAR(12)"),
        (New-Column -Name "customer_status" -Generator "WEIGHTED" -Param1 "ACTIVE:92|INACTIVE:6|CLOSED:2" -SqlType "VARCHAR(12)")
    )
}

$savExtras = @((New-Column -Name "interest_rate" -Generator "DECIMAL_RANGE" -Param1 "0.10" -Param2 "5.25" -SqlType "NUMERIC(7,4)"))
$ddaExtras = @((New-Column -Name "overdraft_limit" -Generator "DECIMAL_RANGE" -Param1 "0" -Param2 "5000" -SqlType "NUMERIC(12,2)"))
$visaExtras = @(
    (New-Column -Name "card_number" -Generator "CREDIT_CARD_VISA" -SqlType "VARCHAR(19)"),
    (New-Column -Name "credit_limit" -Generator "DECIMAL_RANGE" -Param1 "500" -Param2 "50000" -SqlType "NUMERIC(12,2)"),
    (New-Column -Name "expiry_date" -Generator "DATE_FUTURE" -Param1 "1825" -SqlType "DATE")
)
$mcExtras = @(
    (New-Column -Name "card_number" -Generator "CREDIT_CARD_MC" -SqlType "VARCHAR(19)"),
    (New-Column -Name "credit_limit" -Generator "DECIMAL_RANGE" -Param1 "500" -Param2 "40000" -SqlType "NUMERIC(12,2)"),
    (New-Column -Name "expiry_date" -Generator "DATE_FUTURE" -Param1 "1825" -SqlType "DATE")
)
$loanExtras = @(
    (New-Column -Name "original_principal" -Generator "DECIMAL_RANGE" -Param1 "1000" -Param2 "750000" -SqlType "NUMERIC(15,2)"),
    (New-Column -Name "interest_rate" -Generator "DECIMAL_RANGE" -Param1 "1.25" -Param2 "18.50" -SqlType "NUMERIC(7,4)"),
    (New-Column -Name "maturity_date" -Generator "DATE_FUTURE" -Param1 "7300" -SqlType "DATE")
)

$tables = @(
    $branch,
    $customer,
    (New-AccountTable -Name "ftdm_bank_sav_account" -Rows 4000 -ProductCode "SAV" -AccountPrefix "SAV" -ExtraColumns $savExtras),
    (New-AccountTable -Name "ftdm_bank_dda_account" -Rows 4000 -ProductCode "DDA" -AccountPrefix "DDA" -ExtraColumns $ddaExtras),
    (New-AccountTable -Name "ftdm_bank_credit_card_account" -Rows 3000 -ProductCode "CREDIT_CARD" -AccountPrefix "VIS" -ExtraColumns $visaExtras),
    (New-AccountTable -Name "ftdm_bank_mcd_account" -Rows 2500 -ProductCode "MCD" -AccountPrefix "MCD" -ExtraColumns $mcExtras),
    (New-AccountTable -Name "ftdm_bank_mcc_account" -Rows 2500 -ProductCode "MCC" -AccountPrefix "MCC" -ExtraColumns $mcExtras),
    (New-AccountTable -Name "ftdm_bank_loan_account" -Rows 3000 -ProductCode "LOAN" -AccountPrefix "LON" -ExtraColumns $loanExtras)
)

$plan = [ordered]@{
    dataset = "FTDM Hybrid Bank Master"
    tables = $tables
    seed = $DatabaseSeed
    receiver = "DB"
    targetDataSourceId = 3
    targetSchema = "public"
    createTable = $true
    dropTable = $true
    prepMode = "DROP_RECREATE"
    loadAction = "INSERT"
    targetPrep = "NONE"
    keyColumns = @()
    batchSize = 1000
    commitEveryRows = 5000
    continueOnError = $false
    maxRejects = 0
    fastLoad = $true
    executionMode = "SINGLE"
    partitionCount = $null
    partitionSize = $null
    targetSystems = @()
}

$savedJobs = @(Invoke-Forge -Session $session -Method GET -Path "/api/synthetic/saved-jobs")
$existingJob = $savedJobs | Where-Object { $_.name -eq "BANK-HYBRID-MASTER-29K" } | Select-Object -First 1
$savedPayload = @{
    name = "BANK-HYBRID-MASTER-29K"
    description = "Hybrid banking master data for SAV, DDA, credit card, MCD, MCC, and loan products. Mainframe transactions bind through product code and account id."
    plan = $plan
}
if ($existingJob) {
    $savedJob = Invoke-Forge -Session $session -Method PUT -Path "/api/synthetic/saved-jobs/$($existingJob.id)" -Body $savedPayload
} else {
    $savedJob = Invoke-Forge -Session $session -Method POST -Path "/api/synthetic/saved-jobs" -Body $savedPayload
}

$started = Invoke-Forge -Session $session -Method POST -Path "/api/synthetic/saved-jobs/$($savedJob.id)/run"
$dbJob = Wait-SyntheticJob -Session $session -JobId ([string]$started.id)
if ($dbJob.status -ne "COMPLETED") {
    throw "Master-data job failed: $($dbJob.message) $($dbJob.error)"
}

$expectedCounts = [ordered]@{
    ftdm_bank_branch = 50
    ftdm_bank_customer = 10000
    ftdm_bank_sav_account = 4000
    ftdm_bank_dda_account = 4000
    ftdm_bank_credit_card_account = 3000
    ftdm_bank_mcd_account = 2500
    ftdm_bank_mcc_account = 2500
    ftdm_bank_loan_account = 3000
}
$accountProducts = [ordered]@{
    ftdm_bank_sav_account = "SAV"
    ftdm_bank_dda_account = "DDA"
    ftdm_bank_credit_card_account = "CREDIT_CARD"
    ftdm_bank_mcd_account = "MCD"
    ftdm_bank_mcc_account = "MCC"
    ftdm_bank_loan_account = "LOAN"
}

$databaseChecks = New-Object System.Collections.Generic.List[object]
foreach ($entry in $expectedCounts.GetEnumerator()) {
    $actual = [long](Scalar -Session $session -Sql "SELECT COUNT(*) AS value FROM public.$($entry.Key)")
    $databaseChecks.Add([ordered]@{ check = "row_count"; object = $entry.Key; expected = $entry.Value; actual = $actual; passed = ($actual -eq $entry.Value) })
}
$nameMismatch = [long](Scalar -Session $session -Sql "SELECT COUNT(*) AS value FROM public.ftdm_bank_customer WHERE full_name <> first_name || ' ' || last_name")
$databaseChecks.Add([ordered]@{ check = "coherent_full_name"; object = "ftdm_bank_customer"; expected = 0; actual = $nameMismatch; passed = ($nameMismatch -eq 0) })
$crfDupes = [long](Scalar -Session $session -Sql "SELECT COUNT(*) AS value FROM (SELECT crf_key FROM public.ftdm_bank_customer GROUP BY crf_key HAVING COUNT(*) > 1) x")
$databaseChecks.Add([ordered]@{ check = "unique_crf_key"; object = "ftdm_bank_customer"; expected = 0; actual = $crfDupes; passed = ($crfDupes -eq 0) })

foreach ($entry in $accountProducts.GetEnumerator()) {
    $table = $entry.Key
    $product = $entry.Value
    $missingCustomer = [long](Scalar -Session $session -Sql "SELECT COUNT(*) AS value FROM public.$table a LEFT JOIN public.ftdm_bank_customer c ON c.customer_id=a.customer_id WHERE c.customer_id IS NULL")
    $missingBranch = [long](Scalar -Session $session -Sql "SELECT COUNT(*) AS value FROM public.$table a LEFT JOIN public.ftdm_bank_branch b ON b.branch_id=a.branch_id WHERE b.branch_id IS NULL")
    $crfMismatch = [long](Scalar -Session $session -Sql "SELECT COUNT(*) AS value FROM public.$table a JOIN public.ftdm_bank_customer c ON c.customer_id=a.customer_id WHERE a.crf_key IS DISTINCT FROM c.crf_key")
    $wrongProduct = [long](Scalar -Session $session -Sql "SELECT COUNT(*) AS value FROM public.$table WHERE product_code <> '$product' OR product_code IS NULL")
    $routeCount = [long](Scalar -Session $session -Sql "SELECT COUNT(*) AS value FROM public.$table WHERE account_id BETWEEN 1 AND 2000")
    $databaseChecks.Add([ordered]@{ check = "customer_fk"; object = $table; expected = 0; actual = $missingCustomer; passed = ($missingCustomer -eq 0) })
    $databaseChecks.Add([ordered]@{ check = "branch_fk"; object = $table; expected = 0; actual = $missingBranch; passed = ($missingBranch -eq 0) })
    $databaseChecks.Add([ordered]@{ check = "customer_crf_consistency"; object = $table; expected = 0; actual = $crfMismatch; passed = ($crfMismatch -eq 0) })
    $databaseChecks.Add([ordered]@{ check = "product_code"; object = $table; expected = 0; actual = $wrongProduct; passed = ($wrongProduct -eq 0) })
    $databaseChecks.Add([ordered]@{ check = "mainframe_routeable_account_range"; object = $table; expected = 2000; actual = $routeCount; passed = ($routeCount -eq 2000) })
}

$cardChecks = New-Object System.Collections.Generic.List[object]
$allCards = New-Object System.Collections.Generic.List[string]
foreach ($table in @("ftdm_bank_credit_card_account", "ftdm_bank_mcd_account", "ftdm_bank_mcc_account")) {
    $values = New-Object System.Collections.Generic.List[string]
    $offset = 0
    do {
        $cards = @(Query-Rows -Session $session -Sql "SELECT card_number FROM public.$table ORDER BY account_id LIMIT 1000 OFFSET $offset")
        foreach ($card in $cards) {
            $value = [string]$card.card_number
            $values.Add($value)
            $allCards.Add($value)
        }
        $offset += $cards.Count
    } while ($cards.Count -eq 1000)
    $invalid = @($values | Where-Object { -not (Test-Luhn $_) }).Count
    $duplicates = $values.Count - @($values | Sort-Object -Unique).Count
    $cardChecks.Add([ordered]@{ table = $table; rows = $values.Count; invalidLuhn = $invalid; duplicates = $duplicates; passed = ($invalid -eq 0 -and $duplicates -eq 0) })
}
$crossTableCardDuplicates = $allCards.Count - @($allCards | Sort-Object -Unique).Count
$cardChecks.Add([ordered]@{ table = "ALL_CARD_PRODUCTS"; rows = $allCards.Count; invalidLuhn = 0; duplicates = $crossTableCardDuplicates; passed = ($crossTableCardDuplicates -eq 0) })

$copybookSource = @'
       01 FTDM-BANK-TXN-RECORD.
          05 TXN-ID                 PIC X(18).
          05 PRODUCT-CODE           PIC X(12).
          05 PRODUCT-ACCOUNT-ID     PIC 9(6).
          05 TXN-DATE               PIC X(10).
          05 TXN-TYPE               PIC X(16).
          05 AMOUNT                 PIC S9(9)V99 COMP-3.
          05 CURRENCY-CODE          PIC X(3).
          05 CHANNEL-CODE           PIC X(10).
          05 MCC-CODE               PIC 9(4).
          05 STATUS-CODE            PIC X(10).
          05 DESCRIPTION            PIC X(24).
'@

$copybooks = @(Invoke-Forge -Session $session -Method GET -Path "/api/mainframe/copybooks")
$existingCopybook = $copybooks | Where-Object { $_.name -eq "FTDM-BANK-TRANSACTIONS-V1" } | Select-Object -First 1
$copybookBody = @{ name = "FTDM-BANK-TRANSACTIONS-V1"; source = $copybookSource; codePage = "Cp037" }
if ($existingCopybook) {
    $copybook = Invoke-Forge -Session $session -Method PUT -Path "/api/mainframe/copybooks/$($existingCopybook.id)" -Body $copybookBody
} else {
    $copybook = Invoke-Forge -Session $session -Method POST -Path "/api/mainframe/copybooks" -Body $copybookBody
}
if ([int]$copybook.recordLength -ne 119) { throw "Copybook LRECL expected 119 but parsed as $($copybook.recordLength)" }

$record = "FTDM-BANK-TXN-RECORD"
$mfColumns = @(
    @{ field = "$record.TXN-ID"; generator = "PADDED_SEQUENCE"; param1 = "14"; param2 = "TXN-" },
    @{ field = "$record.PRODUCT-CODE"; generator = "ENUM"; param1 = "SAV|DDA|CREDIT_CARD|MCD|MCC|LOAN"; param2 = $null },
    @{ field = "$record.PRODUCT-ACCOUNT-ID"; generator = "INT_RANGE"; param1 = "1"; param2 = "2000" },
    @{ field = "$record.TXN-DATE"; generator = "DATE_BETWEEN"; param1 = "2025-01-01"; param2 = "2026-07-15" },
    @{ field = "$record.TXN-TYPE"; generator = "CASE"; param1 = "$record.PRODUCT-CODE"; param2 = "SAV=DEPOSIT|DDA=WITHDRAWAL|CREDIT_CARD=PURCHASE|MCD=POS_DEBIT|MCC=PURCHASE|LOAN=PAYMENT|*=OTHER" },
    @{ field = "$record.AMOUNT"; generator = "DECIMAL_RANGE"; param1 = "0.01"; param2 = "50000" },
    @{ field = "$record.CURRENCY-CODE"; generator = "LITERAL"; param1 = "USD"; param2 = $null },
    @{ field = "$record.CHANNEL-CODE"; generator = "ENUM"; param1 = "ATM|BRANCH|ONLINE|MOBILE|POS"; param2 = $null },
    @{ field = "$record.MCC-CODE"; generator = "INT_RANGE"; param1 = "1000"; param2 = "9999" },
    @{ field = "$record.STATUS-CODE"; generator = "WEIGHTED"; param1 = "POSTED:93|PENDING:5|REVERSED:2"; param2 = $null },
    @{ field = "$record.DESCRIPTION"; generator = "CASE"; param1 = "$record.PRODUCT-CODE"; param2 = "SAV=SAVINGS TRANSACTION|DDA=CHECKING TRANSACTION|CREDIT_CARD=VISA CARD TRANSACTION|MCD=DEBIT CARD TRANSACTION|MCC=MASTERCARD TRANSACTION|LOAN=LOAN PAYMENT|*=BANK TRANSACTION" }
)
$mfRequest = @{
    copybookId = $copybook.id
    codePage = "Cp037"
    recfm = "FB"
    seed = $MainframeSeed
    rowCount = $TransactionRows
    columns = $mfColumns
    output = "DOWNLOAD"
    targetConnectionId = $null
    targetName = $null
}
$mfResult = Invoke-Forge -Session $session -Method POST -Path "/api/mainframe/generate-file" -Body $mfRequest

$datBytes = [Convert]::FromBase64String([string]$mfResult.postBase64)
$datPath = Join-Path $resultDir $mfResult.postName
$csvPath = Join-Path $resultDir $mfResult.preName
$cpyPath = Join-Path $resultDir $mfResult.copybookName
[IO.File]::WriteAllBytes($datPath, $datBytes)
[IO.File]::WriteAllText($csvPath, [string]$mfResult.preContent, [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText($cpyPath, [string]$mfResult.copybook, [Text.UTF8Encoding]::new($false))
Copy-Item -LiteralPath $datPath, $csvPath, $cpyPath -Destination $downloadDir -Force

$script:Ebcdic = [Text.Encoding]::GetEncoding(37)
$lrecl = 119
if (($datBytes.Length % $lrecl) -ne 0) { throw "Generated EBCDIC file is not aligned to LRECL $lrecl" }
$actualRecords = [int]($datBytes.Length / $lrecl)
$productCounts = @{}
$txnIds = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$invalidRecords = New-Object System.Collections.Generic.List[object]
$expectedTypes = @{ SAV = "DEPOSIT"; DDA = "WITHDRAWAL"; CREDIT_CARD = "PURCHASE"; MCD = "POS_DEBIT"; MCC = "PURCHASE"; LOAN = "PAYMENT" }
$validChannels = @("ATM", "BRANCH", "ONLINE", "MOBILE", "POS")
$validStatuses = @("POSTED", "PENDING", "REVERSED")

for ($row = 0; $row -lt $actualRecords; $row++) {
    $offset = $row * $lrecl
    $txnId = Get-EbcdicText -Bytes $datBytes -Offset ($offset + 0) -Length 18
    $product = Get-EbcdicText -Bytes $datBytes -Offset ($offset + 18) -Length 12
    $accountIdText = Get-EbcdicText -Bytes $datBytes -Offset ($offset + 30) -Length 6
    $txnDate = Get-EbcdicText -Bytes $datBytes -Offset ($offset + 36) -Length 10
    $txnType = Get-EbcdicText -Bytes $datBytes -Offset ($offset + 46) -Length 16
    $amount = Get-PackedDecimal -Bytes $datBytes -Offset ($offset + 62) -Length 6 -Scale 2
    $currency = Get-EbcdicText -Bytes $datBytes -Offset ($offset + 68) -Length 3
    $channel = Get-EbcdicText -Bytes $datBytes -Offset ($offset + 71) -Length 10
    $mcc = Get-EbcdicText -Bytes $datBytes -Offset ($offset + 81) -Length 4
    $status = Get-EbcdicText -Bytes $datBytes -Offset ($offset + 85) -Length 10
    $description = Get-EbcdicText -Bytes $datBytes -Offset ($offset + 95) -Length 24
    $accountId = if ($accountIdText -match '^\d{1,6}$') { [int]$accountIdText } else { -1 }

    if (-not $productCounts.ContainsKey($product)) { $productCounts[$product] = 0 }
    $productCounts[$product]++
    $issues = @()
    if (-not $txnIds.Add($txnId)) { $issues += "duplicate txn id" }
    if (-not $expectedTypes.ContainsKey($product)) { $issues += "unknown product" }
    elseif ($txnType -ne $expectedTypes[$product]) { $issues += "transaction type mismatch" }
    if ($accountId -lt 1 -or $accountId -gt 2000) { $issues += "account route out of range" }
    if ($txnDate -notmatch '^\d{4}-\d{2}-\d{2}$') { $issues += "invalid date" }
    if ($amount -lt 0.01 -or $amount -gt 50000) { $issues += "amount out of range" }
    if ($currency -ne "USD") { $issues += "currency mismatch" }
    if ($channel -notin $validChannels) { $issues += "invalid channel" }
    if ($mcc -notmatch '^\d{4}$') { $issues += "invalid MCC" }
    if ($status -notin $validStatuses) { $issues += "invalid status" }
    if ([string]::IsNullOrWhiteSpace($description)) { $issues += "empty description" }
    if ($issues.Count -gt 0 -and $invalidRecords.Count -lt 100) {
        $invalidRecords.Add([ordered]@{ row = $row + 1; txnId = $txnId; product = $product; issues = $issues })
    }
}

$repeatRequest = @{} + $mfRequest
$repeatRequest.rowCount = [Math]::Min(100, $TransactionRows)
$repeatResult = Invoke-Forge -Session $session -Method POST -Path "/api/mainframe/generate-file" -Body $repeatRequest
$repeatBytes = [Convert]::FromBase64String([string]$repeatResult.postBase64)
$prefixBytes = New-Object byte[] $repeatBytes.Length
[Array]::Copy($datBytes, 0, $prefixBytes, 0, $repeatBytes.Length)
$reproducible = [Convert]::ToBase64String($prefixBytes) -ceq [Convert]::ToBase64String($repeatBytes)

$entities = @(Invoke-Forge -Session $session -Method GET -Path "/api/business-entities")
$entity = $entities | Where-Object { $_.name -eq "FTDM Hybrid Banking Customer" } | Select-Object -First 1
$entityPayload = @{
    name = "FTDM Hybrid Banking Customer"
    description = "Customer aggregate spanning PostgreSQL master data and a routed EBCDIC transaction stream for SAV, DDA, credit card, MCD, MCC, and loan products."
    domain = "Retail Banking"
    ownerUsername = $Username
    rootTable = "ftdm_bank_customer"
    businessKeyColumns = "crf_key"
    status = "ACTIVE"
}
if ($entity) {
    $entity = Invoke-Forge -Session $session -Method PUT -Path "/api/business-entities/$($entity.id)" -Body (@{} + $entityPayload + @{ id = $entity.id })
} else {
    $entity = Invoke-Forge -Session $session -Method POST -Path "/api/business-entities" -Body $entityPayload
}

$members = New-Object System.Collections.Generic.List[object]
$ordinal = 0
$members.Add(@{ entityId = $entity.id; systemName = "PostgreSQL Banking Master"; dataSourceId = 3; schemaName = "public"; logicalRole = "customer"; tableName = "ftdm_bank_customer"; tableAlias = "Customer master"; keyColumns = "customer_id,crf_key"; joinToRole = $null; relationshipJson = $null; includeInSubset = $true; includeInSynthetic = $true; ordinalNo = $ordinal++ })
$members.Add(@{ entityId = $entity.id; systemName = "PostgreSQL Banking Master"; dataSourceId = 3; schemaName = "public"; logicalRole = "branch"; tableName = "ftdm_bank_branch"; tableAlias = "Branch master"; keyColumns = "branch_id"; joinToRole = $null; relationshipJson = $null; includeInSubset = $true; includeInSynthetic = $true; ordinalNo = $ordinal++ })
foreach ($entry in $accountProducts.GetEnumerator()) {
    $role = $entry.Value.ToLowerInvariant() + "_account"
    $relationship = @{ type = "FK_AND_CRF"; childColumns = @("customer_id", "crf_key"); parentRole = "customer"; parentColumns = @("customer_id", "crf_key"); productCode = $entry.Value } | ConvertTo-Json -Compress
    $members.Add(@{ entityId = $entity.id; systemName = "PostgreSQL Banking Master"; dataSourceId = 3; schemaName = "public"; logicalRole = $role; tableName = $entry.Key; tableAlias = "$($entry.Value) accounts"; keyColumns = "account_id,customer_id,crf_key"; joinToRole = "customer"; relationshipJson = $relationship; includeInSubset = $true; includeInSynthetic = $true; ordinalNo = $ordinal++ })
}
$mfRelationship = @{ type = "PRODUCT_ROUTER"; compositeKey = @("PRODUCT-CODE", "PRODUCT-ACCOUNT-ID"); routes = @{ SAV = "sav_account"; DDA = "dda_account"; CREDIT_CARD = "credit_card_account"; MCD = "mcd_account"; MCC = "mcc_account"; LOAN = "loan_account" }; customerPath = "transaction -> product account -> customer"; copybookId = $copybook.id; recfm = "FB"; lrecl = 119; codePage = "Cp037" } | ConvertTo-Json -Depth 10 -Compress
$members.Add(@{ entityId = $entity.id; systemName = "Mainframe Transaction Application"; dataSourceId = $null; schemaName = "Cp037-FB-LRECL119"; logicalRole = "transactions"; tableName = $mfResult.postName; tableAlias = "Unified EBCDIC transactions"; keyColumns = "PRODUCT-CODE,PRODUCT-ACCOUNT-ID"; joinToRole = "product account router"; relationshipJson = $mfRelationship; includeInSubset = $true; includeInSynthetic = $true; ordinalNo = $ordinal++ })
$savedMembers = Invoke-Forge -Session $session -Method PUT -Path "/api/business-entities/$($entity.id)/members" -Body $members.ToArray()

$databasePassed = @($databaseChecks | Where-Object { -not $_.passed }).Count -eq 0
$cardsPassed = @($cardChecks | Where-Object { -not $_.passed }).Count -eq 0
$productsPresent = @($expectedTypes.Keys | Where-Object { -not $productCounts.ContainsKey($_) }).Count -eq 0
$mainframePassed = $actualRecords -eq $TransactionRows -and $invalidRecords.Count -eq 0 -and $productsPresent -and $reproducible

$report = [ordered]@{
    testName = "ForgeTDM Hybrid Banking Generation Acceptance"
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    status = if ($databasePassed -and $cardsPassed -and $mainframePassed) { "PASSED" } else { "FAILED" }
    assumptions = @(
        "MCD is modeled as a Mastercard debit product.",
        "MCC is modeled as a Mastercard credit product.",
        "The user term 'loads' is modeled as loans.",
        "Customer/branch/product/account masters are relational; transactions are fixed-block EBCDIC."
    )
    savedComponents = [ordered]@{
        syntheticJobId = $savedJob.id
        syntheticJobName = $savedJob.name
        runId = $dbJob.id
        copybookId = $copybook.id
        copybookName = $copybook.name
        businessEntityId = $entity.id
        businessEntityName = $entity.name
    }
    relationalMaster = [ordered]@{
        target = "targetdb (data source 3) / public"
        plannedRows = 29050
        runStatus = $dbJob.status
        elapsed = [ordered]@{ startedAt = $dbJob.startedAt; finishedAt = $dbJob.finishedAt }
        checks = $databaseChecks.ToArray()
        cardChecks = $cardChecks.ToArray()
        passed = ($databasePassed -and $cardsPassed)
    }
    mainframeTransactions = [ordered]@{
        rows = $actualRecords
        bytes = $datBytes.Length
        lrecl = $lrecl
        recfm = "FB"
        codePage = "Cp037"
        productCounts = $productCounts
        invalidRecordSample = $invalidRecords.ToArray()
        deterministicPrefixReplay = $reproducible
        everyTransactionRouteable = ($invalidRecords.Count -eq 0 -and $productsPresent)
        passed = $mainframePassed
    }
    binding = [ordered]@{
        entityId = $entity.id
        members = @($savedMembers | ForEach-Object { $_ }).Count
        transactionKey = "PRODUCT-CODE + PRODUCT-ACCOUNT-ID"
        customerTrace = "EBCDIC transaction -> routed product account -> customer_id/crf_key -> customer master"
    }
    artifacts = [ordered]@{
        resultDirectory = $resultDir
        downloadDirectory = $downloadDir
        ebcdicFile = $datPath
        logicalCsv = $csvPath
        copybook = $cpyPath
    }
}

$reportPath = Join-Path $resultDir "validation-report.json"
$report | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $reportPath -Encoding utf8
Copy-Item -LiteralPath $reportPath -Destination $downloadDir -Force

[ordered]@{
    status = $report.status
    runId = $dbJob.id
    databaseChecks = $databaseChecks.Count
    databaseFailures = @($databaseChecks | Where-Object { -not $_.passed }).Count
    cardFailures = @($cardChecks | Where-Object { -not $_.passed }).Count
    mainframeRows = $actualRecords
    mainframeFailures = $invalidRecords.Count
    deterministicReplay = $reproducible
    businessEntityId = $entity.id
    report = $reportPath
} | ConvertTo-Json -Depth 4
if ($report.status -ne "PASSED") { exit 2 }
