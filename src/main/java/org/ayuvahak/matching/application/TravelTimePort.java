package org.ayuvahak.matching.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.ExternalReference;

/**
 * Optional Matching-owned request boundary for route-time estimates based only on Location refs.
 */
public interface TravelTimePort {

  /**
   * Signals that route evidence is temporarily unavailable so ranking policy can decide fallback.
   */
  final class UnavailableException extends RuntimeException {

    public UnavailableException(String message) {
      super(message);
    }

    public UnavailableException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * Returns only usable estimates; an empty or partial result leaves fallback policy in control.
   */
  List<Estimate> estimate(TravelTimeRequest request);

  record TravelTimeRequest(
      ExternalReference originLocation,
      List<ExternalReference> destinationLocations,
      Instant evaluatedAt) {

    public TravelTimeRequest {
      requireLocation(originLocation, "origin Location");
      destinationLocations = List.copyOf(destinationLocations);
      if (destinationLocations.isEmpty()
          || destinationLocations.stream().anyMatch(Objects::isNull)
          || destinationLocations.stream().map(TravelTimePort::referenceKey).distinct().count()
              != destinationLocations.size()) {
        throw new IllegalArgumentException("destination Locations must be non-empty and unique");
      }
      destinationLocations.forEach(location -> requireLocation(location, "destination Location"));
      Objects.requireNonNull(evaluatedAt, "evaluation time");
    }
  }

  record Estimate(
      ExternalReference destinationLocation,
      BigDecimal seconds,
      String sourceCode,
      Optional<ExternalReference> sourceSystem,
      Instant observedAt,
      Instant validUntil) {

    public Estimate {
      requireLocation(destinationLocation, "estimated destination Location");
      Objects.requireNonNull(seconds, "travel time seconds");
      if (seconds.signum() <= 0) {
        throw new IllegalArgumentException("travel time seconds must be positive");
      }
      if (sourceCode == null || !sourceCode.matches("[A-Z][A-Z0-9._-]{0,63}")) {
        throw new IllegalArgumentException("travel-time source code must be stable");
      }
      sourceSystem = Objects.requireNonNull(sourceSystem, "travel-time source system");
      Objects.requireNonNull(observedAt, "travel-time observation time");
      Objects.requireNonNull(validUntil, "travel-time validity end");
      if (!validUntil.isAfter(observedAt)) {
        throw new IllegalArgumentException("travel-time validity must follow observation time");
      }
    }
  }

  private static void requireLocation(ExternalReference reference, String label) {
    Objects.requireNonNull(reference, label);
    if (!"Location".equals(reference.resourceType())) {
      throw new IllegalArgumentException(label + " must be a Location reference");
    }
  }

  private static String referenceKey(ExternalReference reference) {
    return reference.issuer().value() + "|" + reference.resourceType() + "|" + reference.value();
  }
}
