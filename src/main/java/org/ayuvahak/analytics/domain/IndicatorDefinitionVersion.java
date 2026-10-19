package org.ayuvahak.analytics.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneId;
import java.time.zone.ZoneRulesProvider;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Immutable, explicitly versioned computation semantics owned by Analytics. */
public record IndicatorDefinitionVersion(
    String key,
    int version,
    String description,
    AggregationFormula formula,
    ZoneId reportingTimeZone,
    List<SourceMeasure> sourceMeasures,
    boolean zeroAllowed,
    List<SourceMeasure> denominatorMeasures,
    IndicatorDefinitionGovernance governance) {

  public IndicatorDefinitionVersion(
      String key,
      int version,
      String description,
      AggregationFormula formula,
      ZoneId reportingTimeZone,
      List<SourceMeasure> sourceMeasures,
      boolean zeroAllowed) {
    this(
        key,
        version,
        description,
        formula,
        reportingTimeZone,
        sourceMeasures,
        zeroAllowed,
        List.of(),
        IndicatorDefinitionGovernance.syntheticFixture(description));
  }

  public IndicatorDefinitionVersion(
      String key,
      int version,
      String description,
      ZoneId reportingTimeZone,
      List<SourceMeasure> sourceMeasures,
      boolean zeroAllowed) {
    this(
        key,
        version,
        description,
        AggregationFormula.SUM,
        reportingTimeZone,
        sourceMeasures,
        zeroAllowed,
        List.of(),
        IndicatorDefinitionGovernance.syntheticFixture(description));
  }

  public IndicatorDefinitionVersion {
    if (key == null || !key.matches("[A-Z0-9][A-Z0-9._-]{0,63}")) {
      throw new IllegalArgumentException("indicator key must be a stable bounded identifier");
    }
    if (version < 1) {
      throw new IllegalArgumentException("indicator version must be positive");
    }
    if (description == null || description.isBlank() || description.length() > 256) {
      throw new IllegalArgumentException("indicator description must be bounded and non-empty");
    }
    if (description.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("indicator description cannot contain control characters");
    }
    Objects.requireNonNull(formula, "formula");
    Objects.requireNonNull(reportingTimeZone, "reporting time zone");
    if (!ZoneId.getAvailableZoneIds().contains(reportingTimeZone.getId())
        || ZoneRulesProvider.getVersions(reportingTimeZone.getId()).isEmpty()) {
      throw new IllegalArgumentException("reporting time zone must be a versioned IANA zone");
    }
    sourceMeasures = List.copyOf(Objects.requireNonNull(sourceMeasures, "source measures"));
    denominatorMeasures =
        List.copyOf(Objects.requireNonNull(denominatorMeasures, "denominator measures"));
    Objects.requireNonNull(governance, "indicator governance");
    if (sourceMeasures.isEmpty() || sourceMeasures.size() > 12) {
      throw new IllegalArgumentException("an indicator requires between one and twelve measures");
    }
    if (sourceMeasures.stream().distinct().count() != sourceMeasures.size()) {
      throw new IllegalArgumentException("indicator source measures must be unique");
    }
    sourceMeasures =
        sourceMeasures.stream()
            .sorted(java.util.Comparator.comparing(SourceMeasure::stableKey))
            .toList();
    if (denominatorMeasures.stream().distinct().count() != denominatorMeasures.size()) {
      throw new IllegalArgumentException("indicator denominator measures must be unique");
    }
    denominatorMeasures =
        denominatorMeasures.stream()
            .sorted(java.util.Comparator.comparing(SourceMeasure::stableKey))
            .toList();
    if (sourceMeasures.size() + denominatorMeasures.size() > 12) {
      throw new IllegalArgumentException("indicator measure count exceeds the bound of twelve");
    }
    if (sourceMeasures.stream().anyMatch(denominatorMeasures::contains)) {
      throw new IllegalArgumentException("numerator and denominator measures must be disjoint");
    }
    if (formula == AggregationFormula.SUM && !denominatorMeasures.isEmpty()) {
      throw new IllegalArgumentException("SUM formula cannot define denominator measures");
    }
    if (formula == AggregationFormula.RATIO_6DP_HALF_EVEN && denominatorMeasures.isEmpty()) {
      throw new IllegalArgumentException("ratio formula requires denominator measures");
    }
    if (java.util.stream.Stream.concat(sourceMeasures.stream(), denominatorMeasures.stream())
            .anyMatch(measure -> measure.attribution() != SourceMeasure.Attribution.NONE)
        && !governance
            .allowedDimensions()
            .contains(IndicatorDefinitionGovernance.ReportingDimension.SOURCE_ATTRIBUTION)) {
      throw new IllegalArgumentException(
          "source attribution measures require an explicitly allowed bounded dimension");
    }
    long transferAttributionCount =
        java.util.stream.Stream.concat(sourceMeasures.stream(), denominatorMeasures.stream())
            .filter(measure -> measure.owner() == SourceMeasure.Owner.TRANSFER)
            .map(SourceMeasure::attribution)
            .distinct()
            .count();
    if (transferAttributionCount > 1) {
      throw new IllegalArgumentException(
          "a scalar indicator must use one Transfer attribution; origin and destination measures require separately defined outputs");
    }
  }

  /** The bounded owner measures summed as the numerator, or as the SUM result. */
  public List<SourceMeasure> numeratorMeasures() {
    return sourceMeasures;
  }

  public String fingerprint() {
    String canonical =
        key
            + "\n"
            + version
            + "\n"
            + description
            + "\n"
            + formula.name()
            + "\n"
            + reportingTimeZone.getId()
            + "\n"
            + zeroAllowed
            + "\n"
            + denominatorMeasures.stream()
                .map(SourceMeasure::stableKey)
                .reduce("", (a, b) -> a + "\n" + b)
            + "\n"
            + governanceCanonical()
            + "\n"
            + sourceMeasures.stream()
                .map(SourceMeasure::stableKey)
                .reduce("", (a, b) -> a + "\n" + b);
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
    }
  }

  private String governanceCanonical() {
    return String.join(
        "\n",
        governance.ownerApprovedPurpose(),
        governance.effectiveFrom().toString(),
        governance.effectiveUntil().map(Object::toString).orElse("OPEN"),
        governance.reportingCalendar().name(),
        governance.allowedDimensions().stream()
            .map(Enum::name)
            .sorted()
            .reduce("", (a, b) -> a + "," + b),
        governance.completenessRequirement().name(),
        governance.maximumSourceAge().map(Object::toString).orElse("OWNER_REPORTED_FRESHNESS"),
        governance.sensitivity().name(),
        governance.requiredDisclosurePolicyKey().orElse("MAPPING_POLICY_REQUIRED"),
        governance
            .authorityDefinition()
            .map(value -> value.authorityIdentifier() + "@" + value.definitionVersion())
            .orElse("NO_EXTERNAL_AUTHORITY_DEFINITION"));
  }
}
