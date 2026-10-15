package org.ayuvahak.directory.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import org.ayuvahak.directory.domain.DirectoryConceptType;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.junit.jupiter.api.Test;

class DirectorySourceProjectionReconciliationTest {

  private static final String SOURCE = "urn:synthetic:mcsd:directory-a";
  private static final String PROFILE = "https://synthetic.example/profile/mcsd/organization";
  private static final Instant BASE_TIME = Instant.parse("2026-10-15T00:00:00Z");
  private final DirectorySourceProjectionReconciliation reconciliation =
      new DirectorySourceProjectionReconciliation();

  @Test
  void exactOpaqueVersionAndProjectionAreAnIdempotentReplay() {
    var current = accepted("v2", Optional.empty(), false, fingerprint('a'), true);

    assertThat(
            reconciliation.decide(
                Optional.of(current),
                organization("v2", Optional.empty(), false, fingerprint('a'), true),
                DirectoryConceptType.ORGANIZATION,
                false))
        .isEqualTo(DirectorySourceProjectionReconciliation.Outcome.UNCHANGED);
  }

  @Test
  void sameOpaqueVersionWithDifferentProjectionIsAConflict() {
    var current = accepted("v2", Optional.empty(), false, fingerprint('a'), true);

    assertThat(
            reconciliation.decide(
                Optional.of(current),
                organization("v2", Optional.empty(), false, fingerprint('b'), true),
                DirectoryConceptType.ORGANIZATION,
                false))
        .isEqualTo(DirectorySourceProjectionReconciliation.Outcome.CONFLICT);
  }

  @Test
  void opaqueVersionTokensAreNeverLexicallyOrdered() {
    var current = accepted("v10", Optional.of(BASE_TIME), false, fingerprint('a'), true);

    assertThat(
            reconciliation.decide(
                Optional.of(current),
                organization(
                    "v2", Optional.of(BASE_TIME.plusSeconds(1)), false, fingerprint('b'), true),
                DirectoryConceptType.ORGANIZATION,
                false))
        .isEqualTo(DirectorySourceProjectionReconciliation.Outcome.CONFLICT);
  }

  @Test
  void onlyConfiguredOrderedLastUpdatedCanClassifyNewerOrOlderSourceRevisions() {
    var current = accepted("opaque-a", Optional.of(BASE_TIME), true, fingerprint('a'), true);

    assertThat(
            reconciliation.decide(
                Optional.of(current),
                organization(
                    "opaque-b",
                    Optional.of(BASE_TIME.plusSeconds(1)),
                    true,
                    fingerprint('b'),
                    true),
                DirectoryConceptType.ORGANIZATION,
                false))
        .isEqualTo(DirectorySourceProjectionReconciliation.Outcome.UPDATE);
    assertThat(
            reconciliation.decide(
                Optional.of(current),
                organization(
                    "opaque-old",
                    Optional.of(BASE_TIME.minusSeconds(1)),
                    true,
                    fingerprint('b'),
                    true),
                DirectoryConceptType.ORGANIZATION,
                false))
        .isEqualTo(DirectorySourceProjectionReconciliation.Outcome.STALE);
  }

  @Test
  void unorderedDifferentProjectionFailsClosedAndCannotOverwrite() {
    var current = accepted("opaque-a", Optional.of(BASE_TIME), false, fingerprint('a'), true);

    assertThat(
            reconciliation.decide(
                Optional.of(current),
                organization(
                    "opaque-b",
                    Optional.of(BASE_TIME.plusSeconds(1)),
                    false,
                    fingerprint('b'),
                    true),
                DirectoryConceptType.ORGANIZATION,
                false))
        .isEqualTo(DirectorySourceProjectionReconciliation.Outcome.CONFLICT);
  }

  @Test
  void explicitSourceDeactivationRequiresExistingOwnerSemantics() {
    var current = accepted("opaque-a", Optional.of(BASE_TIME), true, fingerprint('a'), true);
    var deactivated =
        organization(
            "opaque-b", Optional.of(BASE_TIME.plusSeconds(1)), true, fingerprint('b'), false);

    assertThat(
            reconciliation.decide(
                Optional.of(current), deactivated, DirectoryConceptType.ORGANIZATION, false))
        .isEqualTo(DirectorySourceProjectionReconciliation.Outcome.RECONCILIATION_REQUIRED);
    assertThat(
            reconciliation.decide(
                Optional.of(current), deactivated, DirectoryConceptType.FACILITY, true))
        .isEqualTo(DirectorySourceProjectionReconciliation.Outcome.CONFLICT);
    assertThat(
            reconciliation.decide(
                Optional.of(current), deactivated, DirectoryConceptType.ORGANIZATION, true))
        .isEqualTo(DirectorySourceProjectionReconciliation.Outcome.DEACTIVATE);
  }

  @Test
  void aFirstObservedInactiveResourceRequiresReconciliationInsteadOfCreatingAnActiveFact() {
    assertThat(
            reconciliation.decide(
                Optional.empty(),
                organization("opaque-a", Optional.empty(), false, fingerprint('a'), false),
                DirectoryConceptType.ORGANIZATION,
                false))
        .isEqualTo(DirectorySourceProjectionReconciliation.Outcome.RECONCILIATION_REQUIRED);
  }

  @Test
  void unprojectedSourceFactStillRejectsStaleOrAmbiguousVersionsBeforeCreate() {
    var current =
        unprojected("opaque-new", Optional.of(BASE_TIME.plusSeconds(10)), true, fingerprint('b'));

    assertThat(
            reconciliation.decide(
                Optional.of(current),
                organization("opaque-old", Optional.of(BASE_TIME), true, fingerprint('a'), true),
                DirectoryConceptType.ORGANIZATION,
                false))
        .isEqualTo(DirectorySourceProjectionReconciliation.Outcome.STALE);
    assertThat(
            reconciliation.decide(
                Optional.of(current),
                organization(
                    "opaque-same-time",
                    Optional.of(BASE_TIME.plusSeconds(10)),
                    true,
                    fingerprint('a'),
                    true),
                DirectoryConceptType.ORGANIZATION,
                false))
        .isEqualTo(DirectorySourceProjectionReconciliation.Outcome.CONFLICT);
    assertThat(
            reconciliation.decide(
                Optional.of(current),
                organization(
                    "opaque-later",
                    Optional.of(BASE_TIME.plusSeconds(20)),
                    true,
                    fingerprint('c'),
                    true),
                DirectoryConceptType.ORGANIZATION,
                false))
        .isEqualTo(DirectorySourceProjectionReconciliation.Outcome.CREATE);
  }

  private static DirectorySourceProjectionState accepted(
      String version,
      Optional<Instant> updatedAt,
      boolean ordered,
      String fingerprint,
      boolean active) {
    return new DirectorySourceProjectionState(
        InternalId.generate(),
        identity(),
        DirectoryConceptType.ORGANIZATION,
        Optional.of(InternalId.generate()),
        PROFILE,
        Optional.of(version),
        updatedAt,
        ordered,
        fingerprint,
        active,
        DirectorySourceProjectionReconciliation.Outcome.CREATE,
        1,
        BASE_TIME);
  }

  private static DirectorySourceProjectionState unprojected(
      String version, Optional<Instant> updatedAt, boolean ordered, String fingerprint) {
    return new DirectorySourceProjectionState(
        InternalId.generate(),
        identity(),
        DirectoryConceptType.ORGANIZATION,
        Optional.empty(),
        PROFILE,
        Optional.of(version),
        updatedAt,
        ordered,
        fingerprint,
        true,
        DirectorySourceProjectionReconciliation.Outcome.RECONCILIATION_REQUIRED,
        1,
        BASE_TIME);
  }

  private static DirectorySourceProjection.OrganizationFact organization(
      String version,
      Optional<Instant> updatedAt,
      boolean ordered,
      String fingerprint,
      boolean active) {
    return new DirectorySourceProjection.OrganizationFact(
        new DirectorySourceProjection.Metadata(
            identity(), Optional.ofNullable(version), updatedAt, ordered, PROFILE, fingerprint),
        "Synthetic organization",
        Optional.empty(),
        active);
  }

  private static ExternalReference identity() {
    return new ExternalReference(new IssuerId(SOURCE), "Organization", "org-a");
  }

  private static String fingerprint(char first) {
    return first + "0".repeat(63);
  }
}
