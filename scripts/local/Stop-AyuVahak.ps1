param([switch] $ResetDemo)

$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$environmentPath = Join-Path $repositoryRoot ".env"
$statePath = Join-Path $repositoryRoot ".local-state\installation.json"
if (-not (Test-Path -LiteralPath $statePath) -or -not (Test-Path -LiteralPath $environmentPath)) {
  throw "AyuVahak local installation state is incomplete. No Docker command was run."
}
$state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
if ($state.mode -notin @("SYNTHETIC_DEMO", "EMPTY") -or $state.installationId -notmatch '^[a-f0-9]{12}$') {
  throw "The local installation marker is invalid. No Docker command was run."
}
if ($ResetDemo -and $state.mode -ne "SYNTHETIC_DEMO") {
  throw "Reset is available only for this installation's Synthetic Demo mode. No Docker command was run."
}
$projectName = "ayuvahak-$($state.mode.ToLowerInvariant())-$($state.installationId)"
$composeFiles = @("--file", (Join-Path $repositoryRoot "compose.yaml"))
if ($state.mode -eq "SYNTHETIC_DEMO") {
  $composeFiles += @("--file", (Join-Path $repositoryRoot "compose.demo.yaml"))
}
$arguments = @("compose", "--project-name", $projectName, "--env-file", $environmentPath) + $composeFiles
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw "Docker Desktop with Docker Compose is required." }

Push-Location $repositoryRoot
try {
  if ($ResetDemo) {
    Write-Host "This removes the complete disposable Demo database and identity-provider volumes for $projectName." -ForegroundColor Yellow
    Write-Host "No single-record deletion or production reset is performed."
    $confirmation = Read-Host 'Type RESET DEMO DATABASE to continue'
    if ($confirmation -cne "RESET DEMO DATABASE") {
      Write-Host "Reset cancelled; the installation was not changed."
      exit 0
    }
    & docker @arguments down --volumes --remove-orphans
  } else {
    & docker @arguments down
  }
  if ($LASTEXITCODE -ne 0) { throw "Docker Compose could not stop the selected local project." }
} finally {
  Pop-Location
}
