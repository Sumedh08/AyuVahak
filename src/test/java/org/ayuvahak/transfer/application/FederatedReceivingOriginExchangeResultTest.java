package org.ayuvahak.transfer.application;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Optional;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.transfer.domain.FederatedOriginReceivingAttempt.AssessmentOutcome;
import org.ayuvahak.transfer.domain.FederatedOriginReceivingAttempt.RemoteAssessmentEvidence;
import org.ayuvahak.transfer.domain.FederatedOriginReceivingAttempt.RemoteOutcome;
import org.ayuvahak.transfer.domain.FederatedOriginReceivingAttempt.RemoteReservationStatus;
import org.junit.jupiter.api.Test;

class FederatedReceivingOriginExchangeResultTest {

  private static final Instant NOW = Instant.parse("2026-10-18T00:00:00Z");

  @Test
  void acceptedResultRequiresBothPositiveReceivingAssessments() {
    RemoteAssessmentEvidence supports = assessment(AssessmentOutcome.SUPPORTS);
    RemoteAssessmentEvidence indeterminate = assessment(AssessmentOutcome.INDETERMINATE);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FederatedReceivingOriginExchangeResult(
                RemoteOutcome.ACCEPTED_RESERVED,
                RemoteReservationStatus.RESERVED,
                InternalId.generate(),
                Optional.of(InternalId.generate()),
                Optional.of(supports),
                Optional.of(indeterminate),
                Optional.of(NOW),
                NOW,
                2));
  }

  @Test
  void acceptedResultCannotOmitReceivingAssessmentEvidence() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FederatedReceivingOriginExchangeResult(
                RemoteOutcome.ACCEPTED_RESERVED,
                RemoteReservationStatus.RESERVED,
                InternalId.generate(),
                Optional.of(InternalId.generate()),
                Optional.empty(),
                Optional.empty(),
                Optional.of(NOW),
                NOW,
                2));
  }

  private static RemoteAssessmentEvidence assessment(AssessmentOutcome outcome) {
    return new RemoteAssessmentEvidence(outcome, "SYNTHETIC_REVIEW", NOW, NOW);
  }
}
