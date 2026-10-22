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
    $script:provenance = [pscustomobject]@{
      schemaVersion = "ayuvahak.release-provenance/v1"
      source = [pscustomobject]@{ revision = $script:revision }
      builder = [pscustomobject]@{ id = "local://SYNTHETIC-BUILDER-001" }
      buildType = "maven-package"
      generatedAt = "2026-10-22T00:00:00Z"
      outputs = @([pscustomobject]@{ name = "ayuvahak.jar"; sha256 = $script:digest })
    }
  }

  It "accepts a clean checkout with provenance bound to its exact JAR" {
    { Assert-ReleaseArtifactEvidence -Provenance $script:provenance -SourceRevision $script:revision -JarSha256 $script:digest -SourceTreeClean $true } | Should Not Throw
  }

  It "rejects a JAR digest absent from the manifest or provenance" {
    $threw = Test-ActionThrows { Assert-ReleaseArtifactEvidence -Provenance $script:provenance -SourceRevision $script:revision -JarSha256 ("f" * 64) -SourceTreeClean $true }
    $threw | Should Be $true
  }

  It "rejects a source revision mismatch and dirty checkout" {
    $wrongRevisionThrew = Test-ActionThrows { Assert-ReleaseArtifactEvidence -Provenance $script:provenance -SourceRevision ("f" * 40) -JarSha256 $script:digest -SourceTreeClean $true }
    $dirtyTreeThrew = Test-ActionThrows { Assert-ReleaseArtifactEvidence -Provenance $script:provenance -SourceRevision $script:revision -JarSha256 $script:digest -SourceTreeClean $false }
    $wrongRevisionThrew | Should Be $true
    $dirtyTreeThrew | Should Be $true
  }
}
