package org.ayuvahak.transport.application;

import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.transport.domain.TransportProviderSubmission;

/** Minimum Transport-owned facts needed to form one named provider operation. */
public record TransportProviderSubmissionRequest(
    String partnerKey,
    TransportProviderSubmission.OperationKind operationKind,
    InternalId operationReference,
    InternalId transportRequestId,
    InternalId transferId,
    ExternalReference originFacility,
    ExternalReference destinationFacility,
    Optional<String> reasonCode,
    Optional<InternalId> proposedDestinationFacility,
    CorrelationId correlationId) {

  public TransportProviderSubmissionRequest {
    if (partnerKey == null || !partnerKey.matches("[A-Z0-9][A-Z0-9._-]{0,63}")) {
      throw new IllegalArgumentException("provider partner key is invalid");
    }
    Objects.requireNonNull(operationKind, "operation kind");
    Objects.requireNonNull(operationReference, "operation reference");
    Objects.requireNonNull(transportRequestId, "Transport request id");
    Objects.requireNonNull(transferId, "Transfer id");
    requireFacility(originFacility);
    requireFacility(destinationFacility);
    reasonCode = Objects.requireNonNull(reasonCode, "reason code");
    proposedDestinationFacility =
        Objects.requireNonNull(proposedDestinationFacility, "proposed destination");
    Objects.requireNonNull(correlationId, "correlation id");
    reasonCode.ifPresent(
        value -> {
          if (!value.matches("[A-Z][A-Z0-9_]{1,63}")) {
            throw new IllegalArgumentException("provider reason code is invalid");
          }
        });
    if ((operationKind == TransportProviderSubmission.OperationKind.DESTINATION_CHANGE)
        != proposedDestinationFacility.isPresent()) {
      throw new IllegalArgumentException(
          "destination-change operation must include its proposed Facility");
    }
  }

  private static void requireFacility(ExternalReference reference) {
    Objects.requireNonNull(reference, "Facility reference");
    if (!"urn:ayuvahak:directory".equals(reference.issuer().value())
        || !"Facility".equals(reference.resourceType())) {
      throw new IllegalArgumentException(
          "provider operations require Directory Facility references");
    }
    InternalId.parse(reference.value());
  }
}
