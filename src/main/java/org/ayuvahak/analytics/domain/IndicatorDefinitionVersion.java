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
    boolean zeroAllowed) {

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
        zeroAllowed);
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
}
