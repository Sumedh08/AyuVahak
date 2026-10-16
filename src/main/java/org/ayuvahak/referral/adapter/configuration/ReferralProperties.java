package org.ayuvahak.referral.adapter.configuration;

import jakarta.annotation.PostConstruct;
import org.ayuvahak.referral.application.ReferralSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Referral-owned deployment settings for consuming Transfer outcome events. */
@ConfigurationProperties(prefix = "ayuvahak.referral")
public class ReferralProperties implements ReferralSettings {

  private String transferOutcomeConsumerClientId = "ayuvahak-transfer-outcome-consumer";
  private String transferAmendmentCheckClientId = "ayuvahak-transfer-amendment-guard";

  public String getTransferOutcomeConsumerClientId() {
    return transferOutcomeConsumerClientId;
  }

  public void setTransferOutcomeConsumerClientId(String clientId) {
    transferOutcomeConsumerClientId = clientId;
  }

  public String getTransferAmendmentCheckClientId() {
    return transferAmendmentCheckClientId;
  }

  public void setTransferAmendmentCheckClientId(String clientId) {
    transferAmendmentCheckClientId = clientId;
  }

  @PostConstruct
  void validateTransferOutcomeConsumerConfiguration() {
    if (transferOutcomeConsumerClientId == null
        || !transferOutcomeConsumerClientId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
      throw new IllegalArgumentException("Referral Transfer outcome consumer client id is invalid");
    }
    if (transferAmendmentCheckClientId == null
        || !transferAmendmentCheckClientId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
      throw new IllegalArgumentException("Referral Transfer amendment check client id is invalid");
    }
  }
}
