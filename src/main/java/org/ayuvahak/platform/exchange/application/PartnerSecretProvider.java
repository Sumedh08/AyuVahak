package org.ayuvahak.platform.exchange.application;

import java.util.Optional;

/** Resolves an opaque configured reference without persisting the returned secret material. */
public interface PartnerSecretProvider {

  Optional<PartnerSecretMaterial> resolve(String secretReference);
}
