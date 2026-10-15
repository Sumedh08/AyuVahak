package org.ayuvahak.referral.application;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Bounded source attribution and time evidence; it contains no patient or endpoint identifier. */
public record PatientContextSourceEvidence(
    String sourceKey,
    Optional<String> sourceVersion,
    Optional<Instant> sourceReportedAt,
    Instant receivedAt,
    String projectionVersion,
    Freshness freshness) {

  public PatientContextSourceEvidence {
    if (sourceKey == null || !sourceKey.matches("[A-Z][A-Z0-9_.-]{0,63}")) {
      throw new IllegalArgumentException("source key is invalid");
    }
    sourceVersion = Objects.requireNonNull(sourceVersion, "source version");
    sourceVersion.ifPresent(
        value -> {
          if (value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException("source version is outside the supported bounds");
          }
        });
    sourceReportedAt = Objects.requireNonNull(sourceReportedAt, "source-reported time");
    Objects.requireNonNull(receivedAt, "received time");
    if (projectionVersion == null || !projectionVersion.matches("[A-Z][A-Z0-9_.-]{0,63}")) {
      throw new IllegalArgumentException("projection version is invalid");
    }
    Objects.requireNonNull(freshness, "source freshness");
  }

  public enum Freshness {
    CURRENT,
    STALE,
    UNKNOWN
  }

  @Override
  public String toString() {
    return "PatientContextSourceEvidence[sourceKey="
        + sourceKey
        + ", projectionVersion="
        + projectionVersion
        + ", freshness="
        + freshness
        + ", identifiers=redacted]";
  }
}
