package org.ayuvahak.platform.operatorsupport.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.ayuvahak.platform.configuration.OperatorSupportProperties;
import org.ayuvahak.platform.exchange.application.PartnerSecretMaterial;
import org.ayuvahak.platform.exchange.application.PartnerSecretProvider;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OpenAiCompatibleOperatorSupportProviderTest {

  private static final String SECRET = "SYN-LOCAL-ONLY-OPERATOR-KEY";
  private static final ApprovedOperatorSupportSource SOURCE =
      ApprovedOperatorSupportSource.fromExcerpt(
          "DATABASE_RESTORE",
          "docs/operations/database-restore.md",
          "Restore verification",
          "Restore into an isolated database and verify state before serving traffic.");

  @Test
  void sendsOnlyFiniteTopicAndApprovedDocumentationToConfiguredEndpoint() throws Exception {
    AtomicReference<URI> endpoint = new AtomicReference<>();
    AtomicReference<byte[]> requestBody = new AtomicReference<>();
    AtomicReference<String> authorization = new AtomicReference<>();
    AtomicReference<char[]> credentialArray = new AtomicReference<>();
    OperatorSupportHttpTransport transport =
        (uri, body, token, timeout, maxBytes) -> {
          endpoint.set(uri);
          requestBody.set(body.clone());
          authorization.set(new String(token));
          credentialArray.set(token);
          return """
              {"choices":[{"message":{"content":"{\\"answer\\":\\"Restore to an isolated target and verify before serving traffic.\\",\\"sourceIds\\":[\\"DATABASE_RESTORE\\"]}"}}]}
              """.getBytes(StandardCharsets.UTF_8);
        };
    PartnerSecretProvider secrets =
        reference ->
            "env:SYN_OPERATOR_SUPPORT_KEY".equals(reference)
                ? Optional.of(new PartnerSecretMaterial(SECRET.toCharArray()))
                : Optional.empty();
    OperatorSupportProperties properties = externalProperties();
    OpenAiCompatibleOperatorSupportProvider provider =
        new OpenAiCompatibleOperatorSupportProvider(
            properties, transport, secrets, JsonMapper.builder().build());

    ProviderResponse response =
        provider.answer(OperatorSupportQuestionKey.DATABASE_RESTORE, List.of(SOURCE));

    String serializedRequest = new String(requestBody.get(), StandardCharsets.UTF_8);
    assertThat(endpoint.get())
        .isEqualTo(URI.create("https://api.openai.com/v1/chat/completions"));
    assertThat(serializedRequest)
        .contains("DATABASE_RESTORE", "Restore verification", SOURCE.excerpt())
        .doesNotContain("PatientReference", "SYN-PATIENT", "Authorization", SECRET);
    assertThat(serializedRequest)
        .contains("\"stream\":false", "\"store\":false")
        .doesNotContain("\"tools\"", "\"tool_choice\"");
    assertThat(authorization.get()).isEqualTo(SECRET);
    assertThat(credentialArray.get()).containsOnly('\0');
    assertThat(response.status()).isEqualTo(OperatorSupportResponse.Status.ADVISORY);
    assertThat(response.answer()).isEqualTo("Restore to an isolated target and verify before serving traffic.");
    assertThat(response.sourceIds()).containsExactly("DATABASE_RESTORE");
  }

  @Test
  void invalidProviderResponseAbstainsWithoutReturningProviderBody() {
    OperatorSupportHttpTransport transport =
        (uri, body, token, timeout, maxBytes) ->
            "{\"choices\":[{\"message\":{\"content\":\"not json SYN-SECRET-RESPONSE\"}}]}"
                .getBytes(StandardCharsets.UTF_8);
    OpenAiCompatibleOperatorSupportProvider provider =
        new OpenAiCompatibleOperatorSupportProvider(
            externalProperties(),
            transport,
            reference -> Optional.of(new PartnerSecretMaterial(SECRET.toCharArray())),
            JsonMapper.builder().build());

    ProviderResponse response =
        provider.answer(OperatorSupportQuestionKey.DATABASE_RESTORE, List.of(SOURCE));

    assertThat(response.status()).isEqualTo(OperatorSupportResponse.Status.ABSTAINED);
    assertThat(response.toString()).doesNotContain("SYN-SECRET-RESPONSE", SECRET);
  }

  @Test
  void rejectsAnUnconfiguredSecretReferenceWithoutCallingTransport() {
    AtomicReference<Boolean> called = new AtomicReference<>(false);
    OperatorSupportHttpTransport transport =
        (uri, body, token, timeout, maxBytes) -> {
          called.set(true);
          return new byte[0];
        };
    OpenAiCompatibleOperatorSupportProvider provider =
        new OpenAiCompatibleOperatorSupportProvider(
            externalProperties(), transport, reference -> Optional.empty(), JsonMapper.builder().build());

    ProviderResponse response =
        provider.answer(OperatorSupportQuestionKey.DATABASE_RESTORE, List.of(SOURCE));

    assertThat(called.get()).isFalse();
    assertThat(response.status()).isEqualTo(OperatorSupportResponse.Status.ABSTAINED);
  }

  @Test
  void rejectsAnOversizedCredentialBeforeCallingTransport() {
    AtomicReference<Boolean> called = new AtomicReference<>(false);
    OperatorSupportHttpTransport transport =
        (uri, body, token, timeout, maxBytes) -> {
          called.set(true);
          return new byte[0];
        };
    OpenAiCompatibleOperatorSupportProvider provider =
        new OpenAiCompatibleOperatorSupportProvider(
            externalProperties(),
            transport,
            reference -> Optional.of(new PartnerSecretMaterial("S".repeat(4_097).toCharArray())),
            JsonMapper.builder().build());

    ProviderResponse response =
        provider.answer(OperatorSupportQuestionKey.DATABASE_RESTORE, List.of(SOURCE));

    assertThat(called.get()).isFalse();
    assertThat(response.status()).isEqualTo(OperatorSupportResponse.Status.ABSTAINED);
    assertThat(response.reasonCode())
        .isEqualTo(OperatorSupportResponse.ReasonCode.PROVIDER_UNAVAILABLE);
  }

  private static OperatorSupportProperties externalProperties() {
    OperatorSupportProperties properties = new OperatorSupportProperties();
    properties.setEnabled(true);
    properties.setProvider(OperatorSupportProperties.Provider.OPENAI_COMPATIBLE);
    properties.setEndpoint(URI.create("https://api.openai.com/v1/chat/completions"));
    properties.setModel("SYNTHETIC-MODEL-001");
    properties.setSecretReference("env:SYN_OPERATOR_SUPPORT_KEY");
    properties.setAllowedHosts(List.of("api.openai.com"));
    properties.setTimeout(Duration.ofSeconds(2));
    properties.setMaxResponseBytes(16_384);
    properties.validate();
    return properties;
  }
}
