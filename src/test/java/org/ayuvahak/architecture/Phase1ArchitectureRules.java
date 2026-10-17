package org.ayuvahak.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

final class Phase1ArchitectureRules {

  private static final String[] PROHIBITED_CORE_DEPENDENCIES = {
    "org.springframework..",
    "java.sql..",
    "javax.sql..",
    "javax.persistence..",
    "jakarta.persistence..",
    "org.hibernate..",
    "org.jooq..",
    "io.r2dbc..",
    "org.flywaydb..",
    "ca.uhn.fhir..",
    "org.hl7.fhir..",
    "org.dhis2..",
    "org.openmrs..",
    "org.openhie..",
    "org.openhim..",
    "org.keycloak..",
    "org.apache.kafka..",
    "org.springframework.kafka..",
    "redis.clients..",
    "org.redisson..",
    "org.elasticsearch..",
    "org.opensearch..",
    "org.neo4j..",
    "org.mongodb..",
    "com.mongodb..",
    "org.apache.cassandra..",
    "jakarta.servlet..",
    "javax.servlet..",
    "jakarta.ws.rs..",
    "javax.ws.rs..",
    "java.net.http..",
    "org.apache.http..",
    "okhttp3.."
  };

  private Phase1ArchitectureRules() {}

  static ArchRule productionPackagesArePlatformOnly() {
    return classes()
        .should()
        .resideInAnyPackage(
            "org.ayuvahak.platform",
            "org.ayuvahak.platform..",
            "org.ayuvahak.directory",
            "org.ayuvahak.directory..",
            "org.ayuvahak.capacity",
            "org.ayuvahak.capacity..",
            "org.ayuvahak.referral",
            "org.ayuvahak.referral..",
            "org.ayuvahak.matching",
            "org.ayuvahak.matching..",
            "org.ayuvahak.transfer",
            "org.ayuvahak.transfer..",
            "org.ayuvahak.transport",
            "org.ayuvahak.transport..")
        .as(
            "Production code contains only implemented platform concerns and the active Directory"
                + ", Capacity, Referral, Matching, Transfer, and Transport contexts; create later"
                + " healthcare contexts only when their phase begins");
  }

  static ArchRule packageSlicesAreAcyclic() {
    return slices()
        .matching("org.ayuvahak.(*)..")
        .should()
        .beFreeOfCycles()
        .as(
            "AyuVahak platform and business-context package slices must not depend on one"
                + " another cyclically");
  }

  static ArchRule applicationPackagesMustNotDependOnAdapters() {
    return noClasses()
        .that()
        .resideInAnyPackage("org.ayuvahak..application..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.ayuvahak..adapter..")
        .as("Application policies and contracts must not depend on outer adapter packages")
        .allowEmptyShould(true);
  }

  static ArchRule domainCodeMustNotDependOnFrameworks() {
    return noClasses()
        .that()
        .resideInAnyPackage("..domain", "..domain..", "org.ayuvahak.platform.core..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(PROHIBITED_CORE_DEPENDENCIES)
        .as(
            "Domain and platform core code must remain framework-independent and must not depend"
                + " on persistence, HTTP, healthcare integration, or prohibited infrastructure libraries")
        .allowEmptyShould(true);
  }

  static ArchRule fhirTypesMustRemainInApprovedAdapterPackages() {
    return noClasses()
        .that()
        .resideOutsideOfPackages(
            "org.ayuvahak.platform.fhir..",
            "org.ayuvahak.directory.adapter.mcsd..",
            "org.ayuvahak.referral.adapter.ips..",
            "org.ayuvahak.capacity.adapter.fhir..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("ca.uhn.fhir..", "org.hl7.fhir..")
        .as(
            "FHIR API/model types may appear only in the platform boundary or an approved"
                + " owner-context adapter; never in domains, application contracts, APIs, or persistence");
  }

  static ArchRule notificationHintsMustNotDependOnSseTypes() {
    return noClasses()
        .that()
        .resideInAnyPackage(
            "org.ayuvahak.platform.notification.domain..",
            "org.ayuvahak.platform.notification.application..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework.web.servlet.mvc.method.annotation..")
        .as(
            "Notification application contracts stay transport-neutral and do not depend on SSE framework types")
        .allowEmptyShould(true);
  }

  static ArchRule notificationHintsMustNotDependOnPatientReference() {
    return noClasses()
        .that()
        .resideInAnyPackage("org.ayuvahak.platform.notification..")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("org.ayuvahak.referral.domain.PatientReference")
        .as("Notification hints must never carry a patient reference")
        .allowEmptyShould(true);
  }

  static ArchRule coreCodeMustNotDependOnOuterLayers() {
    return noClasses()
        .that()
        .resideInAnyPackage("org.ayuvahak.platform.core..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.ayuvahak.platform.api..",
            "org.ayuvahak.platform.configuration..",
            "org.ayuvahak.platform.observability..",
            "org.ayuvahak.platform.persistence..")
        .as("Platform core values must not depend on outer platform adapters or infrastructure")
        .allowEmptyShould(true);
  }

  static ArchRule directoryMustNotDependOnOtherBusinessContexts() {
    return noClasses()
        .that()
        .resideInAnyPackage("org.ayuvahak.directory..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.ayuvahak.capacity..",
            "org.ayuvahak.referral..",
            "org.ayuvahak.matching..",
            "org.ayuvahak.transfer..",
            "org.ayuvahak.transport..",
            "org.ayuvahak.notifications..",
            "org.ayuvahak.analytics..")
        .as("Directory must not depend on another business bounded context")
        .allowEmptyShould(true);
  }

  static ArchRule capacityMustNotDependOnReferralMatchingOrTransfer() {
    return classes()
        .that()
        .resideInAnyPackage("org.ayuvahak.capacity..")
        .should(
            new ArchCondition<>(
                "not depend on CareCapability, Directory persistence, Referral, Matching, Transfer, or Transport") {
              @Override
              public void check(JavaClass item, ConditionEvents events) {
                for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                  JavaClass target = dependency.getTargetClass();
                  String targetPackage = target.getPackageName();
                  if (target.getName().equals("org.ayuvahak.directory.domain.CareCapability")
                      || targetPackage.startsWith("org.ayuvahak.directory.adapter.persistence")
                      || targetPackage.startsWith("org.ayuvahak.referral.")
                      || targetPackage.startsWith("org.ayuvahak.matching.")
                      || targetPackage.startsWith("org.ayuvahak.transfer.")
                      || targetPackage.startsWith("org.ayuvahak.transport.")) {
                    events.add(
                        SimpleConditionEvent.violated(
                            item,
                            item.getName()
                                + " depends on a prohibited Capacity dependency: "
                                + target.getName()));
                  }
                }
              }
            })
        .as(
            "Capacity must evaluate only explicit CapacityDimensions through owner contracts; it"
                + " must not translate CareCapabilities or depend on Referral, Matching, Transfer,"
                + " Transport, or Directory persistence")
        .allowEmptyShould(true);
  }

  static ArchRule referralMustNotDependOnMatchingOrTransfer() {
    return noClasses()
        .that()
        .resideInAnyPackage("org.ayuvahak.referral..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.ayuvahak.matching..", "org.ayuvahak.transfer..", "org.ayuvahak.transport..")
        .as("Referral must not depend on Matching, Transfer, or Transport implementation")
        .allowEmptyShould(true);
  }

  static ArchRule matchingDomainMustRemainIndependent() {
    return noClasses()
        .that()
        .resideInAnyPackage("org.ayuvahak.matching.domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.ayuvahak.directory..",
            "org.ayuvahak.capacity..",
            "org.ayuvahak.referral..",
            "org.ayuvahak.transfer..",
            "org.ayuvahak.transport..",
            "org.ayuvahak.notifications..",
            "org.ayuvahak.analytics..",
            "org.springframework..",
            "java.sql..",
            "javax.sql..",
            "org.hibernate..",
            "org.jooq..",
            "ca.uhn.fhir..",
            "org.hl7.fhir..",
            "org.springframework.ai..",
            "dev.langchain4j..",
            "org.tensorflow..",
            "ai.onnxruntime..",
            "org.deeplearning4j..",
            "org.pytorch..",
            "com.microsoft.onnxruntime..",
            "com.huggingface..",
            "com.google.cloud.vertexai..")
        .as(
            "Matching domain decisions must use Matching-owned evidence types and remain free of"
                + " business-context, framework, persistence, interoperability, and AI/ML"
                + " dependencies; owner projections are translated at the application boundary")
        .allowEmptyShould(true);
  }

  static ArchRule transportDomainMustRemainProviderNeutral() {
    return noClasses()
        .that()
        .resideInAnyPackage("org.ayuvahak.transport.domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.ayuvahak.directory..",
            "org.ayuvahak.capacity..",
            "org.ayuvahak.referral..",
            "org.ayuvahak.matching..",
            "org.ayuvahak.transfer..",
            "org.springframework..",
            "ca.uhn.fhir..",
            "org.hl7.fhir..",
            "org.apache.http..",
            "java.net.http..",
            "okhttp3..")
        .as(
            "Transport domain decisions must remain provider-neutral and depend only on"
                + " Transport-owned concepts and the platform core")
        .allowEmptyShould(true);
  }

  static ArchRule transportMustNotAccessTransferPersistencePort() {
    return classes()
        .that()
        .resideInAnyPackage("org.ayuvahak.transport..")
        .should(
            new ArchCondition<>("not depend on Transfer persistence ports or implementation") {
              @Override
              public void check(JavaClass item, ConditionEvents events) {
                for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                  JavaClass target = dependency.getTargetClass();
                  if (isTransferPersistenceType(target)) {
                    events.add(
                        SimpleConditionEvent.violated(
                            item,
                            item.getName()
                                + " depends on Transfer persistence instead of an owner contract: "
                                + target.getName()));
                  }
                }
              }
            })
        .as("Transport must use Transfer owner contracts, never Transfer's persistence ports")
        .allowEmptyShould(true);
  }

  private static boolean isTransferPersistenceType(JavaClass target) {
    String packageName = target.getPackageName();
    return packageName.startsWith("org.ayuvahak.transfer.adapter.persistence")
        || (packageName.equals("org.ayuvahak.transfer.application")
            && target.getSimpleName().endsWith("Store"));
  }

  static ArchRule businessContextsMustNotDependOnAnotherContextsPersistence() {
    return classes()
        .should(
            new ArchCondition<>(
                "not depend on another context's adapter.persistence implementation") {
              @Override
              public void check(JavaClass item, ConditionEvents events) {
                String sourceContext = contextName(item.getPackageName());
                for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                  String targetPackage = dependency.getTargetClass().getPackageName();
                  String targetContext = contextName(targetPackage);
                  if (sourceContext != null
                      && targetContext != null
                      && !sourceContext.equals(targetContext)
                      && isPersistencePackage(targetPackage)) {
                    events.add(
                        SimpleConditionEvent.violated(
                            item,
                            item.getName()
                                + " depends on another context's persistence implementation: "
                                + dependency.getTargetClass().getName()));
                  }
                }
              }
            })
        .as("A context must use another context's public application boundary")
        .allowEmptyShould(true);
  }

  private static String contextName(String packageName) {
    String prefix = "org.ayuvahak.";
    if (!packageName.startsWith(prefix)) {
      return null;
    }
    String suffix = packageName.substring(prefix.length());
    int nextPackage = suffix.indexOf('.');
    return nextPackage < 0 ? suffix : suffix.substring(0, nextPackage);
  }

  private static boolean isPersistencePackage(String packageName) {
    return packageName.contains(".adapter.persistence.")
        || packageName.endsWith(".adapter.persistence");
  }
}
