---
status: accepted
date: 2026-10-17
---

# ADR 0028: Analytics uses versioned owner-produced aggregates

## Context

Phase 12 requires reproducible operational indicators from Referral, Capacity, Transfer and Transport without cross-context private SQL, patient-level warehousing or synchronous coupling to operational transactions. The bounded-context map anticipated Analytics but did not define it as an implemented context.

## Decision

Introduce Analytics as a Volume IV bounded context that owns only derived, minimized, versioned aggregate measures/snapshots, completeness and suppression decisions, mappings and export-operation records. Each source owner computes approved bounded reporting measures within its own persistence boundary and returns a scoped snapshot with source cutoff/version, interval, completeness and generation time. Analytics does not read private repositories/tables or reconstruct an alternate owner ledger from raw events.

Indicator versions are immutable and define formula, source measures, dimensions, period/timezone, missing semantics, attribution and privacy policy. Period snapshots are immutable; late facts and corrections create linked revisions. Missing or partial inputs are not zero. Aggregate privacy requires configured suppression/coarsening where a definition marks cells sensitive; absent required policy blocks export. Owner workflows do not wait for analytics.

## Consequences

- Analytics cannot change Directory, Capacity, Referral, Matching, Transfer or Transport truth.
- Cross-context joins and patient/case identifiers are excluded from the reporting interface.
- Cross-deployment aggregate combination requires compatible versions and explicit attribution, source provenance and completeness.
- Historical snapshots remain explainable even after definitions or mappings change.

## Alternatives considered

- Cross-schema analytical SQL was rejected because it bypasses owner contracts and increases coupling.
- A generic BI/data-lake context was rejected as unnecessary and outside product scope.
- Treating every source event as a reporting event was rejected; only approved minimized measures are exposed.
- Synchronous computation in business commands was rejected because reporting delay must not block care coordination.

## References

- [Volume IV architecture](../architecture/volume-4-resilient-operations-federation-public-health.md)
- [Phase 12 specification](../specs/phase-12-public-health-analytics-dhis2-spec.md)
- [Data ownership](../architecture/volume-4-data-ownership.md)
- [Context contracts](../architecture/volume-4-context-contracts.md)
