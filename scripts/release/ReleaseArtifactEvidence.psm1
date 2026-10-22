function Assert-ReleaseArtifactEvidence {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)] [object] $Provenance,
    [Parameter(Mandatory = $true)] [string] $SourceRevision,
    [Parameter(Mandatory = $true)] [string] $JarSha256,
    [Parameter(Mandatory = $true)] [bool] $SourceTreeClean
  )

  if (-not $SourceTreeClean) { throw "The source tree must be clean before packaging release evidence." }
  if ($Provenance.schemaVersion -ne "ayuvahak.release-provenance/v1") {
    throw "The supplied provenance has an unsupported schema."
  }
  if ($SourceRevision -notmatch '^[0-9a-f]{40}$' -or $Provenance.source.revision -ne $SourceRevision) {
    throw "The provenance source revision does not match the package source revision."
  }
  if (-not $Provenance.builder.id -or -not $Provenance.buildType -or -not $Provenance.generatedAt) {
    throw "The supplied provenance is missing builder, build type, or generation metadata."
  }
  $digest = $JarSha256.ToLowerInvariant()
  if ($digest -notmatch '^[0-9a-f]{64}$') { throw "The application JAR SHA-256 is invalid." }
  $provenanceArtifacts = @($Provenance.outputs | Where-Object { $_.sha256 -eq $digest })
  if ($provenanceArtifacts.Count -ne 1) {
    throw "The application JAR digest is not uniquely bound by the supplied provenance."
  }
}

Export-ModuleMember -Function Assert-ReleaseArtifactEvidence
