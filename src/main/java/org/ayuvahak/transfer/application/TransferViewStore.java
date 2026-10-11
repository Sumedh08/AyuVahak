package org.ayuvahak.transfer.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.transfer.domain.CoordinatedTransfer;
import org.ayuvahak.transfer.domain.TransferRequestAttempt;

/** Transfer-owned read boundary for participant views. */
public interface TransferViewStore {

  Optional<JourneyRows> journey(InternalId journeyId, ExecutionScope scope, long offset, int limit);

  List<IncomingRequest> pendingIncoming(InternalId receivingFacilityId, long offset, int limit);

  List<TransferSummary> transfers(
      ExecutionScope scope, Optional<CoordinatedTransfer.Status> status, long offset, int limit);

  Optional<TransferSummary> transfer(InternalId transferId, ExecutionScope scope);

  record JourneyRows(
      InternalId journeyId,
      InternalId referralId,
      InternalId originOrganizationId,
      Optional<InternalId> originFacilityId,
      Instant createdAt,
      long version,
      List<RequestAttemptView> attempts) {
    public JourneyRows {
      attempts = List.copyOf(attempts);
    }
  }

  record RequestAttemptView(
      InternalId requestAttemptId,
      int attemptNumber,
      InternalId referralId,
      long referralRevision,
      InternalId recommendationId,
      InternalId destinationFacilityId,
      TransferRequestAttempt.Status status,
      Instant requestedAt,
      Instant expiresAt,
      Optional<Instant> terminalOccurredAt,
      Optional<Instant> terminalRecordedAt,
      Optional<String> clinicalOutcome,
      Optional<String> clinicalReasonCode,
      Optional<String> operationalOutcome,
      Optional<String> operationalReasonCode,
      Optional<String> decisionKind,
      Optional<String> decisionReasonCode,
      Optional<InternalId> transferId,
      Optional<String> transferStatus,
      Optional<String> reservationOutcome,
      Optional<InternalId> reservationId) {}

  record IncomingRequest(
      InternalId requestAttemptId,
      InternalId journeyId,
      InternalId referralId,
      long referralRevision,
      InternalId recommendationId,
      InternalId originOrganizationId,
      Optional<InternalId> originFacilityId,
      InternalId receivingFacilityId,
      Instant requestedAt,
      Instant expiresAt) {}

  record TransferSummary(
      InternalId transferId,
      InternalId journeyId,
      InternalId requestAttemptId,
      InternalId referralId,
      InternalId recommendationId,
      InternalId originOrganizationId,
      Optional<InternalId> originFacilityId,
      InternalId receivingOrganizationId,
      InternalId receivingFacilityId,
      CoordinatedTransfer.Status status,
      long version,
      Instant createdAt,
      Instant lastEventAt,
      long lastAggregateSequence,
      Optional<String> reservationOutcome,
      Optional<InternalId> reservationId) {}
}
