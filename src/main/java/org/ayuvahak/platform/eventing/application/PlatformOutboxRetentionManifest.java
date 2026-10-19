package org.ayuvahak.platform.eventing.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Owner-scoped, payload-free action evidence exported alongside a database backup. */
public record PlatformOutboxRetentionManifest(
    UUID restoreId,
    Instant backupCompletedAt,
    Instant generatedAt,
    List<PurgedEvent> purgedEvents,
    List<HoldSnapshot> holds) {

  public PlatformOutboxRetentionManifest {
    Objects.requireNonNull(restoreId, "restore id");
    Objects.requireNonNull(backupCompletedAt, "backup completion time");
    Objects.requireNonNull(generatedAt, "manifest generation time");
    purgedEvents = List.copyOf(Objects.requireNonNull(purgedEvents, "purged events"));
    holds = List.copyOf(Objects.requireNonNull(holds, "retention holds"));
    if (!generatedAt.isAfter(backupCompletedAt)) {
      throw new IllegalArgumentException("manifest must be generated after the backup checkpoint");
    }
  }

  public record PurgedEvent(UUID eventId, int deliveryGeneration, Instant purgedAt) {
    public PurgedEvent {
      Objects.requireNonNull(eventId, "event id");
      Objects.requireNonNull(purgedAt, "purge time");
      if (deliveryGeneration < 1) throw new IllegalArgumentException("delivery generation must be positive");
    }
  }

  public record HoldSnapshot(
      UUID holdId,
      UUID eventId,
      String purposeCode,
      Instant reviewBy,
      Instant createdAt,
      String createdByKind,
      String createdByIssuer,
      String createdByIdentifierType,
      String createdByIdentifier,
      Optional<Instant> releasedAt,
      Optional<String> releaseReasonCode,
      Optional<String> releasedByKind,
      Optional<String> releasedByIssuer,
      Optional<String> releasedByIdentifierType,
      Optional<String> releasedByIdentifier) {
    public HoldSnapshot {
      Objects.requireNonNull(holdId, "hold id");
      Objects.requireNonNull(eventId, "event id");
      Objects.requireNonNull(purposeCode, "purpose code");
      Objects.requireNonNull(reviewBy, "review time");
      Objects.requireNonNull(createdAt, "created time");
      Objects.requireNonNull(createdByKind, "creator kind");
      Objects.requireNonNull(createdByIssuer, "creator issuer");
      Objects.requireNonNull(createdByIdentifierType, "creator identifier type");
      Objects.requireNonNull(createdByIdentifier, "creator identifier");
      releasedAt = Objects.requireNonNull(releasedAt, "released time");
      releaseReasonCode = Objects.requireNonNull(releaseReasonCode, "release reason");
      releasedByKind = Objects.requireNonNull(releasedByKind, "releaser kind");
      releasedByIssuer = Objects.requireNonNull(releasedByIssuer, "releaser issuer");
      releasedByIdentifierType = Objects.requireNonNull(releasedByIdentifierType, "releaser type");
      releasedByIdentifier = Objects.requireNonNull(releasedByIdentifier, "releaser identifier");
    }
  }
}
