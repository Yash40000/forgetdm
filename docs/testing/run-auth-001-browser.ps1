[CmdletBinding()]
param(
    [Parameter()]
    [string]$BackendUrl = "http://127.0.0.1:8099",

    [Parameter()]
    [string]$Username = $env:AUTH001_USER,

    [Parameter()]
    [string]$Password = $env:AUTH001_PASSWORD,

    [Parameter()]
    [string]$AdminUser = $env:FORGETDM_TEST_ADMIN_USER,

    [Parameter()]
    [string]$AdminPassword = $env:FORGETDM_TEST_ADMIN_PASSWORD,

    [Parameter()]
    [ValidateRange(1024, 65535)]
    [int]$HttpPort = 3101,

    [Parameter()]
    [ValidateRange(1024, 65535)]
    [int]$HttpsPort = 3443,

    [Parameter()]
    [ValidateRange(60, 600)]
    [int]$BuildTimeoutSeconds = 300,

    [Parameter()]
    [ValidateRange(30, 300)]
    [int]$TestTimeoutSeconds = 210
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$frontendRoot = Join-Path $repoRoot "frontend"
$artifactRoot = Join-Path $repoRoot "docs\testing\evidence\artifacts"
$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("forgetdm-auth001-browser-" + [Guid]::NewGuid().ToString("N"))
$laneEvidenceRoot = Join-Path $tempRoot "lane-evidence"
$playwrightOutputRoot = Join-Path $tempRoot "playwright-output"
$playwrightReportRoot = Join-Path $tempRoot "playwright-report"
$nextStdout = Join-Path $tempRoot "next.stdout.log"
$nextStderr = Join-Path $tempRoot "next.stderr.log"
$proxyStdout = Join-Path $tempRoot "tls-proxy.stdout.log"
$proxyStderr = Join-Path $tempRoot "tls-proxy.stderr.log"
$buildStdout = Join-Path $tempRoot "build.stdout.log"
$buildStderr = Join-Path $tempRoot "build.stderr.log"
$testStdout = Join-Path $tempRoot "playwright.stdout.log"
$testStderr = Join-Path $tempRoot "playwright.stderr.log"
$pfxPath = Join-Path $tempRoot "localhost.pfx"
$proxyScriptPath = Join-Path $tempRoot "tls-reverse-proxy.cjs"
$nextProcess = $null
$proxyProcess = $null
$fixtureUserId = $null
$fixtureAdminToken = ""

$environmentNames = @(
    "FORGETDM_API_BASE",
    "AUTH001_USER",
    "AUTH001_PASSWORD",
    "AUTH001_HTTP_UI_BASE_URL",
    "AUTH001_HTTPS_UI_BASE_URL",
    "AUTH001_LANE_EVIDENCE_DIR",
    "AUTH001_PLAYWRIGHT_OUTPUT_DIR",
    "AUTH001_PLAYWRIGHT_REPORT_DIR",
    "AUTH001_COMMIT_IDENTITY",
    "AUTH001_SOURCE_IDENTITY",
    "AUTH001_NEXT_BUILD_ID",
    "AUTH001_SERVER_MODE"
)
$savedEnvironment = @{}
foreach ($name in $environmentNames) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
}

function Assert-RequiredFile([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Required file not found: $Path"
    }
}

function Assert-PortAvailable([int]$Port) {
    $listener = $null
    try {
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
        $listener.Start()
    } catch {
        throw "Port $Port is already in use. The harness will not stop or reuse an unrelated process."
    } finally {
        if ($null -ne $listener) { $listener.Stop() }
    }
}

function Stop-OwnedProcessTree([System.Diagnostics.Process]$Process) {
    if ($null -eq $Process) { return }
    try {
        if ($Process.HasExited) { return }
        $children = Get-CimInstance Win32_Process -Filter "ParentProcessId=$($Process.Id)" -ErrorAction SilentlyContinue
        foreach ($child in $children) {
            try {
                Stop-OwnedProcessTree ([System.Diagnostics.Process]::GetProcessById([int]$child.ProcessId))
            } catch {
                # The child may have exited between enumeration and lookup.
            }
        }
        Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue
        $Process.WaitForExit(5000) | Out-Null
    } catch {
        # Cleanup must continue even if a process already exited.
    }
}

function ConvertTo-ProcessArguments([string[]]$Arguments) {
    return @($Arguments | ForEach-Object {
        if ($_ -match '[\s"]') {
            '"' + $_.Replace('"', '\"') + '"'
        } else {
            $_
        }
    })
}

function Invoke-BoundedProcess(
    [string]$FilePath,
    [string[]]$ArgumentList,
    [string]$WorkingDirectory,
    [int]$TimeoutSeconds,
    [string]$StandardOutputPath,
    [string]$StandardErrorPath,
    [string]$Label
) {
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $FilePath
    $startInfo.Arguments = (ConvertTo-ProcessArguments $ArgumentList) -join " "
    $startInfo.WorkingDirectory = $WorkingDirectory
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    try {
        if (-not $process.Start()) { throw "$Label could not be started." }
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            Stop-OwnedProcessTree $process
            throw "$Label exceeded its $TimeoutSeconds second bound."
        }
        $process.WaitForExit()
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        [System.IO.File]::WriteAllText($StandardOutputPath, $stdout, [System.Text.UTF8Encoding]::new($false))
        [System.IO.File]::WriteAllText($StandardErrorPath, $stderr, [System.Text.UTF8Encoding]::new($false))
        $exitCode = $process.ExitCode
        if ($exitCode -ne 0) {
            $stdoutTail = ($stdout -split "`r?`n" | Select-Object -Last 80) -join [Environment]::NewLine
            $stderrTail = ($stderr -split "`r?`n" | Select-Object -Last 80) -join [Environment]::NewLine
            throw "$Label failed with exit code $exitCode.`n$stdoutTail`n$stderrTail"
        }
    } finally {
        $process.Dispose()
    }
}

function Wait-ForUrl([string]$Url, [bool]$AllowUntrustedCertificate, [int]$TimeoutSeconds = 45) {
    if ([string]::IsNullOrWhiteSpace($script:nodeRuntime)) {
        throw "The Node runtime must be resolved before endpoint readiness checks."
    }
    $probeScript = @'
const target = new URL(process.argv[1]);
const client = require(target.protocol === 'https:' ? 'node:https' : 'node:http');
const request = client.get(target, { rejectUnauthorized: false, timeout: 2500 }, (response) => {
  response.resume();
  process.exit(response.statusCode >= 200 && response.statusCode < 500 ? 0 : 1);
});
request.on('timeout', () => request.destroy());
request.on('error', () => process.exit(1));
'@
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        & $script:nodeRuntime -e $probeScript $Url 2>$null
        if ($LASTEXITCODE -eq 0) { return }
        Start-Sleep -Milliseconds 300
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw "Timed out waiting for $Url"
}

function Invoke-BackendJson {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [ValidateSet("GET", "POST", "PUT", "DELETE")][string]$Method = "GET",
        [object]$Body = $null,
        [string]$CookieToken = ""
    )
    Add-Type -AssemblyName System.Net.Http
    $handler = [System.Net.Http.HttpClientHandler]::new()
    $handler.UseCookies = $false
    $handler.UseProxy = $false
    $client = [System.Net.Http.HttpClient]::new($handler)
    $client.Timeout = [TimeSpan]::FromSeconds(15)
    try {
        $request = [System.Net.Http.HttpRequestMessage]::new(
            [System.Net.Http.HttpMethod]::new($Method),
            "$($BackendUrl.TrimEnd('/'))$Path"
        )
        if ($null -ne $Body) {
            $json = $Body | ConvertTo-Json -Depth 12 -Compress
            $request.Content = [System.Net.Http.StringContent]::new($json, [Text.Encoding]::UTF8, "application/json")
        }
        if (-not [string]::IsNullOrWhiteSpace($CookieToken)) {
            [void]$request.Headers.TryAddWithoutValidation("Cookie", "FORGETDM_SESSION=$CookieToken")
        }
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $parsed = $null
        if (-not [string]::IsNullOrWhiteSpace($text)) {
            try { $parsed = $text | ConvertFrom-Json } catch { $parsed = $text }
        }
        $setCookie = if ($response.Headers.Contains("Set-Cookie")) {
            $response.Headers.GetValues("Set-Cookie") -join "; "
        } else { "" }
        return [pscustomobject]@{ Status = [int]$response.StatusCode; Body = $parsed; SetCookie = $setCookie }
    } finally {
        $client.Dispose()
        $handler.Dispose()
    }
}

function Get-SessionToken([string]$SetCookie) {
    $match = [regex]::Match($SetCookie, '(?i)(?:^|[;,]\s*)FORGETDM_SESSION=([^;]+)')
    if (-not $match.Success) { throw "Authentication did not issue the expected session cookie." }
    return $match.Groups[1].Value
}

function Get-Sha256Text([string]$Text) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Text)
        return ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace("-", "").ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Get-SourceIdentity {
    $roots = @(
        (Join-Path $frontendRoot "src"),
        (Join-Path $frontendRoot "public")
    )
    $files = foreach ($root in $roots) {
        if (Test-Path -LiteralPath $root) {
            Get-ChildItem -LiteralPath $root -Recurse -File
        }
    }
    $files += @(
        (Get-Item (Join-Path $frontendRoot "next.config.ts")),
        (Get-Item (Join-Path $frontendRoot "package.json")),
        (Get-Item (Join-Path $frontendRoot "tsconfig.json")),
        (Get-Item (Join-Path $frontendRoot "e2e\auth-001\login-session.spec.ts")),
        (Get-Item (Join-Path $frontendRoot "playwright.auth001.config.ts")),
        (Get-Item $PSCommandPath),
        (Get-Item (Join-Path $artifactRoot "AUTH-001-BROWSER-EVIDENCE.schema.json"))
    )
    $manifest = foreach ($file in ($files | Sort-Object FullName -Unique)) {
        $relative = $file.FullName.Substring($repoRoot.Length).TrimStart("\").Replace("\", "/")
        "$relative`t$((Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant())"
    }
    return Get-Sha256Text (($manifest -join "`n") + "`n")
}

function New-EphemeralTlsCertificate([string]$Path, [string]$Passphrase) {
    $rsa = [System.Security.Cryptography.RSA]::Create(2048)
    $certificate = $null
    try {
        $subject = [System.Security.Cryptography.X509Certificates.X500DistinguishedName]::new("CN=localhost")
        $request = [System.Security.Cryptography.X509Certificates.CertificateRequest]::new(
            $subject,
            $rsa,
            [System.Security.Cryptography.HashAlgorithmName]::SHA256,
            [System.Security.Cryptography.RSASignaturePadding]::Pkcs1
        )
        $san = [System.Security.Cryptography.X509Certificates.SubjectAlternativeNameBuilder]::new()
        $san.AddDnsName("localhost")
        $san.AddIpAddress([System.Net.IPAddress]::Loopback)
        $request.CertificateExtensions.Add($san.Build())
        $request.CertificateExtensions.Add(
            [System.Security.Cryptography.X509Certificates.X509BasicConstraintsExtension]::new($false, $false, 0, $true)
        )
        $request.CertificateExtensions.Add(
            [System.Security.Cryptography.X509Certificates.X509KeyUsageExtension]::new(
                [System.Security.Cryptography.X509Certificates.X509KeyUsageFlags]::DigitalSignature,
                $true
            )
        )
        $certificate = $request.CreateSelfSigned([DateTimeOffset]::UtcNow.AddMinutes(-5), [DateTimeOffset]::UtcNow.AddHours(4))
        [System.IO.File]::WriteAllBytes(
            $Path,
            $certificate.Export([System.Security.Cryptography.X509Certificates.X509ContentType]::Pfx, $Passphrase)
        )
    } finally {
        if ($null -ne $certificate) { $certificate.Dispose() }
        $rsa.Dispose()
    }
}

try {
    $hasExistingUser = -not [string]::IsNullOrWhiteSpace($Username) -and -not [string]::IsNullOrEmpty($Password)
    $hasAdmin = -not [string]::IsNullOrWhiteSpace($AdminUser) -and -not [string]::IsNullOrEmpty($AdminPassword)
    if ($hasExistingUser -and $hasAdmin) {
        throw "Choose either an existing test user or administrator credentials for a disposable user, not both."
    }
    if (-not $hasExistingUser -and -not $hasAdmin) {
        throw "Pass -AdminUser/-AdminPassword for a disposable fixture, or AUTH001_USER/AUTH001_PASSWORD for an existing dedicated test user. The harness has no credential defaults."
    }
    if ($hasAdmin) {
        $adminLogin = Invoke-BackendJson -Path "/api/auth/login" -Method POST `
            -Body @{ username = $AdminUser; password = $AdminPassword }
        if ($adminLogin.Status -ne 200) { throw "The browser fixture administrator could not authenticate." }
        $fixtureAdminToken = Get-SessionToken $adminLogin.SetCookie
        $fixtureRunId = [Guid]::NewGuid().ToString("N").Substring(0, 12)
        $Username = "auth001_browser_$fixtureRunId"
        $Password = "B1!" + [Guid]::NewGuid().ToString("N")
        $created = Invoke-BackendJson -Path "/api/security/users" -Method POST -CookieToken $fixtureAdminToken `
            -Body @{ username = $Username; displayName = "AUTH-001 Browser Evidence"; password = $Password; active = $true; roles = @("TDM_ARCHITECT"); groupIds = @() }
        if ($created.Status -ne 200 -or $null -eq $created.Body.id) {
            throw "The disposable browser fixture user could not be created."
        }
        $fixtureUserId = [long]$created.Body.id
    }
    if ($HttpPort -eq $HttpsPort) { throw "HTTP and HTTPS ports must be different." }

    New-Item -ItemType Directory -Path $tempRoot, $laneEvidenceRoot, $playwrightOutputRoot, $playwrightReportRoot, $artifactRoot -Force | Out-Null
    Assert-PortAvailable $HttpPort
    Assert-PortAvailable $HttpsPort

    $node = (Get-Command node.exe -ErrorAction Stop).Source
    $script:nodeRuntime = $node
    $git = "C:\Program Files\Git\cmd\git.exe"
    Assert-RequiredFile $git
    $nextCli = Join-Path $frontendRoot "node_modules\next\dist\bin\next"
    $playwrightCli = Join-Path $frontendRoot "node_modules\@playwright\test\cli.js"
    Assert-RequiredFile $nextCli
    Assert-RequiredFile $playwrightCli

    $commitIdentity = (& $git -C $repoRoot rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($commitIdentity)) {
        throw "Unable to resolve the Git commit identity."
    }
    $sourceIdentity = Get-SourceIdentity

    [Environment]::SetEnvironmentVariable("FORGETDM_API_BASE", $BackendUrl, "Process")
    Invoke-BoundedProcess $node @($nextCli, "build") $frontendRoot $BuildTimeoutSeconds $buildStdout $buildStderr "Optimized Next build"

    $nextBuildIdPath = Join-Path $frontendRoot ".next\BUILD_ID"
    Assert-RequiredFile $nextBuildIdPath
    $nextBuildId = (Get-Content -LiteralPath $nextBuildIdPath -Raw).Trim()
    if ([string]::IsNullOrWhiteSpace($nextBuildId)) { throw "The optimized Next build did not emit a build ID." }

    $nextProcess = Start-Process -FilePath $node `
        -ArgumentList (ConvertTo-ProcessArguments @($nextCli, "start", "--hostname", "127.0.0.1", "--port", [string]$HttpPort)) `
        -WorkingDirectory $frontendRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput $nextStdout `
        -RedirectStandardError $nextStderr `
        -PassThru
    Wait-ForUrl "http://127.0.0.1:$HttpPort/login" $false

    $pfxPassphrase = [Guid]::NewGuid().ToString("N")
    New-EphemeralTlsCertificate $pfxPath $pfxPassphrase
    @'
const fs = require('node:fs');
const http = require('node:http');
const https = require('node:https');

const [httpsPort, httpPort, pfxPath, passphrase] = process.argv.slice(2);
const server = https.createServer({ pfx: fs.readFileSync(pfxPath), passphrase }, (request, response) => {
  const forwardedHost = request.headers.host || `127.0.0.1:${httpsPort}`;
  const headers = {
    ...request.headers,
    host: `127.0.0.1:${httpPort}`,
    'x-forwarded-proto': 'https',
    'x-forwarded-host': forwardedHost
  };
  const upstream = http.request({
    hostname: '127.0.0.1',
    port: Number(httpPort),
    method: request.method,
    path: request.url,
    headers
  }, (upstreamResponse) => {
    const responseHeaders = { ...upstreamResponse.headers };
    if (typeof responseHeaders.location === 'string') {
      responseHeaders.location = responseHeaders.location.replace(
        `http://127.0.0.1:${httpPort}`,
        `https://127.0.0.1:${httpsPort}`
      );
    }
    response.writeHead(upstreamResponse.statusCode || 502, responseHeaders);
    upstreamResponse.pipe(response);
  });
  upstream.on('error', () => {
    if (!response.headersSent) response.writeHead(502, { 'content-type': 'text/plain' });
    response.end('TLS proxy upstream unavailable');
  });
  request.pipe(upstream);
});
server.headersTimeout = 15_000;
server.requestTimeout = 30_000;
server.listen(Number(httpsPort), '127.0.0.1');
'@ | Set-Content -LiteralPath $proxyScriptPath -Encoding UTF8

    $proxyProcess = Start-Process -FilePath $node `
        -ArgumentList (ConvertTo-ProcessArguments @($proxyScriptPath, [string]$HttpsPort, [string]$HttpPort, $pfxPath, $pfxPassphrase)) `
        -WorkingDirectory $tempRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput $proxyStdout `
        -RedirectStandardError $proxyStderr `
        -PassThru
    try {
        Wait-ForUrl "https://127.0.0.1:$HttpsPort/login" $true
    } catch {
        $proxyOut = if (Test-Path $proxyStdout) { (Get-Content $proxyStdout -Tail 40) -join [Environment]::NewLine } else { "" }
        $proxyErr = if (Test-Path $proxyStderr) { (Get-Content $proxyStderr -Tail 40) -join [Environment]::NewLine } else { "" }
        throw "TLS reverse proxy did not become ready.`n$proxyOut`n$proxyErr`n$($_.Exception.Message)"
    }

    [Environment]::SetEnvironmentVariable("AUTH001_USER", $Username, "Process")
    [Environment]::SetEnvironmentVariable("AUTH001_PASSWORD", $Password, "Process")
    [Environment]::SetEnvironmentVariable("AUTH001_HTTP_UI_BASE_URL", "http://127.0.0.1:$HttpPort", "Process")
    [Environment]::SetEnvironmentVariable("AUTH001_HTTPS_UI_BASE_URL", "https://127.0.0.1:$HttpsPort", "Process")
    [Environment]::SetEnvironmentVariable("AUTH001_LANE_EVIDENCE_DIR", $laneEvidenceRoot, "Process")
    [Environment]::SetEnvironmentVariable("AUTH001_PLAYWRIGHT_OUTPUT_DIR", $playwrightOutputRoot, "Process")
    [Environment]::SetEnvironmentVariable("AUTH001_PLAYWRIGHT_REPORT_DIR", $playwrightReportRoot, "Process")
    [Environment]::SetEnvironmentVariable("AUTH001_COMMIT_IDENTITY", $commitIdentity, "Process")
    [Environment]::SetEnvironmentVariable("AUTH001_SOURCE_IDENTITY", $sourceIdentity, "Process")
    [Environment]::SetEnvironmentVariable("AUTH001_NEXT_BUILD_ID", $nextBuildId, "Process")
    [Environment]::SetEnvironmentVariable("AUTH001_SERVER_MODE", "next-start-optimized", "Process")

    Invoke-BoundedProcess $node @($playwrightCli, "test", "--config=playwright.auth001.config.ts") $frontendRoot $TestTimeoutSeconds $testStdout $testStderr "Two-lane Edge acceptance"

    $endingCommitIdentity = (& $git -C $repoRoot rev-parse HEAD).Trim()
    $endingSourceIdentity = Get-SourceIdentity
    if ($endingCommitIdentity -ne $commitIdentity -or $endingSourceIdentity -ne $sourceIdentity) {
        throw "Repository inputs changed during execution; evidence was discarded rather than bound to a moving build."
    }

    $laneFiles = @(Get-ChildItem -LiteralPath $laneEvidenceRoot -Filter "AUTH-001-01-*.json" -File | Sort-Object Name)
    if ($laneFiles.Count -ne 2) { throw "Expected exactly two sanitized lane evidence files; found $($laneFiles.Count)." }
    $lanes = @($laneFiles | ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json })
    $laneNames = @($lanes | ForEach-Object { $_.lane } | Sort-Object -Unique)
    if (($laneNames -join ",") -ne "HTTP,HTTPS") { throw "The retained evidence does not contain one HTTP and one HTTPS lane." }
    foreach ($lane in $lanes) {
        if ($lane.retainedEvidenceSecretScan.exposureDetected -ne $false) {
            throw "A lane reported sensitive material in retained evidence."
        }
        if ($lane.responseBodyInspection.boundedCaptureExceeded -ne $false) {
            throw "A lane exceeded its response inspection bound."
        }
        if ($lane.sessionCookie.secureMatchesLane -ne $true) {
            throw "A lane did not observe the expected session-cookie Secure behavior."
        }
    }

    $artifactSuffix = $sourceIdentity.Substring(0, 12)
    foreach ($lane in $lanes) {
        $laneName = $lane.lane.ToLowerInvariant()
        $screenshot = Get-ChildItem -LiteralPath $playwrightOutputRoot -Recurse -Filter "AUTH-001-01-$laneName-post-login.png" -File | Select-Object -First 1
        if ($null -eq $screenshot) { throw "Missing post-login UI evidence for the $laneName lane." }

        $retainedScreenshotName = "AUTH-001-BROWSER-TWO-LANE-$artifactSuffix-$laneName.png"
        Copy-Item -LiteralPath $screenshot.FullName -Destination (Join-Path $artifactRoot $retainedScreenshotName) -Force
        $lane.screenshot = $retainedScreenshotName
    }

    $combined = [ordered]@{
        schemaVersion = "1.0"
        caseId = "AUTH-001-01"
        executedAtUtc = [DateTimeOffset]::UtcNow.ToString("o")
        result = "PASS"
        browserChannel = "msedge"
        serverMode = "next-start-optimized"
        buildIdentity = [ordered]@{
            commit = $commitIdentity
            sourceTreeSha256 = $sourceIdentity
            nextBuildId = $nextBuildId
        }
        lanes = $lanes
        productionTrustGate = "Local HTTPS proves physical TLS termination with an ephemeral self-signed certificate. Production CA chain, ingress policy, and HSTS remain deployment evidence gates."
    }
    $combinedJson = $combined | ConvertTo-Json -Depth 20
    if ($combinedJson.Contains($Username) -or $combinedJson.Contains($Password)) {
        throw "The combined sanitized artifact contained an entered authentication value."
    }

    $artifactPath = Join-Path $artifactRoot "AUTH-001-BROWSER-TWO-LANE-$artifactSuffix.json"
    [System.IO.File]::WriteAllText($artifactPath, $combinedJson + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))

    $junitSource = Join-Path $playwrightReportRoot "AUTH-001-BROWSER-TWO-LANE.junit.xml"
    Assert-RequiredFile $junitSource
    Copy-Item -LiteralPath $junitSource `
        -Destination (Join-Path $artifactRoot "AUTH-001-BROWSER-TWO-LANE-$artifactSuffix.junit.xml") -Force

    Write-Host "AUTH-001-01 browser acceptance passed in HTTP and physically TLS-terminated HTTPS lanes."
    Write-Host "Sanitized evidence: $artifactPath"
} finally {
    Stop-OwnedProcessTree $proxyProcess
    Stop-OwnedProcessTree $nextProcess
    if ($null -ne $fixtureUserId -and -not [string]::IsNullOrWhiteSpace($fixtureAdminToken)) {
        try { Invoke-BackendJson -Path "/api/security/users/$fixtureUserId" -Method DELETE -CookieToken $fixtureAdminToken | Out-Null } catch { }
    }
    if (-not [string]::IsNullOrWhiteSpace($fixtureAdminToken)) {
        try { Invoke-BackendJson -Path "/api/auth/logout" -Method POST -CookieToken $fixtureAdminToken | Out-Null } catch { }
    }
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], "Process")
    }
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
