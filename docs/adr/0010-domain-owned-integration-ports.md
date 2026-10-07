---
status: accepted
date: 2026-10-08
---

# Domain contexts own their integration ports

## Context

The earlier context map treated Interoperability as a business bounded context and as the path through which all external healthcare integrations flowed. Elsewhere, routing, identity, and notification providers connected to their consuming contexts. A universal gateway would blur business ownership and contradict ports-and-adapters: only an integration service would understand partner meaning, while every domain would depend on it.

## Decision

Interoperability is a shared platform capability, not a business bounded context or mandatory gateway. Each business context owns the port that expresses its need from an external actor/system, the adapter implementing that port, partner-specific mapping into/out of the context's language, and business-level retry, reconciliation, and completion semantics.

Shared technical interoperability capabilities may include protocol clients, authentication helpers, conformance validation, terminology lookup, external identifier/provenance helpers, safe transport retry, correlation, and transport observability. They do not own a care workflow, external business mapping, the authoritative relationship between identifiers, or the decision that a remote business action succeeded.

## Consequences

- Referral owns patient-context and IPS mapping; Directory owns registry/mCSD mapping; Capacity owns capacity-source mapping; Transfer owns reservation semantics; Matching owns travel-time interpretation; Transport owns dispatch semantics; Notifications owns delivery outcomes; Identity & Access owns its IdP need and authorization; Analytics owns aggregate export and DHIS2 mapping.
- FHIR version handling remains in boundary adapters. A domain model must be usable without FHIR and with partner-specific versions/profiles.
- External identifiers remain source-issued and are associated with AyuVahak concepts by the owning context, scoped by issuer and resource type. There is no central identifier-mapping context.
- Connection credentials are deployment-managed secret references. Partner configuration is adapter-owned; shared operator views may report health without centralizing mappings.
- Common transport retry is allowed only when bounded and safe. Business retry/reconciliation is context-owned.
- A deployment may route an adapter through OpenHIE/OpenHIM or another engine. That route neither gives the engine AyuVahak workflow ownership nor makes AyuVahak mandatory where another system already owns the workflow.
- This supports local adapter testing and future extraction because each context has a meaningful port. It may duplicate a small amount of mapping code across adapters; sharing is appropriate only for genuinely technical machinery, not to centralize semantics.

## Alternatives considered

1. **Universal Interoperability gateway:** creates one common place for protocol, mapping, authentication, retry, and observability. It also tends to become a god integration service with every domain's semantics, couples all contexts to its release/deployment, obscures who reconciles a failed reservation, and makes country-specific adapters and versioned FHIR handling harder to own and test.
2. **Domain-owned ports and adapters with shared technical support (chosen):** leaves protocol/mapping responsibility near the business need while reusing stable technical mechanisms. It strengthens ownership and extraction seams; it requires explicit rules to prevent duplicated low-level client utilities and inconsistent security/retry defaults.
3. **Direct partner calls from domain logic:** simplest only superficially; it imports transport, FHIR, and partner assumptions into the domain and makes local testing, version handling, and replacement harder. Rejected.

## Boundaries

Mapping from a partner's contract to a domain fact belongs to the consuming context's adapter. Standard conformance validation may be a shared capability; deciding whether the validated fact is meaningful or current belongs to the domain. External source IDs remain external; the owning context stores the scoped association. The context decides business retry, timeout, compensation, and reconciliation. Central observability may aggregate adapter health but must not own connector configuration or business outcomes.

See [bounded contexts](../architecture/bounded-contexts.md), [standards strategy](../architecture/standards-and-interoperability.md), and [engineering decisions](../architecture/phase-1-engineering-decisions.md).
