package org.ayuvahak.directory.application;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.federation.domain.DeploymentId;

/** Logical, administrator-bounded query for one remote Directory peer and scope. */
public record DirectoryFederatedDiscoveryQuery(
    PartnerKey partnerKey,
    DeploymentId peerDeployment,
    ExternalReference remoteScope,
    InternalId operationId,
    int partnerConfigurationVersion,
    Duration freshness,
    Optional<CorrelationId> correlationId) {

  public DirectoryFederatedDiscoveryQuery {
    Objects.requireNonNull(partnerKey, "partnerKey");
    Objects.requireNonNull(peerDeployment, "peerDeployment");
    Objects.requireNonNull(remoteScope, "remoteScope");
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(freshness, "freshness");
    correlationId = Objects.requireNonNull(correlationId, "correlationId");
    if (!"Organization".equals(remoteScope.resourceType())
        && !"Facility".equals(remoteScope.resourceType())) {
      throw new IllegalArgumentException("remote Directory scope must be Organization or Facility");
    }
    if ("*".equals(remoteScope.value()) || partnerConfigurationVersion < 1) {
      throw new IllegalArgumentException("remote Directory query scope or version is invalid");
    }
    if (freshness.isZero()
        || freshness.isNegative()
        || freshness.compareTo(Duration.ofDays(7)) > 0) {
      throw new IllegalArgumentException("remote Directory freshness must be within one week");
    }
  }
}
