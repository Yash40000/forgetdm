[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8099",
    [string]$AdminUser = $env:FORGETDM_TEST_ADMIN_USER,
    [string]$AdminPassword = $env:FORGETDM_TEST_ADMIN_PASSWORD,
    [string]$DbHost = $(if ($env:FORGETDM_TEST_DB_HOST) { $env:FORGETDM_TEST_DB_HOST } else { "localhost" }),
    [int]$DbPort = $(if ($env:FORGETDM_TEST_DB_PORT) { [int]$env:FORGETDM_TEST_DB_PORT } else { 5433 }),
    [string]$DbName = $(if ($env:FORGETDM_TEST_DB_NAME) { $env:FORGETDM_TEST_DB_NAME } else { "forgetdm" }),
    [string]$DbUser = $env:FORGETDM_TEST_DB_USER,
    [string]$DbPassword = $env:FORGETDM_TEST_DB_PASSWORD,
    [string]$PsqlPath = "C:\Program Files\PostgreSQL\17\bin\psql.exe",
    [int]$HttpPort = 18442,
    [int]$TlsPort = 18443,
    [string]$EvidencePath = ""
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Net.Http

function Require-Value([string]$Name, [string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value)) {
        throw "$Name is required. Pass it explicitly or set the documented FORGETDM_TEST_* environment variable."
    }
}

function Sql-Literal([string]$Value) {
    if ($null -eq $Value) { return "NULL" }
    return "'" + $Value.Replace("'", "''") + "'"
}

function Invoke-Psql([string]$Sql) {
    $previousPassword = $env:PGPASSWORD
    try {
        $env:PGPASSWORD = $DbPassword
        $output = & $PsqlPath -h $DbHost -p $DbPort -U $DbUser -d $DbName -X -A -t -v ON_ERROR_STOP=1 -c $Sql 2>&1
        if ($LASTEXITCODE -ne 0) { throw "Database assertion failed without exposing connector details." }
        return (($output | Where-Object { $_ -and $_ -notmatch '^\s*$' }) -join "`n").Trim()
    } finally {
        if ($null -eq $previousPassword) { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
        else { $env:PGPASSWORD = $previousPassword }
    }
}

function Invoke-RawRequest {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [ValidateSet("GET", "POST", "PUT", "DELETE")][string]$Method = "GET",
        [object]$Body = $null,
        [string]$CookieToken = "",
        [hashtable]$Headers = @{},
        [switch]$TrustTestCertificate
    )

    $handler = New-Object System.Net.Http.HttpClientHandler
    $handler.UseCookies = $false
    $handler.UseProxy = $false
    if ($TrustTestCertificate) {
        $handler.SslProtocols = [System.Security.Authentication.SslProtocols]::Tls12
        $handler.ServerCertificateCustomValidationCallback = { param($message, $certificate, $chain, $errors) return $true }
    }
    $client = New-Object System.Net.Http.HttpClient($handler)
    $client.Timeout = [TimeSpan]::FromSeconds(15)
    try {
        $httpMethod = New-Object System.Net.Http.HttpMethod($Method)
        $request = New-Object System.Net.Http.HttpRequestMessage($httpMethod, $Uri)
        if ($null -ne $Body) {
            $json = if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Depth 20 -Compress }
            $request.Content = New-Object System.Net.Http.StringContent($json, [Text.Encoding]::UTF8, "application/json")
        }
        if (-not [string]::IsNullOrWhiteSpace($CookieToken)) {
            [void]$request.Headers.TryAddWithoutValidation("Cookie", "FORGETDM_SESSION=$CookieToken")
        }
        foreach ($entry in $Headers.GetEnumerator()) {
            [void]$request.Headers.TryAddWithoutValidation([string]$entry.Key, [string]$entry.Value)
        }

        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $parsed = $null
        if (-not [string]::IsNullOrWhiteSpace($text)) {
            try { $parsed = $text | ConvertFrom-Json } catch { $parsed = $text }
        }
        $setCookie = ""
        if ($response.Headers.Contains("Set-Cookie")) {
            $setCookie = ($response.Headers.GetValues("Set-Cookie") -join "; ")
        }
        return [pscustomobject]@{
            Status = [int]$response.StatusCode
            BodyText = $text
            Body = $parsed
            SetCookie = $setCookie
        }
    } finally {
        $client.Dispose()
        $handler.Dispose()
    }
}

function Invoke-CurlTlsRequest {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [ValidateSet("GET", "POST")][string]$Method = "GET",
        [object]$Body = $null,
        [string]$CookieToken = "",
        [hashtable]$Headers = @{},
        [Parameter(Mandatory = $true)][string]$TempRoot
    )

    $curl = "C:\Windows\System32\curl.exe"
    if (-not (Test-Path -LiteralPath $curl)) { throw "Windows curl is required for the disposable TLS evidence lane." }
    $requestId = [Guid]::NewGuid().ToString("N")
    $configPath = Join-Path $TempRoot "$requestId.curl.conf"
    $requestBodyPath = Join-Path $TempRoot "$requestId.request.json"
    $responseBodyPath = Join-Path $TempRoot "$requestId.response.json"
    $responseHeadersPath = Join-Path $TempRoot "$requestId.headers.txt"
    try {
        $curlRequestBodyPath = $requestBodyPath -replace '\\', '/'
        $curlResponseBodyPath = $responseBodyPath -replace '\\', '/'
        $curlResponseHeadersPath = $responseHeadersPath -replace '\\', '/'
        $config = @(
            "silent",
            "show-error",
            "insecure",
            "max-time = 15",
            "request = `"$Method`"",
            "url = `"$Uri`"",
            "dump-header = `"$curlResponseHeadersPath`"",
            "output = `"$curlResponseBodyPath`""
        )
        if ($null -ne $Body) {
            $json = if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Depth 20 -Compress }
            Set-Content -LiteralPath $requestBodyPath -Value $json -Encoding UTF8
            $config += "header = `"Content-Type: application/json`""
            $config += "data-binary = `"@$curlRequestBodyPath`""
        }
        if (-not [string]::IsNullOrWhiteSpace($CookieToken)) {
            $config += "header = `"Cookie: FORGETDM_SESSION=$CookieToken`""
        }
        foreach ($entry in $Headers.GetEnumerator()) {
            $config += "header = `"$($entry.Key): $($entry.Value)`""
        }
        Set-Content -LiteralPath $configPath -Value $config -Encoding ASCII

        $previousPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = "Continue"
            & $curl --config $configPath 1> $null 2> $null
            $curlExit = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $previousPreference
        }
        if ($curlExit -ne 0) { throw "TLS request failed with curl exit $curlExit." }

        $headerText = if (Test-Path -LiteralPath $responseHeadersPath) { Get-Content -LiteralPath $responseHeadersPath -Raw } else { "" }
        $bodyText = if (Test-Path -LiteralPath $responseBodyPath) { Get-Content -LiteralPath $responseBodyPath -Raw } else { "" }
        $statusMatches = [regex]::Matches($headerText, '(?im)^HTTP/\S+\s+(\d{3})')
        if ($statusMatches.Count -eq 0) { throw "TLS response did not contain an HTTP status line." }
        $status = [int]$statusMatches[$statusMatches.Count - 1].Groups[1].Value
        $cookieLines = [regex]::Matches($headerText, '(?im)^Set-Cookie:\s*(.+)$')
        $setCookie = (($cookieLines | ForEach-Object { $_.Groups[1].Value.Trim() }) -join "; ")
        $parsed = $null
        if (-not [string]::IsNullOrWhiteSpace($bodyText)) {
            try { $parsed = $bodyText | ConvertFrom-Json } catch { $parsed = $bodyText }
        }
        return [pscustomobject]@{ Status = $status; BodyText = $bodyText; Body = $parsed; SetCookie = $setCookie }
    } finally {
        Remove-Item -LiteralPath $configPath, $requestBodyPath, $responseBodyPath, $responseHeadersPath -Force -ErrorAction SilentlyContinue
    }
}

function Session-Token([string]$SetCookie) {
    $match = [regex]::Match($SetCookie, '(?i)(?:^|[;,]\s*)FORGETDM_SESSION=([^;]*)')
    if (-not $match.Success -or [string]::IsNullOrWhiteSpace($match.Groups[1].Value)) {
        throw "Authentication response did not issue the expected session cookie."
    }
    return $match.Groups[1].Value
}

function Cookie-Contract([string]$SetCookie, [bool]$RequireSecure, [bool]$RequirePositiveLifetime) {
    $maxAgeMatch = [regex]::Match($SetCookie, '(?i)(?:^|;\s*)Max-Age=(\d+)')
    $maxAge = if ($maxAgeMatch.Success) { [int64]$maxAgeMatch.Groups[1].Value } else { -1 }
    $secure = [bool]($SetCookie -match '(?i)(?:^|;\s*)Secure(?:;|$)')
    return [ordered]@{
        httpOnly = [bool]($SetCookie -match '(?i)(?:^|;\s*)HttpOnly(?:;|$)')
        pathRoot = [bool]($SetCookie -match '(?i)(?:^|;\s*)Path=/(?:;|$)')
        sameSiteLax = [bool]($SetCookie -match '(?i)(?:^|;\s*)SameSite=Lax(?:;|$)')
        secure = $secure
        secureAsRequired = (-not $RequireSecure) -or $secure
        lifetimeSeconds = $maxAge
        positiveFiniteLifetime = (-not $RequirePositiveLifetime) -or ($maxAge -gt 0 -and $maxAge -le 31536000)
    }
}

function Canonical-Principal($User) {
    $groups = @($User.groups | ForEach-Object {
        [ordered]@{
            id = $_.id
            name = $_.name
            roles = @($_.roles | Sort-Object)
        }
    } | Sort-Object name)
    return [ordered]@{
        username = $User.username
        displayName = $User.displayName
        roles = @($User.roles | Sort-Object)
        permissions = @($User.permissions | Sort-Object)
        groups = $groups
    }
}

function Principal-Equal($Left, $Right) {
    return ((Canonical-Principal $Left) | ConvertTo-Json -Depth 10 -Compress) -eq `
           ((Canonical-Principal $Right) | ConvertTo-Json -Depth 10 -Compress)
}

function Sha256-Base64([string]$Value) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        return [Convert]::ToBase64String($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value)))
    } finally {
        $sha.Dispose()
    }
}

function Safe-Response([object]$Response, [string[]]$ForbiddenValues) {
    if ($Response.SetCookie) { return $false }
    foreach ($value in $ForbiddenValues) {
        if (-not [string]::IsNullOrWhiteSpace($value) -and $Response.BodyText.Contains($value)) { return $false }
    }
    return -not [bool]($Response.BodyText -match '(?i)bearer|session[_-]?token|password_hash|token_hash|java\.lang\.|stack\s*trace|\bexception\b|\bat\s+[a-z0-9_.$]+\([^)]*\.java:\d+\)')
}

function Start-ProbeServer {
    param(
        [int]$Port,
        [bool]$UseTls,
        [string]$LaneName
    )

    $connection = Get-NetTCPConnection -State Listen -LocalPort ([Uri]$BaseUrl).Port -ErrorAction Stop | Select-Object -First 1
    $runtime = Get-CimInstance Win32_Process -Filter "ProcessId=$($connection.OwningProcess)"
    if ($null -eq $runtime) { throw "Could not inspect the running verification server process." }

    $javaMatch = [regex]::Match($runtime.CommandLine, '^"([^"]+java\.exe)"')
    if (-not $javaMatch.Success) { throw "Could not locate the Java runtime used by the verification server." }
    $argMatch = [regex]::Match($runtime.CommandLine, '@([^\s]+\.argfile)')
    if (-not $argMatch.Success -or -not (Test-Path -LiteralPath $argMatch.Groups[1].Value)) {
        throw "Could not locate the verification server classpath file."
    }

    if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) {
        throw "$LaneName evidence port $Port is already in use."
    }

    $javaPath = $javaMatch.Groups[1].Value
    $keytoolPath = Join-Path (Split-Path -Parent $javaPath) "keytool.exe"
    $tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("forgetdm-auth001-" + [Guid]::NewGuid().ToString("N"))
    [void](New-Item -ItemType Directory -Path $tempRoot)
    $keyStore = Join-Path $tempRoot "auth001.p12"
    $stdout = Join-Path $tempRoot "server.out.log"
    $stderr = Join-Path $tempRoot "server.err.log"
    $storePassword = "T" + [Guid]::NewGuid().ToString("N") + "!"

    if ($UseTls) {
        $previousPreference = $ErrorActionPreference
        try {
            # keytool writes normal progress to stderr; successful output must not become a terminating error.
            $ErrorActionPreference = "Continue"
            & $keytoolPath -genkeypair -alias auth001 -keyalg RSA -keysize 2048 -storetype PKCS12 `
                -keystore $keyStore -storepass $storePassword -keypass $storePassword `
                -dname "CN=localhost, OU=ForgeTDM Test, O=ForgeTDM, C=US" -validity 2 `
                -ext "SAN=dns:localhost,ip:127.0.0.1" -noprompt 1> $null 2> $null
            $keytoolExit = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $previousPreference
        }
        if ($keytoolExit -ne 0 -or -not (Test-Path -LiteralPath $keyStore)) {
            Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
            throw "Could not create the disposable TLS certificate."
        }
    }

    $oldEnv = @{}
    foreach ($name in @("FORGETDM_DB_URL", "FORGETDM_DB_USER", "FORGETDM_DB_PASS", "FORGETDM_MASKING_SECRET")) {
        $oldEnv[$name] = [Environment]::GetEnvironmentVariable($name)
    }
    try {
        $env:FORGETDM_DB_URL = "jdbc:postgresql://${DbHost}:$DbPort/$DbName"
        $env:FORGETDM_DB_USER = $DbUser
        $env:FORGETDM_DB_PASS = $DbPassword
        $env:FORGETDM_MASKING_SECRET = "auth001-disposable-mask-key"
        $arguments = @(
            "-XX:TieredStopAtLevel=1",
            "-cp", "@$($argMatch.Groups[1].Value)",
            "io.forgetdm.ForgeTdmApplication",
            "--server.port=$Port",
            "--management.server.port=$($Port + 1000)",
            "--spring.flyway.enabled=false",
            "--spring.task.scheduling.enabled=false"
        )
        if ($UseTls) {
            $keyStoreUri = "file:" + ($keyStore -replace '\\', '/')
            $arguments += @(
                "--server.ssl.enabled=true",
                "--server.ssl.key-store=$keyStoreUri",
                "--server.ssl.key-store-password=$storePassword",
                "--server.ssl.key-store-type=PKCS12",
                "--server.ssl.key-alias=auth001"
            )
        } else {
            $arguments += "--server.ssl.enabled=false"
        }
        $process = Start-Process -FilePath $javaPath -ArgumentList $arguments -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    } finally {
        foreach ($name in $oldEnv.Keys) {
            if ($null -eq $oldEnv[$name]) { Remove-Item "Env:$name" -ErrorAction SilentlyContinue }
            else { [Environment]::SetEnvironmentVariable($name, $oldEnv[$name]) }
        }
    }

    $scheme = if ($UseTls) { "https" } else { "http" }
    $url = "${scheme}://localhost:$Port"
    $ready = $false
    $lastProbeError = "No connection attempt completed."
    for ($i = 0; $i -lt 120; $i++) {
        if ($process.HasExited) { break }
        try {
            $probe = if ($UseTls) {
                Invoke-CurlTlsRequest -Uri "$url/api/auth/me" -TempRoot $tempRoot
            } else {
                Invoke-RawRequest -Uri "$url/api/auth/me"
            }
            if ($probe.Status -eq 200) { $ready = $true; break }
            $lastProbeError = "HTTP $($probe.Status)"
        } catch {
            $lastProbeError = $_.Exception.GetType().FullName + ": " + $_.Exception.Message
        }
        Start-Sleep -Milliseconds 500
        $process.Refresh()
    }
    if (-not $ready) {
        if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
        throw "Disposable $LaneName verification server did not become ready ($lastProbeError)."
    }
    return [pscustomobject]@{
        Process = $process
        Name = $LaneName
        UseTls = $UseTls
        Url = $url
        Stdout = $stdout
        Stderr = $stderr
        TempRoot = $tempRoot
        StorePassword = $(if ($UseTls) { $storePassword } else { "" })
    }
}

function Stop-ProbeServer($Probe) {
    if ($null -eq $Probe) { return }
    if (-not $Probe.Process.HasExited) {
        Stop-Process -Id $Probe.Process.Id -Force -ErrorAction SilentlyContinue
        [void]$Probe.Process.WaitForExit(10000)
    }
}

function Invoke-LaneRequest {
    param(
        [Parameter(Mandatory = $true)]$Lane,
        [Parameter(Mandatory = $true)][string]$Path,
        [ValidateSet("GET", "POST", "PUT", "DELETE")][string]$Method = "GET",
        [object]$Body = $null,
        [string]$CookieToken = "",
        [hashtable]$Headers = @{}
    )
    $uri = "$($Lane.Url)$Path"
    if ($Lane.UseTls) {
        if ($Method -notin @("GET", "POST")) { throw "The TLS evidence transport supports GET and POST requests only." }
        return Invoke-CurlTlsRequest -Uri $uri -Method $Method -Body $Body -CookieToken $CookieToken -Headers $Headers -TempRoot $Lane.TempRoot
    }
    return Invoke-RawRequest -Uri $uri -Method $Method -Body $Body -CookieToken $CookieToken -Headers $Headers
}

function Add-SecretCanary([System.Collections.Generic.List[string]]$Canaries, [string]$Value) {
    if (-not [string]::IsNullOrWhiteSpace($Value) -and -not $Canaries.Contains($Value)) {
        [void]$Canaries.Add($Value)
    }
}

function Run-LaneCases {
    param(
        [Parameter(Mandatory = $true)]$Lane,
        [Parameter(Mandatory = $true)][string]$UserAgent,
        [Parameter(Mandatory = $true)][string]$CorrelationPrefix,
        [Parameter(Mandatory = $true)][long]$TestUserId,
        [Parameter(Mandatory = $true)][string]$TestUsername,
        [Parameter(Mandatory = $true)][string]$TestPassword,
        [Parameter(Mandatory = $true)][string]$WrongPassword,
        [Parameter(Mandatory = $true)][string]$UnknownUsername,
        [Parameter(Mandatory = $true)][string]$AdminToken,
        [Parameter(Mandatory = $true)][System.Collections.Generic.List[string]]$SecretCanaries,
        [Parameter(Mandatory = $true)][datetime]$StartedAt
    )

    $cases = [ordered]@{}

    # 02 - login and /me identity parity.
    $case02Headers = @{ "User-Agent" = $UserAgent; "X-Correlation-ID" = "$CorrelationPrefix-C02" }
    $login = Invoke-LaneRequest -Lane $Lane -Path "/api/auth/login" -Method POST -Headers $case02Headers `
        -Body @{ username = $TestUsername; password = $TestPassword }
    $loginToken = Session-Token $login.SetCookie
    Add-SecretCanary $SecretCanaries $loginToken
    $me = Invoke-LaneRequest -Lane $Lane -Path "/api/auth/me" -CookieToken $loginToken -Headers $case02Headers
    $principalMatch = Principal-Equal $login.Body.user $me.Body.user
    $case02Passed = $login.Status -eq 200 -and $me.Status -eq 200 -and $login.Body.authenticated -and $me.Body.authenticated -and `
        $principalMatch -and $null -ne $login.Body.expiresAt
    $cases.case02 = [ordered]@{
        status = if ($case02Passed) { "PASS" } else { "FAIL" }
        loginHttpStatus = $login.Status
        meHttpStatus = $me.Status
        identityFieldsCompared = @("username", "displayName", "roles", "groups", "permissions")
        exactPrincipalMatch = [bool]$principalMatch
        expiryReturned = $null -ne $login.Body.expiresAt
    }
    $cookieContract = Cookie-Contract $login.SetCookie $Lane.UseTls $true
    Invoke-LaneRequest -Lane $Lane -Path "/api/auth/logout" -Method POST -CookieToken $loginToken -Headers $case02Headers | Out-Null

    # 03 - cookie contract on this physical transport.
    $case03Passed = $cookieContract.httpOnly -and $cookieContract.pathRoot -and $cookieContract.sameSiteLax -and `
        $cookieContract.secureAsRequired -and $cookieContract.positiveFiniteLifetime
    $cases.case03 = [ordered]@{
        status = if ($case03Passed) { "PASS" } else { "FAIL" }
        contract = $cookieContract
        transport = $Lane.Name
    }

    # 04 - wrong-password and unknown-user paths are indistinguishable and create no sessions.
    $case04Headers = @{ "User-Agent" = $UserAgent; "X-Correlation-ID" = "$CorrelationPrefix-C04" }
    $sessionsBefore = [int](Invoke-Psql "SELECT count(*) FROM forge_sessions WHERE user_id = $TestUserId;")
    $wrong = Invoke-LaneRequest -Lane $Lane -Path "/api/auth/login" -Method POST -Headers $case04Headers `
        -Body @{ username = $TestUsername; password = $WrongPassword }
    $unknown = Invoke-LaneRequest -Lane $Lane -Path "/api/auth/login" -Method POST -Headers $case04Headers `
        -Body @{ username = $UnknownUsername; password = $WrongPassword }
    $sessionsAfter = [int](Invoke-Psql "SELECT count(*) FROM forge_sessions WHERE user_id = $TestUserId;")
    $wrongKeys = @($wrong.Body.psobject.Properties.Name | Sort-Object)
    $unknownKeys = @($unknown.Body.psobject.Properties.Name | Sort-Object)
    $sameShape = ($wrongKeys -join '|') -eq ($unknownKeys -join '|')
    $sameError = [string]$wrong.Body.error -eq [string]$unknown.Body.error
    $timingsExisting = @()
    $timingsUnknown = @()
    for ($i = 0; $i -lt 5; $i++) {
        $sw = [Diagnostics.Stopwatch]::StartNew()
        Invoke-LaneRequest -Lane $Lane -Path "/api/auth/login" -Method POST -Headers $case04Headers `
            -Body @{ username = $TestUsername; password = $WrongPassword } | Out-Null
        $sw.Stop(); $timingsExisting += $sw.ElapsedMilliseconds
        $sw = [Diagnostics.Stopwatch]::StartNew()
        Invoke-LaneRequest -Lane $Lane -Path "/api/auth/login" -Method POST -Headers $case04Headers `
            -Body @{ username = $UnknownUsername; password = $WrongPassword } | Out-Null
        $sw.Stop(); $timingsUnknown += $sw.ElapsedMilliseconds
    }
    $existingMedian = ($timingsExisting | Sort-Object)[2]
    $unknownMedian = ($timingsUnknown | Sort-Object)[2]
    $timingRatio = if ([math]::Min($existingMedian, $unknownMedian) -gt 0) {
        [math]::Round([math]::Max($existingMedian, $unknownMedian) / [math]::Min($existingMedian, $unknownMedian), 2)
    } else { 99 }
    $setCookieAbsent = [string]::IsNullOrWhiteSpace($wrong.SetCookie) -and [string]::IsNullOrWhiteSpace($unknown.SetCookie)
    $case04Passed = $wrong.Status -eq 401 -and $unknown.Status -eq 401 -and $sameShape -and $sameError -and $setCookieAbsent -and `
        (Safe-Response $wrong @($TestUsername, $WrongPassword)) -and (Safe-Response $unknown @($UnknownUsername, $WrongPassword)) -and `
        $sessionsAfter -eq $sessionsBefore -and $timingRatio -lt 2.0
    $cases.case04 = [ordered]@{
        status = if ($case04Passed) { "PASS" } else { "FAIL" }
        existingUserHttpStatus = $wrong.Status
        unknownUserHttpStatus = $unknown.Status
        sameResponseShape = $sameShape
        sameSanitizedError = $sameError
        setCookieAbsent = $setCookieAbsent
        sessionsCreated = $sessionsAfter - $sessionsBefore
        existingMedianMs = $existingMedian
        unknownMedianMs = $unknownMedian
        maxTimingRatio = $timingRatio
    }

    # 05 - inactive account denial, with guaranteed fixture restoration.
    $deactivate = Invoke-RawRequest -Uri "$BaseUrl/api/security/users/$TestUserId" -Method PUT -CookieToken $AdminToken -Body @{ active = $false }
    if ($deactivate.Status -ne 200) { throw "Could not deactivate the disposable user for $($Lane.Name) case 05." }
    try {
        $inactiveBefore = [int](Invoke-Psql "SELECT count(*) FROM forge_sessions WHERE user_id = $TestUserId;")
        $inactive = Invoke-LaneRequest -Lane $Lane -Path "/api/auth/login" -Method POST `
            -Headers @{ "User-Agent" = $UserAgent; "X-Correlation-ID" = "$CorrelationPrefix-C05" } `
            -Body @{ username = $TestUsername; password = $TestPassword }
        $inactiveAfter = [int](Invoke-Psql "SELECT count(*) FROM forge_sessions WHERE user_id = $TestUserId;")
    } finally {
        $reactivate = Invoke-RawRequest -Uri "$BaseUrl/api/security/users/$TestUserId" -Method PUT -CookieToken $AdminToken -Body @{ active = $true }
    }
    if ($reactivate.Status -ne 200) { throw "Could not reactivate the disposable user after $($Lane.Name) case 05." }
    $inactiveSanitized = Safe-Response $inactive @($TestUsername, $TestPassword)
    $inactiveCookieAbsent = [string]::IsNullOrWhiteSpace($inactive.SetCookie)
    $case05Passed = $inactive.Status -eq 401 -and $inactiveSanitized -and $inactiveCookieAbsent -and $inactiveAfter -eq $inactiveBefore
    $cases.case05 = [ordered]@{
        status = if ($case05Passed) { "PASS" } else { "FAIL" }
        httpStatus = $inactive.Status
        setCookieAbsent = $inactiveCookieAbsent
        sanitizedResponse = [bool]$inactiveSanitized
        sessionsCreated = $inactiveAfter - $inactiveBefore
        fixtureReactivated = $reactivate.Status -eq 200
    }

    # 06 - logout revokes the persisted session and an explicitly retained old cookie cannot replay.
    $case06Headers = @{ "User-Agent" = $UserAgent; "X-Correlation-ID" = "$CorrelationPrefix-C06" }
    $logoutLogin = Invoke-LaneRequest -Lane $Lane -Path "/api/auth/login" -Method POST -Headers $case06Headers `
        -Body @{ username = $TestUsername; password = $TestPassword }
    $oldToken = Session-Token $logoutLogin.SetCookie
    Add-SecretCanary $SecretCanaries $oldToken
    $oldHash = Sha256-Base64 $oldToken
    $beforeLogoutRows = [int](Invoke-Psql "SELECT count(*) FROM forge_sessions WHERE token_hash = $(Sql-Literal $oldHash);")
    $logout = Invoke-LaneRequest -Lane $Lane -Path "/api/auth/logout" -Method POST -CookieToken $oldToken -Headers $case06Headers
    $clearContract = Cookie-Contract $logout.SetCookie $Lane.UseTls $false
    $clearMaxAge = [regex]::Match($logout.SetCookie, '(?i)(?:^|;\s*)Max-Age=(\d+)')
    $replay = Invoke-LaneRequest -Lane $Lane -Path "/api/policies" -CookieToken $oldToken -Headers $case06Headers
    $afterLogoutRows = [int](Invoke-Psql "SELECT count(*) FROM forge_sessions WHERE token_hash = $(Sql-Literal $oldHash);")
    $case06Passed = $beforeLogoutRows -eq 1 -and $logout.Status -eq 200 -and $clearMaxAge.Success -and `
        [int64]$clearMaxAge.Groups[1].Value -eq 0 -and $clearContract.httpOnly -and $clearContract.pathRoot -and `
        $clearContract.sameSiteLax -and $clearContract.secureAsRequired -and $replay.Status -eq 401 -and `
        (Safe-Response $replay @($oldToken)) -and $afterLogoutRows -eq 0
    $cases.case06 = [ordered]@{
        status = if ($case06Passed) { "PASS" } else { "FAIL" }
        sessionPresentBeforeLogout = $beforeLogoutRows -eq 1
        logoutHttpStatus = $logout.Status
        clearingCookieMaxAgeZero = $clearMaxAge.Success -and [int64]$clearMaxAge.Groups[1].Value -eq 0
        clearingCookieHttpOnly = $clearContract.httpOnly
        clearingCookiePathRoot = $clearContract.pathRoot
        clearingCookieSameSiteLax = $clearContract.sameSiteLax
        clearingCookieSecureAsRequired = $clearContract.secureAsRequired
        deliberateOldCookieReplayHttpStatus = $replay.Status
        replayResponseSanitized = [bool](Safe-Response $replay @($oldToken))
        storedSessionRowsAfterLogout = $afterLogoutRows
    }

    # 07 - two sessions remain independent when one is revoked.
    $case07Headers = @{ "User-Agent" = $UserAgent; "X-Correlation-ID" = "$CorrelationPrefix-C07" }
    $sessionA = Invoke-LaneRequest -Lane $Lane -Path "/api/auth/login" -Method POST -Headers $case07Headers `
        -Body @{ username = $TestUsername; password = $TestPassword }
    $sessionB = Invoke-LaneRequest -Lane $Lane -Path "/api/auth/login" -Method POST -Headers $case07Headers `
        -Body @{ username = $TestUsername; password = $TestPassword }
    $tokenA = Session-Token $sessionA.SetCookie
    $tokenB = Session-Token $sessionB.SetCookie
    Add-SecretCanary $SecretCanaries $tokenA
    Add-SecretCanary $SecretCanaries $tokenB
    $bothValid = (Invoke-LaneRequest -Lane $Lane -Path "/api/policies" -CookieToken $tokenA -Headers $case07Headers).Status -eq 200 -and `
        (Invoke-LaneRequest -Lane $Lane -Path "/api/policies" -CookieToken $tokenB -Headers $case07Headers).Status -eq 200
    Invoke-LaneRequest -Lane $Lane -Path "/api/auth/logout" -Method POST -CookieToken $tokenA -Headers $case07Headers | Out-Null
    $aReplayStatus = (Invoke-LaneRequest -Lane $Lane -Path "/api/policies" -CookieToken $tokenA -Headers $case07Headers).Status
    $bStatus = (Invoke-LaneRequest -Lane $Lane -Path "/api/policies" -CookieToken $tokenB -Headers $case07Headers).Status
    Invoke-LaneRequest -Lane $Lane -Path "/api/auth/logout" -Method POST -CookieToken $tokenB -Headers $case07Headers | Out-Null
    $case07Passed = $tokenA -ne $tokenB -and $bothValid -and $aReplayStatus -eq 401 -and $bStatus -eq 200
    $cases.case07 = [ordered]@{
        status = if ($case07Passed) { "PASS" } else { "FAIL" }
        distinctSessionTokens = $tokenA -ne $tokenB
        bothInitiallyValid = $bothValid
        loggedOutSessionReplayHttpStatus = $aReplayStatus
        independentSessionHttpStatus = $bStatus
        policy = "Sessions are independently revoked."
    }

    # 08 - an expired persisted session is denied with a sanitized response.
    $case08Headers = @{ "User-Agent" = $UserAgent; "X-Correlation-ID" = "$CorrelationPrefix-C08" }
    $expiryLogin = Invoke-LaneRequest -Lane $Lane -Path "/api/auth/login" -Method POST -Headers $case08Headers `
        -Body @{ username = $TestUsername; password = $TestPassword }
    $expiryToken = Session-Token $expiryLogin.SetCookie
    Add-SecretCanary $SecretCanaries $expiryToken
    $expiryHash = Sha256-Base64 $expiryToken
    Invoke-Psql "UPDATE forge_sessions SET expires_at = now() - interval '1 minute' WHERE token_hash = $(Sql-Literal $expiryHash);" | Out-Null
    $expiredRows = [int](Invoke-Psql "SELECT count(*) FROM forge_sessions WHERE token_hash = $(Sql-Literal $expiryHash) AND expires_at < now();")
    $expiryReplay = Invoke-LaneRequest -Lane $Lane -Path "/api/policies" -CookieToken $expiryToken -Headers $case08Headers
    $expirySanitized = Safe-Response $expiryReplay @($expiryToken)
    $case08Passed = $expiredRows -eq 1 -and $expiryReplay.Status -eq 401 -and $expirySanitized
    $cases.case08 = [ordered]@{
        status = if ($case08Passed) { "PASS" } else { "FAIL" }
        expiredFixtureRows = $expiredRows
        protectedReplayHttpStatus = $expiryReplay.Status
        sanitizedResponse = [bool]$expirySanitized
        serverExceptionExposed = -not $expirySanitized
    }

    # 10 - dedicated, correlated success/failure/logout records for this physical lane.
    $auditCorrelations = [ordered]@{
        loginSuccess = "$CorrelationPrefix-AS"
        loginFailed = "$CorrelationPrefix-AF"
        logout = "$CorrelationPrefix-AO"
    }
    $auditLogin = Invoke-LaneRequest -Lane $Lane -Path "/api/auth/login" -Method POST `
        -Headers @{ "User-Agent" = $UserAgent; "X-Correlation-ID" = $auditCorrelations.loginSuccess } `
        -Body @{ username = $TestUsername; password = $TestPassword }
    $auditToken = Session-Token $auditLogin.SetCookie
    Add-SecretCanary $SecretCanaries $auditToken
    Invoke-LaneRequest -Lane $Lane -Path "/api/auth/login" -Method POST `
        -Headers @{ "User-Agent" = $UserAgent; "X-Correlation-ID" = $auditCorrelations.loginFailed } `
        -Body @{ username = $TestUsername; password = $WrongPassword } | Out-Null
    Invoke-LaneRequest -Lane $Lane -Path "/api/auth/logout" -Method POST -CookieToken $auditToken `
        -Headers @{ "User-Agent" = $UserAgent; "X-Correlation-ID" = $auditCorrelations.logout } | Out-Null

    $correlationSql = @($auditCorrelations.Values | ForEach-Object { Sql-Literal $_ }) -join ", "
    $secretPredicates = @($SecretCanaries | ForEach-Object {
        "position(" + (Sql-Literal $_) + " in (coalesce(detail, '') || ' ' || coalesce(metadata, '') || ' ' || coalesce(user_agent, ''))) > 0"
    }) -join " OR "
    $auditSql = @"
SELECT coalesce(json_agg(json_build_object(
    'id', id,
    'actor', actor,
    'action', action,
    'outcome', outcome,
    'createdAt', created_at,
    'ipAddressPresent', ip_address IS NOT NULL AND btrim(ip_address) <> '',
    'category', category,
    'correlationId', metadata::jsonb ->> 'correlationId',
    'containsForbiddenValue', ($secretPredicates)
) ORDER BY id), '[]'::json)::text
FROM audit_events
WHERE user_agent = $(Sql-Literal $UserAgent)
  AND action IN ('LOGIN_SUCCESS', 'LOGIN_FAILED', 'LOGOUT')
  AND metadata IS NOT NULL
  AND metadata::jsonb ->> 'correlationId' IN ($correlationSql)
  AND created_at >= $(Sql-Literal ($StartedAt.ToString('o')))::timestamptz;
"@
    $auditRows = @()
    for ($attempt = 0; $attempt -lt 10; $attempt++) {
        $auditRows = @((Invoke-Psql $auditSql | ConvertFrom-Json))
        if ($auditRows.Count -ge 3) { break }
        Start-Sleep -Milliseconds 200
    }
    $successRows = @($auditRows | Where-Object { $_.action -eq 'LOGIN_SUCCESS' -and $_.outcome -eq 'SUCCESS' -and $_.correlationId -eq $auditCorrelations.loginSuccess })
    $failureRows = @($auditRows | Where-Object { $_.action -eq 'LOGIN_FAILED' -and $_.outcome -eq 'FAILURE' -and $_.correlationId -eq $auditCorrelations.loginFailed })
    $logoutRows = @($auditRows | Where-Object { $_.action -eq 'LOGOUT' -and $_.outcome -eq 'SUCCESS' -and $_.correlationId -eq $auditCorrelations.logout })
    $allAuditFields = $auditRows.Count -eq 3 -and @($auditRows | Where-Object {
        $null -eq $_.id -or [string]::IsNullOrWhiteSpace([string]$_.actor) -or $null -eq $_.createdAt -or `
        -not $_.ipAddressPresent -or $_.category -ne 'AUTH' -or $_.containsForbiddenValue
    }).Count -eq 0
    $case10Passed = $successRows.Count -eq 1 -and $failureRows.Count -eq 1 -and $logoutRows.Count -eq 1 -and $allAuditFields
    $auditEvidence = [ordered]@{
        status = if ($case10Passed) { "PASS" } else { "FAIL" }
        lane = $Lane.Name
        expectedCorrelationIds = @($auditCorrelations.Values)
        eventCount = $auditRows.Count
        events = @($auditRows)
        eachRequiredEventAttributed = $successRows.Count -eq 1 -and $failureRows.Count -eq 1 -and $logoutRows.Count -eq 1
        allRequiredFieldsPresent = $allAuditFields
        forbiddenValuesRetained = @($auditRows | Where-Object { $_.containsForbiddenValue }).Count
    }

    return [ordered]@{
        lane = $Lane.Name
        baseOrigin = $Lane.Url
        physicalTransport = $true
        cases = $cases
        audit = $auditEvidence
    }
}

Require-Value "AdminUser" $AdminUser
Require-Value "AdminPassword" $AdminPassword
Require-Value "DbUser" $DbUser
Require-Value "DbPassword" $DbPassword
if (-not (Test-Path -LiteralPath $PsqlPath)) { throw "psql executable was not found." }

$startedAt = (Get-Date).ToUniversalTime()
$runId = [Guid]::NewGuid().ToString("N").Substring(0, 12)
$testUsername = "auth001_$runId"
$testPassword = "A1!" + [Guid]::NewGuid().ToString("N")
$wrongPassword = "W1!" + [Guid]::NewGuid().ToString("N")
$unknownUsername = "missing_$runId"
$adminToken = ""
$testUserId = $null
$probes = [System.Collections.Generic.List[object]]::new()
$secretCanaries = [System.Collections.Generic.List[string]]::new()
$laneRuns = [ordered]@{}
$laneLogTexts = [ordered]@{}
$caseResults = [ordered]@{}

# DbPassword is intentionally excluded from substring checks: the local default is also the
# database/product name, so normal startup metadata would be a false positive. Authentication
# passwords, disposable keystore secrets, and every issued session token remain exact canaries.
foreach ($secret in @($AdminPassword, $testPassword, $wrongPassword, "auth001-disposable-mask-key")) {
    Add-SecretCanary $secretCanaries $secret
}

try {
    $adminLogin = Invoke-RawRequest -Uri "$BaseUrl/api/auth/login" -Method POST `
        -Body @{ username = $AdminUser; password = $AdminPassword }
    if ($adminLogin.Status -ne 200) { throw "Verification administrator could not authenticate." }
    $adminToken = Session-Token $adminLogin.SetCookie
    Add-SecretCanary $secretCanaries $adminToken

    $create = Invoke-RawRequest -Uri "$BaseUrl/api/security/users" -Method POST -CookieToken $adminToken `
        -Body @{ username = $testUsername; displayName = "AUTH-001 Evidence User"; password = $testPassword; active = $true; roles = @("TDM_ARCHITECT"); groupIds = @() }
    if ($create.Status -ne 200 -or $null -eq $create.Body.id) { throw "Could not create the disposable authentication fixture user." }
    $testUserId = [int64]$create.Body.id

    $laneDefinitions = @(
        [pscustomobject]@{ Name = "HTTP"; Port = $HttpPort; UseTls = $false },
        [pscustomobject]@{ Name = "HTTPS"; Port = $TlsPort; UseTls = $true }
    )
    foreach ($definition in $laneDefinitions) {
        $probe = Start-ProbeServer -Port $definition.Port -UseTls $definition.UseTls -LaneName $definition.Name
        [void]$probes.Add($probe)
        Add-SecretCanary $secretCanaries $probe.StorePassword
        try {
            $laneRuns[$definition.Name] = Run-LaneCases -Lane $probe `
                -UserAgent "ForgeTDM-AUTH001-$runId-$($definition.Name)" `
                -CorrelationPrefix "A001-$runId-$($definition.Name)" `
                -TestUserId $testUserId -TestUsername $testUsername -TestPassword $testPassword `
                -WrongPassword $wrongPassword -UnknownUsername $unknownUsername -AdminToken $adminToken `
                -SecretCanaries $secretCanaries -StartedAt $startedAt
        } finally {
            Stop-ProbeServer $probe
        }
        $stdoutExists = Test-Path -LiteralPath $probe.Stdout
        $stderrExists = Test-Path -LiteralPath $probe.Stderr
        $serverLog = ""
        if ($stdoutExists) { $serverLog += Get-Content -LiteralPath $probe.Stdout -Raw }
        if ($stderrExists) { $serverLog += Get-Content -LiteralPath $probe.Stderr -Raw }
        $laneLogTexts[$definition.Name] = $serverLog
        $laneRuns[$definition.Name]["logCapture"] = [ordered]@{
            stdoutCreated = $stdoutExists
            stderrCreated = $stderrExists
            nonEmpty = -not [string]::IsNullOrWhiteSpace($serverLog)
            characterCount = $serverLog.Length
            lineCount = if ($serverLog.Length -gt 0) { @($serverLog -split "`r?`n").Count } else { 0 }
        }
    }

    # Aggregate cases 02-08 only after both physical lanes have completed.
    foreach ($caseName in @("case02", "case03", "case04", "case05", "case06", "case07", "case08")) {
        $lanes = [ordered]@{}
        $allLanePass = $true
        foreach ($laneName in @("HTTP", "HTTPS")) {
            $lanes[$laneName] = $laneRuns[$laneName].cases[$caseName]
            if ($lanes[$laneName].status -ne "PASS") { $allLanePass = $false }
        }
        $caseResults[$caseName] = [ordered]@{
            status = if ($allLanePass) { "PASS" } else { "FAIL" }
            allPhysicalLanesPassed = $allLanePass
            lanes = $lanes
        }
        if ($caseName -eq "case03") {
            $caseResults[$caseName]["certificateBoundary"] = "Disposable localhost certificate; production CA trust, ingress, rotation, and HSTS remain deployment gates."
        }
    }

    # 09 - storage plus non-vacuous, bounded logs from both physical lanes.
    $forbiddenRows = @($secretCanaries | ForEach-Object { "(" + (Sql-Literal $_) + ")" }) -join ",`n        "

    $storageSql = @"
WITH forbidden(secret) AS (
    VALUES $forbiddenRows
), session_summary AS (
    SELECT count(*) AS row_count,
           count(*) FILTER (WHERE token_hash !~ '^[A-Za-z0-9+/]{43}=$') AS invalid_hashes
    FROM forge_sessions
), user_summary AS (
    SELECT count(*) AS row_count,
           count(*) FILTER (WHERE password_hash IS NULL OR btrim(password_hash) = '') AS missing_hashes,
           count(*) FILTER (
               WHERE array_length(string_to_array(password_hash, chr(36)), 1) <> 4
                  OR split_part(password_hash, chr(36), 1) <> 'pbkdf2'
                  OR split_part(password_hash, chr(36), 2) !~ '^[0-9]+$'
                  OR split_part(password_hash, chr(36), 3) !~ '^[A-Za-z0-9+/=]+$'
                  OR split_part(password_hash, chr(36), 4) !~ '^[A-Za-z0-9+/=]+$'
           ) AS invalid_hashes
    FROM forge_users
), column_summary AS (
    SELECT count(*) FILTER (
               WHERE table_name = 'forge_sessions'
                 AND lower(column_name) IN ('token', 'session_token', 'cookie', 'password')
           ) AS clear_session_columns,
           count(*) FILTER (
               WHERE table_name = 'forge_users'
                 AND lower(column_name) = 'password'
           ) AS clear_password_columns
    FROM information_schema.columns
    WHERE table_schema = 'public' AND table_name IN ('forge_sessions', 'forge_users')
), audit_summary AS (
    SELECT count(*) AS secret_matches
    FROM audit_events a
    JOIN forbidden f ON position(f.secret in (coalesce(a.detail, '') || ' ' || coalesce(a.metadata, '') || ' ' || coalesce(a.user_agent, ''))) > 0
)
SELECT json_build_object(
    'sessionRows', s.row_count,
    'allSessionTokensStoredAsSha256', s.invalid_hashes = 0,
    'userRows', u.row_count,
    'allPasswordHashesPresent', u.row_count > 0 AND u.missing_hashes = 0,
    'allPasswordHashesUsePbkdf2', u.row_count > 0 AND u.invalid_hashes = 0,
    'clearSessionColumns', c.clear_session_columns,
    'clearPasswordColumns', c.clear_password_columns,
    'auditSecretMatches', a.secret_matches
)::text
FROM session_summary s, user_summary u, column_summary c, audit_summary a;
"@
    $storage = Invoke-Psql $storageSql | ConvertFrom-Json
    $logsSanitized = $true
    $allLogsPresentAndNonEmpty = $true
    $logEvidence = [ordered]@{}
    foreach ($laneName in @("HTTP", "HTTPS")) {
        $text = [string]$laneLogTexts[$laneName]
        $capture = $laneRuns[$laneName].logCapture
        $laneSanitized = $true
        foreach ($secret in $secretCanaries) {
            if ($text.Contains($secret)) { $laneSanitized = $false; break }
        }
        $laneLogReady = $capture.stdoutCreated -and $capture.stderrCreated -and $capture.nonEmpty
        if (-not $laneSanitized) { $logsSanitized = $false }
        if (-not $laneLogReady) { $allLogsPresentAndNonEmpty = $false }
        $logEvidence[$laneName] = [ordered]@{
            stdoutCreated = $capture.stdoutCreated
            stderrCreated = $capture.stderrCreated
            nonEmpty = $capture.nonEmpty
            characterCount = $capture.characterCount
            lineCount = $capture.lineCount
            forbiddenValuesChecked = $secretCanaries.Count
            forbiddenValuesFound = if ($laneSanitized) { 0 } else { 1 }
        }
    }
    $case09 = $storage.allSessionTokensStoredAsSha256 -and $storage.allPasswordHashesPresent -and $storage.allPasswordHashesUsePbkdf2 -and `
        [int]$storage.clearSessionColumns -eq 0 -and [int]$storage.clearPasswordColumns -eq 0 -and `
        [int]$storage.auditSecretMatches -eq 0 -and $logsSanitized -and $allLogsPresentAndNonEmpty
    $caseResults.case09 = [ordered]@{
        status = if ($case09) { "PASS" } else { "FAIL" }
        sessionRowsInspected = [int]$storage.sessionRows
        allSessionTokensStoredAsSha256 = [bool]$storage.allSessionTokensStoredAsSha256
        userRowsInspected = [int]$storage.userRows
        allPasswordHashesPresent = [bool]$storage.allPasswordHashesPresent
        allPasswordHashesUsePbkdf2 = [bool]$storage.allPasswordHashesUsePbkdf2
        clearSessionColumns = [int]$storage.clearSessionColumns
        clearPasswordColumns = [int]$storage.clearPasswordColumns
        auditSecretMatches = [int]$storage.auditSecretMatches
        boundedApplicationLogsInspected = $allLogsPresentAndNonEmpty
        generatedPasswordsAndAllTokensAbsentFromLogs = $logsSanitized
        forbiddenValuesChecked = $secretCanaries.Count
        lanes = $logEvidence
    }

    # 10 - independently attributed evidence from each physical transport.
    $auditLanes = [ordered]@{}
    $case10 = $true
    foreach ($laneName in @("HTTP", "HTTPS")) {
        $auditLanes[$laneName] = $laneRuns[$laneName].audit
        if ($auditLanes[$laneName].status -ne "PASS") { $case10 = $false }
    }
    $caseResults.case10 = [ordered]@{
        status = if ($case10) { "PASS" } else { "FAIL" }
        allPhysicalLanesPassed = $case10
        lanes = $auditLanes
    }
} finally {
    foreach ($probe in $probes) { Stop-ProbeServer $probe }
    if ($null -ne $testUserId -and -not [string]::IsNullOrWhiteSpace($adminToken)) {
        try { Invoke-RawRequest -Uri "$BaseUrl/api/security/users/$testUserId" -Method DELETE -CookieToken $adminToken | Out-Null } catch { }
    }
    if (-not [string]::IsNullOrWhiteSpace($adminToken)) {
        try { Invoke-RawRequest -Uri "$BaseUrl/api/auth/logout" -Method POST -CookieToken $adminToken | Out-Null } catch { }
    }
    foreach ($probe in $probes) {
        if (Test-Path -LiteralPath $probe.TempRoot) {
            Remove-Item -LiteralPath $probe.TempRoot -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}

$passedCases = @($caseResults.Keys | Where-Object { $caseResults[$_].status -eq "PASS" }).Count
$allPassed = $passedCases -eq 9
$gitPath = "C:\Program Files\Git\cmd\git.exe"
$gitCommit = if (Test-Path -LiteralPath $gitPath) { (& $gitPath rev-parse HEAD 2>$null | Select-Object -First 1).Trim() } else { "unavailable" }
$gitDirty = if (Test-Path -LiteralPath $gitPath) { -not [string]::IsNullOrWhiteSpace(((& $gitPath status --porcelain 2>$null) -join "`n")) } else { $true }
$result = [ordered]@{
    story = "AUTH-001"
    executedAt = (Get-Date).ToUniversalTime().ToString("o")
    scope = "API, cookie, session, storage, audit, and bounded application logs across physical HTTP and HTTPS lanes for cases 02-10"
    case01 = "See independent Playwright browser artifact."
    executionIdentity = [ordered]@{
        gitCommit = $gitCommit
        worktreeDirty = $gitDirty
        runnerSha256 = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash
        lanes = @($laneRuns.Keys | ForEach-Object { [ordered]@{ name = $_; baseOrigin = $laneRuns[$_].baseOrigin; physicalTransport = $true } })
    }
    cases = $caseResults
    passedCases = $passedCases
    totalCases = 9
    allCasesExecutedOnBothPhysicalLanes = [bool]$allPassed
    secretsRetainedInEvidence = $false
    passed = $allPassed
}

if ([string]::IsNullOrWhiteSpace($EvidencePath)) {
    $EvidencePath = Join-Path $PSScriptRoot "evidence\artifacts\AUTH-001-ACCEPTANCE-2026-07-19.json"
}
$parent = Split-Path -Parent $EvidencePath
if (-not (Test-Path -LiteralPath $parent)) { [void](New-Item -ItemType Directory -Path $parent) }
$result | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $EvidencePath -Encoding UTF8
$result | ConvertTo-Json -Depth 12
if (-not $allPassed) { exit 1 }
