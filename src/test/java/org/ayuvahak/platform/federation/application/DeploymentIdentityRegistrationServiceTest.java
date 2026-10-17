package org.ayuvahak.platform.federation.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.ayuvahak.platform.federation.domain.DeploymentIdentity;
import org.junit.jupiter.api.Test;

class DeploymentIdentityRegistrationServiceTest {

  @Test
  void persistedIdentityDoesNotEnableFederationUntilThisRuntimeVerifiesItsBinding() {
    Instant registeredAt = Instant.parse("2026-10-17T10:00:00Z");
    DeploymentIdentity persisted =
        new DeploymentIdentity(new DeploymentId("SYN-DEPLOYMENT-A"), 1, registeredAt, registeredAt);
    DeploymentIdentityStore store = new PersistedIdentityStore(persisted);
    DeploymentIdentityRegistrationService unregisteredRuntime =
        new DeploymentIdentityRegistrationService(store);

    assertThat(unregisteredRuntime.current()).isEmpty();
    assertThat(
            unregisteredRuntime.register(
                persisted.deploymentId(), "SYN-INSTALLATION-BINDING-ONLY-LOCAL-0001", registeredAt))
        .isEqualTo(persisted);
    assertThat(unregisteredRuntime.current()).contains(persisted);
  }

  private static final class PersistedIdentityStore implements DeploymentIdentityStore {

    private final DeploymentIdentity persisted;

    private PersistedIdentityStore(DeploymentIdentity persisted) {
      this.persisted = persisted;
    }

    @Override
    public DeploymentIdentity registerOrVerify(
        DeploymentId deploymentId, String installationBindingDigest, Instant now) {
      return persisted;
    }

    @Override
    public Optional<DeploymentIdentity> findCurrent() {
      return Optional.of(persisted);
    }
  }
}
