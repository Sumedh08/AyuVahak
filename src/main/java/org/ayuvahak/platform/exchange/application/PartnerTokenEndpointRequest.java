package org.ayuvahak.platform.exchange.application;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

/** OAuth client-credentials request; it cannot carry a destination or arbitrary form fields. */
public final class PartnerTokenEndpointRequest {

  private static final int MAX_FORM_BYTES = 16_384;
  private static final Pattern TOKEN_SAFE_VALUE = Pattern.compile("[A-Za-z0-9._~+-]{1,1024}");
  private static final String ASSERTION_TYPE =
      "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";

  private final String formBody;
  private final String basicAuthorization;

  private PartnerTokenEndpointRequest(String formBody, String basicAuthorization) {
    this.formBody = Objects.requireNonNull(formBody, "formBody");
    this.basicAuthorization = basicAuthorization;
    if (formBody.isBlank()
        || formBody.length() > MAX_FORM_BYTES
        || formBody.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("token request form is outside the allowed bounds");
    }
  }

  public static PartnerTokenEndpointRequest clientSecretBasic(String clientId, char[] secret) {
    Objects.requireNonNull(secret, "secret");
    if (!TOKEN_SAFE_VALUE.matcher(Objects.requireNonNull(clientId, "clientId")).matches()
        || secret.length == 0
        || secret.length > 4_096
        || containsControl(secret)) {
      throw new IllegalArgumentException("client credentials are outside the allowed bounds");
    }
    String encodedUser = formEncode(clientId);
    String encodedPassword = formEncode(new String(secret));
    String credentials = encodedUser + ":" + encodedPassword;
    String header =
        "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    return new PartnerTokenEndpointRequest("grant_type=client_credentials", header);
  }

  public static PartnerTokenEndpointRequest privateKeyJwt(String clientId, String assertion) {
    if (!TOKEN_SAFE_VALUE.matcher(Objects.requireNonNull(clientId, "clientId")).matches()
        || assertion == null
        || assertion.length() > 12_000
        || !assertion.matches("[A-Za-z0-9._-]+")) {
      throw new IllegalArgumentException("client assertion request is outside the allowed bounds");
    }
    String form =
        "grant_type=client_credentials&client_id="
            + formEncode(clientId)
            + "&client_assertion_type="
            + formEncode(ASSERTION_TYPE)
            + "&client_assertion="
            + formEncode(assertion);
    return new PartnerTokenEndpointRequest(form, null);
  }

  public String formBody() {
    return formBody;
  }

  public String basicAuthorization() {
    return basicAuthorization;
  }

  @Override
  public String toString() {
    return "PartnerTokenEndpointRequest[formBody=redacted, clientAuthentication=redacted]";
  }

  private static String formEncode(String value) {
    return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static boolean containsControl(char[] value) {
    for (char character : value) {
      if (Character.isISOControl(character)) {
        return true;
      }
    }
    return false;
  }
}
