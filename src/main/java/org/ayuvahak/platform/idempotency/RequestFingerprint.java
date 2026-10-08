package org.ayuvahak.platform.idempotency;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** SHA-256 over canonical UTF-8 JSON for validated, normalized command fields. */
public final class RequestFingerprint {

  private static final ObjectMapper STRING_ENCODER = new ObjectMapper();

  private final String value;

  private RequestFingerprint(String value) {
    this.value = value;
  }

  /**
   * Canonicalizes object-field order recursively, preserves array order, and normalizes numbers.
   * Callers must supply validated command fields after domain-appropriate normalization; request
   * metadata such as correlation identifiers must be excluded by the caller.
   */
  public static RequestFingerprint from(JsonNode normalizedCommand) {
    Objects.requireNonNull(normalizedCommand, "normalizedCommand");
    if (!normalizedCommand.isObject()) {
      throw new IllegalArgumentException("normalized command must be a JSON object");
    }
    return new RequestFingerprint(
        IdempotencyKeyDigest.sha256(
            canonicalJson(normalizedCommand).getBytes(StandardCharsets.UTF_8)));
  }

  /** Restore a fingerprint from a command owner's persisted hexadecimal digest. */
  public static RequestFingerprint fromDigest(String digest) {
    if (digest == null || !digest.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("request fingerprint must be a lowercase SHA-256 digest");
    }
    return new RequestFingerprint(digest);
  }

  public String value() {
    return value;
  }

  private static String canonicalJson(JsonNode node) {
    if (node.isObject()) {
      Map<String, JsonNode> sorted = new TreeMap<>();
      node.properties().forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
      StringBuilder output = new StringBuilder("{");
      boolean first = true;
      for (var field : sorted.entrySet()) {
        if (!first) {
          output.append(',');
        }
        first = false;
        output
            .append(encodeString(field.getKey()))
            .append(':')
            .append(canonicalJson(field.getValue()));
      }
      return output.append('}').toString();
    }
    if (node.isArray()) {
      StringBuilder output = new StringBuilder("[");
      for (int index = 0; index < node.size(); index++) {
        if (index > 0) {
          output.append(',');
        }
        output.append(canonicalJson(node.get(index)));
      }
      return output.append(']').toString();
    }
    if (node.isTextual()) {
      return encodeString(node.textValue());
    }
    if (node.isBoolean()) {
      return Boolean.toString(node.booleanValue());
    }
    if (node.isNull()) {
      return "null";
    }
    if (node.isIntegralNumber()) {
      BigInteger integer = node.bigIntegerValue();
      return integer.toString();
    }
    if (node.isNumber()) {
      double value = node.doubleValue();
      if (!Double.isFinite(value)) {
        throw new IllegalArgumentException("normalized command must contain finite JSON numbers");
      }
      BigDecimal decimal = node.decimalValue().stripTrailingZeros();
      return decimal.signum() == 0 ? "0" : decimal.toPlainString();
    }
    throw new IllegalArgumentException("normalized command must contain only JSON values");
  }

  private static String encodeString(String value) {
    try {
      return STRING_ENCODER.writeValueAsString(value);
    } catch (tools.jackson.core.JacksonException exception) {
      throw new IllegalArgumentException(
          "normalized command contains an invalid JSON string", exception);
    }
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof RequestFingerprint that && value.equals(that.value);
  }

  @Override
  public int hashCode() {
    return value.hashCode();
  }

  @Override
  public String toString() {
    return "RequestFingerprint[redacted]";
  }
}
