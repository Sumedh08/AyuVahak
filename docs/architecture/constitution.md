# AyuVahak Architecture Constitution

This is the short, authoritative summary of the product and architecture. Read it with the linked documents before writing a specification or implementation. The current architecture exit review and remaining decision timing are in [Phase 0 consistency review](../reviews/phase-0-consistency-review.md) and [open questions](../reviews/phase-0-open-questions.md).

Durable decisions and their amendment/supersession policy are indexed in the [ADR register](../adr/README.md).

## Product role and ownership

AyuVahak coordinates a cross-organization referral and transfer when a deployment needs a shared workflow. It owns the coordination record and actions it performs only where it is selected as that workflow's authority. It may be omitted when an existing system already owns the workflow. AyuVahak is not an EHR/HIS, HIE or generic integration engine, DHIS2, LMIS, ambulance dispatch system, national patient identity service, or general BI platform. The [vision](../product/vision.md) and [scope](../product/scope-and-non-goals.md) define that boundary.

Information authority is category-specific. Clinical records, patient identity, professional credentials, facility identity, dispatch, and public-health indicators remain with their designated external authorities. AyuVahak stores only the coordination state and minimum, purpose-limited transfer context it needs. Every imported observation retains its source and time. See [data ownership](data-ownership.md) and [security and trust](security-and-trust.md).

## Domain and interoperability

Referral, Transfer Request, Acceptance, Reservation, Transfer, Transport, Arrival, and Handover are distinct facts and lifecycles. Capability, Capacity, Availability, and Readiness are distinct. Recommendations are advisory and deterministic; qualified local authorities make clinical-suitability, operational-readiness, and destination decisions. Their functions are mapped to deployment roles, not fixed globally to job titles. Stale or missing capacity is unknown, never positive availability. The [glossary](../../GLOSSARY.md), [domain context](../../CONTEXT.md), and [reference workflow](../workflows/emergency-interfacility-transfer.md) are canonical.

Business contexts own the ports, semantics, mappings, and reconciliation rules for the external facts or actions they need. Adapters live with their owning context. Shared interoperability tooling supplies technical support and is not a universal gateway or workflow owner. FHIR R4 is the first exchange baseline; mCSD, IPS, SMART App Launch, WHO SMART Guidelines, OpenHIE/OpenHIM, DHIS2, and LMIS have the specific roles and limits documented in [standards and interoperability](standards-and-interoperability.md) and [bounded contexts](bounded-contexts.md).

## System shape, safety, and reliability

Start as a monorepo and one modular monolith with package-by-feature ownership, inward dependency rules, private persistence, and no cross-context table access. Use Java/Spring and PostgreSQL/PostGIS. Later phases may use domain events and an outbox where committed facts must be delivered reliably; these are architectural choices, not Phase 1 deliverables. Do not use full event sourcing, distributed CQRS, or mandatory Kafka. Docker Compose remains a first-class path; Kubernetes and proprietary cloud services are not prerequisites. Phase 1 builds and verifies only the engineering substrate described in [Phase 1 engineering decisions](phase-1-engineering-decisions.md); Phase 2 introduces the platform kernel, identity, and trust mechanisms.

No LLM or machine-learning model participates in a safety-critical eligibility, destination, capacity/readiness, acceptance, reservation, or dispatch decision. External systems can fail independently: pending delivery is not acceptance, reservation, dispatch, or current capacity. Duplicate and delayed messages must preserve attribution and chronology. The required scenario behavior and verification approach are in [quality attributes](quality-attributes.md).

Authentication is delegated to an external identity provider, and AyuVahak authorization is separate, scoped, and RBAC-first with room for later ABAC. Phase 2 implements the authentication integration and authorization framework; Phase 1 does not implement either. Machine identities and credentials are deployment-managed. Normal logs do not need patient or clinical payloads. See [security and trust](security-and-trust.md).

## Agent safety

The permanent external-mutation policy in [AGENTS.md](../../AGENTS.md) applies in every phase. Public/shared healthcare systems and upstream repositories are not test targets; default integration verification uses local containers, deterministic fixtures, mocks, and disposable infrastructure controlled by developers. The rationale and safe test-environment expectations are in [agent safety policy](../governance/agent-safety-policy.md).

## System view

[System context](system-context.md) shows AyuVahak as one optional system interacting with people and external authorities. It is distinct from the internal [context map](bounded-contexts.md). Deployment-specific law, authorization mapping, terminology, retention, partner profiles, and federation agreements are explicit configuration/governance questions; they do not change the core ownership boundary.

