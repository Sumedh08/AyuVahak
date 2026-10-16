package org.ayuvahak.transport.adapter.exchange;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.exchange.application.PartnerOutboundOperationPort;
import org.ayuvahak.platform.exchange.application.PartnerOutboundOperationRequest;
import org.ayuvahak.platform.exchange.application.PartnerOutboundOperationResponse;
import org.ayuvahak.platform.exchange.application.PartnerTransportException;
import org.ayuvahak.platform.exchange.application.PartnerTransportFailure;
import org.ayuvahak.platform.exchange.application.PartnerTrustLookupUnavailableException;
import org.ayuvahak.transport.application.TransportProviderSubmissionRequest;
import org.ayuvahak.transport.domain.TransportProviderSubmission;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class Phase9TransportProviderSubmissionAdapterTest {

  private static final InternalId REQUEST =
      InternalId.parse("10000000-0000-4000-8000-000000000001");
  private static final InternalId TRANSFER =
      InternalId.parse("10000000-0000-4000-8000-000000000002");
  private static final InternalId ORIGIN = InternalId.parse("10000000-0000-4000-8000-000000000003");
  private static final InternalId DESTINATION =
      InternalId.parse("10000000-0000-4000-8000-000000000004");

  @Test
  void dispatchUsesConfiguredOperationAndOnlyMinimumCoordinationReferences() {
    AtomicReference<PartnerOutboundOperationRequest> captured = new AtomicReference<>();
    PartnerOutboundOperationPort exchange =
        request -> {
          captured.set(request);
          return new PartnerOutboundOperationResponse(202, Optional.empty(), new byte[0], 3);
        };
    var adapter = new Phase9TransportProviderSubmissionAdapter(exchange, new ObjectMapper());

    var result =
        adapter.submit(request(TransportProviderSubmission.OperationKind.DISPATCH, REQUEST));

    assertThat(result.state()).isEqualTo(TransportProviderSubmission.State.ACKNOWLEDGED);
    assertThat(captured.get().partnerKey().value()).isEqualTo("SYNTHETIC-PROVIDER");
    assertThat(captured.get().operation().operationCode()).isEqualTo("transport.dispatch");
    assertThat(captured.get().pathSegments())
        .containsExactly("transport-requests", REQUEST.toString(), "dispatches");
    assertThat(captured.get().operationReference()).isEqualTo(REQUEST.toString());
    var payload = new ObjectMapper().readTree(captured.get().body());
    assertThat(payload.properties().stream().map(java.util.Map.Entry::getKey))
        .containsExactlyInAnyOrder(
            "operationReference",
            "transportRequestReference",
            "transferReference",
            "originFacilityReference",
            "destinationFacilityReference");
    assertThat(payload.toString()).doesNotContain("Patient", "clinical", "diagnosis");
  }

  @Test
  void destinationChangeUsesStableOwnerOperationReferenceAndDoesNotRetryTimeout() {
    AtomicInteger attempts = new AtomicInteger();
    AtomicReference<PartnerOutboundOperationRequest> captured = new AtomicReference<>();
    PartnerOutboundOperationPort exchange =
        request -> {
          attempts.incrementAndGet();
          captured.set(request);
          throw new PartnerTransportException(PartnerTransportFailure.READ_TIMEOUT);
        };
    var adapter = new Phase9TransportProviderSubmissionAdapter(exchange, new ObjectMapper());
    InternalId operation = InternalId.parse("10000000-0000-4000-8000-000000000005");

    var result =
        adapter.submit(
            new TransportProviderSubmissionRequest(
                "SYNTHETIC-PROVIDER",
                TransportProviderSubmission.OperationKind.DESTINATION_CHANGE,
                operation,
                REQUEST,
                TRANSFER,
                facility(ORIGIN),
                facility(DESTINATION),
                Optional.of("ROUTE_UNAVAILABLE"),
                Optional.of(InternalId.parse("10000000-0000-4000-8000-000000000006")),
                new CorrelationId("provider-adapter-fixture")));

    assertThat(attempts).hasValue(1);
    assertThat(captured.get().operation().operationCode())
        .isEqualTo("transport.destination-change");
    assertThat(captured.get().operationReference()).isEqualTo(operation.toString());
    assertThat(result.state()).isEqualTo(TransportProviderSubmission.State.OUTCOME_UNKNOWN);
    assertThat(result.failureCode()).contains("READ_TIMEOUT");
  }

  @Test
  void cancellationUsesItsNamedPhase9OperationAndStableOperationReference() {
    AtomicReference<PartnerOutboundOperationRequest> captured = new AtomicReference<>();
    PartnerOutboundOperationPort exchange =
        request -> {
          captured.set(request);
          return new PartnerOutboundOperationResponse(202, Optional.empty(), new byte[0], 4);
        };
    var adapter = new Phase9TransportProviderSubmissionAdapter(exchange, new ObjectMapper());
    InternalId operation = InternalId.parse("10000000-0000-4000-8000-000000000007");

    var result =
        adapter.submit(
            new TransportProviderSubmissionRequest(
                "SYNTHETIC-PROVIDER",
                TransportProviderSubmission.OperationKind.CANCELLATION,
                operation,
                REQUEST,
                TRANSFER,
                facility(ORIGIN),
                facility(DESTINATION),
                Optional.of("TRANSFER_WITHDRAWN"),
                Optional.empty(),
                new CorrelationId("provider-adapter-fixture")));

    assertThat(result.state()).isEqualTo(TransportProviderSubmission.State.ACKNOWLEDGED);
    assertThat(captured.get().operation().operationCode())
        .isEqualTo("transport.request-cancellation");
    assertThat(captured.get().pathSegments())
        .containsExactly(
            "transport-requests", REQUEST.toString(), "cancellations", operation.toString());
    assertThat(captured.get().operationReference()).isEqualTo(operation.toString());
    assertThat(new ObjectMapper().readTree(captured.get().body()).get("reasonCode").asString())
        .isEqualTo("TRANSFER_WITHDRAWN");
  }

  @Test
  void trustStoreUnavailableIsRetryableBecauseNoProviderRequestWasSent() {
    AtomicInteger attempts = new AtomicInteger();
    PartnerOutboundOperationPort exchange =
        request -> {
          attempts.incrementAndGet();
          throw new PartnerTrustLookupUnavailableException(new IllegalStateException("offline"));
        };
    var adapter = new Phase9TransportProviderSubmissionAdapter(exchange, new ObjectMapper());

    var result =
        adapter.submit(request(TransportProviderSubmission.OperationKind.DISPATCH, REQUEST));

    assertThat(attempts).hasValue(1);
    assertThat(result.state().name()).isEqualTo("RETRY_PENDING");
    assertThat(result.failureCode()).contains("TRUST_LOOKUP_UNAVAILABLE");
  }

  private static TransportProviderSubmissionRequest request(
      TransportProviderSubmission.OperationKind kind, InternalId operationReference) {
    return new TransportProviderSubmissionRequest(
        "SYNTHETIC-PROVIDER",
        kind,
        operationReference,
        REQUEST,
        TRANSFER,
        facility(ORIGIN),
        facility(DESTINATION),
        Optional.empty(),
        Optional.empty(),
        new CorrelationId("provider-adapter-fixture"));
  }

  private static ExternalReference facility(InternalId id) {
    return new ExternalReference(new IssuerId("urn:ayuvahak:directory"), "Facility", id.toString());
  }
}
