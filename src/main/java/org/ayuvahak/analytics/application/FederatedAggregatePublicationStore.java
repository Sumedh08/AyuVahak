package org.ayuvahak.analytics.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.analytics.domain.FederatedAggregatePublication;
import org.ayuvahak.analytics.domain.FederatedAggregatePublication.State;

/** Durable sender-side identity, single-attempt state and disclosure linkage. */
public interface FederatedAggregatePublicationStore {

  CreateResult createOrReplay(FederatedAggregatePublication proposed);

  Optional<FederatedAggregatePublication> find(UUID operationId);

  FederatedAggregatePublication attachDisclosureReservation(
      UUID operationId, UUID reservationId, Instant changedAt);

  Optional<FederatedAggregatePublication> claimForSend(UUID operationId, Instant changedAt);

  FederatedAggregatePublication transition(
      UUID operationId,
      State expected,
      State next,
      String resultClass,
      Optional<UUID> receiptId,
      Instant changedAt);

  void attachAudit(UUID operationId, UUID auditRecordId);

  record CreateResult(FederatedAggregatePublication publication, boolean created) {}
}
