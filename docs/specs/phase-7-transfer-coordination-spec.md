# Phase 7 Specification — Transfer Coordination

**Volume:** II — Core Care Coordination  
**Phase boundary:** Final Volume II business phase. Transfer coordination only; no dispatch, clinical record, FHIR, public-health, UI, analytics, or Volume III adapters.  
**Architecture references:** [Volume II architecture](../architecture/volume-2-core-care-coordination.md), [context contracts](../architecture/volume-2-context-contracts.md), [state machines](../architecture/volume-2-state-machines.md), [Capacity/readiness policy](../architecture/capacity-readiness-policy-v1.md), [Matching policy](../architecture/matching-policy-v1.md), [recommendation applicability contract](../architecture/volume-2-context-contracts.md#recommendation-applicability-contract), [functional authorities ADR](../adr/0011-functional-receiving-authorities.md), [audit ADR](../adr/0012-audit-capture-and-failure-policy.md), [idempotency ADR](../adr/0013-idempotency-and-consumer-deduplication.md), [ADR 0019](../adr/0019-synchronous-recommendation-applicability.md), [ADR 0020](../adr/0020-declared-future-consumer-delivery-state.md).

## Objective and outcome

Coordinate one Referral through sequential receiving-facility requests, distinct assessments and Acceptance, a separate Capacity-owned reservation, reported transfer progress, Arrival, Handover and completion. Preserve every attempt and actual event time. A Transfer being coordinated is not a claim that AyuVahak has made a clinical decision, owns a bed, assigned an ambulance, or controls the patient's subsequent care.

## Scope and exclusions

**Included:** TransferJourney, ordered TransferRequestAttempt, Clinical Suitability and Operational Readiness assessment facts, Acceptance/rejection/expiry/withdrawal, Capacity Reservation command/reconciliation, selection and rerouting, Transfer lifecycle, Arrival/Handover/Completion, scoped APIs/views, owner schema, events, idempotency, audit/provenance, race/recovery tests.

**Excluded:** Transport or ambulance domain implementation, dispatch/vehicle/fleet, clinical chart/hand-over narrative, physical admission or bed ledger, FHIR/HIE/EMS adapters, notifications, scheduling engine, frontend, workflow engine, distributed lock, and cross-context transaction.

## Model and invariants

| Concept | Owner and invariant |
| --- | --- |
| TransferJourney | Transfer-owned coordination history for one Referral. It links ordered destination attempts, accepted basis and any current Transfer. It stores only references to Referral/Matching/Directory/Capacity facts. |
| TransferRequestAttempt | One directed request to one receiving Facility, for one submitted Referral revision and recommendation evidence reference, with request/deadline and one terminal outcome. Attempts are never overwritten by rerouting. |
| ClinicalSuitabilityAssessment | A separate receiving-side case judgement, recorded as `SUPPORTS`, `DOES_NOT_SUPPORT`, or `INDETERMINATE`, with functional authority, actor/source, time, reason code and minimal evidence reference. AyuVahak does not infer its content. |
| OperationalReadinessAssessment | A separate receiving-side operational judgement with the same attribution and result vocabulary. It does not merely copy Capacity's system evaluation. |
| Acceptance | Explicit receiving-facility willingness to proceed after both required latest assessments are `SUPPORTS`. It is not Capacity Reservation. |
| CapacityReservation | Capacity-owned time-bounded coordination hold tied to a stable Transfer reference, a reservable Capacity dimension, unit/quantity/interval and expiry. Transfer stores only returned status/reference. |
| Transfer | Transfer-owned movement coordination chronology. It starts at Acceptance as `AWAITING_RESERVATION`; `READY` requires recorded active reservation confirmation. |
| Arrival / Handover / Completion | Separate facts: patient arrival reported; care responsibility handover acknowledged; coordination case closed. None is inferred from the prior fact. |

One TransferJourney permits at most one pending request attempt and at most one nonterminal accepted Transfer. A new request can begin only after the prior request is rejected/expired/withdrawn or its accepted Transfer is explicitly closed/cancelled and any Capacity hold outcome is reconciled. A recommendation is advisory; an authorized human explicitly chooses a currently eligible candidate, not necessarily rank 1.

TransferRequestAttempt stores the Referral reference/revision and minimal coordination facts, not a copied PatientReference. After the request is created, an authorized receiving participant may request the opaque PatientReference through Referral's purpose-bound `GetTransferBasis` view if needed to locate source records. Transfer does not retain the identifier; the receiver gets no name, demographics, summary, note or attachment from the Volume II request.

Initial policy requires a current eligible recommendation candidate before issuing a request. An ineligible/indeterminate destination cannot be selected through a hidden override. A later explicit, audited override policy is a separate decision; urgent out-of-band care must not be blocked by software, but it cannot be misrepresented as a normal AyuVahak-confirmed route.

## Request, assessment, acceptance and expiry

The sender creates a directed request for an active receiving Facility, referencing Referral/revision, recommendation snapshot/candidate evidence, origin and destination, request time, policy-defined expiry and coordination requirements. If no valid deployment response-deadline policy is configured, request creation fails with `TRANSFER_EXPIRY_POLICY_UNCONFIGURED`; no request is left indefinitely pending. Before creating each request, Transfer calls Matching's synchronous `RevalidateRecommendationApplicability` contract with the exact snapshot ID, requested Referral ID and revision, and candidate Facility. Matching verifies that the snapshot belongs to the requested Referral before evaluating current applicability. Only `APPLICABLE` permits creation. `NOT_APPLICABLE` or `INDETERMINATE` blocks the request and requires a fresh evaluation or recovery of the required owner query. This check performs targeted reads of current Referral, Directory, Capacity and Matching policy facts; no change/invalidation event is required. Acceptance and Capacity Reservation later recheck their own owner facts, because revalidation and request creation are not a cross-context transaction. If facts changed, the command returns an explicit stale/ineligible outcome and requires new evidence.

Receiving decisions use functional authorities rather than job titles:

- `ClinicalSuitabilityAuthority` may submit a Clinical Suitability Assessment.
- `OperationalReadinessAuthority` may submit an Operational Readiness Assessment.
- `AcceptanceAuthority` may accept only when both latest assessments are `SUPPORTS` and the request is pending/unexpired.
- A deployment may grant these functions to one person or separate people/teams; each function is separately authorized at the destination's receiving organization/facility scope.

An assessment `DOES_NOT_SUPPORT` does not silently become rejection; the authorized receiving party issues an explicit rejection with a structured reason or may request further information through an explicitly modeled future extension. `INDETERMINATE` is not positive. Request outcomes are `ACCEPTED`, `REJECTED`, `EXPIRED`, `WITHDRAWN`. Expiry is an owner command evaluated at/after its deadline; accept-vs-expire and accept-vs-withdraw serialize on the attempt version. A late acceptance is rejected; an exact retry of a prior committed acceptance replays.

## Reservation and acceptance failure window

Acceptance transactionally commits in Transfer and creates a Transfer in `AWAITING_RESERVATION`. Transfer then calls Capacity's owner command with stable operation ID, Transfer reference, Facility, reservable dimension, value/unit, interval and expiry. There is no shared transaction across contexts.

- Capacity returns `RESERVED`, `CONFLICT`, `UNKNOWN`, `UNAVAILABLE`, or a safe validation failure. Only `RESERVED` permits Transfer to record `READY`.
- If capacity changed after recommendation or acceptance, an insufficient/stale/unknown result leaves Acceptance intact and Transfer explicitly unreserved. It must not claim ready, admitted, or guaranteed.
- If the response times out, Transfer keeps `AWAITING_RESERVATION`/unknown and retries or queries using the same operation ID. If Capacity committed but Transfer crashed, same-key replay returns the original hold outcome; the Transfer update is recoverable.
- If a reservation cannot be obtained, the receiving authority may reconfirm, or an authorized operator closes/cancels this accepted attempt after reconciling any hold and creates a new request attempt based on fresh evidence. The prior Acceptance and conflict remain in history.
- Cancellation of a reserved pre-departure Transfer records cancellation/release intent. Until Capacity confirms release/expiry, the journey remains `CANCELLATION_PENDING_RELEASE` and cannot issue a competing request. Retrying release is idempotent.
- A later capacity decrease may mark the hold `AT_RISK`. Before departure, Transfer enters/retains a coordination exception and requires current receiving confirmation or explicit safe closure. Once movement is in progress, the event is attributed and surfaced as an operational exception; it does not fabricate cancellation or reverse arrival.

Capacity serializes the last-unit race in its own PostgreSQL transaction. For available quantity 1 and two concurrent requests for the same dimension/unit/overlapping time, exactly one hold commits. Holds with non-overlapping intervals only share capacity if the dimension policy explicitly defines that behavior. No distributed lock and no direct Transfer write to Capacity storage.

## Rerouting and golden paths

A rejection, expiry, withdrawal, stale recommendation, unavailable destination, or reservation conflict does not erase history. The next attempt is a new ordered TransferRequestAttempt on the same TransferJourney. Before each new attempt, Transfer calls Matching's synchronous applicability contract for the exact snapshot, candidate and current Referral revision; only `APPLICABLE` permits the request. Recompute a new Recommendation if applicability is `NOT_APPLICABLE`; if it is `INDETERMINATE`, surface the owner-query uncertainty and do not proceed. Only one pending attempt remains at a time.

**Canonical synthetic happy path:** Origin Facility O submits required capabilities `SYN-ICU`, `SYN-CT`, `SYN-NEURO`. Candidate A is closer but lacks `SYN-NEURO` and is ineligible. Candidate B satisfies all capabilities and has fresh `READY` evidence; Candidate C has stale capacity and is indeterminate. Matching ranks B. Authorized sender chooses B. Receiving authorities separately submit positive clinical and operational assessments; Acceptance is recorded. Capacity reserves one explicitly defined coordination unit; Transfer becomes READY, then the authorized source reports `IN_TRANSIT`, receiving side reports `ARRIVED`, acknowledges `HANDED_OVER`, and Transfer completes. Referral resolves through its own idempotent reaction.

**Canonical synthetic failure path:** Facility B is recommended and rejects; a fresh evaluation selects Facility C; C accepts but loses the last-unit reservation race; the attempt/Acceptance/conflict remain recorded; coordinator closes that unreserved Transfer and recomputes; Facility D accepts and reserves; completion occurs without deleting earlier attempts. All fixtures and identifiers are synthetic.

## Transfer lifecycle and late/delayed facts

Use the normative state machine in [Volume II state machines](../architecture/volume-2-state-machines.md): `AWAITING_RESERVATION`, `READY`, `IN_TRANSIT`, `INTERRUPTED`, `ARRIVED`, `HANDED_OVER`, `COMPLETED`, `COORDINATION_EXCEPTION`, and pre-departure `CANCELLED` (with a pending-release condition where applicable).

- `IN_TRANSIT` means a trusted actor/source reported departure/journey state; it is not evidence that AyuVahak dispatched a vehicle.
- `RecordArrival` may be accepted from `READY`, `IN_TRANSIT`, or an accepted `AWAITING_RESERVATION`/exception state when the physical arrival occurred before the digital workflow caught up. It records actual `occurredAt`, server `recordedAt`, source, and an explicit exception if no reservation/ready state was recorded. It does not fabricate missing earlier transitions.
- Arrival may not be inferred from an ambulance/vehicle location. Handover requires distinct acknowledgement from a destination-authorized care-team function. Completion requires handover plus explicit closure.
- Duplicate Arrival/Handover/Completion commands replay idempotently. A corrected timestamp/result is a new correction record referencing the original and reason, not an in-place edit.
- After departure, user cancellation cannot pretend to stop the journey. A reported aborted journey is `INTERRUPTED`; an authorized source may report resumption or final abandonment. After Arrival, cancellation is not allowed; a correction/exception path is used.
- Delayed and out-of-order messages preserve occurrence and recorded times and source sequence/reference. Aggregate sequence determines causal ordering; wall-clock timestamps do not.

## Commands, queries, and HTTP contract

All business routes require authenticated Phase 2 resource-server identity, explicit validated execution scope, functional authority and bounded access. No anonymous route. Continue URI major version `/api/v1`, RFC 9457, stable codes and correlation header. State-transition routes express commands explicitly; do not allow arbitrary `PUT status`.

| Command/query | Purpose |
| --- | --- |
| Create Transfer Request attempt | Sender creates next attempt linked to eligible snapshot/candidate. REQUIRED idempotency. |
| Record Clinical Suitability Assessment | Destination clinical-suitability authority records result/reason/evidence reference. REQUIRED idempotency. |
| Record Operational Readiness Assessment | Destination readiness authority records distinct result. REQUIRED idempotency. |
| Accept / Reject / Withdraw / Expire request | Destination acceptance authority, authorized sender withdrawal, or Transfer expiry worker; each state-checked and REQUIRED idempotency. |
| Reserve / query / release Capacity | Transfer invokes Capacity application contract; Capacity owns idempotency and hold. |
| Record departure / interruption / Arrival / Handover / Completion | Scoped source/functional authorities record attributable fact and event time. REQUIRED idempotency. |
| Get TransferJourney / request history | Participant-scoped history with minimal patient reference and safe reason fields. |
| List incoming pending requests | Receiving-facility scope, bounded page and stable sort by deadline then request ID. |
| List active transfers / awaiting handover | Origin/receiving facility or delegated regional scope, bounded pages. |

### HTTP resource contract

| Method and route | Purpose |
| --- | --- |
| `POST /api/v1/transfer-journeys` | Create one Journey and its first request attempt from a current eligible recommendation candidate. |
| `GET /api/v1/transfer-journeys/{journeyId}` | Read authorized Journey, request-attempt history, current Transfer and outcome references. |
| `POST /api/v1/transfer-journeys/{journeyId}/request-attempts` | Create a sequential new attempt after prior attempt/Transfer is terminal and reconciled. |
| `POST /api/v1/transfer-journeys/{journeyId}/closure` | Explicitly close an unfulfilled Journey with a structured outcome/reason; emits `transfer.unfulfilled.v1` after Transfer's terminal checks. |
| `GET /api/v1/transfer-request-attempts` | Bounded incoming/pending list filtered by receiving Facility and state. |
| `POST /api/v1/transfer-request-attempts/{attemptId}/clinical-suitability-assessments` | Record separate clinical assessment. |
| `POST /api/v1/transfer-request-attempts/{attemptId}/operational-readiness-assessments` | Record separate operational assessment. |
| `POST /api/v1/transfer-request-attempts/{attemptId}/acceptance` | Accept only after both latest assessments support. |
| `POST /api/v1/transfer-request-attempts/{attemptId}/rejection` | Record explicit structured rejection. |
| `POST /api/v1/transfer-request-attempts/{attemptId}/withdrawal` | Withdraw a pending request under sender/receiving policy. |
| `GET /api/v1/transfers/{transferId}` and `GET /api/v1/transfers` | Read a single Transfer or scoped bounded active/history list. |
| `POST /api/v1/transfers/{transferId}/departure` | Report departure/in-transit fact. |
| `POST /api/v1/transfers/{transferId}/interruption` | Report an interruption/abort and attributed outcome. |
| `POST /api/v1/transfers/{transferId}/arrival` | Record actual Arrival and recorded times. |
| `POST /api/v1/transfers/{transferId}/handover` | Record receiving Handover acknowledgement. |
| `POST /api/v1/transfers/{transferId}/completion` | Close the coordination lifecycle after Handover. |
| `POST /api/v1/transfers/{transferId}/cancellation` | Explicit pre-departure cancellation; response may remain pending while Capacity release reconciles. |

All transition commands require `Idempotency-Key`, an expected request/Transfer version, and the relevant Phase 2 authority; bodies carry structured reason codes, occurrence time, source reference, and only permitted coordination metadata. Capacity Reservation remains an in-process Capacity application contract, not a direct Transfer route. Incoming/active lists have deterministic order (pending requests by expiry then request ID; Transfer history by event time then aggregate sequence), bounded page size and allowlisted filters. List/query persistence projections belong to Transfer and may not read Referral/Capacity tables.

Errors include `TRANSFER_REQUEST_NOT_FOUND`, `TRANSFER_REQUEST_EXPIRED`, `TRANSFER_REQUEST_NOT_PENDING`, `TRANSFER_EXPIRY_POLICY_UNCONFIGURED`, `TRANSFER_ASSESSMENT_REQUIRED`, `TRANSFER_ASSESSMENT_NOT_POSITIVE`, `TRANSFER_DESTINATION_NOT_ELIGIBLE`, `TRANSFER_RECOMMENDATION_STALE`, `TRANSFER_ACTIVE_ATTEMPT_EXISTS`, `TRANSFER_ACCEPTANCE_CONFLICT`, `TRANSFER_RESERVATION_CONFLICT`, `TRANSFER_RESERVATION_UNKNOWN`, `TRANSFER_STATE_CONFLICT`, `TRANSFER_CANCELLATION_PENDING_RELEASE`, `TRANSFER_ARRIVAL_INVALID`, `TRANSFER_HANDOVER_REQUIRED`, `TRANSFER_SCOPE_MISMATCH`, and safe `INTERNAL_ERROR`. Capacity's errors retain `CAPACITY_*` owner codes. Do not expose internal source payload, raw SQL, credentials, or sensitive policy details.

## Events and transaction boundaries

Phase 7's justified events (all `*.v1`, minimal, with Phase 2 correlation/causation/actor/scope/aggregate sequence and no patient clinical payload):

| Event | Owner → consumer | Trigger | Ordering / privacy |
| --- | --- | --- | --- |
| `referral.cancelled.v1` | Referral → Transfer | Referral cancellation committed; Transfer idempotently withdraws pending attempt or reconciles active hold/movement. | Referral aggregate sequence; restricted reason/reference/attribution, no patient context. |
| `capacity.reservation-expired.v1` | Capacity → Transfer | Capacity owner expired a hold; Transfer reconciles the referenced Transfer. | Reservation aggregate sequence; reservation/transfer references and expiry only. |
| `capacity.reservation-at-risk.v1` | Capacity → Transfer | Capacity evidence undermines current hold; Transfer records exception/reconfirmation need. | Reservation aggregate sequence; evidence version/reason only, no raw Capacity payload. |
| `transfer.completed.v1` | Transfer → Referral | Transfer reached Handover and explicit completion; Referral resolves idempotently. | TransferJourney aggregate sequence; outcome/reference/attribution only. |
| `transfer.unfulfilled.v1` | Transfer → Referral | Authorized terminal closure states no Transfer fulfilled the active Referral; Referral resolves `UNFULFILLED` idempotently. | TransferJourney aggregate sequence; outcome/reference/attribution only. |

Every consumer runs with an explicit, narrowly scoped service identity and validated organization/facility context; it does not borrow a human's grant. A cancellation/completion event arriving after the receiver's terminal state is durably handled as an audited no-state-change/reconciliation outcome and cannot reopen or overwrite that state. Duplicate delivery returns its owner-local recorded outcome. There is no global event order.

Phase 5 may produce `referral.cancelled.v1` before the Phase 7 Transfer consumer is installed. Such events remain in the explicit `WAITING_FOR_CONSUMER` state defined by the platform eventing contract. When Phase 7 adds the Transfer handler, previously waiting events are not automatically replayed: no TransferJourney or request could have existed before Phase 7. New cancellation events use the registered handler. Phase 7 does not add change-event dependencies for Directory, Capacity, or Matching; it revalidates owner facts synchronously.

No event for every assessment, rejection, Arrival, or command is needed by a current asynchronous consumer. Those facts remain queryable and audited in Transfer. Directory/Matching/Capacity queries and reservation use owner contracts. Each context transaction commits its state, required audit and owner-local idempotency receipt atomically; when the transaction emits an event defined in the catalog above, its outbox intent commits in that same transaction. Capacity's reservation transaction is separate from Transfer's acceptance/transfer transaction. Consumer effect and dedup receipt commit together under consumer ownership.

## Persistence, data minimization and observability

Transfer owns its `transfer` schema and no foreign keys into Referral/Matching/Capacity/Directory. Its private state includes requests, assessments, Acceptance, status history, TransferJourney references, Capacity reservation outcome references, Arrival/Handover/Completion facts, and minimal correction/provenance history. It never stores a full chart, clinical note or attachment. The sole PatientReference is carried from Referral only in authorized case views; Matching snapshots and Capacity state do not contain it. Audit stores restricted target/actor/context/outcome metadata. Normal logs/metrics/traces use operation, normalized route, result class, duration, and correlation; no patient/case IDs as metric labels and no request/response bodies.

Metrics may count pending requests, reservation conflict/risk totals, and state transition failures with bounded labels. Exact latency/SLO/cardinality targets need measured workload and deployment evidence. No availability, throughput, clinical outcome, or nationwide scale claim is made.

## Concurrency and recovery

- Request accept/reject/expire/withdraw compare-and-set against the pending attempt version; one terminal transition wins.
- Journey sequencing and one-active-attempt invariant are enforced transactionally by Transfer owner state; no two destinations are simultaneously pending.
- Transfer progress commands compare current state/version; duplicate commands replay, conflicting older messages are rejected or added as explicit late factual corrections.
- Reservation last-unit race is serialized in Capacity using current usable evidence and active holds. Exactly one request succeeds for one available coordination unit.
- Cancellation versus departure: one Transfer transition wins; if cancellation commits first, departure is rejected; if departure commits first, cancellation cannot erase movement and uses interruption/reporting.
- Capacity reservation vs capacity update: both serialize under Capacity's owner transaction and version; if update wins, reservation uses new evidence; if reservation wins and subsequent evidence changes, emit `AT_RISK`.
- Rerouting cannot begin while a previous pending request or unreconciled active hold exists.
- Restart/outbox recovery replays events at least once; idempotent consumer effects and owner queries restore transfer/referral views. Never infer completion from outbox delivery or HTTP acknowledgement.

## TDD seams and end-to-end suite

Use local disposable PostgreSQL/PostGIS, synthetic IDs and test-scoped IdP tokens/fixtures; do not use public healthcare servers or real patient data.

1. Before a request, only `APPLICABLE` from synchronous Matching revalidation permits the exact recommendation candidate/Referral revision; revised/cancelled/resolved Referral, inactive Facility, invalid capability assertion, stale/not-ready/unavailable mapped Capacity evidence, retired policy/mapping, expired age, or unavailable owner response blocks request with the documented non-positive result. Historical snapshot remains readable and unchanged.
2. Pending request, separate assessments, positive Acceptance; negative/indeterminate assessment cannot accept.
3. Rejection, timeout, withdrawal, late acceptance and accept/expire/withdraw race; exactly one terminal request outcome.
4. Acceptance commits, Capacity refuses due to changed evidence; Transfer remains accepted/unreserved; idempotent query/retry does not create duplicate hold.
5. Two Transfer requests race for one coordination unit; exactly one Reservation; test same/different idempotency key behavior.
6. Crash after Capacity hold but before Transfer records response; retry resolves same hold. Crash after Transfer mutation but before event delivery; outbox recovery and consumer dedup behave per Phase 2.
7. Reservation expiry, release and at-risk after later evidence; no false READY/physical guarantee; cancellation remains pending until release reconciles.
8. B rejection → C acceptance/reservation conflict → D success; attempt history and recommendation evidence remain intact.
9. Arrival before digital reservation/departure update is recorded with actual and recorded times; no fabricated earlier state. Duplicate/delayed Arrival and Handover preserve chronology.
10. Arrival does not imply handover/completion; only receiving acknowledgement moves to HANDED_OVER, explicit completion closes.
11. Grant revocation, cross-org scope, audit failure, idempotent command retry, event duplicate/recovery and safe error/log behavior.
12. Architecture rules prove Transfer has no Capacity persistence access, no Referral/Matching internals, and no FHIR/EMS dependencies; no integration relies on Directory/Capacity invalidation events.

## Implementation order and Phase 7 acceptance gate

Recommended: request/attempt model and invariant → assessment/acceptance transitions → TransferJourney and state history → Capacity reservation contract/recovery → expiry/cancellation/reroute → Arrival/Handover/Completion → API/query projections → race and end-to-end recovery suite. Phases 3–6 must be frozen first. Within this phase, independent test/documentation work may proceed after its contract is frozen, but do not split a shared state invariant across concurrent implementation changes.

**PASS only if** happy and failure journeys work on local infrastructure; state ownership remains separate; exactly one last-unit Reservation wins; acceptance/reservation/arrival/handover remain distinct; lost responses, retries, duplicates, delayed messages and restarts preserve evidence; authorization and audit are enforced; no patient payload or sensitive identity leaks into telemetry; the accepted, rejected, expired, cancelled and completed histories reconcile; no dispatch/standards/business scope outside Phase 7 appears.

## Explicit non-goals

No EMS, ambulance, transport assignment, fleet/location tracking, dispatch command, complete clinical handover note, patient chart, public FHIR, DHIS2, OpenHIE/OpenHIM connector, notification provider, frontend, analytics, broker, workflow engine, or Volume III implementation.
