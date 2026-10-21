package org.ayuvahak.platform.operatorsupport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Shared loader for the versioned, synthetic Operator Support evaluation corpus. */
public final class OperatorSupportEvaluationFixture {

  private static final String RESOURCE = "/evaluation/phase14/operator-support-evaluation-v1.json";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private OperatorSupportEvaluationFixture() {}

  public static JsonNode read() throws IOException {
    try (InputStream stream =
        OperatorSupportEvaluationFixture.class.getResourceAsStream(RESOURCE)) {
      if (stream == null) {
        throw new IllegalStateException("Missing synthetic evaluation corpus " + RESOURCE);
      }
      return JSON.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
    }
  }
}
