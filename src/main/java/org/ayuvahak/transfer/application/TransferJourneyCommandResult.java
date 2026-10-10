package org.ayuvahak.transfer.application;

import java.util.Objects;
import org.ayuvahak.transfer.domain.TransferJourney;
import org.ayuvahak.transfer.domain.TransferRequestAttempt;

/** Safe result of creating or replaying a TransferJourney's first request. */
public record TransferJourneyCommandResult(
    TransferJourney journey, TransferRequestAttempt requestAttempt, boolean commandReplayed) {

  public TransferJourneyCommandResult {
    Objects.requireNonNull(journey, "journey");
    Objects.requireNonNull(requestAttempt, "request attempt");
    if (!journey.id().equals(requestAttempt.journeyId())) {
      throw new IllegalArgumentException("request attempt must belong to the returned journey");
    }
  }
}
