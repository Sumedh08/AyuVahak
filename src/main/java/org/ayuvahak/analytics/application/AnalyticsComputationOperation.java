package org.ayuvahak.analytics.application;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;

/** Durable, restartable Analytics work request containing only scoped aggregate parameters. */
public record AnalyticsComputationOperation(
    InternalId requestId,
    String fingerprint,
    AnalyticsComputationRequest request,
    ExternalReference organization,
    Optional<ExternalReference> facility,
    ActorReference requester,
    CorrelationId correlationId,
    State state,
    int attemptCount,
    Instant availableAt,
    Optional<Instant> leaseUntil,
    Optional<UUID> snapshotId,
    Optional<String> failureClass,
    Instant createdAt,
    Instant updatedAt) {

  public AnalyticsComputationOperation {
    Objects.requireNonNull(requestId, "request id");
    if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("computation request fingerprint is invalid");
    }
    Objects.requireNonNull(request, "computation request");
    Objects.requireNonNull(organization, "organization");
    facility = Objects.requireNonNull(facility, "facility");
    facility.ifPresent(
        reference -> {
          if (!"Facility".equals(reference.resourceType())
              || !organization.issuer().equals(reference.issuer())) {
            throw new IllegalArgumentException("computation facility scope is invalid");
          }
        });
    Objects.requireNonNull(requester, "requester");
    if (requester.kind() != ActorKind.SERVICE) {
      throw new IllegalArgumentException("Analytics work must be requested by a service identity");
    }
    Objects.requireNonNull(correlationId, "correlation id");
    Objects.requireNonNull(state, "state");
    if (attemptCount < 0 || attemptCount > 8) {
      throw new IllegalArgumentException("computation attempt count is outside its bound");
    }
    Objects.requireNonNull(availableAt, "available at");
    leaseUntil = Objects.requireNonNull(leaseUntil, "lease until");
    snapshotId = Objects.requireNonNull(snapshotId, "snapshot id");
    failureClass = Objects.requireNonNull(failureClass, "failure class");
    failureClass.ifPresent(
        value -> {
          if (!value.matches("[A-Z][A-Z0-9_]{0,47}")) {
            throw new IllegalArgumentException("computation failure class is invalid");
          }
        });
    Objects.requireNonNull(createdAt, "created at");
    Objects.requireNonNull(updatedAt, "updated at");
    if (!request.computationRequestId().equals(requestId)) {
      throw new IllegalArgumentException("operation and computation request IDs must match");
    }
  }

  public AnalyticsComputationOperation(
      InternalId requestId,
      String fingerprint,
      AnalyticsComputationRequest request,
      ExternalReference organization,
      ActorReference requester,
      CorrelationId correlationId,
      State state,
      int attemptCount,
      Instant availableAt,
      Optional<Instant> leaseUntil,
      Optional<UUID> snapshotId,
      Optional<String> failureClass,
      Instant createdAt,
      Instant updatedAt) {
    this(
        requestId,
        fingerprint,
        request,
        organization,
        Optional.empty(),
        requester,
        correlationId,
        state,
        attemptCount,
        availableAt,
        leaseUntil,
        snapshotId,
        failureClass,
        createdAt,
        updatedAt);
  }

  public enum State {
    PENDING,
    PROCESSING,
    RETRY_PENDING,
    COMPLETED,
    FAILED_PERMANENT
  }
}
