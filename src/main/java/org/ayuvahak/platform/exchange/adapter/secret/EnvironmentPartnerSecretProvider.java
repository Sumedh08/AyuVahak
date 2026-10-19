package org.ayuvahak.platform.exchange.adapter.secret;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.ayuvahak.platform.exchange.application.PartnerSecretMaterial;
import org.ayuvahak.platform.exchange.application.PartnerSecretProvider;
import org.ayuvahak.platform.exchange.application.PartnerSecretProviderUnavailableException;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Resolves local/deployment secrets only from explicit environment-variable references. */
@Component
final class EnvironmentPartnerSecretProvider implements PartnerSecretProvider {

  private static final Pattern VARIABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

  private final Environment environment;

  EnvironmentPartnerSecretProvider(Environment environment) {
    this.environment = Objects.requireNonNull(environment, "environment");
  }

  @Override
  public Optional<PartnerSecretMaterial> resolve(String secretReference) {
    if (secretReference == null || !secretReference.startsWith("env:")) {
      return Optional.empty();
    }
    String variableName = secretReference.substring("env:".length());
    if (!VARIABLE_NAME.matcher(variableName).matches()) {
      return Optional.empty();
    }
    String configuredValue;
    try {
      configuredValue = environment.getProperty(variableName);
    } catch (RuntimeException exception) {
      throw new PartnerSecretProviderUnavailableException();
    }
    return configuredValue == null || configuredValue.isEmpty()
        ? Optional.empty()
        : Optional.of(new PartnerSecretMaterial(configuredValue.toCharArray()));
  }
}
