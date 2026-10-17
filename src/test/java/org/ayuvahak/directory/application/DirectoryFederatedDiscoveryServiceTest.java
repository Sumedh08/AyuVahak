package org.ayuvahak.directory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.junit.jupiter.api.Test;

class DirectoryFederatedDiscoveryServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-18T06:00:00Z");
  private static final DeploymentId PEER = new DeploymentId("SYN-DEPLOYMENT-B");
  private static final ExternalReference REMOTE_ORG =
      new ExternalReference(
          new IssuerId("urn:synthetic:deployment-b"), "Organization", "SYN-ORG-001");

  @Test
  void partialResponseIsExplicitAndOnlyReturnedFactsAreReconciled() {
    var repository = new InMemoryProjectionRepository();
    var authorized = new AtomicBoolean();
    var fact = fact("SYN-FACILITY-A", "Synthetic Facility A", 1);
    var service =
        service(
            (context, peer, scope) -> authorized.set(true),
            ignored ->
                page(PEER, DirectoryFederatedDiscoveryPage.Completeness.PARTIAL, List.of(fact)),
            repository);

    var result = service.discover(context(), query());

    assertThat(authorized).isTrue();
    assertThat(result.status()).isEqualTo(DirectoryFederatedDiscoveryResult.Status.PARTIAL);
    assertThat(result.factsReceived()).isEqualTo(1);
    assertThat(result.reconciliations()).hasSize(1);
    assertThat(repository.values()).containsKey(fact.reference());
    assertThat(repository.values())
        .doesNotContainKey(
            new DirectoryRemoteResourceReference(
                PEER, "Directory", "Facility", "SYN-FACILITY-ABSENT"));
  }

  @Test
  void peerFailureReturnsUnavailableWithoutChangingProjection() {
    var repository = new InMemoryProjectionRepository();
    var service =
        service(
            (context, peer, scope) -> {},
            ignored -> {
              throw new DirectoryFederatedDiscoveryUnavailableException();
            },
            repository);

    var result = service.discover(context(), query());

    assertThat(result.status())
        .isEqualTo(DirectoryFederatedDiscoveryResult.Status.PEER_UNAVAILABLE);
    assertThat(result.reconciliations()).isEmpty();
    assertThat(repository.values()).isEmpty();
  }

  @Test
  void wrongPeerResponseIsRejectedBeforeProjection() {
    var repository = new InMemoryProjectionRepository();
    var wrongPeer = new DeploymentId("SYN-DEPLOYMENT-C");
    var service =
        service(
            (context, peer, scope) -> {},
            ignored ->
                page(
                    wrongPeer,
                    DirectoryFederatedDiscoveryPage.Completeness.COMPLETE,
                    List.of(fact(wrongPeer, "SYN-FACILITY-A", "Synthetic Facility A", 1))),
            repository);

    assertThatThrownBy(() -> service.discover(context(), query()))
        .isInstanceOf(DirectoryFederatedDiscoveryInvalidResponseException.class);
    assertThat(repository.values()).isEmpty();
  }

  private static DirectoryFederatedDiscoveryService service(
      DirectoryFederatedDiscoveryAuthorizationPort authorization,
      DirectoryFederatedDiscoveryPort discovery,
      InMemoryProjectionRepository repository) {
    return new DirectoryFederatedDiscoveryService(
        authorization, discovery, repository, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static ExecutionContext context() {
    var organization =
        new ExternalReference(new IssuerId("urn:synthetic:local"), "Organization", "SYN-ORG-LOCAL");
    return new ExecutionContextFactory((actor, scope) -> true)
        .forInteractiveRequest(
            ActorReference.human(new IssuerId("urn:synthetic:idp"), "SYN-USER-001"),
            RequestedExecutionScope.organization(organization),
            CorrelationId.generate());
  }

  private static DirectoryFederatedDiscoveryQuery query() {
    return new DirectoryFederatedDiscoveryQuery(
        new PartnerKey("synthetic-directory-peer"),
        PEER,
        REMOTE_ORG,
        InternalId.generate(),
        3,
        Duration.ofMinutes(5),
        Optional.empty());
  }

  private static DirectoryFederatedDiscoveryPage page(
      DeploymentId peer,
      DirectoryFederatedDiscoveryPage.Completeness completeness,
      List<DirectoryFederatedFact> facts) {
    return new DirectoryFederatedDiscoveryPage(
        peer, REMOTE_ORG, "directory-profile-v1", NOW, completeness, facts);
  }

  private static DirectoryFederatedFact fact(String id, String name, long version) {
    return fact(PEER, id, name, version);
  }

  private static DirectoryFederatedFact fact(
      DeploymentId source, String id, String name, long version) {
    return new DirectoryFederatedFact(
        new DirectoryRemoteResourceReference(source, "Directory", "Facility", id),
        Optional.of(
            new DirectoryRemoteResourceReference(
                source, "Directory", "Organization", "SYN-ORG-001")),
        name,
        true,
        version,
        Optional.of(NOW.minusSeconds(5)),
        "directory-profile-v1");
  }

  private static final class InMemoryProjectionRepository
      implements DirectoryFederatedProjectionRepository {

    private final Map<DirectoryRemoteResourceReference, DirectoryFederatedProjection> values =
        new HashMap<>();

    @Override
    public DirectoryFederatedProjectionPolicy.Result reconcile(
        DirectoryFederatedFact fact,
        PartnerKey partnerKey,
        int partnerConfigurationVersion,
        Instant receivedAt,
        Duration freshness) {
      var result =
          DirectoryFederatedProjectionPolicy.reconcile(
              Optional.ofNullable(values.get(fact.reference())),
              fact,
              partnerKey,
              partnerConfigurationVersion,
              receivedAt,
              freshness);
      values.put(fact.reference(), result.projection());
      return result;
    }

    @Override
    public List<DirectoryFederatedProjectionPolicy.Result> reconcilePage(
        List<DirectoryFederatedFact> facts,
        PartnerKey partnerKey,
        int partnerConfigurationVersion,
        Instant sourceObservedAt,
        Instant receivedAt,
        Duration freshness) {
      return facts.stream()
          .map(
              fact -> {
                var result =
                    DirectoryFederatedProjectionPolicy.reconcile(
                        Optional.ofNullable(values.get(fact.reference())),
                        fact,
                        partnerKey,
                        partnerConfigurationVersion,
                        sourceObservedAt,
                        receivedAt,
                        freshness);
                values.put(fact.reference(), result.projection());
                return result;
              })
          .toList();
    }

    @Override
    public Optional<DirectoryFederatedProjection> find(DirectoryRemoteResourceReference reference) {
      return Optional.ofNullable(values.get(reference));
    }

    @Override
    public List<DirectoryFederatedProjection> findByRemoteScope(
        DeploymentId sourceDeployment, ExternalReference remoteScope) {
      return values.values().stream()
          .filter(value -> value.reference().deploymentId().equals(sourceDeployment))
          .toList();
    }

    private Map<DirectoryRemoteResourceReference, DirectoryFederatedProjection> values() {
      return Map.copyOf(values);
    }
  }
}
