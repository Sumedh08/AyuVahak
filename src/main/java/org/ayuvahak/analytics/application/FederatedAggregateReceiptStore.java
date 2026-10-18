package org.ayuvahak.analytics.application;

import java.time.Instant;
import org.ayuvahak.analytics.domain.FederatedAggregateReceipt;
import org.ayuvahak.analytics.domain.FederatedAggregateSnapshot;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.exchange.application.FederationOperationFingerprint;
import org.ayuvahak.platform.exchange.application.FederationPeerInboundAuthorization;

/** Durable receiver-side deduplication and safe aggregate evidence. */
public interface FederatedAggregateReceiptStore {

  RecordResult recordOrReplay(
      FederationPeerInboundAuthorization authorization,
      ExternalReference targetOrganization,
      FederatedAggregateSnapshot snapshot,
      java.util.UUID operationId,
      FederationOperationFingerprint operationFingerprint,
      FederatedAggregateReceipt.State state,
      String resultClass,
      String mappingKey,
      Integer mappingVersion,
      Instant receivedAt);

  void attachAudit(java.util.UUID receiptId, java.util.UUID auditRecordId);

  record RecordResult(FederatedAggregateReceipt receipt, boolean created) {}
}
