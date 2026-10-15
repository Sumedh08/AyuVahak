package org.ayuvahak.referral.adapter.ips;

import java.util.Objects;
import org.ayuvahak.referral.application.PatientContextSourceDocument;
import org.ayuvahak.referral.application.PatientContextSourceRequest;
import org.ayuvahak.referral.application.ReferralIpsPayload;
import org.ayuvahak.referral.application.ReferralIpsPayloadReadPort;
import org.ayuvahak.referral.application.ReferralPatientContextSourcePort;

/** Referral-owned adapter from a transient IPS payload read to the allowlisted projection. */
final class ReferralIpsPatientContextSourceAdapter implements ReferralPatientContextSourcePort {

  private final ReferralIpsPatientContextMapper mapper;
  private final ReferralIpsPayloadReadPort payloadReader;

  ReferralIpsPatientContextSourceAdapter(
      ReferralIpsPatientContextMapper mapper, ReferralIpsPayloadReadPort payloadReader) {
    this.mapper = Objects.requireNonNull(mapper, "IPS mapper");
    this.payloadReader = Objects.requireNonNull(payloadReader, "IPS payload reader");
  }

  @Override
  public PatientContextSourceDocument retrieve(PatientContextSourceRequest request) {
    Objects.requireNonNull(request, "Referral patient-context request");
    ReferralIpsPayload payload =
        Objects.requireNonNull(payloadReader.read(request), "IPS payload read result");
    return mapper.map(
        payload.content(),
        payload.contentType(),
        request.patientReference(),
        request.permittedCategories(),
        payload.evidence());
  }
}
