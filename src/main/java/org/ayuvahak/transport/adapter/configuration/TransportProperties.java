package org.ayuvahak.transport.adapter.configuration;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Identity used only for the explicit Transfer-to-Transport service boundary. */
@ConfigurationProperties("ayuvahak.transport")
public class TransportProperties {

  private String workerClientId = "ayuvahak-transport-worker";
  private String providerPartnerKey = "";

  public String getWorkerClientId() {
    return workerClientId;
  }

  public void setWorkerClientId(String workerClientId) {
    this.workerClientId = workerClientId;
  }

  /** Optional single deployment-selected provider; trust and endpoint remain in Exchange. */
  public String getProviderPartnerKey() {
    return providerPartnerKey;
  }

  public void setProviderPartnerKey(String providerPartnerKey) {
    this.providerPartnerKey = providerPartnerKey == null ? "" : providerPartnerKey.strip();
  }

  public boolean hasProviderPartner() {
    return !providerPartnerKey.isEmpty();
  }

  @PostConstruct
  void validate() {
    if (workerClientId == null || !workerClientId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
      throw new IllegalArgumentException("Transport worker client id is invalid");
    }
    if (!providerPartnerKey.isEmpty() && !providerPartnerKey.matches("[A-Z0-9][A-Z0-9._-]{0,63}")) {
      throw new IllegalArgumentException("Transport provider partner key is invalid");
    }
  }
}
