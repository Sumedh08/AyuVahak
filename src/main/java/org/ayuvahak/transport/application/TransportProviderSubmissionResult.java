package org.ayuvahak.transport.application;

import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.transport.domain.TransportProviderSubmission;

/** Safe technical result; an acknowledgement is not a dispatch assignment or movement fact. */
public record TransportProviderSubmissionResult(
    TransportProviderSubmission.State state,
    Optional<Integer> configurationVersion,
    Optional<Integer> responseStatus,
    Optional<String> failureCode) {

  public TransportProviderSubmissionResult {
    Objects.requireNonNull(state, "submission state");
    configurationVersion = Objects.requireNonNull(configurationVersion, "configuration version");
    responseStatus = Objects.requireNonNull(responseStatus, "response status");
    failureCode = Objects.requireNonNull(failureCode, "failure code");
  }
}
