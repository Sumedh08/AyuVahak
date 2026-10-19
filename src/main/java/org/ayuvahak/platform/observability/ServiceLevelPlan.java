package org.ayuvahak.platform.observability;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/** Complete per-deployment selection of objectives and explicitly omitted indicator classes. */
public record ServiceLevelPlan(
    List<ServiceLevelObjective> objectives, List<ServiceLevelOmission> omissions) {

  public ServiceLevelPlan {
    objectives = List.copyOf(Objects.requireNonNull(objectives, "objectives are required"));
    omissions = List.copyOf(Objects.requireNonNull(omissions, "omissions are required"));

    EnumSet<ServiceLevelIndicator> covered = EnumSet.noneOf(ServiceLevelIndicator.class);
    for (ServiceLevelObjective objective : objectives) {
      Objects.requireNonNull(objective, "objective entry is required");
      if (!covered.add(objective.indicator())) {
        throw new IllegalArgumentException("duplicate SLI coverage: " + objective.indicator());
      }
    }
    for (ServiceLevelOmission omission : omissions) {
      Objects.requireNonNull(omission, "omission entry is required");
      if (!covered.add(omission.indicator())) {
        throw new IllegalArgumentException("duplicate SLI coverage: " + omission.indicator());
      }
    }

    EnumSet<ServiceLevelIndicator> missing = EnumSet.allOf(ServiceLevelIndicator.class);
    missing.removeAll(covered);
    if (!missing.isEmpty()) {
      throw new IllegalArgumentException(
          "missing SLI declaration or omission rationale: " + missing);
    }
  }
}
