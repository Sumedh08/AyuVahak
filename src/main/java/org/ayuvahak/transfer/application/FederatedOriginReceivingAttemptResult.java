package org.ayuvahak.transfer.application;

import java.util.Objects;
import org.ayuvahak.transfer.domain.FederatedOriginReceivingAttempt;

/** Owner result for one stable federated destination attempt. */
public record FederatedOriginReceivingAttemptResult(
    FederatedOriginReceivingAttempt attempt, boolean replayed) {

  public FederatedOriginReceivingAttemptResult {
    Objects.requireNonNull(attempt, "origin receiving attempt");
  }
}
