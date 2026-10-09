# Phase 4 Implementation Review — Capacity and Operational Readiness

**Result:** PASS; Phase 4 is ready to freeze locally.  
**Branch:** `phase4-capacity-readiness`  
**Design base:** Phase 3 local freeze `f83211062abbd444be8b830790df689cc0729237`  
**Architecture contract:** [Phase 4 specification](../specs/phase-4-capacity-readiness-spec.md) and [Capacity/readiness policy](../architecture/capacity-readiness-policy-v1.md).

## Work delivered

The human-approved four-slice graph was implemented sequentially: governed dimensions and policies (P4-01), immutable observation intake and correction (P4-02), current evidence and bounded history (P4-03), then readiness evaluation and phase acceptance (P4-04). The local ticket graph is in `.scratch/phase-4-capacity-readiness/`.

Capacity now owns typed dimensions and versioned policies, attributed immutable observations and correction history, deterministic current-evidence reconstruction, bounded current/history queries, and deterministic operational-readiness evaluation. Reads use a repeatable-read Capacity transaction over facts and selected policy. Ingestion stores the fact with required audit and idempotency state; it does not maintain a mutable projection. This preserves the separation between observation ingestion and query-time projection/reconciliation. The response projection version identifies the algorithm; dimension and policy versions identify the governing configuration.

The implementation uses the Phase 2 authorization, execution-scope, audit, and command-idempotency contracts. Facility validation crosses into Directory through its application boundary. Capacity domain code remains independent of Spring and persistence adapters. No source connector or polling job was added.

## Scope and architecture review

- P4-01 through P4-04 are integrated in the approved order. No slice was merged into another or split further.
- Capacity does not consume Referral or CareCapability, perform Matching, reserve resources, emit business events, or depend on another context's tables.
- Migration V12 creates the Capacity policy catalog and V13 creates observation/correction history, both under the Capacity-owned schema. No future business-context schema was added.
- Phase 4 introduces no Capacity business event or Capacity outbox intent. Existing Phase 2 outbox regression tests remain part of full verification.
- No Phase 0 product or architecture decision was changed. The policy/spec/context-contract edits record the implemented query-time reconstruction contract and resolve earlier wording that implied a stored projection.

## Review results

- **Standards review:** No documented-standard breach. One low-risk judgment-call note identified repeated execution-scope construction across Capacity HTTP controllers. It was left local to the adapters because the current repeated shape does not justify a new abstraction and no correctness issue was demonstrated.
- **Specification review:** No implementation scope creep or code defect found. The review identified projection persistence wording in the spec; the policy, Phase 4 spec, and context contract were corrected to describe query-time derivation and no mutable projection state.
- **Defect-first review:** No actionable correctness, security, performance, or maintainability finding remained after the targeted review and integrated verification.

## Versions and verification environment

- Eclipse Temurin JDK `25.0.4.1+1`.
- Spring Boot `4.1.1`.
- Maven Wrapper / Apache Maven `3.10.0`.
- ArchUnit `1.5.1`; Testcontainers `2.0.5`.
- Disposable integration database: PostgreSQL `18.6`, PostGIS `3.6.4`, image `postgis/postgis:18-3.6` pinned by digest in the repository.
- Local runtime: Docker Engine `29.3.1`; Docker Compose `v5.1.1`.

`.\mvnw.cmd -B -ntp clean verify` passed with 99 Surefire tests and 121 Failsafe integration tests; no failures, errors, or skips. Spotless passed. Integration tests used local disposable PostgreSQL/PostGIS and local synthetic identity fixtures.

The documented Compose path validated, built the application image, started the app and local database, applied all 13 migrations, and reached readiness. Health, liveness, readiness, `/api/v1/system-info`, and Prometheus returned HTTP 200. The Prometheus scrape included application-startup and JDBC-pool metrics. A direct local database query returned PostGIS `3.6.4` and Flyway version `13`. The smoke stack used its own Compose project and loopback ports, then its containers, network, and volume were removed.

The dependency-tree audit found no HAPI/FHIR model, DHIS2 client, Kafka, Redis, Elasticsearch/OpenSearch, Neo4j, Hibernate ORM/JPA, jOOQ, or AI/LLM dependency. Spring Security and Hibernate Validator are present from the already-frozen Phase 2 security and validation foundation; Phase 4 did not change dependencies.

## Limitations and follow-up

Capacity values, source priority, freshness, and clock-skew thresholds remain deployment configuration because the repository has no source characteristics or jurisdiction from which to justify universal numbers. Query-time reconstruction is the initial design; a materialized projection may be considered only after measured query cost demonstrates a need and it preserves the same evidence semantics.

There is no Capacity Reservation, referral, recommendation, transfer, transport, external source connector, patient-context persistence, or clinical decision behavior. Those remain in later phases under their approved contracts. Phase 5 is authorized to begin only after this local Phase 4 freeze.
