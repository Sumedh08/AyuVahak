package org.ayuvahak.platform.core;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Minimized source and temporal evidence attached to a fact by that fact's owner. */
public record Provenance(
    IssuerId source,
    Optional<ExternalReference> sourceReference,
    ActorReference attributedActor,
    SourceKind sourceKind,
    OccurredAt observedAt,
    RecordedAt recordedAt,
    Optional<String> transformation,
    Verification verification,
    Optional<Instant> validUntil) {

  public Provenance {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(sourceReference, "sourceReference");
    Objects.requireNonNull(attributedActor, "attributedActor");
    Objects.requireNonNull(sourceKind, "sourceKind");
    Objects.requireNonNull(observedAt, "observedAt");
    Objects.requireNonNull(recordedAt, "recordedAt");
    Objects.requireNonNull(transformation, "transformation").ifPresent(Provenance::requireToken);
    Objects.requireNonNull(verification, "verification");
    Objects.requireNonNull(validUntil, "validUntil");

    if (validUntil.isPresent() && !validUntil.orElseThrow().isAfter(observedAt.value())) {
      throw new IllegalArgumentException("validUntil must be later than observedAt");
    }
  }

  @Override
  public String toString() {
    return "Provenance[redacted]";
  }

  private static void requireToken(String value) {
    if (value == null || !value.matches("[A-Za-z][A-Za-z0-9._:-]{0,63}")) {
      throw new IllegalArgumentException("provenance metadata must be a bounded identifier token");
    }
  }

  /**
   * Describes verification without implying that an unverified or source-asserted fact is valid.
   */
  public record Verification(
      Status status,
      Optional<ActorReference> verifiedBy,
      Optional<RecordedAt> verificationRecordedAt,
      Optional<String> method) {

    public Verification {
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(verifiedBy, "verifiedBy");
      Objects.requireNonNull(verificationRecordedAt, "verificationRecordedAt");
      Objects.requireNonNull(method, "method").ifPresent(Provenance::requireToken);

      boolean hasEvidence =
          verifiedBy.isPresent() && verificationRecordedAt.isPresent() && method.isPresent();
      boolean hasPartialEvidence =
          verifiedBy.isPresent() || verificationRecordedAt.isPresent() || method.isPresent();
      if (status == Status.INDEPENDENTLY_VERIFIED && !hasEvidence) {
        throw new IllegalArgumentException(
            "independent verification requires actor, recorded time, and method");
      }
      if (status != Status.INDEPENDENTLY_VERIFIED && hasPartialEvidence) {
        throw new IllegalArgumentException(
            "verification evidence is only allowed for independently verified provenance");
      }
    }

    public static Verification notAssessed() {
      return new Verification(
          Status.NOT_ASSESSED, Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static Verification sourceAsserted() {
      return new Verification(
          Status.SOURCE_ASSERTED, Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static Verification independentlyVerified(
        ActorReference verifiedBy, RecordedAt verificationRecordedAt, String method) {
      return new Verification(
          Status.INDEPENDENTLY_VERIFIED,
          Optional.of(verifiedBy),
          Optional.of(verificationRecordedAt),
          Optional.of(method));
    }

    @Override
    public String toString() {
      return "Verification[status=" + status + "]";
    }

    public enum Status {
      NOT_ASSESSED,
      SOURCE_ASSERTED,
      INDEPENDENTLY_VERIFIED
    }
  }
}
