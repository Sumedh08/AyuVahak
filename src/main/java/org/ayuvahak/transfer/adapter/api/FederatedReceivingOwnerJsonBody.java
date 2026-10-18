package org.ayuvahak.transfer.adapter.api;

import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.ayuvahak.platform.api.PlatformErrorCode;
import org.ayuvahak.platform.api.PlatformProblemException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Bounded JSON parser for small, owner-facing federated receiving commands. */
final class FederatedReceivingOwnerJsonBody {

  static final int MAX_BYTES = 16_384;
  private static final JsonFactory JSON_FACTORY =
      JsonFactory.builder()
          .streamReadConstraints(
              StreamReadConstraints.builder()
                  .maxNestingDepth(32)
                  .maxStringLength(8_192)
                  .maxNumberLength(64)
                  .build())
          .build();
  private static final ObjectMapper MAPPER = new ObjectMapper(JSON_FACTORY);

  private FederatedReceivingOwnerJsonBody() {}

  static JsonNode read(HttpServletRequest request) {
    byte[] bytes = readBounded(request);
    try {
      String decoded =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      try (var parser = JSON_FACTORY.createParser(decoded)) {
        JsonNode value = MAPPER.readTree(parser);
        if (value == null || !value.isObject() || parser.nextToken() != null) {
          throw malformed();
        }
        return value;
      }
    } catch (JacksonException | IOException invalid) {
      throw malformed();
    }
  }

  private static byte[] readBounded(HttpServletRequest request) {
    if (request.getContentLengthLong() > MAX_BYTES) {
      throw tooLarge();
    }
    String encoding = request.getHeader("Content-Encoding");
    if (encoding != null && !encoding.isBlank() && !"identity".equalsIgnoreCase(encoding)) {
      throw unsupportedMediaType();
    }
    try {
      String contentType = request.getContentType();
      MediaType mediaType = contentType == null ? null : MediaType.parseMediaType(contentType);
      if (mediaType == null
          || !mediaType.isCompatibleWith(MediaType.APPLICATION_JSON)
          || (mediaType.getCharset() != null
              && !StandardCharsets.UTF_8.equals(mediaType.getCharset()))) {
        throw unsupportedMediaType();
      }
    } catch (IllegalArgumentException invalid) {
      throw unsupportedMediaType();
    }
    try (var input = request.getInputStream();
        var output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[4_096];
      int total = 0;
      int read;
      while ((read = input.read(buffer)) != -1) {
        total = Math.addExact(total, read);
        if (total > MAX_BYTES) {
          throw tooLarge();
        }
        output.write(buffer, 0, read);
      }
      if (total == 0) {
        throw malformed();
      }
      return output.toByteArray();
    } catch (IOException | ArithmeticException invalid) {
      throw malformed();
    }
  }

  private static PlatformProblemException malformed() {
    return new PlatformProblemException(
        HttpStatus.BAD_REQUEST,
        PlatformErrorCode.MALFORMED_REQUEST,
        "The federated receiving command is malformed.");
  }

  private static PlatformProblemException tooLarge() {
    return new PlatformProblemException(
        HttpStatus.PAYLOAD_TOO_LARGE,
        PlatformErrorCode.VALIDATION_FAILED,
        "The federated receiving command exceeds its payload limit.");
  }

  private static PlatformProblemException unsupportedMediaType() {
    return new PlatformProblemException(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        PlatformErrorCode.UNSUPPORTED_MEDIA_TYPE,
        "The federated receiving command accepts UTF-8 JSON only.");
  }
}
