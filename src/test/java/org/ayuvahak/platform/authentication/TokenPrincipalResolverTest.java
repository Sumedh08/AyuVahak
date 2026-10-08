package org.ayuvahak.platform.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;
import org.ayuvahak.platform.core.ActorKind;
import org.ayuvahak.platform.core.IssuerId;
import org.junit.jupiter.api.Test;

class TokenPrincipalResolverTest {

  private final TokenPrincipalResolver resolver =
      new TokenPrincipalResolver(
          new IssuerId("https://identity.example.test/realms/ayuvahak"), Set.of("transfer-agent"));

  @Test
  void mapsSubjectOnlyAccessTokenToIssuerScopedHuman() {
    var actor = resolver.resolve(Map.of("sub", "synthetic-person-17", "typ", "Bearer"));

    assertThat(actor.kind()).isEqualTo(ActorKind.HUMAN);
    assertThat(actor.identity().issuer().value())
        .isEqualTo("https://identity.example.test/realms/ayuvahak");
    assertThat(actor.identity().resourceType()).isEqualTo("subject");
    assertThat(actor.identity().value()).isEqualTo("synthetic-person-17");
  }

  @Test
  void mapsConfiguredClientOnlyPrincipalToIssuerScopedService() {
    var actor =
        resolver.resolve(
            Map.of(
                "sub",
                "synthetic-service-subject",
                "client_id",
                "transfer-agent",
                "typ",
                "Bearer"));

    assertThat(actor.kind()).isEqualTo(ActorKind.SERVICE);
    assertThat(actor.identity().resourceType()).isEqualTo("client_id");
    assertThat(actor.identity().value()).isEqualTo("transfer-agent");
  }

  @Test
  void rejectsIdentityTokenProfile() {
    assertThatThrownBy(() -> resolver.resolve(Map.of("sub", "synthetic-person-17", "typ", "ID")))
        .isInstanceOf(InvalidAccessTokenException.class);
  }

  @Test
  void rejectsUnconfiguredServiceClientInsteadOfTreatingItAsHuman() {
    assertThatThrownBy(
            () ->
                resolver.resolve(
                    Map.of(
                        "sub",
                        "synthetic-person-17",
                        "client_id",
                        "unknown-client",
                        "typ",
                        "Bearer")))
        .isInstanceOf(InvalidAccessTokenException.class);
  }

  @Test
  void rejectsBlankOrMissingSubjectAndUnsupportedTokenType() {
    assertThatThrownBy(() -> resolver.resolve(Map.of("typ", "Bearer")))
        .isInstanceOf(InvalidAccessTokenException.class);
    assertThatThrownBy(() -> resolver.resolve(Map.of("sub", " ", "typ", "Bearer")))
        .isInstanceOf(InvalidAccessTokenException.class);
    assertThatThrownBy(() -> resolver.resolve(Map.of("sub", "synthetic-person-17", "typ", "ID")))
        .isInstanceOf(InvalidAccessTokenException.class);
  }
}
