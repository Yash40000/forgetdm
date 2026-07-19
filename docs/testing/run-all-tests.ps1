<#
.SYNOPSIS
  ForgeTDM full test batch — build, unit tests, frontend typecheck, and live security
  regression probes for the defects closed in the DEF-0001..DEF-0007 series.

.DESCRIPTION
  Three phases, each independently skippable:
    1. Backend   — mvn clean test (compiles + runs the JUnit suite)
    2. Frontend  — npm run typecheck
    3. Live      — API regression probes against a RUNNING backend, asserting the
                   behaviour proven during RBAC-001 / RBAC-002 execution.

  Phase 3 is the standing regression net for the security fixes: it provisions its own
  tenants/users (idempotent) and asserts real HTTP status codes and audit records.

  Compatible with Windows PowerShell 5.1 and PowerShell 7+.

.EXAMPLE
  .\docs\testing\run-all-tests.ps1                # everything
  .\docs\testing\run-all-tests.ps1 -LiveOnly      # just the live security probes (fast)
  .\docs\testing\run-all-tests.ps1 -SkipLive      # build + tests only (backend not running)
#>
[CmdletBinding()]
param(
    [string]$BaseUrl       = "http://localhost:8088",
    [string]$AdminUser     = $env:FORGETDM_TEST_ADMIN_USER,
    [string]$AdminPassword = $env:FORGETDM_TEST_ADMIN_PASSWORD,
    [string]$TestPassword  = $env:FORGETDM_TEST_USER_PASSWORD,
    [switch]$SkipBackend,
    [switch]$SkipFrontend,
    [switch]$SkipLive,
    [switch]$LiveOnly
)

$ErrorActionPreference = "Stop"
if ($LiveOnly) { $SkipBackend = $true; $SkipFrontend = $true }

$RepoRoot   = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$script:Pass     = 0
$script:Fail     = 0
$script:Failures = @()

function Section($t) { Write-Host ""; Write-Host "=== $t ===" -ForegroundColor Cyan }

function Check {
    param([string]$Name, [bool]$Condition, [string]$Detail = "")
    if ($Condition) {
        $script:Pass++
        Write-Host "  [PASS] $Name" -ForegroundColor Green
    } else {
        $script:Fail++
        $script:Failures += $Name
        Write-Host "  [FAIL] $Name $Detail" -ForegroundColor Red
    }
}

# ─────────────────────────────── 1. Backend ───────────────────────────────
if (-not $SkipBackend) {
    Section "1. Backend - mvn clean test"
    Push-Location $RepoRoot
    try {
        & mvn -q clean test
        Check "Maven build + unit tests" ($LASTEXITCODE -eq 0) "(exit $LASTEXITCODE)"
    } finally { Pop-Location }
}

# ─────────────────────────────── 2. Frontend ──────────────────────────────
if (-not $SkipFrontend) {
    Section "2. Frontend - typecheck"
    $fe = Join-Path $RepoRoot "frontend"
    if (Test-Path (Join-Path $fe "package.json")) {
        Push-Location $fe
        try {
            if (-not (Test-Path "node_modules")) { Write-Host "  installing deps (first run)..."; & npm install --silent }
            & npm run typecheck
            Check "Frontend typecheck" ($LASTEXITCODE -eq 0) "(exit $LASTEXITCODE)"
        } finally { Pop-Location }
    } else {
        Write-Host "  frontend/package.json not found - skipped" -ForegroundColor Yellow
    }
}

# ─────────────────────────── 3. Live API probes ───────────────────────────
if (-not $SkipLive) {
    Section "3. Live security regression probes ($BaseUrl)"

    if ([string]::IsNullOrWhiteSpace($AdminUser) -or
        [string]::IsNullOrWhiteSpace($AdminPassword) -or
        [string]::IsNullOrWhiteSpace($TestPassword)) {
        throw "Live probes require FORGETDM_TEST_ADMIN_USER, FORGETDM_TEST_ADMIN_PASSWORD, and FORGETDM_TEST_USER_PASSWORD (or explicit parameters)."
    }

    function Login($user, $pass) {
        try {
            $s = $null
            Invoke-RestMethod -Uri "$BaseUrl/api/auth/login" -Method Post -SessionVariable s `
                -ContentType "application/json" `
                -Body (@{ username = $user; password = $pass } | ConvertTo-Json) | Out-Null
            return $s
        } catch { return $null }
    }

    # Returns @{ Status; Body } and never throws on 4xx/5xx (works on PS 5.1 and 7+).
    function Api($session, $method, $path, $body = $null) {
        $p = @{ Uri = "$BaseUrl$path"; Method = $method; WebSession = $session; UseBasicParsing = $true }
        if ($null -ne $body) { $p.ContentType = "application/json"; $p.Body = ($body | ConvertTo-Json -Depth 10) }
        try {
            $r = Invoke-WebRequest @p
            $parsed = $null
            if ($r.Content) { try { $parsed = $r.Content | ConvertFrom-Json } catch { $parsed = $r.Content } }
            return @{ Status = [int]$r.StatusCode; Body = $parsed }
        } catch {
            $resp = $_.Exception.Response
            if ($null -ne $resp) { return @{ Status = [int]$resp.StatusCode; Body = $null } }
            return @{ Status = 0; Body = $null }
        }
    }

    $admin = Login $AdminUser $AdminPassword
    if (-not $admin) {
        Check "Backend reachable + admin login" $false "(is it running at $BaseUrl?)"
    } else {
        Check "Admin login" $true

        # --- DEF-0002: /api/auth/me exposes group membership -------------
        $me = Api $admin GET "/api/auth/me"
        Check "DEF-0002 /me returns groups" ($null -ne $me.Body.user.groups) "(groups missing)"

        # --- backend freshness: full mask catalogue ----------------------
        $fns = (Api $admin GET "/api/policies/functions").Body
        Check "Mask catalogue complete (>=43)" ($fns.Count -ge 43) "(got $($fns.Count))"
        Check "DIRECT_LOOKUP present" ([bool]($fns -contains "DIRECT_LOOKUP"))
        Check "HASH_LOOKUP present"   ([bool]($fns -contains "HASH_LOOKUP"))

        # --- audit API (V54 hardening) -----------------------------------
        Check "Audit search API" ((Api $admin GET "/api/audit?limit=1").Status -eq 200)
        Check "Audit stats API"  ((Api $admin GET "/api/audit/stats").Status -eq 200)

        # --- provision tenants + users (idempotent) ----------------------
        $groups = (Api $admin GET "/api/security/groups").Body
        function Ensure-Group($name) {
            $g = $groups | Where-Object { $_.name -eq $name } | Select-Object -First 1
            if (-not $g) {
                $g = (Api $admin POST "/api/security/groups" @{ name = $name; description = "$name tenant (test batch)"; roles = @("TDM_ARCHITECT") }).Body
            }
            return $g
        }
        $alphaG = Ensure-Group "ALPHA"
        $betaG  = Ensure-Group "BETA"

        $users = (Api $admin GET "/api/security/users").Body
        function Ensure-User($name, $groupIds, $roles) {
            $u = $users | Where-Object { $_.username -eq $name } | Select-Object -First 1
            if ($u) {
                Api $admin PUT "/api/security/users/$($u.id)" @{ password = $TestPassword; active = $true; roles = $roles; groupIds = $groupIds } | Out-Null
                return $u.id
            }
            return (Api $admin POST "/api/security/users" @{ username = $name; displayName = $name; password = $TestPassword; active = $true; roles = $roles; groupIds = $groupIds }).Body.id
        }
        Ensure-User "alpha_user"   @($alphaG.id) @()          | Out-Null
        Ensure-User "beta_user"    @($betaG.id)  @()          | Out-Null
        Ensure-User "rbac_auditor" @()           @("AUDITOR") | Out-Null

        $alpha   = Login "alpha_user"   $TestPassword
        $beta    = Login "beta_user"    $TestPassword
        $auditor = Login "rbac_auditor" $TestPassword
        Check "Tenant test logins" (($null -ne $alpha) -and ($null -ne $beta) -and ($null -ne $auditor))

        # --- RBAC-001: AUDITOR is read-only, default-deny holds ----------
        Check "RBAC-001 auditor can read policies"      ((Api $auditor GET  "/api/policies").Status -eq 200)
        Check "RBAC-001 auditor cannot create policy"   ((Api $auditor POST "/api/policies" @{ name = "AUDITOR-SHOULD-FAIL-0001" }).Status -eq 403)
        Check "RBAC-001 auditor denied security admin"  ((Api $auditor GET  "/api/security/users").Status -eq 403)
        Check "RBAC-001 unmapped write needs admin.all" ((Api $auditor POST "/api/zzz-unmapped" @{}).Status -eq 403)

        # --- DEF-0007 / RBAC-002: cross-tenant isolation -----------------
        $probeName = "ALPHA-TENANT-PROBE-" + [DateTime]::Now.ToString('yyyyMMddHHmmss')
        $created   = Api $alpha POST "/api/policies" @{ name = $probeName; description = "tenancy regression probe" }
        $policyId  = $created.Body.id
        Check "DEF-0007 alpha created policy" (($created.Status -eq 200) -and ($null -ne $policyId))
        Check "DEF-0007 ownership stamped" (($created.Body.ownerUsername -eq "alpha_user") -and ($created.Body.visibility -eq "GROUP")) `
            "(owner=$($created.Body.ownerUsername) vis=$($created.Body.visibility))"

        if ($null -ne $policyId) {
            $betaList = (Api $beta GET "/api/policies").Body
            Check "DEF-0007 beta cannot LIST alpha's policy"   (-not [bool]($betaList | Where-Object { $_.id -eq $policyId }))
            Check "DEF-0007 beta cannot READ alpha's policy"   ((Api $beta GET    "/api/policies/$policyId/rules").Status -eq 403)
            Check "DEF-0007 beta cannot DELETE alpha's policy" ((Api $beta DELETE "/api/policies/$policyId").Status -eq 403)

            $alphaList = (Api $alpha GET "/api/policies").Body
            Check "DEF-0007 policy survived cross-tenant delete" ([bool]($alphaList | Where-Object { $_.id -eq $policyId }))
            Check "DEF-0007 legacy SHARED rows still visible"    ([bool]($alphaList | Where-Object { $_.visibility -eq "SHARED" }))
            Check "DEF-0007 owner can delete own policy"         ((Api $alpha DELETE "/api/policies/$policyId").Status -eq 200)
        }

        # --- the denial must be audited ----------------------------------
        $recent = (Api $admin GET "/api/audit?limit=25").Body.events
        Check "DEF-0007 denial audited as ACCESS_DENIED" `
            ([bool]($recent | Where-Object { ($_.action -eq "ACCESS_DENIED") -and ($_.actor -eq "beta_user") }))

        # ─────────────────── AUTH-001 / AUTH-003 acceptance ───────────────────
        # These run here rather than in a browser: PowerShell can read the raw Set-Cookie header and
        # hold two independent sessions, neither of which is possible from browser JavaScript
        # (HttpOnly hides the cookie; one profile means one cookie jar).

        # AUTH-001-01 — login response must not carry the token or the password
        $rawLogin = Invoke-WebRequest -Uri "$BaseUrl/api/auth/login" -Method Post -SessionVariable probeSession `
            -ContentType "application/json" -UseBasicParsing `
            -Body (@{ username = $AdminUser; password = $AdminPassword } | ConvertTo-Json)
        Check "AUTH-001-01 login body carries no token/password" `
            (-not ($rawLogin.Content -match '(?i)token|' + [regex]::Escape($AdminPassword)))

        # AUTH-001-03 — session cookie flags (browser JS can never see these)
        $setCookie = $rawLogin.Headers['Set-Cookie']
        if ($setCookie -is [array]) { $setCookie = $setCookie -join '; ' }
        Check "AUTH-001-03 cookie HttpOnly" ([bool]($setCookie -match '(?i)HttpOnly'))
        Check "AUTH-001-03 cookie SameSite"  ([bool]($setCookie -match '(?i)SameSite'))
        Check "AUTH-001-03 cookie Path=/"    ([bool]($setCookie -match '(?i)Path=/'))
        if ($BaseUrl -like 'https:*') {
            Check "AUTH-001-03 cookie Secure on HTTPS" ([bool]($setCookie -match '(?i)Secure'))
        } else {
            Write-Host "  [skip] AUTH-001-03 Secure flag - only asserted on an HTTPS lane" -ForegroundColor Yellow
        }

        # AUTH-001-04 — wrong password vs unknown user must be indistinguishable in body AND timing.
        # DEF-0016: PBKDF2 was skipped for unknown users, giving a ~27x timing oracle.
        function Time-Login($user) {
            $sw = [System.Diagnostics.Stopwatch]::StartNew()
            try {
                Invoke-WebRequest -Uri "$BaseUrl/api/auth/login" -Method Post -UseBasicParsing `
                    -ContentType "application/json" `
                    -Body (@{ username = $user; password = "definitely-wrong-pw" } | ConvertTo-Json) | Out-Null
            } catch { }
            $sw.Stop(); return $sw.ElapsedMilliseconds
        }
        Time-Login $AdminUser | Out-Null; Time-Login "no-such-user-xyz" | Out-Null   # warm both paths
        $tExisting = @(); $tUnknown = @()
        for ($i = 0; $i -lt 5; $i++) { $tExisting += Time-Login $AdminUser; $tUnknown += Time-Login "no-such-user-xyz" }
        $medE = ($tExisting | Sort-Object)[2]; $medU = ($tUnknown | Sort-Object)[2]
        $ratio = if ($medU -gt 0) { [math]::Round($medE / $medU, 1) } else { 99 }
        Check "AUTH-001-04 no timing oracle (ratio < 2x)" ($ratio -lt 2.0) "(existing ${medE}ms vs unknown ${medU}ms = ${ratio}x)"

        # AUTH-001-05 — inactive user denied, no session issued
        $allUsers = (Api $admin GET "/api/security/users").Body
        $victim = $allUsers | Where-Object { $_.username -eq "rbac_auditor" } | Select-Object -First 1
        if ($victim) {
            Api $admin PUT "/api/security/users/$($victim.id)" @{ active = $false } | Out-Null
            $deniedSession = Login "rbac_auditor" $TestPassword
            Check "AUTH-001-05 inactive user cannot log in" ($null -eq $deniedSession)
            Api $admin PUT "/api/security/users/$($victim.id)" @{ active = $true } | Out-Null
            Check "AUTH-001-05 reactivated user can log in" ($null -ne (Login "rbac_auditor" $TestPassword))
        }

        # AUTH-001-06 — logout invalidates the server session; replay is rejected
        $s1 = Login $AdminUser $AdminPassword
        Check "AUTH-001-06 session valid before logout" ((Api $s1 GET "/api/policies").Status -eq 200)
        Api $s1 POST "/api/auth/logout" @{} | Out-Null
        Check "AUTH-001-06 replay after logout is 401" ((Api $s1 GET "/api/policies").Status -eq 401)

        # AUTH-001-07 — two concurrent sessions; logging out one must not affect the other
        $sA = Login $AdminUser $AdminPassword
        $sB = Login $AdminUser $AdminPassword
        Check "AUTH-001-07 both sessions valid" (((Api $sA GET "/api/policies").Status -eq 200) -and ((Api $sB GET "/api/policies").Status -eq 200))
        Api $sA POST "/api/auth/logout" @{} | Out-Null
        Check "AUTH-001-07 logged-out session rejected" ((Api $sA GET "/api/policies").Status -eq 401)
        Check "AUTH-001-07 other session still valid"   ((Api $sB GET "/api/policies").Status -eq 200)

        # AUTH-001-10 / DEF-0008 — failed authentication must be recorded
        $failsBefore = (Api $admin GET "/api/audit?action=LOGIN_FAILED&size=1").Body.total
        Login $AdminUser "wrong-password-for-audit" | Out-Null
        $failsAfter = (Api $admin GET "/api/audit?action=LOGIN_FAILED&size=1").Body.total
        Check "AUTH-001-10 failed login is audited" ($failsAfter -gt $failsBefore) "($failsBefore -> $failsAfter)"

        # AUTH-003-07 — expired/absent session on a direct API call returns structured JSON, never HTML
        $anon = New-Object Microsoft.PowerShell.Commands.WebRequestSession
        $anonResp = Api $anon GET "/api/policies"
        Check "AUTH-003-07 unauthenticated API returns 401" ($anonResp.Status -eq 401)
    }
}

# ─────────────────────────────── summary ──────────────────────────────────
Section "Summary"
Write-Host "  Passed: $script:Pass" -ForegroundColor Green
if ($script:Fail -gt 0) {
    Write-Host "  Failed: $script:Fail" -ForegroundColor Red
    Write-Host ""
    Write-Host "  Failing checks:" -ForegroundColor Red
    $script:Failures | ForEach-Object { Write-Host "    - $_" -ForegroundColor Red }
    exit 1
}
Write-Host "  Failed: 0" -ForegroundColor Green
Write-Host ""
Write-Host "All checks passed." -ForegroundColor Green
exit 0
