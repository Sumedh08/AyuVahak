package org.ayuvahak.referral.adapter.ips;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.exchange.domain.PartnerKey;

/** Resolves only deployment-configured patient identifier namespaces, never issuer URLs. */
final class ReferralIpsPartnerBindings {

  private final Map<IssuerId, PartnerKey> partnersByIssuer;

  ReferralIpsPartnerBindings(List<ReferralIpsSourceProperties.SourceBinding> sourceBindings) {
    Objects.requireNonNull(sourceBindings, "source bindings");
    Map<IssuerId, PartnerKey> bindings = new HashMap<>();
    for (ReferralIpsSourceProperties.SourceBinding binding : sourceBindings) {
      Objects.requireNonNull(binding, "source binding");
      IssuerId issuer = new IssuerId(binding.patientIdentifierSystem());
      PartnerKey partner = new PartnerKey(binding.partnerKey());
      if (bindings.putIfAbsent(issuer, partner) != null) {
        throw new IllegalArgumentException(
            "Referral IPS identifier systems must have one configured partner binding");
      }
    }
    partnersByIssuer = Map.copyOf(bindings);
  }

  PartnerKey partnerFor(IssuerId issuer) {
    Objects.requireNonNull(issuer, "patient identifier system");
    PartnerKey partnerKey = partnersByIssuer.get(issuer);
    if (partnerKey == null) {
      throw new ReferralIpsSourceUnavailableException();
    }
    return partnerKey;
  }
}
