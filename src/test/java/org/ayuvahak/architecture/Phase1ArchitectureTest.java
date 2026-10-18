package org.ayuvahak.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.EvaluationResult;
import java.nio.file.Path;
import org.ayuvahak.architecture.fixtures.domain.SpringDependentDomainFixture;
import org.ayuvahak.capacity.application.CapacityPersistenceAccessFixture;
import org.ayuvahak.capacity.domain.fixture.CareCapabilityCouplingFixture;
import org.ayuvahak.directory.adapter.persistence.DirectoryStoreFixture;
import org.ayuvahak.platform.core.fixtures.forbidden.SpringDependentCoreFixture;
import org.junit.jupiter.api.Test;

class Phase1ArchitectureTest {

  @Test
  void productionPackagesContainOnlyPlatformTechnicalConcerns() {
    JavaClasses productionClasses =
        new ClassFileImporter().importPath(Path.of("target", "classes"));

    assertThat(productionClasses).isNotEmpty();
    Phase1ArchitectureRules.productionPackagesArePlatformOnly().check(productionClasses);
    Phase1ArchitectureRules.applicationPackagesMustNotDependOnAdapters().check(productionClasses);
    Phase1ArchitectureRules.fhirTypesMustRemainInApprovedAdapterPackages().check(productionClasses);
    Phase1ArchitectureRules.notificationHintsMustNotDependOnSseTypes().check(productionClasses);
    Phase1ArchitectureRules.notificationHintsMustNotDependOnPatientReference()
        .check(productionClasses);
    Phase1ArchitectureRules.domainCodeMustNotDependOnFrameworks().check(productionClasses);
    Phase1ArchitectureRules.directoryMustNotDependOnOtherBusinessContexts()
        .check(productionClasses);
    Phase1ArchitectureRules.businessContextsMustNotDependOnAnotherContextsPersistence()
        .check(productionClasses);
    Phase1ArchitectureRules.capacityMustNotDependOnReferralMatchingOrTransfer()
        .check(productionClasses);
    Phase1ArchitectureRules.referralMustNotDependOnMatchingOrTransfer().check(productionClasses);
    Phase1ArchitectureRules.matchingDomainMustRemainIndependent().check(productionClasses);
    Phase1ArchitectureRules.transportDomainMustRemainProviderNeutral().check(productionClasses);
    Phase1ArchitectureRules.transportMustNotAccessTransferPersistencePort()
        .check(productionClasses);
  }

  @Test
  void productionPackageSlicesAreAcyclic() {
    JavaClasses productionClasses =
        new ClassFileImporter().importPath(Path.of("target", "classes"));

    assertThat(productionClasses).isNotEmpty();
    Phase1ArchitectureRules.packageSlicesAreAcyclic().check(productionClasses);
  }

  @Test
  void applicationBoundaryRuleDetectsAdapterDependencyInTestFixture() {
    JavaClasses fixtureClasses =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importClasses(
                org.ayuvahak.architecture.fixtures.application.AdapterConfigurationDependencyFixture
                    .class,
                org.ayuvahak.directory.adapter.persistence.DirectoryStoreFixture.class);

    EvaluationResult result =
        Phase1ArchitectureRules.applicationPackagesMustNotDependOnAdapters()
            .evaluate(fixtureClasses);

    assertThat(result.hasViolation()).isTrue();
    assertThat(result.getFailureReport().toString())
        .contains("Application policies and contracts must not depend on outer adapter packages")
        .contains("org.ayuvahak.directory.adapter.persistence.DirectoryStoreFixture");
  }

  @Test
  void activeDirectoryAndMatchingDomainsArePresentAndFrameworkIndependent() {
    JavaClasses productionClasses =
        new ClassFileImporter().importPath(Path.of("target", "classes"));

    assertThat(productionClasses.get("org.ayuvahak.directory.domain.DirectoryAuthorities"))
        .isNotNull();
    assertThat(productionClasses.get("org.ayuvahak.matching.domain.MatchingAuthorities"))
        .isNotNull();
    assertThat(
            productionClasses.get("org.ayuvahak.matching.application.MatchingOwnerEvidenceReader"))
        .isNotNull();
    assertThat(productionClasses.get("org.ayuvahak.transport.domain.TransportRequest")).isNotNull();
    Phase1ArchitectureRules.productionPackagesArePlatformOnly().check(productionClasses);
    Phase1ArchitectureRules.domainCodeMustNotDependOnFrameworks().check(productionClasses);
    Phase1ArchitectureRules.directoryMustNotDependOnOtherBusinessContexts()
        .check(productionClasses);
    Phase1ArchitectureRules.businessContextsMustNotDependOnAnotherContextsPersistence()
        .check(productionClasses);
    Phase1ArchitectureRules.capacityMustNotDependOnReferralMatchingOrTransfer()
        .check(productionClasses);
    Phase1ArchitectureRules.matchingDomainMustRemainIndependent().check(productionClasses);
    Phase1ArchitectureRules.transportDomainMustRemainProviderNeutral().check(productionClasses);
    Phase1ArchitectureRules.transportMustNotAccessTransferPersistencePort()
        .check(productionClasses);
  }

  @Test
  void activeReferralBoundariesArePresentAndFrameworkIndependent() {
    JavaClasses productionClasses =
        new ClassFileImporter().importPath(Path.of("target", "classes"));

    assertThat(productionClasses.get("org.ayuvahak.referral.domain.ReferralState")).isNotNull();
    assertThat(productionClasses.get("org.ayuvahak.referral.domain.ReferralAuthorities"))
        .isNotNull();
    assertThat(productionClasses.get("org.ayuvahak.referral.application.ReferralDraftCommands"))
        .isNotNull();
    assertThat(productionClasses.get("org.ayuvahak.referral.application.ReferralQueries"))
        .isNotNull();
    assertThat(
            productionClasses.get(
                "org.ayuvahak.referral.adapter.persistence.JdbcReferralDraftStore"))
        .isNotNull();
    assertThat(productionClasses.get("org.ayuvahak.referral.adapter.api.ReferralDraftController"))
        .isNotNull();

    Phase1ArchitectureRules.domainCodeMustNotDependOnFrameworks().check(productionClasses);
    Phase1ArchitectureRules.businessContextsMustNotDependOnAnotherContextsPersistence()
        .check(productionClasses);
    Phase1ArchitectureRules.referralMustNotDependOnMatchingOrTransfer().check(productionClasses);
  }

  @Test
  void domainBoundaryRuleDetectsSpringDependencyInTestFixture() {
    JavaClasses fixtureClasses =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importClasses(SpringDependentDomainFixture.class);

    EvaluationResult result =
        Phase1ArchitectureRules.domainCodeMustNotDependOnFrameworks().evaluate(fixtureClasses);

    assertThat(result.hasViolation()).isTrue();
    assertThat(result.getFailureReport().toString())
        .contains("Domain and platform core code must remain framework-independent")
        .contains("org.springframework.context.ApplicationContext");
  }

  @Test
  void compiledCoreValuesRemainIndependentOfFrameworksAndOuterPlatformLayers() {
    JavaClasses productionClasses =
        new ClassFileImporter().importPath(Path.of("target", "classes"));

    assertThat(productionClasses.get("org.ayuvahak.platform.core.InternalId")).isNotNull();
    Phase1ArchitectureRules.domainCodeMustNotDependOnFrameworks().check(productionClasses);
    Phase1ArchitectureRules.coreCodeMustNotDependOnOuterLayers().check(productionClasses);
  }

  @Test
  void coreBoundaryRulesDetectFrameworkPersistenceAndHttpDependenciesInTestFixture() {
    JavaClasses fixtureClasses =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importClasses(SpringDependentCoreFixture.class);

    EvaluationResult result =
        Phase1ArchitectureRules.domainCodeMustNotDependOnFrameworks().evaluate(fixtureClasses);

    assertThat(result.hasViolation()).isTrue();
    assertThat(result.getFailureReport().toString())
        .contains("Domain and platform core code must remain framework-independent")
        .contains("org.springframework.context.ApplicationContext")
        .contains("java.sql.Connection")
        .contains("java.net.http.HttpClient");
  }

  @Test
  void crossContextPersistenceRuleDetectsAProhibitedTestFixtureDependency() {
    JavaClasses fixtureClasses =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importClasses(CapacityPersistenceAccessFixture.class, DirectoryStoreFixture.class);

    EvaluationResult result =
        Phase1ArchitectureRules.businessContextsMustNotDependOnAnotherContextsPersistence()
            .evaluate(fixtureClasses);

    assertThat(result.hasViolation()).isTrue();
    assertThat(result.getFailureReport().toString())
        .contains("A context must use another context's public application boundary")
        .contains("org.ayuvahak.directory.adapter.persistence.DirectoryStoreFixture");
  }

  @Test
  void transportOwnerBoundaryRuleDetectsTransferPersistencePortAccessInTestFixture() {
    JavaClasses fixtureClasses =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importClasses(
                org.ayuvahak.transport.adapter.fixture.TransferPersistenceAccessFixture.class);

    EvaluationResult result =
        Phase1ArchitectureRules.transportMustNotAccessTransferPersistencePort()
            .evaluate(fixtureClasses);

    assertThat(result.hasViolation()).isTrue();
    assertThat(result.getFailureReport().toString())
        .contains("Transport must use Transfer owner contracts")
        .contains("org.ayuvahak.transfer.application.TransferStore");

    JavaClasses viewStoreFixtureClasses =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importClasses(
                org.ayuvahak.transport.adapter.fixture.TransferViewPersistenceAccessFixture.class);
    EvaluationResult viewStoreResult =
        Phase1ArchitectureRules.transportMustNotAccessTransferPersistencePort()
            .evaluate(viewStoreFixtureClasses);

    assertThat(viewStoreResult.hasViolation()).isTrue();
    assertThat(viewStoreResult.getFailureReport().toString())
        .contains("Transport must use Transfer owner contracts")
        .contains("org.ayuvahak.transfer.application.TransferViewStore");
  }

  @Test
  void capacityBoundaryRuleDetectsCareCapabilityCouplingInTestFixture() {
    JavaClasses fixtureClasses =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importClasses(CareCapabilityCouplingFixture.class);

    EvaluationResult result =
        Phase1ArchitectureRules.capacityMustNotDependOnReferralMatchingOrTransfer()
            .evaluate(fixtureClasses);

    assertThat(result.hasViolation()).isTrue();
    assertThat(result.getFailureReport().toString())
        .contains("Capacity must evaluate only explicit CapacityDimensions")
        .contains("org.ayuvahak.directory.domain.CareCapability");
  }

  @Test
  void matchingDomainBoundaryRuleDetectsOwnerTypeCouplingInTestFixture() {
    JavaClasses fixtureClasses =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importClasses(
                org.ayuvahak.matching.domain.fixture.DirectoryCoupledMatchingFixture.class);

    EvaluationResult result =
        Phase1ArchitectureRules.matchingDomainMustRemainIndependent().evaluate(fixtureClasses);

    assertThat(result.hasViolation()).isTrue();
    assertThat(result.getFailureReport().toString())
        .contains("Matching domain decisions must use Matching-owned evidence types")
        .contains("org.ayuvahak.directory.domain.DirectoryAuthorities");
  }

  @Test
  void matchingDomainBoundaryRuleDetectsFrameworkAndPersistenceDependenciesInTestFixture() {
    JavaClasses fixtureClasses =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importClasses(
                org.ayuvahak.matching.domain.fixture.InfrastructureCoupledMatchingFixture.class);

    EvaluationResult result =
        Phase1ArchitectureRules.matchingDomainMustRemainIndependent().evaluate(fixtureClasses);

    assertThat(result.hasViolation()).isTrue();
    assertThat(result.getFailureReport().toString())
        .contains("Matching domain decisions must use Matching-owned evidence types")
        .contains("org.springframework.context.ApplicationContext")
        .contains("java.sql.Connection");
  }

  @Test
  void fhirBoundaryRuleDetectsAResourceTypeInADomainFixture() {
    JavaClasses fixtureClasses =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importClasses(
                org.ayuvahak.architecture.fixtures.domain.FhirDependentDomainFixture.class);

    EvaluationResult result =
        Phase1ArchitectureRules.fhirTypesMustRemainInApprovedAdapterPackages()
            .evaluate(fixtureClasses);

    assertThat(result.hasViolation()).isTrue();
    assertThat(result.getFailureReport().toString())
        .contains("FHIR API/model types may appear only in the platform boundary")
        .contains("org.ayuvahak.architecture.fixtures.domain.FhirDependentDomainFixture");
  }
}
