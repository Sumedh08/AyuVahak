package org.ayuvahak.transfer.adapter.transport;

import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.transfer.application.TransferTransportRequestEligibilityQuery;
import org.ayuvahak.transport.application.TransferRequestEligibilityPort;
import org.ayuvahak.transport.application.TransportRequestEligibility;
import org.springframework.stereotype.Component;

/** Exposes only Transfer's bounded eligibility answer to the Transport owner. */
@Component
public final class TransferRequestEligibilityAdapter implements TransferRequestEligibilityPort {

  private final TransferTransportRequestEligibilityQuery query;

  public TransferRequestEligibilityAdapter(TransferTransportRequestEligibilityQuery query) {
    this.query = java.util.Objects.requireNonNull(query, "Transfer eligibility query");
  }

  @Override
  public TransportRequestEligibility check(
      ExecutionContext serviceContext, InternalId transferId, ExternalReference requestedOrigin) {
    var result = query.check(serviceContext, transferId, requestedOrigin);
    return new TransportRequestEligibility(
        TransportRequestEligibility.Outcome.valueOf(result.outcome().name()),
        result.originFacility(),
        result.destinationFacility(),
        result.reasonCode());
  }
}
