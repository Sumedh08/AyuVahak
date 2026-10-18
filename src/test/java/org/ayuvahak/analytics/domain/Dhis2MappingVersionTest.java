package org.ayuvahak.analytics.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.junit.jupiter.api.Test;

class Dhis2MappingVersionTest {

  @Test
  void resolvesVersionedMappingToCompleteConfiguredTargetTuple() {
    Dhis2MappingVersion mapping = mapping();
    ReportingPeriod period =
        ReportingPeriod.resolve(
            LocalDate.parse("2026-01-01"), LocalDate.parse("2026-02-01"), ZoneId.of("UTC"));

    assertThat(mapping.tupleFor(period).stableKey())
        .isEqualTo("SYN-DATAELEMENT-001|202601|SYN-ORGUNIT-001|SYN-COC-001|SYN-AOC-001");
    assertThat(mapping.effectiveAt(Instant.parse("2026-03-01T00:00:00Z"))).isTrue();
    assertThat(mapping.toString()).doesNotContain("SYN-ORGUNIT-001", "SYN-DATAELEMENT-001");
  }

  @Test
  void refusesCallerLikeOrUnboundedMetadataAndEmptyEffectiveRange() {
    assertThatThrownBy(
            () ->
                new Dhis2MappingVersion(
                    "SYN-MAPPING-001",
                    1,
                    "SYN-INDICATOR-001",
                    1,
                    organization(),
                    new PartnerKey("synthetic-dhis2"),
                    "https://attacker.test/dataElement",
                    "SYN-ORGUNIT-001",
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Dhis2PeriodFormat.MONTHLY,
                    "SYN-REPORTING-SCOPE-A",
                    "AGGREGATE-OUTPUT",
                    true,
                    true,
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                new Dhis2MappingVersion(
                    "SYN-MAPPING-001",
                    1,
                    "SYN-INDICATOR-001",
                    1,
                    organization(),
                    new PartnerKey("synthetic-dhis2"),
                    "SYN-DATAELEMENT-001",
                    "SYN-ORGUNIT-001",
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Dhis2PeriodFormat.MONTHLY,
                    "SYN-REPORTING-SCOPE-A",
                    "AGGREGATE-OUTPUT",
                    true,
                    true,
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Optional.of(Instant.parse("2026-01-01T00:00:00Z"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void exposesOnlySyntheticAggregateMappingSemantics() {
    AggregateSnapshot snapshot =
        AggregateSnapshot.create(
            InternalId.generate(),
            new IndicatorDefinitionVersion(
                "SYN-INDICATOR-001",
                1,
                "Synthetic monthly count",
                ZoneId.of("UTC"),
                List.of(
                    new SourceMeasure(
                        SourceMeasure.Owner.REFERRAL,
                        "SUBMITTED_COUNT",
                        SourceMeasure.Attribution.NONE)),
                true),
            organization(),
            ReportingPeriod.resolve(
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-02-01"), ZoneId.of("UTC")),
            AggregateCompleteness.COMPLETE,
            OptionalLong.of(4),
            List.of(
                new AggregateInput(
                    new SourceMeasure(
                        SourceMeasure.Owner.REFERRAL,
                        "SUBMITTED_COUNT",
                        SourceMeasure.Attribution.NONE),
                    Optional.of(UUID.randomUUID()),
                    Optional.of(Instant.parse("2026-02-02T00:00:00Z")),
                    Optional.of("referral-reporting-v1"),
                    AggregateCompleteness.COMPLETE,
                    OptionalLong.of(4),
                    Optional.of(Instant.parse("2026-02-02T00:00:00Z")))),
            Instant.parse("2026-02-03T00:00:00Z"));
    var payload =
        new org.ayuvahak.analytics.adapter.dhis2.Dhis2DataValueSetMapper(
                new tools.jackson.databind.ObjectMapper())
            .mapToString(snapshot, mapping());

    assertThat(payload)
        .contains("\"dataSet\":\"SYN-DATASET-001\"", "\"period\":\"202601\"")
        .contains("\"dataValues\":[{\"dataElement\":\"SYN-DATAELEMENT-001\"")
        .contains("\"value\":\"4\"")
        .doesNotContain("PatientReference", "SYN-PATIENT", "clinical", "diagnosis");
  }

  private static Dhis2MappingVersion mapping() {
    return new Dhis2MappingVersion(
        "SYN-MAPPING-001",
        1,
        "SYN-INDICATOR-001",
        1,
        organization(),
        new PartnerKey("synthetic-dhis2"),
        "SYN-DATAELEMENT-001",
        "SYN-ORGUNIT-001",
        Optional.of("SYN-DATASET-001"),
        Optional.of("SYN-COC-001"),
        Optional.of("SYN-AOC-001"),
        Dhis2PeriodFormat.MONTHLY,
        "SYN-REPORTING-SCOPE-A",
        "AGGREGATE-OUTPUT",
        true,
        true,
        Instant.parse("2026-01-01T00:00:00Z"),
        Optional.empty());
  }

  private static ExternalReference organization() {
    return new ExternalReference(
        new IssuerId("urn:ayuvahak:directory"), "Organization", UUID.randomUUID().toString());
  }
}
