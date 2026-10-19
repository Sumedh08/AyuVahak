package org.ayuvahak.analytics.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Reporting scope metadata used to conservatively relate releases across geographic levels. */
public record DisclosureScope(
    String indicatorKey,
    int indicatorVersion,
    String organizationIssuer,
    UUID organizationId,
    Optional<UUID> facilityId,
    Instant periodStartInclusive,
    Instant periodEndExclusive) {

  public DisclosureScope {
    if (indicatorKey == null || !indicatorKey.matches("[A-Z0-9][A-Z0-9._-]{0,63}")) {
      throw new IllegalArgumentException("disclosure indicator key is invalid");
    }
    if (indicatorVersion < 1) {
      throw new IllegalArgumentException("disclosure indicator version is invalid");
    }
    if (organizationIssuer == null || organizationIssuer.isBlank()) {
      throw new IllegalArgumentException("disclosure organization issuer is required");
    }
    Objects.requireNonNull(organizationId, "disclosure organization id");
    facilityId = Objects.requireNonNull(facilityId, "disclosure facility id");
    Objects.requireNonNull(periodStartInclusive, "disclosure period start");
    Objects.requireNonNull(periodEndExclusive, "disclosure period end");
    if (!periodStartInclusive.isBefore(periodEndExclusive)) {
      throw new IllegalArgumentException("disclosure period must be a non-empty interval");
    }
  }

  public static DisclosureScope from(AggregateSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "aggregate snapshot");
    return new DisclosureScope(
        snapshot.indicatorKey(),
        snapshot.indicatorVersion(),
        snapshot.organization().issuer().value(),
        UUID.fromString(snapshot.organization().value()),
        snapshot.facility().map(reference -> UUID.fromString(reference.value())),
        snapshot.period().startInclusiveUtc(),
        snapshot.period().endExclusiveUtc());
  }

  public boolean overlaps(DisclosureScope other) {
    return indicatorKey.equals(other.indicatorKey)
        && indicatorVersion == other.indicatorVersion
        && organizationIssuer.equals(other.organizationIssuer)
        && organizationId.equals(other.organizationId)
        && !facilityId.equals(other.facilityId)
        && periodStartInclusive.isBefore(other.periodEndExclusive)
        && periodEndExclusive.isAfter(other.periodStartInclusive);
  }

  @Override
  public String toString() {
    return "DisclosureScope[indicatorKey="
        + indicatorKey
        + ", indicatorVersion="
        + indicatorVersion
        + ", organization=redacted, facility=redacted, period=redacted]";
  }
}
