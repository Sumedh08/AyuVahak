package org.ayuvahak.matching.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.ayuvahak.platform.core.ExternalReference;

/** Pure, deterministic hard-eligibility evaluation over owner-produced evidence projections. */
public final class CandidateEligibilityEvaluator {

  private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9._-]{0,63}");

  public Evaluation evaluate(Request request) {
    Objects.requireNonNull(request, "eligibility request");
    TreeSet<Reason> evaluationReasons = new TreeSet<>(REASON_ORDER);
    if (!request.directoryEvidenceComplete()) {
      evaluationReasons.add(new Reason(ReasonCode.DIRECTORY_EVIDENCE_INCOMPLETE, Optional.empty()));
    }
    if (!request.operationalRequirementsComplete()) {
      evaluationReasons.add(new Reason(ReasonCode.OPERATIONAL_MAPPING_MISSING, Optional.empty()));
    }
    if (!request.permittedGeographyCodes().isEmpty()) {
      evaluationReasons.add(
          new Reason(ReasonCode.GEOGRAPHIC_CONSTRAINT_UNRESOLVED, Optional.empty()));
    }

    Map<ExternalReference, CapacityEvidence> capacityByFacility = new HashMap<>();
    request.capacityEvidence().forEach(item -> capacityByFacility.put(item.facility(), item));
    Set<ExternalReference> knownCandidates = new HashSet<>();
    request.candidates().forEach(candidate -> knownCandidates.add(candidate.facility()));
    if (capacityByFacility.keySet().stream()
        .anyMatch(facility -> !knownCandidates.contains(facility))) {
      evaluationReasons.add(new Reason(ReasonCode.CAPACITY_RESULT_OUT_OF_SCOPE, Optional.empty()));
    }

    List<CandidateResult> results =
        request.candidates().stream()
            .map(
                candidate ->
                    evaluateCandidate(
                        request,
                        candidate,
                        capacityByFacility.get(candidate.facility()),
                        evaluationReasons))
            .sorted(Comparator.comparing(result -> referenceKey(result.facility())))
            .toList();
    return new Evaluation(
        evaluationReasons.isEmpty() ? Completeness.COMPLETE : Completeness.INCOMPLETE,
        List.copyOf(evaluationReasons),
        results);
  }

  private CandidateResult evaluateCandidate(
      Request request,
      CandidateEvidence candidate,
      CapacityEvidence capacity,
      Set<Reason> evaluationReasons) {
    TreeSet<Reason> reasons = new TreeSet<>(REASON_ORDER);
    if (!request.candidateOrganizationScope().contains(candidate.organization())) {
      reasons.add(new Reason(ReasonCode.CANDIDATE_ORGANIZATION_OUT_OF_SCOPE, Optional.empty()));
    }
    request
        .selectedDestinationOrganization()
        .filter(selected -> !selected.equals(candidate.organization()))
        .ifPresent(
            ignored ->
                reasons.add(
                    new Reason(ReasonCode.DESTINATION_ORGANIZATION_MISMATCH, Optional.empty())));

    switch (candidate.facilityStatus()) {
      case ACTIVE -> {}
      case INACTIVE -> reasons.add(new Reason(ReasonCode.FACILITY_INACTIVE, Optional.empty()));
      case UNKNOWN -> reasons.add(new Reason(ReasonCode.FACILITY_STATUS_UNKNOWN, Optional.empty()));
    }

    Map<String, CapabilityEvidence> capabilities = new HashMap<>();
    candidate.capabilities().forEach(evidence -> capabilities.put(evidence.code(), evidence));
    for (String requiredCapability : request.requiredCapabilityCodes()) {
      CapabilityEvidence capability = capabilities.get(requiredCapability);
      if (capability == null) {
        reasons.add(
            new Reason(ReasonCode.CAPABILITY_EVIDENCE_UNKNOWN, Optional.of(requiredCapability)));
      } else {
        switch (capability.assessment()) {
          case SATISFIED -> {
            if (capability.qualifyingAssertionReferences().isEmpty()) {
              reasons.add(
                  new Reason(
                      ReasonCode.CAPABILITY_EVIDENCE_UNKNOWN, Optional.of(requiredCapability)));
            }
          }
          case NOT_ASSERTED ->
              reasons.add(
                  new Reason(ReasonCode.CAPABILITY_NOT_ASSERTED, Optional.of(requiredCapability)));
          case INACTIVE_CAPABILITY ->
              reasons.add(
                  new Reason(ReasonCode.CAPABILITY_INACTIVE, Optional.of(requiredCapability)));
          case UNKNOWN_CAPABILITY ->
              reasons.add(
                  new Reason(ReasonCode.CAPABILITY_UNKNOWN, Optional.of(requiredCapability)));
          case NOT_EFFECTIVE ->
              reasons.add(
                  new Reason(ReasonCode.CAPABILITY_NOT_EFFECTIVE, Optional.of(requiredCapability)));
          case INACTIVE_OFFERING ->
              reasons.add(
                  new Reason(
                      ReasonCode.CAPABILITY_OFFERING_INACTIVE, Optional.of(requiredCapability)));
          case NO_CURRENT_ASSERTION ->
              reasons.add(
                  new Reason(
                      ReasonCode.CAPABILITY_NO_CURRENT_ASSERTION, Optional.of(requiredCapability)));
          case UNVERIFIED, DISPUTED, UNKNOWN ->
              reasons.add(
                  new Reason(
                      ReasonCode.CAPABILITY_EVIDENCE_UNKNOWN, Optional.of(requiredCapability)));
        }
      }
      if (!request
              .operationalRequirements()
              .dimensionsByCapability()
              .containsKey(requiredCapability)
          || request
              .operationalRequirements()
              .missingCapabilityCodes()
              .contains(requiredCapability)) {
        reasons.add(
            new Reason(ReasonCode.OPERATIONAL_MAPPING_MISSING, Optional.of(requiredCapability)));
      }
    }

    if (!request.permittedGeographyCodes().isEmpty()) {
      reasons.add(new Reason(ReasonCode.GEOGRAPHIC_CONSTRAINT_UNRESOLVED, Optional.empty()));
    }
    if (request.destinationLocationRequired() && candidate.coordinate().isEmpty()) {
      reasons.add(new Reason(ReasonCode.DESTINATION_LOCATION_UNKNOWN, Optional.empty()));
    }

    boolean hardExcludedBeforeCapacity = hasHardDisqualification(reasons);
    if (!hardExcludedBeforeCapacity && request.operationalRequirementsComplete()) {
      evaluateCapacity(request, candidate, capacity, reasons, evaluationReasons);
    }

    CandidateOutcome outcome =
        hasHardDisqualification(reasons)
            ? CandidateOutcome.INELIGIBLE
            : reasons.isEmpty() ? CandidateOutcome.ELIGIBLE : CandidateOutcome.INDETERMINATE;
    return new CandidateResult(
        candidate.facility(), candidate.organization(), outcome, List.copyOf(reasons));
  }

  private static void evaluateCapacity(
      Request request,
      CandidateEvidence candidate,
      CapacityEvidence capacity,
      Set<Reason> reasons,
      Set<Reason> evaluationReasons) {
    if (request.operationalRequirements().uniqueDimensionCodes().isEmpty()) {
      reasons.add(new Reason(ReasonCode.OPERATIONAL_MAPPING_MISSING, Optional.empty()));
      evaluationReasons.add(new Reason(ReasonCode.OPERATIONAL_MAPPING_MISSING, Optional.empty()));
      return;
    }
    if (!request.capacityEvidenceAvailable()) {
      reasons.add(new Reason(ReasonCode.CAPACITY_EVIDENCE_UNAVAILABLE, Optional.empty()));
      evaluationReasons.add(new Reason(ReasonCode.CAPACITY_EVIDENCE_UNAVAILABLE, Optional.empty()));
      return;
    }
    if (capacity == null) {
      reasons.add(new Reason(ReasonCode.CAPACITY_RESULT_INCOMPLETE, Optional.empty()));
      evaluationReasons.add(new Reason(ReasonCode.CAPACITY_RESULT_INCOMPLETE, Optional.empty()));
      return;
    }
    if (!capacity.evaluationTime().equals(request.evaluationTime())) {
      reasons.add(new Reason(ReasonCode.CAPACITY_EVIDENCE_TIME_MISMATCH, Optional.empty()));
      evaluationReasons.add(
          new Reason(ReasonCode.CAPACITY_EVIDENCE_TIME_MISMATCH, Optional.empty()));
      return;
    }

    Map<String, CapacityStatus> dimensions = new HashMap<>();
    capacity
        .dimensions()
        .forEach(dimension -> dimensions.put(dimension.code(), dimension.status()));
    Set<String> requiredDimensions =
        Set.copyOf(request.operationalRequirements().uniqueDimensionCodes());
    if (!dimensions.keySet().equals(requiredDimensions)) {
      reasons.add(new Reason(ReasonCode.CAPACITY_RESULT_INCOMPLETE, Optional.empty()));
      evaluationReasons.add(new Reason(ReasonCode.CAPACITY_RESULT_INCOMPLETE, Optional.empty()));
    }
    for (String dimensionCode : request.operationalRequirements().uniqueDimensionCodes()) {
      CapacityStatus status = dimensions.get(dimensionCode);
      if (status == null) {
        continue;
      }
      switch (status) {
        case READY -> {}
        case NOT_READY ->
            reasons.add(new Reason(ReasonCode.CAPACITY_NOT_READY, Optional.of(dimensionCode)));
        case UNKNOWN ->
            reasons.add(
                new Reason(ReasonCode.CAPACITY_EVIDENCE_UNKNOWN, Optional.of(dimensionCode)));
        case STALE ->
            reasons.add(new Reason(ReasonCode.CAPACITY_EVIDENCE_STALE, Optional.of(dimensionCode)));
        case CONFLICTED ->
            reasons.add(
                new Reason(ReasonCode.CAPACITY_EVIDENCE_CONFLICTED, Optional.of(dimensionCode)));
        case UNAVAILABLE -> {
          reasons.add(
              new Reason(ReasonCode.CAPACITY_EVIDENCE_UNAVAILABLE, Optional.of(dimensionCode)));
          evaluationReasons.add(
              new Reason(ReasonCode.CAPACITY_EVIDENCE_UNAVAILABLE, Optional.empty()));
        }
      }
    }
  }

  private static boolean hasHardDisqualification(Set<Reason> reasons) {
    return reasons.stream().anyMatch(reason -> reason.code().hardDisqualification());
  }

  private static String referenceKey(ExternalReference reference) {
    return reference.issuer().value() + "|" + reference.resourceType() + "|" + reference.value();
  }

  private static List<String> normalizedCodes(List<String> codes, String label) {
    Objects.requireNonNull(codes, label);
    TreeSet<String> normalized = new TreeSet<>();
    for (String code : codes) {
      if (code == null || !CODE.matcher(code).matches()) {
        throw new IllegalArgumentException(label + " must contain stable uppercase codes");
      }
      normalized.add(code);
    }
    return List.copyOf(normalized);
  }

  private static void requireReferenceType(ExternalReference reference, String type, String label) {
    Objects.requireNonNull(reference, label);
    if (!type.equals(reference.resourceType())) {
      throw new IllegalArgumentException(label + " must reference " + type);
    }
  }

  private static <T> void requireUnique(
      List<T> items, java.util.function.Function<T, ?> key, String label) {
    if (items.stream().map(key).distinct().count() != items.size()) {
      throw new IllegalArgumentException(label + " must be unique");
    }
  }

  public record Request(
      List<String> requiredCapabilityCodes,
      ResolvedOperationalRequirements operationalRequirements,
      List<CandidateEvidence> candidates,
      List<CapacityEvidence> capacityEvidence,
      Set<ExternalReference> candidateOrganizationScope,
      Optional<ExternalReference> selectedDestinationOrganization,
      List<String> permittedGeographyCodes,
      Instant evaluationTime,
      boolean destinationLocationRequired,
      boolean directoryEvidenceComplete,
      boolean capacityEvidenceAvailable) {

    public Request {
      requiredCapabilityCodes = normalizedCodes(requiredCapabilityCodes, "required capabilities");
      if (requiredCapabilityCodes.isEmpty()) {
        throw new IllegalArgumentException("at least one required CareCapability is required");
      }
      Objects.requireNonNull(operationalRequirements, "resolved operational requirements");
      candidates = List.copyOf(Objects.requireNonNull(candidates, "candidate evidence"));
      capacityEvidence = List.copyOf(Objects.requireNonNull(capacityEvidence, "Capacity evidence"));
      candidateOrganizationScope =
          Set.copyOf(
              Objects.requireNonNull(candidateOrganizationScope, "candidate organization scope"));
      if (candidateOrganizationScope.isEmpty()) {
        throw new IllegalArgumentException("candidate organization scope must be explicit");
      }
      candidateOrganizationScope.forEach(
          organization -> requireReferenceType(organization, "Organization", "candidate scope"));
      selectedDestinationOrganization =
          Objects.requireNonNull(
              selectedDestinationOrganization, "selected destination organization");
      selectedDestinationOrganization.ifPresent(
          organization ->
              requireReferenceType(
                  organization, "Organization", "selected destination organization"));
      permittedGeographyCodes =
          normalizedCodes(permittedGeographyCodes, "permitted geography codes");
      Objects.requireNonNull(evaluationTime, "evaluation time");
      if (candidates.stream().anyMatch(Objects::isNull)
          || capacityEvidence.stream().anyMatch(Objects::isNull)) {
        throw new IllegalArgumentException("eligibility evidence cannot contain null");
      }
      requireUnique(candidates, CandidateEvidence::facility, "candidate Facility references");
      requireUnique(capacityEvidence, CapacityEvidence::facility, "Capacity Facility results");
    }

    public boolean operationalRequirementsComplete() {
      Set<String> required = Set.copyOf(requiredCapabilityCodes);
      Map<String, List<String>> mappings = operationalRequirements.dimensionsByCapability();
      TreeSet<String> mappedDimensions = new TreeSet<>();
      mappings.values().forEach(mappedDimensions::addAll);
      return operationalRequirements.missingCapabilityCodes().isEmpty()
          && mappings.keySet().equals(required)
          && !mappedDimensions.isEmpty()
          && mappedDimensions.equals(Set.copyOf(operationalRequirements.uniqueDimensionCodes()));
    }
  }

  public record CandidateEvidence(
      ExternalReference facility,
      ExternalReference organization,
      FacilityStatus facilityStatus,
      Optional<Coordinate> coordinate,
      List<CapabilityEvidence> capabilities) {

    public CandidateEvidence {
      requireReferenceType(facility, "Facility", "candidate Facility");
      requireReferenceType(organization, "Organization", "candidate Organization");
      Objects.requireNonNull(facilityStatus, "Facility status");
      coordinate = Objects.requireNonNull(coordinate, "candidate coordinate");
      capabilities = List.copyOf(Objects.requireNonNull(capabilities, "capability evidence"));
      if (capabilities.stream().anyMatch(Objects::isNull)) {
        throw new IllegalArgumentException("capability evidence cannot contain null");
      }
      requireUnique(capabilities, CapabilityEvidence::code, "CareCapability evidence");
    }
  }

  public record Coordinate(double longitude, double latitude) {

    public Coordinate {
      if (!Double.isFinite(longitude)
          || longitude < -180
          || longitude > 180
          || !Double.isFinite(latitude)
          || latitude < -90
          || latitude > 90) {
        throw new IllegalArgumentException("coordinate must be a valid WGS84 point");
      }
    }
  }

  public record CapabilityEvidence(
      String code,
      CapabilityAssessment assessment,
      List<ExternalReference> qualifyingAssertionReferences) {

    public CapabilityEvidence {
      if (code == null || !CODE.matcher(code).matches()) {
        throw new IllegalArgumentException("CareCapability code must be stable and uppercase");
      }
      Objects.requireNonNull(assessment, "capability assessment");
      qualifyingAssertionReferences =
          List.copyOf(
              Objects.requireNonNull(
                  qualifyingAssertionReferences, "qualifying assertion references"));
      qualifyingAssertionReferences.forEach(
          reference -> requireReferenceType(reference, "FacilityCapabilityAssertion", "assertion"));
    }
  }

  public record CapacityEvidence(
      ExternalReference facility, Instant evaluationTime, List<DimensionReadiness> dimensions) {

    public CapacityEvidence {
      requireReferenceType(facility, "Facility", "Capacity Facility");
      Objects.requireNonNull(evaluationTime, "Capacity evaluation time");
      dimensions = List.copyOf(Objects.requireNonNull(dimensions, "dimension readiness"));
      if (dimensions.isEmpty()) {
        throw new IllegalArgumentException("Capacity evidence must cover at least one dimension");
      }
      if (dimensions.stream().anyMatch(Objects::isNull)) {
        throw new IllegalArgumentException("dimension readiness cannot contain null");
      }
      requireUnique(dimensions, DimensionReadiness::code, "Capacity dimensions");
    }
  }

  public record DimensionReadiness(String code, CapacityStatus status) {

    public DimensionReadiness {
      if (code == null || !CODE.matcher(code).matches()) {
        throw new IllegalArgumentException("CapacityDimension code must be stable and uppercase");
      }
      Objects.requireNonNull(status, "Capacity status");
    }
  }

  public record Evaluation(
      Completeness completeness, List<Reason> reasons, List<CandidateResult> candidates) {

    public Evaluation {
      Objects.requireNonNull(completeness, "evaluation completeness");
      reasons = List.copyOf(Objects.requireNonNull(reasons, "evaluation reasons"));
      candidates = List.copyOf(Objects.requireNonNull(candidates, "candidate results"));
    }

    public List<String> reasonCodes() {
      return reasons.stream().map(reason -> reason.code().name()).distinct().toList();
    }

    /** Only a complete candidate universe can supply candidates to a later ranking step. */
    public List<CandidateResult> rankableCandidates() {
      if (completeness != Completeness.COMPLETE) {
        return List.of();
      }
      return candidates.stream()
          .filter(candidate -> candidate.outcome() == CandidateOutcome.ELIGIBLE)
          .toList();
    }
  }

  public record CandidateResult(
      ExternalReference facility,
      ExternalReference organization,
      CandidateOutcome outcome,
      List<Reason> reasons) {

    public CandidateResult {
      requireReferenceType(facility, "Facility", "result Facility");
      requireReferenceType(organization, "Organization", "result Organization");
      Objects.requireNonNull(outcome, "candidate outcome");
      reasons = List.copyOf(Objects.requireNonNull(reasons, "candidate reasons"));
    }

    public List<String> reasonCodes() {
      return reasons.stream().map(reason -> reason.code().name()).distinct().toList();
    }
  }

  public record Reason(ReasonCode code, Optional<String> subjectCode) {

    public Reason {
      Objects.requireNonNull(code, "reason code");
      subjectCode = Objects.requireNonNull(subjectCode, "reason subject");
      subjectCode.ifPresent(
          subject -> {
            if (!CODE.matcher(subject).matches()) {
              throw new IllegalArgumentException("reason subject must be a stable uppercase code");
            }
          });
    }
  }

  private static final Comparator<Reason> REASON_ORDER =
      Comparator.comparing((Reason reason) -> reason.code().name())
          .thenComparing(reason -> reason.subjectCode().orElse(""));

  public enum FacilityStatus {
    ACTIVE,
    INACTIVE,
    UNKNOWN
  }

  public enum CapabilityAssessment {
    SATISFIED,
    NOT_ASSERTED,
    UNKNOWN_CAPABILITY,
    INACTIVE_CAPABILITY,
    UNVERIFIED,
    DISPUTED,
    NOT_EFFECTIVE,
    INACTIVE_OFFERING,
    NO_CURRENT_ASSERTION,
    UNKNOWN
  }

  public enum CapacityStatus {
    READY,
    NOT_READY,
    UNKNOWN,
    STALE,
    CONFLICTED,
    UNAVAILABLE
  }

  public enum CandidateOutcome {
    ELIGIBLE,
    INELIGIBLE,
    INDETERMINATE
  }

  public enum Completeness {
    COMPLETE,
    INCOMPLETE
  }

  public enum ReasonCode {
    CAPACITY_EVIDENCE_CONFLICTED(false),
    CAPACITY_EVIDENCE_STALE(false),
    CAPACITY_EVIDENCE_TIME_MISMATCH(false),
    CAPACITY_EVIDENCE_UNAVAILABLE(false),
    CAPACITY_EVIDENCE_UNKNOWN(false),
    CAPACITY_NOT_READY(true),
    CAPACITY_RESULT_INCOMPLETE(false),
    CAPACITY_RESULT_OUT_OF_SCOPE(false),
    CAPABILITY_EVIDENCE_UNKNOWN(false),
    CAPABILITY_INACTIVE(true),
    CAPABILITY_NOT_ASSERTED(true),
    CAPABILITY_NOT_EFFECTIVE(true),
    CAPABILITY_NO_CURRENT_ASSERTION(true),
    CAPABILITY_OFFERING_INACTIVE(true),
    CAPABILITY_UNKNOWN(false),
    CANDIDATE_ORGANIZATION_OUT_OF_SCOPE(true),
    DESTINATION_LOCATION_UNKNOWN(false),
    DESTINATION_ORGANIZATION_MISMATCH(true),
    DIRECTORY_EVIDENCE_INCOMPLETE(false),
    FACILITY_INACTIVE(true),
    FACILITY_STATUS_UNKNOWN(false),
    GEOGRAPHIC_CONSTRAINT_UNRESOLVED(false),
    OPERATIONAL_MAPPING_MISSING(false);

    private final boolean hardDisqualification;

    ReasonCode(boolean hardDisqualification) {
      this.hardDisqualification = hardDisqualification;
    }

    boolean hardDisqualification() {
      return hardDisqualification;
    }
  }
}
