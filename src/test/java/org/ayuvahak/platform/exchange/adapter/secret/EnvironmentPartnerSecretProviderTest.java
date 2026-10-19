package org.ayuvahak.platform.exchange.adapter.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import org.ayuvahak.platform.exchange.application.PartnerSecretMaterial;
import org.ayuvahak.platform.exchange.application.PartnerSecretProviderUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.PropertySource;
import org.springframework.mock.env.MockEnvironment;

class EnvironmentPartnerSecretProviderTest {

  @Test
  void resolvesOnlyNamedEnvironmentReferencesAndRedactsMaterial() {
    MockEnvironment environment = new MockEnvironment().withProperty("SYNTHETIC_SECRET", "marker");
    EnvironmentPartnerSecretProvider provider = new EnvironmentPartnerSecretProvider(environment);

    PartnerSecretMaterial material = provider.resolve("env:SYNTHETIC_SECRET").orElseThrow();
    char[] copy = material.copy();
    try {
      assertThat(new String(copy)).isEqualTo("marker");
      assertThat(material.toString()).doesNotContain("marker");
    } finally {
      Arrays.fill(copy, '\0');
      material.close();
    }
    assertThat(provider.resolve("file:C:/outside")).isEmpty();
    assertThat(provider.resolve("env:MISSING_SECRET")).isEmpty();
  }

  @Test
  void resolvesRotatedValuesFreshlyAndTreatsAnEmptyValueAsRevoked() {
    MockEnvironment environment =
        new MockEnvironment().withProperty("SYNTHETIC_ROTATING_SECRET", "synthetic-old-value");
    EnvironmentPartnerSecretProvider provider = new EnvironmentPartnerSecretProvider(environment);

    PartnerSecretMaterial oldMaterial =
        provider.resolve("env:SYNTHETIC_ROTATING_SECRET").orElseThrow();
    char[] oldCopy = oldMaterial.copy();
    try {
      assertThat(new String(oldCopy)).isEqualTo("synthetic-old-value");

      environment.setProperty("SYNTHETIC_ROTATING_SECRET", "synthetic-new-value");
      try (PartnerSecretMaterial newMaterial =
          provider.resolve("env:SYNTHETIC_ROTATING_SECRET").orElseThrow()) {
        char[] newCopy = newMaterial.copy();
        try {
          assertThat(new String(newCopy)).isEqualTo("synthetic-new-value");
        } finally {
          Arrays.fill(newCopy, '\0');
        }
      }

      environment.setProperty("SYNTHETIC_ROTATING_SECRET", "");
      assertThat(provider.resolve("env:SYNTHETIC_ROTATING_SECRET")).isEmpty();
    } finally {
      Arrays.fill(oldCopy, '\0');
      oldMaterial.close();
    }
  }

  @Test
  void reportsProviderFailureWithoutRetainingUntrustedDiagnosticDetail() {
    MockEnvironment environment = new MockEnvironment();
    environment
        .getPropertySources()
        .addFirst(
            new PropertySource<Object>("synthetic-unavailable-secret-source") {
              @Override
              public Object getProperty(String name) {
                throw new IllegalStateException("synthetic-secret-provider-detail");
              }
            });
    EnvironmentPartnerSecretProvider provider = new EnvironmentPartnerSecretProvider(environment);

    assertThatThrownBy(() -> provider.resolve("env:SYNTHETIC_SECRET"))
        .isInstanceOf(PartnerSecretProviderUnavailableException.class)
        .hasMessage("Configured secret provider is unavailable")
        .hasNoCause()
        .satisfies(
            exception -> {
              assertThat(exception.toString())
                  .isEqualTo("PartnerSecretProviderUnavailableException");
              assertThat(exception.getSuppressed()).isEmpty();
            });
  }
}
