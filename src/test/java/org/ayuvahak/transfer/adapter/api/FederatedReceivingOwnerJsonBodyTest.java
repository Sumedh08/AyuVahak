package org.ayuvahak.transfer.adapter.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.ayuvahak.platform.api.PlatformProblemException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class FederatedReceivingOwnerJsonBodyTest {

  @Test
  void parsesSmallJsonObjectWithIdentityEncoding() {
    var parsed = FederatedReceivingOwnerJsonBody.read(request("{\"expectedVersion\":3}"));

    assertEquals(3, parsed.get("expectedVersion").intValue());
  }

  @Test
  void rejectsBodyOverTheOwnerCommandLimitBeforeParsing() {
    String oversized =
        "{\"value\":\"" + "x".repeat(FederatedReceivingOwnerJsonBody.MAX_BYTES) + "\"}";

    PlatformProblemException failure =
        assertThrows(
            PlatformProblemException.class,
            () -> FederatedReceivingOwnerJsonBody.read(request(oversized)));

    assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, failure.status());
  }

  @Test
  void rejectsExcessiveJsonNesting() {
    String nested = "{".repeat(40) + "0" + "}".repeat(40);

    assertThrows(
        PlatformProblemException.class,
        () -> FederatedReceivingOwnerJsonBody.read(request(nested)));
  }

  private static MockHttpServletRequest request(String content) {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setContentType("application/json; charset=UTF-8");
    request.setContent(bytes);
    return request;
  }
}
