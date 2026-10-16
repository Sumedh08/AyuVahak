package org.ayuvahak.platform.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.Optional;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Direction;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.OwnerContext;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Protocol;
import org.junit.jupiter.api.Test;

class PartnerOutboundOperationRequestTest {

  private static final PartnerKey PARTNER = new PartnerKey("synthetic-transport-provider");
  private static final AllowedOperation OPERATION =
      new AllowedOperation(
          OwnerContext.TRANSPORT,
          "transport.dispatch",
          Direction.OUTBOUND,
          Protocol.JSON,
          "AyuVahakTransportProvider-v1");

  @Test
  void acceptsOnlyConfiguredTransportJsonCommandsAndDefensivelyCopiesBody() {
    byte[] body = "{\"transportRequestId\":\"SYN-TRANSPORT-001\"}".getBytes();
    PartnerOutboundOperationRequest request =
        new PartnerOutboundOperationRequest(
            PARTNER,
            OPERATION,
            List.of("transport-requests"),
            body,
            "dispatch-SYN-TRANSPORT-001",
            Optional.of(CorrelationId.generate()));

    body[0] = 'x';

    assertThat(new String(request.body())).startsWith("{");
    byte[] returned = request.body();
    returned[0] = 'x';
    assertThat(new String(request.body())).startsWith("{");
  }

  @Test
  void refusesCallerChosenDestinationsAndUnboundedOrUnsafeOperationInputs() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new PartnerOutboundOperationRequest(
                    PARTNER,
                    OPERATION,
                    List.of(".."),
                    new byte[] {'{'},
                    "operation-1",
                    Optional.empty()));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new PartnerOutboundOperationRequest(
                    PARTNER,
                    OPERATION,
                    List.of("transport-requests"),
                    new byte[1_048_577],
                    "operation-1",
                    Optional.empty()));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new PartnerOutboundOperationRequest(
                    PARTNER,
                    OPERATION,
                    List.of("transport-requests"),
                    new byte[] {'{'},
                    "bad key\r\nAuthorization: stolen",
                    Optional.empty()));
  }

  @Test
  void refusesFhirReadAndInboundOperationsAtTheCommandBoundary() {
    AllowedOperation fhirRead =
        new AllowedOperation(
            OwnerContext.DIRECTORY,
            "directory.read",
            Direction.OUTBOUND,
            Protocol.FHIR_R4_JSON,
            "IHE-mCSD");
    AllowedOperation inbound =
        new AllowedOperation(
            OwnerContext.TRANSPORT,
            "transport.status",
            Direction.INBOUND,
            Protocol.JSON,
            "AyuVahakTransportProvider-v1");

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new PartnerOutboundOperationRequest(
                    PARTNER, fhirRead, List.of("transport-requests"), new byte[] {'{'}, "op-1"));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new PartnerOutboundOperationRequest(
                    PARTNER, inbound, List.of("transport-requests"), new byte[] {'{'}, "op-1"));
  }
}
