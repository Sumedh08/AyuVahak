package org.ayuvahak.platform.operatorsupport.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** One immutable, reviewed excerpt from an explicitly approved documentation section. */
public record ApprovedOperatorSupportSource(
    String sourceId, String path, String section, String contentHash, String excerpt) {

  public ApprovedOperatorSupportSource {
    requireToken(sourceId, "source id", 80);
    if (path == null || !path.matches("docs/[A-Za-z0-9._/-]{1,240}") || path.contains("..")) {
      throw new IllegalArgumentException("source path must be a repository documentation path");
    }
    requireBoundedText(section, "source section", 160);
    if (contentHash == null || !contentHash.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("source content hash must be a SHA-256 digest");
    }
    requireBoundedText(excerpt, "approved excerpt", 1800);
  }

  public static ApprovedOperatorSupportSource fromExcerpt(
      String sourceId, String path, String section, String excerpt) {
    return new ApprovedOperatorSupportSource(sourceId, path, section, sha256(excerpt), excerpt);
  }

  private static String sha256(String value) {
    Objects.requireNonNull(value, "excerpt");
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }

  private static void requireToken(String value, String name, int maximumLength) {
    if (value == null || !value.matches("[A-Z][A-Z0-9_-]{0," + (maximumLength - 1) + "}")) {
      throw new IllegalArgumentException(name + " must be a bounded stable identifier");
    }
  }

  private static void requireBoundedText(String value, String name, int maximumLength) {
    if (value == null
        || value.isBlank()
        || value.length() > maximumLength
        || value.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(name + " must be nonblank bounded text");
    }
  }

  @Override
  public String toString() {
    return "ApprovedOperatorSupportSource[sourceId="
        + sourceId
        + ", path="
        + path
        + ", section="
        + section
        + ", contentHash="
        + contentHash
        + ", excerpt=redacted]";
  }
}
