# ADR 0015: Capacity evidence and readiness are freshness-aware, source-attributed facts

- **Status:** Accepted
- **Date:** 2026-10-08
- **Decision owners:** AyuVahak architecture / product owner

## Context

The Capacity context must provide useful current operational evidence across manual, legacy, and integrated deployments. A single “available” boolean, last-message-wins projection, or universal source hierarchy would turn missing, stale, delayed, or conflicting observations into an unsafe positive fact. Capacity is not a physical bed ledger, and receiving readiness is not a stable facility capability.

## Decision

Capacity stores append-oriented, source-attributed observations with distinct observed, received, and validity times. Current state is a deterministic, reconstructable projection selected by explicit per-dimension, versioned deployment source/freshness policy. Equal-authority disagreement remains `CONFLICTED`; absence or unusable evidence remains `UNKNOWN`, `STALE`, or `EXPIRED`. No numeric freshness threshold or universal source ranking is hard-coded. Readiness is an explainable multi-state evaluation; only all-current passing evidence yields `READY`. Reservation is a later Capacity-owned coordination hold and must atomically account for current evidence and active holds.

## Consequences

- Phase 4 can be usable without a referral or matching context; Phase 6 consumes the contract without internal table reads.
- Manual input remains supported with actor/source provenance and need not be categorically less trusted.
- Unknown/stale/conflicted evidence cannot be turned into zero, available, or ready.
- Deployment policy must define semantics and source trust before enabling a dimension for live decisions.
- Reservation remains a Phase 7 extension of Capacity; it is not a Facility bed/admission guarantee.

## Alternatives considered

- **Latest received message wins:** rejected because delivery order does not establish the most recent real-world fact or authority.
- **One global source priority and freshness period:** rejected because source authority and operational update cadence differ by facility and dimension.
- **Store only current state:** rejected because it prevents reconstruction, provenance, correction history, and conflict explanation.
- **Boolean readiness:** rejected because stale, absent, and conflicting evidence are materially different from a negative observation.

## References

- [Capacity/readiness policy v1](../architecture/capacity-readiness-policy-v1.md)
- [Volume II context contracts](../architecture/volume-2-context-contracts.md)
- [Quality attributes](../architecture/quality-attributes.md)
