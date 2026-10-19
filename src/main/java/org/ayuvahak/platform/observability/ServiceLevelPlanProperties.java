package org.ayuvahak.platform.observability;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Operator-supplied SLO declarations bound only when a production profile is active. */
@ConfigurationProperties("ayuvahak.production.sli")
public record ServiceLevelPlanProperties(
    List<ServiceLevelObjective> objectives, Map<ServiceLevelIndicator, String> omittedRationales) {

  public ServiceLevelPlanProperties {
    objectives = objectives == null ? List.of() : List.copyOf(objectives);
    omittedRationales = omittedRationales == null ? Map.of() : Map.copyOf(omittedRationales);
  }

  public ServiceLevelPlan toPlan() {
    List<ServiceLevelOmission> omissions =
        omittedRationales.entrySet().stream()
            .map(entry -> new ServiceLevelOmission(entry.getKey(), entry.getValue()))
            .toList();
    return new ServiceLevelPlan(objectives, omissions);
  }
}
