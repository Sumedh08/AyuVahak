package org.ayuvahak.transport.adapter.transfer;

import java.time.Instant;
import java.util.Objects;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.transfer.application.TransportArrivalEvidencePort;
import org.ayuvahak.transport.application.TransportMovementEvidenceQuery;
import org.springframework.stereotype.Component;

/** Transfer-to-Transport adapter for a minimal owner-authorized arrival chronology query. */
@Component
public final class TransferTransportArrivalEvidenceAdapter implements TransportArrivalEvidencePort {

  private final TransportMovementEvidenceQuery query;

  public TransferTransportArrivalEvidenceAdapter(TransportMovementEvidenceQuery query) {
    this.query = Objects.requireNonNull(query, "Transport movement evidence query");
  }

  @Override
  public boolean hasArrivalAtOrBefore(
      ExecutionContext serviceContext,
      InternalId transportRequestId,
      ExternalReference destinationFacility,
      Instant cutoff) {
    return query.hasArrivalAtOrBefore(
        serviceContext, transportRequestId, destinationFacility, cutoff);
  }
}
