package org.ayuvahak.transfer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.junit.jupiter.api.Test;

class CapacityReservationIntentTest {

  @Test
  void destinationReservationCanHaveAnOperationIdDistinctFromItsTransfer() {
    InternalId transferId = InternalId.generate();
    InternalId reservationOperationId = InternalId.generate();
    Instant start = Instant.parse("2026-10-16T10:00:00Z");
    Instant end = start.plusSeconds(3600);

    CapacityReservationIntent intent =
        new CapacityReservationIntent(
            reservationOperationId,
            transferId,
            directory("Organization", InternalId.generate()),
            directory("Facility", InternalId.generate()),
            Optional.of("TRANSFER_COORDINATION_SLOT"),
            Optional.of("slot"),
            Optional.of(BigDecimal.ONE),
            Optional.of(start),
            Optional.of(end),
            Optional.of(end),
            CapacityReservationIntent.Outcome.PENDING,
            Optional.empty(),
            Optional.empty(),
            0,
            start);

    assertThat(intent.operationId()).isEqualTo(reservationOperationId);
    assertThat(intent.transferId()).isEqualTo(transferId);
    assertThat(intent.operationId()).isNotEqualTo(intent.transferId());
  }

  private static ExternalReference directory(String type, InternalId id) {
    return new ExternalReference(new IssuerId("urn:ayuvahak:directory"), type, id.toString());
  }
}
