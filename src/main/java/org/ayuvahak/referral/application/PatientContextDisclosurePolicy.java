package org.ayuvahak.referral.application;

/** Referral-owned port for deployment purpose, consent, and legal-basis policy evaluation. */
@FunctionalInterface
public interface PatientContextDisclosurePolicy {

  PatientContextDisclosureDecision evaluate(PatientContextDisclosureRequest request);
}
