# ADR 0016: Destination recommendations are deterministic, policy-versioned evidence snapshots

- **Status:** Accepted
- **Date:** 2026-10-08
- **Decision owners:** AyuVahak architecture / product owner

## Context

ADR 0006 establishes advisory deterministic matching. Volume II needs to make the policy operationally precise without inventing clinical logic, opaque numeric weights, or a recommendation that outlives its evidence. A later reviewer must be able to reconstruct why a Facility was included, excluded, uncertain, and ordered at the time.

## Decision

Matching is a separate business context. It consumes exact submitted Referral revisions, Directory facts, and freshness-aware Capacity results through application contracts. Eligibility is hard-gated by required direct active capability evidence, configured geographic constraints, and current `READY` operational evidence. Unknown, stale, conflicted, or unavailable evidence is not rankable as confirmed. V1 ranking is lexicographic and deterministic, using the configured current travel-time metric or an explicitly labeled straight-line-distance fallback, optional authorized deployment preference, and a stable Facility ID tie-break. There are no ML/LLM decisions, opaque weights, or automatic destination selection.

Every evaluation persists an immutable, policy- and evidence-versioned structured snapshot with reasons, provenance references, timestamps, ranking inputs, and an applicability deadline. Material input changes can make it unusable for a new Transfer Request, but do not rewrite or mutate the historical snapshot. Current applicability is synchronously revalidated at use time under [ADR 0019](0019-synchronous-recommendation-applicability.md); no stored boolean or Directory/Capacity invalidation event is authoritative. Reevaluation creates a new linked snapshot. Matching's capability-to-operational-dimension mapping is separately owned and versioned under [ADR 0018](0018-matching-owns-capability-operational-mapping.md). The Recommendation is advisory and never Acceptance, Reservation, or clinical suitability.

## Consequences

- Phase 6 can explain its result and test identical inputs for identical ordering.
- No numeric universal ranking weights, travel radius, route ETA, or recommendation lifetime is asserted.
- The deployment must configure metric and evidence-age policy before live matching.
- Historical snapshots remain understandable without copying patient context or full external resource payloads.

## Alternatives considered

- **Weighted score:** rejected for V1 because weights would create unjustified clinical/operational tradeoffs and be difficult to explain.
- **Treat unknown evidence as low score:** rejected because it converts lack of knowledge into a ranking signal and can imply false suitability.
- **Overwrite a single current recommendation:** rejected because it destroys the evidence and policy basis of prior human decisions.
- **Automatically select the top result:** rejected because AyuVahak does not make clinical or destination decisions.

## References

- [Matching policy v1](../architecture/matching-policy-v1.md)
- [Volume II state machines](../architecture/volume-2-state-machines.md)
- [ADR 0006](0006-deterministic-advisory-matching.md)
