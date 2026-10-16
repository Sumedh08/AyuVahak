package org.ayuvahak.transport.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.transport.domain.TransportRequest;

/** Bounded, Transport-owned read projection for participant views. */
public interface TransportRequestReadStore {

  List<RequestRow> requestsForTransfer(InternalId transferId, long offset, int limit);

  Optional<RequestRow> request(InternalId requestId);

  List<StatusRow> statuses(InternalId requestId, int limit);

  List<AssignmentRow> assignments(InternalId requestId, int limit);

  List<EtaRow> etas(InternalId requestId, int limit);

  List<CancellationRow> cancellations(InternalId requestId, int limit);

  List<DestinationChangeRow> destinationChanges(InternalId requestId, int limit);

  List<DestinationChangeEvidenceRow> destinationChangeEvidence(InternalId operationId, int limit);

  List<ProviderSubmissionRow> providerSubmissions(InternalId requestId, int limit);

  record RequestRow(
      InternalId requestId,
      InternalId transferId,
      InternalId originFacilityId,
      InternalId destinationFacilityId,
      TransportRequest.Status status,
      Instant requestedAt,
      CorrelationId correlationId,
      long version) {}

  record StatusRow(
      InternalId observationId,
      String sourceNamespace,
      String sourceObservationId,
      TransportRequest.Status status,
      Instant occurredAt,
      Instant recordedAt,
      String outcome,
      TransportRequest.Status requestStatusAfter,
      Optional<String> reasonCode,
      long resultingRequestVersion) {}

  record AssignmentRow(
      InternalId observationId,
      String sourceNamespace,
      String sourceObservationId,
      String assignmentReference,
      Instant occurredAt,
      Instant recordedAt,
      String outcome,
      TransportRequest.Status requestStatusAfter,
      long resultingRequestVersion) {}

  record EtaRow(
      InternalId observationId,
      String sourceNamespace,
      String sourceObservationId,
      Instant estimatedArrivalAt,
      Instant observedAt,
      Instant recordedAt,
      Instant validUntil) {}

  record CancellationRow(
      InternalId operationId,
      String reasonCode,
      String state,
      Optional<String> outcome,
      Instant initiatedAt,
      Optional<Instant> resolvedAt,
      long version) {}

  record DestinationChangeRow(
      InternalId operationId,
      InternalId transferOperationId,
      InternalId transferId,
      InternalId oldFacilityId,
      InternalId proposedFacilityId,
      String reasonCode,
      String state,
      Optional<String> outcome,
      Instant createdAt,
      Instant updatedAt,
      long version,
      List<DestinationChangeEvidenceRow> evidence) {
    public DestinationChangeRow {
      evidence = List.copyOf(evidence);
    }
  }

  record DestinationChangeEvidenceRow(
      InternalId evidenceId,
      String reportedOutcome,
      String sourceNamespace,
      String sourceObservationId,
      Instant sourceOccurredAt,
      Instant recordedAt,
      String disposition,
      Optional<String> reasonCode) {}

  record ProviderSubmissionRow(
      InternalId operationReference,
      String operationKind,
      String partnerKey,
      String state,
      Optional<Integer> configurationVersion,
      Optional<Integer> responseStatus,
      Optional<String> failureCode,
      Instant createdAt,
      Instant updatedAt,
      long version) {}
}
