package org.ayuvahak.analytics.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
    Optional<ExternalReference> facility,
    ReportingPeriod period,
    AggregateCompleteness completeness,
    OptionalLong value,
    Optional<BigDecimal> preciseValue,
    List<AggregateInput> inputs,
    Instant computedAt,
    String inputFingerprint) {

  /** Compatibility constructor for integral, organization-scoped snapshots. */
  public AggregateSnapshot(
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
    this(
        snapshotId,
        computationRequestId,
        indicatorKey,
        indicatorVersion,
        organization,
        Optional.empty(),
        period,
        completeness,
        value,
        value.isPresent() ? Optional.of(BigDecimal.valueOf(value.getAsLong())) : Optional.empty(),
        inputs,
        computedAt,
        inputFingerprint);
  }

  public AggregateSnapshot {
    Objects.requireNonNull(snapshotId, "snapshot id");
    Objects.requireNonNull(computationRequestId, "computation request id");
    Objects.requireNonNull(indicatorKey, "indicator key");
    Objects.requireNonNull(organization, "organization");
    facility = Objects.requireNonNull(facility, "facility");
    facility.ifPresent(
        reference -> {
          if (!"Facility".equals(reference.resourceType())
              || !organization.issuer().equals(reference.issuer())) {
            throw new IllegalArgumentException(
                "aggregate facility must share the Directory namespace");
          }
        });
    Objects.requireNonNull(period, "period");
    Objects.requireNonNull(completeness, "completeness");
    Objects.requireNonNull(value, "integral value");
    preciseValue = Objects.requireNonNull(preciseValue, "precise value");
    inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
    Objects.requireNonNull(computedAt, "computed at");
    Objects.requireNonNull(inputFingerprint, "input fingerprint");
    if (indicatorVersion < 1 || inputs.isEmpty() || !inputFingerprint.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("aggregate snapshot metadata is invalid");
    }
    if ((completeness == AggregateCompleteness.COMPLETE) != preciseValue.isPresent()) {
      throw new IllegalArgumentException("only complete aggregate snapshots may carry a value");
    }
    preciseValue.ifPresent(
        numeric -> {
          if (numeric.signum() < 0 || numeric.scale() > 6 || numeric.precision() > 20) {
            throw new IllegalArgumentException("aggregate value is outside supported bounds");
          }
        });
    if (value.isPresent()) {
      BigDecimal integral = BigDecimal.valueOf(value.getAsLong());
      if (value.getAsLong() < 0 || preciseValue.filter(integral::equals).isEmpty()) {
        throw new IllegalArgumentException("integral and precise aggregate values must agree");
      }
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
    Objects.requireNonNull(value, "value");
    return createNumeric(
        computationRequestId,
        definition,
        organization,
        Optional.empty(),
        period,
        completeness,
        value.isPresent() ? Optional.of(BigDecimal.valueOf(value.getAsLong())) : Optional.empty(),
        inputs,
        computedAt);
  }

  public static AggregateSnapshot createNumeric(
      InternalId computationRequestId,
      IndicatorDefinitionVersion definition,
      ExternalReference organization,
      Optional<ExternalReference> facility,
      ReportingPeriod period,
      AggregateCompleteness completeness,
      Optional<BigDecimal> preciseValue,
      List<AggregateInput> inputs,
      Instant computedAt) {
    Objects.requireNonNull(definition, "indicator definition");
    Objects.requireNonNull(preciseValue, "precise value");
    List<AggregateInput> ordered =
        inputs.stream()
            .sorted(java.util.Comparator.comparing(input -> input.measure().stableKey()))
            .toList();
    OptionalLong integralValue =
        definition.formula() == AggregationFormula.SUM && preciseValue.isPresent()
            ? OptionalLong.of(preciseValue.orElseThrow().longValueExact())
            : OptionalLong.empty();
    return new AggregateSnapshot(
        UUID.randomUUID(),
        computationRequestId,
        definition.key(),
        definition.version(),
        organization,
        facility,
        period,
        completeness,
        integralValue,
        preciseValue,
        ordered,
        computedAt,
        fingerprint(
            computationRequestId,
            definition,
            organization,
            facility,
            period,
            completeness,
            preciseValue,
            ordered));
  }

  private static String fingerprint(
      InternalId requestId,
      IndicatorDefinitionVersion definition,
      ExternalReference organization,
      Optional<ExternalReference> facility,
      ReportingPeriod period,
      AggregateCompleteness completeness,
      Optional<BigDecimal> value,
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
            .append(organization.value());
    facility.ifPresent(
        facilityReference ->
            canonical
                .append("\nFACILITY\n")
                .append(facilityReference.issuer().value())
                .append('\n')
                .append(facilityReference.value()));
    canonical
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
        .append(value.map(BigDecimal::toPlainString).orElse("MISSING"));
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

  public Optional<BigDecimal> numericValue() {
    return preciseValue;
  }

  @Override
  public String toString() {
    return "AggregateSnapshot[snapshotId="
        + snapshotId
        + ", indicatorKey="
        + indicatorKey
        + ", indicatorVersion="
        + indicatorVersion
        + ", organization=redacted, facility=redacted, completeness="
        + completeness
        + ", value="
        + (value.isPresent() || preciseValue.isPresent() ? "redacted" : "absent")
        + ", inputCount="
        + inputs.size()
        + "]";
  }
}
