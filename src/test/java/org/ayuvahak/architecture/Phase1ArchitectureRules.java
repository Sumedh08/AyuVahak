package org.ayuvahak.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.lang.ArchRule;

final class Phase1ArchitectureRules {

  private Phase1ArchitectureRules() {}

  static ArchRule productionPackagesArePlatformOnly() {
    return classes()
        .should()
        .resideInAnyPackage("org.ayuvahak.platform", "org.ayuvahak.platform..")
        .as(
            "Phase 1 production code contains only implemented platform technical concerns;"
                + " create healthcare bounded contexts only when their owning phase begins");
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
        .resideInAnyPackage("..domain", "..domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework..",
            "java.sql..",
            "javax.sql..",
            "javax.persistence..",
            "jakarta.persistence..",
            "org.hibernate..",
            "org.jooq..",
            "io.r2dbc..",
            "ca.uhn.fhir..",
            "org.hl7.fhir..",
            "org.dhis2..",
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
            "org.apache.cassandra..")
        .as(
            "Domain code must remain framework-independent and must not depend on persistence,"
                + " healthcare integration, or prohibited infrastructure libraries")
        .allowEmptyShould(true);
  }
}
