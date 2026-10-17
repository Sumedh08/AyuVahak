package org.ayuvahak.transfer.application;

import java.util.Objects;
import org.ayuvahak.transfer.domain.FederatedReceivingRequest;

/** Result of receiving or replaying one peer-scoped receiving operation. */
public record FederatedReceivingRequestResult(FederatedReceivingRequest request, boolean replayed) {
  public FederatedReceivingRequestResult {
    Objects.requireNonNull(request, "federated receiving request");
  }
}
