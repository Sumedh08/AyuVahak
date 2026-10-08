# ADR 0019: Recommendation applicability is synchronously checked at use time

- **Status:** Accepted
- **Date:** 2026-10-08
- **Decision owners:** AyuVahak architecture / product owner

## Context

A recommendation is an immutable explanation of a past evaluation. Directory, Capacity, Referral, and policy facts may change before a coordinator requests a destination. A stored `current` flag driven by change events would either become stale when an event is missed or create broad event fan-out and coupling. A maximum-age deadline alone cannot prove the underlying facts remain valid.

## Decision

Matching retains each Recommendation Snapshot as immutable historical evidence and exposes a targeted synchronous applicability revalidation for the exact snapshot, candidate Facility, and Referral revision. Before creating any Transfer Request, Transfer calls this contract. It returns `APPLICABLE`, `NOT_APPLICABLE`, or `INDETERMINATE` with reasons.

Revalidation checks the exact Referral revision remains current/submitted and not cancelled/resolved; the Facility remains active; required direct capability assertions remain active/effective/verified; every mapped CapacityDimension has fresh `READY` evidence under current policy; mapping/recommendation policy versions remain permitted; and configured age/evidence deadlines have not elapsed. A changed or expired fact is `NOT_APPLICABLE`; unavailable or ambiguous owner evidence is `INDETERMINATE`. Both block request creation. `GetCurrentRecommendation` returns the latest historical snapshot plus a fresh applicability result, never a cached truth flag.

The operation reads only the candidate and owner facts referenced by the snapshot. It does not require invalidation events or a distributed transaction. Applicability is a point-in-time evidence check, not an Acceptance, Capacity reservation, or guarantee; downstream owners recheck their own state.

## Consequences

- Historical recommendations remain auditable and explainable after becoming unusable.
- Transfer has one explicit synchronous guard before a request; stale or unknown inputs cannot produce a new request.
- Query unavailability has a safe negative outcome and may reduce availability of new requests without changing historical records.
- No Directory/Capacity event fan-out or mutable applicability projection is needed.

## Alternatives considered

- **Persist a current boolean and invalidate it through events:** rejected because missed/delayed events can leave false current state and broaden coupling.
- **Trust `applicableUntil` alone:** rejected because policy deadlines do not detect material changes before the deadline.
- **Revalidate every owner state in Transfer:** rejected because Matching owns recommendation evidence and the applicability interpretation; Transfer uses one contract.
- **Make revalidation and request creation one distributed transaction:** rejected because contexts own separate facts and no cross-context transaction is available or justified.

## References

- [Matching policy v1](../architecture/matching-policy-v1.md)
- [Volume II context contracts](../architecture/volume-2-context-contracts.md)
- [Phase 6 specification](../specs/phase-6-matching-recommendation-spec.md)
- [Phase 7 specification](../specs/phase-7-transfer-coordination-spec.md)
- [ADR 0016](0016-deterministic-recommendation-snapshots.md)
