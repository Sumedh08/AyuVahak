package org.ayuvahak.platform.core;

/** Exact issuer/source namespace for an externally assigned reference. */
public record IssuerId(String value) {

  public IssuerId {
    requireOpaqueText("issuer/source", value);
  }

  private static void requireOpaqueText(String name, String text) {
    if (text == null || text.isBlank() || !text.equals(text.strip()) || containsControl(text)) {
      throw new IllegalArgumentException(
          name + " must be nonblank, trimmed, and contain no control characters");
    }
  }

  private static boolean containsControl(String text) {
    return text.codePoints().anyMatch(Character::isISOControl);
  }
}
