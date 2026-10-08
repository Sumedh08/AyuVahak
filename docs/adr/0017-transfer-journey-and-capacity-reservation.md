# ADR 0017: Transfer attempts and capacity reservations remain separate, owner-controlled facts

- **Status:** Accepted
- **Date:** 2026-10-08
- **Decision owners:** AyuVahak architecture / product owner

## Context

A receiving Facility may reject, time out, accept but lack reservable capacity, or become unavailable after recommendation. Treating a request, acceptance, reservation, transport, and movement as one state risks duplicate commitments and overbooking. A cross-context transaction or shared repository would also violate the modular-monolith ownership rules.

## Decision

Transfer owns one coordination Journey per Referral with immutable, ordered destination-specific Transfer Request attempts and at most one pending attempt or nonterminal Transfer at a time. Clinical Suitability Assessment, Operational Readiness Assessment, and Acceptance are separately attributed facts. Acceptance does not reserve Capacity.

Capacity alone owns coordination Reservations and atomically serializes current-evidence/active-hold checks and hold creation/release/expiry. Transfer records the reservation outcome in its own state through a stable idempotent Capacity command. There is no cross-context transaction: interruption leaves explicit `AWAITING_RESERVATION`/unknown state, resolved by status query or replay of the same operation. A capacity decrease may mark a hold `AT_RISK`; it does not auto-release or imply a physical bed guarantee. Rejection, expiry, withdrawal, conflict, cancellation, rerouting, Arrival, Handover, and Completion preserve prior attempt/event chronology.

## Consequences

- Capacity reservation races can be verified with real PostgreSQL transactions and permit at most one hold consuming the last available coordination unit.
- The Transfer and Capacity owners may temporarily disagree about the latest outcome during a lost response; stable idempotency, explicit unknown state, and reconciliation resolve it without fabricating success.
- A Reservation is an AyuVahak coordination commitment only unless a future, explicitly authorized adapter obtains confirmation from an authoritative local bed system.
- No dispatch/fleet workflow is included; Transfer `IN_TRANSIT` is a reported journey state.

## Alternatives considered

- **Acceptance implies Reservation:** rejected because willingness to proceed and resource commitment are distinct facts.
- **Transfer writes Capacity tables in a shared database:** rejected because it bypasses context ownership and makes extraction/testing unsafe.
- **Distributed lock or cross-context transaction:** rejected because both contexts live in one monolith/database but own separate transactions; retries and owner-local atomicity suffice.
- **One mutable request record per Referral:** rejected because it loses rejection/expiry history and obscures which destination was approached.

## References

- [Volume II state machines](../architecture/volume-2-state-machines.md)
- [Volume II context contracts](../architecture/volume-2-context-contracts.md)
- [Capacity/readiness policy v1](../architecture/capacity-readiness-policy-v1.md)
- [ADR 0011](0011-functional-receiving-authorities.md)
- [ADR 0012](0012-audit-capture-and-failure-policy.md)
- [ADR 0013](0013-idempotency-and-consumer-deduplication.md)
