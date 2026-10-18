package org.ayuvahak.transfer.adapter.federation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.exchange.application.FederationPeerOperationDisposition;
import org.ayuvahak.platform.exchange.application.FederationPeerOperationException;
import org.ayuvahak.platform.exchange.application.FederationPeerOperationFailure;
import org.ayuvahak.platform.exchange.application.FederationPeerOperationPort;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.ayuvahak.transfer.application.FederatedReceivingOriginExchangeException;
import org.ayuvahak.transfer.domain.FederatedOriginReceivingAttempt;
import org.junit.jupiter.api.Test;

class Phase9FederatedReceivingOriginExchangeTest {

  private static final DeploymentId ORIGIN = new DeploymentId("SYN-DEPLOYMENT-A");
  private static final DeploymentId PEER = new DeploymentId("SYN-DEPLOYMENT-B");

  @Test
  void technicalRemoteRejectionIsNotReportedAsBusinessRejection() {
    Phase9FederatedReceivingOriginExchange adapter =
        adapter(
            new FederationPeerOperationException(
                FederationPeerOperationDisposition.REMOTE_REJECTED,
                FederationPeerOperationFailure.REMOTE_REJECTED));

    assertThatThrownBy(() -> adapter.execute(attempt(), false))
        .isInstanceOf(FederatedReceivingOriginExchangeException.class)
        .extracting("disposition")
        .isEqualTo(FederatedReceivingOriginExchangeException.Disposition.INVALID_RESPONSE);
  }

  @Test
  void statusNotFoundAllowsSafeSameIdentityRequestRetryInsteadOfTerminalRejection() {
    Phase9FederatedReceivingOriginExchange adapter =
        adapter(
            new FederationPeerOperationException(
                FederationPeerOperationDisposition.REMOTE_REJECTED,
                FederationPeerOperationFailure.REMOTE_NOT_FOUND));

    assertThatThrownBy(() -> adapter.execute(attempt(), true))
        .isInstanceOf(FederatedReceivingOriginExchangeException.class)
        .extracting("disposition")
        .isEqualTo(FederatedReceivingOriginExchangeException.Disposition.NOT_SENT);
  }

  private static Phase9FederatedReceivingOriginExchange adapter(
      FederationPeerOperationException failure) {
    FederationPeerOperationPort port =
        ignored -> {
          throw failure;
        };
    return new Phase9FederatedReceivingOriginExchange(
        port, () -> Optional.of(ORIGIN), new tools.jackson.databind.ObjectMapper());
  }

  private static FederatedOriginReceivingAttempt attempt() {
    InternalId facility = InternalId.generate();
    byte[] body =
        ("{\"destinationFacility\":{\"value\":\""
                + facility
                + "\"},"
                + "\"destinationOrganization\":{\"value\":\""
                + InternalId.generate()
                + "\"}}")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    return FederatedOriginReceivingAttempt.pending(
        InternalId.generate(),
        InternalId.generate(),
        1,
        ORIGIN,
        PEER,
        new PartnerKey("synthetic-peer-b"),
        1,
        InternalId.generate(),
        org.ayuvahak.platform.exchange.application.FederationOperationFingerprint.calculate(
            ORIGIN,
            PEER,
            new org.ayuvahak.platform.core.ExternalReference(
                new org.ayuvahak.platform.core.IssuerId("urn:synthetic:scope"),
                "Facility",
                facility.toString()),
            org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation
                .transferRemoteReceivingRequest(
                    org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Direction
                        .OUTBOUND),
            body),
        "a".repeat(64),
        body,
        Instant.parse("2026-10-18T00:00:00Z"));
  }
}
