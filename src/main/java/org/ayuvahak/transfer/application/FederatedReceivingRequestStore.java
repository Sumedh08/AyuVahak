package org.ayuvahak.transfer.application;

import java.time.Instant;
import java.util.Optional;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.ayuvahak.transfer.domain.FederatedReceivingRequest;
import org.ayuvahak.transfer.domain.FederatedReceivingRequest.Assessment;

/** Transfer-owned persistence contract for receiver-local federated receiving cases. */
public interface FederatedReceivingRequestStore {

  Claim receive(FederatedReceivingRequest request, Instant recordedAt);

  Optional<FederatedReceivingRequest> find(InternalId requestId);

  Optional<FederatedReceivingRequest> lock(InternalId requestId);

  Optional<FederatedReceivingRequest> findByPeerOperation(
      DeploymentId peer, InternalId operationId);

  boolean update(FederatedReceivingRequest request, long expectedVersion);

  void appendAssessment(InternalId requestId, Assessment assessment, long requestVersion);

  record Claim(FederatedReceivingRequest request, boolean fingerprintMatches, boolean created) {}
}
