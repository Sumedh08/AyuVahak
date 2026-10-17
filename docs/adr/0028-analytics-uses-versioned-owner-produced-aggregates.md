---
status: accepted
date: 2026-10-17
---

# ADR 0028: Analytics uses versioned owner-produced aggregates

## Context

Phase 12 requires reproducible operational indicators from Referral, Capacity, Transfer and Transport without cross-context private SQL, patient-level warehousing or synchronous coupling to operational transactions. The bounded-context map anticipated Analytics but did not define it as an implemented context.

## Decision

Introduce Analytics as a Volume IV bounded context that owns only derived, minimized, versioned aggregate measures/snapshots, completeness and disclosure decisions, mappings and export-operation records. Each source owner computes approved bounded reporting measures within its own persistence boundary and returns a scoped snapshot with source cutoff/version, interval, completeness and generation time. Analytics does not read private repositories/tables or reconstruct an alternate owner ledger from raw events. Analytics owns AyuVahak's calculation formula/version; the authorized public-health authority owns official indicator interpretation and target metadata. Where applicable, the approved external definition identifier/version is recorded as provenance.

Indicator versions are immutable and define formula, source measures, dimensions, period/timezone, missing semantics, attribution and privacy policy. Period snapshots are immutable; late facts and corrections create linked revisions. Missing or partial inputs are not zero. Sensitive output uses release-set disclosure control across related cells, totals, periods, revisions and prior releases; absent policy or history blocks the related release. DHIS2 and peer publication share a serialized Analytics disclosure reservation gate. An immutable disclosure lineage key covers outputs that could be recombined across destinations, channels, periods, revisions or dimensions; versioned policies cannot reset its history. Policy rollover locks the union of old/new lineages and preserves common history through merges/splits. The conservative default is one lineage per policy/release scope; narrower lineages require proven non-overlap, and incomplete mapping fails closed. In-flight and unknown outcomes count as potentially disclosed, preventing concurrent workers from bypassing complementary suppression. A durable per-target-tuple barrier also prevents a newer DHIS2 revision from overtaking an in-flight or unknown older operation. Optional peer aggregation requires an explicit Phase 9 peer Analytics operation, exact compatible definition semantics, provenance, completeness, deduplication and disclosure review. Owner workflows do not wait for analytics.

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
