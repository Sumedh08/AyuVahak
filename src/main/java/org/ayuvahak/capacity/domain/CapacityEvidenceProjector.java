package org.ayuvahak.capacity.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import org.ayuvahak.platform.core.ExternalReference;

/** Reconstructs current evidence without using receipt order or mutating source history. */
public final class CapacityEvidenceProjector {

  public static final String PROJECTION_VERSION = "capacity-evidence-v1";

  public CapacityEvidenceProjection project(
      ExternalReference facility,
      String dimensionCode,
      Optional<CapacityDimension> dimension,
      Optional<CapacityPolicy> policy,
      List<CapacityObservationFact> facts,
      Instant at) {
    Objects.requireNonNull(facility, "facility");
    Objects.requireNonNull(dimensionCode, "dimension code");
    Objects.requireNonNull(dimension, "dimension");
    Objects.requireNonNull(policy, "policy");
    facts = List.copyOf(Objects.requireNonNull(facts, "facts"));
    Objects.requireNonNull(at, "evaluation time");

    if (dimension.isEmpty()) {
      return unknown(
          dimensionCode, OptionalLong.empty(), OptionalLong.empty(), "DIMENSION_UNKNOWN");
    }
    CapacityDimension configuredDimension = dimension.orElseThrow();
    if (!configuredDimension.code().equals(dimensionCode)) {
      return unknown(
          dimensionCode,
          OptionalLong.empty(),
          OptionalLong.empty(),
          "DIMENSION_CONFIGURATION_INVALID");
    }
    if (policy.isEmpty()
        || policy.orElseThrow().dimensionVersion() != configuredDimension.version()) {
      return unknown(
          dimensionCode,
          OptionalLong.of(configuredDimension.version()),
          OptionalLong.empty(),
          "POLICY_UNCONFIGURED");
    }

    CapacityPolicy configuredPolicy = policy.orElseThrow();
    List<CapacitySourcePriority> tiers =
        configuredPolicy.sources().stream()
            .sorted(Comparator.comparingInt(CapacitySourcePriority::priority))
            .toList();
    List<CapacityObservationFact> scopedFacts =
        facts.stream()
            .filter(fact -> sameScope(fact.observation(), facility, dimensionCode))
            .toList();

    TierResolution firstBlocked = null;
    boolean fallbackUsed = false;
    for (int index = 0; index < tiers.size(); ) {
      int priority = tiers.get(index).priority();
      int nextIndex = index;
      while (nextIndex < tiers.size() && tiers.get(nextIndex).priority() == priority) {
        nextIndex++;
      }
      List<CapacitySourcePriority> tier = tiers.subList(index, nextIndex);
      TierResolution result =
          resolveTier(tier, configuredDimension, configuredPolicy, scopedFacts, at);
      if (result.freshness() == CapacityEvidenceFreshness.CURRENT
          || result.freshness() == CapacityEvidenceFreshness.CONFLICTED) {
        List<String> reasonCodes = new ArrayList<>(result.reasonCodes());
        if (fallbackUsed && !reasonCodes.contains("FALLBACK_SOURCE_SELECTED")) {
          reasonCodes.add("FALLBACK_SOURCE_SELECTED");
        }
        return projection(
            configuredDimension,
            configuredPolicy,
            result.freshness(),
            result.evidence(),
            result.fallbackUsed() || fallbackUsed,
            reasonCodes);
      }
      if (firstBlocked == null && result.freshness() != CapacityEvidenceFreshness.UNKNOWN) {
        firstBlocked = result;
      }
      if (nextIndex >= tiers.size()) {
        break;
      }

      List<CapacitySourcePriority> nextTier = tierAt(tiers, nextIndex);
      boolean mayFallback =
          result.freshness() == CapacityEvidenceFreshness.STALE
                  || result.freshness() == CapacityEvidenceFreshness.EXPIRED
              ? nextTier.stream().allMatch(CapacitySourcePriority::fallbackWhenHigherPriorityStale)
              : nextTier.stream()
                  .allMatch(CapacitySourcePriority::fallbackWhenHigherPriorityAbsent);
      if (!mayFallback) {
        break;
      }
      fallbackUsed = true;
      index = nextIndex;
    }

    if (firstBlocked != null) {
      return projection(
          configuredDimension,
          configuredPolicy,
          firstBlocked.freshness(),
          firstBlocked.evidence(),
          false,
          firstBlocked.reasonCodes());
    }
    String reason =
        scopedFacts.stream().anyMatch(fact -> fact.superseded())
            ? "SUPERSEDED_EVIDENCE_EXCLUDED"
            : scopedFacts.stream().anyMatch(fact -> fact.observation().futureSkewed())
                ? "FUTURE_SKEW_EXCLUDED"
                : scopedFacts.stream()
                        .anyMatch(
                            fact -> fact.observation().kind() == CapacityObservationKind.RETRACTION)
                    ? "RETRACTION_EXCLUDED"
                    : "NO_USABLE_EVIDENCE";
    return projection(
        configuredDimension,
        configuredPolicy,
        CapacityEvidenceFreshness.UNKNOWN,
        List.of(),
        false,
        List.of(reason));
  }

  private static List<CapacitySourcePriority> tierAt(
      List<CapacitySourcePriority> sources, int index) {
    int priority = sources.get(index).priority();
    int end = index;
    while (end < sources.size() && sources.get(end).priority() == priority) {
      end++;
    }
    return sources.subList(index, end);
  }

  private static TierResolution resolveTier(
      List<CapacitySourcePriority> tier,
      CapacityDimension dimension,
      CapacityPolicy policy,
      List<CapacityObservationFact> facts,
      Instant at) {
    List<CapacityObservation> current = new ArrayList<>();
    List<CapacityObservation> stale = new ArrayList<>();
    List<CapacityObservation> expired = new ArrayList<>();

    for (CapacitySourcePriority source : tier) {
      for (CapacityObservationFact fact : facts) {
        CapacityObservation observation = fact.observation();
        if (!source.sourceCode().equals(observation.sourceCode())
            || fact.superseded()
            || !validFor(observation, dimension)
            || observation.kind() != CapacityObservationKind.VALUE
            || observation.futureSkewed()
            || observation.observedAt().isAfter(at)) {
          continue;
        }
        if (observation.validUntil().isEmpty()
            || !observation.validUntil().orElseThrow().isAfter(observation.observedAt())) {
          continue;
        }
        if (!at.isBefore(observation.validUntil().orElseThrow())) {
          expired.add(observation);
        } else if (isStale(observation.observedAt(), policy, at)) {
          stale.add(observation);
        } else {
          current.add(observation);
        }
      }
    }

    if (!current.isEmpty()) {
      Instant latestObservedAt =
          current.stream()
              .map(CapacityObservation::observedAt)
              .max(Instant::compareTo)
              .orElseThrow();
      List<CapacityObservation> selected =
          current.stream()
              .filter(observation -> observation.observedAt().equals(latestObservedAt))
              .sorted(OBSERVATION_ORDER)
              .toList();
      if (selected.stream().map(CapacityEvidenceProjector::valueKey).distinct().count() > 1) {
        return new TierResolution(
            CapacityEvidenceFreshness.CONFLICTED,
            selected,
            false,
            List.of("EQUAL_AUTHORITY_DISAGREEMENT"));
      }
      return new TierResolution(
          CapacityEvidenceFreshness.CURRENT, selected, false, List.of("CURRENT_SOURCE_EVIDENCE"));
    }
    if (!stale.isEmpty()) {
      return blocked(CapacityEvidenceFreshness.STALE, stale, "OBSERVATION_TOO_OLD");
    }
    if (!expired.isEmpty()) {
      return blocked(CapacityEvidenceFreshness.EXPIRED, expired, "VALIDITY_ELAPSED");
    }
    return new TierResolution(
        CapacityEvidenceFreshness.UNKNOWN, List.of(), false, List.of("NO_USABLE_EVIDENCE"));
  }

  private static TierResolution blocked(
      CapacityEvidenceFreshness freshness, List<CapacityObservation> facts, String reason) {
    Instant latestObservedAt =
        facts.stream().map(CapacityObservation::observedAt).max(Instant::compareTo).orElseThrow();
    return new TierResolution(
        freshness,
        facts.stream()
            .filter(observation -> observation.observedAt().equals(latestObservedAt))
            .sorted(OBSERVATION_ORDER)
            .toList(),
        false,
        List.of(reason));
  }

  private static boolean isStale(Instant observedAt, CapacityPolicy policy, Instant at) {
    try {
      return at.isAfter(observedAt.plusSeconds(policy.maximumObservationAgeSeconds()));
    } catch (RuntimeException overflow) {
      return false;
    }
  }

  private static boolean validFor(CapacityObservation observation, CapacityDimension dimension) {
    if (!observation.dimensionCode().equals(dimension.code())
        || observation.dimensionVersion() != dimension.version()
        || observation.valueKind().isEmpty()) {
      return false;
    }
    if (dimension.valueKind() == CapacityValueKind.QUANTITY) {
      return observation.valueKind().orElseThrow() == CapacityValueKind.QUANTITY
          && observation.quantity().isPresent()
          && observation.quantity().orElseThrow().signum() >= 0
          && observation.state().isEmpty()
          && observation.unit().filter(dimension.unit()::equals).isPresent()
          && (dimension.minimumValue() == null
              || observation.quantity().orElseThrow().compareTo(dimension.minimumValue()) >= 0)
          && (dimension.maximumValue() == null
              || observation.quantity().orElseThrow().compareTo(dimension.maximumValue()) <= 0);
    }
    return observation.valueKind().orElseThrow() == CapacityValueKind.STATE
        && observation.quantity().isEmpty()
        && observation.unit().isEmpty()
        && observation.state().filter(dimension.allowedStates()::contains).isPresent();
  }

  private static boolean sameScope(
      CapacityObservation observation, ExternalReference facility, String dimensionCode) {
    return observation.facility().equals(facility)
        && observation.dimensionCode().equals(dimensionCode);
  }

  private static String valueKey(CapacityObservation observation) {
    return observation.valueKind().orElseThrow()
        + "|"
        + observation
            .quantity()
            .map(BigDecimal::stripTrailingZeros)
            .map(Object::toString)
            .orElse("")
        + "|"
        + observation.state().orElse("")
        + "|"
        + observation.unit().orElse("");
  }

  private static CapacityEvidenceProjection projection(
      CapacityDimension dimension,
      CapacityPolicy policy,
      CapacityEvidenceFreshness freshness,
      List<CapacityObservation> evidence,
      boolean fallbackUsed,
      List<String> reasons) {
    boolean singleValue = freshness == CapacityEvidenceFreshness.CURRENT && !evidence.isEmpty();
    CapacityObservation representative = singleValue ? evidence.getFirst() : null;
    return new CapacityEvidenceProjection(
        dimension.code(),
        OptionalLong.of(dimension.version()),
        OptionalLong.of(policy.version()),
        freshness,
        singleValue ? representative.valueKind() : Optional.empty(),
        singleValue ? representative.quantity() : Optional.empty(),
        singleValue ? representative.state() : Optional.empty(),
        singleValue ? representative.unit() : Optional.empty(),
        evidence,
        fallbackUsed,
        reasons,
        PROJECTION_VERSION);
  }

  private static CapacityEvidenceProjection unknown(
      String dimensionCode,
      OptionalLong dimensionVersion,
      OptionalLong policyVersion,
      String reason) {
    return new CapacityEvidenceProjection(
        dimensionCode,
        dimensionVersion,
        policyVersion,
        CapacityEvidenceFreshness.UNKNOWN,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        List.of(),
        false,
        List.of(reason),
        PROJECTION_VERSION);
  }

  private static final Comparator<CapacityObservation> OBSERVATION_ORDER =
      Comparator.comparing(CapacityObservation::sourceCode)
          .thenComparing(observation -> observation.sourceObservation().issuer().value())
          .thenComparing(observation -> observation.sourceObservation().resourceType())
          .thenComparing(observation -> observation.sourceObservation().value())
          .thenComparing(observation -> observation.id().toString());

  private record TierResolution(
      CapacityEvidenceFreshness freshness,
      List<CapacityObservation> evidence,
      boolean fallbackUsed,
      List<String> reasonCodes) {}
}
