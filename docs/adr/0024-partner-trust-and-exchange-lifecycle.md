---
status: accepted
date: 2026-10-15
---

# ADR 0024: Enforce partner trust centrally; keep exchange meaning with the owner

## Context

Volume III adds external FHIR and optional transport integrations. Caller-supplied endpoints, untrusted FHIR references, broadly shared credentials, and a central service that owns all mappings or retry outcomes would create SSRF risk and integration-gateway coupling. Conversely, duplicating low-level TLS, URL policy, and credential handling inside every adapter would produce inconsistent security enforcement.

## Decision

Phase 9 provides a shared technical trust envelope and transport capability. It centrally enforces administrator-controlled partner identity, allowed origin/host/path, authentication/TLS mode, trust status, payload/time limits, and secret/key references. No ordinary API input or external FHIR Endpoint/Reference may select an outbound destination.

Endpoint validation is bound to the actual connection attempt: inspect every DNS answer, connect only to a vetted address, retain the configured hostname for SNI/certificate verification, and verify the connected peer. If a client cannot enforce this binding, it must fail closed or use an egress control that enforces destination policy at connect time. Redirects and connection pools may not bypass the check.

Each context still owns its external port, partner profile mapping, external identifiers, exchange purpose, business idempotency/retry meaning, and reconciliation result. The owner retains only its own operation intent/status; shared infrastructure is not a universal gateway, connector registry, payload archive, or business receipt store. A technical delivery acknowledgement never implies Acceptance, Reservation, Transfer, or Transport state.

Attempts are bounded and operation-identified. Retry only when the failure is plausibly transient and the remote effect is idempotent or recoverable. If a side effect may have occurred and the partner offers no idempotency or status query, keep the result `OUTCOME_UNKNOWN` for reconciliation rather than blindly resend. External delivery is at-least-once where safe; exactly-once is not claimed. HTTP retry, partner replay protection, owner command idempotency, and internal event consumer dedup remain distinct.

## Consequences

- Central technical controls are reusable while semantic mapping remains context-owned.
- Partner configuration changes are versioned, attributable, and audited; revocation blocks new/queued operations and invalidates cached credentials.
- Production TLS verification cannot be disabled; private partner networks require explicit deployment allowlisting and egress controls.
- Future external connectors remain testable with local mock servers, synthetic certificates/tokens, and deterministic payloads.
- A deployment may use OAuth/SMART Backend Services, mTLS, or another explicitly agreed mechanism; none substitutes for AyuVahak authorization.

## Alternatives considered

- **Universal Interoperability gateway with all mapping/retry semantics:** rejected; it becomes a god integration service and obscures fact ownership.
- **Per-adapter independent endpoint policy/credential implementation:** rejected as a default because common SSRF/TLS/secret protections can diverge.
- **Blind retry until remote success or exactly-once guarantee:** rejected; remote side effects cannot generally participate in a local database transaction, and an unknown result must remain unknown.
- **Kafka/broker or external orchestrator now:** rejected; no requirement justifies a mandatory distributed integration platform.

## References

- [ADR 0010 — Domain contexts own integration ports](0010-domain-owned-integration-ports.md)
- [Volume III architecture](../architecture/volume-3-interoperability-security-transport.md)
- [Volume III context contracts](../architecture/volume-3-context-contracts.md)
- [Volume III state machines](../architecture/volume-3-state-machines.md)
- [Phase 9 specification](../specs/phase-9-secure-external-exchange-spec.md)
- [RFC 9700 OAuth 2.0 Security BCP](https://www.rfc-editor.org/rfc/rfc9700.html)
