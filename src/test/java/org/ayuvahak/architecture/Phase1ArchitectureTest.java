package org.ayuvahak.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.EvaluationResult;
import java.nio.file.Path;
import org.ayuvahak.architecture.fixtures.domain.SpringDependentDomainFixture;
import org.junit.jupiter.api.Test;

class Phase1ArchitectureTest {

  @Test
  void productionPackagesContainOnlyPlatformTechnicalConcerns() {
    JavaClasses productionClasses =
        new ClassFileImporter().importPath(Path.of("target", "classes"));

    assertThat(productionClasses).isNotEmpty();
    Phase1ArchitectureRules.productionPackagesArePlatformOnly().check(productionClasses);
    Phase1ArchitectureRules.domainCodeMustNotDependOnFrameworks().check(productionClasses);
  }

  @Test
  void productionPackageSlicesAreAcyclic() {
    JavaClasses productionClasses =
        new ClassFileImporter().importPath(Path.of("target", "classes"));

    assertThat(productionClasses).isNotEmpty();
    Phase1ArchitectureRules.packageSlicesAreAcyclic().check(productionClasses);
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
        .contains("Domain code must remain framework-independent")
        .contains("org.springframework.context.ApplicationContext");
  }
}
