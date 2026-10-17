package org.ayuvahak.platform.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.ayuvahak.platform.api.PlatformErrorCode;
import org.ayuvahak.platform.api.PlatformProblemException;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.executioncontext.ExecutionContextFactory;
import org.ayuvahak.platform.executioncontext.ExecutionScope;
import org.ayuvahak.platform.executioncontext.RequestedExecutionScope;
import org.ayuvahak.platform.notification.adapter.api.NotificationStreamController;
import org.ayuvahak.platform.notification.application.NotificationFeedAuthorizer;
import org.ayuvahak.platform.notification.application.NotificationHintReader;
import org.ayuvahak.platform.notification.application.NotificationProjectionPage;
import org.ayuvahak.platform.notification.application.NotificationStreamService;
import org.ayuvahak.platform.notification.configuration.NotificationStreamProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class NotificationStreamControllerTest {

  @Test
  void acceptsReconnectCursorOnlyFromHeaderAndRevalidatesTheExplicitScope() throws Exception {
    NotificationStreamProperties properties = new NotificationStreamProperties();
    ActorReference actor =
        ActorReference.human(new IssuerId("https://identity.example.test/realm"), "SYN-USER-001");
    String organizationId = "00000000-0000-4000-8000-000000000001";
    String requestedCursor = "00000000-0000-0000-0000-000000000003";
    String currentCursor = "00000000-0000-0000-0000-000000000004";
    AtomicReference<Optional<String>> observedCursor = new AtomicReference<>();
    AtomicReference<ExecutionScope> observedScope = new AtomicReference<>();
    CountDownLatch queryObserved = new CountDownLatch(1);
    var contextFactory = new ExecutionContextFactory((ignoredActor, ignoredScope) -> true);
    NotificationHintReader reader =
        new NotificationHintReader() {
          @Override
          public String currentCursor(ExecutionScope scope) {
            return currentCursor;
          }

          @Override
          public NotificationProjectionPage listAfter(
              ExecutionScope scope, Optional<String> cursor, int limit) {
            observedScope.set(scope);
            observedCursor.set(cursor);
            queryObserved.countDown();
            return new NotificationProjectionPage(List.of(), Optional.of(currentCursor), true);
          }
        };
    NotificationFeedAuthorizer authorizer =
        (candidate, requestedScope, correlationId) ->
            contextFactory.forInteractiveRequest(candidate, requestedScope, correlationId).scope();
    NotificationStreamService service =
        new NotificationStreamService(reader, authorizer, properties, Clock.systemUTC());
    try {
      NotificationStreamController controller =
          new NotificationStreamController(service, properties);
      MockHttpServletRequest request = new MockHttpServletRequest();
      request.addHeader(NotificationStreamController.LAST_EVENT_ID_HEADER, requestedCursor);

      var response = controller.open(actor, "ORGANIZATION", organizationId, null, request);

      assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.OK.value());
      assertThat(queryObserved.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(observedCursor.get()).contains(requestedCursor);
      assertThat(observedScope.get().kind()).isEqualTo(ExecutionScope.Kind.ORGANIZATION);
      assertThat(observedScope.get().organization().orElseThrow().value())
          .isEqualTo(organizationId);
    } finally {
      service.close();
    }
  }

  @Test
  void rejectsUrlParametersSoCursorAndFiltersCannotBePlacedInTheUrl() {
    NotificationStreamProperties properties = new NotificationStreamProperties();
    ActorReference actor =
        ActorReference.human(new IssuerId("https://identity.example.test/realm"), "SYN-USER-001");
    RequestedExecutionScope organization =
        RequestedExecutionScope.organization(
            new ExternalReference(
                new IssuerId("urn:ayuvahak:directory"),
                "Organization",
                "00000000-0000-0000-0000-000000000001"));
    ExecutionScope scope =
        new ExecutionContextFactory((ignoredActor, ignoredScope) -> true)
            .forInteractiveRequest(actor, organization, CorrelationId.generate())
            .scope();
    NotificationHintReader reader =
        new NotificationHintReader() {
          @Override
          public String currentCursor(ExecutionScope ignoredScope) {
            return "00000000-0000-0000-0000-000000000002";
          }

          @Override
          public NotificationProjectionPage listAfter(
              ExecutionScope ignoredScope, Optional<String> cursor, int limit) {
            return new NotificationProjectionPage(List.of(), cursor, false);
          }
        };
    NotificationFeedAuthorizer authorizer =
        (ignoredActor, ignoredRequestedScope, ignoredCorrelationId) -> scope;
    NotificationStreamService service =
        new NotificationStreamService(reader, authorizer, properties, Clock.systemUTC());
    NotificationStreamController controller = new NotificationStreamController(service, properties);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("cursor", "00000000-0000-0000-0000-000000000001");

    assertThatThrownBy(() -> controller.open(null, null, null, null, request))
        .isInstanceOf(PlatformProblemException.class)
        .satisfies(
            failure -> {
              PlatformProblemException problem = (PlatformProblemException) failure;
              org.assertj.core.api.Assertions.assertThat(problem.status())
                  .isEqualTo(HttpStatus.BAD_REQUEST);
              org.assertj.core.api.Assertions.assertThat(problem.code())
                  .isEqualTo(PlatformErrorCode.MALFORMED_REQUEST);
            });
    service.close();
  }
}
