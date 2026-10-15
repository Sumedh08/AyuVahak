package org.ayuvahak.transport.application;

import java.time.Instant;
import java.util.Optional;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.transport.domain.TransportAssignmentObservation;
import org.ayuvahak.transport.domain.TransportEtaObservation;
import org.ayuvahak.transport.domain.TransportRequest;
import org.ayuvahak.transport.domain.TransportStatusObservation;

/** Persistence port for immutable Transport operational evidence. */
public interface TransportEvidenceStore {

  Optional<EvidenceReplay> findStatus(
      InternalId requestId, String sourceNamespace, String sourceObservationId);

  Optional<EvidenceReplay> findAssignment(
      InternalId requestId, String sourceNamespace, String sourceObservationId);

  Optional<EvidenceReplay> findEta(
      InternalId requestId, String sourceNamespace, String sourceObservationId);

  Optional<Long> latestAppliedStatusSequence(InternalId requestId, String sourceNamespace);

  Optional<Instant> latestAppliedStatusTime(InternalId requestId, String sourceNamespace);

  Optional<Instant> latestEtaTime(InternalId requestId, String sourceNamespace);

  boolean updateStatus(
      InternalId requestId,
      long expectedVersion,
      TransportRequest.Status nextStatus,
      long nextVersion,
      Instant recordedAt);

  boolean insertStatus(TransportStatusObservation observation, String fingerprint);

  boolean insertAssignment(TransportAssignmentObservation observation, String fingerprint);

  boolean insertEta(TransportEtaObservation observation, String fingerprint);

  record EvidenceReplay(
      InternalId observationId,
      String fingerprint,
      String outcome,
      String requestStatus,
      Optional<Instant> validUntil) {}
}
