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
            "org.ayuvahak.capacity..")
        .as(
            "Production code contains only implemented platform concerns and the active Directory"
                + " and Capacity contexts; create later healthcare contexts only when their phase begins");
  }

  static ArchRule packageSlicesAreAcyclic() {
    return slices()
        .matching("org.ayuvahak.(*)..")
        .should()
        .beFreeOfCycles()
        .as(
            "AyuVahak platform and future business-context package slices must not depend on one"
                + " another cyclically");
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
