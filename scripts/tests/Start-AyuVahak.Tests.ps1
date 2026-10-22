$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path

Describe "Start-AyuVahak local port defaults" {
  It "derives the default OIDC issuer from a custom first-run Keycloak port" {
    $testRoot = Join-Path $env:TEMP ("ayuvahak-launcher-test-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $testRoot | Out-Null
    try {
      Copy-Item -LiteralPath (Join-Path $repositoryRoot "compose.yaml") -Destination $testRoot
      Copy-Item -LiteralPath (Join-Path $repositoryRoot "compose.demo.yaml") -Destination $testRoot
      Copy-Item -LiteralPath (Join-Path $repositoryRoot "Dockerfile") -Destination $testRoot
      Copy-Item -LiteralPath (Join-Path $repositoryRoot "scripts\local") `
        -Destination (Join-Path $testRoot "scripts\local") -Recurse
      Copy-Item -LiteralPath (Join-Path $repositoryRoot "infra\keycloak") `
        -Destination (Join-Path $testRoot "infra\keycloak") -Recurse
      @("AYUVAHAK_HTTP_PORT=18080", "AYUVAHAK_KEYCLOAK_PORT=18081") |
        Set-Content -LiteralPath (Join-Path $testRoot ".env") -Encoding ascii

      $powershell = Get-Command powershell.exe, pwsh.exe -ErrorAction SilentlyContinue |
        Select-Object -First 1
      if (-not $powershell) { throw "PowerShell is required to exercise the local launcher." }
      $launcher = Join-Path $testRoot "scripts\local\Start-AyuVahak.ps1"
      $output = & $powershell.Source -NoLogo -NoProfile -ExecutionPolicy Bypass `
        -File $launcher -Mode EMPTY -PlanOnly 2>&1
      $exitCode = $LASTEXITCODE

      $exitCode | Should Be 0
      $environment = Get-Content -LiteralPath (Join-Path $testRoot ".env")
      $environment -contains "AYUVAHAK_OIDC_ISSUER=http://localhost:18081/realms/ayuvahak" |
        Should Be $true
      $serviceIdentityLine = $environment | Where-Object { $_ -like "AYUVAHAK_OIDC_SERVICE_CLIENT_IDS=*" }
      $serviceIdentities = @($serviceIdentityLine.Substring("AYUVAHAK_OIDC_SERVICE_CLIENT_IDS=".Length) -split ',')
      ($serviceIdentities -contains "ayuvahak-matching-owner-read") | Should Be $true
      ($serviceIdentities -contains "ayuvahak-transfer-reservation-worker") | Should Be $true
      ($serviceIdentities -contains "ayuvahak-transfer-owner-event-consumer") | Should Be $true
      ($serviceIdentities -contains "ayuvahak-transport-worker") | Should Be $true
      $realm = Get-Content -LiteralPath (Join-Path $testRoot ".local-state\keycloak-realm-import.json") -Raw |
        ConvertFrom-Json
      $humanClient = @($realm.clients | Where-Object { $_.clientId -eq "ayuvahak-local-client" })
      $humanClient.Count | Should Be 1
      $humanClient[0].webOrigins | Should Be @("http://localhost:18080")
      $humanClient[0].redirectUris | Should Be @(
        "http://localhost:18080/auth/callback",
        "http://localhost:18080/"
      )
      $humanClient[0].protocolMappers.config.'included.custom.audience' |
        Should Be "ayuvahak-api"
      (@($realm.clients.clientId) -contains "ayuvahak-matching-owner-read") | Should Be $false
      (@($realm.clients.clientId) -contains "ayuvahak-transfer-reservation-worker") | Should Be $false
      ($output -join "`n") | Should Match "Plan-only verification succeeded"
    } finally {
      if (Test-Path -LiteralPath $testRoot) {
        $resolvedTemp = [System.IO.Path]::GetFullPath($env:TEMP).TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
        $resolvedTestRoot = [System.IO.Path]::GetFullPath($testRoot)
        if (-not $resolvedTestRoot.StartsWith($resolvedTemp, [System.StringComparison]::OrdinalIgnoreCase)) {
          throw "Refusing to clean a launcher test path outside the temporary directory."
        }
        Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
      }
    }
  }
}
