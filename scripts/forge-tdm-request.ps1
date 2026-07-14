param(
  [Parameter(Mandatory=$true)][string]$BaseUrl,
  [Parameter(Mandatory=$true)][string]$Token,
  [Parameter(Mandatory=$true)][string]$TemplateId,
  [Parameter(Mandatory=$true)][string]$Purpose,
  [string]$Environment = "QA",
  [int]$PollSeconds = 15,
  [int]$TimeoutMinutes = 60
)

$ErrorActionPreference = "Stop"
$headers = @{ Authorization = "Bearer $Token" }
$body = @{ templateId=$TemplateId; purpose=$Purpose; environment=$Environment } | ConvertTo-Json
$request = Invoke-RestMethod -Uri "$BaseUrl/api/self-service/requests" -Method Post -Headers $headers -ContentType "application/json" -Body $body
Write-Host "ForgeTDM request $($request.id) submitted; status=$($request.status)"

$deadline = (Get-Date).AddMinutes($TimeoutMinutes)
while ((Get-Date) -lt $deadline) {
  $request = Invoke-RestMethod -Uri "$BaseUrl/api/self-service/requests/$($request.id)" -Headers $headers
  if ($request.status -eq "APPROVED") {
    $result = Invoke-RestMethod -Uri "$BaseUrl/api/self-service/requests/$($request.id)/fulfill" -Method Post -Headers $headers -ContentType "application/json" -Body "{}"
    $result | ConvertTo-Json -Depth 8
    exit 0
  }
  if ($request.status -eq "REJECTED") { throw "ForgeTDM request rejected: $($request.decisionNote)" }
  if ($request.status -eq "FULFILLED") { $request | ConvertTo-Json -Depth 8; exit 0 }
  Start-Sleep -Seconds $PollSeconds
}
throw "Timed out waiting for ForgeTDM request approval"
