function Test-AyuVahakOwnedComposePort {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [AllowEmptyCollection()]
    [object[]] $Containers,
    [Parameter(Mandatory = $true)]
    [string] $ProjectName,
    [Parameter(Mandatory = $true)]
    [string] $ServiceName,
    [Parameter(Mandatory = $true)]
    [string] $ContainerPort,
    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 65535)]
    [int] $HostPort
  )

  foreach ($container in $Containers) {
    $labels = $container.Config.Labels
    $projectLabel = $labels.PSObject.Properties["com.docker.compose.project"]
    $serviceLabel = $labels.PSObject.Properties["com.docker.compose.service"]
    if (-not $projectLabel -or -not $serviceLabel -or
        [string]$projectLabel.Value -ne $ProjectName -or
        [string]$serviceLabel.Value -ne $ServiceName) {
      continue
    }

    $portProperty = $container.NetworkSettings.Ports.PSObject.Properties[$ContainerPort]
    if (-not $portProperty -or $null -eq $portProperty.Value) { continue }

    foreach ($binding in @($portProperty.Value)) {
      if ([string]$binding.HostIp -eq "127.0.0.1" -and
          [string]$binding.HostPort -eq [string]$HostPort) {
        return $true
      }
    }
  }

  return $false
}

Export-ModuleMember -Function Test-AyuVahakOwnedComposePort
