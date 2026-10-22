Set-StrictMode -Version Latest

function ConvertTo-StableDemoSeedTimestamp {
  param([Parameter(Mandatory = $true)][object] $Value)

  if ($Value -is [DateTimeOffset]) {
    $timestamp = $Value
  } elseif ($Value -is [DateTime]) {
    if ($Value.Kind -eq [DateTimeKind]::Unspecified) {
      $timestamp = [DateTimeOffset]::new([DateTime]::SpecifyKind($Value, [DateTimeKind]::Utc))
    } else {
      $timestamp = [DateTimeOffset]::new($Value)
    }
  } else {
    $timestamp = [DateTimeOffset]::Parse(
      [string]$Value,
      [Globalization.CultureInfo]::InvariantCulture,
      [Globalization.DateTimeStyles]::RoundtripKind)
  }

  return $timestamp.ToUniversalTime().ToString("O", [Globalization.CultureInfo]::InvariantCulture)
}

function ConvertTo-StableDemoSourceCutoff {
  param([Parameter(Mandatory = $true)][object] $Value)

  $stableTimestamp = ConvertTo-StableDemoSeedTimestamp $Value
  $timestamp = [DateTimeOffset]::Parse(
    $stableTimestamp,
    [Globalization.CultureInfo]::InvariantCulture,
    [Globalization.DateTimeStyles]::RoundtripKind).ToUniversalTime()
  $microsecondTicks = $timestamp.Ticks - ($timestamp.Ticks % 10)
  $microsecondTimestamp = [DateTimeOffset]::new($microsecondTicks, [TimeSpan]::Zero)
  return $microsecondTimestamp.ToString(
    "yyyy-MM-ddTHH:mm:ss.ffffffZ",
    [Globalization.CultureInfo]::InvariantCulture)
}

function Get-PreviousCompletedReportingMonthStart {
  param([Parameter(Mandatory = $true)][DateTimeOffset] $ReferenceInstant)

  $utc = $ReferenceInstant.ToUniversalTime().UtcDateTime
  $currentMonthStart = [DateTime]::new($utc.Year, $utc.Month, 1, 0, 0, 0, [DateTimeKind]::Utc)
  return $currentMonthStart.AddMonths(-1).ToString("yyyy-MM-dd", [Globalization.CultureInfo]::InvariantCulture)
}

Export-ModuleMember -Function ConvertTo-StableDemoSeedTimestamp, ConvertTo-StableDemoSourceCutoff, Get-PreviousCompletedReportingMonthStart
