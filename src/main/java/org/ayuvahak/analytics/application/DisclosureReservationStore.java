package org.ayuvahak.analytics.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.ayuvahak.analytics.domain.DisclosureCandidate;
import org.ayuvahak.analytics.domain.DisclosurePolicyActivation;
import org.ayuvahak.analytics.domain.DisclosurePolicyVersion;
import org.ayuvahak.analytics.domain.DisclosureReservation;
import org.ayuvahak.analytics.domain.DisclosureReservationState;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.ExternalReference;

/** Analytics-owned serialization and durable-history boundary for disclosure decisions. */
public interface DisclosureReservationStore {

  Optional<DisclosurePolicyVersion> activePolicy(String policyKey);

  DisclosurePolicyActivation activatePolicy(DisclosurePolicyVersion proposed, Instant activatedAt);

  DisclosureReleaseContext lockForRelease(DisclosureCandidate candidate);

  DisclosureReservation recordDecision(
      DisclosureCandidate candidate,
      DisclosureReleaseContext lockedHistory,
      DisclosureReservationState state,
      String reasonCode,
      Instant recordedAt);

  DisclosureReservation recordKnownExternalBaseline(
      DisclosureCandidate candidate, DisclosureReleaseContext lockedHistory, Instant recordedAt);

  Optional<DisclosureReservation> findReservation(UUID reservationId);

  Optional<ExternalReference> organizationForReservation(UUID reservationId);

  DisclosureReservation transition(
      UUID reservationId,
      DisclosureReservationState targetState,
      String reasonCode,
      Instant changedAt);

  DisclosureReservation resolveReviewRequired(
      UUID reservationId,
      String expectedOperationFingerprint,
      DisclosureReservationState targetState,
      UUID evidenceReference,
      ActorReference reviewer,
      UUID auditRecordId,
      Instant resolvedAt);

  void attachPolicyAudit(String policyKey, int policyVersion, UUID auditRecordId);

  void attachReservationAudit(UUID reservationId, UUID auditRecordId);
}
