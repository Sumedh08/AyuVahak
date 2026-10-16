package org.ayuvahak.transport.application;

import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;

/** Transfer-owned eligibility contract called before Transport creates or resolves a reroute. */
public interface TransferDestinationChangeEligibilityPort {

  TransferDestinationChangeEligibility check(
      ExecutionContext serviceContext,
      InternalId transferId,
      InternalId transportRequestId,
      InternalId destinationChangeId,
      ExternalReference oldDestination,
      ExternalReference proposedDestination);
}
