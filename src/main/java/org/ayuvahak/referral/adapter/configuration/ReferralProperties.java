package org.ayuvahak.referral.adapter.configuration;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Referral-owned deployment settings for consuming Transfer outcome events. */
@ConfigurationProperties(prefix = "ayuvahak.referral")
public class ReferralProperties {

  private String transferOutcomeConsumerClientId = "ayuvahak-transfer-outcome-consumer";

  public String getTransferOutcomeConsumerClientId() {
    return transferOutcomeConsumerClientId;
  }

  public void setTransferOutcomeConsumerClientId(String clientId) {
    transferOutcomeConsumerClientId = clientId;
  }

  @PostConstruct
  void validateTransferOutcomeConsumerConfiguration() {
    if (transferOutcomeConsumerClientId == null
        || !transferOutcomeConsumerClientId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
      throw new IllegalArgumentException("Referral Transfer outcome consumer client id is invalid");
    }
  }
}
