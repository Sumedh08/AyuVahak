package org.ayuvahak.directory.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.ayuvahak.platform.AyuVahakApplication;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
    classes = AyuVahakApplication.class,
    properties = {
      "ayuvahak.authentication.issuer=https://identity.example.test/realms/ayuvahak",
      "ayuvahak.authentication.jwk-set-uri=https://identity.example.test/realms/ayuvahak/protocol/openid-connect/certs",
      "ayuvahak.authentication.audience=ayuvahak-api",
      "ayuvahak.authentication.service-client-ids=synthetic-directory-worker"
    })
@Testcontainers
class DirectoryFederatedProjectionRepositoryIT {

  private static final String TEST_PASSWORD = "local-synthetic-only";
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
              DockerImageName.parse(
                      "postgis/postgis:18-3.6@sha256:60f6ad1d21ea86a67d47780b9a0d1e1d200500f62b19293fa834d0dea80b8677")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("ayuvahak")
          .withUsername("ayuvahak")
          .withPassword(TEST_PASSWORD);

  @Container private static final PostgreSQLContainer CONTAINER = POSTGRES;

  private static final DeploymentId SOURCE = new DeploymentId("SYN-DEPLOYMENT-B");
  private static final PartnerKey PARTNER = new PartnerKey("synthetic-directory-peer");
  private static final Instant RECEIVED_AT = Instant.parse("2026-10-18T06:00:00Z");
  private static final Duration FRESHNESS = Duration.ofMinutes(5);
  private static final DirectoryRemoteResourceReference REFERENCE =
      new DirectoryRemoteResourceReference(SOURCE, "Directory", "Facility", "SYN-FACILITY-A");

  @Autowired private DirectoryFederatedProjectionRepository repository;
  @Autowired private JdbcTemplate jdbc;

  @DynamicPropertySource
  static void localDatabase(DynamicPropertyRegistry registry) {
    registry.add("AYUVAHAK_DATABASE_URL", POSTGRES::getJdbcUrl);
    registry.add("AYUVAHAK_DATABASE_USERNAME", POSTGRES::getUsername);
    registry.add("AYUVAHAK_DATABASE_PASSWORD", POSTGRES::getPassword);
  }

  @BeforeEach
  void clearSyntheticProjection() {
    jdbc.update("DELETE FROM directory.federated_remote_projection");
  }

  @Test
  void projectionSurvivesReadAfterReconciliationAndNeverCreatesLocalFacility() {
    var created =
        repository.reconcile(
            fact(1, "Synthetic Facility A", true), PARTNER, 2, RECEIVED_AT, FRESHNESS);

    assertThat(created.outcome()).isEqualTo(DirectoryFederatedProjectionPolicy.Outcome.CREATED);
    assertThat(repository.find(REFERENCE)).contains(created.projection());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM directory.facility WHERE name = ?",
                Integer.class,
                "Synthetic Facility A"))
        .isZero();
  }

  @Test
  void olderSourceVersionCannotOverwriteTheDurableProjection() {
    var created =
        repository.reconcile(
            fact(3, "Synthetic Facility New", true), PARTNER, 2, RECEIVED_AT, FRESHNESS);

    var ignored =
        repository.reconcile(
            fact(2, "Synthetic Facility Old", false),
            PARTNER,
            2,
            RECEIVED_AT.plusSeconds(10),
            FRESHNESS);

    assertThat(ignored.outcome())
        .isEqualTo(DirectoryFederatedProjectionPolicy.Outcome.STALE_IGNORED);
    assertThat(repository.find(REFERENCE)).contains(created.projection());
  }

  @Test
  void sameVersionContradictionIsDurablyConflictedUntilNewerSourceVersion() {
    repository.reconcile(fact(3, "Synthetic Facility A", true), PARTNER, 2, RECEIVED_AT, FRESHNESS);

    var conflicted =
        repository.reconcile(
            fact(3, "Synthetic Facility B", true),
            PARTNER,
            2,
            RECEIVED_AT.plusSeconds(10),
            FRESHNESS);
    var sameVersion =
        repository.reconcile(
            fact(3, "Synthetic Facility A", true),
            PARTNER,
            2,
            RECEIVED_AT.plusSeconds(20),
            FRESHNESS);
    var recovered =
        repository.reconcile(
            fact(4, "Synthetic Facility C", true),
            PARTNER,
            2,
            RECEIVED_AT.plusSeconds(30),
            FRESHNESS);

    assertThat(conflicted.projection().status())
        .isEqualTo(DirectoryFederatedProjection.Status.CONFLICT);
    assertThat(sameVersion.projection().status())
        .isEqualTo(DirectoryFederatedProjection.Status.CONFLICT);
    assertThat(recovered.projection().status())
        .isEqualTo(DirectoryFederatedProjection.Status.CURRENT);
    assertThat(repository.find(REFERENCE)).contains(recovered.projection());
  }

  @Test
  void projectionBecomesStaleAtLocalValidityDeadline() {
    repository.reconcile(fact(3, "Synthetic Facility A", true), PARTNER, 2, RECEIVED_AT, FRESHNESS);

    var persisted = repository.find(REFERENCE).orElseThrow();

    assertThat(persisted.freshnessAt(RECEIVED_AT.plusSeconds(299)))
        .isEqualTo(DirectoryFederatedProjection.Freshness.FRESH);
    assertThat(persisted.freshnessAt(RECEIVED_AT.plusSeconds(300)))
        .isEqualTo(DirectoryFederatedProjection.Freshness.STALE);
  }

  @Test
  void concurrentFirstObservationsResolveThroughSourceVersionConflictPolicy() throws Exception {
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () ->
                  repository.reconcile(
                      fact(3, "Synthetic Facility A", true), PARTNER, 2, RECEIVED_AT, FRESHNESS));
      var second =
          executor.submit(
              () ->
                  repository.reconcile(
                      fact(3, "Synthetic Facility B", true),
                      PARTNER,
                      2,
                      RECEIVED_AT.plusSeconds(1),
                      FRESHNESS));

      first.get(20, TimeUnit.SECONDS);
      second.get(20, TimeUnit.SECONDS);
    }

    assertThat(repository.find(REFERENCE).orElseThrow().status())
        .isEqualTo(DirectoryFederatedProjection.Status.CONFLICT);
  }

  @Test
  void absenceFromPartialObservationDoesNotDeletePriorProjection() {
    var original =
        repository.reconcile(
            fact(3, "Synthetic Facility A", true), PARTNER, 2, RECEIVED_AT, FRESHNESS);

    // A partial page is represented by the facts it actually contains. Reconciliation has no
    // delete-by-absence operation, so unrelated remote identities remain attributable.
    var otherReference =
        new DirectoryRemoteResourceReference(SOURCE, "Directory", "Facility", "SYN-FACILITY-B");
    var otherFact =
        new DirectoryFederatedFact(
            otherReference,
            Optional.of(
                new DirectoryRemoteResourceReference(
                    SOURCE, "Directory", "Organization", "SYN-ORG-001")),
            "Synthetic Facility B",
            true,
            1,
            Optional.of(RECEIVED_AT.minusSeconds(1)),
            "directory-profile-v1");
    repository.reconcilePage(
        List.of(otherFact),
        PARTNER,
        2,
        RECEIVED_AT.plusSeconds(1),
        RECEIVED_AT.plusSeconds(1),
        FRESHNESS);

    assertThat(repository.find(REFERENCE)).contains(original.projection());
    assertThat(
            repository.findByRemoteScope(
                SOURCE,
                new ExternalReference(
                    new IssuerId("urn:synthetic:directory"), "Organization", "SYN-ORG-001")))
        .hasSize(2);
  }

  private static DirectoryFederatedFact fact(long version, String name, boolean active) {
    return new DirectoryFederatedFact(
        REFERENCE,
        Optional.of(
            new DirectoryRemoteResourceReference(
                SOURCE, "Directory", "Organization", "SYN-ORG-001")),
        name,
        active,
        version,
        Optional.of(RECEIVED_AT.minusSeconds(5)),
        "directory-profile-v1");
  }
}
