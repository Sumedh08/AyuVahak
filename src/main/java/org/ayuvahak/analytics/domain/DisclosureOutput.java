package org.ayuvahak.analytics.domain;

import java.math.BigInteger;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** One proposed aggregate and its approved linear relationship to bounded synthetic cells. */
public record DisclosureOutput(
    String outputKey,
    long value,
    Map<String, Long> cellCoefficients,
    long equationResult,
    boolean appliesMinimumCellCount) {

  private static final Pattern KEY = Pattern.compile("[A-Z0-9][A-Z0-9._:-]{0,127}");
  private static final int MAX_TERMS = 256;
  private static final long MAX_COEFFICIENT = 1_000_000;

  public DisclosureOutput(String outputKey, long value, Map<String, Long> cellCoefficients) {
    this(outputKey, value, cellCoefficients, value, true);
  }

  public DisclosureOutput {
    requireKey(outputKey, "output key");
    Objects.requireNonNull(cellCoefficients, "cell coefficients");
    if (value < 0 || cellCoefficients.isEmpty() || cellCoefficients.size() > MAX_TERMS) {
      throw new IllegalArgumentException("disclosure output is outside its safe bounds");
    }
    cellCoefficients = Map.copyOf(cellCoefficients);
    cellCoefficients.forEach(
        (cellKey, coefficient) -> {
          requireKey(cellKey, "cell key");
          if (coefficient == null
              || coefficient == 0
              || coefficient < -MAX_COEFFICIENT
              || coefficient > MAX_COEFFICIENT) {
            throw new IllegalArgumentException("disclosure output coefficient is invalid");
          }
        });
    if (equationResult < 0) {
      throw new IllegalArgumentException("disclosure equation result cannot be negative");
    }
  }

  public static void requireKey(String key, String label) {
    if (key == null || !KEY.matcher(key).matches()) {
      throw new IllegalArgumentException(label + " must be a bounded stable identifier");
    }
  }

  @Override
  public String toString() {
    return "DisclosureOutput[outputKey="
        + outputKey
        + ", value=redacted, termCount="
        + cellCoefficients.size()
        + "]";
  }
}
