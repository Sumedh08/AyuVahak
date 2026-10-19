# Architecture Documentation

Start with the [architecture constitution](constitution.md) for product boundaries and system-wide rules. Use the volume documents below for phase-specific ownership, contracts and acceptance design.

## Frozen volumes

- [Volume II — Core Care Coordination](volume-2-core-care-coordination.md): Directory, Capacity, Referral, Matching and Transfer.
- [Volume III — Interoperability, Secure Exchange and Transport](volume-3-interoperability-security-transport.md): frozen design and implementations for Phases 8–10.

## Volume IV — complete and frozen

- [Volume IV architecture](volume-4-resilient-operations-federation-public-health.md): Phase 11 resilience/federation and Phase 12 aggregate analytics/DHIS2 boundaries.
- [Context contracts](volume-4-context-contracts.md)
- [State machines](volume-4-state-machines.md)
- [Data ownership](volume-4-data-ownership.md)
- [Privacy and disclosure matrix](volume-4-privacy-matrix.md)
- [Threat and failure model](volume-4-threat-model.md)
- [Phase 11 specification](../specs/phase-11-real-time-resilience-federation-spec.md)
- [Phase 11 acceptance](../reviews/phase-11-acceptance.md)
- [Phase 12 specification](../specs/phase-12-public-health-analytics-dhis2-spec.md)
- [Phase 12 acceptance](../reviews/phase-12-acceptance.md)
- [Volume IV integrated acceptance](../reviews/volume-4-acceptance.md)
- [Official technical research](../research/volume-4-technical-research.md)
- [Design review](../reviews/volume-4-design-review.md)
- [Resolved design-question register](../reviews/volume-4-open-questions.md)

## Volume V — design review; implementation not started

- [Volume V architecture](volume-5-production-hardening-optimization-intelligence.md): production hardening, DPG evidence, measured optimization and optional non-authoritative intelligence.
- [Phase 13 specification](../specs/phase-13-production-hardening-dpg-readiness-spec.md)
- [Phase 14 specification](../specs/phase-14-optimization-intelligence-spec.md)
- [Production readiness gap analysis](../reviews/production-readiness-gap-analysis.md)
- [DPG readiness gap analysis](../reviews/dpg-readiness-gap-analysis.md)
- [Volume V threat model](volume-5-threat-model.md)
- [Volume V design review](../reviews/volume-5-design-review.md)
- [Volume V question disposition](../reviews/volume-5-open-questions.md)
- [Official research: production/security/supply chain](../research/volume-5-production-security-supply-chain-research.md), [DPG Standard](../research/volume-5-dpg-standard-research.md), and [AI governance](../research/volume-5-ai-governance-research.md)

Root [AGENTS.md](../../AGENTS.md) is authoritative for program state and execution authorization. Volumes I–IV are implemented and frozen. Volume V design is in review; Phase 13/14 implementation requires separate human authorization.
