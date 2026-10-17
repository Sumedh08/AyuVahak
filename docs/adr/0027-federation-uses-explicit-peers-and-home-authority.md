---
status: accepted
date: 2026-10-17
---

# ADR 0027: Federation uses explicit peers and home authority

## Context

Independent deployments may need to coordinate a Directory lookup, remote Capacity evidence/reservation, or receiving Transfer request. They have different local databases, administrators, trust policies and failure domains. Shared persistence or unrestricted replication would blur who may decide and mutate each fact.

## Decision

Federate only named owner operations over the Phase 9 secure exchange boundary. Each deployment has a unique stable DeploymentIdentity distinct from its Organization identity. Each peer profile explicitly scopes context, operation, direction, organization/facility, protocol profile and trust/configuration version. Remote references are namespaced `(deploymentId, context, resourceId)` values.

Directory facts remain authoritative at the owning Directory deployment. Capacity evidence/reservation remain authoritative at the facility's receiving Capacity owner and expire to STALE/UNKNOWN. The origin owns Referral and the coordinating Transfer journey; the receiving deployment owns local assessment, Acceptance and reservation. The origin records attributed remote outcomes but does not clone the remote aggregate. Transport facts remain with the one deployment coordinating that movement.

Federated operations use peer-scoped stable IDs, payload fingerprints, owner-local transactions and at-least-once delivery. A potentially committed request with a lost response is OUTCOME_UNKNOWN until the same operation ID is reconciled; no global 2PC or shared database is introduced. Peer revocation blocks new work and retries while retaining history.

## Consequences

- Authentication identifies the peer but does not authorize its business operation.
- Owner contexts validate scope, semantics, freshness, source authority and idempotency.
- No PatientReference or clinical content is federated by default.
- Duplicate and out-of-order delivery are expected; source sequence/version is preferred to peer wall-clock order.

## Alternatives considered

- Shared federation database and cross-deployment SQL were rejected because they violate autonomous ownership and failure isolation.
- Replicating all business tables or arbitrary multi-master edits was rejected because no conflict/authority model can safely preserve the existing domain semantics.
- Trusting another deployment merely because it runs AyuVahak was rejected; trust is explicit, revocable and operation-scoped.
- Distributed two-phase commit was rejected; local owner commits plus stable remote operation reconciliation preserve truth through partitions.

## References

- [Volume IV architecture](../architecture/volume-4-resilient-operations-federation-public-health.md)
- [Phase 11 specification](../specs/phase-11-real-time-resilience-federation-spec.md)
- [Context contracts](../architecture/volume-4-context-contracts.md)
- [State machines](../architecture/volume-4-state-machines.md)
- [ADR 0024 — Partner trust and exchange lifecycle](0024-partner-trust-and-exchange-lifecycle.md)
