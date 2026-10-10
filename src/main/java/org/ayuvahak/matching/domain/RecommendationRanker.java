package org.ayuvahak.matching.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.ayuvahak.matching.domain.CandidateEligibilityEvaluator.CandidateOutcome;
import org.ayuvahak.matching.domain.CandidateEligibilityEvaluator.CandidateResult;
import org.ayuvahak.matching.domain.CandidateEligibilityEvaluator.Completeness;
import org.ayuvahak.matching.domain.CandidateEligibilityEvaluator.Evaluation;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;

/** Deterministic advisory ordering over Matching-owned eligibility results. */
public final class RecommendationRanker {

  private static final double EARTH_MEAN_RADIUS_METERS = 6_371_008.8;

  public Ranking rank(Request request) {
    Objects.requireNonNull(request, "ranking request");
    Evaluation evaluation = request.evaluation();
    List<CandidateResult> eligible =
        evaluation.candidates().stream()
            .filter(candidate -> candidate.outcome() == CandidateOutcome.ELIGIBLE)
            .toList();
    Set<ExternalReference> rankable = new HashSet<>();
    Map<ExternalReference, Measure> measures = new HashMap<>();
    Map<ExternalReference, List<String>> additionalReasons = new HashMap<>();
    Set<ExternalReference> indeterminate = new HashSet<>();
    List<String> evaluationReasons = new ArrayList<>();

    if (evaluation.completeness() == Completeness.INCOMPLETE) {
      evaluationReasons.add("ELIGIBILITY_EVALUATION_INCOMPLETE");
      eligible.forEach(
          candidate -> {
            indeterminate.add(candidate.facility());
            addReason(additionalReasons, candidate.facility(), "ELIGIBILITY_EVALUATION_INCOMPLETE");
          });
    } else if (!eligible.isEmpty()) {
      applyMetric(
          request,
          eligible,
          rankable,
          measures,
          additionalReasons,
          indeterminate,
          evaluationReasons);
    }

    List<CandidateResult> rankOrder = new ArrayList<>(evaluation.candidates());
    rankOrder.sort(
        Comparator.comparing(
            candidate -> InternalId.parse(candidate.facility().value()).toString()));
    if (!rankable.isEmpty()) {
      rankOrder.sort(
          Comparator.comparingInt(
                  (CandidateResult candidate) -> rankable.contains(candidate.facility()) ? 0 : 1)
              .thenComparing(
                  candidate ->
                      rankable.contains(candidate.facility())
                          ? measures.get(candidate.facility()).value()
                          : BigDecimal.ZERO)
              .thenComparingInt(
                  candidate ->
                      rankable.contains(candidate.facility())
                          ? request
                              .policy()
                              .preferenceOrdinals()
                              .getOrDefault(candidate.facility(), Integer.MAX_VALUE)
                          : Integer.MAX_VALUE)
              .thenComparing(
                  candidate -> InternalId.parse(candidate.facility().value()).toString()));
    }

    List<Ranking.CandidateRanking> candidates = new ArrayList<>();
    int rank = 1;
    for (CandidateResult candidate : rankOrder) {
      CandidateStatus status =
          switch (candidate.outcome()) {
            case INELIGIBLE -> CandidateStatus.INELIGIBLE;
            case INDETERMINATE -> CandidateStatus.INDETERMINATE;
            case ELIGIBLE ->
                rankable.contains(candidate.facility())
                    ? CandidateStatus.RANKED_ELIGIBLE
                    : CandidateStatus.INDETERMINATE;
          };
      OptionalInt candidateRank =
          status == CandidateStatus.RANKED_ELIGIBLE ? OptionalInt.of(rank++) : OptionalInt.empty();
      List<String> reasonCodes = new ArrayList<>(candidate.reasonCodes());
      reasonCodes.addAll(additionalReasons.getOrDefault(candidate.facility(), List.of()));
      candidates.add(
          new Ranking.CandidateRanking(
              candidate.facility(),
              candidate.organization(),
              status,
              candidateRank,
              Optional.ofNullable(measures.get(candidate.facility())),
              status == CandidateStatus.RANKED_ELIGIBLE
                  ? preference(request.policy(), candidate.facility())
                  : OptionalInt.empty(),
              reasonCodes.stream().distinct().sorted().toList()));
    }

    boolean complete =
        evaluation.completeness() == Completeness.COMPLETE
            && (indeterminate.isEmpty() || !rankable.isEmpty());
    return new Ranking(
        complete ? Outcome.COMPLETE : Outcome.INDETERMINATE,
        evaluationReasons.stream().distinct().sorted().toList(),
        candidates);
  }

  private static void applyMetric(
      Request request,
      List<CandidateResult> eligible,
      Set<ExternalReference> rankable,
      Map<ExternalReference, Measure> measures,
      Map<ExternalReference, List<String>> additionalReasons,
      Set<ExternalReference> indeterminate,
      List<String> evaluationReasons) {
    if (request.policy().primaryMetric() == MetricKind.STRAIGHT_LINE_DISTANCE) {
      eligible.forEach(
          candidate ->
              distanceFor(
                  request, candidate, measures, rankable, indeterminate, additionalReasons));
      return;
    }

    Map<ExternalReference, TravelTimeEstimate> current = currentTravelTimes(request);
    boolean allTravelTimesAvailable =
        eligible.stream().allMatch(candidate -> current.containsKey(candidate.facility()));
    if (allTravelTimesAvailable) {
      eligible.forEach(
          candidate -> {
            TravelTimeEstimate estimate = current.get(candidate.facility());
            rankable.add(candidate.facility());
            measures.put(
                candidate.facility(),
                new Measure(
                    MetricKind.TRAVEL_TIME,
                    estimate.sourceCode(),
                    estimate.seconds(),
                    "s",
                    estimate.observedAt(),
                    estimate.validUntil(),
                    estimate.sourceSystem()));
          });
      return;
    }

    if (!request.policy().allowDistanceFallback()) {
      eligible.forEach(
          candidate -> {
            indeterminate.add(candidate.facility());
            addReason(additionalReasons, candidate.facility(), "TRAVEL_TIME_UNAVAILABLE");
          });
      evaluationReasons.add("TRAVEL_TIME_UNAVAILABLE");
      return;
    }

    boolean allCoordinatesAvailable =
        request.originCoordinate().isPresent()
            && eligible.stream()
                .allMatch(
                    candidate ->
                        request.destinationCoordinates().containsKey(candidate.facility()));
    if (!allCoordinatesAvailable) {
      eligible.forEach(
          candidate -> {
            indeterminate.add(candidate.facility());
            addReason(additionalReasons, candidate.facility(), "DISTANCE_FALLBACK_UNAVAILABLE");
            if (request.originCoordinate().isEmpty()) {
              addReason(additionalReasons, candidate.facility(), "ORIGIN_LOCATION_UNKNOWN");
            }
            if (!request.destinationCoordinates().containsKey(candidate.facility())) {
              addReason(additionalReasons, candidate.facility(), "DESTINATION_LOCATION_UNKNOWN");
            }
          });
      evaluationReasons.add("DISTANCE_FALLBACK_UNAVAILABLE");
      return;
    }
    eligible.forEach(
        candidate ->
            distanceFor(request, candidate, measures, rankable, indeterminate, additionalReasons));
    evaluationReasons.add("DISTANCE_FALLBACK_USED");
  }

  private static Map<ExternalReference, TravelTimeEstimate> currentTravelTimes(Request request) {
    Map<ExternalReference, TravelTimeEstimate> current = new HashMap<>();
    request.travelTimeEstimates().stream()
        .filter(estimate -> request.originFacility().equals(Optional.of(estimate.origin())))
        .filter(estimate -> !estimate.observedAt().isAfter(request.evaluatedAt()))
        .filter(estimate -> estimate.validUntil().isAfter(request.evaluatedAt()))
        .forEach(estimate -> current.put(estimate.destination(), estimate));
    return current;
  }

  private static void distanceFor(
      Request request,
      CandidateResult candidate,
      Map<ExternalReference, Measure> measures,
      Set<ExternalReference> rankable,
      Set<ExternalReference> indeterminate,
      Map<ExternalReference, List<String>> reasons) {
    if (request.originCoordinate().isEmpty()) {
      indeterminate.add(candidate.facility());
      addReason(reasons, candidate.facility(), "ORIGIN_LOCATION_UNKNOWN");
      return;
    }
    Coordinate destination = request.destinationCoordinates().get(candidate.facility());
    if (destination == null) {
      indeterminate.add(candidate.facility());
      addReason(reasons, candidate.facility(), "DESTINATION_LOCATION_UNKNOWN");
      return;
    }
    BigDecimal meters = distanceMeters(request.originCoordinate().orElseThrow(), destination);
    rankable.add(candidate.facility());
    measures.put(
        candidate.facility(),
        new Measure(
            MetricKind.STRAIGHT_LINE_DISTANCE,
            "STRAIGHT_LINE_HAVERSINE_V1",
            meters,
            "m",
            request.evaluatedAt(),
            request.evaluatedAt(),
            Optional.empty()));
  }

  private static BigDecimal distanceMeters(Coordinate origin, Coordinate destination) {
    double latitude1 = Math.toRadians(origin.latitude());
    double latitude2 = Math.toRadians(destination.latitude());
    double latitudeDelta = latitude2 - latitude1;
    double longitudeDelta = Math.toRadians(destination.longitude() - origin.longitude());
    double a =
        Math.pow(Math.sin(latitudeDelta / 2), 2)
            + Math.cos(latitude1) * Math.cos(latitude2) * Math.pow(Math.sin(longitudeDelta / 2), 2);
    double boundedA = Math.min(1, Math.max(0, a));
    double centralAngle = 2 * Math.atan2(Math.sqrt(boundedA), Math.sqrt(1 - boundedA));
    return BigDecimal.valueOf(EARTH_MEAN_RADIUS_METERS * centralAngle)
        .setScale(3, RoundingMode.HALF_UP);
  }

  private static OptionalInt preference(Policy policy, ExternalReference facility) {
    Integer value = policy.preferenceOrdinals().get(facility);
    return value == null ? OptionalInt.empty() : OptionalInt.of(value);
  }

  private static void addReason(
      Map<ExternalReference, List<String>> reasons, ExternalReference facility, String reason) {
    reasons.computeIfAbsent(facility, ignored -> new ArrayList<>()).add(reason);
  }

  public record Request(
      Evaluation evaluation,
      Optional<ExternalReference> originFacility,
      Optional<Coordinate> originCoordinate,
      Map<ExternalReference, Coordinate> destinationCoordinates,
      Instant evaluatedAt,
      Policy policy,
      List<TravelTimeEstimate> travelTimeEstimates) {

    public Request {
      Objects.requireNonNull(evaluation, "eligibility evaluation");
      originFacility = Objects.requireNonNull(originFacility, "origin Facility");
      originFacility.ifPresent(reference -> requireType(reference, "Facility", "origin Facility"));
      originCoordinate = Objects.requireNonNull(originCoordinate, "origin coordinate");
      destinationCoordinates = Map.copyOf(destinationCoordinates);
      destinationCoordinates.forEach(
          (facility, coordinate) -> {
            requireType(facility, "Facility", "destination Facility");
            Objects.requireNonNull(coordinate, "destination coordinate");
          });
      Objects.requireNonNull(evaluatedAt, "evaluation time");
      Objects.requireNonNull(policy, "ranking policy");
      travelTimeEstimates = List.copyOf(travelTimeEstimates);
      if (travelTimeEstimates.stream().anyMatch(Objects::isNull)) {
        throw new IllegalArgumentException("travel-time estimates cannot contain null");
      }
      if (travelTimeEstimates.stream().map(TravelTimeEstimate::destination).distinct().count()
          != travelTimeEstimates.size()) {
        throw new IllegalArgumentException("travel-time estimates must be unique per destination");
      }
      for (CandidateResult candidate : evaluation.candidates()) {
        InternalId.parse(candidate.facility().value());
      }
    }

    public Request(
        Evaluation evaluation,
        ExternalReference originFacility,
        Optional<Coordinate> originCoordinate,
        Map<ExternalReference, Coordinate> destinationCoordinates,
        Instant evaluatedAt,
        Policy policy,
        List<TravelTimeEstimate> travelTimeEstimates) {
      this(
          evaluation,
          Optional.of(originFacility),
          originCoordinate,
          destinationCoordinates,
          evaluatedAt,
          policy,
          travelTimeEstimates);
    }
  }

  public record Policy(
      MetricKind primaryMetric,
      boolean allowDistanceFallback,
      Map<ExternalReference, Integer> preferenceOrdinals) {

    public Policy {
      Objects.requireNonNull(primaryMetric, "primary ranking metric");
      preferenceOrdinals = Map.copyOf(preferenceOrdinals);
      preferenceOrdinals.forEach(
          (facility, ordinal) -> {
            requireType(facility, "Facility", "preferred Facility");
            if (ordinal == null || ordinal < 1) {
              throw new IllegalArgumentException("preference ordinals must be positive");
            }
          });
    }
  }

  public record TravelTimeEstimate(
      ExternalReference origin,
      ExternalReference destination,
      BigDecimal seconds,
      String sourceCode,
      Optional<ExternalReference> sourceSystem,
      Instant observedAt,
      Instant validUntil) {

    public TravelTimeEstimate {
      requireType(origin, "Facility", "travel-time origin");
      requireType(destination, "Facility", "travel-time destination");
      Objects.requireNonNull(seconds, "travel time");
      if (seconds.signum() <= 0) {
        throw new IllegalArgumentException("travel time must be positive");
      }
      if (sourceCode == null || !sourceCode.matches("[A-Z][A-Z0-9._-]{0,63}")) {
        throw new IllegalArgumentException("travel-time source code must be stable and uppercase");
      }
      sourceSystem = Objects.requireNonNull(sourceSystem, "travel-time source system");
      Objects.requireNonNull(observedAt, "travel-time observation time");
      Objects.requireNonNull(validUntil, "travel-time validity end");
      if (!validUntil.isAfter(observedAt)) {
        throw new IllegalArgumentException("travel-time validity must end after observation");
      }
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

  public record Measure(
      MetricKind kind,
      String sourceCode,
      BigDecimal value,
      String unit,
      Instant observedAt,
      Instant validUntil,
      Optional<ExternalReference> sourceSystem) {

    public Measure {
      Objects.requireNonNull(kind, "metric kind");
      if (sourceCode == null || !sourceCode.matches("[A-Z][A-Z0-9._-]{0,63}")) {
        throw new IllegalArgumentException("metric source code must be stable and uppercase");
      }
      Objects.requireNonNull(value, "metric value");
      if (value.signum() < 0) {
        throw new IllegalArgumentException("metric value cannot be negative");
      }
      Objects.requireNonNull(unit, "metric unit");
      Objects.requireNonNull(observedAt, "metric observation time");
      Objects.requireNonNull(validUntil, "metric validity end");
      sourceSystem = Objects.requireNonNull(sourceSystem, "metric source system");
      String expectedUnit = kind == MetricKind.TRAVEL_TIME ? "s" : "m";
      if (!expectedUnit.equals(unit)) {
        throw new IllegalArgumentException("metric unit must match metric kind");
      }
      if (kind == MetricKind.TRAVEL_TIME && !validUntil.isAfter(observedAt)) {
        throw new IllegalArgumentException("travel-time validity must follow observation time");
      }
    }
  }

  public record Ranking(Outcome outcome, List<String> reasons, List<CandidateRanking> candidates) {

    public Ranking {
      Objects.requireNonNull(outcome, "ranking outcome");
      reasons = List.copyOf(reasons);
      candidates = List.copyOf(candidates);
      long rankedCount =
          candidates.stream().filter(candidate -> candidate.rank().isPresent()).count();
      Set<Integer> ranks = new HashSet<>();
      candidates.stream()
          .map(CandidateRanking::rank)
          .filter(OptionalInt::isPresent)
          .mapToInt(OptionalInt::getAsInt)
          .forEach(ranks::add);
      if (ranks.size() != rankedCount
          || (rankedCount > 0
              && (ranks.stream().mapToInt(Integer::intValue).min().orElseThrow() != 1
                  || ranks.stream().mapToInt(Integer::intValue).max().orElseThrow()
                      != rankedCount))) {
        throw new IllegalArgumentException(
            "candidate ranks must be unique and contiguous from one");
      }
    }

    public record CandidateRanking(
        ExternalReference facility,
        ExternalReference organization,
        CandidateStatus status,
        OptionalInt rank,
        Optional<Measure> measure,
        OptionalInt preferenceOrdinal,
        List<String> reasonCodes) {

      public CandidateRanking {
        requireType(facility, "Facility", "ranked Facility");
        requireType(organization, "Organization", "ranked Organization");
        Objects.requireNonNull(status, "candidate ranking status");
        rank = Objects.requireNonNull(rank, "candidate rank");
        measure = Objects.requireNonNull(measure, "candidate ranking measure");
        preferenceOrdinal = Objects.requireNonNull(preferenceOrdinal, "preference ordinal");
        reasonCodes = List.copyOf(reasonCodes);
        boolean ranked = status == CandidateStatus.RANKED_ELIGIBLE;
        if (ranked != rank.isPresent() || ranked != measure.isPresent()) {
          throw new IllegalArgumentException(
              "only ranked eligible candidates may have rank/measure");
        }
        if (ranked
            && (rank.getAsInt() < 1
                || preferenceOrdinal.isPresent() && preferenceOrdinal.getAsInt() < 1)) {
          throw new IllegalArgumentException("rank and preference ordinal must be positive");
        }
        if (!ranked && preferenceOrdinal.isPresent()) {
          throw new IllegalArgumentException(
              "non-ranked candidates cannot have a preference ordinal");
        }
      }
    }
  }

  public enum MetricKind {
    STRAIGHT_LINE_DISTANCE,
    TRAVEL_TIME
  }

  public enum CandidateStatus {
    RANKED_ELIGIBLE,
    INELIGIBLE,
    INDETERMINATE
  }

  public enum Outcome {
    COMPLETE,
    INDETERMINATE
  }

  private static void requireType(ExternalReference reference, String type, String label) {
    Objects.requireNonNull(reference, label);
    if (!type.equals(reference.resourceType())) {
      throw new IllegalArgumentException(label + " must reference " + type);
    }
  }
}
