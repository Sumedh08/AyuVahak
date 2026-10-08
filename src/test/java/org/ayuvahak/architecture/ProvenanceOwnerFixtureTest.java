package org.ayuvahak.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import org.ayuvahak.architecture.fixtures.provenance.consumer.application.UnrelatedModuleAccessFixture;
import org.ayuvahak.architecture.fixtures.provenance.owner.adapter.persistence.OwnedFactStoreFixture;
import org.ayuvahak.architecture.fixtures.provenance.owner.domain.OwnedFactFixture;
import org.ayuvahak.platform.core.ActorReference;
import org.ayuvahak.platform.core.IssuerId;
import org.ayuvahak.platform.core.OccurredAt;
import org.ayuvahak.platform.core.Provenance;
import org.ayuvahak.platform.core.RecordedAt;
import org.ayuvahak.platform.core.SourceKind;
import org.junit.jupiter.api.Test;

class ProvenanceOwnerFixtureTest {

  @Test
  void owningFactRetainsProvenanceWithoutAPlaceForRawSourcePayload() {
    Provenance provenance =
        new Provenance(
            new IssuerId("manual-entry:facility-west"),
            Optional.empty(),
            ActorReference.human(new IssuerId("identity.example"), "synthetic-person-17"),
            SourceKind.INTERACTIVE_REQUEST,
            new OccurredAt(Instant.parse("2026-04-01T10:00:00Z")),
            new RecordedAt(Instant.parse("2026-04-01T10:00:00Z")),
            Optional.empty(),
            Provenance.Verification.notAssessed(),
            Optional.empty());
    OwnedFactFixture fact = new OwnedFactFixture("synthetic-fact-17", provenance);
    OwnedFactStoreFixture store = new OwnedFactStoreFixture();

    store.save(fact);

    assertThat(store.find()).contains(fact);
    assertThat(fact.provenance().verification().status())
        .isEqualTo(Provenance.Verification.Status.NOT_ASSESSED);
    assertThat(
            Arrays.stream(OwnedFactFixture.class.getRecordComponents())
                .map(component -> component.getName()))
        .containsExactly("factReference", "provenance")
        .doesNotContain("payload", "sourcePayload", "rawPayload");
    assertThat(provenance.toString())
        .doesNotContain("manual-entry:facility-west", "synthetic-person-17", "synthetic-fact-17");
  }

  @Test
  void architectureRuleDetectsAnotherContextReachingIntoOwnerPersistence() {
    JavaClasses fixtures =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importClasses(UnrelatedModuleAccessFixture.class, OwnedFactStoreFixture.class);

    EvaluationResult result = unrelatedContextsCannotAccessOwnerPersistence().evaluate(fixtures);

    assertThat(result.hasViolation()).isTrue();
    assertThat(result.getFailureReport().toString())
        .contains("UnrelatedModuleAccessFixture")
        .contains("OwnedFactStoreFixture");
  }

  private static ArchRule unrelatedContextsCannotAccessOwnerPersistence() {
    return noClasses()
        .that()
        .resideInAnyPackage("..provenance.consumer..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("..provenance.owner.adapter.persistence..")
        .as("A context may not reach into another context's persistence internals");
  }
}
