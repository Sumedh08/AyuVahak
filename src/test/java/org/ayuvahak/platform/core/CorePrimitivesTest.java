package org.ayuvahak.platform.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CorePrimitivesTest {

  @Test
  void internalIdentifiersAreOpaqueCanonicalUuidV4Values() {
    InternalId generated = InternalId.generate();

    assertThat(generated.value().version()).isEqualTo(4);
    assertThat(generated.value().variant()).isEqualTo(2);
    assertThat(InternalId.parse(generated.toString())).isEqualTo(generated);
    assertThat(generated.toString()).matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
  }

  @Test
  void internalIdentifiersRejectNonV4OrAmbiguousText() {
    assertThatThrownBy(
            () -> new InternalId(UUID.fromString("00000000-0000-1000-8000-000000000001")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> InternalId.parse("1-1-1-1-1"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void principalsAndExternalReferencesRemainScopedByExactIssuerAndResourceType() {
    IssuerId issuerA = new IssuerId("https://identity.example/issuer-a");
    IssuerId issuerB = new IssuerId("https://identity.example/issuer-b");
    PrincipalReference principalA = new PrincipalReference(issuerA, "subject-7");

    assertThat(new PrincipalReference(issuerA, "subject-7")).isEqualTo(principalA);
    assertThat(new PrincipalReference(issuerB, "subject-7")).isNotEqualTo(principalA);
    assertThat(new ExternalReference(issuerA, "Facility", "site-7"))
        .isNotEqualTo(new ExternalReference(issuerB, "Facility", "site-7"))
        .isNotEqualTo(new ExternalReference(issuerA, "Organization", "site-7"));
    assertThat(principalA.toString()).doesNotContain("subject-7");
    assertThat(new ExternalReference(issuerA, "Facility", "site-7").toString())
        .doesNotContain("site-7");
  }

  @Test
  void issuerScopedReferencesRejectBlankTrimmedOrControlCharacterValues() {
    assertThatThrownBy(() -> new IssuerId("  ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new IssuerId(" issuer ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PrincipalReference(new IssuerId("issuer"), "subject\n7"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ExternalReference(new IssuerId("issuer"), "Facility", "  "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void correlationAndCausationAreDistinctValidatedValues() {
    CorrelationId correlationId = CorrelationId.generate();
    CausationId causationId = new CausationId(correlationId.value());

    assertThat(correlationId.value()).matches("[0-9a-f-]{36}");
    assertThat(causationId).isNotEqualTo(correlationId);
    assertThatThrownBy(() -> new CorrelationId("unsafe\nvalue"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CausationId("x".repeat(129)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void recordedTimeUsesAnInjectedClockAndRemainsDistinctFromOccurrenceTime() {
    Instant occurredAt = Instant.parse("2026-01-02T03:04:05Z");
    Clock receiptClock = Clock.fixed(Instant.parse("2026-01-02T03:05:06Z"), ZoneOffset.UTC);

    OccurredAt occurrence = new OccurredAt(occurredAt);
    RecordedAt recorded = RecordedAt.capture(receiptClock);

    assertThat(occurrence.value()).isEqualTo(occurredAt);
    assertThat(recorded.value()).isEqualTo(Instant.parse("2026-01-02T03:05:06Z"));
    assertThat(recorded.value()).isNotEqualTo(occurrence.value());
  }

  @Test
  void sourceKindNamesTheOriginWithoutEncodingActorIdentity() {
    assertThat(SourceKind.values())
        .containsExactly(
            SourceKind.INTERACTIVE_REQUEST,
            SourceKind.INTEGRATION,
            SourceKind.SCHEDULED_WORK,
            SourceKind.BACKGROUND_WORK,
            SourceKind.OTHER_GOVERNED);
  }
}
