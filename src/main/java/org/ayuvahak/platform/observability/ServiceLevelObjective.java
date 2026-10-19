package org.ayuvahak.platform.observability;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Deployment-owned SLO declaration; it supplies no platform-wide target or guarantee. */
public record ServiceLevelObjective(
    ServiceLevelIndicator indicator,
    BigDecimal target,
    Duration window,
    ServiceScope serviceScope,
    String failureDefinition,
    List<String> exclusions,
    BigDecimal alertThreshold,
    String accountableOwner,
    String response) {

  private static final Duration MAXIMUM_WINDOW = Duration.ofDays(366);
  private static final int MAXIMUM_EXCLUSIONS = 16;

  public ServiceLevelObjective {
    Objects.requireNonNull(indicator, "indicator is required");
    Objects.requireNonNull(target, "target is required");
    Objects.requireNonNull(window, "window is required");
    Objects.requireNonNull(serviceScope, "service scope is required");
    Objects.requireNonNull(alertThreshold, "alert threshold is required");

    if (target.signum() < 0
        || ("ratio".equals(indicator.unit()) && target.compareTo(BigDecimal.ONE) > 0)) {
      throw new IllegalArgumentException("target is outside the indicator's valid range");
    }
    if (alertThreshold.signum() < 0
        || ("ratio".equals(indicator.unit()) && alertThreshold.compareTo(BigDecimal.ONE) > 0)) {
      throw new IllegalArgumentException("alert threshold is outside the indicator's valid range");
    }
    if (window.isZero() || window.isNegative() || window.compareTo(MAXIMUM_WINDOW) > 0) {
      throw new IllegalArgumentException("window must be positive and no longer than 366 days");
    }
    if (!indicator.allowedServiceScopes().contains(serviceScope)) {
      throw new IllegalArgumentException("service scope is not valid for " + indicator.name());
    }
    boolean thresholdConsistent =
        indicator.higherIsBetter()
            ? alertThreshold.compareTo(target) <= 0
            : alertThreshold.compareTo(target) >= 0;
    if (!thresholdConsistent) {
      throw new IllegalArgumentException("alert threshold must follow the indicator direction");
    }

    failureDefinition =
        ServiceLevelOmission.boundedText(failureDefinition, "failure definition", 512);
    accountableOwner = ServiceLevelOmission.boundedText(accountableOwner, "accountable owner", 128);
    response = ServiceLevelOmission.boundedText(response, "response", 512);
    if (exclusions == null || exclusions.size() > MAXIMUM_EXCLUSIONS) {
      throw new IllegalArgumentException("exclusions must contain at most 16 entries");
    }
    exclusions =
        exclusions.stream()
            .map(value -> ServiceLevelOmission.boundedText(value, "exclusion", 256))
            .toList();
  }
}
