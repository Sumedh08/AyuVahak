package org.ayuvahak.referral.adapter.ips;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.ayuvahak.platform.core.CorrelationId;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.exchange.application.PartnerOutboundReadPort;
import org.ayuvahak.platform.exchange.application.PartnerOutboundReadRequest;
import org.ayuvahak.platform.exchange.application.PartnerOutboundReadResponse;
import org.ayuvahak.platform.exchange.application.PartnerTransportException;
import org.ayuvahak.platform.exchange.application.PartnerTransportFailure;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Direction;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.OwnerContext;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Protocol;
import org.ayuvahak.referral.application.PatientContextCategory;
import org.ayuvahak.referral.application.PatientContextSourceRequest;
import org.ayuvahak.referral.domain.PatientReference;
import org.junit.jupiter.api.Test;

class PartnerReferralIpsPayloadReadAdapterTest {

  private static final String IDENTIFIER_SYSTEM = "urn:synthetic:patient-issuer";
  private static final String PATIENT_ID = "SYN-PRIVATE-PATIENT-MARKER-8F93";
  private static final CorrelationId CORRELATION = new CorrelationId("synthetic-correlation-8f93");
  private static final Instant RECEIVED_AT = Instant.parse("2026-10-15T00:00:00Z");

  @Test
  void sendsOnlyTheBoundedIpsSummaryRequestToTheMappedTrustedPartner() {
    PartnerKey configuredPartner = new PartnerKey("synthetic-clinical-source");
    CapturingOutbound outbound = new CapturingOutbound();
    PartnerReferralIpsPayloadReadAdapter adapter =
        new PartnerReferralIpsPayloadReadAdapter(
            bindings(configuredPartner), outbound, Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));

    var payload = adapter.read(request(PATIENT_ID));

    PartnerOutboundReadRequest actual = outbound.request;
    assertThat(actual.partnerKey()).isEqualTo(configuredPartner);
    assertThat(actual.operation())
        .isEqualTo(
            new AllowedOperation(
                OwnerContext.REFERRAL,
                "ips.patient-context.read",
                Direction.OUTBOUND,
                Protocol.FHIR_R4_JSON,
                "IPS-2.0.1"));
    assertThat(actual.pathSegments()).containsExactly("Patient", "$summary");
    assertThat(actual.queryParameters())
        .containsEntry("identifier", List.of(IDENTIFIER_SYSTEM + "|" + PATIENT_ID))
        .containsEntry(
            "profile",
            List.of("http://hl7.org/fhir/uv/ips/StructureDefinition/Composition-uv-ips|2.0.1"));
    assertThat(actual.correlationId()).contains(CORRELATION);
    assertThat(payload.content()).containsExactly((byte) '{', (byte) '}');
    assertThat(payload.evidence().sourceKey()).isEqualTo("SYNTHETIC-CLINICAL-SOURCE");
    assertThat(payload.evidence().receivedAt()).isEqualTo(RECEIVED_AT);
    assertThat(payload.evidence().freshness())
        .isEqualTo(
            org.ayuvahak.referral.application.PatientContextSourceEvidence.Freshness.CURRENT);
    assertThat(actual.toString()).doesNotContain(PATIENT_ID, IDENTIFIER_SYSTEM);
    assertThat(payload.toString()).doesNotContain(PATIENT_ID, IDENTIFIER_SYSTEM);
  }

  @Test
  void missingNamespaceBindingFailsClosedWithoutCallingPartnerTransport() {
    CapturingOutbound outbound = new CapturingOutbound();
    PartnerReferralIpsPayloadReadAdapter adapter =
        new PartnerReferralIpsPayloadReadAdapter(
            new ReferralIpsPartnerBindings(List.of()),
            outbound,
            Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));

    assertThatThrownBy(() -> adapter.read(request(PATIENT_ID)))
        .isInstanceOf(ReferralIpsSourceUnavailableException.class)
        .hasMessage("The configured patient-context source is unavailable.");
    assertThat(outbound.request).isNull();
  }

  @Test
  void identifierWithSearchDelimiterIsRejectedRatherThanChangingPatientSelection() {
    CapturingOutbound outbound = new CapturingOutbound();
    PartnerReferralIpsPayloadReadAdapter adapter =
        new PartnerReferralIpsPayloadReadAdapter(
            bindings(new PartnerKey("synthetic-clinical-source")),
            outbound,
            Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));

    assertThatThrownBy(() -> adapter.read(request("SYN-PATIENT-1|SYN-PATIENT-2")))
        .isInstanceOf(ReferralIpsSourceUnavailableException.class);
    assertThat(outbound.request).isNull();
  }

  @Test
  void partnerFailureIsRedactedAndDoesNotExposePatientReference() {
    PartnerOutboundReadPort unavailable =
        new PartnerOutboundReadPort() {
          @Override
          public void requireActive(PartnerKey partnerKey, AllowedOperation operation) {}

          @Override
          public PartnerOutboundReadResponse read(PartnerOutboundReadRequest request) {
            throw new PartnerTransportException(PartnerTransportFailure.DESTINATION_REJECTED);
          }
        };
    PartnerReferralIpsPayloadReadAdapter adapter =
        new PartnerReferralIpsPayloadReadAdapter(
            bindings(new PartnerKey("synthetic-clinical-source")),
            unavailable,
            Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));

    assertThatThrownBy(() -> adapter.read(request(PATIENT_ID)))
        .isInstanceOf(ReferralIpsSourceUnavailableException.class)
        .hasMessage("The configured patient-context source is unavailable.")
        .hasMessageNotContaining(PATIENT_ID)
        .hasMessageNotContaining(IDENTIFIER_SYSTEM);
  }

  private static ReferralIpsPartnerBindings bindings(PartnerKey partner) {
    return new ReferralIpsPartnerBindings(
        List.of(new ReferralIpsSourceProperties.SourceBinding(IDENTIFIER_SYSTEM, partner.value())));
  }

  private static PatientContextSourceRequest request(String patientId) {
    return new PatientContextSourceRequest(
        new PatientReference(
            new ExternalReference(new IssuerId(IDENTIFIER_SYSTEM), "Patient", patientId),
            Optional.empty()),
        "CARE_COORDINATION",
        java.util.Set.of(PatientContextCategory.PROBLEMS),
        CORRELATION);
  }

  private static final class CapturingOutbound implements PartnerOutboundReadPort {

    private PartnerOutboundReadRequest request;

    @Override
    public void requireActive(PartnerKey partnerKey, AllowedOperation operation) {}

    @Override
    public PartnerOutboundReadResponse read(PartnerOutboundReadRequest request) {
      this.request = request;
      return new PartnerOutboundReadResponse(
          200, Optional.of("application/fhir+json"), new byte[] {'{', '}'}, 1);
    }
  }
}
