package org.ayuvahak.referral.adapter.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.referral.application.ReferralIdentifierRestrictionReason;
import org.ayuvahak.referral.application.ReferralRetentionHoldPurpose;
import org.ayuvahak.referral.application.ReferralRetentionHoldReleaseReason;
import org.ayuvahak.referral.application.ReferralRetentionManifest;
import org.ayuvahak.referral.application.ReferralRetentionStore;
import org.ayuvahak.platform.retention.OwnerRetentionReapplicationStatus;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL adapter for Referral-owned hold and identifier restriction state. */
@Repository
@Lazy
public class JdbcReferralRetentionStore
    implements ReferralRetentionStore, OwnerRetentionReapplicationStatus {

  private final JdbcTemplate jdbc;

  public JdbcReferralRetentionStore(JdbcTemplate jdbc) {
    this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public boolean isIdentifierRestricted(InternalId referralId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM referral.identifier_restriction WHERE referral_id = ?)",
            Boolean.class,
            referralId.value()));
  }

  @Override
  public boolean hasActiveHold(InternalId referralId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM referral.retention_hold "
                + "WHERE referral_id = ? AND released_at IS NULL)",
            Boolean.class,
            referralId.value()));
  }

  @Override
  public boolean createHold(
      UUID holdId,
      InternalId referralId,
      ReferralRetentionHoldPurpose purpose,
      Instant reviewBy,
      ActorReference actor,
      Instant createdAt) {
    return jdbc.update(
            "INSERT INTO referral.retention_hold "
                + "(hold_id, referral_id, purpose_code, review_by, created_at, created_by_kind, "
                + "created_by_issuer, created_by_identifier_type, created_by_identifier) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            holdId,
            referralId.value(),
            purpose.name(),
            Timestamp.from(reviewBy),
            Timestamp.from(createdAt),
            actor.kind().name(),
            actor.identity().issuer().value(),
            actor.identity().resourceType(),
            actor.identity().value())
        == 1;
  }

  @Override
  public boolean releaseHold(
      UUID holdId,
      InternalId referralId,
      ReferralRetentionHoldReleaseReason reason,
      ActorReference actor,
      Instant releasedAt) {
    return jdbc.update(
            "UPDATE referral.retention_hold SET released_at = ?, release_reason_code = ?, "
                + "released_by_kind = ?, released_by_issuer = ?, released_by_identifier_type = ?, "
                + "released_by_identifier = ? WHERE hold_id = ? AND referral_id = ? "
                + "AND released_at IS NULL",
            Timestamp.from(releasedAt),
            reason.name(),
            actor.kind().name(),
            actor.identity().issuer().value(),
            actor.identity().resourceType(),
            actor.identity().value(),
            holdId,
            referralId.value())
        == 1;
  }

  @Override
  public boolean restrictIdentifier(
      UUID restrictionId,
      InternalId referralId,
      ReferralIdentifierRestrictionReason reason,
      ActorReference actor,
      Instant restrictedAt) {
    return jdbc.update(
            "INSERT INTO referral.identifier_restriction "
                + "(referral_id, restriction_id, reason_code, restricted_at, restricted_by_kind, "
                + "restricted_by_issuer, restricted_by_identifier_type, restricted_by_identifier) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (referral_id) DO NOTHING",
            referralId.value(),
            restrictionId,
            reason.name(),
            Timestamp.from(restrictedAt),
            actor.kind().name(),
            actor.identity().issuer().value(),
            actor.identity().resourceType(),
            actor.identity().value())
        == 1;
  }

  @Override
  public ReferralRetentionManifest exportSince(
      UUID restoreId, Instant backupCompletedAt, Instant generatedAt) {
    List<ReferralRetentionManifest.HoldSnapshot> holds =
        jdbc.query(
            "SELECT hold_id, referral_id, purpose_code, review_by, created_at, created_by_kind, "
                + "created_by_issuer, created_by_identifier_type, created_by_identifier, "
                + "released_at, release_reason_code, released_by_kind, released_by_issuer, "
                + "released_by_identifier_type, released_by_identifier "
                + "FROM referral.retention_hold WHERE created_at > ? OR released_at > ? "
                + "ORDER BY created_at, hold_id",
            JdbcReferralRetentionStore::holdSnapshot,
            Timestamp.from(backupCompletedAt),
            Timestamp.from(backupCompletedAt));
    List<ReferralRetentionManifest.IdentifierRestrictionSnapshot> restrictions =
        jdbc.query(
            "SELECT referral_id, restriction_id, reason_code, restricted_at, restricted_by_kind, "
                + "restricted_by_issuer, restricted_by_identifier_type, restricted_by_identifier "
                + "FROM referral.identifier_restriction WHERE restricted_at > ? "
                + "ORDER BY restricted_at, referral_id",
            (row, index) ->
                new ReferralRetentionManifest.IdentifierRestrictionSnapshot(
                    row.getObject("referral_id", UUID.class),
                    row.getObject("restriction_id", UUID.class),
                    row.getString("reason_code"),
                    row.getTimestamp("restricted_at").toInstant(),
                    row.getString("restricted_by_kind"),
                    row.getString("restricted_by_issuer"),
                    row.getString("restricted_by_identifier_type"),
                    row.getString("restricted_by_identifier")),
            Timestamp.from(backupCompletedAt));
    return new ReferralRetentionManifest(
        restoreId, backupCompletedAt, generatedAt, holds, restrictions);
  }

  @Override
  public void reapply(ReferralRetentionManifest manifest, Instant completedAt) {
    for (ReferralRetentionManifest.HoldSnapshot hold : manifest.holds()) {
      mergeHold(hold);
    }
    for (ReferralRetentionManifest.IdentifierRestrictionSnapshot restriction :
        manifest.identifierRestrictions()) {
      mergeRestriction(restriction);
    }
    jdbc.update(
        "INSERT INTO referral.retention_reapplication_receipt (restore_id, completed_at) "
            + "VALUES (?, ?) ON CONFLICT (restore_id) DO UPDATE SET completed_at = EXCLUDED.completed_at",
        manifest.restoreId(),
        Timestamp.from(completedAt));
  }

  @Override
  public boolean hasCompleted(UUID restoreId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM referral.retention_reapplication_receipt "
                + "WHERE restore_id = ?)",
            Boolean.class,
            restoreId));
  }

  @Override
  public String ownerCode() {
    return "referral";
  }

  private void mergeHold(ReferralRetentionManifest.HoldSnapshot hold) {
    jdbc.update(
        "INSERT INTO referral.retention_hold "
            + "(hold_id, referral_id, purpose_code, review_by, created_at, created_by_kind, "
            + "created_by_issuer, created_by_identifier_type, created_by_identifier, released_at, "
            + "release_reason_code, released_by_kind, released_by_issuer, released_by_identifier_type, "
            + "released_by_identifier) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (hold_id) DO NOTHING",
        hold.holdId(),
        hold.referralId(),
        hold.purposeCode(),
        Timestamp.from(hold.reviewBy()),
        Timestamp.from(hold.createdAt()),
        hold.createdByKind(),
        hold.createdByIssuer(),
        hold.createdByIdentifierType(),
        hold.createdByIdentifier(),
        hold.releasedAt().map(Timestamp::from).orElse(null),
        hold.releaseReasonCode().orElse(null),
        hold.releasedByKind().orElse(null),
        hold.releasedByIssuer().orElse(null),
        hold.releasedByIdentifierType().orElse(null),
        hold.releasedByIdentifier().orElse(null));
    if (hold.releasedAt().isPresent()) {
      jdbc.update(
          "UPDATE referral.retention_hold SET released_at = ?, release_reason_code = ?, "
              + "released_by_kind = ?, released_by_issuer = ?, released_by_identifier_type = ?, "
              + "released_by_identifier = ? WHERE hold_id = ? AND referral_id = ? "
              + "AND purpose_code = ? AND created_at = ? AND released_at IS NULL",
          Timestamp.from(hold.releasedAt().orElseThrow()),
          hold.releaseReasonCode().orElseThrow(),
          hold.releasedByKind().orElseThrow(),
          hold.releasedByIssuer().orElseThrow(),
          hold.releasedByIdentifierType().orElseThrow(),
          hold.releasedByIdentifier().orElseThrow(),
          hold.holdId(),
          hold.referralId(),
          hold.purposeCode(),
          Timestamp.from(hold.createdAt()));
    }
    List<ReferralRetentionManifest.HoldSnapshot> actual =
        jdbc.query(
            "SELECT hold_id, referral_id, purpose_code, review_by, created_at, created_by_kind, "
                + "created_by_issuer, created_by_identifier_type, created_by_identifier, "
                + "released_at, release_reason_code, released_by_kind, released_by_issuer, "
                + "released_by_identifier_type, released_by_identifier "
                + "FROM referral.retention_hold WHERE hold_id = ?",
            JdbcReferralRetentionStore::holdSnapshot,
            hold.holdId());
    if (actual.size() != 1 || !actual.getFirst().equals(hold)) {
      throw new IllegalStateException("Referral retention hold conflicts with restored state");
    }
  }

  private void mergeRestriction(
      ReferralRetentionManifest.IdentifierRestrictionSnapshot restriction) {
    List<String> states =
        jdbc.query(
            "SELECT state FROM referral.draft WHERE referral_id = ? FOR UPDATE",
            (row, index) -> row.getString(1),
            restriction.referralId());
    if (states.size() != 1
        || !("CANCELLED".equals(states.getFirst()) || "RESOLVED".equals(states.getFirst()))) {
      throw new IllegalStateException(
          "Referral identifier restriction cannot be reapplied to a missing or open Referral");
    }
    if (hasActiveHold(new InternalId(restriction.referralId()))) {
      throw new IllegalStateException(
          "Referral identifier restriction conflicts with an active restored hold");
    }
    jdbc.update(
        "INSERT INTO referral.identifier_restriction "
            + "(referral_id, restriction_id, reason_code, restricted_at, restricted_by_kind, "
            + "restricted_by_issuer, restricted_by_identifier_type, restricted_by_identifier) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (referral_id) DO NOTHING",
        restriction.referralId(),
        restriction.restrictionId(),
        restriction.reasonCode(),
        Timestamp.from(restriction.restrictedAt()),
        restriction.restrictedByKind(),
        restriction.restrictedByIssuer(),
        restriction.restrictedByIdentifierType(),
        restriction.restrictedByIdentifier());
    List<ReferralRetentionManifest.IdentifierRestrictionSnapshot> actual =
        jdbc.query(
            "SELECT referral_id, restriction_id, reason_code, restricted_at, restricted_by_kind, "
                + "restricted_by_issuer, restricted_by_identifier_type, restricted_by_identifier "
                + "FROM referral.identifier_restriction WHERE referral_id = ?",
            (row, index) ->
                new ReferralRetentionManifest.IdentifierRestrictionSnapshot(
                    row.getObject("referral_id", UUID.class),
                    row.getObject("restriction_id", UUID.class),
                    row.getString("reason_code"),
                    row.getTimestamp("restricted_at").toInstant(),
                    row.getString("restricted_by_kind"),
                    row.getString("restricted_by_issuer"),
                    row.getString("restricted_by_identifier_type"),
                    row.getString("restricted_by_identifier")),
            restriction.referralId());
    if (actual.size() != 1 || !actual.getFirst().equals(restriction)) {
      throw new IllegalStateException("Referral restriction conflicts with restored state");
    }
  }

  private static ReferralRetentionManifest.HoldSnapshot holdSnapshot(
      ResultSet row, int ignored) throws SQLException {
    return new ReferralRetentionManifest.HoldSnapshot(
        row.getObject("hold_id", UUID.class),
        row.getObject("referral_id", UUID.class),
        row.getString("purpose_code"),
        row.getTimestamp("review_by").toInstant(),
        row.getTimestamp("created_at").toInstant(),
        row.getString("created_by_kind"),
        row.getString("created_by_issuer"),
        row.getString("created_by_identifier_type"),
        row.getString("created_by_identifier"),
        Optional.ofNullable(row.getTimestamp("released_at")).map(Timestamp::toInstant),
        Optional.ofNullable(row.getString("release_reason_code")),
        Optional.ofNullable(row.getString("released_by_kind")),
        Optional.ofNullable(row.getString("released_by_issuer")),
        Optional.ofNullable(row.getString("released_by_identifier_type")),
        Optional.ofNullable(row.getString("released_by_identifier")));
  }
}
