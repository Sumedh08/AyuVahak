package org.ayuvahak.referral.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Payload-free, Referral-owned retention actions needed after restoring an older backup. */
public record ReferralRetentionManifest(
    UUID restoreId,
    Instant backupCompletedAt,
    Instant generatedAt,
    List<HoldSnapshot> holds,
    List<IdentifierRestrictionSnapshot> identifierRestrictions) {

  public ReferralRetentionManifest {
    Objects.requireNonNull(restoreId, "restore id");
    Objects.requireNonNull(backupCompletedAt, "backup completion time");
    Objects.requireNonNull(generatedAt, "manifest generation time");
    holds = List.copyOf(Objects.requireNonNull(holds, "retention holds"));
    identifierRestrictions =
        List.copyOf(Objects.requireNonNull(identifierRestrictions, "identifier restrictions"));
    if (!generatedAt.isAfter(backupCompletedAt)) {
      throw new IllegalArgumentException("manifest must be generated after the backup checkpoint");
    }
  }

  public record HoldSnapshot(
      UUID holdId,
      UUID referralId,
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
      Objects.requireNonNull(referralId, "Referral id");
      Objects.requireNonNull(purposeCode, "purpose");
      Objects.requireNonNull(reviewBy, "review time");
      Objects.requireNonNull(createdAt, "created time");
      Objects.requireNonNull(createdByKind, "creator kind");
      Objects.requireNonNull(createdByIssuer, "creator issuer");
      Objects.requireNonNull(createdByIdentifierType, "creator identifier type");
      Objects.requireNonNull(createdByIdentifier, "creator identifier");
      releasedAt = Objects.requireNonNull(releasedAt, "release time");
      releaseReasonCode = Objects.requireNonNull(releaseReasonCode, "release reason");
      releasedByKind = Objects.requireNonNull(releasedByKind, "releaser kind");
      releasedByIssuer = Objects.requireNonNull(releasedByIssuer, "releaser issuer");
      releasedByIdentifierType = Objects.requireNonNull(releasedByIdentifierType, "releaser type");
      releasedByIdentifier = Objects.requireNonNull(releasedByIdentifier, "releaser identifier");
    }
  }

  public record IdentifierRestrictionSnapshot(
      UUID referralId,
      UUID restrictionId,
      String reasonCode,
      Instant restrictedAt,
      String restrictedByKind,
      String restrictedByIssuer,
      String restrictedByIdentifierType,
      String restrictedByIdentifier) {
    public IdentifierRestrictionSnapshot {
      Objects.requireNonNull(referralId, "Referral id");
      Objects.requireNonNull(restrictionId, "restriction id");
      Objects.requireNonNull(reasonCode, "restriction reason");
      Objects.requireNonNull(restrictedAt, "restriction time");
      Objects.requireNonNull(restrictedByKind, "actor kind");
      Objects.requireNonNull(restrictedByIssuer, "actor issuer");
      Objects.requireNonNull(restrictedByIdentifierType, "actor identifier type");
      Objects.requireNonNull(restrictedByIdentifier, "actor identifier");
    }
  }
}
