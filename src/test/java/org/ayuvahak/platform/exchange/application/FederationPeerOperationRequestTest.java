package org.ayuvahak.platform.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.exchange.domain.FederationPeerOperations;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Direction;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.OwnerContext;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Protocol;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.junit.jupiter.api.Test;

class FederationPeerOperationRequestTest {

  private static final PartnerKey PARTNER = new PartnerKey("synthetic-peer-b");
  private static final DeploymentId SOURCE = new DeploymentId("SYN-DEPLOYMENT-A");
  private static final DeploymentId PEER = new DeploymentId("SYN-DEPLOYMENT-B");
  private static final ExternalReference FACILITY =
      new ExternalReference(new IssuerId("urn:synthetic:authority"), "Facility", "SYN-FACILITY-A");
  private static final AllowedOperation OPERATION = AllowedOperation.transferMovementFact();
  private static final byte[] BODY =
      "{\"transferReference\":\"SYN-TRANSFER-001\",\"movement\":\"ARRIVED\"}"
          .getBytes(StandardCharsets.UTF_8);

  @Test
  void createsBoundedRequestWithStableIdentityAndDefensivePayloadCopy() {
    InternalId operationId = InternalId.generate();
    FederationOperationFingerprint fingerprint = fingerprint(BODY);
    byte[] body = BODY.clone();

    FederationPeerOperationRequest request =
        new FederationPeerOperationRequest(
            PARTNER,
            SOURCE,
            PEER,
            FACILITY,
            OPERATION,
            operationId,
            3,
            body,
            fingerprint,
            Optional.empty());

    body[0] = 'x';
    assertThat(request.body()).isEqualTo(BODY);
    byte[] returned = request.body();
    returned[0] = 'x';
    assertThat(request.body()).isEqualTo(BODY);
    assertThat(request.fingerprint()).isEqualTo(fingerprint);
    assertThat(request.toString()).doesNotContain("SYN-TRANSFER-001");

    FederationPeerOperationRequest receivingView =
        new FederationPeerOperationRequest(
            new PartnerKey("synthetic-local-alias-for-peer-a"),
            SOURCE,
            PEER,
            FACILITY,
            OPERATION,
            operationId,
            17,
            BODY,
            fingerprint,
            Optional.empty());
    assertThat(receivingView.fingerprint()).isEqualTo(request.fingerprint());
  }

  @Test
  void fingerprintChangesWhenPeerScopeOperationOrPayloadChanges() {
    FederationOperationFingerprint original = fingerprint(BODY);
    assertThat(
            FederationOperationFingerprint.calculate(
                SOURCE,
                PEER,
                FACILITY,
                AllowedOperation.transferMovementFact(Direction.INBOUND),
                BODY))
        .isEqualTo(original);

    assertThat(
            FederationOperationFingerprint.calculate(
                SOURCE, new DeploymentId("SYN-DEPLOYMENT-C"), FACILITY, OPERATION, BODY))
        .isNotEqualTo(original);
    assertThat(
            FederationOperationFingerprint.calculate(
                SOURCE,
                PEER,
                new ExternalReference(
                    new IssuerId("urn:synthetic:authority"), "Facility", "SYN-FACILITY-B"),
                OPERATION,
                BODY))
        .isNotEqualTo(original);
    assertThat(
            FederationOperationFingerprint.calculate(
                SOURCE,
                PEER,
                FACILITY,
                new AllowedOperation(
                    OwnerContext.TRANSFER,
                    "transfer.remote-receiving-request",
                    Direction.OUTBOUND,
                    Protocol.JSON,
                    "AyuVahak-Federation-v1"),
                BODY))
        .isNotEqualTo(original);
    assertThat(fingerprint("{\"movement\":\"DEPARTED\"}".getBytes(StandardCharsets.UTF_8)))
        .isNotEqualTo(original);
  }

  @Test
  void rejectsChangedContentUnderTheSameFingerprintAndUnapprovedOperations() {
    InternalId operationId = InternalId.generate();
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new FederationPeerOperationRequest(
                    PARTNER,
                    SOURCE,
                    PEER,
                    FACILITY,
                    OPERATION,
                    operationId,
                    1,
                    "{\"movement\":\"DEPARTED\"}".getBytes(StandardCharsets.UTF_8),
                    fingerprint(BODY),
                    Optional.empty()));

    AllowedOperation arbitraryTransferOperation =
        new AllowedOperation(
            OwnerContext.TRANSFER,
            "transfer.anything",
            Direction.OUTBOUND,
            Protocol.JSON,
            "AyuVahak-Federation-v1");
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new FederationPeerOperationRequest(
                    PARTNER,
                    SOURCE,
                    PEER,
                    FACILITY,
                    arbitraryTransferOperation,
                    operationId,
                    1,
                    BODY,
                    FederationOperationFingerprint.calculate(
                        SOURCE, PEER, FACILITY, arbitraryTransferOperation, BODY),
                    Optional.empty()));
  }

  @Test
  void finiteAllowlistIncludesOnlyNamedOwnerContracts() {
    assertThat(FederationPeerOperations.all())
        .extracting(operation -> operation.ownerContext().name())
        .containsOnly("DIRECTORY", "CAPACITY", "TRANSFER", "ANALYTICS");
    assertThat(FederationPeerOperations.all())
        .extracting(AllowedOperation::operationCode)
        .containsExactlyInAnyOrder(
            "directory.remote-discovery",
            "directory.remote-discovery",
            "capacity.remote-evidence",
            "capacity.remote-evidence",
            "transfer.remote-receiving-request",
            "transfer.remote-receiving-request",
            "transfer.remote-reservation-status",
            "transfer.remote-reservation-status",
            "transfer.movement-fact",
            "transfer.movement-fact",
            "analytics.aggregate-snapshot",
            "analytics.aggregate-snapshot");
    assertThat(
            FederationPeerOperations.supportsOutbound(
                AllowedOperation.directoryRemoteDiscovery(Direction.OUTBOUND)))
        .isTrue();
    assertThat(
            FederationPeerOperations.supportsOutbound(
                AllowedOperation.capacityRemoteEvidence(Direction.OUTBOUND)))
        .isTrue();
    assertThat(
            FederationPeerOperations.supportsOutbound(
                AllowedOperation.transferRemoteReceivingRequest(Direction.OUTBOUND)))
        .isTrue();
    assertThat(
            FederationPeerOperations.supportsOutbound(
                AllowedOperation.transferRemoteReservationStatus(Direction.OUTBOUND)))
        .isTrue();
    assertThat(
            FederationPeerOperations.supportsOutbound(
                AllowedOperation.analyticsAggregateSnapshot(Direction.OUTBOUND)))
        .isTrue();
    assertThat(
            FederationPeerOperations.supportsOutbound(
                new AllowedOperation(
                    OwnerContext.TRANSPORT,
                    "transport.anything",
                    Direction.OUTBOUND,
                    Protocol.JSON,
                    "AyuVahak-Federation-v1")))
        .isFalse();
    assertThat(
            FederationPeerOperations.supportsOutbound(
                new AllowedOperation(
                    OwnerContext.TRANSFER,
                    "transfer.anything",
                    Direction.OUTBOUND,
                    Protocol.JSON,
                    "AyuVahak-Federation-v1")))
        .isFalse();
  }

  @Test
  void analyticsAggregateFederationIsNamedAndBounded() {
    AllowedOperation outbound = AllowedOperation.analyticsAggregateSnapshot(Direction.OUTBOUND);
    assertThat(outbound.ownerContext()).isEqualTo(OwnerContext.ANALYTICS);
    assertThat(outbound.operationCode()).isEqualTo("analytics.aggregate-snapshot");
    assertThat(outbound.protocol()).isEqualTo(Protocol.JSON);
    assertThat(FederationPeerOperations.supportsOutbound(outbound)).isTrue();
    assertThat(
            FederationPeerOperations.supportsInbound(
                AllowedOperation.analyticsAggregateSnapshot(Direction.INBOUND)))
        .isTrue();

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new AllowedOperation(
                    OwnerContext.ANALYTICS,
                    "analytics.anything",
                    Direction.OUTBOUND,
                    Protocol.JSON,
                    "AyuVahak-Federation-v1"));
  }

  @Test
  void rejectsInboundOrNonTransferOperationAndUnboundedRequest() {
    AllowedOperation inbound = AllowedOperation.transferMovementFact(Direction.INBOUND);
    assertThat(inbound.direction()).isEqualTo(Direction.INBOUND);
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new FederationPeerOperationRequest(
                    PARTNER,
                    SOURCE,
                    PEER,
                    FACILITY,
                    inbound,
                    InternalId.generate(),
                    1,
                    BODY,
                    FederationOperationFingerprint.calculate(SOURCE, PEER, FACILITY, inbound, BODY),
                    Optional.empty()));

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new FederationPeerOperationRequest(
                    PARTNER,
                    SOURCE,
                    PEER,
                    FACILITY,
                    OPERATION,
                    InternalId.generate(),
                    1,
                    new byte[FederationPeerOperationRequest.MAX_REQUEST_BYTES + 1],
                    FederationOperationFingerprint.calculate(
                        SOURCE,
                        PEER,
                        FACILITY,
                        OPERATION,
                        new byte[FederationPeerOperationRequest.MAX_REQUEST_BYTES + 1]),
                    Optional.empty()));
  }

  private static FederationOperationFingerprint fingerprint(byte[] body) {
    return FederationOperationFingerprint.calculate(SOURCE, PEER, FACILITY, OPERATION, body);
  }
}
