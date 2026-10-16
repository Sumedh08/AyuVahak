package org.ayuvahak.transport.application;

import java.time.Instant;
import java.util.Optional;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.transport.domain.TransportProviderSubmission;

/** Persistence boundary for Transport-owned provider submission outcomes. */
public interface TransportProviderSubmissionStore {

  Optional<TransportProviderSubmission> find(
      TransportProviderSubmission.OperationKind kind, InternalId operationReference);

  Optional<TransportProviderSubmission> findForUpdate(
      TransportProviderSubmission.OperationKind kind, InternalId operationReference);

  boolean insert(TransportProviderSubmission submission);

  boolean transition(
      InternalId operationReference,
      TransportProviderSubmission.OperationKind kind,
      long expectedVersion,
      TransportProviderSubmission.State state,
      Optional<Integer> configurationVersion,
      Optional<Integer> responseStatus,
      Optional<String> failureCode,
      Instant updatedAt);

  boolean claimRetry(
      InternalId operationReference,
      TransportProviderSubmission.OperationKind kind,
      long expectedVersion,
      Instant updatedAt);
}
