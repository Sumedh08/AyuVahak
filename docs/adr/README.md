# Architecture Decision Records

ADRs record durable AyuVahak architecture decisions, their context, and the rationale that later maintainers need. The current constitution is a concise index; detailed decisions and domain facts live in their linked documents.

## Status and change policy

- **Accepted** means the decision is the current baseline unless a later ADR says otherwise.
- **Superseded** means a later decision replaces all or part of the historical decision. Keep the old record and link the replacement; do not erase decision history.
- **Amended** decisions retain their original ADR number and add a dated amendment note that explains the scope of change and links the current baseline.
- **Rejected** or **withdrawn** proposals remain for context if they were durable enough to record; do not treat them as active requirements.
- A decision is not changed by silently editing prose. For a material change, amend the current ADR or create a new ADR that names the superseded record. Correct factual errors directly only when the decision itself did not change, and record the correction date/source.
- Avoid an ADR for a reversible implementation detail already covered by an engineering baseline. Link the baseline document instead.

## Current decisions

| ADR | Decision | Current status |
| --- | --- | --- |
| [0001](0001-coordination-state-not-clinical-record.md) | Coordination state, not clinical record | Accepted |
| [0002](0002-modular-monolith-and-ports-adapters.md) | Modular monolith and ports/adapters | Accepted; amended 2026-10-08 |
| [0003](0003-java-spring-postgresql-postgis.md) | Java/Spring and PostgreSQL/PostGIS | Accepted; amended 2026-10-08 |
| [0004](0004-fhir-at-interoperability-boundaries.md) | FHIR at interoperability boundaries | Accepted |
| [0005](0005-external-identity-and-local-authorization.md) | External authentication, local authorization | Accepted |
| [0006](0006-deterministic-advisory-matching.md) | Deterministic, explainable advisory matching | Accepted |
| [0007](0007-domain-events-and-transactional-outbox.md) | Domain events and transactional outbox | Accepted; amended 2026-10-08 |
| [0008](0008-lightweight-compose-first-deployment.md) | Lightweight Compose-capable deployment | Accepted |
| [0009](0009-separate-receiving-confirmations.md) | Historical fixed-role confirmation decision | Superseded by ADR 0011 |
| [0010](0010-domain-owned-integration-ports.md) | Domain-owned integration ports and adapters | Accepted |
| [0011](0011-functional-receiving-authorities.md) | Receiving authority functions, locally mapped | Accepted; supersedes ADR 0009 |

