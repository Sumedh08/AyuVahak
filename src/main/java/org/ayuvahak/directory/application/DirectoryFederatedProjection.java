package org.ayuvahak.directory.application;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.exchange.domain.PartnerKey;

/** Attributable, bounded remote Directory projection; never a local Directory fact. */
public record DirectoryFederatedProjection(
    DirectoryFederatedFact fact,
    PartnerKey partnerKey,
    int partnerConfigurationVersion,
    String factFingerprint,
    Status status,
    Optional<String> conflictFingerprint,
    Instant observedAt,
    Instant receivedAt,
    Instant validUntil) {

  public DirectoryFederatedProjection {
    Objects.requireNonNull(fact, "fact");
    Objects.requireNonNull(partnerKey, "partnerKey");
    if (partnerConfigurationVersion < 1) {
      throw new IllegalArgumentException("partner configuration version must be positive");
    }
    if (factFingerprint == null || !factFingerprint.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("fact fingerprint must be a SHA-256 digest");
    }
    Objects.requireNonNull(status, "status");
    conflictFingerprint = Objects.requireNonNull(conflictFingerprint, "conflictFingerprint");
    conflictFingerprint.ifPresent(
        value -> {
          if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("conflict fingerprint must be a SHA-256 digest");
          }
        });
    Objects.requireNonNull(observedAt, "observedAt");
    Objects.requireNonNull(receivedAt, "receivedAt");
    Objects.requireNonNull(validUntil, "validUntil");
    if (!validUntil.isAfter(receivedAt)) {
      throw new IllegalArgumentException("projection freshness deadline must follow receipt");
    }
    if ((status == Status.CONFLICT) != conflictFingerprint.isPresent()) {
      throw new IllegalArgumentException("projection conflict status and evidence must agree");
    }
  }

  public DirectoryRemoteResourceReference reference() {
    return fact.reference();
  }

  public Freshness freshnessAt(Instant now) {
    Objects.requireNonNull(now, "now");
    if (status == Status.CONFLICT) {
      return Freshness.CONFLICT;
    }
    return now.isBefore(validUntil) ? Freshness.FRESH : Freshness.STALE;
  }

  @Override
  public String toString() {
    return "DirectoryFederatedProjection[reference="
        + reference()
        + ", partnerKey="
        + partnerKey
        + ", partnerConfigurationVersion="
        + partnerConfigurationVersion
        + ", sourceVersion="
        + fact.sourceVersion()
        + ", status="
        + status
        + ", receivedAt="
        + receivedAt
        + ", validUntil="
        + validUntil
        + "]";
  }

  public enum Status {
    CURRENT,
    CONFLICT
  }

  public enum Freshness {
    FRESH,
    STALE,
    CONFLICT
  }
}
