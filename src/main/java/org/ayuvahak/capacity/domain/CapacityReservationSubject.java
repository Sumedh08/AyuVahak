package org.ayuvahak.capacity.domain;

import java.util.Objects;
import org.ayuvahak.platform.core.InternalId;

/** Typed owner-scoped identity for a Capacity reservation subject. */
public record CapacityReservationSubject(Kind kind, InternalId id) {

  public CapacityReservationSubject {
    Objects.requireNonNull(kind, "subject kind");
    Objects.requireNonNull(id, "subject id");
  }

  public static CapacityReservationSubject transfer(InternalId transferId) {
    return new CapacityReservationSubject(Kind.TRANSFER, transferId);
  }

  public static CapacityReservationSubject federatedReceivingRequest(InternalId requestId) {
    return new CapacityReservationSubject(Kind.FEDERATED_RECEIVING_REQUEST, requestId);
  }

  public enum Kind {
    TRANSFER,
    FEDERATED_RECEIVING_REQUEST
  }
}
