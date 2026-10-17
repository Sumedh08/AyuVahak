package org.ayuvahak.capacity.domain;

import java.math.BigDecimal;
import java.time.Instant;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;

/** Minimum owner command data shared by the existing and typed Capacity subjects. */
public interface CapacityReservationIntent {

  InternalId operationId();

  CapacityReservationSubject subject();

  ExternalReference facility();

  String dimensionCode();

  String unit();

  BigDecimal quantity();

  Instant intervalStart();

  Instant intervalEnd();

  Instant expiresAt();
}
