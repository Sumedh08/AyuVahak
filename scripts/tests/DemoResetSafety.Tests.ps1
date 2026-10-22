$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Import-Module (Join-Path $repositoryRoot "scripts\local\DemoResetSafety.psm1") -Force

Describe "Synthetic Demo whole-volume reset boundary" {
  $projectName = "ayuvahak-synthetic_demo-a1b2c3d4e5f6"
  $configuration = @"
{
  "name": "$projectName",
  "services": {
    "database": {
      "environment": {},
      "volumes": [{ "source": "${projectName}_postgres-data", "target": "/var/lib/postgresql" }]
    },
    "app": {
      "environment": { "AYUVAHAK_DATABASE_URL": "jdbc:postgresql://database:5432/ayuvahak" },
      "ports": [{ "target": 8080, "published": "18080", "host_ip": "127.0.0.1", "protocol": "tcp" }]
    },
    "keycloak": {
      "volumes": [{ "source": "${projectName}_keycloak-data", "target": "/opt/keycloak/data" }],
      "ports": [{ "target": 8080, "published": "18081", "host_ip": "127.0.0.1", "protocol": "tcp" }]
    }
  },
  "volumes": {
    "postgres-data": { "name": "${projectName}_postgres-data" },
    "keycloak-data": { "name": "${projectName}_keycloak-data" }
  }
}
"@ | ConvertFrom-Json

  It "accepts only dedicated Compose volumes, an internal database endpoint and loopback service ports" {
    $expected = Assert-DemoResetComposeConfiguration $configuration $projectName 18080 18081
    Assert-DemoResetVolumeInspection @(
      [pscustomobject]@{ Name = $expected["postgres-data"]; Labels = @{ "com.docker.compose.project" = $projectName; "com.docker.compose.volume" = "postgres-data" } },
      [pscustomobject]@{ Name = $expected["keycloak-data"]; Labels = @{ "com.docker.compose.project" = $projectName; "com.docker.compose.volume" = "keycloak-data" } }
    ) $projectName $expected
    Assert-DemoResetContainerInspection @(
      [pscustomobject]@{ Config = [pscustomobject]@{ Labels = @{ "com.docker.compose.project" = $projectName; "com.docker.compose.service" = "app" } } }
    ) $projectName
  }

  It "rejects a database host port, non-loopback service binding or foreign project volume" {
    $databasePublished = $configuration | ConvertTo-Json -Depth 20 | ConvertFrom-Json
    $databasePublished.services.database | Add-Member -NotePropertyName ports -NotePropertyValue @(
      [pscustomobject]@{ target = 5432; published = "15432"; host_ip = "127.0.0.1"; protocol = "tcp" }
    )
    $databaseRefused = $false
    try { Assert-DemoResetComposeConfiguration $databasePublished $projectName 18080 18081 }
    catch { $databaseRefused = $true }
    $databaseRefused | Should Be $true

    $externalApp = $configuration | ConvertTo-Json -Depth 20 | ConvertFrom-Json
    $externalApp.services.app.ports[0].host_ip = "0.0.0.0"
    $externalRefused = $false
    try { Assert-DemoResetComposeConfiguration $externalApp $projectName 18080 18081 }
    catch { $externalRefused = $true }
    $externalRefused | Should Be $true

    $expected = Assert-DemoResetComposeConfiguration $configuration $projectName 18080 18081
    $foreign = [pscustomobject]@{
      Name = $expected["postgres-data"]
      Labels = @{ "com.docker.compose.project" = "other-installation"; "com.docker.compose.volume" = "postgres-data" }
    }
    $volumeRefused = $false
    try { Assert-DemoResetVolumeInspection @($foreign) $projectName $expected }
    catch { $volumeRefused = $true }
    $volumeRefused | Should Be $true
  }

  It "rejects any Compose container outside the dedicated application services" {
    $foreign = [pscustomobject]@{
      Config = [pscustomobject]@{
        Labels = @{ "com.docker.compose.project" = $projectName; "com.docker.compose.service" = "unrelated" }
      }
    }
    $containerRefused = $false
    try { Assert-DemoResetContainerInspection @($foreign) $projectName }
    catch { $containerRefused = $true }
    $containerRefused | Should Be $true
  }
}
