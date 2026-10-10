package org.ayuvahak.matching.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.regex.Pattern;
import org.ayuvahak.matching.domain.RecommendationRanker.CandidateStatus;
import org.ayuvahak.matching.domain.RecommendationRanker.Measure;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;

/** Immutable minimal evidence for why Matching produced an ordered or non-positive result. */
public record RecommendationSnapshot(
    InternalId id,
    InternalId referralId,
    long referralRevision,
    Instant generatedAt,
    Instant applicableUntil,
    PolicyVersion matchingPolicy,
    PolicyVersion mappingPolicy,
    List<CapabilityDimensionMapping> resolvedMappings,
    EvaluationOutcome outcome,
    List<String> reasonCodes,
    List<CandidateEvidence> candidates,
    Attribution attribution) {

  private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9._-]{0,79}");

  public RecommendationSnapshot {
    Objects.requireNonNull(id, "recommendation id");
    Objects.requireNonNull(referralId, "Referral id");
    if (referralRevision < 1) {
      throw new IllegalArgumentException("Referral revision must be positive");
    }
    Objects.requireNonNull(generatedAt, "generated time");
    Objects.requireNonNull(applicableUntil, "applicability deadline");
    if (!applicableUntil.isAfter(generatedAt)) {
      throw new IllegalArgumentException("applicability deadline must follow generation time");
    }
    Objects.requireNonNull(matchingPolicy, "Matching policy version");
    Objects.requireNonNull(mappingPolicy, "mapping policy version");
    resolvedMappings = List.copyOf(Objects.requireNonNull(resolvedMappings, "resolved mappings"));
    if (resolvedMappings.stream().anyMatch(Objects::isNull)
        || resolvedMappings.stream()
                .map(CapabilityDimensionMapping::capabilityCode)
                .distinct()
                .count()
            != resolvedMappings.size()) {
      throw new IllegalArgumentException("resolved capability mappings must be unique and present");
    }
    Objects.requireNonNull(outcome, "evaluation outcome");
    reasonCodes = normalizedCodes(reasonCodes, "evaluation reason codes");
    candidates = List.copyOf(Objects.requireNonNull(candidates, "candidate evidence"));
    if (candidates.stream().anyMatch(Objects::isNull)
        || candidates.stream().map(CandidateEvidence::facility).distinct().count()
            != candidates.size()) {
      throw new IllegalArgumentException("candidate evidence must be unique and present");
    }
    validateRanks(outcome, candidates);
    validateApplicabilityDeadline(generatedAt, applicableUntil, candidates);
    Objects.requireNonNull(attribution, "evaluation attribution");
  }

  private static void validateRanks(EvaluationOutcome outcome, List<CandidateEvidence> candidates) {
    List<Integer> ranks =
        candidates.stream()
            .map(CandidateEvidence::rank)
            .filter(OptionalInt::isPresent)
            .mapToInt(OptionalInt::getAsInt)
            .boxed()
            .sorted()
            .toList();
    if (outcome == EvaluationOutcome.INDETERMINATE && !ranks.isEmpty()) {
      throw new IllegalArgumentException(
          "an indeterminate evaluation cannot contain ranked candidates");
    }
    for (int index = 0; index < ranks.size(); index++) {
      if (ranks.get(index) != index + 1) {
        throw new IllegalArgumentException("snapshot ranks must be unique and contiguous from one");
      }
    }
  }

  private static void validateApplicabilityDeadline(
      Instant generatedAt, Instant applicableUntil, List<CandidateEvidence> candidates) {
    boolean futureEvidence =
        candidates.stream()
            .flatMap(candidate -> candidate.evidence().stream())
            .anyMatch(evidence -> evidence.observedAt().isAfter(generatedAt));
    boolean futureMetric =
        candidates.stream()
            .flatMap(candidate -> candidate.measure().stream())
            .anyMatch(measure -> measure.observedAt().isAfter(generatedAt));
    boolean beyondEvidenceValidity =
        candidates.stream()
            .flatMap(candidate -> candidate.evidence().stream())
            .anyMatch(
                evidence ->
                    "CAPACITY_DIMENSION".equals(evidence.factCode())
                        && evidence.validUntil().isPresent()
                        && applicableUntil.isAfter(evidence.validUntil().orElseThrow()));
    boolean beyondMetricValidity =
        candidates.stream()
            .flatMap(candidate -> candidate.measure().stream())
            .anyMatch(
                measure ->
                    measure.kind() == RecommendationRanker.MetricKind.TRAVEL_TIME
                        && applicableUntil.isAfter(measure.validUntil()));
    if (futureEvidence
        || futureMetric
        || beyondEvidenceValidity
        || beyondMetricValidity
        || !applicableUntil.isAfter(generatedAt)) {
      throw new IllegalArgumentException(
          "snapshot timing cannot precede evidence or exceed relevant evidence validity");
    }
  }

  public record PolicyVersion(ExternalReference reference, long version) {

    public PolicyVersion {
      Objects.requireNonNull(reference, "policy reference");
      if (!Set.of("MatchingPolicy", "CapabilityOperationalRequirementPolicy")
              .contains(reference.resourceType())
          || version < 1) {
        throw new IllegalArgumentException("policy reference and version are invalid");
      }
    }
  }

  public record CapabilityDimensionMapping(String capabilityCode, List<String> dimensionCodes) {

    public CapabilityDimensionMapping {
      requireCode(capabilityCode, "CareCapability code");
      dimensionCodes = normalizedCodes(dimensionCodes, "CapacityDimension codes");
      if (dimensionCodes.isEmpty()) {
        throw new IllegalArgumentException(
            "each resolved CareCapability mapping must be non-empty");
      }
    }
  }

  public record CandidateEvidence(
      ExternalReference facility,
      ExternalReference organization,
      CandidateStatus status,
      OptionalInt rank,
      OptionalInt preferenceOrdinal,
      Optional<Measure> measure,
      List<CandidateReason> reasons,
      List<DirectoryCapabilityAssessment> directoryCapabilityAssessments,
      List<EvidenceFact> evidence) {

    public CandidateEvidence {
      requireType(facility, "Facility", "candidate Facility");
      requireType(organization, "Organization", "candidate Organization");
      Objects.requireNonNull(status, "candidate status");
      rank = Objects.requireNonNull(rank, "candidate rank");
      preferenceOrdinal = Objects.requireNonNull(preferenceOrdinal, "preference ordinal");
      measure = Objects.requireNonNull(measure, "candidate measure");
      reasons = List.copyOf(Objects.requireNonNull(reasons, "candidate reasons"));
      directoryCapabilityAssessments =
          List.copyOf(
              Objects.requireNonNull(
                  directoryCapabilityAssessments, "Directory capability assessments"));
      evidence = List.copyOf(Objects.requireNonNull(evidence, "candidate evidence facts"));
      if (reasons.stream().anyMatch(Objects::isNull)
          || directoryCapabilityAssessments.stream().anyMatch(Objects::isNull)
          || evidence.stream().anyMatch(Objects::isNull)) {
        throw new IllegalArgumentException("candidate evidence cannot contain null");
      }
      if (directoryCapabilityAssessments.stream()
              .map(DirectoryCapabilityAssessment::capabilityCode)
              .distinct()
              .count()
          != directoryCapabilityAssessments.size()) {
        throw new IllegalArgumentException("Directory capability assessments must be unique");
      }
      boolean ranked = status == CandidateStatus.RANKED_ELIGIBLE;
      if (ranked != rank.isPresent() || ranked != measure.isPresent()) {
        throw new IllegalArgumentException(
            "only ranked eligible candidates may have rank and metric");
      }
      if (ranked && rank.getAsInt() < 1) {
        throw new IllegalArgumentException("candidate rank must be positive");
      }
      if (ranked && preferenceOrdinal.isPresent() && preferenceOrdinal.getAsInt() < 1) {
        throw new IllegalArgumentException("preference ordinal must be positive");
      }
      if (!ranked && preferenceOrdinal.isPresent()) {
        throw new IllegalArgumentException(
            "non-ranked candidates cannot have a preference ordinal");
      }
      Set<String> factKeys = new HashSet<>();
      for (EvidenceFact fact : evidence) {
        if (!factKeys.add(fact.factCode() + "|" + fact.reference())) {
          throw new IllegalArgumentException("candidate evidence facts must be unique");
        }
      }
    }
  }

  /** Directory's assessment is retained even when no FacilityCapabilityAssertion exists. */
  public record DirectoryCapabilityAssessment(
      String capabilityCode,
      String outcomeCode,
      OptionalLong capabilityVersion,
      Optional<String> capabilityStatus,
      ExternalReference sourceSystem) {

    public DirectoryCapabilityAssessment {
      requireCode(capabilityCode, "CareCapability code");
      requireCode(outcomeCode, "Directory capability assessment outcome");
      capabilityVersion = Objects.requireNonNull(capabilityVersion, "capability version");
      if (capabilityVersion.isPresent() && capabilityVersion.getAsLong() < 1) {
        throw new IllegalArgumentException("capability version must be positive when present");
      }
      capabilityStatus = Objects.requireNonNull(capabilityStatus, "capability status");
      capabilityStatus.ifPresent(status -> requireCode(status, "capability status"));
      Objects.requireNonNull(sourceSystem, "Directory source system");
    }
  }

  /** One selected, source-attributed fact; never a serialized source-system record. */
  public record EvidenceFact(
      String factCode,
      String subjectCode,
      ExternalReference reference,
      ExternalReference sourceSystem,
      long sourceVersion,
      OptionalLong policyVersion,
      Optional<String> projectionVersion,
      String outcomeCode,
      Optional<BigDecimal> value,
      Optional<String> unit,
      Instant observedAt,
      Optional<Instant> receivedAt,
      Optional<Instant> validUntil,
      Optional<ExternalReference> provenanceReference) {

    public EvidenceFact {
      requireCode(factCode, "evidence fact code");
      requireCode(subjectCode, "evidence subject code");
      Objects.requireNonNull(reference, "evidence reference");
      Objects.requireNonNull(sourceSystem, "evidence source system");
      if (sourceVersion < 1) {
        throw new IllegalArgumentException("source version must be positive");
      }
      policyVersion = Objects.requireNonNull(policyVersion, "owner policy version");
      if (policyVersion.isPresent() && policyVersion.getAsLong() < 1) {
        throw new IllegalArgumentException("owner policy version must be positive");
      }
      projectionVersion = Objects.requireNonNull(projectionVersion, "owner projection version");
      projectionVersion.ifPresent(
          projection -> {
            if (!projection.matches("[a-z][a-z0-9-]{0,63}")) {
              throw new IllegalArgumentException("projection version must be stable");
            }
          });
      requireCode(outcomeCode, "evidence outcome code");
      value = Objects.requireNonNull(value, "evidence value");
      unit = Objects.requireNonNull(unit, "evidence unit");
      if (value.isPresent() != unit.isPresent()
          || value.filter(number -> number.signum() < 0).isPresent()
          || unit.filter(String::isBlank).isPresent()) {
        throw new IllegalArgumentException("evidence value and unit must be valid together");
      }
      Objects.requireNonNull(observedAt, "evidence observation time");
      receivedAt = receivedAt == null ? Optional.empty() : receivedAt;
      validUntil = Objects.requireNonNull(validUntil, "evidence validity end");
      if (validUntil.filter(until -> !until.isAfter(observedAt)).isPresent()) {
        throw new IllegalArgumentException("evidence validity must follow observation time");
      }
      provenanceReference = Objects.requireNonNull(provenanceReference, "provenance reference");
      provenanceReference.ifPresent(Objects::requireNonNull);
    }
  }

  public record CandidateReason(String code, Optional<String> subjectCode) {

    public CandidateReason {
      requireCode(code, "candidate reason code");
      subjectCode = Objects.requireNonNull(subjectCode, "reason subject");
      subjectCode.ifPresent(subject -> requireCode(subject, "reason subject code"));
    }
  }

  public record Attribution(
      ActorReference actor,
      ExternalReference organization,
      Optional<ExternalReference> facility,
      CorrelationId correlationId,
      String purposeCode) {

    public Attribution {
      Objects.requireNonNull(actor, "evaluation actor or service");
      requireType(organization, "Organization", "execution organization");
      facility = Objects.requireNonNull(facility, "execution facility");
      facility.ifPresent(reference -> requireType(reference, "Facility", "execution facility"));
      Objects.requireNonNull(correlationId, "correlation identifier");
      if (!"MATCHING_EVALUATION".equals(purposeCode)) {
        throw new IllegalArgumentException("evaluation purpose must be MATCHING_EVALUATION");
      }
    }
  }

  public enum EvaluationOutcome {
    COMPLETE,
    INDETERMINATE
  }

  private static List<String> normalizedCodes(List<String> codes, String label) {
    List<String> supplied = List.copyOf(Objects.requireNonNull(codes, label));
    supplied.forEach(code -> requireCode(code, label));
    List<String> normalized = supplied.stream().distinct().sorted().toList();
    if (normalized.size() != supplied.size()) {
      throw new IllegalArgumentException(label + " must be unique");
    }
    return normalized;
  }

  private static void requireCode(String value, String label) {
    if (value == null || !CODE.matcher(value).matches()) {
      throw new IllegalArgumentException(label + " must be a stable uppercase code");
    }
  }

  private static void requireType(ExternalReference reference, String type, String label) {
    Objects.requireNonNull(reference, label);
    if (!type.equals(reference.resourceType())) {
      throw new IllegalArgumentException(label + " must reference " + type);
    }
  }
}
