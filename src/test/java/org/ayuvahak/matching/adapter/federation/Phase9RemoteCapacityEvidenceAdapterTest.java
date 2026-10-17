package org.ayuvahak.matching.adapter.federation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.ayuvahak.matching.application.FederationLocalDeploymentIdentity;
import org.ayuvahak.matching.application.RemoteCapacityEvidenceExchangeException;
import org.ayuvahak.matching.application.RemoteCapacityPeerTarget;
import org.ayuvahak.matching.domain.RemoteCapacityEvidenceCompatibility;
import org.ayuvahak.matching.domain.RemoteFacilityReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.exchange.application.FederationPeerOperationException;
import org.ayuvahak.platform.exchange.application.FederationPeerOperationFailure;
import org.ayuvahak.platform.exchange.application.FederationPeerOperationPort;
import org.ayuvahak.platform.exchange.application.FederationPeerOperationRequest;
import org.ayuvahak.platform.exchange.application.PartnerOutboundOperationResponse;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class Phase9RemoteCapacityEvidenceAdapterTest {

  private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");
  private static final RemoteFacilityReference FACILITY =
      new RemoteFacilityReference(
          new DeploymentId("SYN-DEPLOYMENT-B"), "directory", InternalId.generate());
  private static final RemoteCapacityPeerTarget PEER =
      new RemoteCapacityPeerTarget(
          new PartnerKey("syn-peer-b"),
          new DeploymentId("SYN-DEPLOYMENT-B"),
          7,
          new RemoteCapacityEvidenceCompatibility("SYN-CAPACITY-PROFILE-1", 4, 2));

  @Test
  void issuesOnlyTheNamedCapacityReadAgainstTheConfiguredPeerAndScope() {
    AtomicReference<FederationPeerOperationRequest> captured = new AtomicReference<>();
    Phase9RemoteCapacityEvidenceAdapter adapter =
        adapter(
            request -> {
              captured.set(request);
              return response(responseBody(FACILITY, "OXYGEN_STATIONS", "4"), 7);
            });

    var evidence = adapter.request(PEER, FACILITY, "OXYGEN_STATIONS");

    FederationPeerOperationRequest request = captured.get();
    assertThat(request.partnerKey()).isEqualTo(PEER.partnerKey());
    assertThat(request.peerDeployment()).isEqualTo(PEER.peerDeploymentId());
    assertThat(request.scope()).isEqualTo(FACILITY.toExternalReference());
    assertThat(request.operation().operationCode()).isEqualTo("capacity.remote-evidence");
    assertThat(request.operation().direction())
        .isEqualTo(
            org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Direction.OUTBOUND);
    assertThat(new String(request.body(), StandardCharsets.UTF_8))
        .contains("capacity-remote-evidence-v1", "SYN-DEPLOYMENT-B", "OXYGEN_STATIONS")
        .doesNotContain("Patient", "patient", "SYN-PATIENT");
    assertThat(evidence.sourceSequence()).isEqualTo(21);
    assertThat(evidence.facility()).isEqualTo(FACILITY);
    assertThat(evidence.receivedAt()).isEqualTo(NOW);
  }

  @Test
  void rejectsAResponseForAnotherFacilityOrWithUnexpectedFields() {
    RemoteFacilityReference wrongFacility =
        new RemoteFacilityReference(
            new DeploymentId("SYN-DEPLOYMENT-C"), "directory", InternalId.generate());
    Phase9RemoteCapacityEvidenceAdapter wrongScope =
        adapter(request -> response(responseBody(wrongFacility, "OXYGEN_STATIONS", "4"), 7));
    Phase9RemoteCapacityEvidenceAdapter extraPatientField =
        adapter(
            request ->
                response(
                    responseBody(FACILITY, "OXYGEN_STATIONS", "4")
                        .replace(
                            "\"contractVersion\"",
                            "\"patient\":\"SYN-PATIENT-001\",\"contractVersion\""),
                    7));

    assertInvalid(() -> wrongScope.request(PEER, FACILITY, "OXYGEN_STATIONS"));
    assertInvalid(() -> extraPatientField.request(PEER, FACILITY, "OXYGEN_STATIONS"));
  }

  @Test
  void mapsRevokedOrUnavailablePeerToSanitizedFailure() {
    FederationPeerOperationPort revoked =
        request -> {
          throw new FederationPeerOperationException(
              org.ayuvahak.platform.exchange.application.FederationPeerOperationDisposition
                  .NOT_SENT,
              FederationPeerOperationFailure.TRUST_DENIED);
        };
    Phase9RemoteCapacityEvidenceAdapter adapter = adapter(revoked);

    assertThatThrownBy(() -> adapter.request(PEER, FACILITY, "OXYGEN_STATIONS"))
        .isInstanceOf(RemoteCapacityEvidenceExchangeException.class)
        .extracting(error -> ((RemoteCapacityEvidenceExchangeException) error).reason())
        .isEqualTo(RemoteCapacityEvidenceExchangeException.Reason.TRUST_DENIED);
  }

  @Test
  void rejectsAChangedPartnerConfigurationVersion() {
    Phase9RemoteCapacityEvidenceAdapter adapter =
        adapter(request -> response(responseBody(FACILITY, "OXYGEN_STATIONS", "4"), 8));

    assertInvalid(() -> adapter.request(PEER, FACILITY, "OXYGEN_STATIONS"));
  }

  private static Phase9RemoteCapacityEvidenceAdapter adapter(FederationPeerOperationPort port) {
    FederationLocalDeploymentIdentity identity =
        () -> Optional.of(new DeploymentId("SYN-DEPLOYMENT-A"));
    return new Phase9RemoteCapacityEvidenceAdapter(
        port, identity, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static PartnerOutboundOperationResponse response(String body, int configurationVersion) {
    return new PartnerOutboundOperationResponse(
        200,
        Optional.of("application/json"),
        body.getBytes(StandardCharsets.UTF_8),
        configurationVersion);
  }

  private static String responseBody(
      RemoteFacilityReference facility, String dimensionCode, String quantity) {
    return "{"
        + "\"contractVersion\":\"capacity-remote-evidence-v1\","
        + "\"deploymentId\":\""
        + facility.deploymentId().value()
        + "\",\"context\":\"directory\",\"resourceId\":\""
        + facility.resourceId()
        + "\",\"dimensionCode\":\""
        + dimensionCode
        + "\",\"sourceSequence\":21,\"dimensionVersion\":4,\"policyVersion\":2,"
        + "\"sourceProfileVersion\":\"SYN-CAPACITY-PROFILE-1\","
        + "\"evidenceId\":\"SYN-EVIDENCE-001\",\"outcome\":\"READY\","
        + "\"freshness\":\"CURRENT\",\"complete\":true,\"quantity\":"
        + quantity
        + ",\"state\":null,\"unit\":\"stations\","
        + "\"observedAt\":\"2026-10-18T09:59:30Z\","
        + "\"validUntil\":\"2026-10-18T10:05:00Z\"}";
  }

  private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOf(RemoteCapacityEvidenceExchangeException.class)
        .extracting(error -> ((RemoteCapacityEvidenceExchangeException) error).reason())
        .isEqualTo(RemoteCapacityEvidenceExchangeException.Reason.INVALID_RESPONSE);
  }
}
