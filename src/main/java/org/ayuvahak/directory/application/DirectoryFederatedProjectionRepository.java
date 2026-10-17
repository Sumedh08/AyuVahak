package org.ayuvahak.directory.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.federation.domain.DeploymentId;

/** Directory-owned persistence boundary for attributable remote discovery projections. */
public interface DirectoryFederatedProjectionRepository {

  DirectoryFederatedProjectionPolicy.Result reconcile(
      DirectoryFederatedFact fact,
      PartnerKey partnerKey,
      int partnerConfigurationVersion,
      Instant receivedAt,
      Duration freshness);

  List<DirectoryFederatedProjectionPolicy.Result> reconcilePage(
      List<DirectoryFederatedFact> facts,
      PartnerKey partnerKey,
      int partnerConfigurationVersion,
      Instant sourceObservedAt,
      Instant receivedAt,
      Duration freshness);

  Optional<DirectoryFederatedProjection> find(DirectoryRemoteResourceReference reference);

  List<DirectoryFederatedProjection> findByRemoteScope(
      DeploymentId sourceDeployment, ExternalReference remoteScope);
}
