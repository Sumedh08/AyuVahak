package org.ayuvahak.platform.operatorsupport.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
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
import org.ayuvahak.platform.operatorsupport.OperatorSupportEvaluationFixture;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Executes the versioned synthetic support-evaluation cases through the application boundary. */
class OperatorSupportEvaluationCorpusTest {

  private static final String CAPABILITY_MANIFEST =
      "/evaluation/phase14/operator-support-capability-v1.json";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final ActorReference OPERATOR =
      ActorReference.human(new IssuerId("urn:synthetic:operator-support-eval"), "SYN-OPERATOR-01");

  @Test
  void versionedSyntheticCasesMatchTheSupportContract() throws IOException {
    JsonNode root = OperatorSupportEvaluationFixture.read();
    assertThat(root.get("version").stringValue()).isEqualTo("1.0.0");
    assertThat(root.get("purpose").stringValue())
        .isEqualTo("documentation-only advisory evaluation; no model or external provider");
    JsonNode contract = root.get("evaluationContract");
    assertThat(contract.get("owner").stringValue()).isNotBlank();
    assertThat(contract.get("baseline").stringValue()).isNotBlank();
    assertThat(contract.get("measurement").stringValue()).isNotBlank();
    assertThat(contract.get("acceptance").stringValue()).isNotBlank();

    JsonNode cases = root.get("cases");
    assertThat(cases.isArray()).isTrue();
    assertThat(cases.size()).isEqualTo(8);

    for (JsonNode scenario : cases) {
      String scenarioId = scenario.get("id").stringValue();
      String assertion = scenario.get("assertion").stringValue();
      assertThat(assertion)
          .as(scenarioId + " executable assertion mapping")
          .isIn(
              "EXACT_SOURCE_AND_CITATION_PROVENANCE",
              "NO_PROVIDER_CALL_AND_EMPTY_ABSTENTION",
              "FAILURE_DETAILS_REDACTED",
              "INVALID_OUTPUT_NOT_EXPOSED");
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
      assertEvidence(assertion, response, key, invocations.get());
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
      }
    }
  }

  @Test
  void versionedCapabilityManifestKeepsTheCurrentCapabilityDisabledAndNonAuthoritative()
      throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(CAPABILITY_MANIFEST)) {
      assertThat(stream).as("versioned capability manifest exists").isNotNull();
      JsonNode manifest = JSON.readTree(stream);

      assertThat(manifest.get("version").stringValue()).isEqualTo("1.0.0");
      assertThat(manifest.get("capabilityId").stringValue()).isEqualTo("operator-support");
      assertThat(manifest.get("defaultEnabled").booleanValue()).isFalse();
      assertThat(manifest.get("currentProvider").get("kind").stringValue())
          .isEqualTo("DETERMINISTIC_LOCAL_FAKE");
      assertThat(manifest.get("currentProvider").get("externalInferenceConfigured").booleanValue())
          .isFalse();
      assertThat(manifest.get("corpusVersion").stringValue())
          .isEqualTo(OperatorSupportCorpus.APPROVED_VERSION);
      assertThat(manifest.get("outputSchemaVersion").stringValue())
          .isEqualTo(OperatorSupportService.CAPABILITY_VERSION);
      assertThat(manifest.get("actionAuthority")).isEmpty();
      assertThat(manifest.get("retention").get("rawPromptRetained").booleanValue()).isFalse();
      assertThat(manifest.get("retention").get("rawAnswerRetained").booleanValue()).isFalse();
      assertThat(manifest.get("humanOversight").get("canCloseOrIgnore").booleanValue()).isTrue();
      assertThat(manifest.get("humanOversight").get("rejectionChangesBusinessState").booleanValue())
          .isFalse();

      assertThat(stringValues(manifest.get("forbiddenInputs")))
          .contains("PatientReference", "IPS", "diagnoses", "clinical narratives", "secrets");
      assertThat(stringValues(manifest.get("reEvaluationTriggers")))
          .contains(
              "provider",
              "model",
              "prompt template",
              "corpus",
              "purpose",
              "allowed inputs",
              "privacy policy",
              "output schema",
              "major incident");
      assertThat(stringValues(manifest.get("knownLimitations")))
          .anyMatch(limitation -> limitation.contains("not an evaluation of a model"));
    }
  }

  private static List<String> stringValues(JsonNode array) {
    assertThat(array).isNotNull();
    assertThat(array.isArray()).isTrue();
    return java.util.stream.StreamSupport.stream(array.spliterator(), false)
        .map(JsonNode::stringValue)
        .toList();
  }

  private static void assertEvidence(
      String assertion,
      OperatorSupportResponse response,
      OperatorSupportQuestionKey key,
      int invocationCount) {
    switch (assertion) {
      case "EXACT_SOURCE_AND_CITATION_PROVENANCE" -> {
        assertThat(invocationCount).isEqualTo(1);
        ApprovedOperatorSupportSource source =
            OperatorSupportCorpus.approved().sourcesFor(key).getFirst();
        assertThat(response.answer()).isEqualTo(source.excerpt());
        assertThat(response.sources())
            .singleElement()
            .satisfies(
                citation -> {
                  assertThat(citation.sourceId()).isEqualTo(source.sourceId());
                  assertThat(citation.path()).isEqualTo(source.path());
                  assertThat(citation.section()).isEqualTo(source.section());
                  assertThat(citation.contentHash()).isEqualTo(source.contentHash());
                });
      }
      case "NO_PROVIDER_CALL_AND_EMPTY_ABSTENTION" -> {
        assertThat(invocationCount).isZero();
        assertThat(response.answer()).isEmpty();
      }
      case "FAILURE_DETAILS_REDACTED" -> {
        assertThat(invocationCount).isEqualTo(1);
        assertThat(response.status()).isEqualTo(OperatorSupportResponse.Status.ABSTAINED);
        assertThat(response.answer()).isEmpty();
        assertThat(response.sources()).isEmpty();
        assertThat(response.toString()).doesNotContain("SYNTHETIC_PROVIDER_");
      }
      case "INVALID_OUTPUT_NOT_EXPOSED" -> {
        assertThat(invocationCount).isEqualTo(1);
        assertThat(response.status()).isEqualTo(OperatorSupportResponse.Status.ABSTAINED);
        assertThat(response.reasonCode())
            .isEqualTo(OperatorSupportResponse.ReasonCode.INVALID_PROVIDER_OUTPUT);
        assertThat(response.answer()).isEmpty();
        assertThat(response.sources()).isEmpty();
      }
      default -> throw new AssertionError("No executable evidence assertion for " + assertion);
    }
  }

  private static ProviderResponse providerResponse(
      String behavior, List<ApprovedOperatorSupportSource> sources) {
    return switch (behavior) {
      case "APPROVED_SOURCE" ->
          ProviderResponse.advisory(
              sources.getFirst().excerpt(), List.of(sources.getFirst().sourceId()));
      case "THROW_UNAVAILABLE" -> throw new IllegalStateException("SYNTHETIC_PROVIDER_FAILURE");
      case "THROW_TIMEOUT" ->
          throw new java.util.concurrent.CompletionException(
              new java.util.concurrent.TimeoutException("SYNTHETIC_PROVIDER_TIMEOUT"));
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
}
