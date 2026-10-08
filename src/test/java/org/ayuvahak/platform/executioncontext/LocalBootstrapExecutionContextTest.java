package org.ayuvahak.platform.executioncontext;

import static org.assertj.core.api.Assertions.assertThat;

import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.core.SourceKind;
import org.junit.jupiter.api.Test;

class LocalBootstrapExecutionContextTest {

  @Test
  void representsLocalOperatorControlWithoutClaimingOidcAuthentication() {
    ExecutionContextFactory factory =
        new ExecutionContextFactory(
            (actor, scope) -> {
              throw new AssertionError("platform bootstrap must not consult organization registry");
            });
    ActorReference operator =
        ActorReference.human(new IssuerId("urn:ayuvahak:local-operator"), "operator-test-01");

    ExecutionContext context =
        factory.forLocalOperatorBootstrap(operator, CorrelationId.generate());

    assertThat(context.actor()).isEqualTo(operator);
    assertThat(context.scope().kind()).isEqualTo(ExecutionScope.Kind.PLATFORM);
    assertThat(context.sourceKind()).isEqualTo(SourceKind.LOCAL_OPERATOR_COMMAND);
    assertThat(context.authenticationMechanism())
        .isEqualTo(AuthenticationMechanism.LOCAL_OPERATOR_CONTROL);
  }
}
