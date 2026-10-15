package org.ayuvahak.directory.adapter.mcsd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.ayuvahak.directory.adapter.configuration.DirectoryMcsdSourceProperties.SourceBinding;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.junit.jupiter.api.Test;

class DirectoryMcsdPartnerBindingsTest {

  @Test
  void resolvesTheSingleConfiguredNamespaceForAnInboundPartner() {
    var bindings =
        new DirectoryMcsdPartnerBindings(
            List.of(new SourceBinding("urn:synthetic:directory", "synthetic-directory")));

    assertThat(bindings.requireNamespace(new PartnerKey("synthetic-directory")))
        .isEqualTo(new IssuerId("urn:synthetic:directory"));
  }

  @Test
  void rejectsAmbiguousNamespacesForOneInboundPartner() {
    assertThatThrownBy(
            () ->
                new DirectoryMcsdPartnerBindings(
                    List.of(
                        new SourceBinding("urn:synthetic:directory-a", "synthetic-directory"),
                        new SourceBinding("urn:synthetic:directory-b", "synthetic-directory"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("multiple source namespaces");
  }
}
