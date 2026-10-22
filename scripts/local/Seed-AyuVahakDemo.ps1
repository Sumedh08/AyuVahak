param(
  [switch] $PlanOnly,
  [switch] $ResumeInProgress
)

$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$environmentPath = Join-Path $repositoryRoot ".env"
$statePath = Join-Path $repositoryRoot ".local-state\installation.json"
$seedStatePath = Join-Path $repositoryRoot ".local-state\demo-seed-state.json"
$demoManifestPath = Join-Path $repositoryRoot "demo-data\demo-manifest.json"
$identityManifestPath = Join-Path $repositoryRoot "infra\keycloak\demo-manifest.json"
$demoConfigPath = Join-Path $repositoryRoot ".local-state\demo-application.yml"
Import-Module (Join-Path $PSScriptRoot "DemoSeedState.psm1") -Force

function Stop-Safely([string] $Message) {
  throw $Message
}

function Read-EnvironmentFile([string] $Path) {
  $values = @{}
  foreach ($line in Get-Content -LiteralPath $Path) {
    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
      $values[$Matches[1]] = $Matches[2]
    }
  }
  return $values
}

function Save-SeedState($Value) {
  $Value | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $seedStatePath -Encoding ascii
}

function Invoke-LocalToken([string] $TokenUri, [hashtable] $Form, [string] $Purpose) {
  try {
    return Invoke-RestMethod -Method Post -Uri $TokenUri -ContentType "application/x-www-form-urlencoded" `
      -Body $Form -TimeoutSec 10 -ErrorAction Stop
  } catch {
    $status = "unknown"
    if ($_.Exception.Response -and $_.Exception.Response.StatusCode) { $status = [string][int]$_.Exception.Response.StatusCode }
    $errorCode = "UNCLASSIFIED"
    $problemText = [string]$_.ErrorDetails.Message
    if (-not $problemText -and $_.Exception.Response) {
      $responseStream = $null
      $responseReader = $null
      try {
        $responseStream = $_.Exception.Response.GetResponseStream()
        if ($responseStream) {
          $responseReader = [System.IO.StreamReader]::new($responseStream)
          $problemText = $responseReader.ReadToEnd()
        }
      } catch { }
      finally {
        if ($responseReader) { $responseReader.Dispose() }
        elseif ($responseStream) { $responseStream.Dispose() }
      }
    }
    if ($problemText) {
      try {
        $problem = $problemText | ConvertFrom-Json -ErrorAction Stop
        if ([string]$problem.error -match '^[a-z][a-z0-9_]{0,79}$') { $errorCode = [string]$problem.error }
      } catch { }
    }
    Stop-Safely "Local Demo authentication failed during '$Purpose' (HTTP $status, $errorCode). Confirm Keycloak is ready and the Synthetic Demo identity is initialized. Credentials and response details were suppressed."
  }
}

function Invoke-DemoApi(
  [string] $Method,
  [string] $Path,
  [string] $Token,
  [string] $Scope,
  [string] $IdempotencyKey,
  [object] $Body = $null,
  [string] $OrganizationId = $null,
  [string] $FacilityId = $null,
  [string] $Description = $null) {
  $headers = @{
    Authorization = "Bearer $Token"
    "X-AyuVahak-Execution-Scope" = $Scope
  }
  if ($IdempotencyKey) { $headers["Idempotency-Key"] = $IdempotencyKey }
  if ($OrganizationId) { $headers["X-AyuVahak-Organization-Id"] = $OrganizationId }
  if ($FacilityId) { $headers["X-AyuVahak-Facility-Id"] = $FacilityId }
  $request = @{
    Method = $Method
    Uri = "${script:apiBase}$Path"
    Headers = $headers
    TimeoutSec = 20
    ErrorAction = "Stop"
  }
  if ($null -ne $Body) {
    $request.ContentType = "application/json"
    $serializedBody = $Body | ConvertTo-Json -Depth 40 -Compress
    $request.Body = $serializedBody
  }
  $stepDescription = if ($Description) { $Description } else { $Path }
  try {
    return Invoke-RestMethod @request
  } catch {
    $status = "unknown"
    if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
      $status = [string][int]$_.Exception.Response.StatusCode
    }
    $errorClass = "UNCLASSIFIED"
    $errorDiagnostic = ""
    $validationDiagnostics = @()
    $problemFieldDiagnostics = @()
    $problemText = [string]$_.ErrorDetails.Message
    if (-not $problemText -and $_.Exception.Response) {
      $responseStream = $null
      $responseReader = $null
      try {
        $responseStream = $_.Exception.Response.GetResponseStream()
        if ($responseStream) {
          $responseReader = [System.IO.StreamReader]::new($responseStream)
          $problemText = $responseReader.ReadToEnd()
        }
      } catch { }
      finally {
        if ($responseReader) { $responseReader.Dispose() }
        elseif ($responseStream) { $responseStream.Dispose() }
      }
    }
    if ($problemText) {
      try {
        $problem = $problemText | ConvertFrom-Json -ErrorAction Stop
        if ([string]$problem.code -match '^[A-Z][A-Z0-9_]{0,79}$') { $errorClass = [string]$problem.code }
        $problemFieldDiagnostics = @($problem.PSObject.Properties.Name | Where-Object { $_ -match '^[A-Za-z][A-Za-z0-9_]{0,39}$' })
        $errorDiagnostic = switch -Exact ([string]$problem.detail) {
          "The requested authority is not registered in this deployment." { "AUTHORITY_NOT_REGISTERED" }
          "A valid reason code is required." { "INVALID_REASON_CODE" }
          "The principal issuer is not configured as trusted." { "UNTRUSTED_PRINCIPAL_ISSUER" }
          "The idempotency key is missing or invalid." { "INVALID_IDEMPOTENCY_KEY" }
          "The principal reference is invalid." { "INVALID_PRINCIPAL_REFERENCE" }
          "The target scope is not valid for this authority or owning context." { "INVALID_TARGET_SCOPE" }
          default { "" }
        }
        if (-not $errorDiagnostic -and $errorClass -eq "VALIDATION_FAILED") {
          $detailCategory = ([string]$problem.detail).Trim()
          if ($detailCategory -match '(?i)service principal') { $errorDiagnostic = "SERVICE_CLIENT_ALLOWLIST" }
          elseif ($detailCategory -match '(?i)issuer') { $errorDiagnostic = "UNTRUSTED_PRINCIPAL_ISSUER" }
          elseif ($detailCategory -match '(?i)principal.*identifier|identifier.*principal') { $errorDiagnostic = "PRINCIPAL_IDENTIFIER" }
          elseif ($detailCategory -match '(?i)principal reference') { $errorDiagnostic = "PRINCIPAL_REFERENCE" }
          elseif ($detailCategory -match '(?i)authority|registered') { $errorDiagnostic = "AUTHORITY_VALIDATION" }
          elseif ($detailCategory -match '(?i)reason') { $errorDiagnostic = "REASON_VALIDATION" }
          elseif ($detailCategory -match '(?i)issuer|trusted') { $errorDiagnostic = "TRUST_VALIDATION" }
          elseif ($detailCategory -match '(?i)idempotency') { $errorDiagnostic = "IDEMPOTENCY_VALIDATION" }
          elseif ($detailCategory -match '(?i)principal|subject|client') { $errorDiagnostic = "PRINCIPAL_VALIDATION" }
          elseif ($detailCategory -match '(?i)scope|organization|facility') { $errorDiagnostic = "SCOPE_VALIDATION" }
          else { $errorDiagnostic = "UNCLASSIFIED_VALIDATION_DETAIL" }
        }
        foreach ($issue in @($problem.validationErrors)) {
          $field = [string]$issue.field
          $code = [string]$issue.code
          if ($field -match '^[A-Za-z][A-Za-z0-9_.\[\]]{0,79}$' -and $code -match '^[A-Z][A-Z0-9_]{0,39}$') {
            $validationDiagnostics += "$field`:$code"
          }
        }
      } catch { }
    }
    $diagnosticParts = @()
    if ($errorDiagnostic) { $diagnosticParts += $errorDiagnostic }
    if ($validationDiagnostics.Count -gt 0) {
      $diagnosticParts += "validation fields $($validationDiagnostics -join ', ')"
    } elseif ($problemFieldDiagnostics.Count -gt 0) {
      $diagnosticParts += "problem fields $($problemFieldDiagnostics -join ', ')"
    }
    $diagnosticSuffix = if ($diagnosticParts.Count -gt 0) { ", $($diagnosticParts -join '; ')" } else { "" }
    Stop-Safely "Synthetic Demo owner API step failed during '$stepDescription' (HTTP $status, $errorClass$diagnosticSuffix). The installation is marked IN_PROGRESS; inspect its local state or reset the dedicated Demo volume. Request and response bodies were suppressed."
  }
}

function New-GrantBody(
  [string] $Kind,
  [string] $Issuer,
  [string] $Identifier,
  [string] $Authority,
  [string] $Scope,
  [string] $OrganizationId = $null,
  [string] $FacilityId = $null) {
  $principal = if ($Kind -eq "HUMAN") {
    @{ kind = "HUMAN"; issuer = $Issuer; subject = $Identifier }
  } else {
    @{ kind = "SERVICE"; issuer = $Issuer; clientId = $Identifier }
  }
  $target = @{ kind = $Scope }
  if ($OrganizationId) { $target.organization = @{ issuer = "urn:ayuvahak:directory"; identifier = $OrganizationId } }
  if ($FacilityId) { $target.facility = @{ issuer = "urn:ayuvahak:directory"; identifier = $FacilityId } }
  $reasonCode = if ($Kind -eq "SERVICE") { "SERVICE_SETUP" } else { "ACCESS_SETUP" }
  return @{
    principal = $principal
    authority = $Authority
    scope = $target
    reasonCode = $reasonCode
  }
}

function Grant-Authority(
  [string] $AdminToken,
  [string] $Kind,
  [string] $Identifier,
  [string] $Authority,
  [string] $Scope,
  [string] $OrganizationId = $null,
  [string] $FacilityId = $null) {
  $scopeKey = if ($FacilityId) { "facility-$FacilityId" } elseif ($OrganizationId) { "organization-$OrganizationId" } else { "platform" }
  $principalKey = if ($Kind -eq "HUMAN") { "human-$Identifier" } else { "service-$Identifier" }
  $keyMaterial = [System.Text.Encoding]::UTF8.GetBytes("$principalKey|$Authority|$scopeKey")
  $hashAlgorithm = [System.Security.Cryptography.SHA256]::Create()
  try {
    $keyDigest = [BitConverter]::ToString($hashAlgorithm.ComputeHash($keyMaterial)).Replace("-", "").ToLowerInvariant().Substring(0, 32)
  } finally {
    $hashAlgorithm.Dispose()
  }
  $key = "demo-v2-grant-$keyDigest"
  Invoke-DemoApi -Method Post -Path "/api/v1/platform/access-grants" -Token $AdminToken `
    -Scope "PLATFORM" -IdempotencyKey $key -Body (New-GrantBody $Kind $script:issuer $Identifier $Authority $Scope $OrganizationId $FacilityId) `
    -Description "access grant for $Kind principal: $Authority at $Scope scope" | Out-Null
}

function New-DirectoryResource(
  [string] $Path,
  [object] $Body,
  [string] $IdempotencyKey,
  [string] $Token,
  [string] $Description) {
  $result = Invoke-DemoApi -Method Post -Path $Path -Token $Token -Scope "PLATFORM" `
    -IdempotencyKey $IdempotencyKey -Body $Body -Description $Description
  return [string]$result.id
}

function Wait-LocalReadiness([int] $Port) {
  # A fresh packaged-JAR restart can spend 505 seconds loading the bundled FHIR profiles under
  # local synthetic acceptance load. Match the install launcher's bounded ten-minute readiness
  # window so the Demo-only profile restart does not time out before the app can serve requests.
  $deadline = (Get-Date).AddMinutes(10)
  while ((Get-Date) -lt $deadline) {
    try {
      $response = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/actuator/health/readiness" `
        -TimeoutSec 3 -ErrorAction Stop
      if ($response.status -eq "UP") { return }
    } catch { }
    Start-Sleep -Seconds 3
  }
  Stop-Safely "The local Demo application did not become ready after its Demo-only Matching configuration was applied. No credentials were printed."
}

if (-not (Test-Path -LiteralPath $environmentPath -PathType Leaf) -or
    -not (Test-Path -LiteralPath $statePath -PathType Leaf) -or
    -not (Test-Path -LiteralPath $demoManifestPath -PathType Leaf) -or
    -not (Test-Path -LiteralPath $identityManifestPath -PathType Leaf)) {
  Stop-Safely "Synthetic Demo is not initialized. Start a local Synthetic Demo installation first."
}

$installation = Get-Content -LiteralPath $statePath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($installation.mode -ne "SYNTHETIC_DEMO" -or $installation.installationId -notmatch '^[a-f0-9]{12}$') {
  Stop-Safely "Demo seeding is restricted to a valid, dedicated SYNTHETIC_DEMO installation. No API request was sent."
}
$values = Read-EnvironmentFile $environmentPath
if ($values["AYUVAHAK_DEPLOYMENT_MODE"] -ne "SYNTHETIC_DEMO") {
  Stop-Safely "The local mode marker and .env disagree. No API request was sent."
}
$manifest = Get-Content -LiteralPath $demoManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
$identity = Get-Content -LiteralPath $identityManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($manifest.version -ne 2 -or $manifest.mode -ne "SYNTHETIC_DEMO" -or
    $manifest.manifestId -ne "SYN-AYUVAHAK-DEMO-V2" -or
    $identity.version -ne 1 -or $identity.mode -ne "SYNTHETIC_DEMO" -or
    $identity.seedCredentialMode -ne "GENERATED_PER_INSTALLATION" -or
    $identity.seedUsername -ne "synthetic.demo-seeder" -or
    $identity.seedClientId -ne "ayuvahak-demo-seed-client") {
  Stop-Safely "The local versioned Demo manifest is unsupported or invalid. No API request was sent."
}

$httpPort = 0
$identityPort = 0
if (-not [int]::TryParse([string]$values["AYUVAHAK_HTTP_PORT"], [ref]$httpPort) -or
    -not [int]::TryParse([string]$values["AYUVAHAK_KEYCLOAK_PORT"], [ref]$identityPort) -or
    $httpPort -lt 1024 -or $httpPort -gt 65535 -or
    $identityPort -lt 1024 -or $identityPort -gt 65535 -or $httpPort -eq $identityPort) {
  Stop-Safely "The local Demo ports are invalid. No API request was sent."
}
$issuerUri = $null
$issuerText = [string]$values["AYUVAHAK_OIDC_ISSUER"]
if (-not [uri]::TryCreate($issuerText, [System.UriKind]::Absolute, [ref]$issuerUri) -or
    $issuerUri.Host -ne "localhost" -or $issuerUri.Port -ne $identityPort -or
    $issuerUri.AbsolutePath -ne "/realms/ayuvahak" -or $issuerUri.Scheme -ne "http" -or
    $issuerUri.Query -or $issuerUri.Fragment -or $issuerUri.UserInfo) {
  Stop-Safely "The Demo seed command only accepts the configured loopback Keycloak realm. No API request was sent."
}
$script:issuer = $issuerUri.AbsoluteUri.TrimEnd("/")
$script:apiBase = "http://127.0.0.1:$httpPort"

$priorSeedState = $null
if (Test-Path -LiteralPath $seedStatePath -PathType Leaf) {
  $prior = Get-Content -LiteralPath $seedStatePath -Raw -Encoding UTF8 | ConvertFrom-Json
  if ($prior.state -eq "COMPLETE" -and $prior.manifestId -eq $manifest.manifestId -and
      $prior.installationId -eq $installation.installationId) {
    Write-Host "Synthetic Demo manifest $($manifest.manifestId) is already seeded for this local installation. No data was changed."
    exit 0
  }
  if (-not $ResumeInProgress -or $prior.state -ne "IN_PROGRESS" -or
      $prior.manifestId -ne $manifest.manifestId -or
      $prior.manifestVersion -ne $manifest.version -or
      $prior.installationId -ne $installation.installationId) {
    Stop-Safely "A previous Demo seed is incomplete or belongs to another installation. Use -ResumeInProgress only for the matching manifest/install, or reset its dedicated Demo volume."
  }
  $priorSeedState = [ordered]@{}
  foreach ($property in $prior.PSObject.Properties) {
    $priorSeedState[$property.Name] = $property.Value
  }
  foreach ($timestampName in @("startedAt", "observedAt", "requestStart", "requestEnd", "referralSourceCutoff")) {
    if ($priorSeedState.Contains($timestampName) -and $priorSeedState[$timestampName]) {
      # ConvertFrom-Json materializes ISO timestamps as local DateTime values on Windows PowerShell.
      # Normalize them back to an explicit UTC representation before fingerprinted owner API replay.
      $priorSeedState[$timestampName] = ConvertTo-StableDemoSeedTimestamp $priorSeedState[$timestampName]
    }
  }
  if ($priorSeedState.Contains("referralSourceCutoff") -and $priorSeedState["referralSourceCutoff"]) {
    $priorSeedState["referralSourceCutoff"] =
      ConvertTo-StableDemoSourceCutoff $priorSeedState["referralSourceCutoff"]
  }
} elseif ($ResumeInProgress) {
  Stop-Safely "There is no incomplete Demo seed to resume. No API request was sent."
}

if ($PlanOnly) {
  Write-Host "Plan validated for the dedicated loopback Synthetic Demo installation."
  Write-Host "The command will create fictional Directory, Capacity, Referral, Matching, Transfer, Transport, and aggregate Analytics data through authenticated owner APIs."
  Write-Host "Federation and export remain NOT_CONFIGURED; no peer or external target is contacted."
  Write-Host "No network request was sent and no local seed state was written."
  exit 0
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
  Stop-Safely "Docker Compose is required to safely apply the Demo-only configuration and restart the packaged app. No API request was sent."
}

$keycloakTokenUri = "$script:issuer/protocol/openid-connect/token"
$bootstrapUsername = [string]$identity.bootstrapUsername
$bootstrapPassword = [string]$values["AYUVAHAK_DEMO_PASSWORD"]
$seedUsername = [string]$identity.seedUsername
$seedPassword = [string]$values["AYUVAHAK_DEMO_PASSWORD"]
$transferAgentClientSecret = [string]$values["AYUVAHAK_TRANSFER_AGENT_CLIENT_SECRET"]
if (-not $bootstrapUsername -or -not $seedUsername -or
    $bootstrapPassword.Length -lt 32 -or $seedPassword -ne $bootstrapPassword -or
    $transferAgentClientSecret.Length -lt 32) {
  Stop-Safely "Per-install Synthetic Demo credentials are missing or invalid. Reinitialize the local Demo installation deliberately. No API request was sent."
}

try {
  $readiness = Invoke-RestMethod -Uri "$script:apiBase/actuator/health/readiness" -TimeoutSec 5 -ErrorAction Stop
  if ($readiness.status -ne "UP") { Stop-Safely "The local AyuVahak application is not ready. No business data was sent." }
} catch {
  if ($_.Exception.Message -like "The local AyuVahak application is not ready*") { throw }
  Stop-Safely "The local AyuVahak application is unavailable. No business data was sent."
}

$adminTokenResponse = Invoke-LocalToken -TokenUri $keycloakTokenUri -Form @{
  grant_type = "password"
  client_id = "ayuvahak-demo-seed-client"
  username = $bootstrapUsername
  password = $bootstrapPassword
} -Purpose "bootstrap operator token"
$adminToken = [string]$adminTokenResponse.access_token
$null = Invoke-DemoApi -Method Get -Path "/api/v1/platform/access-grants?limit=1" `
  -Token $adminToken -Scope "PLATFORM" -IdempotencyKey $null
$seederTokenResponse = Invoke-LocalToken -TokenUri $keycloakTokenUri -Form @{
  grant_type = "password"
  client_id = [string]$identity.seedClientId
  username = $seedUsername
  password = $seedPassword
} -Purpose "synthetic seed actor token"
$seederToken = [string]$seederTokenResponse.access_token
$seederSubject = [string]$identity.seedSubject

$seedState = if ($priorSeedState) { $priorSeedState } else {
  $startedAt = [DateTimeOffset]::UtcNow
  [ordered]@{
    state = "IN_PROGRESS"
    manifestId = [string]$manifest.manifestId
    manifestVersion = [int]$manifest.version
    installationId = [string]$installation.installationId
    startedAt = $startedAt.ToString("O")
    observedAt = $startedAt.AddSeconds(-2).ToString("O")
    computationRequestId = [guid]::NewGuid().ToString()
  }
}
$seedStartedAt = [DateTimeOffset]::Parse([string]$seedState.startedAt)
if (-not $seedState.observedAt) { $seedState.observedAt = $seedStartedAt.AddSeconds(-2).ToString("O") }
if (-not $seedState.computationRequestId) { $seedState.computationRequestId = [guid]::NewGuid().ToString() }
if (-not $seedState.requestStart) { $seedState.requestStart = $seedStartedAt.AddHours(1).ToString("O") }
if (-not $seedState.requestEnd) { $seedState.requestEnd = $seedStartedAt.AddHours(3).ToString("O") }
if (-not $seedState.referralSourceCutoff) {
  $seedState.referralSourceCutoff = ConvertTo-StableDemoSourceCutoff $seedStartedAt
} else {
  $seedState.referralSourceCutoff = ConvertTo-StableDemoSourceCutoff $seedState.referralSourceCutoff
}
if (-not $seedState.periodStart) {
  $seedState.periodStart = Get-PreviousCompletedReportingMonthStart $seedStartedAt
}
if (-not $seedState.periodEnd) {
  $seedPeriodStart = [DateTime]::ParseExact([string]$seedState.periodStart, "yyyy-MM-dd", [Globalization.CultureInfo]::InvariantCulture)
  $seedState.periodEnd = $seedPeriodStart.AddMonths(1).ToString("yyyy-MM-dd")
}
Save-SeedState $seedState

# This account is only a synthetic local seed actor. The bootstrap operator remains separate and
# grants it the exact local authorities through the protected AccessGrantAdministration API.
Grant-Authority $adminToken "HUMAN" $seederSubject "DirectoryCuration" "PLATFORM"
Grant-Authority $adminToken "HUMAN" $seederSubject "DirectoryRead" "PLATFORM"
Grant-Authority $adminToken "HUMAN" $seederSubject "DirectoryVocabularyAdministration" "PLATFORM"
Grant-Authority $adminToken "HUMAN" $seederSubject "CapacityPolicyAdministration" "PLATFORM"
Grant-Authority $adminToken "HUMAN" $seederSubject "AnalyticsIndicatorDefinitionAdministration" "PLATFORM"
Grant-Authority $adminToken "HUMAN" $seederSubject "OperatorSupportRead" "PLATFORM"

$originOrganization = $manifest.organizations | Where-Object key -eq "origin" | Select-Object -First 1
$receivingOrganization = $manifest.organizations | Where-Object key -eq "receiving" | Select-Object -First 1
$originFacilityTemplate = $manifest.facilities | Where-Object key -eq "origin" | Select-Object -First 1
$receivingFacilityTemplate = $manifest.facilities | Where-Object key -eq "receiving" | Select-Object -First 1
$now = [DateTimeOffset]::Parse($seedState.observedAt).ToString("O")
$organizationProvenance = @{ method = "MANUAL_REVIEW"; observedAt = $now; reasonCode = "INITIAL_ENTRY" }
$facilityProvenance = @{ method = "MANUAL_REVIEW"; observedAt = $now; reasonCode = "INITIAL_ENTRY" }
$locationProvenance = @{ method = "MANUAL_REVIEW"; observedAt = $now; reasonCode = "LOCATION_ENTRY" }
$capabilityProvenance = @{ method = "MANUAL_REVIEW"; observedAt = $now; reasonCode = "CAPABILITY_ENTRY" }
$assertionProvenance = @{ method = "MANUAL_REVIEW"; observedAt = $now; reasonCode = "MANUAL_REVIEW" }

$originOrganizationId = New-DirectoryResource "/api/v1/directory/organizations" @{
  name = $originOrganization.name
  source = @{ issuer = "urn:ayuvahak:synthetic:demo"; resourceType = "Organization"; reference = $originOrganization.id }
  provenance = $organizationProvenance
} "demo-v2-directory-organization-origin" $seederToken "create synthetic origin organization"
$receivingOrganizationId = New-DirectoryResource "/api/v1/directory/organizations" @{
  name = $receivingOrganization.name
  source = @{ issuer = "urn:ayuvahak:synthetic:demo"; resourceType = "Organization"; reference = $receivingOrganization.id }
  provenance = $organizationProvenance
} "demo-v2-directory-organization-receiving" $seederToken "create synthetic receiving organization"

$originFacilityId = New-DirectoryResource "/api/v1/directory/facilities" @{
  name = $originFacilityTemplate.name
  managingOrganizationId = $originOrganizationId
  source = @{ issuer = "urn:ayuvahak:synthetic:demo"; resourceType = "Facility"; reference = $originFacilityTemplate.id }
  provenance = $facilityProvenance
} "demo-v2-directory-facility-origin" $seederToken
$receivingFacilityId = New-DirectoryResource "/api/v1/directory/facilities" @{
  name = $receivingFacilityTemplate.name
  managingOrganizationId = $receivingOrganizationId
  source = @{ issuer = "urn:ayuvahak:synthetic:demo"; resourceType = "Facility"; reference = $receivingFacilityTemplate.id }
  provenance = $facilityProvenance
} "demo-v2-directory-facility-receiving" $seederToken

# Synthetic browser personas are separate local IdP identities. Their authority comes only from
# the same granular owner grants used by institutional deployments; the fixed demo password is
# confined to the loopback-only Synthetic Demo realm.
$personaGrantTemplates = @{
  "organization-directory-administrator" = @("DirectoryRead", "DirectoryCuration")
  "referral-coordinator-facility" = @(
    "ReferralRead", "ReferralCreate", "ReferralSubmit", "RecommendationRead",
    "RecommendationRequest", "TransferViewAuthority", "TransferRequest", "TransferProgressAuthority"
  )
  "receiving-coordinator" = @(
    "TransferViewAuthority", "ClinicalSuitabilityAuthority", "OperationalReadinessAuthority",
    "AcceptanceAuthority", "TransferReservationReconciliation", "TransferHandoverAuthority",
    "TransferCompletionAuthority"
  )
  "capacity-manager" = @("CapacityEvidenceReview", "CapacityReporting", "CapacityReservation")
  "transport-coordinator" = @(
    "TransferViewAuthority", "TransportRequestAuthority", "TransportDispatchCoordinationAuthority",
    "TransportStatusAuthority"
  )
  "analyst" = @("AnalyticsAggregateRead")
}
foreach ($persona in @($identity.personas)) {
  $templateKey = [string]$persona.template
  $subject = [string]$persona.subject
  if (-not $personaGrantTemplates.ContainsKey($templateKey) -or
      $subject -notmatch '^[a-f0-9-]{36}$' -or
      [string]$persona.username -notmatch '^synthetic\.[a-z-]+$') {
    Stop-Safely "The Synthetic Demo persona manifest is invalid. No persona grants were created."
  }
  $organizationId = switch ([string]$persona.organizationKey) {
    "origin" { $originOrganizationId }
    "receiving" { $receivingOrganizationId }
    default { $null }
  }
  $facilityId = switch ([string]$persona.facilityKey) {
    "origin" { $originFacilityId }
    "receiving" { $receivingFacilityId }
    default { $null }
  }
  if (-not $organizationId -or
      (([string]$persona.facilityKey) -and -not $facilityId)) {
    Stop-Safely "A Synthetic Demo persona references an unknown owner scope. No persona grants were created."
  }
  $scope = if ($facilityId) { "FACILITY" } else { "ORGANIZATION" }
  foreach ($authority in $personaGrantTemplates[$templateKey]) {
    Grant-Authority $adminToken "HUMAN" $subject $authority $scope $organizationId $facilityId
  }
}

foreach ($facility in @(
    @{ id = $originFacilityId; key = "origin" },
    @{ id = $receivingFacilityId; key = "receiving" })) {
  $location = $manifest.location.($facility.key)
  $locationBody = @{
    facilityId = $facility.id
    name = $manifest.location.name
    longitude = $location.longitude
    latitude = $location.latitude
    primary = $true
    source = @{ issuer = "urn:ayuvahak:synthetic:demo"; resourceType = "Location"; reference = "SYN-LOCATION-$($facility.key.ToUpperInvariant())" }
    provenance = $locationProvenance
  }
  New-DirectoryResource "/api/v1/directory/locations" $locationBody "demo-v2-location-$($facility.key)" $seederToken | Out-Null
}

foreach ($capability in $manifest.capabilities) {
  New-DirectoryResource "/api/v1/directory/capabilities" @{
    code = $capability.code
    displayName = $capability.displayName
    source = @{ issuer = "urn:ayuvahak:synthetic:demo"; resourceType = "CareCapability"; reference = $capability.code }
    provenance = $capabilityProvenance
  } "demo-v2-capability-$($capability.code)" $seederToken | Out-Null
  Invoke-DemoApi -Method Post -Path "/api/v1/directory/facilities/$receivingFacilityId/capability-assertions" `
    -Token $seederToken -Scope "PLATFORM" -IdempotencyKey "demo-v2-capability-assertion-$($capability.code)" `
    -Body @{
      capabilityCode = $capability.code
      healthcareServiceId = $null
      verificationStatus = "VERIFIED"
      source = @{ issuer = "urn:ayuvahak:synthetic:demo"; resourceType = "FacilityCapabilityAssertion"; reference = "SYN-ASSERTION-$($capability.code)" }
      provenance = $assertionProvenance
      effectiveFrom = "2026-01-01T00:00:00Z"
      effectiveUntil = $null
    } | Out-Null
}

Grant-Authority $adminToken "HUMAN" $seederSubject "CapacityReporting" "FACILITY" $receivingOrganizationId $receivingFacilityId
Grant-Authority $adminToken "HUMAN" $seederSubject "CapacityEvidenceReview" "FACILITY" $receivingOrganizationId $receivingFacilityId
Grant-Authority $adminToken "HUMAN" $seederSubject "ReferralCreate" "FACILITY" $originOrganizationId $originFacilityId
Grant-Authority $adminToken "HUMAN" $seederSubject "ReferralSubmit" "FACILITY" $originOrganizationId $originFacilityId
Grant-Authority $adminToken "HUMAN" $seederSubject "ReferralRead" "FACILITY" $originOrganizationId $originFacilityId
Grant-Authority $adminToken "HUMAN" $seederSubject "RecommendationRequest" "FACILITY" $originOrganizationId $originFacilityId
Grant-Authority $adminToken "HUMAN" $seederSubject "RecommendationRead" "FACILITY" $originOrganizationId $originFacilityId
Grant-Authority $adminToken "HUMAN" $seederSubject "TransferRequest" "FACILITY" $originOrganizationId $originFacilityId
Grant-Authority $adminToken "HUMAN" $seederSubject "TransferViewAuthority" "FACILITY" $originOrganizationId $originFacilityId
Grant-Authority $adminToken "HUMAN" $seederSubject "ClinicalSuitabilityAuthority" "FACILITY" $receivingOrganizationId $receivingFacilityId
Grant-Authority $adminToken "HUMAN" $seederSubject "OperationalReadinessAuthority" "FACILITY" $receivingOrganizationId $receivingFacilityId
Grant-Authority $adminToken "HUMAN" $seederSubject "AcceptanceAuthority" "FACILITY" $receivingOrganizationId $receivingFacilityId
Grant-Authority $adminToken "HUMAN" $seederSubject "TransportRequestAuthority" "FACILITY" $originOrganizationId $originFacilityId
Grant-Authority $adminToken "HUMAN" $seederSubject "TransportStatusAuthority" "FACILITY" $originOrganizationId $originFacilityId
Grant-Authority $adminToken "HUMAN" $seederSubject "TransportDispatchCoordinationAuthority" "FACILITY" $originOrganizationId $originFacilityId
Grant-Authority $adminToken "HUMAN" $seederSubject "AnalyticsAggregateRead" "ORGANIZATION" $originOrganizationId

foreach ($entry in @(
    @{ client = "ayuvahak-matching-owner-read"; authority = "ReferralRead"; scope = "FACILITY"; org = $originOrganizationId; facility = $originFacilityId },
    @{ client = "ayuvahak-matching-owner-read"; authority = "DirectoryRead"; scope = "FACILITY"; org = $originOrganizationId; facility = $originFacilityId },
    @{ client = "ayuvahak-matching-owner-read"; authority = "DirectoryRead"; scope = "ORGANIZATION"; org = $receivingOrganizationId; facility = $null },
    @{ client = "ayuvahak-matching-owner-read"; authority = "CapacityEvidenceReview"; scope = "FACILITY"; org = $receivingOrganizationId; facility = $receivingFacilityId },
    @{ client = "ayuvahak-transfer-reservation-worker"; authority = "CapacityReservation"; scope = "FACILITY"; org = $receivingOrganizationId; facility = $receivingFacilityId },
    @{ client = "ayuvahak-transfer-reservation-worker"; authority = "TransferReservationReconciliation"; scope = "FACILITY"; org = $receivingOrganizationId; facility = $receivingFacilityId },
    @{ client = "ayuvahak-transfer-owner-event-consumer"; authority = "TransferProgressAuthority"; scope = "FACILITY"; org = $originOrganizationId; facility = $originFacilityId },
    @{ client = "ayuvahak-transport-worker"; authority = "TransferTransportRequestEligibility"; scope = "FACILITY"; org = $originOrganizationId; facility = $originFacilityId },
    @{ client = "ayuvahak-transport-worker"; authority = "TransportRequestAuthority"; scope = "FACILITY"; org = $originOrganizationId; facility = $originFacilityId },
    @{ client = "transfer-agent"; authority = "AnalyticsAggregateComputation"; scope = "ORGANIZATION"; org = $originOrganizationId; facility = $null },
    @{ client = "transfer-agent"; authority = "ReferralReportingSnapshot"; scope = "ORGANIZATION"; org = $originOrganizationId; facility = $null },
    @{ client = "transfer-agent"; authority = "TransferReportingSnapshot"; scope = "ORGANIZATION"; org = $originOrganizationId; facility = $null })) {
  Grant-Authority $adminToken "SERVICE" $entry.client $entry.authority $entry.scope $entry.org $entry.facility
}

$dimension = [string]$manifest.capacity.dimension
Invoke-DemoApi -Method Post -Path "/api/v1/capacity/policies/dimensions" -Token $seederToken `
  -Scope "PLATFORM" -IdempotencyKey "demo-v2-capacity-dimension" -Body @{
    code = $dimension
    expectedVersion = 0
    displayName = $manifest.capacity.displayName
    meaning = "Synthetic coordination reservation capacity for the local Demo only"
    valueKind = "QUANTITY"
    unit = $manifest.capacity.unit
    minimumValue = 0
    maximumValue = 100
    allowedStates = @()
    usedForReadiness = $true
    reservable = $true
    reasonCode = "SYNTHETIC_DEMO_SEED_V1"
  } | Out-Null
Invoke-DemoApi -Method Post -Path "/api/v1/capacity/policies/dimensions/$dimension/policy" -Token $seederToken `
  -Scope "PLATFORM" -IdempotencyKey "demo-v2-capacity-policy" -Body @{
    expectedVersion = 0
    dimensionVersion = 1
    sources = @(
      @{ sourceCode = "primary"; priority = 0; fallbackWhenHigherPriorityAbsent = $false; fallbackWhenHigherPriorityStale = $false },
      @{ sourceCode = "manual"; priority = 1; fallbackWhenHigherPriorityAbsent = $true; fallbackWhenHigherPriorityStale = $false })
    maximumObservationAgeSeconds = 86400
    defaultValiditySeconds = 43200
    futureSkewToleranceSeconds = 30
    minimumPassingQuantity = 1
    passingStates = @()
    reasonCode = "SYNTHETIC_DEMO_SEED_V1"
  } | Out-Null

$matchingConfig = @"
ayuvahak:
  matching:
    policy:
      active: true
      code: SYN_DEMO_MATCHING_V1
      version: 1
      mapping-code: SYN_DEMO_CAPABILITY_MAPPING_V1
      mapping-version: 1
      dimensions-by-capability:
        $($manifest.capabilities[0].code):
          - $dimension
      candidate-organization-ids:
        - $receivingOrganizationId
      primary-metric: STRAIGHT_LINE_DISTANCE
      allow-distance-fallback: true
      maximum-recommendation-age: PT1H
      directory-page-size: 50
      destination-location-required: true
  transfer:
    request-deadline: P1D
    reservation-dimension: $dimension
    reservation-unit: $($manifest.capacity.unit)
    reservation-quantity: 1
    reservation-duration: PT2H
"@
Set-Content -LiteralPath $demoConfigPath -Value $matchingConfig -Encoding ascii

$composeFiles = @("--file", (Join-Path $repositoryRoot "compose.yaml"), "--file", (Join-Path $repositoryRoot "compose.demo.yaml"))
$projectName = "ayuvahak-synthetic_demo-$($installation.installationId)"
$compose = @("compose", "--project-name", $projectName, "--env-file", $environmentPath) + $composeFiles
Push-Location $repositoryRoot
try {
  & docker @compose config --quiet
  if ($LASTEXITCODE -ne 0) { Stop-Safely "The local Demo Compose configuration is invalid; no app restart was requested." }
  & docker @compose up --detach --force-recreate app
  if ($LASTEXITCODE -ne 0) { Stop-Safely "The local Demo app could not be restarted with its versioned Matching profile." }
} finally { Pop-Location }
Wait-LocalReadiness $httpPort

$seederTokenResponse = Invoke-LocalToken -TokenUri $keycloakTokenUri -Form @{
  grant_type = "password"
  client_id = [string]$identity.seedClientId
  username = $seedUsername
  password = $seedPassword
} -Purpose "post-configuration synthetic seed actor token"
$seederToken = [string]$seederTokenResponse.access_token
$serviceTokenResponse = Invoke-LocalToken -TokenUri $keycloakTokenUri -Form @{
  grant_type = "client_credentials"
  client_id = "transfer-agent"
  client_secret = $transferAgentClientSecret
} -Purpose "local transfer-agent service token"
$serviceToken = [string]$serviceTokenResponse.access_token

Invoke-DemoApi -Method Post -Path "/api/v1/capacity/observations" -Token $seederToken `
  -Scope "FACILITY" -OrganizationId $receivingOrganizationId -FacilityId $receivingFacilityId `
  -IdempotencyKey "demo-v2-capacity-observation" -Body @{
    dimensionCode = $dimension
    sourceCode = "manual"
    sourceIssuer = "urn:ayuvahak:synthetic:demo-capacity"
    sourceObservationId = "SYN-CAPACITY-OBSERVATION-001"
    observationKind = "VALUE"
    valueKind = "QUANTITY"
    quantity = [int]$manifest.capacity.quantity
    state = $null
    unit = [string]$manifest.capacity.unit
    observedAt = $seedState.observedAt
    validUntil = $null
    reportingMethod = "MANUAL_ENTRY"
    reasonCode = "SYNTHETIC_DEMO_SEED_V1"
  } | Out-Null

$requestStart = [DateTimeOffset]::Parse([string]$seedState.requestStart)
$requestEnd = [DateTimeOffset]::Parse([string]$seedState.requestEnd)
$referralBody = @{
  patientReference = $manifest.referral.patientReference
  careRequirement = @{
    requiredCareCapabilityCodes = @([string]$manifest.capabilities[0].code)
    requestedStart = $requestStart.ToString("O")
    requestedEnd = $requestEnd.ToString("O")
    permittedGeographyCodes = @($manifest.referral.permittedGeographyCodes)
    selectedDestinationOrganizationId = $null
    priorityCode = [string]$manifest.referral.priorityCode
  }
}
$referral = Invoke-DemoApi -Method Post -Path "/api/v1/referrals" -Token $seederToken `
  -Scope "FACILITY" -OrganizationId $originOrganizationId -FacilityId $originFacilityId `
  -IdempotencyKey "demo-v2-referral-create" -Body $referralBody
Invoke-DemoApi -Method Post -Path "/api/v1/referrals/$($referral.id)/submission" -Token $seederToken `
  -Scope "FACILITY" -OrganizationId $originOrganizationId -FacilityId $originFacilityId `
  -IdempotencyKey "demo-v2-referral-submit" -Body @{ expectedVersion = 1 } | Out-Null

$recommendation = Invoke-DemoApi -Method Post -Path "/api/v1/recommendations" -Token $seederToken `
  -Scope "FACILITY" -OrganizationId $originOrganizationId -FacilityId $originFacilityId `
  -IdempotencyKey "demo-v2-recommendation" -Body @{ referralId = [string]$referral.id; revisionNumber = 1 }
$eligibleCandidate = @($recommendation.candidates | Where-Object { $_.status -eq "RANKED_ELIGIBLE" }) | Select-Object -First 1
if (-not $eligibleCandidate) { Stop-Safely "The Demo Matching policy produced no eligible synthetic receiving facility. Seed was not marked complete." }
$selectedFacilityId = [string]$eligibleCandidate.facility.identifier

$journey = Invoke-DemoApi -Method Post -Path "/api/v1/transfer-journeys" -Token $seederToken `
  -Scope "FACILITY" -OrganizationId $originOrganizationId -FacilityId $originFacilityId `
  -IdempotencyKey "demo-v2-transfer-journey" -Body @{
    referralId = [string]$referral.id
    referralRevision = 1
    recommendationId = [string]$recommendation.id
    candidateFacilityId = $selectedFacilityId
    transportRequired = $true
  }
$attemptId = [string]$journey.requestAttemptId
$receivingHeaders = @{ "X-AyuVahak-Execution-Scope" = "FACILITY" }
Invoke-DemoApi -Method Post -Path "/api/v1/transfer-request-attempts/$attemptId/clinical-suitability-assessments" `
  -Token $seederToken -Scope "FACILITY" -OrganizationId $receivingOrganizationId -FacilityId $selectedFacilityId `
  -IdempotencyKey "demo-v2-clinical-assessment" -Body @{
    expectedVersion = 1
    outcome = "SUPPORTS"
    reasonCode = "SYNTHETIC_DEMO_REVIEW_COMPLETE"
    occurredAt = [string]$seedState.observedAt
  } | Out-Null
Invoke-DemoApi -Method Post -Path "/api/v1/transfer-request-attempts/$attemptId/operational-readiness-assessments" `
  -Token $seederToken -Scope "FACILITY" -OrganizationId $receivingOrganizationId -FacilityId $selectedFacilityId `
  -IdempotencyKey "demo-v2-readiness-assessment" -Body @{
    expectedVersion = 2
    outcome = "SUPPORTS"
    reasonCode = "SYNTHETIC_DEMO_READINESS_CONFIRMED"
    occurredAt = [string]$seedState.observedAt
  } | Out-Null
$acceptance = Invoke-DemoApi -Method Post -Path "/api/v1/transfer-request-attempts/$attemptId/acceptance" `
  -Token $seederToken -Scope "FACILITY" -OrganizationId $receivingOrganizationId -FacilityId $selectedFacilityId `
  -IdempotencyKey "demo-v2-transfer-acceptance" -Body @{
    expectedVersion = 3
    occurredAt = [string]$seedState.observedAt
  }
$transferId = [string]$acceptance.transferId
$transport = Invoke-DemoApi -Method Post -Path "/api/v1/transfers/$transferId/transport-request" -Token $seederToken `
  -Scope "FACILITY" -OrganizationId $originOrganizationId -FacilityId $originFacilityId `
  -IdempotencyKey "demo-v2-transport-request" -Body @{}

$definition = @{
  key = [string]$manifest.analytics.indicatorKey
  version = 1
  description = [string]$manifest.analytics.description
  formula = [string]$manifest.analytics.formula
  reportingTimeZone = [string]$manifest.analytics.reportingTimeZone
  sourceMeasures = @(@{ owner = [string]$manifest.analytics.measureOwner; key = [string]$manifest.analytics.measureKey; attribution = [string]$manifest.analytics.attribution })
  zeroAllowed = $true
  denominatorMeasures = @()
  governance = @{
    ownerApprovedPurpose = "Synthetic local demonstration only"
    effectiveFrom = "2026-01-01T00:00:00Z"
    reportingCalendar = "MONTHLY"
    allowedDimensions = @("ORGANIZATION")
    completenessRequirement = "ALL_REQUIRED_SOURCES_COMPLETE_FOR_NUMERIC_VALUE"
    sensitivity = "LOW_SENSITIVITY_AGGREGATE"
  }
}
Invoke-DemoApi -Method Post -Path "/api/v1/analytics/indicator-definitions" -Token $seederToken `
  -Scope "PLATFORM" -IdempotencyKey "demo-v2-analytics-definition" -Body $definition | Out-Null
$periodStart = [DateTime]::ParseExact([string]$seedState.periodStart, "yyyy-MM-dd", [Globalization.CultureInfo]::InvariantCulture)
$periodEnd = [DateTime]::ParseExact([string]$seedState.periodEnd, "yyyy-MM-dd", [Globalization.CultureInfo]::InvariantCulture)
$cutoff = [string]$seedState.referralSourceCutoff
$computation = Invoke-DemoApi -Method Post -Path "/api/v1/analytics/computation-operations" -Token $serviceToken `
  -Scope "ORGANIZATION" -OrganizationId $originOrganizationId -IdempotencyKey $null -Body @{
    computationRequestId = [string]$seedState.computationRequestId
    indicatorKey = [string]$manifest.analytics.indicatorKey
    indicatorVersion = 1
    startInclusive = $periodStart.ToString("yyyy-MM-dd")
    endExclusive = $periodEnd.ToString("yyyy-MM-dd")
    referralSourceCutoff = $cutoff
  }
$snapshotFound = $false
$snapshotDeadline = (Get-Date).AddMinutes(2)
while ((Get-Date) -lt $snapshotDeadline -and -not $snapshotFound) {
  $snapshotPage = Invoke-DemoApi -Method Get -Path "/api/v1/analytics/snapshots?page=0&size=50" `
    -Token $seederToken -Scope "ORGANIZATION" -OrganizationId $originOrganizationId -IdempotencyKey $null
  $snapshotFound = @($snapshotPage.items | Where-Object { $_.indicatorKey -eq $manifest.analytics.indicatorKey -and $_.periodStart -eq $periodStart.ToString("yyyy-MM-dd") }).Count -gt 0
  if (-not $snapshotFound) { Start-Sleep -Seconds 2 }
}
if (-not $snapshotFound) { Stop-Safely "The queued synthetic aggregate did not produce a readable snapshot before the local bound. Seed remains IN_PROGRESS." }

$seedState.state = "COMPLETE"
$seedState.completedAt = [DateTimeOffset]::UtcNow.ToString("O")
$seedState.resourceIds = @{
  originOrganization = $originOrganizationId
  receivingOrganization = $receivingOrganizationId
  originFacility = $originFacilityId
  receivingFacility = $receivingFacilityId
  referral = [string]$referral.id
  recommendation = [string]$recommendation.id
  transferJourney = [string]$journey.journeyId
  transfer = $transferId
  transport = [string]$transport.transportRequestId
  analyticsOperation = [string]$computation.operationId
}
$seedState.exportState = [string]$manifest.export.state
$seedState.federationState = [string]$manifest.federation.state
Save-SeedState $seedState
Write-Host "Synthetic Demo v2 seed completed through owner APIs. The local UI identity is synthetic.demo-seeder. No federation peer or export target was configured or contacted."
