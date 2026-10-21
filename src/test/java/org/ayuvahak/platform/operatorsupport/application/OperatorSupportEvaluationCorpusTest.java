package org.ayuvahak.platform.operatorsupport.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.ayuvahak.platform.authorization.application.AuthorizationService;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthorityCatalog;
import org.ayuvahak.platform.configuration.OperatorSupportProperties;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Executes the versioned synthetic support-evaluation cases through the application boundary. */
class OperatorSupportEvaluationCorpusTest {

  private static final String CORPUS_RESOURCE =
      "/evaluation/phase14/operator-support-evaluation-v1.json";
  private static final ActorReference OPERATOR =
      ActorReference.human(new IssuerId("urn:synthetic:operator-support-eval"), "SYN-OPERATOR-01");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void versionedSyntheticCasesMatchTheSupportContract() throws IOException {
    JsonNode root = JSON.readTree(readCorpus());
    assertThat(root.get("version").stringValue()).isEqualTo("1.0.0");
    assertThat(root.get("purpose").stringValue())
        .isEqualTo("documentation-only advisory evaluation; no model or external provider");

    JsonNode cases = root.get("cases");
    assertThat(cases.isArray()).isTrue();
    assertThat(cases.size()).isEqualTo(7);

    for (JsonNode scenario : cases) {
      String scenarioId = scenario.get("id").stringValue();
      OperatorSupportQuestionKey key =
          OperatorSupportQuestionKey.valueOf(scenario.get("questionKey").stringValue());
      boolean enabled = scenario.get("enabled").booleanValue();
      String providerBehavior = scenario.get("providerBehavior").stringValue();
      String expectedStatus = scenario.get("expectedStatus").stringValue();
      String expectedReason = scenario.get("expectedReason").stringValue();
      int expectedInvocations = scenario.get("expectedProviderInvocations").intValue();
      AtomicInteger invocations = new AtomicInteger();
      OperatorSupportService service =
          service(
              enabled,
              (question, sources) -> {
                invocations.incrementAndGet();
                return providerResponse(providerBehavior, sources);
              });

      OperatorSupportResponse response = service.answer(OPERATOR, CorrelationId.generate(), key);

      assertThat(response.status().name()).as(scenarioId + " status").isEqualTo(expectedStatus);
      assertThat(response.reasonCode().name()).as(scenarioId + " reason").isEqualTo(expectedReason);
      assertThat(invocations.get())
          .as(scenarioId + " provider invocation count")
          .isEqualTo(expectedInvocations);
      if (response.status() == OperatorSupportResponse.Status.ADVISORY) {
        assertThat(response.sources()).as(scenarioId + " citations").isNotEmpty();
        assertThat(response.corpusVersion())
            .as(scenarioId + " corpus provenance")
            .isEqualTo(OperatorSupportCorpus.APPROVED_VERSION);
        if ("SUPPORTED_SOURCE_BACKED_ANSWER".equals(scenarioId)) {
          assertThat(response.answer())
              .as(scenarioId + " source support")
              .isEqualTo(OperatorSupportCorpus.approved().sourcesFor(key).getFirst().excerpt());
        }
      } else {
        assertThat(response.answer()).as(scenarioId + " abstention answer").isEmpty();
        assertThat(response.sources()).as(scenarioId + " abstention citations").isEmpty();
        if ("PROVIDER_UNAVAILABLE_ABSTAINS".equals(scenarioId)) {
          assertThat(response.toString()).doesNotContain("SYNTHETIC_PROVIDER_FAILURE");
        }
      }
    }
  }

  private static ProviderResponse providerResponse(
      String behavior, List<ApprovedOperatorSupportSource> sources) {
    return switch (behavior) {
      case "APPROVED_SOURCE" ->
          ProviderResponse.advisory(
              sources.getFirst().excerpt(), List.of(sources.getFirst().sourceId()));
      case "THROW_UNAVAILABLE" -> throw new IllegalStateException("SYNTHETIC_PROVIDER_FAILURE");
      case "FOREIGN_CITATION" ->
          ProviderResponse.advisory("Synthetic ungrounded output.", List.of("SYN-UNAPPROVED-01"));
      case "MALFORMED" -> new ProviderResponse(null, null, null, null);
      case "OVERSIZED" ->
          ProviderResponse.advisory(
              "SYNTHETIC-OVERSIZED-".repeat(100), List.of(sources.getFirst().sourceId()));
      case "UNUSED" -> ProviderResponse.advisory("Should not be invoked.", List.of());
      default -> throw new IllegalArgumentException("Unknown evaluation behavior: " + behavior);
    };
  }

  private static OperatorSupportService service(boolean enabled, OperatorSupportProvider provider) {
    OperatorSupportProperties properties = new OperatorSupportProperties();
    properties.setEnabled(enabled);
    AuthorizationService authorization =
        new AuthorizationService(
            (actor, authority, scope) -> true,
            new FunctionalAuthorityCatalog(List.of(OperatorSupportAuthorities.read())));
    ExecutionContextFactory contexts =
        new ExecutionContextFactory(
            (actor, requestedScope) -> requestedScope instanceof RequestedExecutionScope.Platform);
    return new OperatorSupportService(
        properties, OperatorSupportCorpus.approved(), provider, authorization, contexts);
  }

  private static String readCorpus() throws IOException {
    try (InputStream stream =
        OperatorSupportEvaluationCorpusTest.class.getResourceAsStream(CORPUS_RESOURCE)) {
      if (stream == null) {
        throw new IllegalStateException("Missing synthetic evaluation corpus " + CORPUS_RESOURCE);
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
