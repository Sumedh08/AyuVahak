package org.ayuvahak.directory.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.ayuvahak.platform.exchange.domain.PartnerKey;
import org.ayuvahak.platform.federation.domain.DeploymentId;
import org.junit.jupiter.api.Test;

class DirectoryFederatedProjectionPolicyTest {

  private static final DeploymentId SOURCE = new DeploymentId("SYN-DEPLOYMENT-B");
  private static final PartnerKey PARTNER = new PartnerKey("synthetic-directory-peer");
  private static final Instant RECEIVED_AT = Instant.parse("2026-10-18T06:00:00Z");
  private static final Duration FRESHNESS = Duration.ofMinutes(5);

  @Test
  void firstObservationCreatesNamespacedProjectionWithLocalFreshnessDeadline() {
    var result =
        DirectoryFederatedProjectionPolicy.reconcile(
            Optional.empty(), fact(4, "SYN-FACILITY-A", true), PARTNER, 3, RECEIVED_AT, FRESHNESS);

    assertThat(result.outcome()).isEqualTo(DirectoryFederatedProjectionPolicy.Outcome.CREATED);
    assertThat(result.projection().reference())
        .isEqualTo(
            new DirectoryRemoteResourceReference(
                SOURCE, "Directory", "Facility", "SYN-FACILITY-A"));
    assertThat(result.projection().receivedAt()).isEqualTo(RECEIVED_AT);
    assertThat(result.projection().observedAt()).isEqualTo(RECEIVED_AT.minusSeconds(10));
    assertThat(result.projection().validUntil()).isEqualTo(RECEIVED_AT.plus(FRESHNESS));
    assertThat(result.projection().partnerConfigurationVersion()).isEqualTo(3);
  }

  @Test
  void exactDuplicateRefreshesFreshnessWithoutChangingSourceVersionOrContent() {
    var original = projection(fact(4, "SYN-FACILITY-A", true), RECEIVED_AT);
    Instant refreshedAt = RECEIVED_AT.plusSeconds(30);

    var result =
        DirectoryFederatedProjectionPolicy.reconcile(
            Optional.of(original),
            fact(4, "SYN-FACILITY-A", true),
            PARTNER,
            4,
            refreshedAt,
            FRESHNESS);

    assertThat(result.outcome()).isEqualTo(DirectoryFederatedProjectionPolicy.Outcome.UNCHANGED);
    assertThat(result.projection().fact()).isEqualTo(original.fact());
    assertThat(result.projection().receivedAt()).isEqualTo(refreshedAt);
    assertThat(result.projection().validUntil()).isEqualTo(refreshedAt.plus(FRESHNESS));
    assertThat(result.projection().partnerConfigurationVersion()).isEqualTo(4);
  }

  @Test
  void olderSourceVersionCannotReplaceNewerProjection() {
    var original = projection(fact(5, "SYN-FACILITY-A", true), RECEIVED_AT);

    var result =
        DirectoryFederatedProjectionPolicy.reconcile(
            Optional.of(original),
            fact(4, "SYN-FACILITY-OLDER", false),
            PARTNER,
            3,
            RECEIVED_AT.plusSeconds(30),
            FRESHNESS);

    assertThat(result.outcome())
        .isEqualTo(DirectoryFederatedProjectionPolicy.Outcome.STALE_IGNORED);
    assertThat(result.projection()).isEqualTo(original);
  }

  @Test
  void contradictoryPayloadAtSameSourceVersionBecomesConflictUntilNewerVersionArrives() {
    var original = projection(fact(5, "SYN-FACILITY-A", true), RECEIVED_AT);

    var conflict =
        DirectoryFederatedProjectionPolicy.reconcile(
            Optional.of(original),
            fact(5, "SYN-FACILITY-CHANGED", true),
            PARTNER,
            3,
            RECEIVED_AT.plusSeconds(30),
            FRESHNESS);

    assertThat(conflict.outcome()).isEqualTo(DirectoryFederatedProjectionPolicy.Outcome.CONFLICT);
    assertThat(conflict.projection().status())
        .isEqualTo(DirectoryFederatedProjection.Status.CONFLICT);
    assertThat(conflict.projection().conflictFingerprint()).isPresent();

    var stillConflicted =
        DirectoryFederatedProjectionPolicy.reconcile(
            Optional.of(conflict.projection()),
            fact(5, "SYN-FACILITY-A", true),
            PARTNER,
            3,
            RECEIVED_AT.plusSeconds(60),
            FRESHNESS);

    assertThat(stillConflicted.projection().status())
        .isEqualTo(DirectoryFederatedProjection.Status.CONFLICT);

    var recovered =
        DirectoryFederatedProjectionPolicy.reconcile(
            Optional.of(stillConflicted.projection()),
            fact(6, "SYN-FACILITY-A-NEW", true),
            PARTNER,
            3,
            RECEIVED_AT.plusSeconds(90),
            FRESHNESS);

    assertThat(recovered.outcome()).isEqualTo(DirectoryFederatedProjectionPolicy.Outcome.UPDATED);
    assertThat(recovered.projection().status())
        .isEqualTo(DirectoryFederatedProjection.Status.CURRENT);
    assertThat(recovered.projection().conflictFingerprint()).isEmpty();
  }

  @Test
  void freshnessIsComputedAgainstLocalReceiptDeadline() {
    var projection = projection(fact(4, "SYN-FACILITY-A", true), RECEIVED_AT);

    assertThat(projection.freshnessAt(RECEIVED_AT.plusSeconds(299)))
        .isEqualTo(DirectoryFederatedProjection.Freshness.FRESH);
    assertThat(projection.freshnessAt(RECEIVED_AT.plusSeconds(300)))
        .isEqualTo(DirectoryFederatedProjection.Freshness.STALE);
  }

  private static DirectoryFederatedProjection projection(
      DirectoryFederatedFact fact, Instant receivedAt) {
    return DirectoryFederatedProjectionPolicy.reconcile(
            Optional.empty(), fact, PARTNER, 3, receivedAt, FRESHNESS)
        .projection();
  }

  private static DirectoryFederatedFact fact(long version, String facilityId, boolean active) {
    var reference =
        new DirectoryRemoteResourceReference(SOURCE, "Directory", "Facility", "SYN-FACILITY-A");
    var organization =
        new DirectoryRemoteResourceReference(SOURCE, "Directory", "Organization", "SYN-ORG-001");
    return new DirectoryFederatedFact(
        reference,
        Optional.of(organization),
        "Synthetic " + facilityId + " at version " + version,
        active,
        version,
        Optional.of(RECEIVED_AT.minusSeconds(10)),
        "directory-profile-v1");
  }
}
