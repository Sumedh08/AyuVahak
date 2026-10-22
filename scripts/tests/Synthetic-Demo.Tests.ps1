$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path

Describe "Synthetic Demo business-data manifest" {
  It "contains versioned fictional records for the supported local Demo seed" {
    $manifestPath = Join-Path $repositoryRoot "demo-data\demo-manifest.json"
    (Test-Path -LiteralPath $manifestPath) | Should Be $true

    $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $manifest.version | Should Be 2
    $manifest.mode | Should Be "SYNTHETIC_DEMO"
    @($manifest.organizations).Count | Should BeGreaterThan 0
    @($manifest.facilities).Count | Should BeGreaterThan 0
    @($manifest.capabilities).Count | Should BeGreaterThan 0
    $manifest.capacity.dimension | Should Match '^SYN-'
    $manifest.referral.patientReference.identifier | Should Match '^SYN-'
    $manifest.analytics.indicatorKey | Should Match '^SYN-'
    @($manifest.referral.permittedGeographyCodes).Count | Should Be 0

    foreach ($organization in $manifest.organizations) {
      $organization.id | Should Match '^SYN-'
      $organization.name | Should Match '^SYN-'
    }
    ($manifest.organizations | Select-Object -First 1).name | Should Match '\u2014'
    foreach ($facility in $manifest.facilities) {
      $facility.id | Should Match '^SYN-'
      $facility.name | Should Match '^SYN-'
    }
  }

  It "defines complete fictional profiles for the loopback-only Demo identities" {
    $identityPath = Join-Path $repositoryRoot "infra\keycloak\realm-export.json"
    $realm = Get-Content -LiteralPath $identityPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $bootstrap = @($realm.users | Where-Object username -eq "synthetic.system-admin")
    $seeder = @($realm.users | Where-Object username -eq "synthetic.demo-seeder")
    $expectedPersonas = @(
      "synthetic.org-admin",
      "synthetic.referral-coordinator",
      "synthetic.receiving-coordinator",
      "synthetic.capacity-manager",
      "synthetic.transport-coordinator",
      "synthetic.analyst"
    )
    $personas = @($realm.users | Where-Object username -in $expectedPersonas)

    $bootstrap.Count | Should Be 1
    $seeder.Count | Should Be 1
    $personas.Count | Should Be 6
    foreach ($username in $expectedPersonas) {
      @($personas | Where-Object username -eq $username).Count | Should Be 1
    }
    foreach ($user in @($bootstrap[0], $seeder[0]) + $personas) {
      $user.firstName | Should Be "Synthetic"
      $user.lastName | Should Not BeNullOrEmpty
      $user.email | Should Match '^[a-z0-9.-]+@example\.invalid$'
      $user.emailVerified | Should Be $true
      $user.credentials[0].value | Should Be "local-only-not-a-secret"
    }

    $identityManifest = Get-Content -LiteralPath (Join-Path $repositoryRoot "infra\keycloak\demo-manifest.json") -Raw |
      ConvertFrom-Json
    @($identityManifest.personas).Count | Should Be 6
    foreach ($persona in $identityManifest.personas) {
      @($personas | Where-Object { $_.username -eq $persona.username -and $_.id -eq $persona.subject }).Count |
        Should Be 1
    }
  }

  It "keeps Demo persona authority grants scoped to the declared synthetic owner" {
    $seedScript = Get-Content -LiteralPath (Join-Path $repositoryRoot "scripts\local\Seed-AyuVahakDemo.ps1") -Raw
    $seedScript | Should Match '\$personaGrantTemplates\s*=\s*@\{'
    $seedScript | Should Match 'organization-directory-administrator'
    $seedScript | Should Match 'referral-coordinator-facility'
    $seedScript | Should Match 'receiving-coordinator'
    $seedScript | Should Match 'capacity-manager'
    $seedScript | Should Match 'transport-coordinator'
    $seedScript | Should Match 'analyst'
    $seedScript | Should Match 'Grant-Authority \$adminToken "HUMAN" \$subject \$authority \$scope \$organizationId \$facilityId'
    $seedScript | Should Match 'No persona grants were created'
  }
}

Describe "Seed-AyuVahakDemo safety plan" {
  It "preserves UTC timestamp identity when Windows PowerShell parses saved seed state" {
    Import-Module (Join-Path $repositoryRoot "scripts\local\DemoSeedState.psm1") -Force
    $original = "2026-10-22T07:02:22.2323037+00:00"
    $parsed = ('{"observedAt":"' + $original + '"}' | ConvertFrom-Json).observedAt

    (ConvertTo-StableDemoSeedTimestamp $parsed) | Should Be $original
  }

  It "normalizes Analytics source cutoffs to PostgreSQL-safe microsecond precision" {
    Import-Module (Join-Path $repositoryRoot "scripts\local\DemoSeedState.psm1") -Force
    $timestamp = "2026-10-22T07:02:22.2323037+00:00"

    (ConvertTo-StableDemoSourceCutoff $timestamp) | Should Be "2026-10-22T07:02:22.232303Z"
  }

  It "selects the previous closed UTC reporting month for reproducible Demo Analytics" {
    Import-Module (Join-Path $repositoryRoot "scripts\local\DemoSeedState.psm1") -Force

    (Get-PreviousCompletedReportingMonthStart ([DateTimeOffset]::Parse("2026-10-22T07:02:22Z"))) |
      Should Be "2026-09-01"
    (Get-PreviousCompletedReportingMonthStart ([DateTimeOffset]::Parse("2026-01-22T07:02:22Z"))) |
      Should Be "2025-12-01"

    $seedScript = Get-Content -LiteralPath (Join-Path $repositoryRoot "scripts\local\Seed-AyuVahakDemo.ps1") -Raw
    $seedScript | Should Match 'Get-PreviousCompletedReportingMonthStart'
  }

  It "uses bounded owner APIs and grants the least-privilege Directory vocabulary permission" {
    $seedScript = Get-Content -LiteralPath (Join-Path $repositoryRoot "scripts\local\Seed-AyuVahakDemo.ps1") -Raw
    $seedScript | Should Match 'DirectoryCuration'
    $seedScript | Should Match 'Grant-Authority \$adminToken "HUMAN" \$seederSubject "DirectoryRead" "PLATFORM"'
    $seedScript | Should Match 'DirectoryVocabularyAdministration'
    $seedScript | Should Match 'Grant-Authority \$adminToken "HUMAN" \$seederSubject "OperatorSupportRead" "PLATFORM"'
    $seedScript | Should Match '\$manifest\s*=\s*Get-Content[^\r\n]+-Encoding UTF8'
    $seedScript | Should Match 'CapacityPolicyAdministration'
    $seedScript | Should Match '/api/v1/directory/organizations'
    $seedScript | Should Match '/api/v1/capacity/observations'
    $seedScript | Should Match '/api/v1/referrals'
    $seedScript | Should Match '/api/v1/recommendations'
    $seedScript | Should Match '/api/v1/transfer-journeys'
    $seedScript | Should Match '/api/v1/analytics/computation-operations'
    $seedScript | Should Match '\$manifest\.referral\.permittedGeographyCodes'
    $seedScript | Should Not Match '(?im)^\s*(select|insert|update|delete)\s+.*\b(directory|capacity|referral|transfer|transport|analytics)\.'
  }

  It "requires explicit same-install resume and keeps the seed payload stable" {
    $seedScript = Get-Content -LiteralPath (Join-Path $repositoryRoot "scripts\local\Seed-AyuVahakDemo.ps1") -Raw
    $seedScript | Should Match '\[switch\]\s+\$ResumeInProgress'
    $seedScript | Should Match 'referralSourceCutoff'
    $seedScript | Should Match 'requestStart'
    $seedScript | Should Match 'periodStart'
    $seedScript | Should Match 'prior\.installationId\s+-ne\s+\$installation\.installationId'
    $seedScript | Should Match 'No API request was sent'
  }

  It "accepts only an explicitly marked Synthetic Demo installation in plan mode" {
    $testRoot = Join-Path $env:TEMP ("ayuvahak-demo-seed-plan-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $testRoot | Out-Null
    try {
      New-Item -ItemType Directory -Path (Join-Path $testRoot ".local-state") | Out-Null
      New-Item -ItemType Directory -Path (Join-Path $testRoot "demo-data") | Out-Null
      New-Item -ItemType Directory -Path (Join-Path $testRoot "scripts\local") -Force | Out-Null
      New-Item -ItemType Directory -Path (Join-Path $testRoot "infra\keycloak") -Force | Out-Null
      Copy-Item -LiteralPath (Join-Path $repositoryRoot "scripts\local\Seed-AyuVahakDemo.ps1") `
        -Destination (Join-Path $testRoot "scripts\local\Seed-AyuVahakDemo.ps1")
      Copy-Item -LiteralPath (Join-Path $repositoryRoot "scripts\local\DemoSeedState.psm1") `
        -Destination (Join-Path $testRoot "scripts\local\DemoSeedState.psm1")
      Copy-Item -LiteralPath (Join-Path $repositoryRoot "demo-data\demo-manifest.json") `
        -Destination (Join-Path $testRoot "demo-data\demo-manifest.json")
      Copy-Item -LiteralPath (Join-Path $repositoryRoot "infra\keycloak\demo-manifest.json") `
        -Destination (Join-Path $testRoot "infra\keycloak\demo-manifest.json")
      @("AYUVAHAK_DEPLOYMENT_MODE=SYNTHETIC_DEMO", "AYUVAHAK_HTTP_PORT=18080", `
        "AYUVAHAK_KEYCLOAK_PORT=18081", "AYUVAHAK_OIDC_ISSUER=http://localhost:18081/realms/ayuvahak") |
        Set-Content -LiteralPath (Join-Path $testRoot ".env") -Encoding ascii
      @{ mode = "SYNTHETIC_DEMO"; installationId = "a1b2c3d4e5f6" } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $testRoot ".local-state\installation.json") -Encoding ascii

      $powershell = Get-Command powershell.exe, pwsh.exe -ErrorAction SilentlyContinue |
        Select-Object -First 1
      if (-not $powershell) { throw "PowerShell is required to exercise the Demo seed safety plan." }
      $output = & $powershell.Source -NoLogo -NoProfile -ExecutionPolicy Bypass `
        -File (Join-Path $testRoot "scripts\local\Seed-AyuVahakDemo.ps1") -PlanOnly 2>&1

      $LASTEXITCODE | Should Be 0
      ($output -join "`n") | Should Match "owner APIs"
      ($output -join "`n") | Should Match "No network request was sent"
      (Test-Path -LiteralPath (Join-Path $testRoot ".local-state\demo-seed-state.json")) | Should Be $false
    } finally {
      if (Test-Path -LiteralPath $testRoot) {
        $resolvedTemp = [System.IO.Path]::GetFullPath($env:TEMP).TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
        $resolvedTestRoot = [System.IO.Path]::GetFullPath($testRoot)
        if (-not $resolvedTestRoot.StartsWith($resolvedTemp, [System.StringComparison]::OrdinalIgnoreCase)) {
          throw "Refusing to clean a Demo seed test path outside the temporary directory."
        }
        Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
      }
    }
  }
}
