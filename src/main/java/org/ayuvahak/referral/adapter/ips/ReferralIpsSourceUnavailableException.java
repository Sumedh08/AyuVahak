package org.ayuvahak.referral.adapter.ips;

/** Safe failure for absent, inactive, or unreachable configured IPS sources. */
final class ReferralIpsSourceUnavailableException extends RuntimeException {

  ReferralIpsSourceUnavailableException() {
    super("The configured patient-context source is unavailable.");
  }
}
