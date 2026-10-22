Set-StrictMode -Version Latest

function Get-OptionalProperty($Value, [string] $Name) {
  $property = $Value.PSObject.Properties[$Name]
  if ($property) { return $property.Value }
  return $null
}

function Get-LabelValue($Labels, [string] $Name) {
  if ($Labels -is [System.Collections.IDictionary]) { return $Labels[$Name] }
  return Get-OptionalProperty $Labels $Name
}

function Get-DemoResetExpectedVolumes($Configuration, [string] $ProjectName) {
  if ($Configuration.name -cne $ProjectName) {
    throw "The resolved Compose project does not match this installation. Reset was refused."
  }
  $expected = @{}
  foreach ($logicalName in @("postgres-data", "keycloak-data")) {
    $definition = $Configuration.volumes.PSObject.Properties[$logicalName]
    if (-not $definition) {
      throw "The resolved Compose configuration does not define the expected disposable data volumes. Reset was refused."
    }
    $actualName = [string]$definition.Value.name
    if ($actualName -cne "${ProjectName}_${logicalName}") {
      throw "A resolved volume name is outside this installation's dedicated project. Reset was refused."
    }
    $expected[$logicalName] = $actualName
  }
  return $expected
}

function Assert-DemoResetComposeConfiguration(
  $Configuration,
  [string] $ProjectName,
  [int] $ApplicationPort,
  [int] $IdentityPort) {
  $expectedVolumes = Get-DemoResetExpectedVolumes $Configuration $ProjectName
  $database = $Configuration.services.database
  $app = $Configuration.services.app
  $keycloak = $Configuration.services.keycloak
  if (-not $database -or -not $app -or -not $keycloak) {
    throw "The resolved Compose configuration is incomplete. Reset was refused."
  }
  $databasePorts = Get-OptionalProperty $database "ports"
  if ($null -ne $databasePorts -and @($databasePorts).Count -ne 0) {
    throw "The Demo database has a host-published port. Reset was refused."
  }
  if ([string]$app.environment.AYUVAHAK_DATABASE_URL -notmatch '^jdbc:postgresql://database:5432/[A-Za-z0-9_-]+$') {
    throw "The Demo application database endpoint is not the isolated Compose database. Reset was refused."
  }

  foreach ($mapping in @(
      @{ Service = $database; Target = "/var/lib/postgresql"; LogicalName = "postgres-data" },
      @{ Service = $keycloak; Target = "/opt/keycloak/data"; LogicalName = "keycloak-data" }
    )) {
    $mounts = @($mapping.Service.volumes | Where-Object { $_.target -ceq $mapping.Target })
    if ($mounts.Count -ne 1 -or [string]$mounts[0].source -cne $expectedVolumes[$mapping.LogicalName]) {
      throw "A Demo data mount does not resolve to the dedicated installation volume. Reset was refused."
    }
  }

  foreach ($binding in @(
      @{ Service = $app; Target = 8080; Port = $ApplicationPort },
      @{ Service = $keycloak; Target = 8080; Port = $IdentityPort }
    )) {
    $ports = @($binding.Service.ports | Where-Object { [int]$_.target -eq $binding.Target })
    if ($ports.Count -ne 1 -or [string]$ports[0].host_ip -cne "127.0.0.1" -or
        [int]$ports[0].published -ne $binding.Port -or [string]$ports[0].protocol -cne "tcp") {
      throw "A Demo service is not bound to its configured loopback port. Reset was refused."
    }
  }
  return $expectedVolumes
}

function Assert-DemoResetVolumeInspection($VolumeInspections, [string] $ProjectName, $ExpectedVolumes) {
  foreach ($volume in @($VolumeInspections)) {
    $logicalName = [string](Get-LabelValue $volume.Labels "com.docker.compose.volume")
    if (-not $logicalName -or -not $ExpectedVolumes.ContainsKey($logicalName) -or
        [string]$volume.Name -cne $ExpectedVolumes[$logicalName] -or
        [string](Get-LabelValue $volume.Labels "com.docker.compose.project") -cne $ProjectName) {
      throw "A Docker volume does not carry the expected dedicated Demo project labels. Reset was refused."
    }
  }
}

function Assert-DemoResetContainerInspection($ContainerInspections, [string] $ProjectName) {
  foreach ($container in @($ContainerInspections)) {
    $labels = $container.Config.Labels
    $service = [string](Get-LabelValue $labels "com.docker.compose.service")
    if ([string](Get-LabelValue $labels "com.docker.compose.project") -cne $ProjectName -or
        $service -notin @("app", "database", "keycloak")) {
      throw "A Docker container does not belong to the expected Demo installation. Reset was refused."
    }
  }
}

Export-ModuleMember -Function Assert-DemoResetComposeConfiguration, Assert-DemoResetVolumeInspection, Assert-DemoResetContainerInspection
