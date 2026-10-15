package org.ayuvahak.referral.adapter.ips;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.exchange.application.PartnerOutboundReadPort;
import org.ayuvahak.platform.exchange.application.PartnerOutboundReadRequest;
import org.ayuvahak.platform.exchange.application.PartnerOutboundReadResponse;
import org.ayuvahak.platform.exchange.application.PartnerTransportException;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.AllowedOperation;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Direction;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.OwnerContext;
import org.ayuvahak.platform.exchange.domain.PartnerTrustConfiguration.Protocol;
import org.ayuvahak.referral.application.PatientContextSourceEvidence;
import org.ayuvahak.referral.application.PatientContextSourceRequest;
import org.ayuvahak.referral.application.ReferralIpsPayload;
import org.ayuvahak.referral.application.ReferralIpsPayloadReadPort;

/** Referral-owned IPS retrieval using only the registered summary operation and trusted partner. */
final class PartnerReferralIpsPayloadReadAdapter implements ReferralIpsPayloadReadPort {

  private static final String OPERATION_CODE = "ips.patient-context.read";
  private static final String IPS_PROFILE = "IPS-2.0.1";
  private static final String COMPOSITION_PROFILE =
      "http://hl7.org/fhir/uv/ips/StructureDefinition/Composition-uv-ips|2.0.1";
  private static final String PROJECTION_VERSION = "IPS_R4_2_0_1";
  private static final AllowedOperation OPERATION =
      new AllowedOperation(
          OwnerContext.REFERRAL,
          OPERATION_CODE,
          Direction.OUTBOUND,
          Protocol.FHIR_R4_JSON,
          IPS_PROFILE);

  private final ReferralIpsPartnerBindings partnerBindings;
  private final PartnerOutboundReadPort outbound;
  private final Clock clock;

  PartnerReferralIpsPayloadReadAdapter(
      ReferralIpsPartnerBindings partnerBindings, PartnerOutboundReadPort outbound, Clock clock) {
    this.partnerBindings = Objects.requireNonNull(partnerBindings, "partner bindings");
    this.outbound = Objects.requireNonNull(outbound, "partner outbound read port");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public ReferralIpsPayload read(PatientContextSourceRequest request) {
    Objects.requireNonNull(request, "patient-context request");
    ExternalReference patient = request.patientReference().patient();
    if (patient.value().contains("|")) {
      throw new ReferralIpsSourceUnavailableException();
    }

    var partnerKey = partnerBindings.partnerFor(patient.issuer());
    PartnerOutboundReadRequest outboundRequest =
        new PartnerOutboundReadRequest(
            partnerKey,
            OPERATION,
            List.of("Patient", "$summary"),
            Map.of(
                "identifier",
                List.of(patient.issuer().value() + "|" + patient.value()),
                "profile",
                List.of(COMPOSITION_PROFILE)),
            request.correlationId());
    try {
      PartnerOutboundReadResponse response = outbound.read(outboundRequest);
      PatientContextSourceEvidence evidence =
          new PatientContextSourceEvidence(
              partnerKey.value(),
              Optional.empty(),
              Optional.empty(),
              clock.instant(),
              PROJECTION_VERSION,
              PatientContextSourceEvidence.Freshness.CURRENT);
      return new ReferralIpsPayload(
          response.body(), response.contentType().orElse("application/octet-stream"), evidence);
    } catch (PartnerTransportException unavailable) {
      throw new ReferralIpsSourceUnavailableException();
    }
  }
}
