package org.ayuvahak.platform.federation.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.ayuvahak.platform.federation.domain.DeploymentIdentity;
import org.springframework.stereotype.Service;

/** Registers one stable local identity without persisting or exposing its binding secret. */
@Service
public final class DeploymentIdentityRegistrationService {

  private final DeploymentIdentityStore store;
  private volatile DeploymentIdentity registeredIdentity;

  public DeploymentIdentityRegistrationService(DeploymentIdentityStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public DeploymentIdentity register(
      DeploymentId deploymentId, String installationBinding, Instant now) {
    Objects.requireNonNull(deploymentId, "deploymentId");
    Objects.requireNonNull(now, "now");
    if (installationBinding == null
        || installationBinding.length() < 32
        || installationBinding.length() > 512
        || !installationBinding.equals(installationBinding.strip())
        || installationBinding.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(
          "installation binding must be a bounded high-entropy local secret");
    }
    DeploymentIdentity registered =
        store.registerOrVerify(deploymentId, digest(installationBinding), now);
    registeredIdentity = registered;
    return registered;
  }

  public Optional<DeploymentIdentity> current() {
    DeploymentIdentity verifiedForThisRuntime = registeredIdentity;
    if (verifiedForThisRuntime == null) {
      return Optional.empty();
    }
    return store.findCurrent().filter(verifiedForThisRuntime::equals);
  }

  private static String digest(String binding) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(binding.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }
}
