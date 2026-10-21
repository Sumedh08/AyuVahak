package org.ayuvahak.platform.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import org.ayuvahak.platform.operatorsupport.OperatorSupportEvaluationFixture;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Confirms synthetic sensitive/injection-like fields are rejected at the HTTP request boundary. */
class OperatorSupportInputBoundaryEvaluationTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void forbiddenSyntheticFieldsAreRejectedBeforeAnyProviderCanRun() throws IOException {
    JsonNode root = OperatorSupportEvaluationFixture.read();
    JsonNode forbiddenExamples = root.get("inputBoundary").get("forbiddenInputExamples");
    for (JsonNode forbidden : forbiddenExamples) {
      var request =
          JSON.createObjectNode()
              .put("questionKey", "DATABASE_RESTORE")
              .put("operatorText", forbidden.stringValue());
      assertThatThrownBy(() -> OperatorSupportController.questionKeyFrom(request))
          .as("forbidden input field: %s", forbidden.stringValue())
          .isInstanceOf(PlatformProblemException.class)
          .hasMessage("A supported operator support topic key is required.");
    }
  }
}
