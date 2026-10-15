package org.ayuvahak.referral.application;

/** Referral-owned source boundary; implementations must return only bounded typed projections. */
@FunctionalInterface
public interface ReferralPatientContextSourcePort {

  PatientContextSourceDocument retrieve(PatientContextSourceRequest request);
}
