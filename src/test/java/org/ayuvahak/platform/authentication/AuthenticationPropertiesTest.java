package org.ayuvahak.platform.authentication;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

class AuthenticationPropertiesTest {

  @Test
  void rejectsInsecureHttpTrustEndpointsUnlessExplicitlyEnabledForLocalDevelopment() {
    assertThatThrownBy(
            () ->
                new AuthenticationProperties(
                    "http://identity.example.test/realms/ayuvahak",
                    "https://identity.example.test/keys",
                    "ayuvahak-api",
                    Set.of("transfer-agent"),
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("HTTPS");

    assertThatThrownBy(
            () ->
                new AuthenticationProperties(
                    "https://identity.example.test/realms/ayuvahak",
                    "http://identity.example.test/keys",
                    "ayuvahak-api",
                    Set.of("transfer-agent"),
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("HTTPS");
  }

  @Test
  void rejectsTrustEndpointsWithCredentialsOrFragment() {
    assertThatThrownBy(
            () ->
                new AuthenticationProperties(
                    "https://operator:password@identity.example.test/realms/ayuvahak",
                    "https://identity.example.test/keys",
                    "ayuvahak-api",
                    Set.of("transfer-agent"),
                    false))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                new AuthenticationProperties(
                    "https://identity.example.test/realms/ayuvahak#fragment",
                    "https://identity.example.test/keys",
                    "ayuvahak-api",
                    Set.of("transfer-agent"),
                    false))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
