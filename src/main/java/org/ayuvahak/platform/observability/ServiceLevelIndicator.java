package org.ayuvahak.platform.observability;

import java.util.Map;
import java.util.Set;

/** Bounded operational indicators; labels are dimensions, never resource identities. */
public enum ServiceLevelIndicator {
  API_AVAILABILITY(
      "ayuvahak.sli.api.availability",
      "ratio",
      "Successful API requests divided by eligible requests over the declared window.",
      Set.of("route_class"),
      Map.of("route_class", Set.of("read", "write", "admin", "integration", "other")),
      Set.of(ServiceScope.API),
      true),
  API_LATENCY(
      "ayuvahak.sli.api.latency",
      "milliseconds",
      "API request latency distribution for an approved route class.",
      Set.of("route_class"),
      Map.of("route_class", Set.of("read", "write", "admin", "integration", "other")),
      Set.of(ServiceScope.API),
      false),
  DATABASE_READINESS(
      "ayuvahak.sli.database.readiness",
      "ratio",
      "Time the required local database dependency is ready over the declared window.",
      Set.of(),
      Map.of(),
      Set.of(ServiceScope.DATABASE),
      true),
  DATABASE_POOL_SATURATION(
      "ayuvahak.sli.database.pool.saturation",
      "ratio",
      "Active database connections divided by the configured pool capacity.",
      Set.of(),
      Map.of(),
      Set.of(ServiceScope.DATABASE),
      false),
  AUDIT_WRITE_FAILURES(
      "ayuvahak.sli.audit.write.failures",
      "count",
      "Required audit writes that failed during the declared window.",
      Set.of(),
      Map.of(),
      Set.of(ServiceScope.AUDIT),
      false),
  OUTBOX_LAG(
      "ayuvahak.sli.outbox.lag",
      "seconds",
      "Age of the oldest eligible durable outbox work item.",
      Set.of(),
      Map.of(),
      Set.of(ServiceScope.OUTBOX),
      false),
  NOTIFICATION_PROJECTION_LAG(
      "ayuvahak.sli.notification.projection.lag",
      "seconds",
      "Age of the oldest unprojected notification event.",
      Set.of(),
      Map.of(),
      Set.of(ServiceScope.NOTIFICATION),
      false),
  RECONCILIATION_OPERATION_AGE(
      "ayuvahak.sli.reconciliation.operation.age",
      "seconds",
      "Age of the oldest operation requiring reconciliation.",
      Set.of(),
      Map.of(),
      Set.of(ServiceScope.RECONCILIATION),
      false),
  FEDERATION_OPERATION_AGE(
      "ayuvahak.sli.federation.operation.age",
      "seconds",
      "Age of the oldest pending or unknown federated operation.",
      Set.of(),
      Map.of(),
      Set.of(ServiceScope.FEDERATION),
      false),
  EXCHANGE_OPERATION_AGE(
      "ayuvahak.sli.exchange.operation.age",
      "seconds",
      "Age of the oldest pending or unknown secure-exchange operation.",
      Set.of(),
      Map.of(),
      Set.of(ServiceScope.EXCHANGE),
      false),
  SSE_CONNECTIONS(
      "ayuvahak.sli.sse.connections",
      "count",
      "Current bounded server-sent event connections.",
      Set.of(),
      Map.of(),
      Set.of(ServiceScope.SSE),
      false),
  SSE_OVERFLOW(
      "ayuvahak.sli.sse.overflow",
      "count",
      "Server-sent event client queues that overflowed during the declared window.",
      Set.of(),
      Map.of(),
      Set.of(ServiceScope.SSE),
      false),
  ANALYTICS_BACKLOG(
      "ayuvahak.sli.analytics.backlog",
      "count",
      "Durable Analytics work waiting to be computed.",
      Set.of(),
      Map.of(),
      Set.of(ServiceScope.ANALYTICS),
      false),
  DHIS2_UNRESOLVED_OUTCOME_AGE(
      "ayuvahak.sli.dhis2.unresolved.outcome.age",
      "seconds",
      "Age of the oldest DHIS2 export with an unresolved outcome.",
      Set.of(),
      Map.of(),
      Set.of(ServiceScope.DHIS2),
      false),
  PROCESS_RESOURCE_EXHAUSTION(
      "ayuvahak.sli.process.resource.exhaustion",
      "ratio",
      "Configured process resource limit utilization for the measured resource.",
      Set.of(),
      Map.of(),
      Set.of(ServiceScope.PROCESS),
      false);

  private final String metricKey;
  private final String unit;
  private final String definition;
  private final Set<String> allowedLabelNames;
  private final Map<String, Set<String>> allowedLabelValues;
  private final Set<ServiceScope> allowedServiceScopes;
  private final boolean higherIsBetter;

  ServiceLevelIndicator(
      String metricKey,
      String unit,
      String definition,
      Set<String> allowedLabelNames,
      Map<String, Set<String>> allowedLabelValues,
      Set<ServiceScope> allowedServiceScopes,
      boolean higherIsBetter) {
    this.metricKey = metricKey;
    this.unit = unit;
    this.definition = definition;
    this.allowedLabelNames = Set.copyOf(allowedLabelNames);
    this.allowedLabelValues =
        allowedLabelValues.entrySet().stream()
            .collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
    if (!this.allowedLabelNames.equals(this.allowedLabelValues.keySet())) {
      throw new IllegalArgumentException("every permitted metric label must have fixed values");
    }
    this.allowedServiceScopes = Set.copyOf(allowedServiceScopes);
    this.higherIsBetter = higherIsBetter;
  }

  public String metricKey() {
    return metricKey;
  }

  public String unit() {
    return unit;
  }

  public String definition() {
    return definition;
  }

  /** Only fixed, low-cardinality names may appear as metric tags. */
  public Set<String> allowedLabelNames() {
    return allowedLabelNames;
  }

  /** Fixed, low-cardinality values permitted for each label name. */
  public Map<String, Set<String>> allowedLabelValues() {
    return allowedLabelValues;
  }

  public Set<ServiceScope> allowedServiceScopes() {
    return allowedServiceScopes;
  }

  public boolean higherIsBetter() {
    return higherIsBetter;
  }
}
