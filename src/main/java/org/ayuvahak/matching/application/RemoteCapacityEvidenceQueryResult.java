package org.ayuvahak.matching.application;

import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.matching.domain.RemoteCapacityOutcome;
import org.ayuvahak.matching.domain.RemoteCapacityReadiness;
import org.ayuvahak.matching.domain.RemoteEvidenceFreshness;
import org.ayuvahak.matching.domain.RemoteFacilityReference;

/** Result of one peer-backed query; absent evidence is explicit, never numeric zero. */
public record RemoteCapacityEvidenceQueryResult(
    RemoteFacilityReference facility,
    String dimensionCode,
    RemoteCapacityOutcome outcome,
    RemoteEvidenceFreshness freshness,
    Optional<RemoteCapacityReadiness> evidence,
    String reasonCode) {

  public RemoteCapacityEvidenceQueryResult {
    Objects.requireNonNull(facility, "remote Facility reference");
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(freshness, "freshness");
    evidence = Objects.requireNonNull(evidence, "evidence");
    if (reasonCode == null || !reasonCode.matches("[A-Z][A-Z0-9_]{0,79}")) {
      throw new IllegalArgumentException("reason code must be stable");
    }
    if (evidence.isPresent()
        && (evidence.orElseThrow().outcome() != outcome
            || evidence.orElseThrow().freshness() != freshness)) {
      throw new IllegalArgumentException("query result must match its projected evidence");
    }
  }

  public static RemoteCapacityEvidenceQueryResult unavailable(
      RemoteFacilityReference facility, String dimensionCode, String reasonCode) {
    return new RemoteCapacityEvidenceQueryResult(
        facility,
        dimensionCode,
        RemoteCapacityOutcome.UNKNOWN,
        RemoteEvidenceFreshness.UNKNOWN,
        Optional.empty(),
        reasonCode);
  }

  public static RemoteCapacityEvidenceQueryResult from(RemoteCapacityReadiness evidence) {
    return new RemoteCapacityEvidenceQueryResult(
        evidence.facility(),
        evidence.dimensionCode(),
        evidence.outcome(),
        evidence.freshness(),
        Optional.of(evidence),
        evidence.reasonCodes().getFirst());
  }
}
