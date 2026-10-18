package org.ayuvahak.platform.exchange.domain;

import java.util.List;
import java.util.Objects;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Direction;

/** Finite Phase 11 technical allowlist; semantic handling remains with each named owner. */
public final class FederationPeerOperations {

  private static final List<AllowedOperation> ALLOWED =
      List.of(
          AllowedOperation.directoryRemoteDiscovery(Direction.OUTBOUND),
          AllowedOperation.directoryRemoteDiscovery(Direction.INBOUND),
          AllowedOperation.capacityRemoteEvidence(Direction.OUTBOUND),
          AllowedOperation.capacityRemoteEvidence(Direction.INBOUND),
          AllowedOperation.transferRemoteReceivingRequest(Direction.OUTBOUND),
          AllowedOperation.transferRemoteReceivingRequest(Direction.INBOUND),
          AllowedOperation.transferRemoteReservationStatus(Direction.OUTBOUND),
          AllowedOperation.transferRemoteReservationStatus(Direction.INBOUND),
          AllowedOperation.transferMovementFact(Direction.OUTBOUND),
          AllowedOperation.transferMovementFact(Direction.INBOUND),
          AllowedOperation.analyticsAggregateSnapshot(Direction.OUTBOUND),
          AllowedOperation.analyticsAggregateSnapshot(Direction.INBOUND));

  private FederationPeerOperations() {}

  public static List<AllowedOperation> all() {
    return ALLOWED;
  }

  public static boolean supports(AllowedOperation operation) {
    return ALLOWED.contains(Objects.requireNonNull(operation, "operation"));
  }

  public static boolean supportsOutbound(AllowedOperation operation) {
    return operation != null && operation.direction() == Direction.OUTBOUND && supports(operation);
  }

  public static boolean supportsInbound(AllowedOperation operation) {
    return operation != null && operation.direction() == Direction.INBOUND && supports(operation);
  }
}
