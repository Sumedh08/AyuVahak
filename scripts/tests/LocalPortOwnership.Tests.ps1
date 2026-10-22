$repositoryRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$modulePath = Join-Path $repositoryRoot "scripts\local\LocalPortOwnership.psm1"

Import-Module $modulePath -Force

Describe "Local Compose listener ownership" {
  BeforeAll {
    $ownedKeycloakContainer = [pscustomobject]@{
      State = [pscustomobject]@{ Running = $true }
      Config = [pscustomobject]@{
        Labels = [pscustomobject]@{
          "com.docker.compose.project" = "ayuvahak-empty-123456789abc"
          "com.docker.compose.service" = "keycloak"
        }
      }
      NetworkSettings = [pscustomobject]@{
        Ports = [pscustomobject]@{
          "8080/tcp" = @([pscustomobject]@{ HostIp = "127.0.0.1"; HostPort = "18091" })
        }
      }
    }
  }

  It "accepts a port already bound by this installation's exact loopback service" {
    Test-AyuVahakOwnedComposePort -Containers @($ownedKeycloakContainer) `
      -ProjectName "ayuvahak-empty-123456789abc" -ServiceName "keycloak" `
      -ContainerPort "8080/tcp" -HostPort 18091 | Should Be $true
  }

  It "rejects a listener owned by a different Compose installation" {
    Test-AyuVahakOwnedComposePort -Containers @($ownedKeycloakContainer) `
      -ProjectName "ayuvahak-empty-other-install" -ServiceName "keycloak" `
      -ContainerPort "8080/tcp" -HostPort 18091 | Should Be $false
  }

  It "rejects a port mapping for a different service or host port" {
    Test-AyuVahakOwnedComposePort -Containers @($ownedKeycloakContainer) `
      -ProjectName "ayuvahak-empty-123456789abc" -ServiceName "app" `
      -ContainerPort "8080/tcp" -HostPort 18091 | Should Be $false

    Test-AyuVahakOwnedComposePort -Containers @($ownedKeycloakContainer) `
      -ProjectName "ayuvahak-empty-123456789abc" -ServiceName "keycloak" `
      -ContainerPort "8080/tcp" -HostPort 18092 | Should Be $false
  }

  It "rejects wildcard-bound or absent host mapping evidence" {
    $wildcardBoundContainer = $ownedKeycloakContainer | ConvertTo-Json -Depth 8 | ConvertFrom-Json
    $wildcardBoundContainer.NetworkSettings.Ports.'8080/tcp'[0].HostIp = "0.0.0.0"
    Test-AyuVahakOwnedComposePort -Containers @($wildcardBoundContainer) `
      -ProjectName "ayuvahak-empty-123456789abc" -ServiceName "keycloak" `
      -ContainerPort "8080/tcp" -HostPort 18091 | Should Be $false

    Test-AyuVahakOwnedComposePort -Containers @() `
      -ProjectName "ayuvahak-empty-123456789abc" -ServiceName "keycloak" `
      -ContainerPort "8080/tcp" -HostPort 18091 | Should Be $false
  }
}
