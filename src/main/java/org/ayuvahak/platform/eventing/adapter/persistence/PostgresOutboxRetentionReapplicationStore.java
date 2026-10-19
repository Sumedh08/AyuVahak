package org.ayuvahak.platform.eventing.adapter.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.platform.eventing.application.PlatformOutboxRetentionManifest;
import org.ayuvahak.platform.eventing.application.PlatformOutboxRetentionReapplicationStore;
import org.ayuvahak.platform.retention.OwnerRetentionReapplicationStatus;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL adapter that reapplies only Platform-owned outbox retention state. */
@Repository
@Lazy
public class PostgresOutboxRetentionReapplicationStore
    implements PlatformOutboxRetentionReapplicationStore, OwnerRetentionReapplicationStatus {

  private final JdbcTemplate jdbc;

  public PostgresOutboxRetentionReapplicationStore(JdbcTemplate jdbc) {
    this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public PlatformOutboxRetentionManifest exportSince(
      UUID restoreId, Instant backupCompletedAt, Instant generatedAt) {
    List<PlatformOutboxRetentionManifest.PurgedEvent> purged =
        jdbc.query(
            "SELECT event_id, delivery_generation, purged_at "
                + "FROM platform_eventing.retention_purge_receipt WHERE purged_at > ? "
                + "ORDER BY purged_at, event_id, delivery_generation",
            (row, index) ->
                new PlatformOutboxRetentionManifest.PurgedEvent(
                    row.getObject("event_id", UUID.class),
                    row.getInt("delivery_generation"),
                    row.getTimestamp("purged_at").toInstant()),
            Timestamp.from(backupCompletedAt));
    List<PlatformOutboxRetentionManifest.HoldSnapshot> holds =
        jdbc.query(
            "SELECT hold_id, event_id, purpose_code, review_by, created_at, created_by_kind, "
                + "created_by_issuer, created_by_identifier_type, created_by_identifier, "
                + "released_at, release_reason_code, released_by_kind, released_by_issuer, "
                + "released_by_identifier_type, released_by_identifier "
                + "FROM platform_eventing.retention_hold WHERE created_at > ? OR released_at > ? "
                + "ORDER BY created_at, hold_id",
            PostgresOutboxRetentionReapplicationStore::holdSnapshot,
            Timestamp.from(backupCompletedAt),
            Timestamp.from(backupCompletedAt));
    return new PlatformOutboxRetentionManifest(
        restoreId, backupCompletedAt, generatedAt, purged, holds);
  }

  @Override
  public void reapply(PlatformOutboxRetentionManifest manifest, Instant completedAt) {
    for (PlatformOutboxRetentionManifest.HoldSnapshot hold : manifest.holds()) {
      mergeHold(hold);
    }
    for (PlatformOutboxRetentionManifest.PurgedEvent purged : manifest.purgedEvents()) {
      reapplyPurge(purged);
    }
    jdbc.update(
        "INSERT INTO platform_eventing.retention_reapplication_receipt (restore_id, completed_at) "
            + "VALUES (?, ?) ON CONFLICT (restore_id) DO UPDATE SET completed_at = EXCLUDED.completed_at",
        manifest.restoreId(),
        Timestamp.from(completedAt));
  }

  @Override
  public boolean hasCompleted(UUID restoreId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM platform_eventing.retention_reapplication_receipt "
                + "WHERE restore_id = ?)",
            Boolean.class,
            restoreId));
  }

  @Override
  public String ownerCode() {
    return "platform-eventing";
  }

  private void mergeHold(PlatformOutboxRetentionManifest.HoldSnapshot hold) {
    jdbc.update(
        "INSERT INTO platform_eventing.retention_hold "
            + "(hold_id, event_id, purpose_code, review_by, created_at, created_by_kind, "
            + "created_by_issuer, created_by_identifier_type, created_by_identifier, released_at, "
            + "release_reason_code, released_by_kind, released_by_issuer, released_by_identifier_type, "
            + "released_by_identifier) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (hold_id) DO NOTHING",
        hold.holdId(),
        hold.eventId(),
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
          "UPDATE platform_eventing.retention_hold SET released_at = ?, release_reason_code = ?, "
              + "released_by_kind = ?, released_by_issuer = ?, released_by_identifier_type = ?, "
              + "released_by_identifier = ? WHERE hold_id = ? AND event_id = ? "
              + "AND purpose_code = ? AND created_at = ? AND released_at IS NULL",
          Timestamp.from(hold.releasedAt().orElseThrow()),
          hold.releaseReasonCode().orElseThrow(),
          hold.releasedByKind().orElseThrow(),
          hold.releasedByIssuer().orElseThrow(),
          hold.releasedByIdentifierType().orElseThrow(),
          hold.releasedByIdentifier().orElseThrow(),
          hold.holdId(),
          hold.eventId(),
          hold.purposeCode(),
          Timestamp.from(hold.createdAt()));
    }
    List<PlatformOutboxRetentionManifest.HoldSnapshot> actual =
        jdbc.query(
            "SELECT hold_id, event_id, purpose_code, review_by, created_at, created_by_kind, "
                + "created_by_issuer, created_by_identifier_type, created_by_identifier, "
                + "released_at, release_reason_code, released_by_kind, released_by_issuer, "
                + "released_by_identifier_type, released_by_identifier "
                + "FROM platform_eventing.retention_hold WHERE hold_id = ?",
            PostgresOutboxRetentionReapplicationStore::holdSnapshot,
            hold.holdId());
    if (actual.size() != 1 || !actual.getFirst().equals(hold)) {
      throw new IllegalStateException("Platform retention hold conflicts with restored state");
    }
  }

  private void reapplyPurge(PlatformOutboxRetentionManifest.PurgedEvent purge) {
    List<PurgeTarget> targets =
        jdbc.query(
            "SELECT e.current_delivery_generation, d.state "
                + "FROM platform_eventing.internal_event e "
                + "LEFT JOIN platform_eventing.outbox_delivery d ON d.event_id = e.event_id "
                + "AND d.delivery_generation = e.current_delivery_generation "
                + "WHERE e.event_id = ? FOR UPDATE OF e",
            (row, index) ->
                new PurgeTarget(
                    row.getInt("current_delivery_generation"), row.getString("state")),
            purge.eventId());
    if (!targets.isEmpty()) {
      PurgeTarget target = targets.getFirst();
      if (target.deliveryGeneration() > purge.deliveryGeneration()
          || target.state() == null
          || Boolean.TRUE.equals(
              jdbc.queryForObject(
                  "SELECT EXISTS (SELECT 1 FROM platform_eventing.retention_hold "
                      + "WHERE event_id = ? AND released_at IS NULL)",
                  Boolean.class,
                  purge.eventId()))) {
        throw new IllegalStateException("Platform purge cannot be reapplied to current outbox state");
      }
      jdbc.update(
          "DELETE FROM platform_eventing.delivery_command_receipt WHERE event_id = ?",
          purge.eventId());
      jdbc.update(
          "DELETE FROM platform_eventing.outbox_delivery WHERE event_id = ?",
          purge.eventId());
      jdbc.update(
          "DELETE FROM platform_eventing.internal_event WHERE event_id = ?",
          purge.eventId());
    }
    jdbc.update(
        "INSERT INTO platform_eventing.retention_purge_receipt "
            + "(event_id, delivery_generation, purged_at) VALUES (?, ?, ?) "
            + "ON CONFLICT (event_id, delivery_generation) DO NOTHING",
        purge.eventId(),
        purge.deliveryGeneration(),
        Timestamp.from(purge.purgedAt()));
  }

  private static PlatformOutboxRetentionManifest.HoldSnapshot holdSnapshot(
      ResultSet row, int ignored) throws SQLException {
    return new PlatformOutboxRetentionManifest.HoldSnapshot(
        row.getObject("hold_id", UUID.class),
        row.getObject("event_id", UUID.class),
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

  private record PurgeTarget(int deliveryGeneration, String state) {}
}
