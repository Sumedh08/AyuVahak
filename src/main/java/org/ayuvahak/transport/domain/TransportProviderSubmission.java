package org.ayuvahak.transport.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.InternalId;

/** Transport-owned technical submission state; it stores no provider payload or response body. */
public record TransportProviderSubmission(
    InternalId operationReference,
    InternalId transportRequestId,
    OperationKind operationKind,
    String partnerKey,
    State state,
    Optional<Integer> configurationVersion,
    Optional<Integer> responseStatus,
    Optional<String> failureCode,
    Instant createdAt,
    Instant updatedAt,
    long version) {

  public TransportProviderSubmission {
    Objects.requireNonNull(operationReference, "stable operation reference");
    Objects.requireNonNull(transportRequestId, "Transport request id");
    Objects.requireNonNull(operationKind, "operation kind");
    if (partnerKey == null || !partnerKey.matches("[A-Z0-9][A-Z0-9._-]{0,63}")) {
      throw new IllegalArgumentException("provider partner key is invalid");
    }
    Objects.requireNonNull(state, "submission state");
    configurationVersion = Objects.requireNonNull(configurationVersion, "configuration version");
    responseStatus = Objects.requireNonNull(responseStatus, "response status");
    failureCode = Objects.requireNonNull(failureCode, "failure code");
    Objects.requireNonNull(createdAt, "creation time");
    Objects.requireNonNull(updatedAt, "update time");
    if (version < 1 || updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("provider submission version/time is invalid");
    }
    if (configurationVersion.filter(value -> value < 1).isPresent()
        || responseStatus.filter(value -> value < 100 || value > 599).isPresent()
        || failureCode.filter(value -> !value.matches("[A-Z][A-Z0-9_]{1,63}")).isPresent()) {
      throw new IllegalArgumentException("provider submission metadata is invalid");
    }
    if ((state == State.ACKNOWLEDGED) != responseStatus.isPresent()) {
      throw new IllegalArgumentException(
          "only acknowledged provider submissions carry HTTP status");
    }
    if ((state == State.OUTCOME_UNKNOWN || state == State.REJECTED || state == State.REVOKED)
        != failureCode.isPresent()) {
      throw new IllegalArgumentException(
          "provider submission failure metadata does not match state");
    }
  }

  public enum OperationKind {
    DISPATCH,
    CANCELLATION,
    DESTINATION_CHANGE
  }

  public enum State {
    ATTEMPTING,
    ACKNOWLEDGED,
    OUTCOME_UNKNOWN,
    REJECTED,
    REVOKED
  }
}
