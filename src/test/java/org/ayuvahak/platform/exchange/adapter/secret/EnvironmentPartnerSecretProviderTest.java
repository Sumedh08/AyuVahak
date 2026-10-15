package org.ayuvahak.platform.exchange.adapter.secret;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.ayuvahak.platform.exchange.application.PartnerSecretMaterial;
import org.junit.jupiter.api.Test;
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
}
