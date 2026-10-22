package org.ayuvahak.analytics.adapter.api;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.ayuvahak.analytics.application.AnalyticsIndicatorDefinitionAdministrationService;
import org.ayuvahak.analytics.domain.AggregationFormula;
import org.ayuvahak.analytics.domain.IndicatorDefinitionGovernance;
import org.ayuvahak.analytics.domain.IndicatorDefinitionVersion;
import org.ayuvahak.analytics.domain.SourceMeasure;
import org.ayuvahak.platform.api.CorrelationIdFilter;
import org.ayuvahak.platform.api.PlatformErrorCode;
import org.ayuvahak.platform.api.PlatformProblemException;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** Platform-authorized Analytics owner API for immutable indicator versions. */
@RestController
@RequestMapping("/api/v1/analytics/indicator-definitions")
public final class AnalyticsIndicatorDefinitionController {

  private static final java.util.Set<String> DEFINITION_FIELDS =
      java.util.Set.of(
          "key",
          "version",
          "description",
          "formula",
          "reportingTimeZone",
          "sourceMeasures",
          "zeroAllowed",
          "denominatorMeasures",
          "governance");
  private static final java.util.Set<String> MEASURE_FIELDS =
      java.util.Set.of("owner", "key", "attribution");
  private static final java.util.Set<String> GOVERNANCE_FIELDS =
      java.util.Set.of(
          "ownerApprovedPurpose",
          "effectiveFrom",
          "effectiveUntil",
          "reportingCalendar",
          "allowedDimensions",
          "completenessRequirement",
          "maximumSourceAgeSeconds",
          "sensitivity",
          "requiredDisclosurePolicyKey",
          "authorityDefinition");
  private static final java.util.Set<String> AUTHORITY_FIELDS =
      java.util.Set.of("authorityIdentifier", "definitionVersion");

  private final AnalyticsIndicatorDefinitionAdministrationService administration;
  private final ExecutionContextFactory contexts;

  public AnalyticsIndicatorDefinitionController(
      AnalyticsIndicatorDefinitionAdministrationService administration,
      ExecutionContextFactory contexts) {
    this.administration = administration;
    this.contexts = contexts;
  }

  @PostMapping
  public ResponseEntity<DefinitionVersionView> publish(
      @AuthenticationPrincipal ActorReference actor,
      @RequestHeader(value = "X-AyuVahak-Execution-Scope", required = false) String scope,
      @RequestBody JsonNode body) {
    if (actor == null || actor.kind() != ActorKind.HUMAN) {
      throw problem(
          HttpStatus.UNAUTHORIZED,
          PlatformErrorCode.AUTHENTICATION_REQUIRED,
          "A verified human actor is required to publish an indicator definition.");
    }
    if (!"PLATFORM".equals(scope)) {
      throw problem(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.INVALID_EXECUTION_SCOPE,
          "Indicator definition publication requires explicit platform scope.");
    }
    IndicatorDefinitionVersion proposed = parseDefinition(body);
    var context =
        contexts.forInteractiveRequest(actor, RequestedExecutionScope.platform(), correlationId());
    IndicatorDefinitionVersion published = administration.publish(context, proposed);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            new DefinitionVersionView(
                published.key(), published.version(), published.fingerprint()));
  }

  private static IndicatorDefinitionVersion parseDefinition(JsonNode body) {
    try {
      requireObject(body, DEFINITION_FIELDS);
      JsonNode governanceNode = body.get("governance");
      requireObject(governanceNode, GOVERNANCE_FIELDS);
      return new IndicatorDefinitionVersion(
          requiredText(body, "key"),
          requiredInteger(body, "version"),
          requiredText(body, "description"),
          enumValue(AggregationFormula.class, body, "formula"),
          ZoneId.of(requiredText(body, "reportingTimeZone")),
          measures(body.get("sourceMeasures")),
          requiredBoolean(body, "zeroAllowed"),
          measures(body.get("denominatorMeasures")),
          governance(governanceNode));
    } catch (PlatformProblemException invalid) {
      throw invalid;
    } catch (RuntimeException invalid) {
      throw problem(
          HttpStatus.UNPROCESSABLE_ENTITY,
          PlatformErrorCode.VALIDATION_FAILED,
          "The Analytics indicator definition is invalid.");
    }
  }

  private static IndicatorDefinitionGovernance governance(JsonNode node) {
    Optional<Instant> effectiveUntil = optionalInstant(node, "effectiveUntil");
    Optional<Duration> maximumAge =
        optionalLong(node, "maximumSourceAgeSeconds").map(Duration::ofSeconds);
    Optional<String> policyKey = optionalText(node, "requiredDisclosurePolicyKey");
    Optional<IndicatorDefinitionGovernance.AuthorityDefinition> authority =
        optionalObject(node, "authorityDefinition")
            .map(
                value -> {
                  requireObject(value, AUTHORITY_FIELDS);
                  return new IndicatorDefinitionGovernance.AuthorityDefinition(
                      requiredText(value, "authorityIdentifier"),
                      requiredText(value, "definitionVersion"));
                });
    return new IndicatorDefinitionGovernance(
        requiredText(node, "ownerApprovedPurpose"),
        requiredInstant(node, "effectiveFrom"),
        effectiveUntil,
        enumValue(IndicatorDefinitionGovernance.ReportingCalendar.class, node, "reportingCalendar"),
        dimensions(node.get("allowedDimensions")),
        enumValue(
            IndicatorDefinitionGovernance.CompletenessRequirement.class,
            node,
            "completenessRequirement"),
        maximumAge,
        enumValue(
            IndicatorDefinitionGovernance.SensitivityClassification.class, node, "sensitivity"),
        policyKey,
        authority);
  }

  private static List<SourceMeasure> measures(JsonNode node) {
    if (node == null || !node.isArray() || node.size() > 12) {
      throw invalidDefinition();
    }
    List<SourceMeasure> measures = new ArrayList<>();
    for (JsonNode item : node) {
      requireObject(item, MEASURE_FIELDS);
      SourceMeasure.Owner owner = enumValue(SourceMeasure.Owner.class, item, "owner");
      SourceMeasure.Attribution attribution =
          enumValue(SourceMeasure.Attribution.class, item, "attribution");
      String key = requiredText(item, "key");
      measures.add(
          owner == SourceMeasure.Owner.REFERRAL
              ? referralMeasure(key, attribution)
              : SourceMeasure.transfer(key, attribution));
    }
    return List.copyOf(measures);
  }

  private static SourceMeasure referralMeasure(String key, SourceMeasure.Attribution attribution) {
    if (attribution != SourceMeasure.Attribution.NONE) throw invalidDefinition();
    return SourceMeasure.referral(key);
  }

  private static EnumSet<IndicatorDefinitionGovernance.ReportingDimension> dimensions(
      JsonNode node) {
    if (node == null || !node.isArray() || node.isEmpty()) throw invalidDefinition();
    EnumSet<IndicatorDefinitionGovernance.ReportingDimension> result =
        EnumSet.noneOf(IndicatorDefinitionGovernance.ReportingDimension.class);
    for (JsonNode item : node) {
      if (!item.isString()) throw invalidDefinition();
      result.add(IndicatorDefinitionGovernance.ReportingDimension.valueOf(item.stringValue()));
    }
    return result;
  }

  private static <E extends Enum<E>> E enumValue(Class<E> type, JsonNode node, String name) {
    return Enum.valueOf(type, requiredText(node, name));
  }

  private static String requiredText(JsonNode node, String name) {
    JsonNode value = node.get(name);
    if (value == null || !value.isString() || value.stringValue().isBlank())
      throw invalidDefinition();
    return value.stringValue();
  }

  private static int requiredInteger(JsonNode node, String name) {
    JsonNode value = node.get(name);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt())
      throw invalidDefinition();
    return value.intValue();
  }

  private static boolean requiredBoolean(JsonNode node, String name) {
    JsonNode value = node.get(name);
    if (value == null || !value.isBoolean()) throw invalidDefinition();
    return value.booleanValue();
  }

  private static Instant requiredInstant(JsonNode node, String name) {
    return Instant.parse(requiredText(node, name));
  }

  private static Optional<Instant> optionalInstant(JsonNode node, String name) {
    JsonNode value = node.get(name);
    if (value == null || value.isNull()) return Optional.empty();
    if (!value.isString()) throw invalidDefinition();
    return Optional.of(Instant.parse(value.stringValue()));
  }

  private static Optional<Long> optionalLong(JsonNode node, String name) {
    JsonNode value = node.get(name);
    if (value == null || value.isNull()) return Optional.empty();
    if (!value.isIntegralNumber() || !value.canConvertToLong()) throw invalidDefinition();
    return Optional.of(value.longValue());
  }

  private static Optional<String> optionalText(JsonNode node, String name) {
    JsonNode value = node.get(name);
    if (value == null || value.isNull()) return Optional.empty();
    if (!value.isString()) throw invalidDefinition();
    return Optional.of(value.stringValue());
  }

  private static Optional<JsonNode> optionalObject(JsonNode node, String name) {
    JsonNode value = node.get(name);
    if (value == null || value.isNull()) return Optional.empty();
    if (!value.isObject()) throw invalidDefinition();
    return Optional.of(value);
  }

  private static void requireObject(JsonNode node, java.util.Set<String> fields) {
    if (node == null || !node.isObject()) throw invalidDefinition();
    node.properties()
        .forEach(
            property -> {
              if (!fields.contains(property.getKey())) throw invalidDefinition();
            });
  }

  private static PlatformProblemException invalidDefinition() {
    return problem(
        HttpStatus.UNPROCESSABLE_ENTITY,
        PlatformErrorCode.VALIDATION_FAILED,
        "The Analytics indicator definition contains an unsupported or invalid field.");
  }

  private static CorrelationId correlationId() {
    String value = MDC.get(CorrelationIdFilter.MDC_KEY);
    return value == null ? CorrelationId.generate() : new CorrelationId(value);
  }

  private static PlatformProblemException problem(
      HttpStatus status, PlatformErrorCode code, String detail) {
    return new PlatformProblemException(status, code, detail);
  }

  public record DefinitionVersionView(String key, int version, String fingerprint) {}
}
