param(
  [ValidateSet("SYNTHETIC_DEMO", "EMPTY")]
  [string] $Mode,
  [switch] $NoBrowser,
  [switch] $PlanOnly
)

$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$environmentPath = Join-Path $repositoryRoot ".env"
$stateDirectory = Join-Path $repositoryRoot ".local-state"
$statePath = Join-Path $stateDirectory "installation.json"
$baseCompose = Join-Path $repositoryRoot "compose.yaml"
$demoCompose = Join-Path $repositoryRoot "compose.demo.yaml"

function New-LocalSecret {
  $bytes = New-Object byte[] 32
  $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
  try {
    $generator.GetBytes($bytes)
  } finally {
    $generator.Dispose()
  }
  return [Convert]::ToBase64String($bytes).TrimEnd("=").Replace("+", "-").Replace("/", "_")
}

function Read-EnvironmentFile([string] $Path) {
  $values = [ordered]@{}
  if (-not (Test-Path -LiteralPath $Path)) { return $values }
  foreach ($line in Get-Content -LiteralPath $Path) {
    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
      $values[$Matches[1]] = $Matches[2]
    }
  }
  return $values
}

function Select-DeploymentMode([string] $RequestedMode, $ExistingValues) {
  if (Test-Path -LiteralPath $statePath) {
    $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    if ($state.mode -notin @("SYNTHETIC_DEMO", "EMPTY") -or $state.installationId -notmatch '^[a-f0-9]{12}$') {
      throw "The local installation marker is invalid. Preserve it and ask an operator to inspect the installation."
    }
    if ($RequestedMode -and $RequestedMode -ne $state.mode) {
      throw "This installation is already locked to $($state.mode). Mode changes require a separate clean installation."
    }
    if (-not (Test-Path -LiteralPath $environmentPath)) {
      throw "The local .env file is missing for an initialized installation. Restore it from the local backup; do not regenerate credentials against an existing volume."
    }
    return $state
  }

  $configuredMode = [string]$ExistingValues["AYUVAHAK_DEPLOYMENT_MODE"]
  if ($configuredMode -and $configuredMode -notin @("SYNTHETIC_DEMO", "EMPTY")) {
    throw "AYUVAHAK_DEPLOYMENT_MODE must be SYNTHETIC_DEMO or EMPTY."
  }
  if ($RequestedMode -and $configuredMode -and $RequestedMode -ne $configuredMode) {
    throw "The requested mode conflicts with AYUVAHAK_DEPLOYMENT_MODE in .env. Resolve the local configuration explicitly."
  }
  $selectedMode = if ($RequestedMode) { $RequestedMode } else { $configuredMode }
  if (-not $selectedMode) {
    if ([Console]::IsInputRedirected) {
      throw "First run requires an explicit choice. Run Start-AyuVahak.ps1 -Mode SYNTHETIC_DEMO or -Mode EMPTY."
    }
    Write-Host "Choose the local installation mode:" -ForegroundColor Cyan
    Write-Host "  [1] Synthetic Demo — demo-only local identity realm; no real records"
    Write-Host "  [2] Empty — no synthetic business records or demo realm import"
    $choice = Read-Host "Enter 1 or 2"
    $selectedMode = switch ($choice) {
      "1" { "SYNTHETIC_DEMO" }
      "2" { "EMPTY" }
      default { throw "No valid mode was selected. AyuVahak was not started." }
    }
  }
  if ($selectedMode -notin @("SYNTHETIC_DEMO", "EMPTY")) { throw "A valid explicit mode is required." }

  New-Item -ItemType Directory -Path $stateDirectory -Force | Out-Null
  return [pscustomobject]@{ mode = $selectedMode; installationId = ([guid]::NewGuid().ToString("N").Substring(0, 12)) }
}

$values = Read-EnvironmentFile $environmentPath
$state = Select-DeploymentMode $Mode $values
$selectedMode = [string]$state.mode
$installationId = [string]$state.installationId

if ($values.Count -eq 0) {
  $values = [ordered]@{
    AYUVAHAK_DEPLOYMENT_MODE = $selectedMode
    AYUVAHAK_DATABASE_NAME = "ayuvahak"
    AYUVAHAK_DATABASE_USERNAME = "ayuvahak"
    AYUVAHAK_DATABASE_PASSWORD = (New-LocalSecret)
    AYUVAHAK_HTTP_PORT = "8080"
    AYUVAHAK_KEYCLOAK_ADMIN_USERNAME = "local-admin"
    AYUVAHAK_KEYCLOAK_ADMIN_PASSWORD = (New-LocalSecret)
    AYUVAHAK_KEYCLOAK_PORT = "8081"
    AYUVAHAK_OIDC_ISSUER = "http://localhost:8081/realms/ayuvahak"
    AYUVAHAK_OIDC_JWK_SET_URI = "http://keycloak:8080/realms/ayuvahak/protocol/openid-connect/certs"
    AYUVAHAK_OIDC_AUDIENCE = "ayuvahak-api"
    AYUVAHAK_OIDC_CLIENT_ID = "ayuvahak-local-client"
    AYUVAHAK_OIDC_SCOPE = "openid profile"
    AYUVAHAK_OIDC_SERVICE_CLIENT_IDS = "transfer-agent"
    AYUVAHAK_OIDC_ALLOW_HTTP = "true"
    AYUVAHAK_OUTBOX_DELIVERED_EVENT_RETENTION = "PT720H"
    AYUVAHAK_IDEMPOTENCY_RECEIPT_LOCK_WAIT = "PT5S"
  }
} else {
  if ($values["AYUVAHAK_DEPLOYMENT_MODE"] -and $values["AYUVAHAK_DEPLOYMENT_MODE"] -ne $selectedMode) {
    throw "The local mode marker and .env disagree. AyuVahak was not started."
  }
  $values["AYUVAHAK_DEPLOYMENT_MODE"] = $selectedMode
  if (-not $values["AYUVAHAK_DATABASE_PASSWORD"] -or $values["AYUVAHAK_DATABASE_PASSWORD"] -eq "local-only-not-a-secret") {
    $values["AYUVAHAK_DATABASE_PASSWORD"] = New-LocalSecret
  }
  if (-not $values["AYUVAHAK_KEYCLOAK_ADMIN_PASSWORD"] -or $values["AYUVAHAK_KEYCLOAK_ADMIN_PASSWORD"] -eq "local-only-not-a-secret") {
    $values["AYUVAHAK_KEYCLOAK_ADMIN_PASSWORD"] = New-LocalSecret
  }
}

$defaults = [ordered]@{
  AYUVAHAK_DATABASE_NAME = "ayuvahak"
  AYUVAHAK_DATABASE_USERNAME = "ayuvahak"
  AYUVAHAK_HTTP_PORT = "8080"
  AYUVAHAK_KEYCLOAK_ADMIN_USERNAME = "local-admin"
  AYUVAHAK_KEYCLOAK_PORT = "8081"
  AYUVAHAK_OIDC_ISSUER = "http://localhost:8081/realms/ayuvahak"
  AYUVAHAK_OIDC_JWK_SET_URI = "http://keycloak:8080/realms/ayuvahak/protocol/openid-connect/certs"
  AYUVAHAK_OIDC_AUDIENCE = "ayuvahak-api"
  AYUVAHAK_OIDC_CLIENT_ID = "ayuvahak-local-client"
  AYUVAHAK_OIDC_SCOPE = "openid profile"
  AYUVAHAK_OIDC_SERVICE_CLIENT_IDS = "transfer-agent"
  AYUVAHAK_OIDC_ALLOW_HTTP = "true"
  AYUVAHAK_OUTBOX_DELIVERED_EVENT_RETENTION = "PT720H"
  AYUVAHAK_IDEMPOTENCY_RECEIPT_LOCK_WAIT = "PT5S"
}
foreach ($key in $defaults.Keys) {
  if (-not $values[$key]) { $values[$key] = $defaults[$key] }
}
foreach ($secretKey in @("AYUVAHAK_DATABASE_PASSWORD", "AYUVAHAK_KEYCLOAK_ADMIN_PASSWORD")) {
  if (-not $values[$secretKey]) { $values[$secretKey] = New-LocalSecret }
}

$environmentLines = foreach ($key in $values.Keys) { "$key=$($values[$key])" }
Set-Content -LiteralPath $environmentPath -Value $environmentLines -Encoding ascii
$state | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding ascii

$projectName = "ayuvahak-$($selectedMode.ToLowerInvariant())-$installationId"
$composeFiles = @("--file", $baseCompose)
if ($selectedMode -eq "SYNTHETIC_DEMO") { $composeFiles += @("--file", $demoCompose) }
$dockerArguments = @("compose", "--project-name", $projectName, "--env-file", $environmentPath) + $composeFiles

Push-Location $repositoryRoot
try {
  if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw "Docker Desktop with Docker Compose is required for the supported local installation." }
  & docker @dockerArguments config --quiet
  if ($LASTEXITCODE -ne 0) { throw "Docker Compose configuration validation failed. No services were started." }
  Write-Host "Starting local AyuVahak in $selectedMode mode. Local credentials were generated or retained in the ignored .env file."
  Write-Host "Compose project: $projectName"
  if ($PlanOnly) {
    Write-Host "Plan-only verification succeeded; no containers were started."
    exit 0
  }
  & docker @dockerArguments up --detach
  if ($LASTEXITCODE -ne 0) { throw "Docker Compose failed to start the local services." }
  $port = [int]$values["AYUVAHAK_HTTP_PORT"]
  $readinessUri = "http://127.0.0.1:$port/actuator/health/readiness"
  $deadline = (Get-Date).AddMinutes(3)
  $ready = $false
  while ((Get-Date) -lt $deadline) {
    try {
      $response = Invoke-RestMethod -Uri $readinessUri -TimeoutSec 3
      if ($response.status -eq "UP") { $ready = $true; break }
    } catch { Start-Sleep -Seconds 3 }
  }
  if (-not $ready) { throw "Services started but AyuVahak did not become ready within three minutes. Inspect with docker compose logs app. No credentials were printed." }
  $url = "http://localhost:$port"
  Write-Host "AyuVahak is ready at $url"
  if (-not $NoBrowser) { Start-Process $url }
} finally {
  Pop-Location
}
