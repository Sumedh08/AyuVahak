function Assert-ReleaseArtifactEvidence {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)] [object] $Provenance,
    [Parameter(Mandatory = $true)] [string] $SourceRevision,
    [Parameter(Mandatory = $true)] [string] $JarSha256,
    [Parameter(Mandatory = $true)] [string] $FrontendTreeSha256,
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
  $frontendDigest = $FrontendTreeSha256.ToLowerInvariant()
  if ($frontendDigest -notmatch '^[0-9a-f]{64}$') { throw "The frontend tree SHA-256 is invalid." }
  $frontendArtifacts = @($Provenance.outputs | Where-Object {
    $_.name -eq "frontend-dist" -and $_.sha256 -eq $frontendDigest
  })
  if ($frontendArtifacts.Count -ne 1) {
    throw "The built frontend tree digest is not uniquely bound by the supplied provenance."
  }
}

function Get-ReleaseDirectorySha256 {
  [CmdletBinding()]
  param([Parameter(Mandatory = $true)] [string] $Path)

  $root = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
  if (-not (Test-Path -LiteralPath $root -PathType Container)) {
    throw "The frontend artifact must be a directory."
  }
  $items = @(Get-ChildItem -LiteralPath $root -Force -Recurse)
  if ($items | Where-Object { ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 }) {
    throw "The frontend artifact cannot contain symbolic links or reparse points."
  }

  $files = @($items | Where-Object { -not $_.PSIsContainer })
  $rootPrefix = $root.TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
  $relativePaths = [string[]]@($files | ForEach-Object {
    $_.FullName.Substring($rootPrefix.Length).Replace([IO.Path]::DirectorySeparatorChar, '/').Replace([IO.Path]::AltDirectorySeparatorChar, '/')
  })
  [Array]::Sort($relativePaths, [StringComparer]::Ordinal)

  $hash = [Security.Cryptography.IncrementalHash]::CreateHash([Security.Cryptography.HashAlgorithmName]::SHA256)
  $utf8 = New-Object System.Text.UTF8Encoding($false)
  try {
    foreach ($relativePath in $relativePaths) {
      $relativeBytes = $utf8.GetBytes($relativePath)
      $pathLength = [BitConverter]::GetBytes([UInt32]$relativeBytes.Length)
      $filePath = Join-Path $root ($relativePath.Replace('/', [IO.Path]::DirectorySeparatorChar))
      $fileInfo = Get-Item -LiteralPath $filePath
      $size = [BitConverter]::GetBytes([UInt64]$fileInfo.Length)
      if ([BitConverter]::IsLittleEndian) {
        [Array]::Reverse($pathLength)
        [Array]::Reverse($size)
      }
      $hash.AppendData($pathLength)
      $hash.AppendData($relativeBytes)
      $hash.AppendData($size)
      $stream = [IO.File]::OpenRead($filePath)
      try {
        $buffer = New-Object byte[] 65536
        while (($read = $stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
          $hash.AppendData($buffer, 0, $read)
        }
      } finally {
        $stream.Dispose()
      }
    }
    $digest = $hash.GetHashAndReset()
    return ([BitConverter]::ToString($digest).Replace('-', '').ToLowerInvariant())
  } finally {
    $hash.Dispose()
  }
}

Export-ModuleMember -Function Assert-ReleaseArtifactEvidence, Get-ReleaseDirectorySha256
