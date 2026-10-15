package org.ayuvahak.platform.exchange.domain;

import java.util.Locale;

/** Stable, non-secret logical identity for an administrator-configured exchange partner. */
public record PartnerKey(String value) {

  public PartnerKey {
    if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_-]{1,62}")) {
      throw new IllegalArgumentException("partner key must be a bounded identifier");
    }
    value = value.toUpperCase(Locale.ROOT);
  }

  @Override
  public String toString() {
    return value;
  }
}
