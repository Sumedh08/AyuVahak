package org.ayuvahak.analytics.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.analytics.domain.FederatedAggregateMappingVersion;
import org.ayuvahak.analytics.domain.FederatedAggregateMappingVersion.Direction;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.federation.domain.DeploymentId;

/** Analytics-owned immutable mapping storage for named aggregate federation. */
public interface FederatedAggregateMappingStore {

  PublishResult publish(FederatedAggregateMappingVersion mapping, Instant recordedAt);

  void attachAudit(String mappingKey, int version, UUID auditRecordId);

  void deactivate(String mappingKey, Instant changedAt);

  void attachLifecycleAudit(String mappingKey, UUID auditRecordId);

  Optional<FederatedAggregateMappingVersion> activeMapping(
      Direction direction,
      String indicatorKey,
      int indicatorVersion,
      ExternalReference sourceOrganization,
      DeploymentId peerDeployment,
      ExternalReference peerOrganizationScope,
      Instant at);

  Optional<FederatedAggregateMappingVersion> activeByKey(String mappingKey, Instant at);

  Optional<FederatedAggregateMappingVersion> findVersion(String mappingKey, int version);

  Optional<Integer> findActiveVersion(String mappingKey);

  record PublishResult(FederatedAggregateMappingVersion mapping, boolean created) {}
}
