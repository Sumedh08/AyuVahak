package org.ayuvahak.transport.application;

import java.time.Instant;
import java.util.Objects;
import org.ayuvahak.platform.authorization.application.AuthorizationService;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.transport.domain.TransportAuthorities;
import org.springframework.stereotype.Service;

/** Returns only a bounded movement chronology fact to a scoped Transport consumer identity. */
@Service
public final class TransportMovementEvidenceQuery {

  private final TransportRequestStore requests;
  private final TransportEvidenceStore evidence;
  private final AuthorizationService authorization;

  public TransportMovementEvidenceQuery(
      TransportRequestStore requests,
      TransportEvidenceStore evidence,
      AuthorizationService authorization) {
    this.requests = Objects.requireNonNull(requests, "Transport request store");
    this.evidence = Objects.requireNonNull(evidence, "Transport evidence store");
    this.authorization = Objects.requireNonNull(authorization, "authorization service");
  }

  public boolean hasArrivalAtOrBefore(
      ExecutionContext serviceContext,
      InternalId transportRequestId,
      ExternalReference destinationFacility,
      Instant cutoff) {
    Objects.requireNonNull(serviceContext, "Transport service context");
    Objects.requireNonNull(transportRequestId, "Transport request id");
    Objects.requireNonNull(destinationFacility, "destination facility");
    Objects.requireNonNull(cutoff, "arrival cutoff");
    if (serviceContext.actor().kind() != ActorKind.SERVICE
        || serviceContext.scope().kind() != ExecutionScope.Kind.FACILITY) {
      throw new IllegalArgumentException(
          "Transport evidence query requires a scoped service identity");
    }
    var request =
        requests
            .find(transportRequestId)
            .orElseThrow(() -> new IllegalStateException("Transport request is unavailable"));
    if (serviceContext.scope().facility().filter(request.originFacility()::equals).isEmpty()) {
      throw new IllegalStateException(
          "Transport evidence query is outside its granted origin scope");
    }
    if (!authorization
        .evaluate(serviceContext, TransportAuthorities.destinationChange().code())
        .isAllowed()) {
      throw new IllegalStateException("Transport evidence query is not authorized");
    }
    return evidence.hasArrivalAtOrBefore(transportRequestId, destinationFacility, cutoff);
  }
}
