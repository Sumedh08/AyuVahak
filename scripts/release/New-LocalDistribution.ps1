param(
  [Parameter(Mandatory = $true)]
  [ValidatePattern('^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$')]
  [string] $Version,
  [string] $JarPath = "target/ayuvahak-platform-0.1.0-SNAPSHOT.jar",
  [string] $OutputDirectory = "release-output"
)

$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$resolvedJar = Resolve-Path -LiteralPath (Join-Path $repositoryRoot $JarPath)
$webDirectory = Join-Path $repositoryRoot "ui\dist"
$distributionTemplate = Join-Path $repositoryRoot "distribution\local"
$requiredFiles = @(
  (Join-Path $webDirectory "index.html"),
  (Join-Path $distributionTemplate "Dockerfile"),
  (Join-Path $distributionTemplate ".dockerignore"),
  (Join-Path $distributionTemplate "compose.yaml"),
  (Join-Path $distributionTemplate "compose.demo.yaml"),
  (Join-Path $repositoryRoot "docs\installation\local-first-run.md")
)
if (-not (Test-Path -LiteralPath $resolvedJar.Path -PathType Leaf)) { throw "The application JAR is missing." }
foreach ($file in $requiredFiles) {
  if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "A required release input is missing: $file" }
}
if (-not (Test-Path -LiteralPath (Join-Path $repositoryRoot "infra\keycloak\demo-manifest.json") -PathType Leaf)) {
  throw "The versioned Demo identity manifest is missing."
}

$outputPath = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot $OutputDirectory))
New-Item -ItemType Directory -Path $outputPath -Force | Out-Null
$stagingRoot = Join-Path $outputPath ".staging"
New-Item -ItemType Directory -Path $stagingRoot -Force | Out-Null
$stagePath = Join-Path $stagingRoot ([guid]::NewGuid().ToString("N"))
$packageName = "AyuVahak-$Version-local"
$packagePath = Join-Path $stagePath $packageName
New-Item -ItemType Directory -Path $packagePath -Force | Out-Null

Copy-Item -Path (Join-Path $distributionTemplate "*") -Destination $packagePath -Recurse -Force
New-Item -ItemType Directory -Path (Join-Path $packagePath "web") -Force | Out-Null
Copy-Item -LiteralPath $resolvedJar.Path -Destination (Join-Path $packagePath "app.jar")
Copy-Item -Path (Join-Path $webDirectory "*") -Destination (Join-Path $packagePath "web") -Recurse -Force
Copy-Item -LiteralPath (Join-Path $repositoryRoot "scripts\local") -Destination (Join-Path $packagePath "scripts\local") -Recurse -Force
Copy-Item -LiteralPath (Join-Path $repositoryRoot "infra\keycloak") -Destination (Join-Path $packagePath "infra\keycloak") -Recurse -Force
New-Item -ItemType Directory -Path (Join-Path $packagePath "docs\installation") -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $repositoryRoot "docs\installation\local-first-run.md") -Destination (Join-Path $packagePath "docs\installation\local-first-run.md")

$sourceRevision = (& git -C $repositoryRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $sourceRevision -notmatch '^[0-9a-f]{40}$') {
  throw "The source revision could not be recorded."
}
$jarHash = (Get-FileHash -LiteralPath (Join-Path $packagePath "app.jar") -Algorithm SHA256).Hash.ToLowerInvariant()
$manifest = [ordered]@{
  version = $Version
  sourceRevision = $sourceRevision
  artifact = "app.jar"
  artifactSha256 = $jarHash
  frontend = "web/"
  deploymentModeChoice = @("SYNTHETIC_DEMO", "EMPTY")
  createdAtUtc = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
  license = "NOT_DESIGNATED_BY_REPOSITORY_OWNER"
  productionDeployment = $false
}
$manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $packagePath "release-manifest.json") -Encoding utf8

$forbiddenNames = @(".scratch", "node_modules", "target", ".local-state", "logs", "test-results")
$forbiddenEntries = Get-ChildItem -LiteralPath $packagePath -Force -Recurse | Where-Object {
  $_.Name -in $forbiddenNames -or
    $_.Name -eq ".env" -or
    $_.Extension -in @(".log", ".pem", ".p12", ".pfx", ".jks", ".keystore") -or
    $_.FullName -match '(^|[\\/])\.env($|[\\/])'
}
if ($forbiddenEntries) { throw "The package staging area contains a prohibited local artifact: $($forbiddenEntries[0].Name)" }

$zipPath = Join-Path $outputPath "$packageName.zip"
if (Test-Path -LiteralPath $zipPath) { throw "The output archive already exists; choose a new version or output directory." }
Compress-Archive -Path (Join-Path $packagePath "*") -DestinationPath $zipPath -CompressionLevel Optimal
$zipHash = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
"$zipHash  $packageName.zip" | Set-Content -LiteralPath (Join-Path $outputPath "SHA256SUMS.txt") -Encoding ascii

$resolvedStagingRoot = [System.IO.Path]::GetFullPath($stagingRoot).TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
$resolvedStage = [System.IO.Path]::GetFullPath($stagePath)
if (-not $resolvedStage.StartsWith($resolvedStagingRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
  throw "Refusing to clean a staging path outside the release output staging directory."
}
Remove-Item -LiteralPath $resolvedStage -Recurse -Force

Write-Host "Created $zipPath"
Write-Host "SHA-256: $zipHash"
Write-Host "Application JAR SHA-256: $jarHash"
Write-Host "The archive contains no runtime credentials and has not been published."
