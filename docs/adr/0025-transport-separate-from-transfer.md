---
status: accepted
date: 2026-10-15
---

# ADR 0025: Transport coordinates movement separately from Transfer

## Context

The frozen Volume II Transfer lifecycle records destination request, distinct receiving assessments and Acceptance, Capacity reservation outcome, reported departure, Arrival, Handover, Completion, and reconciliation. Phase 10 needs to coordinate a vehicle/crew/provider movement without transferring clinical workflow ownership or implying that the transport provider controls receiving acceptance or care handover.

## Decision

Add Transport as a separate business bounded context in Phase 10. Transport owns transport requests, provider-scoped assignment references, ETA and status observations, cancellation/reconciliation, movement evidence, and the provider/manual operation that requests a physical destination change. Providers remain authoritative for their fleet, crew, and dispatch algorithms. Transport can operate manually with authorized attributed reports and does not require EMS integration.

Transfer continues to own TransferRequest, Acceptance, reservation relationship, Transfer `IN_TRANSIT`, Transfer Arrival, Handover, Completion, destination reroute authority, pending reroute intent, and the active destination. A Transfer commands Transport through owner contracts; Transport reports justified minimal movement facts through named events/contracts. Neither context accesses the other's private persistence. Before a new TransportRequest is accepted, Transport calls Transfer-owned `CheckTransportRequestEligibility` to confirm the exact linked Transfer is in a permitted pre-departure state, requires transport, and has the current accepted/reserved destination. Unavailable or ambiguous owner results fail closed.

Transport cancellation is a separate durable, idempotent `TransportCancellationOperation`, not a replacement state on the movement request. A late assignment does not clear cancellation intent. `PENDING`, `OUTCOME_UNKNOWN`, or `RECONCILIATION_REQUIRED` blocks replacement; only an owner-confirmed pre-movement cancellation permits Transfer to consider its own next action. Transport provides scoped `GetTransportCancellationOutcome` recovery and publishes `transport.cancellation-resolved.v1` to Transfer when a concrete outcome is resolved. Transfer and Transport each authorize their own effect: the initiating human must pass Transfer policy, and Transport separately checks its narrowly scoped Transfer service grant or a direct Transport authority. The service identity does not inherit human grants or authority from a caller-supplied Transfer reference.

Only an explicit attributed fact that the patient is onboard and has departed origin can be used by Transfer to record `IN_TRANSIT`. Assignment, dispatch acknowledgement, ETA, or arrival at pickup cannot do so. A Transport arrival report may prompt Transfer to record its own Arrival after validation; it never implies Handover or Completion. Cancellation of Transport is distinct from cancellation/interruption of Transfer. Rerouting requires fresh destination applicability, required receiving decisions and Capacity reservation, and explicit Transfer authority. Before movement, it follows the existing cancellation/replacement rules. After movement, Transfer records a durable pending intent while retaining the old active destination and invokes a distinct, stable-ID `TransportDestinationChangeOperation`. Transport independently authorizes the operation and validates the pending intent through `CheckTransportDestinationChangeEligibility`; an acknowledged provider or authorized manual crew/provider result is required. Transport publishes `transport.destination-change-resolved.v1`; Transfer alone applies the destination change after consuming the event and revalidating its own prerequisites. Unknown/rejected outcomes do not change the active destination and block competing changes until reconciled. After an accepted reroute is committed in Transfer, Transfer releases the prior Capacity hold through Capacity's existing owner contract using a separate stable operation ID. A pending/unknown release does not roll back the reroute, but remains a visible cleanup exception and blocks another reroute until `RELEASED` or `EXPIRED` is confirmed. Rejection instead releases the proposed hold; it must also reconcile before a subsequent reroute. No generic destination patch is permitted.

No fleet ERP, computer-aided dispatch, continuous GPS tracking, route optimizer, crew roster/HR, call center, or mandatory EMS standard is included. External provider status does not override owner facts; duplicate, delayed, and out-of-order observations are retained and reconciled under monotonic transition rules.

## Consequences

- There is a new bounded context and future schema only when its Phase 10 implementation begins; no Phase 8/9 work scaffolds it.
- Transport requires scoped functional authorities, owner-local idempotency, audit, separate cancellation and destination-change operation lifecycles, narrow Transfer-owned eligibility queries, and named minimal events under the frozen platform kernel.
- Manual and integrated provider modes share the same state transitions, attribution, privacy, and audit policy.
- Arrival of a vehicle and handover of clinical responsibility remain distinct facts.

## Alternatives considered

- **Add dispatch state to Transfer:** rejected because provider assignment/retry/status and care-transition facts have different authorities and failure semantics.
- **Let Transport mutate Transfer directly:** rejected because it bypasses Transfer's current-state validation, authority, and audit.
- **Make NEMSIS or a provider protocol universal:** rejected; available standards are jurisdiction/product specific and do not provide a universal live dispatch contract.
- **Require GPS or a routing provider:** rejected; manual/local mode must remain viable and location surveillance is out of scope.

## References

- [ADR 0011 — Functional receiving authorities](0011-functional-receiving-authorities.md)
- [ADR 0017 — Transfer attempts and Capacity reservations](0017-transfer-journey-and-capacity-reservation.md)
- [Volume II state machines](../architecture/volume-2-state-machines.md)
- [Volume III architecture](../architecture/volume-3-interoperability-security-transport.md)
- [Volume III context contracts](../architecture/volume-3-context-contracts.md)
- [Volume III state machines](../architecture/volume-3-state-machines.md)
- [Phase 10 specification](../specs/phase-10-emergency-transport-coordination-spec.md)
