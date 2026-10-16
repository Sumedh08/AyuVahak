package org.ayuvahak.referral.application;

/**
 * Deployment identities consumed by Referral policies, supplied by an outer configuration adapter.
 */
public interface ReferralSettings {

  String getTransferOutcomeConsumerClientId();
}
