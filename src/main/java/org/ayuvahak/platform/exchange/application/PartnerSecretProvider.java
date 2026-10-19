package org.ayuvahak.platform.exchange.application;

import java.util.Optional;

/**
 * Resolves opaque administrator-configured references without persisting returned secret material.
 *
 * <p>An empty result means the reference is unknown, absent, or revoked; implementations must not
 * substitute a previously resolved value. A temporary provider failure is reported as {@link
 * PartnerSecretProviderUnavailableException}. Implementations return fresh material rather than
 * retaining an in-process secret cache; callers own and must close the returned material.
 */
public interface PartnerSecretProvider {

  Optional<PartnerSecretMaterial> resolve(String secretReference);
}
