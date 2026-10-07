---
status: accepted
---

**Amended:** 2026-10-08. The Phase 1 baseline is Java 25 LTS, Spring Boot 4.1.x, Maven, Flyway Community with versioned SQL, and Spring JDBC with explicit SQL. See [Phase 1 Engineering Decisions](../architecture/phase-1-engineering-decisions.md) for comparison, current-source facts, and recheck conditions. PostgreSQL/PostGIS remains the persistence direction. This amendment does not authorize implementation or schema creation.

# Use Java and Spring for the transactional core with PostgreSQL/PostGIS

The transactional coordination core uses the Java/Spring ecosystem, PostgreSQL as primary persistence, and PostGIS for facility/location spatial queries. Python may serve later analytics or optimization work but is not the primary owner of referral/transfer transactions. Phase 1 uses Java 25 LTS, Spring Boot 4.1.x, Maven, Flyway Community, and Spring JDBC, subject to the support/version checks in the engineering baseline. This ADR does not authorize scaffolding or a database schema.
