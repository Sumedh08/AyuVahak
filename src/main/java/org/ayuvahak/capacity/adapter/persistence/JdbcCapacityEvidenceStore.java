package org.ayuvahak.capacity.adapter.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.ayuvahak.capacity.application.CapacityEvidenceStore;
import org.ayuvahak.capacity.domain.CapacityObservationFact;
import org.ayuvahak.platform.core.ExternalReference;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** PostgreSQL read adapter for current-evidence reconstruction and bounded source history. */
@Repository
@Lazy
public class JdbcCapacityEvidenceStore implements CapacityEvidenceStore {

  private static final String FACT_COLUMNS =
      JdbcCapacityObservationStore.OBSERVATION_COLUMNS
          + ", EXISTS (SELECT 1 FROM capacity.observation_correction supersession "
          + "WHERE supersession.superseded_observation_id = o.observation_id) AS source_superseded";

  private static final RowMapper<CapacityObservationFact> FACT_MAPPER =
      (result, row) ->
          new CapacityObservationFact(
              JdbcCapacityObservationStore.observation(result, row),
              result.getBoolean("source_superseded"));

  private final JdbcTemplate jdbc;

  public JdbcCapacityEvidenceStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public long currentSourceSequence(ExternalReference facility, String dimensionCode) {
    Long sequence =
        jdbc.queryForObject(
            "SELECT COALESCE(MAX(source_sequence), 1) FROM capacity.capacity_observation "
                + "WHERE facility_issuer = ? AND facility_identifier_type = ? "
                + "AND facility_identifier = ? AND dimension_code = ?",
            Long.class,
            facility.issuer().value(),
            facility.resourceType(),
            facility.value(),
            dimensionCode);
    if (sequence == null || sequence < 1) {
      throw new IllegalStateException("Capacity source sequence is not positive");
    }
    return sequence;
  }

  @Override
  public List<CapacityObservationFact> findForProjection(
      ExternalReference facility, String dimensionCode) {
    return jdbc.query(
        "SELECT "
            + FACT_COLUMNS
            + " FROM capacity.capacity_observation o LEFT JOIN capacity.observation_correction c "
            + "ON c.correcting_observation_id = o.observation_id "
            + "WHERE o.facility_issuer = ? AND o.facility_identifier_type = ? "
            + "AND o.facility_identifier = ? AND o.dimension_code = ? "
            + "ORDER BY o.observed_at DESC, o.observation_id DESC",
        FACT_MAPPER,
        facility.issuer().value(),
        facility.resourceType(),
        facility.value(),
        dimensionCode);
  }

  @Override
  public List<CapacityObservationFact> findHistory(
      ExternalReference facility,
      Optional<String> dimensionCode,
      Optional<String> sourceCode,
      Optional<Instant> observedFromInclusive,
      Optional<Instant> observedUntilExclusive,
      int limit,
      long offset) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT "
                + FACT_COLUMNS
                + " FROM capacity.capacity_observation o "
                + "LEFT JOIN capacity.observation_correction c "
                + "ON c.correcting_observation_id = o.observation_id "
                + "WHERE o.facility_issuer = ? AND o.facility_identifier_type = ? "
                + "AND o.facility_identifier = ?");
    List<Object> parameters = new ArrayList<>();
    parameters.add(facility.issuer().value());
    parameters.add(facility.resourceType());
    parameters.add(facility.value());
    dimensionCode.ifPresent(
        code -> {
          sql.append(" AND o.dimension_code = ?");
          parameters.add(code);
        });
    sourceCode.ifPresent(
        code -> {
          sql.append(" AND o.source_code = ?");
          parameters.add(code);
        });
    observedFromInclusive.ifPresent(
        from -> {
          sql.append(" AND o.observed_at >= ?");
          parameters.add(Timestamp.from(from));
        });
    observedUntilExclusive.ifPresent(
        until -> {
          sql.append(" AND o.observed_at < ?");
          parameters.add(Timestamp.from(until));
        });
    sql.append(" ORDER BY o.observed_at DESC, o.observation_id DESC LIMIT ? OFFSET ?");
    parameters.add(limit);
    parameters.add(offset);
    return jdbc.query(sql.toString(), FACT_MAPPER, parameters.toArray());
  }
}
