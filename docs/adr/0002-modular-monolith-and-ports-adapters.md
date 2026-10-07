---
status: accepted
---

**Amended:** 2026-10-08. The monolith is organized by business bounded context in one build module initially. A context owns its application-facing ports, adapters, meaning, mapping, and business reconciliation. Shared interoperability support is a platform capability, not a universal gateway. Phase 1 enforces package rules with ArchUnit; see [engineering decisions](../architecture/phase-1-engineering-decisions.md). This amendment does not change the accepted modular-monolith decision.

# Start as a modular monolith with ports-and-adapters

Deploy one monorepo and one modular monolith initially, organized around business contexts with private state and narrow interfaces. Apply hexagonal principles at real integration seams so external registries, EHR/HIS, identity providers, transport systems, and notification channels can vary without spreading their protocols through domain logic. A context owns the port for its external need and the adapter that maps partner semantics; shared technical interoperability capabilities may be reused but do not own business workflows. Enforce package boundaries in CI. Do not split contexts into independently deployed services until demonstrated scaling, isolation, or organizational needs justify the operational cost. This keeps early deployment and local operation practical while preserving useful seams.
