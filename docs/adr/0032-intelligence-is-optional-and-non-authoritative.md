---
status: accepted
date: 2026-10-20
---

# ADR 0032: Intelligence is optional and non-authoritative

## Context

Phase 14 evaluates performance and optional advisory capabilities. Care coordination depends on owner-controlled state, deterministic Matching and scoped human authority. Model output can be unavailable, incorrect, biased, manipulated or nondeterministic.

## Decision

Intelligence is not a new bounded context and is disabled by default. The only initial evaluation candidate is documentation/support assistance over approved, versioned, non-sensitive project documentation, invoked by finite allowlisted topic/question keys rather than free-form user text; it may not receive case-specific, patient or clinical input and has no tool or mutation authority. Any output is untrusted, bounded, source-linked, explicitly labeled advisory, rejectable and non-blocking. Unsupported, stale, unsafe or malformed output abstains and falls back to ordinary documentation search. External providers are optional and administrator-configured through a provider-neutral port; local acceptance uses a deterministic fake. Any future use case affecting coordination choices requires separate approval, shadow evaluation and a use-case-specific risk/evaluation record.

Models cannot control clinical suitability, Matching authority, Capacity, Acceptance, reservation, Transfer/Transport, dispatch, identity, grants, privacy, audit, DHIS2 corrections, federation, or patient-context disclosure. No production interaction is used for implicit training.

## Consequences

- The core service remains complete when intelligence is off or unavailable.
- Model libraries and types stay outside owner domains and aggregates.
- No model explanation replaces deterministic evidence, and no confidence value implies safety.
- Provider, model, prompt, corpus and purpose changes require evaluation and approval.
- Clinical-purpose expansion requires a separate jurisdiction-specific review and authorization.

## Alternatives considered

- Automatically ranking destinations with ML was rejected because it would introduce model authority into safety-critical coordination.
- A mandatory external LLM was rejected because it creates availability, privacy and portability dependencies.
- A new AI bounded context/MLOps platform was rejected because no approved use case needs it.
- “Human in the loop” as the sole mitigation was rejected: review must be visible, informed, rejectable and not preceded by a hidden mutation.

## References

- [Volume V architecture](../architecture/volume-5-production-hardening-optimization-intelligence.md)
- [Phase 14 specification](../specs/phase-14-optimization-intelligence-spec.md)
- [AI governance research](../research/volume-5-ai-governance-research.md)
- [ADR 0006 — Deterministic advisory Matching](0006-deterministic-advisory-matching.md)
