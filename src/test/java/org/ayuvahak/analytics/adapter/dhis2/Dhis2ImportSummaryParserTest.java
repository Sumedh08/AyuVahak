package org.ayuvahak.analytics.adapter.dhis2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.ayuvahak.analytics.application.Dhis2ImportOutcome;
import org.ayuvahak.analytics.application.Dhis2ImportResponseException;
import org.ayuvahak.analytics.application.Dhis2ImportSummary;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class Dhis2ImportSummaryParserTest {

  private final Dhis2ImportSummaryParser parser = new Dhis2ImportSummaryParser(new ObjectMapper());

  @Test
  void acknowledgesOnlySingleValueImportedOrUpdatedSummaries() {
    assertThat(
            parse(
                    200,
                    "{\"importCount\":{\"imported\":1,\"updated\":0,\"ignored\":0,\"deleted\":0}}")
                .outcome())
        .isEqualTo(Dhis2ImportOutcome.IMPORTED_ACKNOWLEDGED);
    assertThat(
            parse(
                    200,
                    "{\"importCount\":{\"imported\":0,\"updated\":1,\"ignored\":0,\"deleted\":0}}")
                .outcome())
        .isEqualTo(Dhis2ImportOutcome.UPDATED_ACKNOWLEDGED);
  }

  @Test
  void ignoredSummaryAndAsyncTaskAreNotReportedAsSuccessfulImport() {
    assertThat(
            parse(
                    200,
                    "{\"importCount\":{\"imported\":0,\"updated\":0,\"ignored\":1,\"deleted\":0}}")
                .outcome())
        .isEqualTo(Dhis2ImportOutcome.IGNORED_AMBIGUOUS);
    assertThat(parse(202, "{\"id\":\"SYN-ASYNC-TASK-001\"}").outcome())
        .isEqualTo(Dhis2ImportOutcome.UNSUPPORTED_ASYNC);
  }

  @Test
  void rejectsMalformedMultiValueOrNonJsonSummaries() {
    assertThatThrownBy(() -> parse(200, "not-json"))
        .isInstanceOf(Dhis2ImportResponseException.class);
    assertThatThrownBy(
            () ->
                parse(
                    200,
                    "{\"importCount\":{\"imported\":1,\"updated\":1,\"ignored\":0,\"deleted\":0}}"))
        .isInstanceOf(Dhis2ImportResponseException.class);
    assertThatThrownBy(() -> parse(200, "{\"importCount\":{\"imported\":1}}", "text/plain"))
        .isInstanceOf(Dhis2ImportResponseException.class);
  }

  private Dhis2ImportSummary parse(int status, String body) {
    return parse(status, body, "application/json");
  }

  private Dhis2ImportSummary parse(int status, String body, String contentType) {
    return parser.parse(status, Optional.of(contentType), body.getBytes(StandardCharsets.UTF_8));
  }
}
