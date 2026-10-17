package org.ayuvahak.directory.adapter.federation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.ayuvahak.directory.application.DirectoryFederatedDiscoveryInvalidResponseException;
import org.ayuvahak.directory.application.DirectoryFederatedDiscoveryPage;
import org.ayuvahak.directory.application.DirectoryFederatedDiscoveryQuery;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.exchange.application.FederationPeerOperationPort;
import org.ayuvahak.platform.exchange.application.PartnerOutboundOperationResponse;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.federation.application.DeploymentIdentityRegistrationService;
import org.ayuvahak.platform.federation.application.DeploymentIdentityStore;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.ayuvahak.platform.federation.domain.DeploymentIdentity;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class Phase9DirectoryFederatedDiscoveryAdapterTest {

  private static final DeploymentId LOCAL = new DeploymentId("SYN-DEPLOYMENT-A");
  private static final DeploymentId PEER = new DeploymentId("SYN-DEPLOYMENT-B");
  private static final ExternalReference PEER_ORGANIZATION =
      new ExternalReference(
          new IssuerId("urn:synthetic:deployment-b"), "Organization", "SYN-ORG-001");

  @Test
  void sendsNamedScopedOperationThroughPhase9AndDecodesBoundedResponse() throws Exception {
    AtomicReference<org.ayuvahak.platform.exchange.application.FederationPeerOperationRequest>
        captured = new AtomicReference<>();
    var exchange = exchangeReturning(captured, response(PEER_ORGANIZATION.value(), "SYN-ORG-001"));
    var adapter =
        new Phase9DirectoryFederatedDiscoveryAdapter(exchange, identity(), new ObjectMapper());
    var query = query();

    DirectoryFederatedDiscoveryPage page = adapter.discover(query);

    assertThat(page.sourceDeployment()).isEqualTo(PEER);
    assertThat(page.requestedScope()).isEqualTo(PEER_ORGANIZATION);
    assertThat(page.completeness()).isEqualTo(DirectoryFederatedDiscoveryPage.Completeness.PARTIAL);
    assertThat(page.facts()).hasSize(1);
    assertThat(page.facts().getFirst().reference().deploymentId()).isEqualTo(PEER);
    assertThat(captured.get().peerDeployment()).isEqualTo(PEER);
    assertThat(captured.get().operation().operationCode()).isEqualTo("directory.remote-discovery");
    assertThat(captured.get().operationId()).isEqualTo(query.operationId());
    assertThat(new ObjectMapper().readTree(captured.get().body()).get("maximumFacts").asInt())
        .isEqualTo(DirectoryFederatedDiscoveryPage.MAX_FACTS);
  }

  @Test
  void rejectsOutOfScopeResourceWithoutPersistingOrReturningItsFacts() throws Exception {
    byte[] body = response("SYN-ORG-OTHER", "SYN-ORG-OTHER");
    var adapter =
        new Phase9DirectoryFederatedDiscoveryAdapter(
            (request) ->
                new PartnerOutboundOperationResponse(
                    200,
                    Optional.of("application/json"),
                    body,
                    request.expectedConfigurationVersion()),
            identity(),
            new ObjectMapper());

    assertThatThrownBy(() -> adapter.discover(query()))
        .isInstanceOf(DirectoryFederatedDiscoveryInvalidResponseException.class)
        .hasMessage("Federated Directory response is invalid or outside the requested scope.");
  }

  private static DirectoryFederatedDiscoveryQuery query() {
    return new DirectoryFederatedDiscoveryQuery(
        new PartnerKey("synthetic-directory-peer"),
        PEER,
        PEER_ORGANIZATION,
        InternalId.generate(),
        4,
        Duration.ofMinutes(5),
        Optional.empty());
  }

  private static FederationPeerOperationPort exchangeReturning(
      AtomicReference<org.ayuvahak.platform.exchange.application.FederationPeerOperationRequest>
          captured,
      byte[] body) {
    return request -> {
      captured.set(request);
      return new PartnerOutboundOperationResponse(
          200,
          Optional.of("application/json; charset=utf-8"),
          body,
          request.expectedConfigurationVersion());
    };
  }

  private static byte[] response(String scopeValue, String organizationId) throws Exception {
    var wire =
        new DirectoryFederatedDiscoveryWireResponse(
            "AyuVahak-Federation-v1",
            PEER.value(),
            PEER_ORGANIZATION.issuer().value(),
            "Organization",
            scopeValue,
            "directory-profile-v1",
            "2026-10-18T06:00:00Z",
            "PARTIAL",
            List.of(
                new DirectoryFederatedDiscoveryWireResponse.Fact(
                    "Facility",
                    "SYN-FACILITY-A",
                    organizationId,
                    "Synthetic Facility A",
                    true,
                    7L,
                    "2026-10-18T05:59:00Z")));
    return new ObjectMapper().writeValueAsBytes(wire);
  }

  private static DeploymentIdentityRegistrationService identity() {
    var store =
        new DeploymentIdentityStore() {
          private DeploymentIdentity current;

          @Override
          public DeploymentIdentity registerOrVerify(
              DeploymentId deploymentId, String digest, Instant now) {
            current = new DeploymentIdentity(deploymentId, 1, now, now);
            return current;
          }

          @Override
          public Optional<DeploymentIdentity> findCurrent() {
            return Optional.ofNullable(current);
          }
        };
    var registration = new DeploymentIdentityRegistrationService(store);
    registration.register(
        LOCAL, "synthetic-installation-binding-0123456789", Instant.parse("2026-10-18T06:00:00Z"));
    return registration;
  }
}
