package org.ayuvahak.transport.application;

import java.time.Instant;
import java.util.Optional;
import org.ayuvahak.platform.core.InternalId;

/** Safe owner response for a recorded Transport assignment, status, or ETA observation. */
public record TransportEvidenceCommandResult(
    InternalId observationId,
    InternalId transportRequestId,
    String requestStatus,
    String evidenceOutcome,
    Optional<Instant> validUntil,
    boolean commandReplayed,
    boolean sourceReplayed) {}
