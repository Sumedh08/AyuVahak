package org.ayuvahak.analytics.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.ayuvahak.platform.core.ExternalReference;

/** Deployment-configured disclosure policy and immutable output-to-lineage assignments. */
public record DisclosurePolicyVersion(
    String policyKey,
    int version,
    ExternalReference organization,
    long minimumCellCount,
    boolean externalBaselineComplete,
    UUID conservativeLineageId,
    Map<String, Set<UUID>> outputLineages,
    String fingerprint) {

  public DisclosurePolicyVersion(
      String policyKey,
      int version,
      ExternalReference organization,
      long minimumCellCount,
      boolean externalBaselineComplete,
      UUID conservativeLineageId,
      Map<String, Set<UUID>> outputLineages) {
    this(
        policyKey,
        version,
        organization,
        minimumCellCount,
        externalBaselineComplete,
        conservativeLineageId,
        broaden(outputLineages, conservativeLineageId),
        "pending");
  }

  public DisclosurePolicyVersion {
    if (policyKey == null || !policyKey.matches("[A-Z0-9][A-Z0-9._:-]{0,63}")) {
      throw new IllegalArgumentException("disclosure policy key is invalid");
    }
    Objects.requireNonNull(organization, "organization");
    Objects.requireNonNull(conservativeLineageId, "conservative lineage id");
    if (version < 1 || minimumCellCount < 1) {
      throw new IllegalArgumentException("disclosure policy version or cell threshold is invalid");
    }
    outputLineages = broaden(outputLineages, conservativeLineageId);
    if (outputLineages.isEmpty()) {
      throw new IllegalArgumentException("disclosure policy requires at least one output mapping");
    }
    String calculated =
        calculateFingerprint(
            policyKey,
            version,
            organization,
            minimumCellCount,
            externalBaselineComplete,
            conservativeLineageId,
            outputLineages);
    if (fingerprint == null || fingerprint.equals("pending")) {
      fingerprint = calculated;
    } else if (!fingerprint.equals(calculated)) {
      throw new IllegalArgumentException("disclosure policy fingerprint is inconsistent");
    }
  }

  public Set<UUID> lineagesFor(String outputKey) {
    return outputLineages.getOrDefault(outputKey, Set.of());
  }

  private static Map<String, Set<UUID>> broaden(
      Map<String, Set<UUID>> requested, UUID conservativeLineageId) {
    Objects.requireNonNull(requested, "output lineages");
    Objects.requireNonNull(conservativeLineageId, "conservative lineage id");
    TreeMap<String, Set<UUID>> broadened = new TreeMap<>();
    requested.forEach(
        (outputKey, lineages) -> {
          DisclosureOutput.requireKey(outputKey, "output key");
          Objects.requireNonNull(lineages, "lineages");
          if (lineages.isEmpty()
              || lineages.size() > 64
              || lineages.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("disclosure output lineage mapping is invalid");
          }
          TreeSet<UUID> stable = new TreeSet<>(lineages);
          stable.add(conservativeLineageId);
          broadened.put(outputKey, Set.copyOf(stable));
        });
    return Map.copyOf(broadened);
  }

  private static String calculateFingerprint(
      String policyKey,
      int version,
      ExternalReference organization,
      long minimumCellCount,
      boolean externalBaselineComplete,
      UUID conservativeLineageId,
      Map<String, Set<UUID>> outputLineages) {
    StringBuilder canonical =
        new StringBuilder()
            .append(policyKey)
            .append('\n')
            .append(version)
            .append('\n')
            .append(organization.issuer().value())
            .append('\n')
            .append(organization.resourceType())
            .append('\n')
            .append(organization.value())
            .append('\n')
            .append(minimumCellCount)
            .append('\n')
            .append(externalBaselineComplete)
            .append('\n')
            .append(conservativeLineageId);
    new TreeMap<>(outputLineages)
        .forEach(
            (outputKey, lineages) ->
                new TreeSet<>(lineages)
                    .forEach(
                        lineage ->
                            canonical.append('\n').append(outputKey).append('|').append(lineage)));
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
    }
  }

  @Override
  public String toString() {
    return "DisclosurePolicyVersion[policyKey="
        + policyKey
        + ", version="
        + version
        + ", minimumCellCount="
        + minimumCellCount
        + ", mappedOutputCount="
        + outputLineages.size()
        + ", fingerprint="
        + fingerprint
        + "]";
  }
}
