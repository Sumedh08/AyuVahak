param(
  [string] $GranteeIdentifier,
  [switch] $PlanOnly
)

$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$environmentPath = Join-Path $repositoryRoot ".env"
$statePath = Join-Path $repositoryRoot ".local-state\installation.json"

function Read-EnvironmentFile([string] $Path) {
  $values = @{}
  foreach ($line in Get-Content -LiteralPath $Path) {
    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
      $values[$Matches[1]] = $Matches[2]
    }
  }
  return $values
}

function Test-OpaqueIdentifier([string] $Value) {
  return -not [string]::IsNullOrWhiteSpace($Value) -and
    $Value.Length -le 256 -and
    $Value -eq $Value.Trim() -and
    -not [regex]::IsMatch($Value, '[\x00-\x1f\x7f]')
}

if (-not (Test-Path -LiteralPath $environmentPath) -or -not (Test-Path -LiteralPath $statePath)) {
  throw "AyuVahak local mode is not initialized. Start the local installation first."
}
$state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
if ($state.mode -notin @("SYNTHETIC_DEMO", "EMPTY") -or $state.installationId -notmatch '^[a-f0-9]{12}$') {
  throw "The local installation marker is invalid. No bootstrap command was run."
}
$values = Read-EnvironmentFile $environmentPath
if ($values["AYUVAHAK_DEPLOYMENT_MODE"] -ne $state.mode) {
  throw "The local mode marker and environment file disagree. No bootstrap command was run."
}
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
  throw "Docker Desktop with Docker Compose is required."
}

if ($state.mode -eq "SYNTHETIC_DEMO") {
  $demoManifestPath = Join-Path $repositoryRoot "infra\keycloak\demo-manifest.json"
  $demoManifest = Get-Content -LiteralPath $demoManifestPath -Raw | ConvertFrom-Json
  if ($demoManifest.version -ne 1 -or -not (Test-OpaqueIdentifier ([string]$demoManifest.bootstrapSubject))) {
    throw "The versioned local Demo identity manifest is invalid. No bootstrap command was run."
  }
  if ($GranteeIdentifier -and $GranteeIdentifier -ne $demoManifest.bootstrapSubject) {
    throw "Synthetic Demo bootstrap can target only its manifest subject. No bootstrap command was run."
  }
  $GranteeIdentifier = [string]$demoManifest.bootstrapSubject
} elseif (-not (Test-OpaqueIdentifier $GranteeIdentifier)) {
  throw "Empty mode requires the exact subject identifier from its configured identity provider. For the packaged local install, create a user in the empty Keycloak realm first."
}

$issuer = [string]$values["AYUVAHAK_OIDC_ISSUER"]
if (-not $issuer -or $issuer.Length -gt 512 -or $issuer -ne $issuer.Trim()) {
  throw "A valid configured OIDC issuer is required. No bootstrap command was run."
}
$projectName = "ayuvahak-$($state.mode.ToLowerInvariant())-$($state.installationId)"
$composeFiles = @("--file", (Join-Path $repositoryRoot "compose.yaml"))
if ($state.mode -eq "SYNTHETIC_DEMO") {
  $composeFiles += @("--file", (Join-Path $repositoryRoot "compose.demo.yaml"))
}
$compose = @("compose", "--project-name", $projectName, "--env-file", $environmentPath) + $composeFiles
$bootstrapArgs = @(
  "run", "--rm", "--no-deps", "--entrypoint", "java", "app",
  "-jar", "/app/ayuvahak.jar",
  "--spring.main.web-application-type=none",
  "--ayuvahak.bootstrap.enabled=true",
  "--ayuvahak.bootstrap.operator-issuer=urn:ayuvahak:local-bootstrap",
  "--ayuvahak.bootstrap.operator-reference=LOCAL-OPERATOR-INITIAL-ACCESS",
  "--ayuvahak.bootstrap.grantee-issuer=$issuer",
  "--ayuvahak.bootstrap.grantee-kind=HUMAN",
  "--ayuvahak.bootstrap.grantee-identifier=$GranteeIdentifier",
  "--ayuvahak.bootstrap.scope=PLATFORM",
  "--ayuvahak.bootstrap.confirmation=CREATE_FIRST_ACCESS_GRANT"
)

Push-Location $repositoryRoot
try {
  & docker @compose config --quiet
  if ($LASTEXITCODE -ne 0) { throw "Local Compose configuration validation failed. No bootstrap was run." }
  if ($PlanOnly) {
    Write-Host "Plan is valid for $($state.mode) mode; target kind HUMAN, platform AccessGrantAdministration only."
    Write-Host "No database command was run. The subject identifier and issuer were not printed."
    exit 0
  }
  Write-Host "This one-shot local action grants only platform AccessGrantAdministration. It does not create an IdP account."
  Write-Host "The operation is audited and cannot be repeated after the first successful grant."
  $confirmation = Read-Host 'Type CREATE_FIRST_ACCESS_GRANT to continue'
  if ($confirmation -cne "CREATE_FIRST_ACCESS_GRANT") {
    Write-Host "Bootstrap cancelled; AyuVahak state was not changed."
    exit 0
  }
  $dockerArguments = $compose + $bootstrapArgs
  & docker @dockerArguments
  if ($LASTEXITCODE -ne 0) {
    throw "The one-shot grant was not confirmed. Inspect the local application/database state before trying again. No identifier or credential was printed."
  }
  Write-Host "Initial local grant completed. Sign in through the configured identity provider; then use Access management for additional scopes."
} finally {
  Pop-Location
}
