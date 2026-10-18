package org.ayuvahak.analytics.adapter.federation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.ayuvahak.analytics.domain.AggregateCompleteness;
import org.ayuvahak.analytics.domain.FederatedAggregateEnvelope;
import org.ayuvahak.analytics.domain.FederatedAggregateSnapshot;
import org.ayuvahak.analytics.domain.ReportingPeriod;
import org.ayuvahak.analytics.domain.SourceMeasure;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class FederatedAggregateEnvelopeJsonMapperTest {

  private final FederatedAggregateEnvelopeJsonMapper mapper =
      new FederatedAggregateEnvelopeJsonMapper(new ObjectMapper());

  @Test
  void roundTripsOnlyBoundedAggregateAndNamespacedReferences() {
    FederatedAggregateEnvelope expected = envelope();

    FederatedAggregateEnvelope actual = mapper.decode(mapper.encode(expected));

    assertThat(actual).isEqualTo(expected);
    assertThat(new String(mapper.encode(actual), java.nio.charset.StandardCharsets.UTF_8))
        .doesNotContain("PatientReference", "clinical", "SYN-PATIENT");
  }

  @Test
  void rejectsUnknownFieldsRatherThanForwardingUnrecognizedContent() {
    byte[] valid = mapper.encode(envelope());
    String json = new String(valid, java.nio.charset.StandardCharsets.UTF_8);
    String withExtra =
        json.substring(0, json.length() - 1) + ",\"patientReference\":\"SYN-PATIENT-001\"}";

    assertThatThrownBy(
            () -> mapper.decode(withExtra.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        .isInstanceOf(FederatedAggregateEnvelopeException.class);
  }

  @Test
  void rejectsBodiesOverTheConfiguredLimit() {
    assertThatThrownBy(
            () -> mapper.decode(new byte[FederatedAggregateEnvelopeJsonMapper.MAX_BYTES + 1]))
        .isInstanceOf(FederatedAggregateEnvelopeException.class);
  }

  private static FederatedAggregateEnvelope envelope() {
    DeploymentId deployment = new DeploymentId("SYN-DEPLOYMENT-A");
    ExternalReference sourceOrganization =
        new ExternalReference(
            new IssuerId("urn:ayuvahak:deployment:" + deployment.value() + ":directory"),
            "Organization",
            "SYN-ORG-001");
    ReportingPeriod period =
        ReportingPeriod.resolve(
            LocalDate.parse("2026-01-01"), LocalDate.parse("2026-02-01"), ZoneId.of("UTC"));
    FederatedAggregateSnapshot snapshot =
        new FederatedAggregateSnapshot(
            deployment,
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            "SYN-INDICATOR-001",
            1,
            "a".repeat(64),
            "b".repeat(64),
            sourceOrganization,
            period,
            AggregateCompleteness.COMPLETE,
            7,
            SourceMeasure.Attribution.ORIGIN,
            Instant.parse("2026-02-01T02:00:00Z"));
    ExternalReference targetOrganization =
        new ExternalReference(
            new IssuerId("urn:ayuvahak:deployment:SYN-DEPLOYMENT-B:directory"),
            "Organization",
            "SYN-ORG-002");
    return new FederatedAggregateEnvelope(snapshot, targetOrganization);
  }
}
