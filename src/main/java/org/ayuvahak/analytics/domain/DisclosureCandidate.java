package org.ayuvahak.analytics.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/** Bounded candidate release set used by both outbound disclosure channels. */
public record DisclosureCandidate(
    String policyKey,
    int expectedPolicyVersion,
    UUID operationId,
    Map<String, Long> cellValues,
    List<DisclosureOutput> outputs,
    String fingerprint) {

  private static final int MAX_CELLS = 512;
  private static final int MAX_OUTPUTS = 64;

  public DisclosureCandidate(
      String policyKey,
      int expectedPolicyVersion,
      UUID operationId,
      Map<String, Long> cellValues,
      List<DisclosureOutput> outputs) {
    this(policyKey, expectedPolicyVersion, operationId, cellValues, outputs, "pending");
  }

  public DisclosureCandidate {
    if (policyKey == null || !policyKey.matches("[A-Z0-9][A-Z0-9._:-]{0,63}")) {
      throw new IllegalArgumentException("disclosure policy key is invalid");
    }
    if (expectedPolicyVersion < 1) {
      throw new IllegalArgumentException("expected disclosure policy version is invalid");
    }
    Objects.requireNonNull(operationId, "disclosure operation id");
    Objects.requireNonNull(cellValues, "disclosure cell values");
    Objects.requireNonNull(outputs, "disclosure outputs");
    if (cellValues.isEmpty()
        || cellValues.size() > MAX_CELLS
        || outputs.isEmpty()
        || outputs.size() > MAX_OUTPUTS) {
      throw new IllegalArgumentException("disclosure candidate is outside its safe bounds");
    }
    TreeMap<String, Long> stableCells = new TreeMap<>();
    cellValues.forEach(
        (key, value) -> {
          DisclosureOutput.requireKey(key, "cell key");
          if (value == null || value < 0) {
            throw new IllegalArgumentException("disclosure cell values cannot be negative");
          }
          stableCells.put(key, value);
        });
    cellValues = Map.copyOf(stableCells);
    outputs = outputs.stream().sorted(Comparator.comparing(DisclosureOutput::outputKey)).toList();
    if (outputs.stream().map(DisclosureOutput::outputKey).distinct().count() != outputs.size()) {
      throw new IllegalArgumentException("disclosure output keys must be unique in a release set");
    }
    for (DisclosureOutput output : outputs) {
      long computed = 0;
      try {
        for (Map.Entry<String, Long> term : output.cellCoefficients().entrySet()) {
          Long cellValue = cellValues.get(term.getKey());
          if (cellValue == null) {
            throw new IllegalArgumentException("disclosure output refers to an unknown cell");
          }
          computed = Math.addExact(computed, Math.multiplyExact(cellValue, term.getValue()));
        }
      } catch (ArithmeticException overflow) {
        throw new IllegalArgumentException(
            "disclosure output arithmetic exceeded safe bounds", overflow);
      }
      if (computed != output.value()) {
        throw new IllegalArgumentException(
            "disclosure output does not match its approved cell terms");
      }
    }
    String calculated = calculateFingerprint(policyKey, expectedPolicyVersion, cellValues, outputs);
    if (fingerprint == null || fingerprint.equals("pending")) {
      fingerprint = calculated;
    } else if (!fingerprint.equals(calculated)) {
      throw new IllegalArgumentException("disclosure candidate fingerprint is inconsistent");
    }
  }

  private static String calculateFingerprint(
      String policyKey, int version, Map<String, Long> cells, List<DisclosureOutput> outputs) {
    StringBuilder canonical = new StringBuilder(policyKey).append('\n').append(version);
    new TreeMap<>(cells)
        .forEach(
            (key, value) ->
                canonical.append('\n').append("CELL|").append(key).append('|').append(value));
    outputs.forEach(
        output -> {
          canonical
              .append('\n')
              .append("OUTPUT|")
              .append(output.outputKey())
              .append('|')
              .append(output.value());
          new TreeMap<>(output.cellCoefficients())
              .forEach(
                  (key, coefficient) ->
                      canonical
                          .append('\n')
                          .append("TERM|")
                          .append(key)
                          .append('|')
                          .append(coefficient));
        });
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
    }
  }

  @Override
  public String toString() {
    return "DisclosureCandidate[policyKey="
        + policyKey
        + ", policyVersion="
        + expectedPolicyVersion
        + ", outputCount="
        + outputs.size()
        + ", cellCount="
        + cellValues.size()
        + ", fingerprint="
        + fingerprint
        + "]";
  }
}
