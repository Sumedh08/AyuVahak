Import-Module (Join-Path $PSScriptRoot "..\release\ReleaseArtifactEvidence.psm1") -Force

function Test-ActionThrows {
  param([scriptblock] $Action)
  try {
    & $Action
    return $false
  } catch {
    return $true
  }
}

Describe "Release artifact source and digest binding" {
  BeforeEach {
    $script:revision = "0123456789abcdef0123456789abcdef01234567"
    $script:digest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    $script:frontendDigest = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
    $script:provenance = [pscustomobject]@{
      schemaVersion = "ayuvahak.release-provenance/v1"
      source = [pscustomobject]@{ revision = $script:revision }
      builder = [pscustomobject]@{ id = "local://SYNTHETIC-BUILDER-001" }
      buildType = "maven-package"
      generatedAt = "2026-10-22T00:00:00Z"
      outputs = @(
        [pscustomobject]@{ name = "ayuvahak.jar"; sha256 = $script:digest },
        [pscustomobject]@{ name = "frontend-dist"; sha256 = $script:frontendDigest }
      )
    }
  }

  It "accepts a clean checkout with provenance bound to its exact JAR" {
    { Assert-ReleaseArtifactEvidence -Provenance $script:provenance -SourceRevision $script:revision -JarSha256 $script:digest -FrontendTreeSha256 $script:frontendDigest -SourceTreeClean $true } | Should Not Throw
  }

  It "rejects a JAR digest absent from the manifest or provenance" {
    $threw = Test-ActionThrows { Assert-ReleaseArtifactEvidence -Provenance $script:provenance -SourceRevision $script:revision -JarSha256 ("f" * 64) -FrontendTreeSha256 $script:frontendDigest -SourceTreeClean $true }
    $threw | Should Be $true
  }

  It "rejects frontend assets that are missing from provenance or have changed" {
    $wrongDigestThrew = Test-ActionThrows { Assert-ReleaseArtifactEvidence -Provenance $script:provenance -SourceRevision $script:revision -JarSha256 $script:digest -FrontendTreeSha256 ("f" * 64) -SourceTreeClean $true }
    $missingFrontend = [pscustomobject]@{ outputs = @($script:provenance.outputs[0]) }
    $missingFrontendThrew = Test-ActionThrows { Assert-ReleaseArtifactEvidence -Provenance $missingFrontend -SourceRevision $script:revision -JarSha256 $script:digest -FrontendTreeSha256 $script:frontendDigest -SourceTreeClean $true }
    $wrongDigestThrew | Should Be $true
    $missingFrontendThrew | Should Be $true
  }

  It "rejects a source revision mismatch and dirty checkout" {
    $wrongRevisionThrew = Test-ActionThrows { Assert-ReleaseArtifactEvidence -Provenance $script:provenance -SourceRevision ("f" * 40) -JarSha256 $script:digest -FrontendTreeSha256 $script:frontendDigest -SourceTreeClean $true }
    $dirtyTreeThrew = Test-ActionThrows { Assert-ReleaseArtifactEvidence -Provenance $script:provenance -SourceRevision $script:revision -JarSha256 $script:digest -FrontendTreeSha256 $script:frontendDigest -SourceTreeClean $false }
    $wrongRevisionThrew | Should Be $true
    $dirtyTreeThrew | Should Be $true
  }

  It "hashes frontend build files by relative path and exact bytes" {
    $directory = Join-Path ([IO.Path]::GetTempPath()) ("ayuvahak-frontend-tree-" + [guid]::NewGuid().ToString("N"))
    $assets = Join-Path $directory "assets"
    New-Item -ItemType Directory -Path $assets -Force | Out-Null
    try {
      [IO.File]::WriteAllText((Join-Path $directory "index.html"), "<html>SYN</html>`n", (New-Object System.Text.UTF8Encoding($false)))
      [IO.File]::WriteAllText((Join-Path $assets "app.js"), "console.log('SYN');`n", (New-Object System.Text.UTF8Encoding($false)))
      $first = Get-ReleaseDirectorySha256 -Path $directory
      $first | Should Match '^[0-9a-f]{64}$'
      (Get-ReleaseDirectorySha256 -Path $directory) | Should Be $first

      [IO.File]::WriteAllText((Join-Path $assets "app.js"), "console.log('changed');`n", (New-Object System.Text.UTF8Encoding($false)))
      (Get-ReleaseDirectorySha256 -Path $directory) | Should Not Be $first
    } finally {
      Remove-Item -LiteralPath $directory -Recurse -Force
    }
  }
}
