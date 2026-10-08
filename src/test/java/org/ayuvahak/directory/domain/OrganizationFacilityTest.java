package org.ayuvahak.directory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.ExternalReference;
import org.ayuvahak.platform.core.InternalId;
import org.ayuvahak.platform.core.IssuerId;
import org.junit.jupiter.api.Test;

class OrganizationFacilityTest {

  private static final Instant NOW = Instant.parse("2026-10-08T09:00:00Z");
  private static final IssuerId SOURCE_ISSUER = new IssuerId("urn:synthetic:facility-registry");
  private static final ActorReference ACTOR =
      ActorReference.human(new IssuerId("https://identity.example.test"), "synthetic-user-01");
  private static final DirectorySource ORGANIZATION_SOURCE =
      new DirectorySource(new ExternalReference(SOURCE_ISSUER, "Organization", "org-001"));
  private static final DirectorySource FACILITY_SOURCE =
      new DirectorySource(new ExternalReference(SOURCE_ISSUER, "Facility", "facility-001"));

  @Test
  void organizationCorrectionPreservesIdentityAndAdvancesVersion() {
    InternalId id = InternalId.generate();
    Organization organization =
        new Organization(
            id,
            "Regional Coordination",
            Optional.empty(),
            ORGANIZATION_SOURCE,
            provenance("INITIAL_ENTRY"),
            DirectoryLifecycleStatus.ACTIVE,
            1);

    Organization corrected =
        organization.correct("Regional Care Coordination", provenance("CORRECTION_01"));

    assertThat(corrected.id()).isEqualTo(id);
    assertThat(corrected.name()).isEqualTo("Regional Care Coordination");
    assertThat(corrected.source()).isEqualTo(ORGANIZATION_SOURCE);
    assertThat(corrected.version()).isEqualTo(2);
    assertThat(corrected.provenance().reasonCode()).isEqualTo("CORRECTION_01");
  }

  @Test
  void facilityDeactivationPreservesTheFactAndCannotBeRepeated() {
    Facility facility =
        new Facility(
            InternalId.generate(),
            InternalId.generate(),
            "Central Hospital",
            FACILITY_SOURCE,
            provenance("INITIAL_ENTRY"),
            DirectoryLifecycleStatus.ACTIVE,
            1);

    Facility inactive = facility.deactivate(provenance("RETIRED"));

    assertThat(inactive.id()).isEqualTo(facility.id());
    assertThat(inactive.managingOrganizationId()).isEqualTo(facility.managingOrganizationId());
    assertThat(inactive.name()).isEqualTo(facility.name());
    assertThat(inactive.status()).isEqualTo(DirectoryLifecycleStatus.INACTIVE);
    assertThat(inactive.version()).isEqualTo(2);
    assertThatThrownBy(() -> inactive.deactivate(provenance("RETIRED_AGAIN")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void recordsRejectUntrimmedNamesAndNonpositiveVersions() {
    assertThatThrownBy(
            () ->
                new Organization(
                    InternalId.generate(),
                    " Regional Coordination ",
                    Optional.empty(),
                    ORGANIZATION_SOURCE,
                    provenance("INITIAL_ENTRY"),
                    DirectoryLifecycleStatus.ACTIVE,
                    1))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                new Facility(
                    InternalId.generate(),
                    InternalId.generate(),
                    "Central Hospital",
                    FACILITY_SOURCE,
                    provenance("INITIAL_ENTRY"),
                    DirectoryLifecycleStatus.ACTIVE,
                    0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static DirectoryProvenance provenance(String reasonCode) {
    return new DirectoryProvenance("MANUAL_REVIEW", NOW, ACTOR, NOW, reasonCode);
  }
}
