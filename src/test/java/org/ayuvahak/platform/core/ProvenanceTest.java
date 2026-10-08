package org.ayuvahak.platform.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProvenanceTest {

  @Test
  void provenanceKeepsIssuerActorObservationReceiptAndVerificationDistinct() {
    IssuerId source = new IssuerId("capacity-source-west");
    ActorReference supplier =
        ActorReference.service(new IssuerId("identity.example"), "capacity-feed");
    ExternalReference sourceReference = new ExternalReference(source, "Observation", "obs-17");
    Instant observed = Instant.parse("2026-04-01T10:00:00Z");
    Instant received = Instant.parse("2026-04-01T10:04:00Z");
    Instant validUntil = Instant.parse("2026-04-01T10:03:00Z");

    Provenance provenance =
        new Provenance(
            source,
            Optional.of(sourceReference),
            supplier,
            SourceKind.INTEGRATION,
            new OccurredAt(observed),
            new RecordedAt(received),
            Optional.of("capacity-normalization-v1"),
            Provenance.Verification.sourceAsserted(),
            Optional.of(validUntil));

    assertThat(provenance.source()).isEqualTo(source);
    assertThat(provenance.sourceReference()).contains(sourceReference);
    assertThat(provenance.attributedActor()).isEqualTo(supplier);
    assertThat(provenance.sourceKind()).isEqualTo(SourceKind.INTEGRATION);
    assertThat(provenance.observedAt().value()).isEqualTo(observed);
    assertThat(provenance.recordedAt().value()).isEqualTo(received);
    assertThat(provenance.verification().status())
        .isEqualTo(Provenance.Verification.Status.SOURCE_ASSERTED);
    assertThat(provenance.validUntil()).contains(validUntil);
  }

  @Test
  void provenanceRejectsFreeformTransformationTextAndInvalidValidityWindows() {
    IssuerId source = new IssuerId("source-west");
    ActorReference supplier = ActorReference.human(new IssuerId("identity.example"), "person-17");
    OccurredAt observedAt = new OccurredAt(Instant.parse("2026-04-01T10:00:00Z"));
    RecordedAt recordedAt = new RecordedAt(Instant.parse("2026-04-01T10:04:00Z"));

    assertThatThrownBy(
            () ->
                new Provenance(
                    source,
                    Optional.empty(),
                    supplier,
                    SourceKind.INTERACTIVE_REQUEST,
                    observedAt,
                    recordedAt,
                    Optional.of("normalized\npatient-name"),
                    Provenance.Verification.notAssessed(),
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new Provenance(
                    source,
                    Optional.empty(),
                    supplier,
                    SourceKind.INTERACTIVE_REQUEST,
                    observedAt,
                    recordedAt,
                    Optional.empty(),
                    Provenance.Verification.notAssessed(),
                    Optional.of(observedAt.value())))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void independentVerificationRequiresAnAttributableActorTimeAndMethod() {
    assertThatThrownBy(
            () ->
                new Provenance.Verification(
                    Provenance.Verification.Status.INDEPENDENTLY_VERIFIED,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
