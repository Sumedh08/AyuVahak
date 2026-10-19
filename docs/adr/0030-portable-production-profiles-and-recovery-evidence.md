---
status: accepted
date: 2026-10-20
---

# ADR 0030: Portable production profiles require measured recovery evidence

## Context

Volumes I–IV provide a local Compose deployment and durable PostgreSQL/outbox state, but a runnable container is not an operating model. Institutional deployments differ in availability, data authority, staffing, infrastructure and recovery needs. Requiring one cloud, Kubernetes, or microservice topology would reduce portability without proving safety.

## Decision

Phase 13 defines a portable single-site profile and a highly available institutional profile. Kubernetes and cloud vendors remain optional. PostgreSQL/PostGIS remains the system of record. Each deployment declares measurable SLO, RPO and RTO values appropriate to its profile; the product claims no universal service target. Backup configuration is insufficient: a synthetic isolated restore with migration and workflow-invariant checks is required to demonstrate recovery against the declared targets. Replicas do not replace independent backups. Multi-replica workers rely on existing durable claims/idempotency/outbox ownership rather than process memory.

## Consequences

- Operations and recovery become verifiable deployment responsibilities.
- Compose remains a first-class local path and is not described as production HA.
- Database, identity, secrets, monitoring and ingress remain portable choices behind documented contracts.
- Restore evidence and measured workload envelopes must accompany an actual deployment decision.

## Alternatives considered

- Mandatory Kubernetes was rejected: the need is not established and local Compose must remain viable.
- One universal SLO/RPO/RTO was rejected: no deployment or operational evidence supports a common target.
- Backup-job success as recovery proof was rejected: only restore validation demonstrates application-level recoverability.

## References

- [Volume V architecture](../architecture/volume-5-production-hardening-optimization-intelligence.md)
- [Phase 13 specification](../specs/phase-13-production-hardening-dpg-readiness-spec.md)
- [Production readiness gap analysis](../reviews/production-readiness-gap-analysis.md)
- [PostgreSQL 18 backup research](../research/volume-5-production-security-supply-chain-research.md)
