package org.ayuvahak.platform.audit.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.executioncontext.ExecutionScope;

/** Minimized, structured evidence for one safety- or access-relevant action. */
public record AuditEvidence(
    AuditActionKind actionKind,
    Optional<ActorReference> actor,
    Optional<ExecutionScope> scope,
    String actionCode,
    String targetType,
    Optional<InternalId> targetId,
    Instant occurredAt,
    AuditOutcome outcome,
    Optional<String> reasonCode,
    AuditAuthenticationEvidence authenticationEvidence,
    AuditAuthorizationOutcome authorizationOutcome,
    CorrelationId correlationId) {

  private static final Pattern ACTION_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,99}");
  private static final Pattern TARGET_TYPE = Pattern.compile("[A-Z][A-Za-z0-9]{0,63}");
  private static final Pattern REASON_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,79}");

  public AuditEvidence {
    Objects.requireNonNull(actionKind, "actionKind");
    actor = Objects.requireNonNull(actor, "actor");
    scope = Objects.requireNonNull(scope, "scope");
    targetId = Objects.requireNonNull(targetId, "targetId");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(outcome, "outcome");
    reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
    Objects.requireNonNull(authenticationEvidence, "authenticationEvidence");
    Objects.requireNonNull(authorizationOutcome, "authorizationOutcome");
    Objects.requireNonNull(correlationId, "correlationId");
    if (actionCode == null || !ACTION_CODE.matcher(actionCode).matches()) {
      throw new IllegalArgumentException("actionCode must be a stable uppercase code");
    }
    if (targetType == null || !TARGET_TYPE.matcher(targetType).matches()) {
      throw new IllegalArgumentException("targetType must be a stable identifier");
    }
    reasonCode.ifPresent(
        reason -> {
          if (!REASON_CODE.matcher(reason).matches()) {
            throw new IllegalArgumentException("reasonCode must be a stable uppercase code");
          }
        });
    validateAttribution(actor, scope, authenticationEvidence, authorizationOutcome);
    if (outcome == AuditOutcome.SUCCEEDED
        && authorizationOutcome == AuditAuthorizationOutcome.DENIED) {
      throw new IllegalArgumentException("a denied action cannot be recorded as successful");
    }
  }

  private static void validateAttribution(
      Optional<ActorReference> actor,
      Optional<ExecutionScope> scope,
      AuditAuthenticationEvidence authenticationEvidence,
      AuditAuthorizationOutcome authorizationOutcome) {
    if (actor.isEmpty()) {
      if (scope.isPresent() || authorizationOutcome != AuditAuthorizationOutcome.NOT_EVALUATED) {
        throw new IllegalArgumentException(
            "unverified actors cannot carry execution scope or an authorization decision");
      }
      if (authenticationEvidence != AuditAuthenticationEvidence.NONE
          && authenticationEvidence != AuditAuthenticationEvidence.INVALID_CREDENTIAL
          && authenticationEvidence != AuditAuthenticationEvidence.TRUST_SOURCE_UNAVAILABLE) {
        throw new IllegalArgumentException(
            "unverified actors can carry only safe authentication failure evidence");
      }
      return;
    }

    ActorKind actorKind = actor.orElseThrow().kind();
    boolean humanEvidence =
        authenticationEvidence == AuditAuthenticationEvidence.HUMAN_ACCESS_TOKEN
            || authenticationEvidence == AuditAuthenticationEvidence.LOCAL_OPERATOR_CONTROL;
    boolean serviceEvidence =
        authenticationEvidence == AuditAuthenticationEvidence.SERVICE_ACCESS_TOKEN
            || authenticationEvidence == AuditAuthenticationEvidence.CONFIGURED_SERVICE_IDENTITY;
    if ((actorKind == ActorKind.HUMAN && !humanEvidence)
        || (actorKind == ActorKind.SERVICE && !serviceEvidence)) {
      throw new IllegalArgumentException(
          "authentication evidence must match the verified actor kind");
    }
  }

  @Override
  public String toString() {
    return "AuditEvidence[actionKind="
        + actionKind
        + ", actorKind="
        + actor.map(ActorReference::kind).orElse(null)
        + ", actionCode="
        + actionCode
        + ", outcome="
        + outcome
        + ", correlationId="
        + correlationId
        + ", references=redacted]";
  }
}
