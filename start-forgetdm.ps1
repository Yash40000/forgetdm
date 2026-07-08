param(
  [string]$Profile = "demo",
  [int]$Port = 0,
  [switch]$SkipNativeLoaders
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

function Load-OptionalConfig([string]$path, [string]$label) {
  if (Test-Path -LiteralPath $path) {
    Write-Host "Loading $label: $path"
    . $path
  } else {
    Write-Host "Skipping $label: $path not found"
  }
}

Load-OptionalConfig (Join-Path $root "config\local.env.ps1") "local app environment"
if (-not $SkipNativeLoaders) {
  Load-OptionalConfig (Join-Path $root "config\native-loaders.local.ps1") "native loader environment"
}

if (-not $env:FORGETDM_DB_URL)  { $env:FORGETDM_DB_URL  = "jdbc:postgresql://localhost:5433/forgetdm" }
if (-not $env:FORGETDM_DB_USER) { $env:FORGETDM_DB_USER = "forgetdm" }
if (-not $env:FORGETDM_DB_PASS) { $env:FORGETDM_DB_PASS = "forgetdm" }
if (-not $env:FORGETDM_MASKING_SECRET) { $env:FORGETDM_MASKING_SECRET = "pick-a-long-random-secret" }

$argsList = @("spring-boot:run", "-Dspring-boot.run.profiles=$Profile")
if ($Port -gt 0) {
  $argsList += "-Dspring-boot.run.arguments=--server.port=$Port"
} elseif ($env:SERVER_PORT) {
  $argsList += "-Dspring-boot.run.arguments=--server.port=$env:SERVER_PORT"
}

Write-Host "Starting ForgeTDM with profile '$Profile'"
Write-Host "Database: $env:FORGETDM_DB_URL"
& mvn @argsList
