package org.ayuvahak.matching.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.junit.jupiter.api.Test;

class RemoteCapacityEvidenceProjectorTest {

  private static final Instant RECEIVED_AT = Instant.parse("2026-10-18T10:00:00Z");
  private static final Instant OBSERVED_AT = Instant.parse("2026-10-18T09:59:30Z");
  private static final Instant VALID_UNTIL = Instant.parse("2026-10-18T10:05:00Z");
  private static final RemoteFacilityReference FACILITY =
      new RemoteFacilityReference(
          new DeploymentId("SYN-DEPLOYMENT-B"), "directory", InternalId.generate());
  private static final RemoteCapacityEvidenceCompatibility COMPATIBILITY =
      new RemoteCapacityEvidenceCompatibility("SYN-CAPACITY-PROFILE-1", 4, 2);
  private static final RemoteCapacityEvidence CURRENT_READY =
      evidence(
          21,
          RemoteCapacityOutcome.READY,
          RemoteEvidenceFreshness.CURRENT,
          true,
          VALID_UNTIL,
          "SYN-EVIDENCE-001");

  private final RemoteCapacityEvidenceProjector projector = new RemoteCapacityEvidenceProjector();

  @Test
  void keepsAnExplicitDeploymentDirectoryAndResourceNamespace() {
    assertThat(FACILITY.deploymentId().value()).isEqualTo("SYN-DEPLOYMENT-B");
    assertThat(FACILITY.context()).isEqualTo("directory");
    assertThat(FACILITY.resourceId()).isNotNull();
    assertThat(FACILITY.toExternalReference().issuer().value())
        .isEqualTo("urn:ayuvahak:deployment:SYN-DEPLOYMENT-B:directory");
  }

  @Test
  void onlyFreshCompleteCompatibleOwnerEvidenceCanBeReady() {
    RemoteCapacityReadiness projected =
        projector.project(
            CURRENT_READY, FACILITY, "OXYGEN_STATIONS", COMPATIBILITY, RECEIVED_AT.plusSeconds(10));

    assertThat(projected.facility()).isEqualTo(FACILITY);
    assertThat(projected.outcome()).isEqualTo(RemoteCapacityOutcome.READY);
    assertThat(projected.freshness()).isEqualTo(RemoteEvidenceFreshness.CURRENT);
    assertThat(projected.sourceSequence()).isEqualTo(21);
    assertThat(projected.evidenceId()).contains("SYN-EVIDENCE-001");
    assertThat(projected.quantity()).contains(new BigDecimal("4"));
    assertThat(projected.receivedAt()).isEqualTo(RECEIVED_AT);
  }

  @Test
  void expirationBecomesStaleAndCannotCarryAUsableValue() {
    RemoteCapacityReadiness projected =
        projector.project(CURRENT_READY, FACILITY, "OXYGEN_STATIONS", COMPATIBILITY, VALID_UNTIL);

    assertThat(projected.outcome()).isEqualTo(RemoteCapacityOutcome.STALE);
    assertThat(projected.freshness()).isEqualTo(RemoteEvidenceFreshness.EXPIRED);
    assertThat(projected.quantity()).isEmpty();
    assertThat(projected.evidenceId()).isEmpty();
  }

  @Test
  void incompatibleProfileOrPolicyBecomesUnknownWithoutValue() {
    RemoteCapacityEvidenceCompatibility wrongPolicy =
        new RemoteCapacityEvidenceCompatibility("SYN-CAPACITY-PROFILE-1", 5, 2);

    RemoteCapacityReadiness projected =
        projector.project(
            CURRENT_READY, FACILITY, "OXYGEN_STATIONS", wrongPolicy, RECEIVED_AT.plusSeconds(1));

    assertThat(projected.outcome()).isEqualTo(RemoteCapacityOutcome.UNKNOWN);
    assertThat(projected.freshness()).isEqualTo(RemoteEvidenceFreshness.UNKNOWN);
    assertThat(projected.quantity()).isEmpty();
    assertThat(projected.reasonCodes()).contains("INCOMPATIBLE_SOURCE_POLICY");
  }

  @Test
  void incompleteOrUnknownSourceEvidenceCannotBecomeReadyOrZero() {
    RemoteCapacityEvidence incomplete =
        evidence(
            22,
            RemoteCapacityOutcome.READY,
            RemoteEvidenceFreshness.CURRENT,
            false,
            VALID_UNTIL,
            "SYN-EVIDENCE-002");

    RemoteCapacityReadiness projected =
        projector.project(
            incomplete, FACILITY, "OXYGEN_STATIONS", COMPATIBILITY, RECEIVED_AT.plusSeconds(1));

    assertThat(projected.outcome()).isEqualTo(RemoteCapacityOutcome.UNKNOWN);
    assertThat(projected.quantity()).isEmpty();
    assertThat(projected.reasonCodes()).contains("INCOMPLETE_SOURCE_RESPONSE");
  }

  @Test
  void readyOwnerStatusWithUnknownFreshnessCannotBecomeReady() {
    RemoteCapacityEvidence ownerUnknown =
        evidence(
            23,
            RemoteCapacityOutcome.READY,
            RemoteEvidenceFreshness.UNKNOWN,
            true,
            VALID_UNTIL,
            "SYN-EVIDENCE-003");

    RemoteCapacityReadiness projected =
        projector.project(
            ownerUnknown, FACILITY, "OXYGEN_STATIONS", COMPATIBILITY, RECEIVED_AT.plusSeconds(1));

    assertThat(projected.outcome()).isEqualTo(RemoteCapacityOutcome.UNKNOWN);
    assertThat(projected.quantity()).isEmpty();
  }

  @Test
  void currentNotReadyEvidenceRemainsNotReadyAndDoesNotExposeCapacityValues() {
    RemoteCapacityEvidence ownerNotReady =
        evidence(
            23,
            RemoteCapacityOutcome.NOT_READY,
            RemoteEvidenceFreshness.CURRENT,
            true,
            VALID_UNTIL,
            "SYN-EVIDENCE-003");

    RemoteCapacityReadiness projected =
        projector.project(
            ownerNotReady, FACILITY, "OXYGEN_STATIONS", COMPATIBILITY, RECEIVED_AT.plusSeconds(1));

    assertThat(projected.outcome()).isEqualTo(RemoteCapacityOutcome.NOT_READY);
    assertThat(projected.freshness()).isEqualTo(RemoteEvidenceFreshness.CURRENT);
    assertThat(projected.evidenceId()).contains("SYN-EVIDENCE-003");
    assertThat(projected.quantity()).isEmpty();
  }

  @Test
  void conflictingSourceSequenceIsNotUsable() {
    RemoteCapacityEvidence conflicted = CURRENT_READY.withSourceConflict(true);

    RemoteCapacityReadiness projected =
        projector.project(
            conflicted, FACILITY, "OXYGEN_STATIONS", COMPATIBILITY, RECEIVED_AT.plusSeconds(1));

    assertThat(projected.outcome()).isEqualTo(RemoteCapacityOutcome.CONFLICTED);
    assertThat(projected.freshness()).isEqualTo(RemoteEvidenceFreshness.CONFLICTED);
    assertThat(projected.quantity()).isEmpty();
  }

  @Test
  void evidenceForAnotherRemoteFacilityOrDimensionIsRejected() {
    assertThatThrownBy(
            () ->
                projector.project(
                    CURRENT_READY,
                    new RemoteFacilityReference(
                        new DeploymentId("SYN-DEPLOYMENT-C"), "directory", FACILITY.resourceId()),
                    "OXYGEN_STATIONS",
                    COMPATIBILITY,
                    RECEIVED_AT.plusSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                projector.project(
                    CURRENT_READY, FACILITY, "ICU_BEDS", COMPATIBILITY, RECEIVED_AT.plusSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void sourceSequenceMustBePositiveAndTimestampsMustBeCoherent() {
    assertThatThrownBy(
            () ->
                new RemoteCapacityEvidence(
                    FACILITY,
                    "OXYGEN_STATIONS",
                    0,
                    4,
                    2,
                    "SYN-CAPACITY-PROFILE-1",
                    "SYN-EVIDENCE-004",
                    RemoteCapacityOutcome.READY,
                    RemoteEvidenceFreshness.CURRENT,
                    true,
                    Optional.of(new BigDecimal("4")),
                    Optional.empty(),
                    Optional.empty(),
                    OBSERVED_AT,
                    RECEIVED_AT,
                    VALID_UNTIL,
                    false))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                evidence(
                    24,
                    RemoteCapacityOutcome.READY,
                    RemoteEvidenceFreshness.CURRENT,
                    true,
                    OBSERVED_AT,
                    "SYN-EVIDENCE-005"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static RemoteCapacityEvidence evidence(
      long sourceSequence,
      RemoteCapacityOutcome outcome,
      RemoteEvidenceFreshness freshness,
      boolean complete,
      Instant validUntil,
      String evidenceId) {
    return new RemoteCapacityEvidence(
        FACILITY,
        "OXYGEN_STATIONS",
        sourceSequence,
        4,
        2,
        "SYN-CAPACITY-PROFILE-1",
        evidenceId,
        outcome,
        freshness,
        complete,
        Optional.of(new BigDecimal("4")),
        Optional.empty(),
        Optional.empty(),
        OBSERVED_AT,
        RECEIVED_AT,
        validUntil,
        false);
  }
}
