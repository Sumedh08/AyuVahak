package org.ayuvahak.analytics.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;

/** Immutable result for one version, scope, resolved period, and set of owner cutoffs. */
public record AggregateSnapshot(
    UUID snapshotId,
    InternalId computationRequestId,
    String indicatorKey,
    int indicatorVersion,
    ExternalReference organization,
    ReportingPeriod period,
    AggregateCompleteness completeness,
    OptionalLong value,
    List<AggregateInput> inputs,
    Instant computedAt,
    String inputFingerprint) {

  public AggregateSnapshot {
    Objects.requireNonNull(snapshotId, "snapshot id");
    Objects.requireNonNull(computationRequestId, "computation request id");
    Objects.requireNonNull(indicatorKey, "indicator key");
    Objects.requireNonNull(organization, "organization");
    Objects.requireNonNull(period, "period");
    Objects.requireNonNull(completeness, "completeness");
    Objects.requireNonNull(value, "value");
    inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
    Objects.requireNonNull(computedAt, "computed at");
    Objects.requireNonNull(inputFingerprint, "input fingerprint");
    if (indicatorVersion < 1 || inputs.isEmpty() || !inputFingerprint.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("aggregate snapshot metadata is invalid");
    }
    if ((completeness == AggregateCompleteness.COMPLETE) != value.isPresent()) {
      throw new IllegalArgumentException("only complete aggregate snapshots may carry a value");
    }
    if (value.isPresent() && value.getAsLong() < 0) {
      throw new IllegalArgumentException("aggregate values cannot be negative");
    }
  }

  public static AggregateSnapshot create(
      InternalId computationRequestId,
      IndicatorDefinitionVersion definition,
      ExternalReference organization,
      ReportingPeriod period,
      AggregateCompleteness completeness,
      OptionalLong value,
      List<AggregateInput> inputs,
      Instant computedAt) {
    List<AggregateInput> ordered =
        inputs.stream()
            .sorted(java.util.Comparator.comparing(input -> input.measure().stableKey()))
            .toList();
    return new AggregateSnapshot(
        UUID.randomUUID(),
        computationRequestId,
        definition.key(),
        definition.version(),
        organization,
        period,
        completeness,
        value,
        ordered,
        computedAt,
        fingerprint(
            computationRequestId, definition, organization, period, completeness, value, ordered));
  }

  private static String fingerprint(
      InternalId requestId,
      IndicatorDefinitionVersion definition,
      ExternalReference organization,
      ReportingPeriod period,
      AggregateCompleteness completeness,
      OptionalLong value,
      List<AggregateInput> inputs) {
    StringBuilder canonical =
        new StringBuilder()
            .append(requestId)
            .append('\n')
            .append(definition.fingerprint())
            .append('\n')
            .append(organization.issuer().value())
            .append('\n')
            .append(organization.resourceType())
            .append('\n')
            .append(organization.value())
            .append('\n')
            .append(period.startInclusiveLocal())
            .append('\n')
            .append(period.endExclusiveLocal())
            .append('\n')
            .append(period.timeZoneId())
            .append('\n')
            .append(period.startInclusiveUtc())
            .append('\n')
            .append(period.endExclusiveUtc())
            .append('\n')
            .append(period.timeZoneRulesVersion())
            .append('\n')
            .append(completeness)
            .append('\n')
            .append(value.isPresent() ? value.getAsLong() : "MISSING");
    for (AggregateInput input : inputs) {
      canonical
          .append('\n')
          .append(input.measure().stableKey())
          .append('|')
          .append(input.sourceSnapshotReference().map(UUID::toString).orElse(""))
          .append('|')
          .append(input.sourceCutoff().map(Instant::toString).orElse(""))
          .append('|')
          .append(input.ownerContractVersion().orElse(""))
          .append('|')
          .append(input.completeness())
          .append('|')
          .append(input.value().isPresent() ? input.value().getAsLong() : "MISSING")
          .append('|')
          .append(input.generatedAt().map(Instant::toString).orElse(""));
    }
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
    return "AggregateSnapshot[snapshotId="
        + snapshotId
        + ", indicatorKey="
        + indicatorKey
        + ", indicatorVersion="
        + indicatorVersion
        + ", organization=redacted, completeness="
        + completeness
        + ", value="
        + value
        + ", inputCount="
        + inputs.size()
        + "]";
  }
}
