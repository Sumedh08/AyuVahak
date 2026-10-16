package org.ayuvahak.transport.adapter.transfer;

import java.util.Objects;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.transfer.application.TransferDestinationChangeEligibilityQuery;
import org.ayuvahak.transport.application.TransferDestinationChangeEligibility;
import org.ayuvahak.transport.application.TransferDestinationChangeEligibilityPort;
import org.springframework.stereotype.Component;

/** Adapter translating Transfer's owner-owned eligibility answer to Transport's contract. */
@Component
public final class TransferDestinationChangeEligibilityAdapter
    implements TransferDestinationChangeEligibilityPort {

  private final TransferDestinationChangeEligibilityQuery query;

  public TransferDestinationChangeEligibilityAdapter(
      TransferDestinationChangeEligibilityQuery query) {
    this.query = Objects.requireNonNull(query, "Transfer destination-change eligibility query");
  }

  @Override
  public TransferDestinationChangeEligibility check(
      ExecutionContext serviceContext,
      InternalId transferId,
      InternalId transportRequestId,
      InternalId destinationChangeId,
      ExternalReference oldDestination,
      ExternalReference proposedDestination) {
    var result =
        query.check(
            serviceContext,
            transferId,
            transportRequestId,
            destinationChangeId,
            oldDestination,
            proposedDestination);
    return new TransferDestinationChangeEligibility(
        TransferDestinationChangeEligibility.Outcome.valueOf(result.outcome().name()),
        result.reasonCode());
  }
}
