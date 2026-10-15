package org.ayuvahak.referral.adapter.ips;

import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment mapping from a Referral-owned patient identifier namespace to a trusted partner. */
@ConfigurationProperties("ayuvahak.referral.ips")
public class ReferralIpsSourceProperties {

  private List<SourceBinding> sourceBindings = List.of();

  public List<SourceBinding> getSourceBindings() {
    return sourceBindings;
  }

  public void setSourceBindings(List<SourceBinding> sourceBindings) {
    this.sourceBindings = List.copyOf(Objects.requireNonNull(sourceBindings, "sourceBindings"));
  }

  public record SourceBinding(String patientIdentifierSystem, String partnerKey) {

    public SourceBinding {
      if (patientIdentifierSystem == null
          || patientIdentifierSystem.isBlank()
          || !patientIdentifierSystem.equals(patientIdentifierSystem.strip())
          || patientIdentifierSystem.length() > 256
          || patientIdentifierSystem.codePoints().anyMatch(Character::isISOControl)
          || patientIdentifierSystem.contains("|")
          || partnerKey == null
          || !partnerKey.matches("[A-Za-z][A-Za-z0-9_-]{1,62}")) {
        throw new IllegalArgumentException("Referral IPS source binding is invalid");
      }
    }
  }
}
