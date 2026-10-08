# ADR 0020: Declared same-Volume future consumers use a durable waiting state

- **Status:** Accepted
- **Date:** 2026-10-08
- **Decision owners:** AyuVahak architecture / product owner

## Context

The event framework treats unregistered active consumers as `UNHANDLED`, retaining the event for explicit operator replay once a compatible handler exists. Volume II adds a separate timing case: Referral cancellation is a meaningful committed fact in Phase 5, but the Transfer consumer is intentionally introduced only in Phase 7. There can be no TransferJourney to reconcile before that consumer exists. Treating this expected delay as `UNHANDLED` would expose it to a replay operation that is not needed; treating it as delivered or dropping it would falsely report success or lose the fact.

## Decision

Permit a producer to create `WAITING_FOR_CONSUMER` only when an approved same-Volume design has already frozen the future consumer, purpose, owner, minimal payload/privacy boundary, and activation behavior. The producer marks the event at creation when that handler is not installed. This is a durable intentional state, not a failure or acknowledgement. It has no attempt/failure classification or consumer receipt; it is not claimed, retried, operator-replayed, deleted under ordinary delivered retention, or automatically promoted when the handler appears. It remains visible and preserves required aggregate ordering.

The only currently approved event using this exception is `referral.cancelled.v1` from Referral to Transfer. Phase 7 does not automatically replay the Phase 5–6 waiting records because no TransferJourney could have existed; new cancellation events are delivered normally once the Transfer consumer is active. Any additional future-consumer event requires an explicit architecture decision and same-Volume contract before publication.

## Consequences

- Expected phase-order delay is distinguishable from a misconfigured missing handler and an exhausted delivery.
- Referral cancellation remains correct without Transfer, and the event is retained without claiming a downstream effect.
- The event is operationally visible but deliberately not replayable; operators must not attempt to use it as a historical journey backfill.
- The Phase 5 implementation must extend the platform eventing state model by a forward migration while preserving Phase 2 replay semantics for `STALLED` and `UNHANDLED`.

## Alternatives considered

- **Use `UNHANDLED` and operator replay later:** rejected because it conflates expected phase sequencing with a missing consumer configuration and permits a replay with no applicable journey.
- **Acknowledge as delivered:** rejected because no Transfer handler accepted or applied the fact.
- **Do not emit cancellation events until Phase 7:** rejected because Referral's committed cancellation is a legitimate cross-context fact and its consumer contract is fully defined in this Volume; it should use the established transactional outbox.
- **Automatically promote waiting events when a handler registers:** rejected because consumer installation must not silently trigger a historical fan-out or apply events to state that did not exist when they were produced.

## References

- [Volume II context contracts](../architecture/volume-2-context-contracts.md)
- [Phase 2 eventing contract](../specs/phase-2-platform-kernel-spec.md#48-transactional-outbox-and-dispatcher)
- [Phase 5 specification](../specs/phase-5-referral-spec.md)
- [Phase 7 specification](../specs/phase-7-transfer-coordination-spec.md)
- [ADR 0007](0007-domain-events-and-transactional-outbox.md)
- [ADR 0013](0013-idempotency-and-consumer-deduplication.md)
