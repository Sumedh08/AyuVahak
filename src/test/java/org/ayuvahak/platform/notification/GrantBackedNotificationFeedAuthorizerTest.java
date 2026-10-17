package org.ayuvahak.platform.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.ayuvahak.platform.api.PlatformProblemException;
import org.ayuvahak.platform.authorization.application.AuthorizationGrantLookup;
import org.ayuvahak.platform.authorization.application.AuthorizationService;
import org.ayuvahak.platform.authorization.domain.FunctionalAuthorityCatalog;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.platform.notification.application.GrantBackedNotificationFeedAuthorizer;
import org.ayuvahak.platform.notification.domain.NotificationAuthorities;
import org.junit.jupiter.api.Test;

class GrantBackedNotificationFeedAuthorizerTest {

  @Test
  void rebuildsAndValidatesTheExactScopeBeforeEvaluatingTheCurrentGrant() {
    ActorReference actor = actor();
    RequestedExecutionScope requested =
        RequestedExecutionScope.organization(
            new ExternalReference(
                new IssuerId("urn:ayuvahak:directory"), "Organization", "SYN-ORG-001"));
    AtomicBoolean active = new AtomicBoolean(true);
    AtomicReference<ActorReference> evaluatedActor = new AtomicReference<>();
    AtomicReference<ExecutionScope> evaluatedScope = new AtomicReference<>();
    AuthorizationGrantLookup lookup =
        (candidate, authority, scope) -> {
          assertThat(authority).isEqualTo(NotificationAuthorities.feedRead());
          evaluatedActor.set(candidate);
          evaluatedScope.set(scope);
          return active.get();
        };
    var authorizer = authorizer(lookup);
    CorrelationId correlationId = new CorrelationId("notification-auth-test");

    ExecutionScope authorized = authorizer.authorize(actor, requested, correlationId);

    assertThat(evaluatedActor.get()).isEqualTo(actor);
    assertThat(evaluatedScope.get()).isEqualTo(authorized);
    assertThat(authorized.kind()).isEqualTo(ExecutionScope.Kind.ORGANIZATION);
    active.set(false);
    assertThatThrownBy(() -> authorizer.authorize(actor, requested, correlationId))
        .isInstanceOf(PlatformProblemException.class)
        .hasMessage("The actor is not authorized for this notification scope.");
  }

  @Test
  void rejectsPlatformWideAndUnvalidatedScopes() {
    ActorReference actor = actor();
    AuthorizationGrantLookup grantLookup = (candidate, authority, scope) -> true;
    var authorizer = authorizer(grantLookup);

    assertThatThrownBy(
            () ->
                authorizer.authorize(
                    actor, RequestedExecutionScope.platform(), CorrelationId.generate()))
        .isInstanceOf(PlatformProblemException.class)
        .hasMessage("A validated organization or facility scope is required.");

    var rejectingScopeAuthorizer =
        new GrantBackedNotificationFeedAuthorizer(
            new ExecutionContextFactory((candidate, scope) -> false),
            new AuthorizationService(
                grantLookup,
                new FunctionalAuthorityCatalog(List.of(NotificationAuthorities.feedRead()))));
    RequestedExecutionScope organization =
        RequestedExecutionScope.organization(
            new ExternalReference(
                new IssuerId("urn:ayuvahak:directory"), "Organization", "SYN-ORG-001"));
    assertThatThrownBy(
            () -> rejectingScopeAuthorizer.authorize(actor, organization, CorrelationId.generate()))
        .isInstanceOf(PlatformProblemException.class)
        .hasMessage("A validated organization or facility scope is required.");
  }

  private static GrantBackedNotificationFeedAuthorizer authorizer(
      AuthorizationGrantLookup grantLookup) {
    return new GrantBackedNotificationFeedAuthorizer(
        new ExecutionContextFactory((candidate, scope) -> true),
        new AuthorizationService(
            grantLookup,
            new FunctionalAuthorityCatalog(List.of(NotificationAuthorities.feedRead()))));
  }

  private static ActorReference actor() {
    return ActorReference.human(
        new IssuerId("https://identity.example.test/realms/test"), "SYN-USER-001");
  }
}
