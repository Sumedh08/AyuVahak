package org.ayuvahak.matching.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Applies local Matching policy to attributed source evidence without changing source authority.
 */
public final class RemoteCapacityEvidenceProjector {

  public RemoteCapacityReadiness project(
      RemoteCapacityEvidence evidence,
      RemoteFacilityReference expectedFacility,
      String expectedDimension,
      RemoteCapacityEvidenceCompatibility compatibility,
      Instant evaluatedAt) {
    Objects.requireNonNull(evidence, "remote Capacity evidence");
    Objects.requireNonNull(expectedFacility, "expected remote Facility reference");
    requireDimension(expectedDimension);
    Objects.requireNonNull(compatibility, "source compatibility policy");
    Objects.requireNonNull(evaluatedAt, "evaluation time");
    if (!evidence.facility().equals(expectedFacility)
        || !evidence.dimensionCode().equals(expectedDimension)) {
      throw new IllegalArgumentException("remote evidence does not match the named request");
    }

    if (evidence.sourceConflict()) {
      return hidden(
          evidence,
          RemoteCapacityOutcome.CONFLICTED,
          RemoteEvidenceFreshness.CONFLICTED,
          "SOURCE_SEQUENCE_CONFLICT");
    }

    if (evidence.dimensionVersion() != compatibility.dimensionVersion()
        || evidence.policyVersion() != compatibility.policyVersion()
        || !evidence.sourceProfileVersion().equals(compatibility.sourceProfileVersion())) {
      return hidden(
          evidence,
          RemoteCapacityOutcome.UNKNOWN,
          RemoteEvidenceFreshness.UNKNOWN,
          "INCOMPATIBLE_SOURCE_POLICY");
    }
    if (!evidence.complete()) {
      return hidden(
          evidence,
          RemoteCapacityOutcome.UNKNOWN,
          RemoteEvidenceFreshness.UNKNOWN,
          "INCOMPLETE_SOURCE_RESPONSE");
    }
    if (evidence.observedAt().isAfter(evidence.receivedAt())) {
      return hidden(
          evidence,
          RemoteCapacityOutcome.UNKNOWN,
          RemoteEvidenceFreshness.UNKNOWN,
          "SOURCE_TIME_INVALID");
    }
    if (!evaluatedAt.isBefore(evidence.validUntil())) {
      return hidden(
          evidence,
          RemoteCapacityOutcome.STALE,
          RemoteEvidenceFreshness.EXPIRED,
          "SOURCE_EVIDENCE_EXPIRED");
    }
    if (evidence.freshness() == RemoteEvidenceFreshness.STALE
        || evidence.freshness() == RemoteEvidenceFreshness.EXPIRED) {
      return hidden(
          evidence, RemoteCapacityOutcome.STALE, evidence.freshness(), "SOURCE_EVIDENCE_STALE");
    }
    if (evidence.freshness() != RemoteEvidenceFreshness.CURRENT) {
      return hidden(
          evidence,
          evidence.freshness() == RemoteEvidenceFreshness.CONFLICTED
              ? RemoteCapacityOutcome.CONFLICTED
              : RemoteCapacityOutcome.UNKNOWN,
          evidence.freshness(),
          "SOURCE_EVIDENCE_UNAVAILABLE");
    }
    if (evidence.outcome() != RemoteCapacityOutcome.READY
        && evidence.outcome() != RemoteCapacityOutcome.NOT_READY) {
      return hidden(
          evidence, evidence.outcome(), evidence.freshness(), "SOURCE_READINESS_UNAVAILABLE");
    }
    if (evidence.outcome() == RemoteCapacityOutcome.READY
        && evidence.quantity().isEmpty()
        && evidence.state().isEmpty()) {
      return hidden(
          evidence,
          RemoteCapacityOutcome.UNKNOWN,
          RemoteEvidenceFreshness.UNKNOWN,
          "SOURCE_VALUE_MISSING");
    }

    if (evidence.outcome() == RemoteCapacityOutcome.NOT_READY) {
      return new RemoteCapacityReadiness(
          evidence.facility(),
          evidence.dimensionCode(),
          RemoteCapacityOutcome.NOT_READY,
          RemoteEvidenceFreshness.CURRENT,
          evidence.sourceSequence(),
          evidence.dimensionVersion(),
          evidence.policyVersion(),
          evidence.sourceProfileVersion(),
          Optional.of(evidence.evidenceId()),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          evidence.observedAt(),
          evidence.receivedAt(),
          evidence.validUntil(),
          List.of("SOURCE_NOT_READY"));
    }

    return new RemoteCapacityReadiness(
        evidence.facility(),
        evidence.dimensionCode(),
        evidence.outcome(),
        RemoteEvidenceFreshness.CURRENT,
        evidence.sourceSequence(),
        evidence.dimensionVersion(),
        evidence.policyVersion(),
        evidence.sourceProfileVersion(),
        Optional.of(evidence.evidenceId()),
        evidence.quantity(),
        evidence.state(),
        evidence.unit(),
        evidence.observedAt(),
        evidence.receivedAt(),
        evidence.validUntil(),
        List.of("SOURCE_READY"));
  }

  private static RemoteCapacityReadiness hidden(
      RemoteCapacityEvidence evidence,
      RemoteCapacityOutcome outcome,
      RemoteEvidenceFreshness freshness,
      String reason) {
    return new RemoteCapacityReadiness(
        evidence.facility(),
        evidence.dimensionCode(),
        outcome,
        freshness,
        evidence.sourceSequence(),
        evidence.dimensionVersion(),
        evidence.policyVersion(),
        evidence.sourceProfileVersion(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        evidence.observedAt(),
        evidence.receivedAt(),
        evidence.validUntil(),
        List.of(reason));
  }

  private static void requireDimension(String dimension) {
    if (dimension == null || !dimension.matches("[A-Z][A-Z0-9._-]{0,63}")) {
      throw new IllegalArgumentException("dimension code must be a stable identifier");
    }
  }
}
