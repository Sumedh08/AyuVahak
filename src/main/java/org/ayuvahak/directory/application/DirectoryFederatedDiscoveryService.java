package org.ayuvahak.directory.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/** Orchestrates authorized remote discovery and the consuming Directory's bounded projection. */
@Service
@Lazy
public final class DirectoryFederatedDiscoveryService {

  private final DirectoryFederatedDiscoveryAuthorizationPort authorization;
  private final DirectoryFederatedDiscoveryPort discovery;
  private final DirectoryFederatedProjectionRepository projections;
  private final Clock clock;

  public DirectoryFederatedDiscoveryService(
      DirectoryFederatedDiscoveryAuthorizationPort authorization,
      DirectoryFederatedDiscoveryPort discovery,
      DirectoryFederatedProjectionRepository projections,
      @Qualifier("auditClock") Clock clock) {
    this.authorization = java.util.Objects.requireNonNull(authorization, "authorization");
    this.discovery = java.util.Objects.requireNonNull(discovery, "discovery");
    this.projections = java.util.Objects.requireNonNull(projections, "projections");
    this.clock = java.util.Objects.requireNonNull(clock, "clock");
  }

  public DirectoryFederatedDiscoveryResult discover(
      ExecutionContext context, DirectoryFederatedDiscoveryQuery query) {
    java.util.Objects.requireNonNull(context, "executionContext");
    java.util.Objects.requireNonNull(query, "query");
    authorization.authorize(context, query.peerDeployment(), query.remoteScope());
    DirectoryFederatedDiscoveryPage page;
    try {
      page = discovery.discover(query);
    } catch (DirectoryFederatedDiscoveryUnavailableException unavailable) {
      return new DirectoryFederatedDiscoveryResult(
          DirectoryFederatedDiscoveryResult.Status.PEER_UNAVAILABLE,
          0,
          List.of(),
          Optional.empty());
    }
    Instant now = clock.instant();
    if (!page.sourceDeployment().equals(query.peerDeployment())
        || !page.requestedScope().equals(query.remoteScope())
        || page.observedAt().isAfter(now.plus(Duration.ofMinutes(5)))
        || page.facts().stream()
            .anyMatch(
                fact ->
                    fact.sourceUpdatedAt()
                        .filter(updatedAt -> updatedAt.isAfter(now.plus(Duration.ofMinutes(5))))
                        .isPresent())) {
      throw new DirectoryFederatedDiscoveryInvalidResponseException();
    }
    List<DirectoryFederatedProjectionPolicy.Result> reconciliations =
        projections.reconcilePage(
            page.facts(),
            query.partnerKey(),
            query.partnerConfigurationVersion(),
            page.observedAt(),
            now,
            query.freshness());
    return new DirectoryFederatedDiscoveryResult(
        page.completeness() == DirectoryFederatedDiscoveryPage.Completeness.COMPLETE
            ? DirectoryFederatedDiscoveryResult.Status.COMPLETE
            : DirectoryFederatedDiscoveryResult.Status.PARTIAL,
        page.facts().size(),
        reconciliations,
        Optional.of(page.observedAt()));
  }

  /** Returns only the named peer scope after a fresh local DirectoryRead authorization check. */
  public List<DirectoryFederatedProjection> readProjection(
      ExecutionContext context, DeploymentId sourceDeployment, ExternalReference remoteScope) {
    java.util.Objects.requireNonNull(context, "executionContext");
    java.util.Objects.requireNonNull(sourceDeployment, "sourceDeployment");
    java.util.Objects.requireNonNull(remoteScope, "remoteScope");
    authorization.authorize(context, sourceDeployment, remoteScope);
    return projections.findByRemoteScope(sourceDeployment, remoteScope);
  }
}
