package org.ayuvahak.transport.adapter.exchange;

import java.util.List;
import java.util.Optional;
import org.ayuvahak.platform.exchange.application.PartnerOutboundOperationPort;
import org.ayuvahak.platform.exchange.application.PartnerOutboundOperationRequest;
import org.ayuvahak.platform.exchange.application.PartnerOutboundOperationResponse;
import org.ayuvahak.platform.exchange.application.PartnerTransportException;
import org.ayuvahak.platform.exchange.application.PartnerTransportFailure;
import org.ayuvahak.platform.exchange.application.PartnerTrustLookupUnavailableException;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation;
import org.ayuvahak.transport.application.TransportProviderSubmissionPort;
import org.ayuvahak.transport.application.TransportProviderSubmissionRequest;
import org.ayuvahak.transport.application.TransportProviderSubmissionResult;
import org.ayuvahak.transport.domain.TransportProviderSubmission;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Maps provider-neutral Transport operations to the Phase 9 configured JSON exchange boundary. */
@Component
public final class Phase9TransportProviderSubmissionAdapter
    implements TransportProviderSubmissionPort {

  private final PartnerOutboundOperationPort exchange;
  private final ObjectMapper mapper;

  public Phase9TransportProviderSubmissionAdapter(
      PartnerOutboundOperationPort exchange, ObjectMapper mapper) {
    this.exchange = java.util.Objects.requireNonNull(exchange, "Phase 9 exchange port");
    this.mapper = java.util.Objects.requireNonNull(mapper, "JSON mapper");
  }

  @Override
  public TransportProviderSubmissionResult submit(TransportProviderSubmissionRequest request) {
    try {
      PartnerOutboundOperationResponse response =
          exchange.execute(
              new PartnerOutboundOperationRequest(
                  new PartnerKey(request.partnerKey()),
                  operation(request.operationKind()),
                  path(request),
                  payload(request),
                  request.operationReference().toString(),
                  Optional.of(request.correlationId())));
      return new TransportProviderSubmissionResult(
          TransportProviderSubmission.State.ACKNOWLEDGED,
          Optional.of(response.configurationVersion()),
          Optional.of(response.status()),
          Optional.empty());
    } catch (PartnerTrustLookupUnavailableException unavailable) {
      return failed(TransportProviderSubmission.State.REJECTED, "TRUST_LOOKUP_UNAVAILABLE");
    } catch (PartnerTransportException failure) {
      if (failure.failure() == PartnerTransportFailure.PROFILE_NOT_ACTIVE) {
        return failed(TransportProviderSubmission.State.REVOKED, "PARTNER_PROFILE_INACTIVE");
      }
      if (permanentRejection(failure.failure())) {
        return failed(TransportProviderSubmission.State.REJECTED, "PARTNER_REJECTED");
      }
      return failed(TransportProviderSubmission.State.OUTCOME_UNKNOWN, failure.failure().name());
    } catch (RuntimeException failure) {
      return failed(TransportProviderSubmission.State.OUTCOME_UNKNOWN, "EXCHANGE_FAILURE");
    }
  }

  private byte[] payload(TransportProviderSubmissionRequest request) {
    ObjectNode payload = mapper.createObjectNode();
    payload.put("operationReference", request.operationReference().toString());
    payload.put("transportRequestReference", request.transportRequestId().toString());
    payload.put("transferReference", request.transferId().toString());
    payload.put("originFacilityReference", request.originFacility().value());
    payload.put("destinationFacilityReference", request.destinationFacility().value());
    request.reasonCode().ifPresent(value -> payload.put("reasonCode", value));
    request
        .proposedDestinationFacility()
        .ifPresent(value -> payload.put("proposedDestinationFacilityReference", value.toString()));
    try {
      return mapper.writeValueAsBytes(payload);
    } catch (JacksonException invalid) {
      throw new IllegalStateException("Transport provider request could not be serialized safely");
    }
  }

  private static List<String> path(TransportProviderSubmissionRequest request) {
    String requestId = request.transportRequestId().toString();
    String operationId = request.operationReference().toString();
    return switch (request.operationKind()) {
      case DISPATCH -> List.of("transport-requests", requestId, "dispatches");
      case CANCELLATION -> List.of("transport-requests", requestId, "cancellations", operationId);
      case DESTINATION_CHANGE ->
          List.of("transport-requests", requestId, "destination-changes", operationId);
    };
  }

  private static AllowedOperation operation(TransportProviderSubmission.OperationKind kind) {
    return switch (kind) {
      case DISPATCH -> AllowedOperation.transportDispatch();
      case CANCELLATION -> AllowedOperation.transportCancellation();
      case DESTINATION_CHANGE -> AllowedOperation.transportDestinationChange();
    };
  }

  private static boolean permanentRejection(PartnerTransportFailure failure) {
    return switch (failure) {
      case AUTHENTICATION_NOT_SUPPORTED,
          AUTHENTICATION_FAILURE,
          REMOTE_AUTHENTICATION_FAILURE,
          REMOTE_AUTHORIZATION_FAILURE,
          REMOTE_4XX,
          DESTINATION_REJECTED,
          RESPONSE_TOO_LARGE,
          UNSUPPORTED_CONTENT_ENCODING,
          INVALID_RESPONSE ->
          true;
      default -> false;
    };
  }

  private static TransportProviderSubmissionResult failed(
      TransportProviderSubmission.State state, String failureCode) {
    return new TransportProviderSubmissionResult(
        state, Optional.empty(), Optional.empty(), Optional.of(failureCode));
  }
}
