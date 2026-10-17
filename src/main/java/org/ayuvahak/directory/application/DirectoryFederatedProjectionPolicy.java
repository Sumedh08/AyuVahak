package org.ayuvahak.directory.application;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import org.ayuvahak.platform.exchange.domain.PartnerKey;

/** Source-version reconciliation for one remote Directory identity. */
public final class DirectoryFederatedProjectionPolicy {

  private static final Duration MAX_FRESHNESS = Duration.ofDays(7);

  private DirectoryFederatedProjectionPolicy() {}

  public static Result reconcile(
      Optional<DirectoryFederatedProjection> current,
      DirectoryFederatedFact incoming,
      PartnerKey partnerKey,
      int partnerConfigurationVersion,
      Instant receivedAt,
      Duration freshness) {
    return reconcile(
        current,
        incoming,
        partnerKey,
        partnerConfigurationVersion,
        incoming.sourceUpdatedAt().orElse(receivedAt),
        receivedAt,
        freshness);
  }

  public static Result reconcile(
      Optional<DirectoryFederatedProjection> current,
      DirectoryFederatedFact incoming,
      PartnerKey partnerKey,
      int partnerConfigurationVersion,
      Instant sourceObservedAt,
      Instant receivedAt,
      Duration freshness) {
    Objects.requireNonNull(current, "current");
    Objects.requireNonNull(incoming, "incoming");
    Objects.requireNonNull(partnerKey, "partnerKey");
    Objects.requireNonNull(sourceObservedAt, "sourceObservedAt");
    Objects.requireNonNull(receivedAt, "receivedAt");
    Objects.requireNonNull(freshness, "freshness");
    if (partnerConfigurationVersion < 1
        || freshness.isZero()
        || freshness.isNegative()
        || freshness.compareTo(MAX_FRESHNESS) > 0) {
      throw new IllegalArgumentException("configuration version or freshness bound is invalid");
    }

    String fingerprint = fingerprint(incoming);
    Instant validUntil = receivedAt.plus(freshness);
    if (current.isEmpty()) {
      return new Result(
          Outcome.CREATED,
          projection(
              incoming,
              partnerKey,
              partnerConfigurationVersion,
              fingerprint,
              DirectoryFederatedProjection.Status.CURRENT,
              Optional.empty(),
              incoming.sourceUpdatedAt().orElse(sourceObservedAt),
              receivedAt,
              validUntil));
    }

    DirectoryFederatedProjection previous = current.orElseThrow();
    if (!previous.reference().equals(incoming.reference())) {
      throw new IllegalArgumentException("reconciliation must use the same namespaced reference");
    }
    long previousVersion = previous.fact().sourceVersion();
    if (incoming.sourceVersion() < previousVersion) {
      return new Result(Outcome.STALE_IGNORED, previous);
    }
    if (incoming.sourceVersion() > previousVersion) {
      return new Result(
          Outcome.UPDATED,
          projection(
              incoming,
              partnerKey,
              partnerConfigurationVersion,
              fingerprint,
              DirectoryFederatedProjection.Status.CURRENT,
              Optional.empty(),
              incoming.sourceUpdatedAt().orElse(sourceObservedAt),
              receivedAt,
              validUntil));
    }

    if (previous.status() == DirectoryFederatedProjection.Status.CONFLICT) {
      return new Result(
          Outcome.CONFLICT,
          projection(
              previous.fact(),
              partnerKey,
              partnerConfigurationVersion,
              previous.factFingerprint(),
              DirectoryFederatedProjection.Status.CONFLICT,
              previous.conflictFingerprint(),
              sourceObservedAt,
              receivedAt,
              validUntil));
    }
    if (!previous.factFingerprint().equals(fingerprint)) {
      return new Result(
          Outcome.CONFLICT,
          projection(
              previous.fact(),
              partnerKey,
              partnerConfigurationVersion,
              previous.factFingerprint(),
              DirectoryFederatedProjection.Status.CONFLICT,
              Optional.of(fingerprint),
              previous.observedAt(),
              receivedAt,
              validUntil));
    }
    return new Result(
        Outcome.UNCHANGED,
        projection(
            previous.fact(),
            partnerKey,
            partnerConfigurationVersion,
            previous.factFingerprint(),
            DirectoryFederatedProjection.Status.CURRENT,
            Optional.empty(),
            incoming.sourceUpdatedAt().orElse(sourceObservedAt),
            receivedAt,
            validUntil));
  }

  private static DirectoryFederatedProjection projection(
      DirectoryFederatedFact fact,
      PartnerKey partnerKey,
      int configurationVersion,
      String fingerprint,
      DirectoryFederatedProjection.Status status,
      Optional<String> conflictFingerprint,
      Instant observedAt,
      Instant receivedAt,
      Instant validUntil) {
    return new DirectoryFederatedProjection(
        fact,
        partnerKey,
        configurationVersion,
        fingerprint,
        status,
        conflictFingerprint,
        observedAt,
        receivedAt,
        validUntil);
  }

  private static String fingerprint(DirectoryFederatedFact fact) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream fields = new DataOutputStream(bytes)) {
        write(fields, fact.reference().deploymentId().value());
        write(fields, fact.reference().context());
        write(fields, fact.reference().resourceType());
        write(fields, fact.reference().resourceId());
        write(
            fields,
            fact.organization().map(DirectoryFederatedProjectionPolicy::referenceKey).orElse(""));
        write(fields, fact.displayName());
        fields.writeBoolean(fact.active());
        fields.writeLong(fact.sourceVersion());
        write(fields, fact.sourceUpdatedAt().map(Instant::toString).orElse(""));
        write(fields, fact.sourcePolicyVersion());
      }
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    } catch (IOException impossible) {
      throw new IllegalStateException(
          "Unable to fingerprint the remote Directory fact.", impossible);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable.", impossible);
    }
  }

  private static String referenceKey(DirectoryRemoteResourceReference reference) {
    return String.join(
        "|",
        reference.deploymentId().value(),
        reference.context(),
        reference.resourceType(),
        reference.resourceId());
  }

  private static void write(DataOutputStream fields, String value) throws IOException {
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    fields.writeInt(encoded.length);
    fields.write(encoded);
  }

  public record Result(Outcome outcome, DirectoryFederatedProjection projection) {
    public Result {
      Objects.requireNonNull(outcome, "outcome");
      Objects.requireNonNull(projection, "projection");
    }
  }

  public enum Outcome {
    CREATED,
    UPDATED,
    UNCHANGED,
    STALE_IGNORED,
    CONFLICT
  }
}
