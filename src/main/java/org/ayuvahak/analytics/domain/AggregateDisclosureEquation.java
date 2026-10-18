package org.ayuvahak.analytics.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Stable local equation linking disclosure outputs to their owner-produced source cells. */
public record AggregateDisclosureEquation(
    Map<String, Long> cellValues, Map<String, Long> totalTerms, long total) {

  public AggregateDisclosureEquation {
    cellValues = Map.copyOf(cellValues);
    totalTerms = Map.copyOf(totalTerms);
    if (cellValues.isEmpty() || !cellValues.keySet().equals(totalTerms.keySet()) || total < 0) {
      throw new IllegalArgumentException("aggregate disclosure equation is invalid");
    }
  }

  public DisclosureOutput output(String outputKey) {
    return new DisclosureOutput(outputKey, total, totalTerms);
  }

  public static AggregateDisclosureEquation from(
      AggregateSnapshot snapshot, IndicatorDefinitionVersion definition) {
    Objects.requireNonNull(snapshot, "aggregate snapshot");
    Objects.requireNonNull(definition, "indicator definition");
    if (definition.formula() != AggregationFormula.SUM
        || definition.sourceMeasures().size() != snapshot.inputs().size()
        || !definition
            .sourceMeasures()
            .equals(snapshot.inputs().stream().map(AggregateInput::measure).toList())
        || snapshot.value().isEmpty()) {
      throw new IllegalArgumentException(
          "snapshot is not eligible for source-cell disclosure analysis");
    }

    Map<String, Long> values = new LinkedHashMap<>();
    Map<String, Long> terms = new LinkedHashMap<>();
    long sum = 0;
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
      terms.put(cellKey, 1L);
      sum = Math.addExact(sum, value);
    }
    if (sum != snapshot.value().orElseThrow()) {
      throw new IllegalArgumentException("snapshot result does not match its source inputs");
    }
    return new AggregateDisclosureEquation(values, terms, sum);
  }

  private static String sourceCellKey(AggregateInput input, AggregateSnapshot snapshot) {
    String canonical =
        String.join(
            "\n",
            "ANALYTICS_SOURCE_CELL_V1",
            input.measure().stableKey(),
            snapshot.organization().issuer().value(),
            snapshot.organization().resourceType(),
            snapshot.organization().value(),
            snapshot.period().startInclusiveUtc().toString(),
            snapshot.period().endExclusiveUtc().toString());
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
