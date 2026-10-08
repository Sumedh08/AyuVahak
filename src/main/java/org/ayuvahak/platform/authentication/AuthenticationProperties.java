package org.ayuvahak.platform.authentication;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("ayuvahak.authentication")
public record AuthenticationProperties(
    @NotBlank String issuer,
    @NotBlank String jwkSetUri,
    @NotBlank String audience,
    @NotEmpty Set<String> serviceClientIds,
    boolean allowHttp) {

  public AuthenticationProperties {
    serviceClientIds = Set.copyOf(serviceClientIds);
    if (serviceClientIds.stream().anyMatch(String::isBlank)) {
      throw new IllegalArgumentException("configured service client identifiers must not be blank");
    }
    validateEndpoint("issuer", issuer, allowHttp);
    validateEndpoint("JWK-set URI", jwkSetUri, allowHttp);
  }

  private static void validateEndpoint(String name, String value, boolean allowHttp) {
    if (value == null || value.isBlank()) {
      return;
    }
    try {
      URI endpoint = new URI(value);
      String scheme = endpoint.getScheme();
      boolean secure = "https".equalsIgnoreCase(scheme);
      boolean explicitlyAllowedHttp = allowHttp && "http".equalsIgnoreCase(scheme);
      if ((!secure && !explicitlyAllowedHttp)
          || endpoint.getHost() == null
          || endpoint.getUserInfo() != null
          || endpoint.getFragment() != null) {
        throw new IllegalArgumentException(
            name + " must be an absolute HTTPS URI without user information or a fragment");
      }
    } catch (URISyntaxException exception) {
      throw new IllegalArgumentException(name + " must be a valid absolute URI", exception);
    }
  }
}
