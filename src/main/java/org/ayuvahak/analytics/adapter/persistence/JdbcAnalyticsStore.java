package org.ayuvahak.analytics.adapter.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.ayuvahak.analytics.application.AnalyticsDefinitionConflictException;
import org.ayuvahak.analytics.application.AnalyticsSnapshotConflictException;
import org.ayuvahak.analytics.application.AnalyticsStore;
import org.ayuvahak.analytics.domain.AggregateCompleteness;
import org.ayuvahak.analytics.domain.AggregateInput;
import org.ayuvahak.analytics.domain.AggregateSnapshot;
import org.ayuvahak.analytics.domain.AggregationFormula;
import org.ayuvahak.analytics.domain.IndicatorDefinitionVersion;
import org.ayuvahak.analytics.domain.ReportingPeriod;
import org.ayuvahak.analytics.domain.SourceMeasure;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JDBC persistence adapter; every write and source-input set is committed in one local transaction.
 */
@Repository
public class JdbcAnalyticsStore implements AnalyticsStore {

  private static final RowMapper<AggregateHeader> AGGREGATE_HEADER_MAPPER =
      (result, rowNumber) ->
          new AggregateHeader(
              result.getObject("snapshot_id", UUID.class),
              result.getObject("computation_request_id", UUID.class),
              result.getString("indicator_key"),
              result.getInt("definition_version"),
              result.getString("organization_issuer"),
              result.getString("organization_resource_type"),
              result.getObject("organization_id", UUID.class),
              result.getObject("period_start_local", LocalDate.class),
              result.getObject("period_end_exclusive_local", LocalDate.class),
              instant(result, "period_start_utc"),
              instant(result, "period_end_exclusive_utc"),
              result.getString("time_zone_id"),
              result.getString("time_zone_rules_version"),
              AggregateCompleteness.valueOf(result.getString("completeness")),
              nullableLong(result, "aggregate_value"),
              instant(result, "computed_at"),
              result.getString("input_fingerprint"));

  private static final RowMapper<AggregateInput> AGGREGATE_INPUT_MAPPER =
      (result, rowNumber) ->
          new AggregateInput(
              measure(
                  result.getString("source_owner"),
                  result.getString("measure_key"),
                  result.getString("attribution")),
              Optional.ofNullable(result.getObject("source_snapshot_reference", UUID.class)),
              Optional.ofNullable(instantOrNull(result, "source_cutoff")),
              Optional.ofNullable(result.getString("owner_version")),
              AggregateCompleteness.valueOf(result.getString("completeness")),
              nullableLong(result, "measure_value"),
              Optional.ofNullable(instantOrNull(result, "generated_at")));

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public JdbcAnalyticsStore(JdbcTemplate jdbc, DataSourceTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @Override
  public Optional<IndicatorDefinitionVersion> findDefinition(String key, int version) {
    List<DefinitionHeader> rows =
        jdbc.query(
            "SELECT indicator_key, definition_version, description, aggregation_formula, "
                + "reporting_time_zone, zero_allowed FROM analytics.indicator_definition_version "
                + "WHERE indicator_key = ? AND definition_version = ?",
            (result, rowNumber) ->
                new DefinitionHeader(
                    result.getString("indicator_key"),
                    result.getInt("definition_version"),
                    result.getString("description"),
                    AggregationFormula.valueOf(result.getString("aggregation_formula")),
                    result.getString("reporting_time_zone"),
                    result.getBoolean("zero_allowed")),
            key,
            version);
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    DefinitionHeader header = rows.getFirst();
    List<SourceMeasure> measures =
        jdbc.query(
            "SELECT source_owner, measure_key, attribution FROM analytics.indicator_definition_measure "
                + "WHERE indicator_key = ? AND definition_version = ? ORDER BY measure_position",
            (result, rowNumber) ->
                measure(
                    result.getString("source_owner"),
                    result.getString("measure_key"),
                    result.getString("attribution")),
            key,
            version);
    return Optional.of(
        new IndicatorDefinitionVersion(
            header.key(),
            header.version(),
            header.description(),
            header.formula(),
            ZoneId.of(header.timeZone()),
            measures,
            header.zeroAllowed()));
  }

  @Override
  public IndicatorDefinitionVersion publishDefinition(IndicatorDefinitionVersion definition) {
    try {
      return transactions.execute(
          status -> {
            Optional<IndicatorDefinitionVersion> existing =
                findDefinition(definition.key(), definition.version());
            if (existing.isPresent()) {
              if (existing.orElseThrow().fingerprint().equals(definition.fingerprint())) {
                return existing.orElseThrow();
              }
              throw new AnalyticsDefinitionConflictException();
            }
            Integer currentVersion =
                jdbc.queryForObject(
                    "SELECT MAX(definition_version) FROM analytics.indicator_definition_version "
                        + "WHERE indicator_key = ?",
                    Integer.class,
                    definition.key());
            int nextVersion = currentVersion == null ? 1 : currentVersion + 1;
            if (definition.version() != nextVersion) {
              throw new AnalyticsDefinitionConflictException();
            }
            jdbc.update(
                "INSERT INTO analytics.indicator_definition_version "
                    + "(indicator_key, definition_version, description, aggregation_formula, "
                    + "reporting_time_zone, zero_allowed, definition_fingerprint) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                definition.key(),
                definition.version(),
                definition.description(),
                definition.formula().name(),
                definition.reportingTimeZone().getId(),
                definition.zeroAllowed(),
                definition.fingerprint());
            for (int index = 0; index < definition.sourceMeasures().size(); index++) {
              SourceMeasure measure = definition.sourceMeasures().get(index);
              jdbc.update(
                  "INSERT INTO analytics.indicator_definition_measure "
                      + "(indicator_key, definition_version, measure_position, source_owner, measure_key, attribution) "
                      + "VALUES (?, ?, ?, ?, ?, ?)",
                  definition.key(),
                  definition.version(),
                  index,
                  measure.owner().name(),
                  measure.key(),
                  measure.attribution().name());
            }
            return definition;
          });
    } catch (DuplicateKeyException concurrentPublish) {
      return findDefinition(definition.key(), definition.version())
          .filter(existing -> existing.fingerprint().equals(definition.fingerprint()))
          .orElseThrow(AnalyticsDefinitionConflictException::new);
    }
  }

  @Override
  public AggregateSnapshot saveOrReplay(AggregateSnapshot snapshot) {
    try {
      return transactions.execute(
          status -> {
            Optional<AggregateSnapshot> existing = findSnapshot(snapshot.computationRequestId());
            if (existing.isPresent()) {
              return replayOrConflict(existing.orElseThrow(), snapshot);
            }
            insertSnapshot(snapshot);
            for (AggregateInput input : snapshot.inputs()) {
              jdbc.update(
                  "INSERT INTO analytics.aggregate_snapshot_input "
                      + "(snapshot_id, source_owner, measure_key, attribution, source_snapshot_reference, "
                      + "source_cutoff, owner_version, completeness, measure_value, generated_at) "
                      + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                  snapshot.snapshotId(),
                  input.measure().owner().name(),
                  input.measure().key(),
                  input.measure().attribution().name(),
                  input.sourceSnapshotReference().orElse(null),
                  input.sourceCutoff().map(Timestamp::from).orElse(null),
                  input.ownerContractVersion().orElse(null),
                  input.completeness().name(),
                  input.value().isPresent() ? input.value().getAsLong() : null,
                  input.generatedAt().map(Timestamp::from).orElse(null));
            }
            return snapshot;
          });
    } catch (DuplicateKeyException concurrentComputation) {
      return findSnapshot(snapshot.computationRequestId())
          .map(existing -> replayOrConflict(existing, snapshot))
          .orElseThrow(AnalyticsSnapshotConflictException::new);
    }
  }

  @Override
  public Optional<AggregateSnapshot> findSnapshot(InternalId computationRequestId) {
    List<AggregateHeader> rows =
        jdbc.query(
            "SELECT snapshot_id, computation_request_id, indicator_key, definition_version, "
                + "organization_issuer, organization_resource_type, organization_id, "
                + "period_start_local, period_end_exclusive_local, period_start_utc, "
                + "period_end_exclusive_utc, time_zone_id, time_zone_rules_version, completeness, "
                + "aggregate_value, computed_at, input_fingerprint "
                + "FROM analytics.aggregate_snapshot WHERE computation_request_id = ?",
            AGGREGATE_HEADER_MAPPER,
            computationRequestId.value());
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    AggregateHeader header = rows.getFirst();
    return Optional.of(loadSnapshot(header));
  }

  @Override
  public Optional<AggregateSnapshot> findSnapshotById(UUID snapshotId) {
    List<AggregateHeader> rows =
        jdbc.query(
            "SELECT snapshot_id, computation_request_id, indicator_key, definition_version, "
                + "organization_issuer, organization_resource_type, organization_id, "
                + "period_start_local, period_end_exclusive_local, period_start_utc, "
                + "period_end_exclusive_utc, time_zone_id, time_zone_rules_version, completeness, "
                + "aggregate_value, computed_at, input_fingerprint "
                + "FROM analytics.aggregate_snapshot WHERE snapshot_id = ?",
            AGGREGATE_HEADER_MAPPER,
            snapshotId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(loadSnapshot(rows.getFirst()));
  }

  private AggregateSnapshot loadSnapshot(AggregateHeader header) {
    List<AggregateInput> inputs =
        jdbc.query(
            "SELECT source_owner, measure_key, attribution, source_snapshot_reference, source_cutoff, "
                + "owner_version, completeness, measure_value, generated_at "
                + "FROM analytics.aggregate_snapshot_input WHERE snapshot_id = ? "
                + "ORDER BY source_owner, measure_key, attribution",
            AGGREGATE_INPUT_MAPPER,
            header.snapshotId());
    return header.toSnapshot(inputs);
  }

  private void insertSnapshot(AggregateSnapshot snapshot) {
    jdbc.update(
        "INSERT INTO analytics.aggregate_snapshot "
            + "(snapshot_id, computation_request_id, indicator_key, definition_version, organization_issuer, "
            + "organization_resource_type, organization_id, period_start_local, period_end_exclusive_local, "
            + "period_start_utc, period_end_exclusive_utc, time_zone_id, time_zone_rules_version, "
            + "completeness, aggregate_value, computed_at, input_fingerprint) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        snapshot.snapshotId(),
        snapshot.computationRequestId().value(),
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
        snapshot.completeness().name(),
        snapshot.value().isPresent() ? snapshot.value().getAsLong() : null,
        Timestamp.from(snapshot.computedAt()),
        snapshot.inputFingerprint());
  }

  private static AggregateSnapshot replayOrConflict(
      AggregateSnapshot existing, AggregateSnapshot attempted) {
    if (!existing.inputFingerprint().equals(attempted.inputFingerprint())) {
      throw new AnalyticsSnapshotConflictException();
    }
    return existing;
  }

  private static SourceMeasure measure(String owner, String key, String attribution) {
    return new SourceMeasure(
        SourceMeasure.Owner.valueOf(owner), key, SourceMeasure.Attribution.valueOf(attribution));
  }

  private static OptionalLong nullableLong(ResultSet result, String column) throws SQLException {
    long value = result.getLong(column);
    return result.wasNull() ? OptionalLong.empty() : OptionalLong.of(value);
  }

  private static Instant instant(ResultSet result, String column) throws SQLException {
    Timestamp timestamp = result.getTimestamp(column);
    return timestamp == null ? null : timestamp.toInstant();
  }

  private static Instant instantOrNull(ResultSet result, String column) throws SQLException {
    return instant(result, column);
  }

  private record DefinitionHeader(
      String key,
      int version,
      String description,
      AggregationFormula formula,
      String timeZone,
      boolean zeroAllowed) {}

  private record AggregateHeader(
      UUID snapshotId,
      UUID computationRequestId,
      String indicatorKey,
      int definitionVersion,
      String organizationIssuer,
      String organizationResourceType,
      UUID organizationId,
      LocalDate periodStartLocal,
      LocalDate periodEndExclusiveLocal,
      Instant periodStartUtc,
      Instant periodEndExclusiveUtc,
      String timeZoneId,
      String timeZoneRulesVersion,
      AggregateCompleteness completeness,
      OptionalLong value,
      Instant computedAt,
      String inputFingerprint) {

    private AggregateSnapshot toSnapshot(List<AggregateInput> inputs) {
      return new AggregateSnapshot(
          snapshotId,
          new InternalId(computationRequestId),
          indicatorKey,
          definitionVersion,
          new ExternalReference(
              new IssuerId(organizationIssuer),
              organizationResourceType,
              organizationId.toString()),
          ReportingPeriod.restore(
              periodStartLocal,
              periodEndExclusiveLocal,
              timeZoneId,
              periodStartUtc,
              periodEndExclusiveUtc,
              timeZoneRulesVersion),
          completeness,
          value,
          inputs,
          computedAt,
          inputFingerprint);
    }
  }
}
