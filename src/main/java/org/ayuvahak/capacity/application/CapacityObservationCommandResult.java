package org.ayuvahak.capacity.application;

import java.time.Instant;
import java.util.Optional;
import org.ayuvahak.capacity.domain.CapacityObservationKind;
import org.ayuvahak.platform.core.InternalId;

public record CapacityObservationCommandResult(
    InternalId id,
    String dimensionCode,
    CapacityObservationKind kind,
    Instant receivedAt,
    Optional<Instant> validUntil,
    boolean futureSkewed,
    boolean commandReplayed,
    boolean sourceReplayed,
    int responseStatus) {}
