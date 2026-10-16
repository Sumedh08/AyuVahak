package org.ayuvahak.transfer.application;

import java.time.Instant;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;

/** Scoped Transport owner query used before applying a destination-change acknowledgement. */
public interface TransportArrivalEvidencePort {

  boolean hasArrivalAtOrBefore(
      ExecutionContext serviceContext,
      InternalId transportRequestId,
      ExternalReference destinationFacility,
      Instant cutoff);
}
