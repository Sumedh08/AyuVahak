package org.ayuvahak.directory.application;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.directory.domain.DirectoryConceptType;

/** Fail-closed comparison of a mapped source revision with Directory's last accepted revision. */
public final class DirectorySourceProjectionReconciliation {

  public Outcome decide(
      Optional<DirectorySourceProjectionState> current,
      DirectorySourceProjection incoming,
      DirectoryConceptType targetType,
      boolean targetSupportsDeactivation) {
    Objects.requireNonNull(current, "current projection state");
    Objects.requireNonNull(incoming, "incoming projection");
    Objects.requireNonNull(targetType, "target type");

    if (incoming instanceof DirectorySourceProjection.EndpointMetadata) {
      return Outcome.METADATA_ONLY;
    }

    if (current.isEmpty()) {
      return incoming.active() ? Outcome.CREATE : Outcome.RECONCILIATION_REQUIRED;
    }

    DirectorySourceProjectionState accepted = current.orElseThrow();
    DirectorySourceProjection.Metadata next = incoming.metadata();
    if (!accepted.sourceIdentity().equals(next.sourceIdentity())
        || accepted.conceptType() != targetType
        || !accepted.profileUri().equals(next.profileUri())) {
      return Outcome.CONFLICT;
    }

    // An unprojected source fact can be retried after its owner dependency appears. It still has
    // accepted source revision metadata, so stale or ambiguous retries must not create an owner
    // fact merely because its concept ID is empty.
    if (accepted.conceptId().isEmpty()) {
      Optional<Integer> unprojectedOrder = compareOrderedRevision(accepted, next);
      if (unprojectedOrder.isPresent() && unprojectedOrder.orElseThrow() < 0) {
        return Outcome.STALE;
      }
      if (unprojectedOrder.orElse(1) == 0
          && !accepted.projectionFingerprint().equals(next.projectionFingerprint())) {
        return Outcome.CONFLICT;
      }
      if (sameOpaqueVersion(accepted, next)
          && !accepted.projectionFingerprint().equals(next.projectionFingerprint())) {
        return Outcome.CONFLICT;
      }
      if (unprojectedOrder.isEmpty()
          && !sameOpaqueVersion(accepted, next)
          && !accepted.projectionFingerprint().equals(next.projectionFingerprint())) {
        return Outcome.CONFLICT;
      }
      return incoming.active() ? Outcome.CREATE : Outcome.RECONCILIATION_REQUIRED;
    }

    Optional<Integer> orderedComparison = compareOrderedRevision(accepted, next);
    if (orderedComparison.isPresent()) {
      int comparison = orderedComparison.orElseThrow();
      if (comparison < 0) {
        return Outcome.STALE;
      }
      if (comparison == 0) {
        if (accepted.projectionFingerprint().equals(next.projectionFingerprint())) {
          return incoming.active() == accepted.sourceActive()
              ? Outcome.UNCHANGED
              : deactivationOutcome(targetSupportsDeactivation, incoming.active());
        }
        return Outcome.CONFLICT;
      }
      if (sameOpaqueVersion(accepted, next)) {
        return Outcome.CONFLICT;
      }
      if (!incoming.active()) {
        return targetSupportsDeactivation ? Outcome.DEACTIVATE : Outcome.RECONCILIATION_REQUIRED;
      }
      return Outcome.UPDATE;
    }

    if (sameOpaqueVersion(accepted, next)) {
      return accepted.projectionFingerprint().equals(next.projectionFingerprint())
          ? incoming.active() == accepted.sourceActive()
              ? Outcome.UNCHANGED
              : deactivationOutcome(targetSupportsDeactivation, incoming.active())
          : Outcome.CONFLICT;
    }
    if (accepted.projectionFingerprint().equals(next.projectionFingerprint())
        && incoming.active() == accepted.sourceActive()) {
      return Outcome.UNCHANGED;
    }
    return incoming.active() ? Outcome.CONFLICT : Outcome.RECONCILIATION_REQUIRED;
  }

  private static Optional<Integer> compareOrderedRevision(
      DirectorySourceProjectionState accepted, DirectorySourceProjection.Metadata incoming) {
    if (!accepted.sourceUpdatedAtIsOrderedRevision()
        || !incoming.sourceUpdatedAtIsOrderedRevision()
        || !accepted.profileUri().equals(incoming.profileUri())
        || accepted.sourceUpdatedAt().isEmpty()
        || incoming.sourceUpdatedAt().isEmpty()) {
      return Optional.empty();
    }
    Instant current = accepted.sourceUpdatedAt().orElseThrow();
    Instant next = incoming.sourceUpdatedAt().orElseThrow();
    return Optional.of(next.compareTo(current));
  }

  private static boolean sameOpaqueVersion(
      DirectorySourceProjectionState current, DirectorySourceProjection.Metadata incoming) {
    return current.opaqueVersionId().isPresent()
        && current.opaqueVersionId().equals(incoming.opaqueVersionId());
  }

  private static Outcome deactivationOutcome(boolean targetSupportsDeactivation, boolean active) {
    if (active) {
      return Outcome.CONFLICT;
    }
    return targetSupportsDeactivation ? Outcome.DEACTIVATE : Outcome.RECONCILIATION_REQUIRED;
  }

  public enum Outcome {
    CREATE,
    UPDATE,
    UNCHANGED,
    STALE,
    CONFLICT,
    DEACTIVATE,
    RECONCILIATION_REQUIRED,
    METADATA_ONLY
  }
}
