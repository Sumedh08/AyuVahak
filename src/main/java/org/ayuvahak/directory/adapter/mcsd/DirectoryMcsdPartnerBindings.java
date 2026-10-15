package org.ayuvahak.directory.adapter.mcsd;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.ayuvahak.directory.adapter.configuration.DirectoryMcsdSourceProperties.SourceBinding;
import org.ayuvahak.directory.application.DirectoryMcsdSourceUnavailableException;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.exchange.domain.PartnerKey;

/**
 * Resolves only deployment-configured source namespaces; it never interprets a namespace as URL.
 */
public final class DirectoryMcsdPartnerBindings {

  private final Map<IssuerId, PartnerKey> partnersByNamespace;
  private final Map<PartnerKey, IssuerId> namespacesByPartner;

  public DirectoryMcsdPartnerBindings(List<SourceBinding> sourceBindings) {
    Objects.requireNonNull(sourceBindings, "sourceBindings");
    Map<IssuerId, PartnerKey> bindings = new HashMap<>();
    Map<PartnerKey, IssuerId> reverseBindings = new HashMap<>();
    for (SourceBinding binding : sourceBindings) {
      Objects.requireNonNull(binding, "source binding");
      IssuerId namespace = new IssuerId(binding.sourceNamespace());
      PartnerKey partnerKey = new PartnerKey(binding.partnerKey());
      if (bindings.putIfAbsent(namespace, partnerKey) != null) {
        throw new IllegalArgumentException("mCSD source namespaces must have one partner binding");
      }
      if (reverseBindings.putIfAbsent(partnerKey, namespace) != null) {
        throw new IllegalArgumentException(
            "one mCSD partner cannot own multiple source namespaces in this deployment");
      }
    }
    partnersByNamespace = Map.copyOf(bindings);
    namespacesByPartner = Map.copyOf(reverseBindings);
  }

  public PartnerKey requirePartner(IssuerId sourceNamespace) {
    Objects.requireNonNull(sourceNamespace, "sourceNamespace");
    PartnerKey partnerKey = partnersByNamespace.get(sourceNamespace);
    if (partnerKey == null) {
      throw new DirectoryMcsdSourceUnavailableException(
          "No trusted partner is configured for the requested Directory source namespace.");
    }
    return partnerKey;
  }

  public IssuerId requireNamespace(PartnerKey partnerKey) {
    Objects.requireNonNull(partnerKey, "partnerKey");
    IssuerId namespace = namespacesByPartner.get(partnerKey);
    if (namespace == null) {
      throw new DirectoryMcsdSourceUnavailableException(
          "No Directory source namespace is configured for this trusted partner.");
    }
    return namespace;
  }
}
