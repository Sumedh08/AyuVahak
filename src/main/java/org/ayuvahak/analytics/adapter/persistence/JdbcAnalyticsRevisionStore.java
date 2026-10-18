package org.ayuvahak.analytics.adapter.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.analytics.application.AnalyticsRevisionStore;
import org.ayuvahak.analytics.application.AnalyticsSnapshotRevisionException;
import org.ayuvahak.analytics.application.AppendAggregateSnapshotRevisionRequest;
import org.ayuvahak.analytics.application.FinalizeAggregateSnapshotRequest;
import org.ayuvahak.analytics.domain.AggregateInput;
import org.ayuvahak.analytics.domain.AggregateSnapshot;
import org.ayuvahak.analytics.domain.AggregateSnapshotFinalization;
import org.ayuvahak.analytics.domain.AggregateSnapshotRevision;
import org.ayuvahak.analytics.domain.AnalyticsFinalizationReason;
import org.ayuvahak.analytics.domain.AnalyticsRevisionCause;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** JDBC adapter for append-only revision lineage and compare-and-finalize state. */
@Repository
public class JdbcAnalyticsRevisionStore implements AnalyticsRevisionStore {

  private static final String REVISION_COLUMNS =
      "r.series_id, r.revision_number, r.snapshot_id, r.previous_snapshot_id, r.cause, "
          + "r.latest_source_cutoff, r.recorded_at, r.audit_record_id, r.authorized_correction, "
          + "r.operation_fingerprint, s.state, s.correction_window_ends_at";

  private static final RowMapper<RevisionRow> REVISION_ROW_MAPPER =
      (result, rowNumber) ->
          new RevisionRow(
              new AggregateSnapshotRevision(
                  result.getObject("series_id", UUID.class),
                  result.getInt("revision_number"),
                  result.getObject("snapshot_id", UUID.class),
                  Optional.ofNullable(result.getObject("previous_snapshot_id", UUID.class)),
                  AnalyticsRevisionCause.valueOf(result.getString("cause")),
                  AggregateSnapshotRevision.State.valueOf(result.getString("state")),
                  Optional.ofNullable(instant(result, "latest_source_cutoff")),
                  instant(result, "correction_window_ends_at"),
                  instant(result, "recorded_at"),
                  Optional.ofNullable(result.getObject("audit_record_id", UUID.class)),
                  result.getBoolean("authorized_correction"),
                  false),
              result.getString("operation_fingerprint"));

  private static final RowMapper<SeriesHeader> SERIES_HEADER_MAPPER =
      (result, rowNumber) ->
          new SeriesHeader(
              result.getObject("series_id", UUID.class),
              result.getString("indicator_key"),
              result.getInt("definition_version"),
              result.getString("organization_issuer"),
              result.getString("organization_resource_type"),
              result.getObject("organization_id", UUID.class),
              result.getObject("period_start_local", java.time.LocalDate.class),
              result.getObject("period_end_exclusive_local", java.time.LocalDate.class),
              instant(result, "period_start_utc"),
              instant(result, "period_end_exclusive_utc"),
              result.getString("time_zone_id"),
              result.getString("time_zone_rules_version"),
              result.getInt("current_revision_number"),
              result.getObject("current_snapshot_id", UUID.class),
              AggregateSnapshotRevision.State.valueOf(result.getString("state")),
              instant(result, "correction_window_ends_at"));

  private static final RowMapper<FinalizationRow> FINALIZATION_ROW_MAPPER =
      (result, rowNumber) ->
          new FinalizationRow(
              new AggregateSnapshotFinalization(
                  result.getObject("operation_id", UUID.class),
                  result.getObject("series_id", UUID.class),
                  result.getObject("snapshot_id", UUID.class),
                  result.getInt("revision_number"),
                  AnalyticsFinalizationReason.valueOf(result.getString("reason")),
                  instant(result, "finalized_at"),
                  Optional.ofNullable(result.getObject("audit_record_id", UUID.class)),
                  false),
              result.getString("request_fingerprint"));

  private final JdbcTemplate jdbc;

  public JdbcAnalyticsRevisionStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<AggregateSnapshotRevision> currentRevision(UUID anySnapshotId) {
    List<RevisionRow> rows =
        jdbc.query(
            "SELECT "
                + REVISION_COLUMNS
                + " FROM analytics.aggregate_snapshot_series s "
                + "JOIN analytics.aggregate_snapshot_revision anchor ON anchor.series_id = s.series_id "
                + "JOIN analytics.aggregate_snapshot_revision r ON r.series_id = s.series_id "
                + "AND r.revision_number = s.current_revision_number "
                + "WHERE anchor.snapshot_id = ?",
            REVISION_ROW_MAPPER,
            anySnapshotId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst().revision());
  }

  @Override
  public AggregateSnapshotRevision registerInitial(
      AggregateSnapshot snapshot,
      UUID operationId,
      Instant recordedAt,
      Instant correctionWindowEndsAt) {
    String fingerprint = initialFingerprint(snapshot);
    Optional<RevisionRow> priorOperation = findRevisionByOperation(operationId);
    if (priorOperation.isPresent()) {
      return replay(priorOperation.orElseThrow(), fingerprint);
    }

    UUID seriesId = UUID.randomUUID();
    List<UUID> insertedSeries =
        jdbc.query(
            "INSERT INTO analytics.aggregate_snapshot_series ("
                + "series_id, indicator_key, definition_version, organization_issuer, "
                + "organization_resource_type, organization_id, period_start_local, "
                + "period_end_exclusive_local, period_start_utc, period_end_exclusive_utc, "
                + "time_zone_id, time_zone_rules_version, current_revision_number, "
                + "current_snapshot_id, state, correction_window_ends_at, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, 'PROVISIONAL', ?, ?, ?) "
                + "ON CONFLICT (indicator_key, definition_version, organization_issuer, "
                + "organization_resource_type, organization_id, period_start_local, "
                + "period_end_exclusive_local, period_start_utc, period_end_exclusive_utc, "
                + "time_zone_id, time_zone_rules_version) DO NOTHING RETURNING series_id",
            (result, rowNumber) -> result.getObject("series_id", UUID.class),
            seriesId,
            snapshot.indicatorKey(),
            snapshot.indicatorVersion(),
            snapshot.organization().issuer().value(),
            snapshot.organization().resourceType(),
            UUID.fromString(snapshot.organization().value()),
            snapshot.period().startInclusiveLocal(),
            snapshot.period().endExclusiveLocal(),
            Timestamp.from(snapshot.period().startInclusiveUtc()),
            Timestamp.from(snapshot.period().endExclusiveUtc()),
            snapshot.period().timeZoneId(),
            snapshot.period().timeZoneRulesVersion(),
            snapshot.snapshotId(),
            Timestamp.from(correctionWindowEndsAt),
            Timestamp.from(recordedAt),
            Timestamp.from(recordedAt));
    if (insertedSeries.isEmpty()) {
      Optional<RevisionRow> concurrentOperation = findRevisionByOperation(operationId);
      if (concurrentOperation.isPresent()) {
        return replay(concurrentOperation.orElseThrow(), fingerprint);
      }
      throw failure(AnalyticsSnapshotRevisionException.Reason.SERIES_ALREADY_EXISTS);
    }

    insertRevision(
        seriesId,
        1,
        snapshot,
        Optional.empty(),
        operationId,
        fingerprint,
        AnalyticsRevisionCause.INITIAL,
        false,
        recordedAt);
    return revision(
        seriesId,
        1,
        snapshot.snapshotId(),
        Optional.empty(),
        AnalyticsRevisionCause.INITIAL,
        AggregateSnapshotRevision.State.PROVISIONAL,
        correctionWindowEndsAt,
        recordedAt,
        Optional.empty(),
        false,
        false,
        Optional.empty());
  }

  @Override
  public AggregateSnapshotRevision appendRevision(
      AggregateSnapshot previousSnapshot,
      AggregateSnapshot replacementSnapshot,
      AppendAggregateSnapshotRevisionRequest request,
      Instant recordedAt,
      boolean lateRevisionAuthorized,
      boolean correctionAuthorized) {
    String fingerprint = appendFingerprint(previousSnapshot, replacementSnapshot, request);
    Optional<RevisionRow> priorOperation = findRevisionByOperation(request.operationId());
    if (priorOperation.isPresent()) {
      return replay(priorOperation.orElseThrow(), fingerprint);
    }

    SeriesHeader series = lockSeries(previousSnapshot.snapshotId());
    Optional<RevisionRow> concurrentOperation = findRevisionByOperation(request.operationId());
    if (concurrentOperation.isPresent()) {
      return replay(concurrentOperation.orElseThrow(), fingerprint);
    }
    if (!series.currentSnapshotId().equals(previousSnapshot.snapshotId())
        || series.currentRevisionNumber() != request.expectedRevisionNumber()
        || !request.expectedSnapshotId().equals(previousSnapshot.snapshotId())) {
      throw failure(AnalyticsSnapshotRevisionException.Reason.REVISION_CONFLICT);
    }
    if (!matchesSeries(previousSnapshot, series) || !matchesSeries(replacementSnapshot, series)) {
      throw failure(AnalyticsSnapshotRevisionException.Reason.SERIES_MISMATCH);
    }
    if (replacementSnapshot.completeness() == previousSnapshot.completeness()
        && replacementSnapshot.value().equals(previousSnapshot.value())
        && replacementSnapshot.inputs().equals(previousSnapshot.inputs())) {
      throw failure(AnalyticsSnapshotRevisionException.Reason.NO_CHANGE);
    }
    if (snapshotAlreadyLinked(replacementSnapshot.snapshotId())) {
      throw failure(AnalyticsSnapshotRevisionException.Reason.SERIES_ALREADY_EXISTS);
    }

    boolean authorizedCorrection =
        request.cause() != AnalyticsRevisionCause.LATE_OWNER_FACT
            || series.state() == AggregateSnapshotRevision.State.FINALIZED
            || recordedAt.isAfter(series.correctionWindowEndsAt());
    if (authorizedCorrection && !correctionAuthorized) {
      throw failure(AnalyticsSnapshotRevisionException.Reason.FINALIZED_CORRECTION_REQUIRED);
    }
    if (!authorizedCorrection && !lateRevisionAuthorized && !correctionAuthorized) {
      throw failure(AnalyticsSnapshotRevisionException.Reason.NOT_AUTHORIZED);
    }

    int nextRevision = series.currentRevisionNumber() + 1;
    insertRevision(
        series.seriesId(),
        nextRevision,
        replacementSnapshot,
        Optional.of(previousSnapshot.snapshotId()),
        request.operationId(),
        fingerprint,
        request.cause(),
        authorizedCorrection,
        recordedAt);
    jdbc.update(
        "UPDATE analytics.aggregate_snapshot_series SET current_revision_number = ?, "
            + "current_snapshot_id = ?, state = 'PROVISIONAL', updated_at = ? "
            + "WHERE series_id = ? AND current_revision_number = ? AND current_snapshot_id = ?",
        nextRevision,
        replacementSnapshot.snapshotId(),
        Timestamp.from(recordedAt),
        series.seriesId(),
        series.currentRevisionNumber(),
        previousSnapshot.snapshotId());
    return revision(
        series.seriesId(),
        nextRevision,
        replacementSnapshot.snapshotId(),
        Optional.of(previousSnapshot.snapshotId()),
        request.cause(),
        AggregateSnapshotRevision.State.PROVISIONAL,
        series.correctionWindowEndsAt(),
        recordedAt,
        latestSourceCutoff(replacementSnapshot),
        authorizedCorrection,
        false,
        Optional.empty());
  }

  @Override
  public AggregateSnapshotFinalization finalizeSnapshot(
      AggregateSnapshot snapshot, FinalizeAggregateSnapshotRequest request, Instant finalizedAt) {
    String fingerprint = finalizationFingerprint(snapshot, request);
    Optional<FinalizationRow> priorOperation = findFinalizationByOperation(request.operationId());
    if (priorOperation.isPresent()) {
      return replay(priorOperation.orElseThrow(), fingerprint);
    }

    SeriesHeader series = lockSeries(snapshot.snapshotId());
    Optional<FinalizationRow> concurrentOperation =
        findFinalizationByOperation(request.operationId());
    if (concurrentOperation.isPresent()) {
      return replay(concurrentOperation.orElseThrow(), fingerprint);
    }
    if (!series.currentSnapshotId().equals(snapshot.snapshotId())
        || series.currentRevisionNumber() != request.expectedRevisionNumber()) {
      throw failure(AnalyticsSnapshotRevisionException.Reason.REVISION_CONFLICT);
    }
    if (snapshot.completeness() != org.ayuvahak.analytics.domain.AggregateCompleteness.COMPLETE
        || snapshot.value().isEmpty()) {
      throw failure(AnalyticsSnapshotRevisionException.Reason.INCOMPLETE_NOT_FINALIZABLE);
    }
    if (series.state() == AggregateSnapshotRevision.State.FINALIZED) {
      throw failure(AnalyticsSnapshotRevisionException.Reason.REVISION_CONFLICT);
    }
    jdbc.update(
        "INSERT INTO analytics.aggregate_snapshot_finalization (operation_id, series_id, "
            + "snapshot_id, revision_number, reason, request_fingerprint, finalized_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
        request.operationId(),
        series.seriesId(),
        snapshot.snapshotId(),
        request.expectedRevisionNumber(),
        request.reason().name(),
        fingerprint,
        Timestamp.from(finalizedAt));
    jdbc.update(
        "UPDATE analytics.aggregate_snapshot_series SET state = 'FINALIZED', updated_at = ? "
            + "WHERE series_id = ? AND current_revision_number = ? AND current_snapshot_id = ? "
            + "AND state = 'PROVISIONAL'",
        Timestamp.from(finalizedAt),
        series.seriesId(),
        request.expectedRevisionNumber(),
        snapshot.snapshotId());
    return new AggregateSnapshotFinalization(
        request.operationId(),
        series.seriesId(),
        snapshot.snapshotId(),
        request.expectedRevisionNumber(),
        request.reason(),
        finalizedAt,
        Optional.empty(),
        false);
  }

  @Override
  public void attachRevisionAudit(UUID operationId, UUID auditRecordId) {
    int updated =
        jdbc.update(
            "UPDATE analytics.aggregate_snapshot_revision SET audit_record_id = ? "
                + "WHERE operation_id = ? AND audit_record_id IS NULL",
            auditRecordId,
            operationId);
    if (updated != 1) {
      throw new IllegalStateException("revision audit could not be attached atomically");
    }
  }

  @Override
  public void attachFinalizationAudit(UUID operationId, UUID auditRecordId) {
    int updated =
        jdbc.update(
            "UPDATE analytics.aggregate_snapshot_finalization SET audit_record_id = ? "
                + "WHERE operation_id = ? AND audit_record_id IS NULL",
            auditRecordId,
            operationId);
    if (updated != 1) {
      throw new IllegalStateException("finalization audit could not be attached atomically");
    }
  }

  private SeriesHeader lockSeries(UUID snapshotId) {
    List<SeriesHeader> rows =
        jdbc.query(
            "SELECT s.series_id, s.indicator_key, s.definition_version, s.organization_issuer, "
                + "s.organization_resource_type, s.organization_id, s.period_start_local, "
                + "s.period_end_exclusive_local, s.period_start_utc, s.period_end_exclusive_utc, "
                + "s.time_zone_id, s.time_zone_rules_version, s.current_revision_number, "
                + "s.current_snapshot_id, s.state, s.correction_window_ends_at "
                + "FROM analytics.aggregate_snapshot_series s "
                + "JOIN analytics.aggregate_snapshot_revision r ON r.series_id = s.series_id "
                + "WHERE r.snapshot_id = ? FOR UPDATE OF s",
            SERIES_HEADER_MAPPER,
            snapshotId);
    if (rows.isEmpty()) {
      throw failure(AnalyticsSnapshotRevisionException.Reason.SERIES_NOT_FOUND);
    }
    return rows.getFirst();
  }

  private Optional<RevisionRow> findRevisionByOperation(UUID operationId) {
    List<RevisionRow> rows =
        jdbc.query(
            "SELECT "
                + REVISION_COLUMNS
                + " FROM analytics.aggregate_snapshot_revision r "
                + "JOIN analytics.aggregate_snapshot_series s ON s.series_id = r.series_id "
                + "WHERE r.operation_id = ?",
            REVISION_ROW_MAPPER,
            operationId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  private Optional<FinalizationRow> findFinalizationByOperation(UUID operationId) {
    List<FinalizationRow> rows =
        jdbc.query(
            "SELECT operation_id, series_id, snapshot_id, revision_number, reason, "
                + "request_fingerprint, finalized_at, audit_record_id "
                + "FROM analytics.aggregate_snapshot_finalization WHERE operation_id = ?",
            FINALIZATION_ROW_MAPPER,
            operationId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  private boolean snapshotAlreadyLinked(UUID snapshotId) {
    Boolean exists =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM analytics.aggregate_snapshot_revision WHERE snapshot_id = ?)",
            Boolean.class,
            snapshotId);
    return Boolean.TRUE.equals(exists);
  }

  private void insertRevision(
      UUID seriesId,
      int revisionNumber,
      AggregateSnapshot snapshot,
      Optional<UUID> previousSnapshotId,
      UUID operationId,
      String fingerprint,
      AnalyticsRevisionCause cause,
      boolean authorizedCorrection,
      Instant recordedAt) {
    jdbc.update(
        "INSERT INTO analytics.aggregate_snapshot_revision (revision_id, series_id, "
            + "revision_number, snapshot_id, previous_snapshot_id, operation_id, "
            + "operation_fingerprint, cause, latest_source_cutoff, recorded_at, authorized_correction) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        seriesId,
        revisionNumber,
        snapshot.snapshotId(),
        previousSnapshotId.orElse(null),
        operationId,
        fingerprint,
        cause.name(),
        latestSourceCutoff(snapshot).map(Timestamp::from).orElse(null),
        Timestamp.from(recordedAt),
        authorizedCorrection);
  }

  private static AggregateSnapshotRevision replay(RevisionRow row, String fingerprint) {
    if (!row.operationFingerprint().equals(fingerprint)) {
      throw failure(AnalyticsSnapshotRevisionException.Reason.OPERATION_ID_REUSED);
    }
    AggregateSnapshotRevision existing = row.revision();
    return new AggregateSnapshotRevision(
        existing.seriesId(),
        existing.revisionNumber(),
        existing.snapshotId(),
        existing.previousSnapshotId(),
        existing.cause(),
        existing.state(),
        existing.latestSourceCutoff(),
        existing.correctionWindowEndsAt(),
        existing.recordedAt(),
        existing.auditRecordId(),
        existing.authorizedCorrection(),
        true);
  }

  private static AggregateSnapshotFinalization replay(FinalizationRow row, String fingerprint) {
    if (!row.requestFingerprint().equals(fingerprint)) {
      throw failure(AnalyticsSnapshotRevisionException.Reason.OPERATION_ID_REUSED);
    }
    AggregateSnapshotFinalization existing = row.finalization();
    return new AggregateSnapshotFinalization(
        existing.operationId(),
        existing.seriesId(),
        existing.snapshotId(),
        existing.revisionNumber(),
        existing.reason(),
        existing.finalizedAt(),
        existing.auditRecordId(),
        true);
  }

  private static AggregateSnapshotRevision revision(
      UUID seriesId,
      int revisionNumber,
      UUID snapshotId,
      Optional<UUID> previousSnapshotId,
      AnalyticsRevisionCause cause,
      AggregateSnapshotRevision.State state,
      Instant correctionWindowEndsAt,
      Instant recordedAt,
      Optional<Instant> latestSourceCutoff,
      boolean authorizedCorrection,
      boolean replayed,
      Optional<UUID> auditRecordId) {
    return new AggregateSnapshotRevision(
        seriesId,
        revisionNumber,
        snapshotId,
        previousSnapshotId,
        cause,
        state,
        latestSourceCutoff,
        correctionWindowEndsAt,
        recordedAt,
        auditRecordId,
        authorizedCorrection,
        replayed);
  }

  private static boolean matchesSeries(AggregateSnapshot snapshot, SeriesHeader series) {
    return snapshot.indicatorKey().equals(series.indicatorKey())
        && snapshot.indicatorVersion() == series.definitionVersion()
        && snapshot.organization().issuer().value().equals(series.organizationIssuer())
        && snapshot.organization().resourceType().equals(series.organizationResourceType())
        && UUID.fromString(snapshot.organization().value()).equals(series.organizationId())
        && snapshot.period().startInclusiveLocal().equals(series.periodStartLocal())
        && snapshot.period().endExclusiveLocal().equals(series.periodEndExclusiveLocal())
        && snapshot.period().startInclusiveUtc().equals(series.periodStartUtc())
        && snapshot.period().endExclusiveUtc().equals(series.periodEndExclusiveUtc())
        && snapshot.period().timeZoneId().equals(series.timeZoneId())
        && snapshot.period().timeZoneRulesVersion().equals(series.timeZoneRulesVersion());
  }

  private static Optional<Instant> latestSourceCutoff(AggregateSnapshot snapshot) {
    return snapshot.inputs().stream()
        .map(AggregateInput::sourceCutoff)
        .flatMap(Optional::stream)
        .max(Instant::compareTo);
  }

  private static String initialFingerprint(AggregateSnapshot snapshot) {
    return fingerprint("INITIAL\n" + snapshot.snapshotId() + "\n" + snapshot.inputFingerprint());
  }

  private static String appendFingerprint(
      AggregateSnapshot previous,
      AggregateSnapshot replacement,
      AppendAggregateSnapshotRevisionRequest request) {
    return fingerprint(
        "REVISION\n"
            + previous.snapshotId()
            + "\n"
            + previous.inputFingerprint()
            + "\n"
            + request.expectedSnapshotId()
            + "\n"
            + request.expectedRevisionNumber()
            + "\n"
            + replacement.snapshotId()
            + "\n"
            + replacement.inputFingerprint()
            + "\n"
            + request.cause());
  }

  private static String finalizationFingerprint(
      AggregateSnapshot snapshot, FinalizeAggregateSnapshotRequest request) {
    return fingerprint(
        "FINALIZE\n"
            + snapshot.snapshotId()
            + "\n"
            + request.expectedRevisionNumber()
            + "\n"
            + request.reason());
  }

  private static String fingerprint(String canonical) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
    }
  }

  private static Instant instant(ResultSet result, String column) throws SQLException {
    Timestamp timestamp = result.getTimestamp(column);
    return timestamp == null ? null : timestamp.toInstant();
  }

  private static AnalyticsSnapshotRevisionException failure(
      AnalyticsSnapshotRevisionException.Reason reason) {
    return new AnalyticsSnapshotRevisionException(reason);
  }

  private record RevisionRow(AggregateSnapshotRevision revision, String operationFingerprint) {}

  private record FinalizationRow(
      AggregateSnapshotFinalization finalization, String requestFingerprint) {}

  private record SeriesHeader(
      UUID seriesId,
      String indicatorKey,
      int definitionVersion,
      String organizationIssuer,
      String organizationResourceType,
      UUID organizationId,
      java.time.LocalDate periodStartLocal,
      java.time.LocalDate periodEndExclusiveLocal,
      Instant periodStartUtc,
      Instant periodEndExclusiveUtc,
      String timeZoneId,
      String timeZoneRulesVersion,
      int currentRevisionNumber,
      UUID currentSnapshotId,
      AggregateSnapshotRevision.State state,
      Instant correctionWindowEndsAt) {}
}
