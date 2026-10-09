package org.ayuvahak.capacity.adapter.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.capacity.application.CapacityObservationStore;
import org.ayuvahak.capacity.domain.CapacityObservation;
import org.ayuvahak.capacity.domain.CapacityObservationKind;
import org.ayuvahak.capacity.domain.CapacityReportingMethod;
import org.ayuvahak.capacity.domain.CapacityValueKind;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL adapter for Capacity's immutable observation facts. */
@Repository
@Lazy
public class JdbcCapacityObservationStore implements CapacityObservationStore {

  private static final String OBSERVATION_COLUMNS =
      "o.observation_id, o.facility_issuer, o.facility_identifier_type, o.facility_identifier, "
          + "o.dimension_code, o.dimension_version, o.observation_kind, o.value_kind, o.quantity, "
          + "o.state_code, o.unit_code, o.source_code, o.source_issuer, o.source_identifier_type, "
          + "o.source_identifier, o.reporting_method, o.reported_by_kind, o.reported_by_issuer, "
          + "o.reported_by_identifier_type, o.reported_by_identifier, o.observed_at, o.received_at, "
          + "o.source_valid_until, o.valid_until, o.policy_version, o.future_skewed, o.reason_code, "
          + "c.superseded_observation_id";

  private final JdbcTemplate jdbc;

  public JdbcCapacityObservationStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void lockSourceIdentity(String sourceCode, ExternalReference sourceObservation) {
    jdbc.query(
        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
        result -> {
          while (result.next()) {
            // Consume the transaction-scoped advisory lock result.
          }
        },
        sourceLockKey(sourceCode, sourceObservation));
  }

  @Override
  public Optional<CapacityObservation> findBySourceIdentity(
      String sourceCode, ExternalReference sourceObservation) {
    List<CapacityObservation> observations =
        jdbc.query(
            "SELECT "
                + OBSERVATION_COLUMNS
                + " FROM capacity.capacity_observation o LEFT JOIN capacity.observation_correction c "
                + "ON c.correcting_observation_id = o.observation_id "
                + "WHERE o.source_code = ? AND o.source_issuer = ? "
                + "AND o.source_identifier_type = ? AND o.source_identifier = ?",
            JdbcCapacityObservationStore::observation,
            sourceCode,
            sourceObservation.issuer().value(),
            sourceObservation.resourceType(),
            sourceObservation.value());
    return observations.stream().findFirst();
  }

  @Override
  public Optional<CapacityObservation> lockObservation(InternalId observationId) {
    List<CapacityObservation> observations =
        jdbc.query(
            "SELECT "
                + OBSERVATION_COLUMNS
                + " FROM capacity.capacity_observation o LEFT JOIN capacity.observation_correction c "
                + "ON c.correcting_observation_id = o.observation_id "
                + "WHERE o.observation_id = ? FOR UPDATE OF o",
            JdbcCapacityObservationStore::observation,
            observationId.value());
    return observations.stream().findFirst();
  }

  @Override
  public boolean isSuperseded(InternalId observationId) {
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM capacity.observation_correction WHERE superseded_observation_id = ?",
            Integer.class,
            observationId.value());
    return count != null && count > 0;
  }

  @Override
  public void append(CapacityObservation observation) {
    insertObservation(observation);
  }

  @Override
  public void appendCorrection(
      CapacityObservation observation, InternalId supersededObservationId, String correctionKind) {
    insertObservation(observation);
    jdbc.update(
        "INSERT INTO capacity.observation_correction (correcting_observation_id, "
            + "superseded_observation_id, correction_kind, reason_code) VALUES (?, ?, ?, ?)",
        observation.id().value(),
        supersededObservationId.value(),
        correctionKind,
        observation.reasonCode());
  }

  private void insertObservation(CapacityObservation observation) {
    jdbc.update(
        "INSERT INTO capacity.capacity_observation (observation_id, facility_issuer, "
            + "facility_identifier_type, facility_identifier, dimension_code, dimension_version, "
            + "observation_kind, value_kind, quantity, state_code, unit_code, source_code, "
            + "source_issuer, source_identifier_type, source_identifier, reporting_method, "
            + "reported_by_kind, reported_by_issuer, reported_by_identifier_type, "
            + "reported_by_identifier, observed_at, received_at, source_valid_until, valid_until, "
            + "policy_version, future_skewed, reason_code) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        observation.id().value(),
        observation.facility().issuer().value(),
        observation.facility().resourceType(),
        observation.facility().value(),
        observation.dimensionCode(),
        observation.dimensionVersion(),
        observation.kind().name(),
        observation.valueKind().map(Enum::name).orElse(null),
        observation.quantity().orElse(null),
        observation.state().orElse(null),
        observation.unit().orElse(null),
        observation.sourceCode(),
        observation.sourceObservation().issuer().value(),
        observation.sourceObservation().resourceType(),
        observation.sourceObservation().value(),
        observation.reportingMethod().name(),
        observation.reportedBy().kind().name(),
        observation.reportedBy().identity().issuer().value(),
        observation.reportedBy().identity().resourceType(),
        observation.reportedBy().identity().value(),
        Timestamp.from(observation.observedAt()),
        Timestamp.from(observation.receivedAt()),
        observation.sourceValidUntil().map(Timestamp::from).orElse(null),
        observation.validUntil().map(Timestamp::from).orElse(null),
        observation.policyVersion(),
        observation.futureSkewed(),
        observation.reasonCode());
  }

  private static CapacityObservation observation(ResultSet result, int row) throws SQLException {
    Timestamp sourceValidUntil = result.getTimestamp("source_valid_until");
    Timestamp validUntil = result.getTimestamp("valid_until");
    String valueKind = result.getString("value_kind");
    BigDecimal quantity = result.getBigDecimal("quantity");
    String state = result.getString("state_code");
    String unit = result.getString("unit_code");
    UUID supersededId = result.getObject("superseded_observation_id", UUID.class);
    return new CapacityObservation(
        new InternalId(result.getObject("observation_id", UUID.class)),
        external(result, "facility_issuer", "facility_identifier_type", "facility_identifier"),
        result.getString("dimension_code"),
        result.getLong("dimension_version"),
        CapacityObservationKind.valueOf(result.getString("observation_kind")),
        Optional.ofNullable(valueKind).map(CapacityValueKind::valueOf),
        Optional.ofNullable(quantity),
        Optional.ofNullable(state),
        Optional.ofNullable(unit),
        result.getString("source_code"),
        external(result, "source_issuer", "source_identifier_type", "source_identifier"),
        CapacityReportingMethod.valueOf(result.getString("reporting_method")),
        new ActorReference(
            ActorKind.valueOf(result.getString("reported_by_kind")),
            new ExternalReference(
                new IssuerId(result.getString("reported_by_issuer")),
                result.getString("reported_by_identifier_type"),
                result.getString("reported_by_identifier"))),
        result.getTimestamp("observed_at").toInstant(),
        result.getTimestamp("received_at").toInstant(),
        Optional.ofNullable(sourceValidUntil).map(Timestamp::toInstant),
        Optional.ofNullable(validUntil).map(Timestamp::toInstant),
        result.getLong("policy_version"),
        result.getBoolean("future_skewed"),
        result.getString("reason_code"),
        Optional.ofNullable(supersededId).map(InternalId::new));
  }

  private static ExternalReference external(
      ResultSet result, String issuerColumn, String typeColumn, String valueColumn)
      throws SQLException {
    return new ExternalReference(
        new IssuerId(result.getString(issuerColumn)),
        result.getString(typeColumn),
        result.getString(valueColumn));
  }

  private static String sourceLockKey(String sourceCode, ExternalReference sourceObservation) {
    return token(sourceCode)
        + token(sourceObservation.issuer().value())
        + token(sourceObservation.resourceType())
        + token(sourceObservation.value());
  }

  private static String token(String value) {
    return value.length() + ":" + value;
  }
}
