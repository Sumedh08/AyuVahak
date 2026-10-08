# Volume II Cross-Context Contracts

**Status:** Design contract for Phases 3–7. Names are conceptual application operations, not Java interfaces, HTTP endpoint methods, or event payload schemas.

## Contract rules

1. Each context owns the facts, port semantics, adapter mapping, and reconciliation behavior for its responsibility.
2. A consumer invokes a provider context through an application contract. It never queries the provider's repository, schema, SQL tables, or adapter.
3. Contracts carry opaque UUIDv4 references where an AyuVahak-owned identifier is required; external identifiers remain issuer/source and resource-type scoped. Organization and facility references are explicit validated Phase 2 execution context, not globally shared entities.
4. A response distinguishes `KNOWN`, `UNKNOWN`, `STALE`, `CONFLICTED`, `UNAVAILABLE`, and explicit rejection as applicable. Missing data is not zero, false, available, or accepted.
5. Each call has a correlation identifier, actor/service and organization/facility context, purpose when required, and a bounded timeout. Retry safety is defined per command. A caller that times out records an indeterminate/pending result and queries or retries idempotently; it does not assert success.
6. Events represent a committed fact owned by the producer. Delivery is at least once; consumers are idempotent. Required causal order is per aggregate only. A producer may publish before an executable consumer exists only when the complete consumer contract is frozen in this same approved Volume, including purpose, ownership, payload/privacy boundary, and delivery behavior. That event is marked `WAITING_FOR_CONSUMER`, without retry or consumer receipt; it is not automatically replayed when the consumer is later activated. No event becomes an external FHIR/DHIS2/EMS publication without a separate later decision and an owning adapter.
7. Every asynchronous consumer uses an explicit, narrowly scoped machine identity and organization/facility execution context; it never borrows the initiating human's authority. Its handler policy grants only the specific owner transition required. Correlation and causation are carried through the reaction.
8. No implicit shared transaction spans contexts. The provider commits its own state and returns an outcome; the caller records that outcome separately and can reconcile it after interruption.

## API and list conventions

Business APIs use the Phase 1 `/api/v1` convention and Phase 1 correlation behavior, RFC 9457 Problem Details, stable context error codes, and no anonymous business endpoints. Commands use explicit transition/action resources rather than arbitrary status replacement. Mutations require the Phase 2 validated execution scope and the owner authority; retry-sensitive operations use `Idempotency-Key` and expected-version preconditions where stated.

List/search routes use `page` and `size`, with default size 50 and maximum size 100 to match the Phase 2 platform API. They use an allowlist of filters and a total deterministic order; location search sorts by distance then stable Facility ID. Out-of-range page size is a validation error, not silently unbounded pagination. Response views are owner-produced contracts, never row/ORM shapes.

## Synchronous contracts

| Provider → consumer | Conceptual operation | Input / result | Consistency and failure behavior |
| --- | --- | --- | --- |
| Directory → Referral | `ValidateReferralOrigin` | Validated origin organization/facility references and active status. | A missing or inactive origin is a domain rejection. Directory outage prevents submit; draft entry may remain local only if the owning phase explicitly supports a draft. |
| Directory → Matching | `FindCandidates` | Origin location/reference, required CareCapability codes, configured geographic scope; candidate references with capability assignment state, provenance and directory version. | Active and source-attributed only. Missing location remains unknown/unlocated. Timeout means matching cannot produce a complete result. |
| Directory → Transfer | `GetDestinationPresentationFacts` | Candidate facility reference; receiving organization and service/capability references for authorized request presentation. | Not a second applicability authority. Before a new request, Transfer invokes Matching's targeted applicability contract, which checks active Facility and required capability assertions through their owners. |
| Referral → Matching | `GetSubmittedRequirement` | Referral reference and exact submitted revision; required capabilities, timing/constraints, origin reference; no clinical narrative/patient demographics. | Only submitted, current revision is evaluable. Concurrent revision/cancellation is detected by revision number and state. |
| Referral → Transfer | `GetTransferBasis` | Referral reference, revision, status, selected Recommendation reference and, only for a created request to its selected recipient, the opaque source-scoped PatientReference when the caller explicitly needs it for that purpose. | Transfer request creation requires submitted, non-cancelled Referral and current basis. Recipient access is case-scoped and audited. The reference is transient to the call and is not copied into Transfer, Matching, events, logs or metrics. Conflicting state rejects. |
| Capacity → Matching | `EvaluateOperationalReadiness` | Candidate facility, explicit required `CapacityDimension` set, readiness policy context, and evaluation time; status/reasons/evidence refs, source, observed/received times, valid-until, and projection/policy versions. | Capacity evaluates dimensions generically and never translates CareCapability. Matching supplies the dimensions resolved by its versioned policy. Fresh `READY` is required for confirmed rankability. `NOT_READY` is ineligible. `UNKNOWN`, `STALE`, `CONFLICTED`, or unavailable are non-rankable. No fallback to a lower-trust source unless source policy selects it. |
| Capacity → Transfer | `ReserveCoordinationCapacity`, `GetReservationOutcome`, `ReleaseCoordinationReservation` | Stable caller operation ID, transfer/journey reference, facility, dimension/unit/quantity/interval, expiry, scope; return confirmed, conflict, unknown, expired, released, at-risk. | Capacity validates current evidence and atomically checks existing holds. Same operation ID and same fingerprint replays; changed fingerprint conflicts. Timeout leaves caller outcome unknown until status/retry resolves. |
| Matching → Transfer | `GetRecommendationSnapshot` and `RevalidateRecommendationApplicability` | Snapshot ID, candidate Facility, and exact Referral revision; immutable evidence/policy references plus a fresh `APPLICABLE`, `NOT_APPLICABLE`, or `INDETERMINATE` result with reason codes. | The latest historical snapshot is never treated as currently usable merely because of a stored flag. Matching synchronously queries only material owner facts. `INDETERMINATE` blocks request creation. Historical snapshot remains immutable. |
| Travel-time adapter → Matching | Context-owned `EstimateTravelTime` port | Two Facility location references/coordinates and permitted time context; provider estimate/source/time or unavailable. | Optional, never receives patient reference or case narrative. Failure yields distance-only ranking where permitted, not false ETA. |

### Recommendation applicability contract

Historical validity and current applicability are separate. A Recommendation Snapshot remains an immutable and truthful record of the evidence/policy used when it was generated. Before Transfer creates a request, it calls Matching's `RevalidateRecommendationApplicability` for the exact snapshot, candidate, and Referral revision. Matching performs targeted synchronous reads and returns:

| Owner | Required current check |
| --- | --- |
| Referral | Exact submitted revision remains current and the Referral remains submitted, not cancelled or resolved. |
| Directory | Candidate Facility remains active; each required direct CareCapability assertion remains active, effective, and verified. |
| Capacity | The snapshot's mapped CapacityDimensions still have sufficiently current evidence and usable readiness under the referenced policies. |
| Matching | The policy/mapping version is still permitted for new use and the configured maximum recommendation age has not elapsed. |

All checks passing returns `APPLICABLE`. A known changed/withdrawn/expired fact returns `NOT_APPLICABLE`; an unavailable/ambiguous required owner response returns `INDETERMINATE`. Either non-applicable outcome blocks the Transfer Request and requires a fresh Recommendation. `GetCurrentRecommendation` means the latest historical snapshot plus a fresh applicability result; it never reads a cached `current=true` flag as authority. Revalidation queries only the candidate and evidence referenced by the snapshot, not whole context databases. Because the check and later request are separate context transactions, it is an evidence check at a point in time, not a capacity reservation or guarantee; acceptance/reservation must re-check their own owner facts.

These names are stable domain-level seams; the implementation specification may use Java naming consistent with the repository but may not merge their ownership or weaken unknown/failure outcomes.

## Commands and owner

| Command | Owning context | Retry protection | Required scope / audit |
| --- | --- | --- | --- |
| Curate/deactivate Organization, Facility, Location, HealthcareService, capability assignment | Directory | REQUIRED for externally retried mutation; same key/fingerprint replays | Facility/organization owner or designated Directory curation authority; mutation audit and provenance. |
| Submit/correct/retract Capacity Observation | Capacity | REQUIRED for adapter/manual retries | Facility Capacity reporting authority; audit manual overrides/corrections and retain source times. |
| Create, submit, amend, cancel, resolve Referral | Referral | REQUIRED for submit/cancel/resolve and externally retried create; recommended for draft edits | Origin organization/facility and appropriate referral authority; mutation audit. |
| Evaluate/recompute Recommendation | Matching | RECOMMENDED; exact same referral revision/policy/evidence fingerprint returns same snapshot or an explicitly equivalent replay | Origin-side matching authority; audit execution where deployment marks safety-relevant; preserve immutable evidence. |
| Create/withdraw a Transfer Request attempt; record assessments and Acceptance/rejection | Transfer | REQUIRED for each externally retried state-changing command | Receiving-side functional authority at destination facility; distinct suitability, readiness, acceptance authorities; audit every outcome. |
| Create/retry/release Capacity Reservation | Capacity | REQUIRED; stable operation ID from Transfer across retries | Capacity/reservation authority for destination; atomic audit with reservation fact. |
| Record departure/in-transit, Arrival, Handover, completion, cancellation or exception | Transfer | REQUIRED for state-changing integration/manual retries | Actor and organization/facility context appropriate to fact; mutation audit and event time/recorded time provenance. |

Pure reads do not require idempotency keys. Phase 2's command-idempotency record is command-owner-local; the consumer's event-deduplication receipt is separately owned with the effect it protects.

## Event catalog and actual consumers

The following events are justified by current or explicitly frozen same-Volume consumers. `name` is the frozen lower-kebab event type with major version suffix (`*.v1`); each envelope also carries the Phase 2 event ID, producer context, aggregate ID and monotonic aggregate sequence, occurred/recorded times, correlation, causation, actor/service and organization/facility attribution, and a minimal versioned data reference. Patient context and clinical payloads are prohibited.

| Event | Producer → consumer | Meaning / reaction | Ordering / payload boundary |
| --- | --- | --- | --- |
| `referral.cancelled.v1` | Referral → Transfer | Referral owner committed cancellation. Transfer withdraws a pending request or marks active coordination as needing its explicit cancellation policy; it does not silently erase a reservation or physical movement. | Referral aggregate sequence; restricted coordination references/reason/attribution only, no PatientReference or clinical context. |
| `transfer.unfulfilled.v1` | Transfer → Referral | An authorized operator explicitly closed the Journey without a completed Transfer. Referral resolves `UNFULFILLED` through an idempotent owner reaction; event delivery alone does not change Transfer truth. | TransferJourney aggregate sequence; outcome/reference/attribution only. |
| `capacity.reservation-expired.v1` | Capacity → Transfer | An active hold expired in Capacity. Transfer records the referenced hold outcome and moves the coordination state to awaiting a new reservation or recovery decision; it cannot continue to claim reserved. | Reservation aggregate sequence; hold/transfer references, expiry and source reason only. |
| `capacity.reservation-at-risk.v1` | Capacity → Transfer | A later authoritative observation reduces or invalidates the basis of a hold. Transfer flags a coordination exception and requires receiving-side confirmation/recovery; Capacity does not auto-release or rewrite Transfer. | Reservation aggregate sequence; hold/transfer references, evidence version and risk reason only, no raw observation payload. |
| `transfer.completed.v1` | Transfer → Referral | Transfer owner committed Arrival, Handover and completion. Referral may resolve through its own idempotent event consumer, retaining the fact source and chronology. | TransferJourney aggregate sequence; completion outcome/reference and attribution only. |

All event data is restricted coordination metadata. Phase 2's common event envelope carries actor/service, execution scope, times, correlation/causation and aggregate sequence; event payloads must not contain patient identity, clinical narrative, demographics, attachments, tokens, or raw partner payload. Required ordering is only within an aggregate; there is no global order.

`referral.cancelled.v1` is the approved same-Volume future-consumer exception: Referral owns the fact and Transfer is the frozen Phase 7 consumer. In Phases 3–6, Referral writes the event and its delivery row in the same transaction; when no Transfer handler is registered, that row is `WAITING_FOR_CONSUMER`, has no attempt/failure classification/consumer receipt, is not auto-retried, and is not eligible for operator replay. Cancellation correctness does not depend on Transfer. Phase 7 does not replay historical waiting cancellations because no Transfer journey could have existed before the consumer was introduced; newly produced events use the registered handler. Capacity emits no reservation events in Phase 4 because it has no Reservation fact yet. Directory and Matching do not emit change/invalidation events; current evidence is read synchronously.

An event for a merely hypothetical future consumer remains prohibited. The producer must not declare an event deferred unless the named same-Volume consumer, meaning, ownership, minimal payload, privacy class, and delivery/activation behavior are already approved.

## Asynchronous reaction and recovery

- Each event consumer commits its local effect and its consumer receipt atomically where the same database transaction permits. Duplicate delivery returns the recorded result without duplicating an effect.
- A producer's state and event intent are committed together through the frozen Phase 2 outbox contract. A failed dispatcher cannot roll back the business fact; delivery stays visible and recoverable.
- `WAITING_FOR_CONSUMER` is an intentional, durable deployment state, not a processing failure or success acknowledgement. It consumes no handler attempt, creates no consumer receipt, emits no repeated failure log, and is not automatically claimed or replayed. It remains visible in bounded delivery operations and preserves required per-aggregate ordering. Only a later explicit contract/ADR may define a replay or retirement policy.
- No consumer may mark an event handled before its local effect commits. Retry exhaustion remains visible for operator recovery.
- No global ordering is promised. The Phase 2 per-aggregate sequence prevents a later event from being successfully published ahead of an earlier unpublished event when the producer contract requires that order.
- A downstream consumer does not copy the producer's whole aggregate. It stores the minimal reference and local outcome needed for its own state.
- Cross-context failure produces `PENDING`, `UNKNOWN`, or explicit conflict/rejection, then a bounded retry/reconciliation path. It must not fabricate acceptance, readiness, reservation, or completion.

## Transaction boundaries

Directory updates plus their required audit/provenance/outbox intent are one Directory-owned transaction. Capacity observation receipt/projection and its required audit/event intent are one Capacity-owned transaction. Referral state and required audit/outbox intent are one Referral-owned transaction. Matching persists a snapshot with its audit/outbox intent in one Matching-owned transaction. Transfer request/assessment/acceptance state and its required audit/event intent are Transfer-owned transactions.

A Transfer acceptance transaction records Acceptance and creates/updates an `AWAITING_RESERVATION` Transfer state. It then invokes Capacity's reservation command in a separate owner transaction. If the response is lost, the Transfer remains explicitly unreserved until the same idempotent operation is queried/retried. If Capacity commits a reservation but the caller crashes before recording its result, retry/query by the stable operation ID recovers the existing hold. No hidden cross-context transaction is used.

## Policy/version behavior

Source-precedence, freshness, geographic candidate, capability-to-operational-requirement mapping, matching, and readiness policies have explicit identifiers and immutable versions. A configuration change creates a new version; it does not reinterpret historical evidence or snapshots. Deployment administrators may supply values only within validated supported ranges established by the owning phase. No universal TTL, kilometer radius, capacity dimension list, or legal retention duration is invented here.

## Architecture test contracts for later implementation

- No business `.domain` package imports Spring, persistence, web, or interoperability libraries.
- Matching has no dependency on Directory or Capacity adapters/persistence; it uses their application contracts.
- Transfer has no dependency on Capacity persistence; Capacity owns reservation commands and state.
- Referral domain has no dependency on Matching or Transfer internals.
- A context does not import another context's adapter, repository, row mapper, migration, or schema namespace.
- Phase 3–7 business domains do not import HAPI/FHIR/DHIS2 or framework-specific representations.
- Phase 2 platform capabilities are used through published boundaries; no context writes another context's audit, outbox, grant, or idempotency tables directly.
