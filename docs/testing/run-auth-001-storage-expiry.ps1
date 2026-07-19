[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8099",
    [string]$AdminUser = $env:FORGETDM_TEST_ADMIN_USER,
    [string]$AdminPassword = $env:FORGETDM_TEST_ADMIN_PASSWORD,
    [string]$DbHost = "localhost",
    [int]$DbPort = 5433,
    [string]$DbName = "forgetdm",
    [string]$DbUser = $env:FORGETDM_TEST_DB_USER,
    [string]$DbPassword = $env:FORGETDM_TEST_DB_PASSWORD,
    [string]$PsqlPath = "C:\Program Files\PostgreSQL\17\bin\psql.exe",
    [string]$EvidencePath = ""
)

$ErrorActionPreference = "Stop"

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

function Protected-Status($Session, [string]$Path) {
    try {
        return [int](Invoke-WebRequest -Uri "$BaseUrl$Path" -Method Get -WebSession $Session -UseBasicParsing).StatusCode
    } catch {
        if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode }
        return 0
    }
}

if (-not (Test-Path -LiteralPath $PsqlPath)) { throw "psql executable was not found." }
if ([string]::IsNullOrWhiteSpace($AdminUser) -or [string]::IsNullOrWhiteSpace($AdminPassword) -or
    [string]::IsNullOrWhiteSpace($DbUser) -or [string]::IsNullOrWhiteSpace($DbPassword)) {
    throw "Set the documented FORGETDM_TEST_* credentials or pass them explicitly."
}

$loginBody = @{ username = $AdminUser; password = $AdminPassword } | ConvertTo-Json
$forwardedTlsLogin = Invoke-WebRequest -Uri "$BaseUrl/api/auth/login" -Method Post -UseBasicParsing `
    -Headers @{ "X-Forwarded-Proto" = "https" } -ContentType "application/json" -Body $loginBody
$forwardedTlsCookie = $forwardedTlsLogin.Headers["Set-Cookie"]
if ($forwardedTlsCookie -is [array]) { $forwardedTlsCookie = $forwardedTlsCookie -join "; " }
$forwardedTlsCookieSafe = [bool]($forwardedTlsCookie -match '(?i)Secure') -and `
    [bool]($forwardedTlsCookie -match '(?i)HttpOnly') -and `
    [bool]($forwardedTlsCookie -match '(?i)SameSite=Lax') -and `
    [bool]($forwardedTlsCookie -match '(?i)Path=/')

Invoke-WebRequest -Uri "$BaseUrl/api/auth/login" -Method Post -SessionVariable session -UseBasicParsing `
    -ContentType "application/json" -Body $loginBody | Out-Null

$cookie = $session.Cookies.GetCookies([Uri]$BaseUrl)["FORGETDM_SESSION"]
if (-not $cookie -or [string]::IsNullOrWhiteSpace($cookie.Value)) { throw "Login did not create the expected session cookie." }

$sha = [System.Security.Cryptography.SHA256]::Create()
try {
    $tokenHash = [Convert]::ToBase64String($sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($cookie.Value)))
} finally {
    $sha.Dispose()
}

Invoke-Psql "UPDATE forge_sessions SET expires_at = now() - interval '1 minute' WHERE token_hash = '$tokenHash';" | Out-Null
$expiredRows = [int](Invoke-Psql "SELECT count(*) FROM forge_sessions WHERE token_hash = '$tokenHash' AND expires_at < now();")
$replayStatus = Protected-Status $session "/api/policies"

$storageSql = @"
WITH session_summary AS (
    SELECT count(*) AS row_count,
           count(*) FILTER (WHERE token_hash !~ '^[A-Za-z0-9+/]{43}=$') AS invalid_hashes
    FROM forge_sessions
), user_summary AS (
    SELECT count(*) AS row_count,
           count(*) FILTER (WHERE password_hash IS NULL OR btrim(password_hash) = '') AS invalid_hashes
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
    WHERE table_schema = 'public'
      AND table_name IN ('forge_sessions', 'forge_users')
), audit_summary AS (
    SELECT count(*) AS secret_matches
    FROM audit_events
    WHERE coalesce(detail, '') || ' ' || coalesce(metadata::text, '')
          ~* '(admin123|RbacTest123|FORGETDM_SESSION|definitely-wrong-pw|wrong-password-for-audit)'
)
SELECT json_build_object(
    'sessionRows', s.row_count,
    'allSessionTokensStoredAsSha256', s.invalid_hashes = 0,
    'userRows', u.row_count,
    'allPasswordHashesPresent', u.row_count > 0 AND u.invalid_hashes = 0,
    'clearSessionColumns', c.clear_session_columns,
    'clearPasswordColumns', c.clear_password_columns,
    'auditSecretMatches', a.secret_matches
)::text
FROM session_summary s, user_summary u, column_summary c, audit_summary a;
"@
$storage = Invoke-Psql $storageSql | ConvertFrom-Json

$passed = $forwardedTlsCookieSafe -and $expiredRows -eq 1 -and $replayStatus -eq 401 -and `
    $storage.allSessionTokensStoredAsSha256 -and $storage.allPasswordHashesPresent -and `
    [int]$storage.clearSessionColumns -eq 0 -and [int]$storage.clearPasswordColumns -eq 0 -and `
    [int]$storage.auditSecretMatches -eq 0

$result = [ordered]@{
    story = "AUTH-001"
    executedAt = (Get-Date).ToUniversalTime().ToString("o")
    case03 = [ordered]@{
        status = if ($forwardedTlsCookieSafe) { "PASS" } else { "FAIL" }
        forwardedTlsCookieHasRequiredFlags = $forwardedTlsCookieSafe
        boundary = "TLS proxy behavior proven with X-Forwarded-Proto; physical HTTPS termination is a deployment certification check."
    }
    case08 = [ordered]@{
        status = if ($expiredRows -eq 1 -and $replayStatus -eq 401) { "PASS" } else { "FAIL" }
        expiredSessionRows = $expiredRows
        protectedReplayHttpStatus = $replayStatus
    }
    case09 = [ordered]@{
        status = if ($storage.allSessionTokensStoredAsSha256 -and $storage.allPasswordHashesPresent -and [int]$storage.clearSessionColumns -eq 0 -and [int]$storage.clearPasswordColumns -eq 0 -and [int]$storage.auditSecretMatches -eq 0) { "PASS" } else { "FAIL" }
        sessionRowsInspected = [int]$storage.sessionRows
        allSessionTokensStoredAsSha256 = [bool]$storage.allSessionTokensStoredAsSha256
        userRowsInspected = [int]$storage.userRows
        allPasswordHashesPresent = [bool]$storage.allPasswordHashesPresent
        clearSessionColumns = [int]$storage.clearSessionColumns
        clearPasswordColumns = [int]$storage.clearPasswordColumns
        auditSecretMatches = [int]$storage.auditSecretMatches
    }
    secretsRetainedInEvidence = $false
    passed = $passed
}

if ([string]::IsNullOrWhiteSpace($EvidencePath)) {
    $EvidencePath = Join-Path $PSScriptRoot "evidence\artifacts\AUTH-001-STORAGE-EXPIRY-2026-07-19.json"
}
$result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $EvidencePath -Encoding UTF8
$result | ConvertTo-Json -Depth 8

if (-not $passed) { exit 1 }
