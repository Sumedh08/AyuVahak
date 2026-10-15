package org.ayuvahak.transport.application;

import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;

/** Transfer-owned current-state check invoked before Transport records a request. */
public interface TransferRequestEligibilityPort {

  TransportRequestEligibility check(
      ExecutionContext serviceContext, InternalId transferId, ExternalReference requestedOrigin);
}
