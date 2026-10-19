package org.ayuvahak.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ServiceLevelPlanTest {

  @Test
  void requiresAnExplicitTargetOrOmissionRationaleForEveryCataloguedIndicator() {
    ServiceLevelPlan plan = completePlan();

    assertThat(plan.objectives()).hasSize(1);
    assertThat(plan.omissions())
        .extracting(ServiceLevelOmission::indicator)
        .containsExactlyInAnyOrder(
            java.util.Arrays.stream(ServiceLevelIndicator.values())
                .filter(indicator -> indicator != ServiceLevelIndicator.API_AVAILABILITY)
                .toArray(ServiceLevelIndicator[]::new));
  }

  @Test
  void rejectsAnIndicatorThatHasNeitherAServiceObjectiveNorAnOmissionReason() {
    ServiceLevelPlan complete = completePlan();
    List<ServiceLevelOmission> omissions =
        complete.omissions().stream()
            .filter(omission -> omission.indicator() != ServiceLevelIndicator.SSE_OVERFLOW)
            .toList();

    assertThatThrownBy(() -> new ServiceLevelPlan(complete.objectives(), omissions))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SSE_OVERFLOW");
  }

  @Test
  void rejectsDuplicateOrContradictoryCoverage() {
    ServiceLevelPlan complete = completePlan();

    assertThatThrownBy(
            () ->
                new ServiceLevelPlan(
                    complete.objectives(),
                    append(
                        complete.omissions(),
                        new ServiceLevelOmission(
                            ServiceLevelIndicator.API_AVAILABILITY,
                            "Synthetic omission must not override a target."))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("API_AVAILABILITY");
  }

  @Test
  void objectiveRequiresDeploymentOwnedTargetWindowScopeAndResponse() {
    assertThatThrownBy(
            () ->
                new ServiceLevelObjective(
                    ServiceLevelIndicator.API_AVAILABILITY,
                    new BigDecimal("1.01"),
                    Duration.ofMinutes(5),
                    ServiceScope.API,
                    "A synthetic request failure is a failed request.",
                    List.of(),
                    new BigDecimal("0.99"),
                    "Synthetic operations team",
                    "Review the runbook and restore the service."))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("target");

    assertThatThrownBy(
            () ->
                new ServiceLevelObjective(
                    ServiceLevelIndicator.API_AVAILABILITY,
                    new BigDecimal("0.99"),
                    Duration.ZERO,
                    ServiceScope.API,
                    "A synthetic request failure is a failed request.",
                    List.of(),
                    new BigDecimal("0.98"),
                    "Synthetic operations team",
                    "Review the runbook and restore the service."))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("window");
  }

  private static ServiceLevelPlan completePlan() {
    List<ServiceLevelOmission> omissions =
        java.util.Arrays.stream(ServiceLevelIndicator.values())
            .filter(indicator -> indicator != ServiceLevelIndicator.API_AVAILABILITY)
            .map(
                indicator ->
                    new ServiceLevelOmission(
                        indicator,
                        "Synthetic deployment has no approved objective for this service class."))
            .toList();
    return new ServiceLevelPlan(
        List.of(
            new ServiceLevelObjective(
                ServiceLevelIndicator.API_AVAILABILITY,
                new BigDecimal("0.995"),
                Duration.ofDays(30),
                ServiceScope.API,
                "Eligible successful requests divided by eligible requests.",
                List.of("Authenticated client cancellations before admission."),
                new BigDecimal("0.98"),
                "Synthetic operations team",
                "Review the local incident runbook and restore the declared target.")),
        omissions);
  }

  private static List<ServiceLevelOmission> append(
      List<ServiceLevelOmission> omissions, ServiceLevelOmission value) {
    java.util.ArrayList<ServiceLevelOmission> copy = new java.util.ArrayList<>(omissions);
    copy.add(value);
    return List.copyOf(copy);
  }
}
