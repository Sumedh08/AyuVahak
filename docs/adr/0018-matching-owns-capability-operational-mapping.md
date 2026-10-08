# ADR 0018: Matching owns the versioned capability-to-operational-dimension mapping

- **Status:** Accepted
- **Date:** 2026-10-08
- **Decision owners:** AyuVahak architecture / product owner

## Context

Referral expresses what care capabilities are required. Directory records direct facility capability assertions. Capacity evaluates typed operational dimensions from current evidence. Without a separate mapping owner, an implementation could put operational assumptions into Referral, make Capacity understand clinical/capability vocabulary, or make Directory assert live readiness. The same CareCapability may require multiple operational dimensions, and one dimension may support more than one capability.

## Decision

Matching alone owns a versioned `Capability Operational Requirement Policy` mapping each required CareCapability to a non-empty set of CapacityDimensions. It resolves the union of required dimensions and deduplicates shared dimensions before calling Capacity's generic readiness contract. Referral stores no resolved CapacityDimension mapping; Directory stores only direct capability assertions; Capacity never infers dimensions from CareCapability; callers cannot supply or override mappings.

A missing or empty mapping prevents a positive recommendation and yields `INDETERMINATE` with a stable missing-mapping reason. A policy change creates a new version. Recommendation snapshots retain both the mapping-policy version and the resolved mapping used; later changes never rewrite historical snapshots. Initial mapping values are deployment policy inputs, not universal clinical truth.

## Consequences

- Domain language remains separate: CareCapability states the required service capability; CapacityDimension names an operational quantity/readiness dimension.
- Matching explains how requirements translate into readiness evidence without coupling Capacity to Referral or clinical terminology.
- Phase 6 must validate complete mapping coverage and test shared/multiple dimension mappings and missing-policy behavior.

## Alternatives considered

- **Referral owns the mapping:** rejected because it would make each care request carry policy-derived operational details and complicate policy evolution.
- **Capacity owns the mapping:** rejected because Capacity should evaluate generic dimensions, not interpret Referral capability semantics.
- **Directory capability assertions imply capacity dimensions:** rejected because capability evidence does not establish current readiness.
- **Caller supplies dimensions per request:** rejected because an untrusted or inconsistent caller could bypass the centrally versioned deterministic policy.

## References

- [Matching policy v1](../architecture/matching-policy-v1.md)
- [Volume II context contracts](../architecture/volume-2-context-contracts.md)
- [Phase 6 specification](../specs/phase-6-matching-recommendation-spec.md)
- [ADR 0015](0015-capacity-evidence-and-readiness.md)
- [ADR 0016](0016-deterministic-recommendation-snapshots.md)
