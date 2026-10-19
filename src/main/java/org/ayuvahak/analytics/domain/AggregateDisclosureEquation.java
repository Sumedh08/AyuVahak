package org.ayuvahak.analytics.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.ayuvahak.platform.core.ExternalReference;

/** Stable local equation linking disclosure outputs to their owner-produced source cells. */
public record AggregateDisclosureEquation(
    Map<String, Long> cellValues,
    Map<String, Long> totalTerms,
    long total,
    long equationResult,
    boolean appliesMinimumCellCount) {

  private static final long RATIO_SCALE = 1_000_000L;

  public AggregateDisclosureEquation {
    cellValues = Map.copyOf(cellValues);
    totalTerms = Map.copyOf(totalTerms);
    if (cellValues.isEmpty()
        || totalTerms.isEmpty()
        || !cellValues.keySet().containsAll(totalTerms.keySet())
        || total < 0
        || equationResult < 0) {
      throw new IllegalArgumentException("aggregate disclosure equation is invalid");
    }
  }

  public DisclosureOutput output(String outputKey) {
    return new DisclosureOutput(
        outputKey, total, totalTerms, equationResult, appliesMinimumCellCount);
  }

  public static AggregateDisclosureEquation from(
      AggregateSnapshot snapshot, IndicatorDefinitionVersion definition) {
    Objects.requireNonNull(snapshot, "aggregate snapshot");
    Objects.requireNonNull(definition, "indicator definition");
    java.util.List<SourceMeasure> requiredMeasures =
        java.util.stream.Stream.concat(
                definition.sourceMeasures().stream(), definition.denominatorMeasures().stream())
            .sorted(java.util.Comparator.comparing(SourceMeasure::stableKey))
            .toList();
    if (requiredMeasures.size() != snapshot.inputs().size()
        || !requiredMeasures.equals(
            snapshot.inputs().stream().map(AggregateInput::measure).toList())
        || snapshot.numericValue().isEmpty()) {
      throw new IllegalArgumentException(
          "snapshot is not eligible for source-cell disclosure analysis");
    }

    Map<String, Long> values = new LinkedHashMap<>();
    Map<SourceMeasure, String> keys = new LinkedHashMap<>();
    long numerator = 0;
    long denominator = 0;
    for (AggregateInput input : snapshot.inputs()) {
      if (input.completeness() != AggregateCompleteness.COMPLETE || input.value().isEmpty()) {
        throw new IllegalArgumentException(
            "incomplete source inputs cannot enter disclosure analysis");
      }
      String cellKey = sourceCellKey(input, snapshot);
      long value = input.value().orElseThrow();
      if (values.putIfAbsent(cellKey, value) != null) {
        throw new IllegalArgumentException("source measure cells are not unique");
      }
      keys.put(input.measure(), cellKey);
      if (definition.sourceMeasures().contains(input.measure())) {
        numerator = Math.addExact(numerator, value);
      } else if (definition.denominatorMeasures().contains(input.measure())) {
        denominator = Math.addExact(denominator, value);
      } else {
        throw new IllegalArgumentException("source measure is not declared by the definition");
      }
    }
    if (definition.formula() == AggregationFormula.SUM) {
      if (snapshot.value().isEmpty() || numerator != snapshot.value().orElseThrow()) {
        throw new IllegalArgumentException("snapshot result does not match its source inputs");
      }
      Map<String, Long> terms = new LinkedHashMap<>();
      keys.forEach(
          (measure, key) -> {
            if (definition.sourceMeasures().contains(measure)) {
              terms.put(key, 1L);
            }
          });
      return new AggregateDisclosureEquation(values, terms, numerator, numerator, true);
    }
    if (definition.formula() != AggregationFormula.RATIO_6DP_HALF_EVEN || denominator == 0) {
      throw new IllegalArgumentException("ratio snapshot is not eligible for disclosure analysis");
    }
    java.math.BigDecimal reported = snapshot.numericValue().orElseThrow();
    long scaledRatio;
    try {
      scaledRatio = reported.movePointRight(6).longValueExact();
    } catch (ArithmeticException invalidScale) {
      throw new IllegalArgumentException(
          "ratio precision is outside disclosure bounds", invalidScale);
    }
    java.math.BigDecimal recomputed =
        java.math.BigDecimal.valueOf(numerator)
            .divide(java.math.BigDecimal.valueOf(denominator), 6, java.math.RoundingMode.HALF_EVEN);
    if (recomputed.compareTo(reported) != 0) {
      throw new IllegalArgumentException("ratio snapshot result does not match its source inputs");
    }
    java.math.BigInteger exactNumerator = java.math.BigInteger.valueOf(numerator);
    java.math.BigInteger exactDenominator = java.math.BigInteger.valueOf(denominator);
    java.math.BigInteger gcd = exactNumerator.gcd(exactDenominator);
    long numeratorCoefficient = exactDenominator.divide(gcd).longValueExact();
    long denominatorCoefficient = exactNumerator.divide(gcd).longValueExact();
    if (numeratorCoefficient > 1_000_000L || denominatorCoefficient > 1_000_000L) {
      throw new IllegalArgumentException("ratio relation exceeds disclosure coefficient bounds");
    }
    Map<String, Long> terms = new LinkedHashMap<>();
    keys.forEach(
        (measure, key) -> {
          if (definition.sourceMeasures().contains(measure)) {
            terms.put(key, numeratorCoefficient);
          } else if (denominatorCoefficient != 0) {
            terms.put(key, -denominatorCoefficient);
          }
        });
    java.math.BigInteger relation = java.math.BigInteger.ZERO;
    for (Map.Entry<String, Long> term : terms.entrySet()) {
      relation =
          relation.add(
              java.math.BigInteger.valueOf(values.get(term.getKey()))
                  .multiply(java.math.BigInteger.valueOf(term.getValue())));
    }
    if (relation.signum() != 0) {
      throw new IllegalArgumentException("ratio snapshot does not match its disclosure equation");
    }
    return new AggregateDisclosureEquation(values, terms, scaledRatio, 0, false);
  }

  private static String sourceCellKey(AggregateInput input, AggregateSnapshot snapshot) {
    java.util.ArrayList<String> parts =
        new java.util.ArrayList<>(
            java.util.List.of(
                "ANALYTICS_SOURCE_CELL_V1",
                input.measure().stableKey(),
                snapshot.organization().issuer().value(),
                snapshot.organization().resourceType(),
                snapshot.organization().value()));
    // Preserve historical org-level lineage keys while namespacing facility cells separately.
    snapshot.facility().map(ExternalReference::value).ifPresent(parts::add);
    parts.add(snapshot.period().startInclusiveUtc().toString());
    parts.add(snapshot.period().endExclusiveUtc().toString());
    String canonical = String.join("\n", parts);
    try {
      String digest =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(canonical.getBytes(StandardCharsets.UTF_8)));
      return "SOURCE_" + digest.substring(0, 56).toUpperCase(Locale.ROOT);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
    }
  }
}
