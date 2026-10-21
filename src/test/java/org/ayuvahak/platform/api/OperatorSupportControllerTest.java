package org.ayuvahak.platform.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.ayuvahak.platform.operatorsupport.application.OperatorSupportQuestionKey;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OperatorSupportControllerTest {

  private final JsonMapper jsonMapper = JsonMapper.builder().build();

  @Test
  void acceptsOnlyAQuestionKeyFromTheFiniteTopicSet() {
    var request = jsonMapper.createObjectNode().put("questionKey", "DATABASE_RESTORE");

    assertThat(OperatorSupportController.questionKeyFrom(request))
        .isEqualTo(OperatorSupportQuestionKey.DATABASE_RESTORE);
  }

  @Test
  void rejectsFreeFormInputEvenWhenItIncludesASupportedTopicKey() {
    var request =
        jsonMapper
            .createObjectNode()
            .put("questionKey", "DATABASE_RESTORE")
            .put("question", "SYN-PATIENT-001 case details");

    assertThatThrownBy(() -> OperatorSupportController.questionKeyFrom(request))
        .isInstanceOf(PlatformProblemException.class)
        .hasMessage("A supported operator support topic key is required.");
  }

  @Test
  void rejectsUnknownAndNonObjectRequestShapes() {
    var unknownKey = jsonMapper.createObjectNode().put("questionKey", "ARBITRARY_CHAT");
    var arbitraryText = jsonMapper.createObjectNode().put("question", "SYN-CASE-001");
    var array = jsonMapper.createArrayNode().add("DATABASE_RESTORE");

    assertThatThrownBy(() -> OperatorSupportController.questionKeyFrom(unknownKey))
        .isInstanceOf(PlatformProblemException.class);
    assertThatThrownBy(() -> OperatorSupportController.questionKeyFrom(arbitraryText))
        .isInstanceOf(PlatformProblemException.class);
    assertThatThrownBy(() -> OperatorSupportController.questionKeyFrom(array))
        .isInstanceOf(PlatformProblemException.class);
  }
}
