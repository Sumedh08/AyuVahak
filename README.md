# AyuVahak

AyuVahak is a care-coordination platform. This repository implements the engineering platform and trust kernel, Care Service Directory, Capacity, Referral, deterministic advisory Matching/recommendations, Transfer Coordination, healthcare interoperability, secure external exchange, and bounded Transport coordination. The [architecture constitution](docs/architecture/constitution.md) defines the product and system boundaries.

New contributors should start with the [contributor guide](docs/development/contributor-guide.md). It covers the local Docker Compose environment, build and test commands, runtime configuration, migrations, architecture checks, observability, and troubleshooting.

## Architecture and phase specifications

- [Architecture documentation index](docs/architecture/README.md) — navigation for frozen volumes and current design records.
- [Architecture constitution](docs/architecture/constitution.md) — authoritative product and system boundaries.
- [Volume II core care coordination](docs/architecture/volume-2-core-care-coordination.md) — frozen Directory, Capacity, Referral, Matching, and Transfer ownership.
- [Volume III design](docs/architecture/volume-3-interoperability-security-transport.md) — frozen architecture and specifications for Phases 8–10; all three phases are implemented and frozen. See the [Volume III acceptance record](docs/reviews/volume-3-acceptance.md).
- [Volume IV](docs/architecture/volume-4-resilient-operations-federation-public-health.md) — frozen Phase 11/12 architecture and specifications; both phases are implemented and frozen. Volume IV acceptance records the integrated local verification. See the [Phase 11 acceptance record](docs/reviews/phase-11-acceptance.md), [Phase 12 acceptance record](docs/reviews/phase-12-acceptance.md), [Volume IV acceptance record](docs/reviews/volume-4-acceptance.md), [context contracts](docs/architecture/volume-4-context-contracts.md), [state machines](docs/architecture/volume-4-state-machines.md), [data ownership](docs/architecture/volume-4-data-ownership.md), [privacy matrix](docs/architecture/volume-4-privacy-matrix.md), [threat model](docs/architecture/volume-4-threat-model.md), [design review](docs/reviews/volume-4-design-review.md), and [design question register](docs/reviews/volume-4-open-questions.md).
- [Volume V design](docs/architecture/volume-5-production-hardening-optimization-intelligence.md) — frozen architecture and specifications for Phase 13 production hardening/DPG readiness and Phase 14 optimization/optional intelligence; implementation has not started and requires separate human authorization. See the [retention governance matrix](docs/architecture/volume-5-retention-governance.md), [production gap analysis](docs/reviews/production-readiness-gap-analysis.md), [DPG gap analysis](docs/reviews/dpg-readiness-gap-analysis.md), [Phase 13 spec](docs/specs/phase-13-production-hardening-dpg-readiness-spec.md), and [Phase 14 spec](docs/specs/phase-14-optimization-intelligence-spec.md).
- [Phase 11 specification](docs/specs/phase-11-real-time-resilience-federation-spec.md)
- [Phase 12 specification](docs/specs/phase-12-public-health-analytics-dhis2-spec.md)
- [Volume IV technical research](docs/research/volume-4-technical-research.md) and [ADRs 0026–0029](docs/adr/README.md)
- [Phase 8 specification](docs/specs/phase-8-healthcare-standards-interoperability-spec.md)
- [Phase 9 specification](docs/specs/phase-9-secure-external-exchange-spec.md)
- [Phase 10 specification](docs/specs/phase-10-emergency-transport-coordination-spec.md)
- [Volume III design review](docs/reviews/volume-3-design-review.md) and [open questions](docs/reviews/volume-3-open-questions.md)
