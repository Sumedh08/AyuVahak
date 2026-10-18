package org.ayuvahak.analytics.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.federation.domain.DeploymentId;

/** Immutable administrator-approved direction, semantic compatibility and peer scope. */
public record FederatedAggregateMappingVersion(
    String mappingKey,
    int version,
    Direction direction,
    String indicatorKey,
    int indicatorVersion,
    String definitionFingerprint,
    ExternalReference sourceOrganization,
    PartnerKey partnerKey,
    DeploymentId peerDeployment,
    int partnerConfigurationVersion,
    ExternalReference peerOrganizationScope,
    String disclosurePolicyKey,
    String disclosureOutputKey,
    Instant effectiveFrom,
    Optional<Instant> effectiveUntil,
    String fingerprint) {

  public FederatedAggregateMappingVersion(
      String mappingKey,
      int version,
      Direction direction,
      String indicatorKey,
      int indicatorVersion,
      String definitionFingerprint,
      ExternalReference sourceOrganization,
      PartnerKey partnerKey,
      DeploymentId peerDeployment,
      int partnerConfigurationVersion,
      ExternalReference peerOrganizationScope,
      String disclosurePolicyKey,
      String disclosureOutputKey,
      Instant effectiveFrom,
      Optional<Instant> effectiveUntil) {
    this(
        mappingKey,
        version,
        direction,
        indicatorKey,
        indicatorVersion,
        definitionFingerprint,
        sourceOrganization,
        partnerKey,
        peerDeployment,
        partnerConfigurationVersion,
        peerOrganizationScope,
        disclosurePolicyKey,
        disclosureOutputKey,
        effectiveFrom,
        effectiveUntil,
        "pending");
  }

  public FederatedAggregateMappingVersion {
    if (mappingKey == null || !mappingKey.matches("[A-Z0-9][A-Z0-9._:-]{0,63}")) {
      throw new IllegalArgumentException("aggregate federation mapping key is invalid");
    }
    if (version < 1 || indicatorVersion < 1 || partnerConfigurationVersion < 1) {
      throw new IllegalArgumentException(
          "mapping, indicator and partner versions must be positive");
    }
    Objects.requireNonNull(direction, "mapping direction");
    if (indicatorKey == null || !indicatorKey.matches("[A-Z0-9][A-Z0-9._-]{0,63}")) {
      throw new IllegalArgumentException("indicator key is invalid");
    }
    requireFingerprint(definitionFingerprint, "indicator definition fingerprint");
    Objects.requireNonNull(sourceOrganization, "source organization");
    Objects.requireNonNull(partnerKey, "peer partner key");
    Objects.requireNonNull(peerDeployment, "peer deployment");
    Objects.requireNonNull(peerOrganizationScope, "peer organization scope");
    String expectedSourceNamespace =
        direction == Direction.OUTBOUND
            ? "urn:ayuvahak:directory"
            : deploymentNamespace(peerDeployment);
    String expectedScopeNamespace =
        direction == Direction.OUTBOUND
            ? deploymentNamespace(peerDeployment)
            : "urn:ayuvahak:directory";
    if (!expectedSourceNamespace.equals(sourceOrganization.issuer().value())
        || !"Organization".equals(sourceOrganization.resourceType())) {
      throw new IllegalArgumentException(
          "source organization namespace does not match mapping direction");
    }
    if (!expectedScopeNamespace.equals(peerOrganizationScope.issuer().value())
        || !"Organization".equals(peerOrganizationScope.resourceType())) {
      throw new IllegalArgumentException("peer scope must name an organization at that deployment");
    }
    if (disclosurePolicyKey == null || !disclosurePolicyKey.matches("[A-Z0-9][A-Z0-9._:-]{0,63}")) {
      throw new IllegalArgumentException("disclosure policy key is invalid");
    }
    if (disclosureOutputKey == null
        || !disclosureOutputKey.matches("[A-Z0-9][A-Z0-9._:-]{0,127}")) {
      throw new IllegalArgumentException("disclosure output key is invalid");
    }
    Objects.requireNonNull(effectiveFrom, "effective from");
    effectiveUntil = Objects.requireNonNull(effectiveUntil, "effective until");
    if (effectiveUntil.filter(until -> !effectiveFrom.isBefore(until)).isPresent()) {
      throw new IllegalArgumentException("mapping effective interval is empty");
    }
    String calculated =
        calculateFingerprint(
            mappingKey,
            version,
            direction,
            indicatorKey,
            indicatorVersion,
            definitionFingerprint,
            sourceOrganization,
            partnerKey,
            peerDeployment,
            partnerConfigurationVersion,
            peerOrganizationScope,
            disclosurePolicyKey,
            disclosureOutputKey,
            effectiveFrom,
            effectiveUntil);
    if (fingerprint == null || fingerprint.equals("pending")) {
      fingerprint = calculated;
    } else if (!fingerprint.equals(calculated)) {
      throw new IllegalArgumentException(
          "aggregate federation mapping fingerprint is inconsistent");
    }
  }

  public boolean effectiveAt(Instant at) {
    Objects.requireNonNull(at, "evaluation time");
    return !at.isBefore(effectiveFrom)
        && effectiveUntil.filter(until -> !at.isBefore(until)).isEmpty();
  }

  public ExternalReference administrativeOrganization() {
    return direction == Direction.OUTBOUND ? sourceOrganization : peerOrganizationScope;
  }

  public static String deploymentNamespace(DeploymentId deployment) {
    return "urn:ayuvahak:deployment:" + deployment.value() + ":directory";
  }

  private static String calculateFingerprint(
      String mappingKey,
      int version,
      Direction direction,
      String indicatorKey,
      int indicatorVersion,
      String definitionFingerprint,
      ExternalReference sourceOrganization,
      PartnerKey partnerKey,
      DeploymentId peerDeployment,
      int partnerConfigurationVersion,
      ExternalReference peerOrganizationScope,
      String disclosurePolicyKey,
      String disclosureOutputKey,
      Instant effectiveFrom,
      Optional<Instant> effectiveUntil) {
    String canonical =
        String.join(
            "\n",
            mappingKey,
            Integer.toString(version),
            direction.name(),
            indicatorKey,
            Integer.toString(indicatorVersion),
            definitionFingerprint,
            sourceOrganization.issuer().value(),
            sourceOrganization.resourceType(),
            sourceOrganization.value(),
            partnerKey.value(),
            peerDeployment.value(),
            Integer.toString(partnerConfigurationVersion),
            peerOrganizationScope.issuer().value(),
            peerOrganizationScope.resourceType(),
            peerOrganizationScope.value(),
            disclosurePolicyKey,
            disclosureOutputKey,
            effectiveFrom.toString(),
            effectiveUntil.map(Instant::toString).orElse(""));
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
    }
  }

  private static void requireFingerprint(String fingerprint, String label) {
    if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(label + " must be a lowercase SHA-256 digest");
    }
  }

  @Override
  public String toString() {
    return "FederatedAggregateMappingVersion[mappingKey="
        + mappingKey
        + ", version="
        + version
        + ", direction="
        + direction
        + ", indicatorKey="
        + indicatorKey
        + ", indicatorVersion="
        + indicatorVersion
        + ", peer="
        + peerDeployment
        + ", scopes=redacted, disclosureLineage=redacted]";
  }

  public enum Direction {
    INBOUND,
    OUTBOUND
  }
}
