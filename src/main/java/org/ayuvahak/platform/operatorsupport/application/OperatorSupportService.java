package org.ayuvahak.platform.operatorsupport.application;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.ayuvahak.platform.api.PlatformErrorCode;
import org.ayuvahak.platform.api.PlatformProblemException;
import org.ayuvahak.platform.authorization.application.AuthorizationService;
import org.ayuvahak.platform.authorization.domain.AuthorizationDecision;
import org.ayuvahak.platform.configuration.OperatorSupportProperties;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.executioncontext.ExecutionContext;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Grant-checked, documentation-only support capability with no persistence or action ports. */
@Service
public final class OperatorSupportService {

  public static final String CAPABILITY_VERSION = "operator-support-v1";
  private static final int MAX_ANSWER_LENGTH = 1400;
  private static final int MAX_CITATIONS = 4;

  private final OperatorSupportProperties properties;
  private final OperatorSupportCorpus corpus;
  private final OperatorSupportProvider provider;
  private final AuthorizationService authorization;
  private final ExecutionContextFactory contexts;

  public OperatorSupportService(
      OperatorSupportProperties properties,
      OperatorSupportCorpus corpus,
      OperatorSupportProvider provider,
      AuthorizationService authorization,
      ExecutionContextFactory contexts) {
    this.properties = Objects.requireNonNull(properties, "operator support properties");
    this.corpus = Objects.requireNonNull(corpus, "approved corpus");
    this.provider = Objects.requireNonNull(provider, "operator support provider");
    this.authorization = Objects.requireNonNull(authorization, "authorization service");
    this.contexts = Objects.requireNonNull(contexts, "execution context factory");
  }

  public OperatorSupportResponse answer(
      ActorReference actor, CorrelationId correlationId, OperatorSupportQuestionKey questionKey) {
    requireActor(actor);
    Objects.requireNonNull(correlationId, "correlation id");
    Objects.requireNonNull(questionKey, "question key");

    authorize(actor, correlationId);
    if (!properties.isEnabled()) {
      return abstained(OperatorSupportResponse.ReasonCode.DISABLED);
    }

    List<ApprovedOperatorSupportSource> sources = corpus.sourcesFor(questionKey);
    if (sources.isEmpty()) {
      return abstained(OperatorSupportResponse.ReasonCode.NO_APPROVED_EVIDENCE);
    }

    ProviderResponse providerResponse;
    try {
      providerResponse = provider.answer(questionKey, sources);
    } catch (RuntimeException unavailable) {
      return abstained(OperatorSupportResponse.ReasonCode.PROVIDER_UNAVAILABLE);
    }
    return validate(providerResponse, sources);
  }

  private void authorize(ActorReference actor, CorrelationId correlationId) {
    if (actor.kind() != ActorKind.HUMAN) {
      throw new PlatformProblemException(
          HttpStatus.FORBIDDEN,
          PlatformErrorCode.AUTHORIZATION_DENIED,
          "Operator support is available only to an authorized human operator.");
    }
    ExecutionContext context =
        contexts.forInteractiveRequest(actor, RequestedExecutionScope.platform(), correlationId);
    AuthorizationDecision decision =
        authorization.evaluate(context, OperatorSupportAuthorities.READ_CODE);
    if (decision.isAllowed()) {
      return;
    }
    if (decision.denialReason().orElse(null)
        == AuthorizationDecision.DenialReason.GRANT_STORE_UNAVAILABLE) {
      throw new PlatformProblemException(
          HttpStatus.SERVICE_UNAVAILABLE,
          PlatformErrorCode.AUTHORIZATION_UNAVAILABLE,
          "Operator support access could not be verified.");
    }
    throw new PlatformProblemException(
        HttpStatus.FORBIDDEN,
        PlatformErrorCode.AUTHORIZATION_DENIED,
        "The actor is not authorized for operator support.");
  }

  private OperatorSupportResponse validate(
      ProviderResponse response, List<ApprovedOperatorSupportSource> approvedSources) {
    if (response == null || response.status() == null || response.reasonCode() == null) {
      return abstained(OperatorSupportResponse.ReasonCode.INVALID_PROVIDER_OUTPUT);
    }
    if (response.status() == OperatorSupportResponse.Status.ABSTAINED) {
      if (response.answer() != null
          && response.answer().isEmpty()
          && response.sourceIds() != null
          && response.sourceIds().isEmpty()
          && (response.reasonCode() == OperatorSupportResponse.ReasonCode.NO_APPROVED_EVIDENCE
              || response.reasonCode()
                  == OperatorSupportResponse.ReasonCode.INVALID_PROVIDER_OUTPUT)) {
        return abstained(response.reasonCode());
      }
      return abstained(OperatorSupportResponse.ReasonCode.INVALID_PROVIDER_OUTPUT);
    }
    if (response.status() != OperatorSupportResponse.Status.ADVISORY
        || response.reasonCode() != OperatorSupportResponse.ReasonCode.NONE
        || !validAnswer(response.answer())
        || response.sourceIds() == null
        || response.sourceIds().isEmpty()
        || response.sourceIds().size() > MAX_CITATIONS
        || new HashSet<>(response.sourceIds()).size() != response.sourceIds().size()) {
      return abstained(OperatorSupportResponse.ReasonCode.INVALID_PROVIDER_OUTPUT);
    }

    Map<String, ApprovedOperatorSupportSource> allowedById =
        approvedSources.stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    ApprovedOperatorSupportSource::sourceId, Function.identity()));
    List<OperatorSupportResponse.SourceCitation> citations =
        response.sourceIds().stream()
            .map(allowedById::get)
            .filter(Objects::nonNull)
            .map(OperatorSupportResponse.SourceCitation::from)
            .toList();
    if (citations.size() != response.sourceIds().size()) {
      return abstained(OperatorSupportResponse.ReasonCode.INVALID_PROVIDER_OUTPUT);
    }
    return new OperatorSupportResponse(
        OperatorSupportResponse.Status.ADVISORY,
        response.answer(),
        citations,
        CAPABILITY_VERSION,
        corpus.version(),
        OperatorSupportResponse.ReasonCode.NONE);
  }

  private static boolean validAnswer(String answer) {
    return answer != null
        && !answer.isBlank()
        && answer.length() <= MAX_ANSWER_LENGTH
        && answer
            .codePoints()
            .noneMatch(
                codePoint ->
                    Character.isISOControl(codePoint) && codePoint != '\n' && codePoint != '\t');
  }

  private OperatorSupportResponse abstained(OperatorSupportResponse.ReasonCode reasonCode) {
    return new OperatorSupportResponse(
        OperatorSupportResponse.Status.ABSTAINED,
        "",
        List.of(),
        CAPABILITY_VERSION,
        corpus.version(),
        reasonCode);
  }

  private static void requireActor(ActorReference actor) {
    if (actor == null) {
      throw new PlatformProblemException(
          HttpStatus.UNAUTHORIZED,
          PlatformErrorCode.AUTHENTICATION_REQUIRED,
          "A verified actor is required.");
    }
  }
}
