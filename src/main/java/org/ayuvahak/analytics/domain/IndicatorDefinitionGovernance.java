package org.ayuvahak.analytics.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable governance and applicability rules that qualify one indicator definition version. */
public record IndicatorDefinitionGovernance(
    String ownerApprovedPurpose,
    Instant effectiveFrom,
    Optional<Instant> effectiveUntil,
    ReportingCalendar reportingCalendar,
    Set<ReportingDimension> allowedDimensions,
    CompletenessRequirement completenessRequirement,
    Optional<Duration> maximumSourceAge,
    SensitivityClassification sensitivity,
    Optional<String> requiredDisclosurePolicyKey,
    Optional<AuthorityDefinition> authorityDefinition) {

  public IndicatorDefinitionGovernance {
    if (ownerApprovedPurpose == null
        || ownerApprovedPurpose.isBlank()
        || ownerApprovedPurpose.length() > 512
        || ownerApprovedPurpose.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("indicator purpose must be bounded and non-empty");
    }
    Objects.requireNonNull(effectiveFrom, "effective from");
    effectiveUntil = Objects.requireNonNull(effectiveUntil, "effective until");
    effectiveUntil.ifPresent(
        until -> {
          if (!effectiveFrom.isBefore(until)) {
            throw new IllegalArgumentException("indicator effective interval must be non-empty");
          }
        });
    Objects.requireNonNull(reportingCalendar, "reporting calendar");
    allowedDimensions = Set.copyOf(Objects.requireNonNull(allowedDimensions, "dimensions"));
    if (allowedDimensions.isEmpty()
        || !allowedDimensions.contains(ReportingDimension.ORGANIZATION)
        || allowedDimensions.size() > ReportingDimension.values().length) {
      throw new IllegalArgumentException(
          "indicator dimensions must include bounded organization scope");
    }
    Objects.requireNonNull(completenessRequirement, "completeness requirement");
    maximumSourceAge = Objects.requireNonNull(maximumSourceAge, "maximum source age");
    maximumSourceAge.ifPresent(
        age -> {
          if (age.isZero() || age.isNegative() || age.compareTo(Duration.ofDays(3650)) > 0) {
            throw new IllegalArgumentException("maximum source age must be positive and bounded");
          }
        });
    Objects.requireNonNull(sensitivity, "sensitivity classification");
    requiredDisclosurePolicyKey =
        Objects.requireNonNull(requiredDisclosurePolicyKey, "disclosure policy key");
    requiredDisclosurePolicyKey.ifPresent(
        key -> {
          if (!key.matches("[A-Z0-9][A-Z0-9._:-]{0,63}")) {
            throw new IllegalArgumentException("disclosure policy key is invalid");
          }
        });
    authorityDefinition = Objects.requireNonNull(authorityDefinition, "authority definition");
  }

  /** Compatibility profile for synthetic fixtures; deployments should publish explicit metadata. */
  public static IndicatorDefinitionGovernance syntheticFixture(String description) {
    return new IndicatorDefinitionGovernance(
        description,
        Instant.EPOCH,
        Optional.empty(),
        ReportingCalendar.CUSTOM_HALF_OPEN,
        EnumSet.of(
            ReportingDimension.ORGANIZATION,
            ReportingDimension.FACILITY,
            ReportingDimension.SOURCE_ATTRIBUTION),
        CompletenessRequirement.ALL_REQUIRED_SOURCES_COMPLETE_FOR_NUMERIC_VALUE,
        Optional.empty(),
        SensitivityClassification.SENSITIVE_AGGREGATE,
        Optional.empty(),
        Optional.empty());
  }

  public boolean effectiveFor(ReportingPeriod period) {
    Objects.requireNonNull(period, "reporting period");
    return !period.startInclusiveUtc().isBefore(effectiveFrom)
        && effectiveUntil.map(until -> !period.endExclusiveUtc().isAfter(until)).orElse(true);
  }

  public boolean calendarAccepts(ReportingPeriod period) {
    LocalDate start = period.startInclusiveLocal();
    LocalDate end = period.endExclusiveLocal();
    return switch (reportingCalendar) {
      case CUSTOM_HALF_OPEN -> true;
      case DAILY -> end.equals(start.plusDays(1));
      case WEEKLY_ISO ->
          start.getDayOfWeek() == java.time.DayOfWeek.MONDAY && end.equals(start.plusWeeks(1));
      case MONTHLY ->
          start.getDayOfMonth() == 1 && end.equals(start.plusMonths(1).withDayOfMonth(1));
      case QUARTERLY ->
          start.getDayOfMonth() == 1
              && (start.getMonthValue() - 1) % 3 == 0
              && end.equals(start.plusMonths(3));
    };
  }

  public enum ReportingCalendar {
    CUSTOM_HALF_OPEN,
    DAILY,
    WEEKLY_ISO,
    MONTHLY,
    QUARTERLY
  }

  public enum ReportingDimension {
    ORGANIZATION,
    FACILITY,
    SOURCE_ATTRIBUTION
  }

  public enum CompletenessRequirement {
    ALL_REQUIRED_SOURCES_COMPLETE_FOR_NUMERIC_VALUE
  }

  public enum SensitivityClassification {
    SENSITIVE_AGGREGATE,
    LOW_SENSITIVITY_AGGREGATE
  }

  public record AuthorityDefinition(String authorityIdentifier, String definitionVersion) {
    public AuthorityDefinition {
      if (authorityIdentifier == null
          || !authorityIdentifier.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")
          || definitionVersion == null
          || !definitionVersion.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,63}")) {
        throw new IllegalArgumentException("authority definition reference is invalid");
      }
    }
  }
}
