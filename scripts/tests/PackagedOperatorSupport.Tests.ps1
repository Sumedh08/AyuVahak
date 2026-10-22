Describe "Packaged external operator-support profile boundary" {
  It "keeps provider governance and secret references available in the downloadable Compose profile" {
    $repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
    $compose = Get-Content -LiteralPath (Join-Path $repositoryRoot "distribution\local\compose.yaml") -Raw
    $environmentExample = Get-Content -LiteralPath (Join-Path $repositoryRoot "distribution\local\.env.example") -Raw
    $profileKeys = @(
      "AYUVAHAK_OPERATOR_SUPPORT_ENABLED",
      "AYUVAHAK_OPERATOR_SUPPORT_PROVIDER",
      "AYUVAHAK_OPERATOR_SUPPORT_ENDPOINT",
      "AYUVAHAK_OPERATOR_SUPPORT_ALLOWED_HOSTS",
      "AYUVAHAK_OPERATOR_SUPPORT_MODEL",
      "AYUVAHAK_OPERATOR_SUPPORT_SECRET_REFERENCE",
      "AYUVAHAK_OPERATOR_SUPPORT_USE_CASE",
      "AYUVAHAK_OPERATOR_SUPPORT_ALLOWED_DATA_CLASS",
      "AYUVAHAK_OPERATOR_SUPPORT_RETENTION_DISPOSITION",
      "AYUVAHAK_OPERATOR_SUPPORT_TRAINING_USE",
      "AYUVAHAK_OPERATOR_SUPPORT_PROCESSING_JURISDICTION",
      "AYUVAHAK_OPERATOR_SUPPORT_MONTHLY_SPEND_CAP",
      "AYUVAHAK_OPERATOR_SUPPORT_MONTHLY_SPEND_CAP_CURRENCY",
      "AYUVAHAK_OPERATOR_SUPPORT_SPEND_CAP_ENFORCED",
      "AYUVAHAK_OPERATOR_SUPPORT_SPEND_CAP_CONTROL_REFERENCE",
      "AYUVAHAK_OPERATOR_SUPPORT_API_KEY"
    )

    foreach ($key in $profileKeys) {
      $compose.Contains("$($key):") | Should Be $true
      @($environmentExample -split '\r?\n' | Where-Object { $_ -match ('^' + [regex]::Escape($key) + '=') }).Count | Should Be 1
    }

    $environmentExample | Should Match "(?m)^AYUVAHAK_OPERATOR_SUPPORT_ENABLED=false$"
    $environmentExample | Should Match "(?m)^AYUVAHAK_OPERATOR_SUPPORT_PROVIDER=DETERMINISTIC_LOCAL$"
    $environmentExample | Should Match "(?m)^AYUVAHAK_OPERATOR_SUPPORT_SPEND_CAP_ENFORCED=false$"
  }
}
