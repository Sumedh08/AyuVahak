param([switch] $ResetDemo)

$ErrorActionPreference = "Stop"
$safetyModulePath = Join-Path $PSScriptRoot "DemoResetSafety.psm1"
Import-Module $safetyModulePath -Force
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$environmentPath = Join-Path $repositoryRoot ".env"
$statePath = Join-Path $repositoryRoot ".local-state\installation.json"
if (-not (Test-Path -LiteralPath $statePath) -or -not (Test-Path -LiteralPath $environmentPath)) {
  throw "AyuVahak local installation state is incomplete. No Docker command was run."
}
$state = Get-Content -LiteralPath $statePath -Raw -Encoding UTF8 | ConvertFrom-Json
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

function Assert-CurrentDemoResetTarget {
  $configurationOutput = @(& docker @arguments config --format json 2>$null)
  if ($LASTEXITCODE -ne 0 -or $configurationOutput.Count -eq 0) {
    throw "Docker Compose could not resolve the dedicated Demo reset configuration. Reset was refused."
  }
  try {
    $configuration = ($configurationOutput -join "`n") | ConvertFrom-Json -ErrorAction Stop
  } catch {
    throw "Docker Compose returned an unreadable Demo reset configuration. Reset was refused."
  }
  $httpPort = 0
  $identityPort = 0
  $environment = @{}
  foreach ($line in Get-Content -LiteralPath $environmentPath) {
    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') { $environment[$Matches[1]] = $Matches[2] }
  }
  if (-not [int]::TryParse([string]$environment["AYUVAHAK_HTTP_PORT"], [ref]$httpPort) -or
      -not [int]::TryParse([string]$environment["AYUVAHAK_KEYCLOAK_PORT"], [ref]$identityPort)) {
    throw "The Demo installation port configuration is invalid. Reset was refused."
  }
  $expectedVolumes = Assert-DemoResetComposeConfiguration $configuration $projectName $httpPort $identityPort

  $volumeNames = @(& docker volume ls --quiet --filter "label=com.docker.compose.project=$projectName" 2>$null |
      ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ })
  if ($LASTEXITCODE -ne 0) { throw "Docker could not enumerate the dedicated Demo volumes. Reset was refused." }
  if ($volumeNames.Count -gt 0) {
    $volumeOutput = @(& docker volume inspect @volumeNames 2>$null)
    if ($LASTEXITCODE -ne 0 -or $volumeOutput.Count -eq 0) {
      throw "Docker could not verify the dedicated Demo volume labels. Reset was refused."
    }
    try { $volumeInspections = @(($volumeOutput -join "`n") | ConvertFrom-Json -ErrorAction Stop) }
    catch { throw "Docker returned unreadable Demo volume inspection data. Reset was refused." }
    Assert-DemoResetVolumeInspection $volumeInspections $projectName $expectedVolumes
  }

  $containerIds = @(& docker @arguments ps --all --quiet 2>$null |
      ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ })
  if ($LASTEXITCODE -ne 0) { throw "Docker could not enumerate the dedicated Demo containers. Reset was refused." }
  if ($containerIds.Count -gt 0) {
    $containerOutput = @(& docker inspect @containerIds 2>$null)
    if ($LASTEXITCODE -ne 0 -or $containerOutput.Count -eq 0) {
      throw "Docker could not verify the dedicated Demo container labels. Reset was refused."
    }
    try { $containerInspections = @(($containerOutput -join "`n") | ConvertFrom-Json -ErrorAction Stop) }
    catch { throw "Docker returned unreadable Demo container inspection data. Reset was refused." }
    Assert-DemoResetContainerInspection $containerInspections $projectName
  }
}

Push-Location $repositoryRoot
try {
  if ($ResetDemo) {
    Assert-CurrentDemoResetTarget
    Write-Host "This removes the complete disposable Demo database and identity-provider volumes for $projectName." -ForegroundColor Yellow
    Write-Host "No single-record deletion or production reset is performed."
    $confirmation = Read-Host 'Type RESET DEMO DATABASE to continue'
    if ($confirmation -cne "RESET DEMO DATABASE") {
      Write-Host "Reset cancelled; the installation was not changed."
      exit 0
    }
    # Revalidate after operator confirmation so a local project/volume change during the prompt
    # cannot broaden the destructive target.
    Assert-CurrentDemoResetTarget
    & docker @arguments down --volumes --remove-orphans
    if ($LASTEXITCODE -eq 0) {
      foreach ($localDemoArtifact in @(".local-state\demo-seed-state.json", ".local-state\demo-application.yml")) {
        $artifactPath = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot $localDemoArtifact))
        $expectedPrefix = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot ".local-state")) + [System.IO.Path]::DirectorySeparatorChar
        if (-not $artifactPath.StartsWith($expectedPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
          throw "Refusing to remove a Demo artifact outside .local-state."
        }
        if (Test-Path -LiteralPath $artifactPath -PathType Leaf) { Remove-Item -LiteralPath $artifactPath }
      }
    }
  } else {
    & docker @arguments down
  }
  if ($LASTEXITCODE -ne 0) { throw "Docker Compose could not stop the selected local project." }
} finally {
  Pop-Location
}
