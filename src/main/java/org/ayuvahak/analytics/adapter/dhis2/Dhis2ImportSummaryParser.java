package org.ayuvahak.analytics.adapter.dhis2;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.analytics.application.Dhis2ImportOutcome;
import org.ayuvahak.analytics.application.Dhis2ImportResponseException;
import org.ayuvahak.analytics.application.Dhis2ImportSummary;
import org.ayuvahak.analytics.application.Dhis2ImportSummaryResponseParser;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Parses only the documented synchronous, one-DataValue import summary. */
@Component
public final class Dhis2ImportSummaryParser implements Dhis2ImportSummaryResponseParser {

  private static final int MAX_SUMMARY_BYTES = 65_536;

  private final ObjectMapper mapper;

  public Dhis2ImportSummaryParser(ObjectMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "JSON mapper");
  }

  @Override
  public Dhis2ImportSummary parse(int status, Optional<String> contentType, byte[] body) {
    Objects.requireNonNull(contentType, "content type");
    Objects.requireNonNull(body, "response body");
    if (status == 202) {
      return new Dhis2ImportSummary(Dhis2ImportOutcome.UNSUPPORTED_ASYNC, 0, 0, 0, 0);
    }
    if (status != 200
        || body.length == 0
        || body.length > MAX_SUMMARY_BYTES
        || contentType.filter(Dhis2ImportSummaryParser::isJson).isEmpty()) {
      throw new Dhis2ImportResponseException();
    }

    try {
      JsonNode root = mapper.readTree(body);
      if (root == null || !root.isObject()) {
        throw new Dhis2ImportResponseException();
      }
      if (root.hasNonNull("id") || root.hasNonNull("jobId") || root.hasNonNull("taskId")) {
        return new Dhis2ImportSummary(Dhis2ImportOutcome.UNSUPPORTED_ASYNC, 0, 0, 0, 0);
      }
      JsonNode count = root.path("importCount");
      int imported = count(count, "imported");
      int updated = count(count, "updated");
      int ignored = count(count, "ignored");
      int deleted = count(count, "deleted");
      if (imported + updated + ignored != 1 || deleted != 0) {
        throw new Dhis2ImportResponseException();
      }
      Dhis2ImportOutcome outcome =
          imported == 1
              ? Dhis2ImportOutcome.IMPORTED_ACKNOWLEDGED
              : updated == 1
                  ? Dhis2ImportOutcome.UPDATED_ACKNOWLEDGED
                  : Dhis2ImportOutcome.IGNORED_AMBIGUOUS;
      return new Dhis2ImportSummary(outcome, imported, updated, ignored, deleted);
    } catch (JacksonException malformed) {
      throw new Dhis2ImportResponseException();
    }
  }

  private static int count(JsonNode importCount, String field) {
    JsonNode value = importCount.path(field);
    if (!importCount.isObject() || !value.isIntegralNumber()) {
      throw new Dhis2ImportResponseException();
    }
    long count = value.longValue();
    if (count < 0 || count > 1) {
      throw new Dhis2ImportResponseException();
    }
    return (int) count;
  }

  private static boolean isJson(String value) {
    String mediaType = value.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
    return mediaType.equals("application/json");
  }
}
