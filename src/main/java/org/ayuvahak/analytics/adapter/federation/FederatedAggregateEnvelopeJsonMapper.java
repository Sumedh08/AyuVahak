package org.ayuvahak.analytics.adapter.federation;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.ayuvahak.analytics.application.FederatedAggregateEnvelopeCodec;
import org.ayuvahak.analytics.domain.AggregateCompleteness;
import org.ayuvahak.analytics.domain.FederatedAggregateAcknowledgement;
import org.ayuvahak.analytics.domain.FederatedAggregateEnvelope;
import org.ayuvahak.analytics.domain.FederatedAggregateReceipt;
import org.ayuvahak.analytics.domain.FederatedAggregateSnapshot;
import org.ayuvahak.analytics.domain.ReportingPeriod;
import org.ayuvahak.analytics.domain.SourceMeasure;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Strict, bounded JSON codec for the one named Analytics peer aggregate contract. */
@Component
public final class FederatedAggregateEnvelopeJsonMapper implements FederatedAggregateEnvelopeCodec {

  public static final String PROFILE = "AyuVahak-Federation-v1";
  public static final int MAX_BYTES = 65_536;

  private static final Set<String> FIELDS =
      Set.of(
          "protocolProfile",
          "sourceDeploymentId",
          "sourceSnapshotId",
          "revisionSeriesId",
          "revisionNumber",
          "indicatorKey",
          "indicatorVersion",
          "definitionFingerprint",
          "sourceFingerprint",
          "sourceOrganization",
          "targetOrganization",
          "period",
          "completeness",
          "value",
          "attribution",
          "computedAt",
          "contentFingerprint");
  private static final Set<String> REFERENCE_FIELDS = Set.of("issuer", "resourceType", "value");
  private static final Set<String> PERIOD_FIELDS =
      Set.of(
          "startInclusiveLocal",
          "endExclusiveLocal",
          "timeZoneId",
          "startInclusiveUtc",
          "endExclusiveUtc",
          "timeZoneRulesVersion");
  private static final Set<String> ACK_FIELDS =
      Set.of(
          "protocolProfile",
          "receivingDeploymentId",
          "operationId",
          "sourceSnapshotId",
          "contentFingerprint",
          "receiptId",
          "state");

  private final ObjectMapper mapper;

  public FederatedAggregateEnvelopeJsonMapper(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public byte[] encode(FederatedAggregateEnvelope envelope) {
    FederatedAggregateSnapshot snapshot = envelope.snapshot();
    ObjectNode json = mapper.createObjectNode();
    json.put("protocolProfile", PROFILE);
    json.put("sourceDeploymentId", snapshot.sourceDeployment().value());
    json.put("sourceSnapshotId", snapshot.sourceSnapshotId().toString());
    json.put("revisionSeriesId", snapshot.revisionSeriesId().toString());
    json.put("revisionNumber", snapshot.revisionNumber());
    json.put("indicatorKey", snapshot.indicatorKey());
    json.put("indicatorVersion", snapshot.indicatorVersion());
    json.put("definitionFingerprint", snapshot.definitionFingerprint());
    json.put("sourceFingerprint", snapshot.sourceFingerprint());
    json.set("sourceOrganization", referenceJson(snapshot.sourceOrganization()));
    json.set("targetOrganization", referenceJson(envelope.targetOrganization()));
    ObjectNode period = mapper.createObjectNode();
    period.put("startInclusiveLocal", snapshot.period().startInclusiveLocal().toString());
    period.put("endExclusiveLocal", snapshot.period().endExclusiveLocal().toString());
    period.put("timeZoneId", snapshot.period().timeZoneId());
    period.put("startInclusiveUtc", snapshot.period().startInclusiveUtc().toString());
    period.put("endExclusiveUtc", snapshot.period().endExclusiveUtc().toString());
    period.put("timeZoneRulesVersion", snapshot.period().timeZoneRulesVersion());
    json.set("period", period);
    json.put("completeness", snapshot.completeness().name());
    json.put("value", snapshot.value());
    json.put("attribution", snapshot.attribution().name());
    json.put("computedAt", snapshot.computedAt().toString());
    json.put("contentFingerprint", snapshot.contentFingerprint());
    try {
      byte[] body = mapper.writeValueAsBytes(json);
      if (body.length == 0 || body.length > MAX_BYTES) {
        throw malformed();
      }
      return body;
    } catch (JacksonException failure) {
      throw malformed();
    }
  }

  public FederatedAggregateEnvelope decode(byte[] body) {
    if (body == null || body.length == 0 || body.length > MAX_BYTES) {
      throw malformed();
    }
    try {
      String decoded =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(body))
              .toString();
      JsonNode json = mapper.readTree(decoded);
      requireFields(json, FIELDS);
      if (!PROFILE.equals(text(json, "protocolProfile"))) {
        throw malformed();
      }
      DeploymentId source = new DeploymentId(text(json, "sourceDeploymentId"));
      JsonNode periodJson = json.get("period");
      requireFields(periodJson, PERIOD_FIELDS);
      ReportingPeriod period =
          ReportingPeriod.restore(
              LocalDate.parse(text(periodJson, "startInclusiveLocal")),
              LocalDate.parse(text(periodJson, "endExclusiveLocal")),
              text(periodJson, "timeZoneId"),
              Instant.parse(text(periodJson, "startInclusiveUtc")),
              Instant.parse(text(periodJson, "endExclusiveUtc")),
              text(periodJson, "timeZoneRulesVersion"));
      FederatedAggregateSnapshot snapshot =
          new FederatedAggregateSnapshot(
              source,
              UUID.fromString(text(json, "sourceSnapshotId")),
              UUID.fromString(text(json, "revisionSeriesId")),
              positiveInt(json, "revisionNumber"),
              text(json, "indicatorKey"),
              positiveInt(json, "indicatorVersion"),
              text(json, "definitionFingerprint"),
              text(json, "sourceFingerprint"),
              reference(json.get("sourceOrganization")),
              period,
              AggregateCompleteness.valueOf(text(json, "completeness")),
              nonnegativeLong(json, "value"),
              SourceMeasure.Attribution.valueOf(text(json, "attribution")),
              Instant.parse(text(json, "computedAt")),
              text(json, "contentFingerprint"));
      return new FederatedAggregateEnvelope(snapshot, reference(json.get("targetOrganization")));
    } catch (CharacterCodingException | JacksonException | IllegalArgumentException invalid) {
      throw malformed();
    }
  }

  public byte[] encodeAcknowledgement(
      FederatedAggregateReceipt receipt, DeploymentId receivingDeployment) {
    ObjectNode json = mapper.createObjectNode();
    json.put("protocolProfile", PROFILE);
    json.put(
        "receivingDeploymentId",
        "urn:ayuvahak:deployment:" + receivingDeployment.value() + ":directory");
    json.put("operationId", receipt.operationId().toString());
    json.put("sourceSnapshotId", receipt.snapshot().sourceSnapshotId().toString());
    json.put("contentFingerprint", receipt.snapshot().contentFingerprint());
    json.put("receiptId", receipt.receiptId().toString());
    json.put("state", receipt.state().name());
    try {
      return mapper.writeValueAsBytes(json);
    } catch (JacksonException failure) {
      throw malformed();
    }
  }

  public FederatedAggregateAcknowledgement decodeAcknowledgement(byte[] body) {
    if (body == null || body.length == 0 || body.length > 8_192) {
      throw malformed();
    }
    try {
      String decoded =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(body))
              .toString();
      JsonNode json = mapper.readTree(decoded);
      requireFields(json, ACK_FIELDS);
      if (!PROFILE.equals(text(json, "protocolProfile"))) {
        throw malformed();
      }
      String receivingId = text(json, "receivingDeploymentId");
      String prefix = "urn:ayuvahak:deployment:";
      String suffix = ":directory";
      if (!receivingId.startsWith(prefix) || !receivingId.endsWith(suffix)) {
        throw malformed();
      }
      DeploymentId receivingDeployment =
          new DeploymentId(
              receivingId.substring(prefix.length(), receivingId.length() - suffix.length()));
      return new FederatedAggregateAcknowledgement(
          receivingDeployment,
          UUID.fromString(text(json, "operationId")),
          UUID.fromString(text(json, "sourceSnapshotId")),
          text(json, "contentFingerprint"),
          UUID.fromString(text(json, "receiptId")),
          FederatedAggregateReceipt.State.valueOf(text(json, "state")));
    } catch (CharacterCodingException | JacksonException | IllegalArgumentException invalid) {
      throw malformed();
    }
  }

  private ObjectNode referenceJson(ExternalReference reference) {
    ObjectNode json = mapper.createObjectNode();
    json.put("issuer", reference.issuer().value());
    json.put("resourceType", reference.resourceType());
    json.put("value", reference.value());
    return json;
  }

  private static ExternalReference reference(JsonNode json) {
    requireFields(json, REFERENCE_FIELDS);
    return new ExternalReference(
        new IssuerId(text(json, "issuer")), text(json, "resourceType"), text(json, "value"));
  }

  private static void requireFields(JsonNode json, Set<String> fields) {
    if (json == null || !json.isObject()) {
      throw malformed();
    }
    Set<String> present = new HashSet<>();
    json.properties()
        .forEach(
            field -> {
              if (!fields.contains(field.getKey())) {
                throw malformed();
              }
              present.add(field.getKey());
            });
    if (!present.equals(fields)) {
      throw malformed();
    }
  }

  private static String text(JsonNode json, String field) {
    JsonNode value = json.get(field);
    if (value == null || !value.isString() || value.asString().isBlank()) {
      throw malformed();
    }
    return value.asString();
  }

  private static int positiveInt(JsonNode json, String field) {
    long value = nonnegativeLong(json, field);
    if (value < 1 || value > Integer.MAX_VALUE) {
      throw malformed();
    }
    return (int) value;
  }

  private static long nonnegativeLong(JsonNode json, String field) {
    JsonNode value = json.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw malformed();
    }
    long parsed = value.longValue();
    if (parsed < 0) {
      throw malformed();
    }
    return parsed;
  }

  private static FederatedAggregateEnvelopeException malformed() {
    return new FederatedAggregateEnvelopeException();
  }
}
