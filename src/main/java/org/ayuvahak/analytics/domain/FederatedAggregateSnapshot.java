package org.ayuvahak.analytics.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.federation.domain.DeploymentId;

/** A named, complete aggregate snapshot with source provenance and no owner rows. */
public record FederatedAggregateSnapshot(
    DeploymentId sourceDeployment,
    UUID sourceSnapshotId,
    UUID revisionSeriesId,
    int revisionNumber,
    String indicatorKey,
    int indicatorVersion,
    String definitionFingerprint,
    String sourceFingerprint,
    ExternalReference sourceOrganization,
    ReportingPeriod period,
    AggregateCompleteness completeness,
    long value,
    SourceMeasure.Attribution attribution,
    Instant computedAt,
    String contentFingerprint) {

  public FederatedAggregateSnapshot(
      DeploymentId sourceDeployment,
      UUID sourceSnapshotId,
      UUID revisionSeriesId,
      int revisionNumber,
      String indicatorKey,
      int indicatorVersion,
      String definitionFingerprint,
      String sourceFingerprint,
      ExternalReference sourceOrganization,
      ReportingPeriod period,
      AggregateCompleteness completeness,
      long value,
      SourceMeasure.Attribution attribution,
      Instant computedAt) {
    this(
        sourceDeployment,
        sourceSnapshotId,
        revisionSeriesId,
        revisionNumber,
        indicatorKey,
        indicatorVersion,
        definitionFingerprint,
        sourceFingerprint,
        sourceOrganization,
        period,
        completeness,
        value,
        attribution,
        computedAt,
        "pending");
  }

  public FederatedAggregateSnapshot {
    Objects.requireNonNull(sourceDeployment, "source deployment");
    Objects.requireNonNull(sourceSnapshotId, "source snapshot id");
    Objects.requireNonNull(revisionSeriesId, "revision series id");
    if (revisionNumber < 1) {
      throw new IllegalArgumentException("aggregate revision number must be positive");
    }
    if (indicatorKey == null || !indicatorKey.matches("[A-Z0-9][A-Z0-9._-]{0,63}")) {
      throw new IllegalArgumentException("indicator key is invalid");
    }
    if (indicatorVersion < 1) {
      throw new IllegalArgumentException("indicator version must be positive");
    }
    requireFingerprint(definitionFingerprint, "definition fingerprint");
    requireFingerprint(sourceFingerprint, "source fingerprint");
    Objects.requireNonNull(sourceOrganization, "source organization");
    String expectedSourceNamespace =
        "urn:ayuvahak:deployment:" + sourceDeployment.value() + ":directory";
    if (!expectedSourceNamespace.equals(sourceOrganization.issuer().value())
        || !"Organization".equals(sourceOrganization.resourceType())) {
      throw new IllegalArgumentException(
          "aggregate source organization must be namespaced to its Directory deployment");
    }
    Objects.requireNonNull(period, "reporting period");
    if (completeness != AggregateCompleteness.COMPLETE || value < 0) {
      throw new IllegalArgumentException("only complete nonnegative aggregates can be federated");
    }
    Objects.requireNonNull(attribution, "source attribution");
    Objects.requireNonNull(computedAt, "computed at");
    String calculated =
        calculateContentFingerprint(
            sourceDeployment,
            sourceSnapshotId,
            revisionSeriesId,
            revisionNumber,
            indicatorKey,
            indicatorVersion,
            definitionFingerprint,
            sourceFingerprint,
            sourceOrganization,
            period,
            completeness,
            value,
            attribution,
            computedAt);
    if (contentFingerprint == null || contentFingerprint.equals("pending")) {
      contentFingerprint = calculated;
    } else {
      requireFingerprint(contentFingerprint, "content fingerprint");
      if (!contentFingerprint.equals(calculated)) {
        throw new IllegalArgumentException("aggregate content fingerprint is inconsistent");
      }
    }
  }

  public static FederatedAggregateSnapshot from(
      AggregateSnapshot snapshot,
      IndicatorDefinitionVersion definition,
      AggregateSnapshotRevision revision,
      DeploymentId sourceDeployment) {
    Objects.requireNonNull(snapshot, "aggregate snapshot");
    Objects.requireNonNull(definition, "indicator definition");
    Objects.requireNonNull(revision, "snapshot revision");
    if (snapshot.completeness() != AggregateCompleteness.COMPLETE
        || snapshot.value().isEmpty()
        || !snapshot.indicatorKey().equals(definition.key())
        || snapshot.indicatorVersion() != definition.version()
        || revision.snapshotId() == null
        || !revision.snapshotId().equals(snapshot.snapshotId())
        || definition.formula() != AggregationFormula.SUM
        || !definition.fingerprint().matches("[0-9a-f]{64}")
        || !definition
            .sourceMeasures()
            .equals(snapshot.inputs().stream().map(AggregateInput::measure).toList())
        || snapshot.inputs().stream()
            .anyMatch(
                input ->
                    input.completeness() != AggregateCompleteness.COMPLETE
                        || input.value().isEmpty())) {
      throw new IllegalArgumentException("snapshot is not an eligible exact-version aggregate");
    }
    long sum;
    try {
      sum =
          snapshot.inputs().stream()
              .mapToLong(input -> input.value().orElseThrow())
              .reduce(0, Math::addExact);
    } catch (ArithmeticException overflow) {
      throw new IllegalArgumentException("aggregate inputs exceed safe numeric bounds", overflow);
    }
    if (sum != snapshot.value().orElseThrow()) {
      throw new IllegalArgumentException(
          "aggregate value does not match its approved source inputs");
    }
    SourceMeasure.Attribution attribution = attribution(definition);
    return new FederatedAggregateSnapshot(
        sourceDeployment,
        snapshot.snapshotId(),
        revision.seriesId(),
        revision.revisionNumber(),
        definition.key(),
        definition.version(),
        definition.fingerprint(),
        snapshot.inputFingerprint(),
        namespacedOrganization(snapshot.organization(), sourceDeployment),
        snapshot.period(),
        snapshot.completeness(),
        snapshot.value().orElseThrow(),
        attribution,
        snapshot.computedAt());
  }

  public static SourceMeasure.Attribution attribution(IndicatorDefinitionVersion definition) {
    Set<SourceMeasure.Attribution> transferAttributions =
        definition.sourceMeasures().stream()
            .filter(measure -> measure.owner() == SourceMeasure.Owner.TRANSFER)
            .map(SourceMeasure::attribution)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    if (transferAttributions.size() > 1) {
      throw new IllegalArgumentException(
          "mixed Transfer attribution cannot be federated implicitly");
    }
    return transferAttributions.stream().findFirst().orElse(SourceMeasure.Attribution.NONE);
  }

  private static String calculateContentFingerprint(
      DeploymentId sourceDeployment,
      UUID sourceSnapshotId,
      UUID revisionSeriesId,
      int revisionNumber,
      String indicatorKey,
      int indicatorVersion,
      String definitionFingerprint,
      String sourceFingerprint,
      ExternalReference sourceOrganization,
      ReportingPeriod period,
      AggregateCompleteness completeness,
      long value,
      SourceMeasure.Attribution attribution,
      Instant computedAt) {
    String canonical =
        String.join(
            "\n",
            "AYUVAHAK_FEDERATED_AGGREGATE_V1",
            sourceDeployment.value(),
            sourceSnapshotId.toString(),
            revisionSeriesId.toString(),
            Integer.toString(revisionNumber),
            indicatorKey,
            Integer.toString(indicatorVersion),
            definitionFingerprint,
            sourceFingerprint,
            sourceOrganization.issuer().value(),
            sourceOrganization.resourceType(),
            sourceOrganization.value(),
            period.startInclusiveLocal().toString(),
            period.endExclusiveLocal().toString(),
            period.timeZoneId(),
            period.startInclusiveUtc().toString(),
            period.endExclusiveUtc().toString(),
            period.timeZoneRulesVersion(),
            completeness.name(),
            Long.toString(value),
            attribution.name(),
            computedAt.toString());
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

  private static ExternalReference namespacedOrganization(
      ExternalReference organization, DeploymentId sourceDeployment) {
    if (!"urn:ayuvahak:directory".equals(organization.issuer().value())
        || !"Organization".equals(organization.resourceType())) {
      throw new IllegalArgumentException("local aggregate source organization is invalid");
    }
    return new ExternalReference(
        new org.ayuvahak.platform.core.IssuerId(
            "urn:ayuvahak:deployment:" + sourceDeployment.value() + ":directory"),
        "Organization",
        organization.value());
  }

  @Override
  public String toString() {
    return "FederatedAggregateSnapshot[sourceDeployment="
        + sourceDeployment
        + ", sourceSnapshotId=redacted, indicatorKey="
        + indicatorKey
        + ", indicatorVersion="
        + indicatorVersion
        + ", revisionNumber="
        + revisionNumber
        + ", completeness="
        + completeness
        + ", value=redacted, contentFingerprint=redacted]";
  }
}
