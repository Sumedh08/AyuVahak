package org.ayuvahak.transport.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class TransportArrivalChronologyMigrationIT {

  private static final String TEST_PASSWORD = "local-test-only";

  @Container
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
              DockerImageName.parse(
                      "postgis/postgis:18-3.6@sha256:60f6ad1d21ea86a67d47780b9a0d1e1d200500f62b19293fa834d0dea80b8677")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("ayuvahak")
          .withUsername("ayuvahak")
          .withPassword(TEST_PASSWORD);

  @Test
  void migrationCopiesOnlyMatchingNonFutureArrivalEvidenceIntoTransportOwnership() {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    createVersion61Fixture(jdbc);

    UUID requestId = UUID.randomUUID();
    UUID missingEventRequestId = UUID.randomUUID();
    UUID duplicateEventRequestId = UUID.randomUUID();
    UUID malformedDuplicateRequestId = UUID.randomUUID();
    UUID arrivalId = UUID.randomUUID();
    UUID missingEventArrivalId = UUID.randomUUID();
    UUID duplicateEventArrivalId = UUID.randomUUID();
    UUID malformedDuplicateArrivalId = UUID.randomUUID();
    UUID futureSkewedArrivalId = UUID.randomUUID();
    UUID destinationId = UUID.randomUUID();
    jdbc.update("INSERT INTO transport.transport_request VALUES (?)", requestId);
    jdbc.update("INSERT INTO transport.transport_request VALUES (?)", missingEventRequestId);
    jdbc.update("INSERT INTO transport.transport_request VALUES (?)", duplicateEventRequestId);
    jdbc.update("INSERT INTO transport.transport_request VALUES (?)", malformedDuplicateRequestId);
    insertArrival(jdbc, requestId, arrivalId, "APPLIED");
    insertArrival(jdbc, missingEventRequestId, missingEventArrivalId, "APPLIED");
    insertArrival(jdbc, duplicateEventRequestId, duplicateEventArrivalId, "APPLIED");
    insertArrival(jdbc, malformedDuplicateRequestId, malformedDuplicateArrivalId, "APPLIED");
    insertArrival(jdbc, requestId, futureSkewedArrivalId, "FUTURE_SKEWED");
    insertArrivalEvent(jdbc, requestId, arrivalId, destinationId);
    insertArrivalEvent(jdbc, duplicateEventRequestId, duplicateEventArrivalId, destinationId);
    insertArrivalEvent(jdbc, duplicateEventRequestId, duplicateEventArrivalId, destinationId);
    insertArrivalEvent(
        jdbc, malformedDuplicateRequestId, malformedDuplicateArrivalId, destinationId);
    insertArrivalEvent(
        jdbc, malformedDuplicateRequestId, malformedDuplicateArrivalId, "not-a-uuid");
    insertArrivalEvent(jdbc, requestId, futureSkewedArrivalId, UUID.randomUUID());

    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .schemas("transport", "platform_eventing")
        .defaultSchema("transport")
        .baselineOnMigrate(true)
        .baselineVersion("61")
        .load()
        .migrate();

    assertThat(
            jdbc.queryForObject(
                "SELECT destination_facility_id FROM transport.status_observation "
                    + "WHERE status_observation_id = ?",
                UUID.class,
                arrivalId))
        .isEqualTo(destinationId);
    assertThat(
            jdbc.queryForObject(
                "SELECT destination_facility_id FROM transport.status_observation "
                    + "WHERE status_observation_id = ?",
                UUID.class,
                futureSkewedArrivalId))
        .isNull();
    assertThat(destinationFor(jdbc, missingEventArrivalId)).isNull();
    assertThat(destinationFor(jdbc, duplicateEventArrivalId)).isNull();
    assertThat(destinationFor(jdbc, malformedDuplicateArrivalId)).isNull();
    assertThat(chronologyResolvedFor(jdbc, arrivalId)).isTrue();
    assertThat(chronologyResolvedFor(jdbc, missingEventArrivalId)).isFalse();
    assertThat(chronologyResolvedFor(jdbc, duplicateEventArrivalId)).isFalse();
    assertThat(chronologyResolvedFor(jdbc, malformedDuplicateArrivalId)).isFalse();

    JdbcTransportEvidenceStore evidenceStore = new JdbcTransportEvidenceStore(jdbc);
    ExternalReference oldDestination = facility(destinationId);
    Instant cutoff = Instant.now().plusSeconds(60);
    assertThat(
            evidenceStore.hasArrivalAtOrBefore(
                new InternalId(missingEventRequestId), oldDestination, cutoff))
        .as("an arrival whose event was purged must remain a fail-closed chronology signal")
        .isTrue();
    assertThat(
            evidenceStore.hasArrivalAtOrBefore(
                new InternalId(duplicateEventRequestId), oldDestination, cutoff))
        .as("ambiguous duplicate arrival events must remain a fail-closed chronology signal")
        .isTrue();
    assertThat(
            evidenceStore.hasArrivalAtOrBefore(
                new InternalId(malformedDuplicateRequestId), oldDestination, cutoff))
        .as("a malformed duplicate must not make a valid historical event appear unique")
        .isTrue();
    assertThat(
            evidenceStore.hasArrivalAtOrBefore(
                new InternalId(requestId), facility(UUID.randomUUID()), cutoff))
        .as("a fully resolved arrival to another facility is not an arrival to this facility")
        .isFalse();
    assertThat(
            Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas("transport", "platform_eventing")
                .defaultSchema("transport")
                .load()
                .info()
                .current()
                .getVersion()
                .getVersion())
        .isEqualTo("62");
  }

  private static UUID destinationFor(JdbcTemplate jdbc, UUID observationId) {
    return jdbc.queryForObject(
        "SELECT destination_facility_id FROM transport.status_observation "
            + "WHERE status_observation_id = ?",
        UUID.class,
        observationId);
  }

  private static boolean chronologyResolvedFor(JdbcTemplate jdbc, UUID observationId) {
    return jdbc.queryForObject(
        "SELECT destination_chronology_resolved FROM transport.status_observation "
            + "WHERE status_observation_id = ?",
        Boolean.class,
        observationId);
  }

  private static ExternalReference facility(UUID id) {
    return new ExternalReference(
        new IssuerId("https://directory.synthetic.test"), "Facility", id.toString());
  }

  private static void createVersion61Fixture(JdbcTemplate jdbc) {
    jdbc.execute("CREATE SCHEMA transport");
    jdbc.execute("CREATE SCHEMA platform_eventing");
    jdbc.execute(
        "CREATE TABLE transport.transport_request (transport_request_id uuid PRIMARY KEY)");
    jdbc.execute(
        "CREATE TABLE transport.status_observation ("
            + "status_observation_id uuid PRIMARY KEY, "
            + "transport_request_id uuid NOT NULL REFERENCES transport.transport_request, "
            + "status_code varchar(32) NOT NULL, "
            + "observation_outcome varchar(32) NOT NULL, "
            + "occurred_at timestamptz NOT NULL)");
    jdbc.execute(
        "CREATE TABLE platform_eventing.internal_event ("
            + "event_id uuid PRIMARY KEY, "
            + "event_type varchar(128) NOT NULL, "
            + "producer_context varchar(63) NOT NULL, "
            + "aggregate_ref varchar(512), "
            + "envelope jsonb NOT NULL)");
  }

  private static void insertArrival(
      JdbcTemplate jdbc, UUID requestId, UUID observationId, String outcome) {
    jdbc.update(
        "INSERT INTO transport.status_observation "
            + "(status_observation_id, transport_request_id, status_code, observation_outcome, occurred_at) "
            + "VALUES (?, ?, 'ARRIVED_DESTINATION', ?, now())",
        observationId,
        requestId,
        outcome);
  }

  private static void insertArrivalEvent(
      JdbcTemplate jdbc, UUID requestId, UUID observationId, UUID destinationId) {
    insertArrivalEvent(jdbc, requestId, observationId, destinationId.toString());
  }

  private static void insertArrivalEvent(
      JdbcTemplate jdbc, UUID requestId, UUID observationId, String destinationReference) {
    jdbc.update(
        "INSERT INTO platform_eventing.internal_event "
            + "(event_id, event_type, producer_context, aggregate_ref, envelope) "
            + "VALUES (?, 'transport.arrival-reported.v1', 'transport', ?, ?::jsonb)",
        UUID.randomUUID(),
        "transport-request/" + requestId,
        "{\"payload\":{\"evidenceReference\":\""
            + observationId
            + "\",\"transportRequestReference\":\""
            + requestId
            + "\",\"destinationFacilityReference\":\""
            + destinationReference
            + "\"}}");
  }
}
