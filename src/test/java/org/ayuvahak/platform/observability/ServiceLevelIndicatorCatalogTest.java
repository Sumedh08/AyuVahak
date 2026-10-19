package org.ayuvahak.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ServiceLevelIndicatorCatalogTest {

  private static final Set<String> ALLOWED_LABEL_NAMES = Set.of("route_class");
  private static final Pattern METRIC_KEY =
      Pattern.compile("ayuvahak\\.sli\\.[a-z][a-z0-9_.]{1,80}");

  @Test
  void exposesOnlyTheFrozenBoundedOperationalIndicatorCatalogue() {
    assertThat(ServiceLevelIndicator.values())
        .extracting(ServiceLevelIndicator::metricKey)
        .containsExactlyInAnyOrder(
            "ayuvahak.sli.api.availability",
            "ayuvahak.sli.api.latency",
            "ayuvahak.sli.database.readiness",
            "ayuvahak.sli.database.pool.saturation",
            "ayuvahak.sli.audit.write.failures",
            "ayuvahak.sli.outbox.lag",
            "ayuvahak.sli.notification.projection.lag",
            "ayuvahak.sli.reconciliation.operation.age",
            "ayuvahak.sli.federation.operation.age",
            "ayuvahak.sli.exchange.operation.age",
            "ayuvahak.sli.sse.connections",
            "ayuvahak.sli.sse.overflow",
            "ayuvahak.sli.analytics.backlog",
            "ayuvahak.sli.dhis2.unresolved.outcome.age",
            "ayuvahak.sli.process.resource.exhaustion");
  }

  @Test
  void catalogueKeysAreUniqueAndLabelsCannotEncodeCaseOrPeerIdentity() {
    assertThat(Arrays.stream(ServiceLevelIndicator.values()).map(ServiceLevelIndicator::metricKey))
        .allMatch(key -> METRIC_KEY.matcher(key).matches())
        .doesNotHaveDuplicates();

    assertThat(
            Arrays.stream(ServiceLevelIndicator.values())
                .flatMap(indicator -> indicator.allowedLabelValues().keySet().stream())
                .collect(Collectors.toSet()))
        .isSubsetOf(ALLOWED_LABEL_NAMES);
    assertThat(ServiceLevelIndicator.values())
        .allSatisfy(
            indicator ->
                assertThat(indicator.allowedLabelValues().values())
                    .allSatisfy(
                        values -> assertThat(values).isNotEmpty().hasSizeLessThanOrEqualTo(8)));
  }

  @Test
  void everyIndicatorDeclaresMeasurementUnitAndHumanReadableDefinition() {
    assertThat(ServiceLevelIndicator.values())
        .allSatisfy(
            indicator -> {
              assertThat(indicator.unit()).isNotBlank();
              assertThat(indicator.definition()).isNotBlank();
            });
    assertThat(ServiceLevelIndicator.API_AVAILABILITY.allowedLabelValues())
        .containsEntry("route_class", Set.of("read", "write", "admin", "integration", "other"));
  }
}
