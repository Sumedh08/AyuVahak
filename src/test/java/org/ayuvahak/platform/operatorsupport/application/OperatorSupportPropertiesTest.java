package org.ayuvahak.platform.operatorsupport.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import org.ayuvahak.platform.configuration.OperatorSupportProperties;
import org.junit.jupiter.api.Test;

class OperatorSupportPropertiesTest {

  @Test
  void rejectsHttpLocalAndUnapprovedProviderDestinations() {
    assertInvalid("http://api.openai.com/v1/chat/completions", List.of("api.openai.com"));
    assertInvalid("https://127.0.0.1/v1/chat/completions", List.of("127.0.0.1"));
    assertInvalid("https://localhost/v1/chat/completions", List.of("localhost"));
    assertInvalid("https://other.openai.com/v1/chat/completions", List.of("api.openai.com"));
  }

  @Test
  void rejectsCredentialsQueryAndUnexpectedPath() {
    assertInvalid("https://user@api.openai.com/v1/chat/completions", List.of("api.openai.com"));
    assertInvalid("https://api.openai.com/v1/chat/completions?override=true", List.of("api.openai.com"));
    assertInvalid("https://api.openai.com/v1/other", List.of("api.openai.com"));
  }

  @Test
  void requiresANonSecretProfileRevisionForExternalProfiles() {
    OperatorSupportProperties properties = new OperatorSupportProperties();
    properties.setEnabled(true);
    properties.setProvider(OperatorSupportProperties.Provider.OPENAI_COMPATIBLE);
    properties.setEndpoint(URI.create("https://api.openai.com/v1/chat/completions"));
    properties.setModel("SYNTHETIC-MODEL-001");
    properties.setSecretReference("env:SYN_OPERATOR_SUPPORT_KEY");
    properties.setAllowedHosts(List.of("api.openai.com"));

    assertThatThrownBy(properties::validate)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("The configured operator support provider profile is invalid.");
  }

  private static void assertInvalid(String endpoint, List<String> allowedHosts) {
    OperatorSupportProperties properties = new OperatorSupportProperties();
    properties.setEnabled(true);
    properties.setProvider(OperatorSupportProperties.Provider.OPENAI_COMPATIBLE);
    properties.setEndpoint(URI.create(endpoint));
    properties.setModel("SYNTHETIC-MODEL-001");
    properties.setProviderProfileRevision("SYNTHETIC-PROFILE-REVISION-1");
    properties.setSecretReference("env:SYN_OPERATOR_SUPPORT_KEY");
    properties.setAllowedHosts(allowedHosts);

    assertThatThrownBy(properties::validate)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("The configured operator support provider profile is invalid.");
  }
}
