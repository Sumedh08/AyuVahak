package org.ayuvahak.platform.operatorsupport.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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

class OperatorSupportServiceTest {

  private static final ActorReference OPERATOR =
      ActorReference.human(new IssuerId("urn:test:idp"), "SYN-OPERATOR-01");

  @Test
  void capabilityIsDisabledByDefault() {
    assertThat(new OperatorSupportProperties().isEnabled()).isFalse();
  }

  @Test
  void approvedCorpusUsesOnlyExistingFiniteDocumentationSources() {
    OperatorSupportCorpus corpus = OperatorSupportCorpus.approved();
    List<ApprovedOperatorSupportSource> sources =
        Arrays.stream(OperatorSupportQuestionKey.values())
            .flatMap(questionKey -> corpus.sourcesFor(questionKey).stream())
            .distinct()
            .toList();

    assertThat(corpus.version()).isEqualTo(OperatorSupportCorpus.APPROVED_VERSION);
    assertThat(sources).hasSize(7);
    assertThat(sources)
        .allSatisfy(
            source -> {
              assertThat(source.path()).startsWith("docs/");
              assertThat(Files.isRegularFile(Path.of(source.path()))).isTrue();
              assertThat(source.contentHash()).hasSize(64);
            });
    assertThat(corpus.sourcesFor(OperatorSupportQuestionKey.UNSUPPORTED_TOPIC)).isEmpty();
  }

  @Test
  void disabledCapabilityAbstainsWithoutInvokingProvider() {
    AtomicInteger invocations = new AtomicInteger();
    OperatorSupportService service =
        service(
            false,
            (questionKey, sources) -> {
              invocations.incrementAndGet();
              return ProviderResponse.advisory("unexpected", List.of());
            },
            true);

    OperatorSupportResponse response = answer(service, OperatorSupportQuestionKey.DATABASE_RESTORE);

    assertThat(response.status()).isEqualTo(OperatorSupportResponse.Status.ABSTAINED);
    assertThat(response.reasonCode()).isEqualTo(OperatorSupportResponse.ReasonCode.DISABLED);
    assertThat(response.answer()).isEmpty();
    assertThat(response.sources()).isEmpty();
    assertThat(invocations).hasValue(0);
  }

  @Test
  void advisoryIsVersionedAndCitationsAreRestrictedToQuestionSources() {
    OperatorSupportCorpus corpus = OperatorSupportCorpus.approved();
    OperatorSupportQuestionKey key = OperatorSupportQuestionKey.DATABASE_RESTORE;
    String approvedSourceId = corpus.sourcesFor(key).getFirst().sourceId();
    OperatorSupportService service =
        service(
            true,
            (questionKey, sources) ->
                ProviderResponse.advisory(
                    "Restore to an isolated target and validate before serving traffic.",
                    List.of(approvedSourceId)),
            true);

    OperatorSupportResponse response = answer(service, key);

    assertThat(response.status()).isEqualTo(OperatorSupportResponse.Status.ADVISORY);
    assertThat(response.answer())
        .isEqualTo("Restore to an isolated target and validate before serving traffic.");
    assertThat(response.sources()).hasSize(1);
    assertThat(response.sources().getFirst().sourceId()).isEqualTo(approvedSourceId);
    assertThat(response.capabilityVersion()).isEqualTo(OperatorSupportService.CAPABILITY_VERSION);
    assertThat(response.corpusVersion()).isEqualTo(corpus.version());
    assertThat(response.reasonCode()).isEqualTo(OperatorSupportResponse.ReasonCode.NONE);
  }

  @Test
  void deterministicLocalProviderReturnsOnlyApprovedBoundedEvidence() {
    OperatorSupportCorpus corpus = OperatorSupportCorpus.approved();
    OperatorSupportQuestionKey key = OperatorSupportQuestionKey.CAPACITY_STALENESS;
    OperatorSupportService service =
        service(true, new DeterministicLocalOperatorSupportProvider(), true);

    OperatorSupportResponse response = answer(service, key);

    assertThat(response.status()).isEqualTo(OperatorSupportResponse.Status.ADVISORY);
    assertThat(response.answer()).isEqualTo(corpus.sourcesFor(key).getFirst().excerpt());
    assertThat(response.sources()).hasSize(1);
    assertThat(response.sources().getFirst().sourceId())
        .isEqualTo(corpus.sourcesFor(key).getFirst().sourceId());
  }

  @Test
  void invalidCitationAbstainsInsteadOfReturningUngroundedAnswer() {
    String otherTopicSource =
        OperatorSupportCorpus.approved()
            .sourcesFor(OperatorSupportQuestionKey.DATABASE_RESTORE)
            .getFirst()
            .sourceId();
    OperatorSupportService service =
        service(
            true,
            (questionKey, sources) ->
                ProviderResponse.advisory("A cross-topic citation.", List.of(otherTopicSource)),
            true);

    OperatorSupportResponse response =
        answer(service, OperatorSupportQuestionKey.IDENTITY_PROVIDER_OUTAGE);

    assertThat(response.status()).isEqualTo(OperatorSupportResponse.Status.ABSTAINED);
    assertThat(response.reasonCode())
        .isEqualTo(OperatorSupportResponse.ReasonCode.INVALID_PROVIDER_OUTPUT);
    assertThat(response.answer()).isEmpty();
    assertThat(response.sources()).isEmpty();
  }

  @Test
  void malformedOrUnboundedProviderOutputAbstains() {
    OperatorSupportCorpus corpus = OperatorSupportCorpus.approved();
    String allowedSource =
        corpus.sourcesFor(OperatorSupportQuestionKey.DATABASE_RESTORE).getFirst().sourceId();
    OperatorSupportService tooLong =
        service(
            true,
            (questionKey, sources) ->
                ProviderResponse.advisory("x".repeat(1401), List.of(allowedSource)),
            true);
    OperatorSupportService duplicateCitation =
        service(
            true,
            (questionKey, sources) ->
                ProviderResponse.advisory(
                    "A bounded answer.", List.of(allowedSource, allowedSource)),
            true);
    OperatorSupportService controls =
        service(
            true,
            (questionKey, sources) ->
                ProviderResponse.advisory("Unsafe" + (char) 0 + "output", List.of(allowedSource)),
            true);

    assertInvalidProviderOutput(answer(tooLong, OperatorSupportQuestionKey.DATABASE_RESTORE));
    assertInvalidProviderOutput(
        answer(duplicateCitation, OperatorSupportQuestionKey.DATABASE_RESTORE));
    assertInvalidProviderOutput(answer(controls, OperatorSupportQuestionKey.DATABASE_RESTORE));
  }

  @Test
  void unsupportedFiniteTopicAbstainsWithoutInvokingProvider() {
    AtomicInteger invocations = new AtomicInteger();
    OperatorSupportService service =
        service(
            true,
            (questionKey, sources) -> {
              invocations.incrementAndGet();
              return ProviderResponse.advisory("unsupported", List.of());
            },
            true);

    OperatorSupportResponse response =
        answer(service, OperatorSupportQuestionKey.UNSUPPORTED_TOPIC);

    assertThat(response.status()).isEqualTo(OperatorSupportResponse.Status.ABSTAINED);
    assertThat(response.reasonCode())
        .isEqualTo(OperatorSupportResponse.ReasonCode.NO_APPROVED_EVIDENCE);
    assertThat(invocations).hasValue(0);
  }

  @Test
  void requiresPlatformGrantBeforeProviderInvocation() {
    AtomicInteger invocations = new AtomicInteger();
    OperatorSupportService service =
        service(
            true,
            (questionKey, sources) -> {
              invocations.incrementAndGet();
              return ProviderResponse.advisory("answer", List.of(sources.getFirst().sourceId()));
            },
            false);

    assertThatThrownBy(() -> answer(service, OperatorSupportQuestionKey.RATE_LIMIT_OPERATION))
        .isInstanceOf(org.ayuvahak.platform.api.PlatformProblemException.class)
        .hasMessage("The actor is not authorized for operator support.");
    assertThat(invocations).hasValue(0);
  }

  @Test
  void machineActorCannotUseHumanOperatorSupportCapability() {
    AtomicInteger invocations = new AtomicInteger();
    OperatorSupportService service =
        service(
            true,
            (questionKey, sources) -> {
              invocations.incrementAndGet();
              return ProviderResponse.advisory("answer", List.of(sources.getFirst().sourceId()));
            },
            true);
    ActorReference machine = ActorReference.service(new IssuerId("urn:test:idp"), "SYN-SERVICE-01");

    assertThatThrownBy(
            () ->
                service.answer(
                    machine, CorrelationId.generate(), OperatorSupportQuestionKey.DATABASE_RESTORE))
        .isInstanceOf(org.ayuvahak.platform.api.PlatformProblemException.class)
        .hasMessage("Operator support is available only to an authorized human operator.");
    assertThat(invocations).hasValue(0);
  }

  @Test
  void profileStatusIsAuthorizedAndNeverReturnsEndpointOrSecretReference() {
    OperatorSupportProperties properties = new OperatorSupportProperties();
    properties.setEnabled(false);
    properties.setSecretReference("env:SYNTHETIC_OPERATOR_SUPPORT_SECRET");
    AuthorizationService authorization =
        new AuthorizationService(
            (actor, authority, scope) -> true,
            new FunctionalAuthorityCatalog(List.of(OperatorSupportAuthorities.read())));
    ExecutionContextFactory contexts =
        new ExecutionContextFactory(
            (actor, requestedScope) -> requestedScope instanceof RequestedExecutionScope.Platform);
    OperatorSupportService service =
        new OperatorSupportService(
            properties,
            OperatorSupportCorpus.approved(),
            new DeterministicLocalOperatorSupportProvider(),
            authorization,
            contexts);

    OperatorSupportProfileStatus status =
        service.profileStatus(OPERATOR, CorrelationId.generate());

    assertThat(status.enabled()).isFalse();
    assertThat(status.provider()).isEqualTo("DETERMINISTIC_LOCAL");
    assertThat(status.secretReferenceConfigured()).isFalse();
    assertThat(status.toString()).doesNotContain("SYNTHETIC_OPERATOR_SUPPORT_SECRET");
    assertThat(status.toString()).doesNotContain("endpoint");
  }

  @Test
  void profileStatusRequiresTheSameOperatorSupportGrantAsAnswers() {
    OperatorSupportProperties properties = new OperatorSupportProperties();
    AuthorizationService authorization =
        new AuthorizationService(
            (actor, authority, scope) -> false,
            new FunctionalAuthorityCatalog(List.of(OperatorSupportAuthorities.read())));
    ExecutionContextFactory contexts =
        new ExecutionContextFactory(
            (actor, requestedScope) -> requestedScope instanceof RequestedExecutionScope.Platform);
    OperatorSupportService service =
        new OperatorSupportService(
            properties,
            OperatorSupportCorpus.approved(),
            new DeterministicLocalOperatorSupportProvider(),
            authorization,
            contexts);

    assertThatThrownBy(() -> service.profileStatus(OPERATOR, CorrelationId.generate()))
        .isInstanceOf(org.ayuvahak.platform.api.PlatformProblemException.class)
        .hasMessage("The actor is not authorized for operator support.");
  }

  @Test
  void providerFailureAbstainsWithoutExposingFailureDetails() {
    OperatorSupportService service =
        service(
            true,
            (questionKey, sources) -> {
              throw new IllegalStateException("synthetic-secret-provider-detail");
            },
            true);

    OperatorSupportResponse response =
        answer(service, OperatorSupportQuestionKey.FEDERATION_OUTCOME_UNKNOWN);

    assertThat(response.status()).isEqualTo(OperatorSupportResponse.Status.ABSTAINED);
    assertThat(response.reasonCode())
        .isEqualTo(OperatorSupportResponse.ReasonCode.PROVIDER_UNAVAILABLE);
    assertThat(response.toString()).doesNotContain("synthetic-secret-provider-detail");
  }

  private static OperatorSupportResponse answer(
      OperatorSupportService service, OperatorSupportQuestionKey key) {
    return service.answer(OPERATOR, CorrelationId.generate(), key);
  }

  private static void assertInvalidProviderOutput(OperatorSupportResponse response) {
    assertThat(response.status()).isEqualTo(OperatorSupportResponse.Status.ABSTAINED);
    assertThat(response.reasonCode())
        .isEqualTo(OperatorSupportResponse.ReasonCode.INVALID_PROVIDER_OUTPUT);
    assertThat(response.answer()).isEmpty();
    assertThat(response.sources()).isEmpty();
  }

  private static OperatorSupportService service(
      boolean enabled, OperatorSupportProvider provider, boolean grantAllowed) {
    OperatorSupportProperties properties = new OperatorSupportProperties();
    properties.setEnabled(enabled);
    AuthorizationService authorization =
        new AuthorizationService(
            (actor, authority, scope) -> grantAllowed,
            new FunctionalAuthorityCatalog(List.of(OperatorSupportAuthorities.read())));
    ExecutionContextFactory contexts =
        new ExecutionContextFactory(
            (actor, requestedScope) -> requestedScope instanceof RequestedExecutionScope.Platform);
    return new OperatorSupportService(
        properties, OperatorSupportCorpus.approved(), provider, authorization, contexts);
  }
}
