param([switch] $FollowLogs)

$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$environmentPath = Join-Path $repositoryRoot ".env"
$statePath = Join-Path $repositoryRoot ".local-state\installation.json"
if (-not (Test-Path -LiteralPath $statePath) -or -not (Test-Path -LiteralPath $environmentPath)) {
  throw "AyuVahak local installation state is missing. Start it with Start-AyuVahak.ps1 first."
}
$state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
if ($state.mode -notin @("SYNTHETIC_DEMO", "EMPTY") -or $state.installationId -notmatch '^[a-f0-9]{12}$') {
  throw "The local installation marker is invalid."
}
$projectName = "ayuvahak-$($state.mode.ToLowerInvariant())-$($state.installationId)"
$composeFiles = @("--file", (Join-Path $repositoryRoot "compose.yaml"))
if ($state.mode -eq "SYNTHETIC_DEMO") {
  $composeFiles += @("--file", (Join-Path $repositoryRoot "compose.demo.yaml"))
}
$arguments = @("compose", "--project-name", $projectName, "--env-file", $environmentPath) + $composeFiles
Push-Location $repositoryRoot
try {
  if ($FollowLogs) {
    & docker @arguments logs --follow app
  } else {
    Write-Host "Mode: $($state.mode)"
    Write-Host "Compose project: $projectName"
    & docker @arguments ps
  }
  if ($LASTEXITCODE -ne 0) { throw "Docker Compose status/log retrieval failed." }
} finally {
  Pop-Location
}
