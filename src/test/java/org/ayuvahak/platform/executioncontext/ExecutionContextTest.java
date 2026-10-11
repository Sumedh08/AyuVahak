package org.ayuvahak.platform.executioncontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CausationId;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.core.SourceKind;
import org.junit.jupiter.api.Test;

class ExecutionContextTest {

  private static final IssuerId IDP = new IssuerId("https://identity.example/issuer");
  private static final IssuerId DIRECTORY = new IssuerId("urn:synthetic:directory");
  private static final ActorReference HUMAN = ActorReference.human(IDP, "human-17");
  private static final ActorReference SERVICE = ActorReference.service(IDP, "worker-3");
  private static final ExternalReference ORG_A = reference("Organization", "org-a");
  private static final ExternalReference FACILITY_A = reference("Facility", "facility-a");
  private static final ExternalReference FACILITY_B = reference("Facility", "facility-b");

  @Test
  void interactiveRequestReceivesTrustedScopeOnlyAfterServerValidation() {
    RequestedExecutionScope requested = RequestedExecutionScope.organization(ORG_A);
    AtomicReference<ActorReference> validatedActor = new AtomicReference<>();
    AtomicReference<RequestedExecutionScope> validatedScope = new AtomicReference<>();
    ExecutionContextFactory factory =
        new ExecutionContextFactory(
            (actor, scope) -> {
              validatedActor.set(actor);
              validatedScope.set(scope);
              return true;
            });
    CorrelationId correlation = new CorrelationId("http-req-44");

    ExecutionContext context = factory.forInteractiveRequest(HUMAN, requested, correlation);

    assertThat(validatedActor).hasValue(HUMAN);
    assertThat(validatedScope).hasValue(requested);
    assertThat(context.actor()).isEqualTo(HUMAN);
    assertThat(context.scope().kind()).isEqualTo(ExecutionScope.Kind.ORGANIZATION);
    assertThat(context.scope().organization()).contains(ORG_A);
    assertThat(context.scope().facility()).isEmpty();
    assertThat(context.sourceKind()).isEqualTo(SourceKind.INTERACTIVE_REQUEST);
    assertThat(context.authenticationMechanism())
        .isEqualTo(AuthenticationMechanism.HUMAN_ACCESS_TOKEN);
    assertThat(context.correlationId()).isEqualTo(correlation);
    assertThat(context.causationId()).isEmpty();
  }

  @Test
  void unknownOrUnauthorizedScopeCannotBecomeAnExecutionContext() {
    ExecutionContextFactory factory = new ExecutionContextFactory((actor, scope) -> false);

    assertThatThrownBy(
            () ->
                factory.forInteractiveRequest(
                    HUMAN,
                    RequestedExecutionScope.organization(reference("Organization", "unknown")),
                    new CorrelationId("req-unknown")))
        .isInstanceOf(ExecutionScopeDeniedException.class)
        .hasMessage("execution scope is not valid for this actor");

    assertThatThrownBy(
            () ->
                factory.forInteractiveRequest(HUMAN, null, new CorrelationId("req-missing-scope")))
        .isInstanceOf(ExecutionScopeDeniedException.class);
  }

  @Test
  void unavailableScopeAuthorityFailsClosed() {
    ExecutionContextFactory factory =
        new ExecutionContextFactory(
            (actor, scope) -> {
              throw new IllegalStateException("synthetic validator outage");
            });

    assertThatThrownBy(
            () ->
                factory.forInteractiveRequest(
                    HUMAN,
                    RequestedExecutionScope.organization(ORG_A),
                    new CorrelationId("req-validator-outage")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("synthetic validator outage");
  }

  @Test
  void facilityScopeMustCarryTypedOrganizationAndFacilityReferences() {
    assertThatThrownBy(
            () -> RequestedExecutionScope.facility(ORG_A, reference("Location", "facility-a")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Facility");

    ExecutionContextFactory factory =
        new ExecutionContextFactory(
            (actor, scope) ->
                scope instanceof RequestedExecutionScope.Facility facility
                    && facility.organization().equals(ORG_A)
                    && facility.facility().equals(FACILITY_A));

    assertThatThrownBy(
            () ->
                factory.forInteractiveRequest(
                    HUMAN,
                    RequestedExecutionScope.facility(ORG_A, FACILITY_B),
                    new CorrelationId("req-wrong-facility")))
        .isInstanceOf(ExecutionScopeDeniedException.class);
  }

  @Test
  void backgroundWorkRequiresAnExplicitServiceActorAndScope() {
    ThreadLocal<ActorReference> requestActor = new ThreadLocal<>();
    requestActor.set(HUMAN);
    try {
      ExecutionContextFactory factory = new ExecutionContextFactory((actor, scope) -> true);
      CorrelationId correlation = new CorrelationId("job-chain-9");
      CausationId causation = new CausationId("command-3");

      ExecutionContext context =
          factory.forBackgroundWork(
              SERVICE,
              RequestedExecutionScope.facility(ORG_A, FACILITY_A),
              correlation,
              Optional.of(causation));

      assertThat(context.actor()).isEqualTo(SERVICE);
      assertThat(context.actor().kind()).isEqualTo(ActorKind.SERVICE);
      assertThat(context.scope().organization()).contains(ORG_A);
      assertThat(context.scope().facility()).contains(FACILITY_A);
      assertThat(context.sourceKind()).isEqualTo(SourceKind.BACKGROUND_WORK);
      assertThat(context.authenticationMechanism())
          .isEqualTo(AuthenticationMechanism.CONFIGURED_SERVICE_IDENTITY);
      assertThat(context.correlationId()).isEqualTo(correlation);
      assertThat(context.causationId()).contains(causation);
      assertThat(requestActor.get()).isEqualTo(HUMAN);

      assertThatThrownBy(
              () ->
                  factory.forBackgroundWork(
                      HUMAN,
                      RequestedExecutionScope.organization(ORG_A),
                      correlation,
                      Optional.empty()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("background work requires a service actor");
      assertThatThrownBy(
              () ->
                  factory.forBackgroundWork(
                      SERVICE, RequestedExecutionScope.platform(), correlation, Optional.empty()))
          .isInstanceOf(ExecutionScopeDeniedException.class);
    } finally {
      requestActor.remove();
    }
  }

  @Test
  void integrationRequestsUseServerAssignedSourceAndServiceAuthenticationCategory() {
    ExecutionContextFactory factory = new ExecutionContextFactory((actor, scope) -> true);

    ExecutionContext context =
        factory.forIntegrationRequest(
            SERVICE, RequestedExecutionScope.platform(), new CorrelationId("integration-10"));

    assertThat(context.scope().kind()).isEqualTo(ExecutionScope.Kind.PLATFORM);
    assertThat(context.sourceKind()).isEqualTo(SourceKind.INTEGRATION);
    assertThat(context.authenticationMechanism())
        .isEqualTo(AuthenticationMechanism.SERVICE_CLIENT_CREDENTIALS);
    assertThatThrownBy(
            () ->
                factory.forIntegrationRequest(
                    HUMAN,
                    RequestedExecutionScope.platform(),
                    new CorrelationId("bad-integration-actor")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("integration requests require a service actor");
  }

  @Test
  void deploymentBoundedBackgroundWorkCreatesAnExplicitPlatformServiceContext() {
    AtomicReference<RequestedExecutionScope> validatedScope = new AtomicReference<>();
    ExecutionContextFactory factory =
        new ExecutionContextFactory(
            (actor, scope) -> {
              validatedScope.set(scope);
              return true;
            });
    CorrelationId correlation = new CorrelationId("deployment-boundary-lookup-1");

    ExecutionContext context = factory.forDeploymentBoundedBackgroundWork(SERVICE, correlation);

    assertThat(validatedScope.get()).isInstanceOf(RequestedExecutionScope.Platform.class);
    assertThat(context.actor()).isEqualTo(SERVICE);
    assertThat(context.scope().kind()).isEqualTo(ExecutionScope.Kind.PLATFORM);
    assertThat(context.sourceKind()).isEqualTo(SourceKind.BACKGROUND_WORK);
    assertThat(context.authenticationMechanism())
        .isEqualTo(AuthenticationMechanism.CONFIGURED_SERVICE_IDENTITY);
    assertThat(context.correlationId()).isEqualTo(correlation);
    assertThat(context.causationId()).isEmpty();
    assertThatThrownBy(() -> factory.forDeploymentBoundedBackgroundWork(HUMAN, correlation))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("background work requires a service actor");
  }

  @Test
  void correlationAndCausationRemainSeparateTypedReferences() {
    CorrelationId correlation = new CorrelationId("workflow-chain-1");
    CausationId causation = new CausationId("source-command-2");
    ExecutionContext context =
        new ExecutionContextFactory((actor, scope) -> true)
            .forBackgroundWork(
                SERVICE,
                RequestedExecutionScope.organization(ORG_A),
                correlation,
                Optional.of(causation));

    assertThat(context.correlationId()).isEqualTo(correlation);
    assertThat(context.causationId()).contains(causation);
    assertThat(context.correlationId()).isNotEqualTo(causation);
  }

  private static ExternalReference reference(String type, String value) {
    return new ExternalReference(DIRECTORY, type, value);
  }
}
