package org.ayuvahak.matching.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.ayuvahak.matching.application.RemoteCapacityEvidenceProjectionStore;
import org.ayuvahak.matching.domain.RemoteCapacityEvidence;
import org.ayuvahak.matching.domain.RemoteCapacityOutcome;
import org.ayuvahak.matching.domain.RemoteEvidenceFreshness;
import org.ayuvahak.matching.domain.RemoteFacilityReference;
import org.ayuvahak.platform.AyuVahakApplication;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
    classes = AyuVahakApplication.class,
    properties = {
      "ayuvahak.authentication.issuer=https://identity.example.test/realms/test",
      "ayuvahak.authentication.jwk-set-uri=http://127.0.0.1:9/jwks",
      "ayuvahak.authentication.audience=ayuvahak-api",
      "ayuvahak.authentication.service-client-ids=synthetic-worker",
      "ayuvahak.authentication.allow-http=true",
      "ayuvahak.eventing.outbox.dispatcher-enabled=false"
    })
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RemoteCapacityEvidenceProjectionStoreTest {

  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
              DockerImageName.parse(
                      "postgis/postgis:18-3.6@sha256:60f6ad1d21ea86a67d47780b9a0d1e1d200500f62b19293fa834d0dea80b8677")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("ayuvahak")
          .withUsername("ayuvahak")
          .withPassword("local-synthetic-only");

  @Container private static final PostgreSQLContainer CONTAINER = POSTGRES;

  private static final RemoteFacilityReference FACILITY =
      new RemoteFacilityReference(
          new DeploymentId("SYN-DEPLOYMENT-B"), "directory", InternalId.generate());

  @Autowired private JdbcTemplate jdbc;
  @Autowired private RemoteCapacityEvidenceProjectionStore store;

  @DynamicPropertySource
  static void localDatabase(DynamicPropertyRegistry registry) {
    registry.add("AYUVAHAK_DATABASE_URL", POSTGRES::getJdbcUrl);
    registry.add("AYUVAHAK_DATABASE_USERNAME", POSTGRES::getUsername);
    registry.add("AYUVAHAK_DATABASE_PASSWORD", POSTGRES::getPassword);
  }

  @BeforeEach
  void clearSyntheticProjection() {
    jdbc.update(
        "DELETE FROM matching.remote_capacity_evidence_projection "
            + "WHERE remote_deployment_id = ? AND remote_facility_id = ?",
        FACILITY.deploymentId().value(),
        FACILITY.resourceId().value());
  }

  @Test
  void sourceSequenceWinsOverArrivalAndSourceWallClockOrder() {
    RemoteCapacityEvidence newest = evidence(22, "SYN-EVIDENCE-022", "4", "2026-10-18T09:50:00Z");
    RemoteCapacityEvidence olderButLaterObserved =
        evidence(21, "SYN-EVIDENCE-021", "9", "2026-10-18T10:04:00Z");

    assertThat(store.record(newest))
        .isEqualTo(RemoteCapacityEvidenceProjectionStore.RecordResult.APPLIED);
    assertThat(store.record(olderButLaterObserved))
        .isEqualTo(RemoteCapacityEvidenceProjectionStore.RecordResult.STALE_IGNORED);

    assertThat(store.find(FACILITY, "OXYGEN_STATIONS"))
        .get()
        .extracting(RemoteCapacityEvidence::sourceSequence)
        .isEqualTo(22L);
    assertThat(store.find(FACILITY, "OXYGEN_STATIONS").orElseThrow().quantity())
        .hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo("4"));
  }

  @Test
  void exactDuplicateIsIdempotentButChangedPayloadAtSameSequenceConflicts() {
    RemoteCapacityEvidence initial = evidence(23, "SYN-EVIDENCE-023", "4", "2026-10-18T09:59:30Z");
    RemoteCapacityEvidence duplicateWithLaterReceipt =
        new RemoteCapacityEvidence(
            FACILITY,
            initial.dimensionCode(),
            initial.sourceSequence(),
            initial.dimensionVersion(),
            initial.policyVersion(),
            initial.sourceProfileVersion(),
            initial.evidenceId(),
            initial.outcome(),
            initial.freshness(),
            initial.complete(),
            initial.quantity(),
            initial.state(),
            initial.unit(),
            initial.observedAt(),
            initial.receivedAt().plusSeconds(20),
            initial.validUntil(),
            false);
    RemoteCapacityEvidence changedAtSameSequence =
        evidence(23, "SYN-EVIDENCE-023", "6", "2026-10-18T09:59:30Z");

    assertThat(store.record(initial))
        .isEqualTo(RemoteCapacityEvidenceProjectionStore.RecordResult.APPLIED);
    assertThat(store.record(duplicateWithLaterReceipt))
        .isEqualTo(RemoteCapacityEvidenceProjectionStore.RecordResult.DUPLICATE);
    assertThat(store.record(changedAtSameSequence))
        .isEqualTo(RemoteCapacityEvidenceProjectionStore.RecordResult.CONFLICT);

    RemoteCapacityEvidence conflicted = store.find(FACILITY, "OXYGEN_STATIONS").orElseThrow();
    assertThat(conflicted.sourceConflict()).isTrue();
    assertThat(conflicted.quantity())
        .hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo("4"));
  }

  @Test
  void aNewerSourceSequenceResolvesPriorConflictAndProjectionSurvivesStoreReconstruction() {
    store.record(evidence(24, "SYN-EVIDENCE-024", "4", "2026-10-18T09:59:30Z"));
    store.record(evidence(24, "SYN-EVIDENCE-024", "5", "2026-10-18T09:59:30Z"));
    store.record(evidence(25, "SYN-EVIDENCE-025", "7", "2026-10-18T09:58:00Z"));

    RemoteCapacityEvidenceProjectionStore reconstructed =
        new JdbcRemoteCapacityEvidenceProjectionStore(jdbc);
    Optional<RemoteCapacityEvidence> recovered = reconstructed.find(FACILITY, "OXYGEN_STATIONS");

    assertThat(recovered).isPresent();
    assertThat(recovered.orElseThrow().sourceSequence()).isEqualTo(25);
    assertThat(recovered.orElseThrow().sourceConflict()).isFalse();
    assertThat(recovered.orElseThrow().quantity())
        .hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo("7"));
  }

  @Test
  void concurrentOutOfOrderReceiptsConvergeOnHighestSourceSequence() throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      CompletableFuture<RemoteCapacityEvidenceProjectionStore.RecordResult> older =
          CompletableFuture.supplyAsync(
              () -> recordWhenReleased(ready, start, evidence(31, "SYN-EVIDENCE-031", "3")),
              executor);
      CompletableFuture<RemoteCapacityEvidenceProjectionStore.RecordResult> newer =
          CompletableFuture.supplyAsync(
              () -> recordWhenReleased(ready, start, evidence(32, "SYN-EVIDENCE-032", "8")),
              executor);

      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      assertThat(newer.get(10, TimeUnit.SECONDS))
          .isEqualTo(RemoteCapacityEvidenceProjectionStore.RecordResult.APPLIED);
      assertThat(older.get(10, TimeUnit.SECONDS))
          .isIn(
              RemoteCapacityEvidenceProjectionStore.RecordResult.APPLIED,
              RemoteCapacityEvidenceProjectionStore.RecordResult.STALE_IGNORED);
    }

    RemoteCapacityEvidence finalEvidence = store.find(FACILITY, "OXYGEN_STATIONS").orElseThrow();
    assertThat(finalEvidence.sourceSequence()).isEqualTo(32);
    assertThat(finalEvidence.quantity())
        .hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo("8"));
    assertThat(finalEvidence.sourceConflict()).isFalse();
  }

  private RemoteCapacityEvidenceProjectionStore.RecordResult recordWhenReleased(
      CountDownLatch ready, CountDownLatch start, RemoteCapacityEvidence evidence) {
    ready.countDown();
    try {
      if (!start.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("concurrency test did not release both writers");
      }
      return store.record(evidence);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("concurrent evidence write interrupted", interrupted);
    }
  }

  private static RemoteCapacityEvidence evidence(
      long sourceSequence, String evidenceId, String quantity, String observedAt) {
    return new RemoteCapacityEvidence(
        FACILITY,
        "OXYGEN_STATIONS",
        sourceSequence,
        4,
        2,
        "SYN-CAPACITY-PROFILE-1",
        evidenceId,
        RemoteCapacityOutcome.READY,
        RemoteEvidenceFreshness.CURRENT,
        true,
        Optional.of(new BigDecimal(quantity)),
        Optional.empty(),
        Optional.empty(),
        Instant.parse(observedAt),
        Instant.parse("2026-10-18T10:00:00Z"),
        Instant.parse("2026-10-18T10:05:00Z"),
        false);
  }

  private static RemoteCapacityEvidence evidence(
      long sourceSequence, String evidenceId, String quantity) {
    return evidence(sourceSequence, evidenceId, quantity, "2026-10-18T09:59:30Z");
  }
}
