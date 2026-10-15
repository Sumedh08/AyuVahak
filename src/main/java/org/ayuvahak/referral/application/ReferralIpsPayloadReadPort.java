package org.ayuvahak.referral.application;

/**
 * Referral-owned transient source port for an IPS representation. A transport adapter may provide
 * it later; callers provide no URL, credentials, or network destination.
 */
@FunctionalInterface
public interface ReferralIpsPayloadReadPort {

  ReferralIpsPayload read(PatientContextSourceRequest request);
}
