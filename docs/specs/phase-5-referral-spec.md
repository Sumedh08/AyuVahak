# Phase 5 Specification — Referral

**Volume:** II — Core Care Coordination  
**Phase boundary:** Referral and Care Requirement only; matching and transfer remain future phases.  
**Architecture references:** [Volume II architecture](../architecture/volume-2-core-care-coordination.md), [state machines](../architecture/volume-2-state-machines.md), [context contracts](../architecture/volume-2-context-contracts.md), [Phase 2 kernel specification](phase-2-platform-kernel-spec.md), [data ownership](../architecture/data-ownership.md), [ADR 0020](../adr/0020-declared-future-consumer-delivery-state.md).

## Objective and outcome

Represent an authorized cross-facility care coordination request in the terms supplied by its originating care team. Phase 5 supports create, submit, revise, cancel and resolve Referral state independently of matching. It does not infer care need or create a clinical record.

## Scope and exclusions

**Included:** Referral, immutable submitted revisions, structured CareRequirement, issuer-scoped PatientReference, source/origin attribution, urgency/timing/constraints, status lifecycle, scoped command/query API, audit/provenance/idempotency, owner schema and migrations, the approved `referral.cancelled.v1` deferred-consumer contract and required forward migration adding `WAITING_FOR_CONSUMER` to platform eventing, failure and privacy tests.

**Excluded:** Matching/recommendation, Directory or Capacity implementation changes, transfer attempts, Acceptance/Reservation, clinical diagnosis/triage, patient identity resolution, demographic/clinical chart, free-form clinical notes, attachments, IPS/FHIR exchange, notifications, frontend, offline synchronization, or automatic closure from delivery acknowledgement.

## Domain model

| Concept | Contract |
| --- | --- |
| Referral | One coordination request owned by AyuVahak only when it is the deployment's selected workflow owner. It references the origin and responsible actor/system and has a state and versioned submitted content. |
| CareRequirement | Structured set of required CareCapability codes, explicit hard constraints, timing window, and an optional deployment-coded coordination priority as asserted by the origin. It contains no CapacityDimensions or capability-to-dimension mapping; the mapping is a versioned Matching policy in Phase 6. It is not diagnosis, inferred clinical need, or a recommendation. |
| PatientReference | Issuer/system + resource type + opaque source identifier, optionally an opaque source encounter/case reference. No AyuVahak patient entity, identity merge, MPI, or national identifier is created. |
| SubmittedReferralRevision | Immutable snapshot of the origin, CareRequirement, PatientReference, source context and submit time. Amendment creates a new revision. |
| ReferralResolution | Explicit outcome `FULFILLED` or `UNFULFILLED`, reason code, time and actor/source; not a clinical outcome. |

The Phase 5 default stores **only** the PatientReference and coordination fields above. It does not persist name, date of birth, address, government ID, diagnosis, medication, observations, clinical narrative, attachments, or a patient summary. If a future deployment requires bounded clinical attributes for an identified safety purpose, that requires a reviewed privacy/legal basis and a new explicit contract; it is not silently added to the referral. Receiving clinical authorities obtain clinical detail through the source system or authorized direct/out-of-band clinical exchange and record only the separate assessment result/reason code in AyuVahak.

CareRequirement requires at least one active Directory CareCapability code. Hard constraints are typed and versioned (e.g. permitted geography, requested time window, explicitly selected destination organization); no arbitrary JSON or clinical DSL. Any priority is an origin-asserted code whose values/meaning are set by deployment policy; AyuVahak does not compute it. Referral validation never calls Capacity and never stores operational dimension requirements.

## Lifecycle and invariants

Referral state is `DRAFT`, `SUBMITTED`, `CANCELLED`, or `RESOLVED`. Draft edits change only draft version. Submit validates origin, required capability codes and caller scope, then creates an immutable submitted revision. An amended submitted Referral creates a new immutable revision through an explicit command; prior revisions and their recommendation evidence are retained. It does not “last write wins.”

- Only `DRAFT` can be initially submitted; duplicate exact submit retry replays the original result.
- `SUBMITTED` is the active coordination state. During Phase 5, no Transfer context exists, so amendment is governed by Referral's own state/version rules. Phase 7 activates a Transfer-owned per-Referral coordination guard before Referral locks or changes a submitted revision, followed by the `GetReferralAmendmentStatus` owner query; the guard remains held through the Referral transaction without sharing a cross-context transaction. `ACTIVE_COORDINATION` blocks the amendment, and an unavailable guard/status result fails closed without changing Referral state. The owner query uses the Journey's exact origin scope, including organization/facility scope kind and optional facility reference. Referral does not copy Transfer state or read Transfer persistence.
- Cancel is explicit and remains `CANCELLED`; it is not deletion. The approved same-Volume future-consumer exception permits `referral.cancelled.v1` in Phase 5 even though Transfer arrives in Phase 7. It is durably recorded atomically with cancellation and remains explicitly waiting, not failed, while no real Transfer handler is installed. Cancellation correctness does not depend on acknowledgement.
- Resolve is explicit `FULFILLED` or `UNFULFILLED`; only Transfer's completed/unfulfilled outcome, or an authorized origin-side closure when no pending transfer exists, may resolve it. Resolution is terminal.
- Concurrent submit/edit/cancel uses optimistic Referral revision/state check. The loser receives `REFERRAL_VERSION_CONFLICT`/`REFERRAL_STATE_CONFLICT` and must reload; no silent overwrite.
- A Referral cannot change owner/origin after submit. Cross-organization actors require a grant in the submitted execution scope.

The state diagram and delayed event behavior are normative in [Volume II state machines](../architecture/volume-2-state-machines.md).

## Commands and queries

### Commands

| Command | Behavior and authorization |
| --- | --- |
| `CreateReferralDraft` | Authorized originating organization/facility and `ReferralCreate`; idempotent when externally retried. Creates minimal draft. |
| `UpdateReferralDraft` | Only current draft version; `ReferralEdit`; validates typed fields and capability references. |
| `SubmitReferral` | `ReferralSubmit`; validates Directory origin and active capability codes; creates immutable revision; REQUIRED idempotency. |
| `AmendSubmittedReferral` | `ReferralAmend`; creates a new revision and records reason; REQUIRED idempotency. Phase 5 has no Transfer context/query. In the Phase 7 runtime, Referral acquires the Transfer-owned per-Referral coordination guard before draft locking, then checks the Transfer-owned `GetReferralAmendmentStatus` contract; active coordination returns `REFERRAL_AMENDMENT_BLOCKED`, and unavailable guard/status returns a safe service failure. The guard remains held through Referral transaction completion. |
| `CancelReferral` | `ReferralCancel`; explicit reason, state/version check, audit/outbox atomically; REQUIRED idempotency. |
| `ResolveReferral` | `ReferralResolve`; explicit `FULFILLED`/`UNFULFILLED`, allowed only under resolution policy; REQUIRED idempotency. |

Functional authority names describe policy functions, not job titles. A deployment maps its roles/teams/processes to them. Every action uses Phase 2 validated actor, organization, optional facility, target and purpose. Provider roles alone do not grant access.

### Queries

- `GetReferral` returns current state, origin, submitted revision number, minimum PatientReference and CareRequirement only to an authorized origin-side participant with `ReferralRead` in the validated origin organization or facility scope. A facility grant sees only that facility's referrals; an organization grant may see referrals from facilities in that organization. A receiving participant is not an origin-side participant and cannot read PatientReference through this query.
- `GetReferralRevision` returns one immutable revision and provenance under the same authorized origin-scope rule.
- `ListReferrals` requires an authorized origin organization/facility scope, allowlisted filters, and bounded pagination. It orders by the latest submitted time descending, using draft creation time for a Referral with no submitted revision, then by stable Referral ID ascending. Optional time bounds apply to that same relevant time. List items omit PatientReference and CareRequirement; no global anonymous list or generic BI query exists.
- `GetTransferBasis` is a future owner-produced application contract defined in the [cross-context contracts](../architecture/volume-2-context-contracts.md), not a callable Phase 5 runtime operation. Phase 5 exposes no recipient-facing query or unverified caller assertion. Phase 7 may activate the contract only after the Transfer owner can verify an actual Transfer Request, its selected recipient, and that recipient actor's case participation. The owner may then return the opaque source-scoped PatientReference only for that authorized purpose; it remains transient and is not persisted by Transfer.
- Phase 5 has no Matching query yet. Until Phase 6 exists, a submitted Referral remains active and its use is human/out-of-band.

### HTTP resource contract

| Method and route | Purpose |
| --- | --- |
| `POST /api/v1/referrals` | Create a scoped draft; requires `Idempotency-Key`. |
| `GET /api/v1/referrals/{referralId}` | Read current authorized Referral state and minimum PatientReference. |
| `PATCH /api/v1/referrals/{referralId}/draft` | Edit a draft with an expected-version precondition. |
| `POST /api/v1/referrals/{referralId}/submission` | Submit and create immutable revision; requires `Idempotency-Key`. |
| `POST /api/v1/referrals/{referralId}/revisions` | Amend a submitted Referral into a new immutable revision; requires reason and `Idempotency-Key`. |
| `POST /api/v1/referrals/{referralId}/cancellation` | Cancel with reason; requires `Idempotency-Key`. |
| `POST /api/v1/referrals/{referralId}/resolution` | Resolve `FULFILLED` or `UNFULFILLED` under state/policy rules; requires `Idempotency-Key`. |
| `GET /api/v1/referrals` | Bounded, scoped list by status/origin/time; stable order. |

Mutating requests include expected revision where applicable; a lost update returns `409` Problem Details with stable domain code and current safe version/reference. Patients and clinical text are not returned in listing views.

## Events and transaction boundaries

The only Phase 5 event is `referral.cancelled.v1`. Its Transfer consumer, purpose, ownership, minimal payload, privacy class, and delivery behavior are frozen in the same approved Volume II design; this is the sole approved future-consumer event introduced before its executable consumer. It contains Referral reference/revision, reason code, actor/source references, organization context, occurred/recorded times, correlation/causation, and producer aggregate sequence; it contains no PatientReference or clinical payload. Submission/amendment/resolution remain queryable facts and do not emit events for hypothetical consumers.

Cancellation plus required audit and outbox intent commit atomically. When the declared Transfer handler is not installed, Referral creates the delivery row directly in `WAITING_FOR_CONSUMER`; this is not a dispatcher failure transition or a delivery attempt. It has no handler attempt, failure category/log churn, automatic retry, consumer receipt, or replay eligibility. This is durable intentional deferral, not processing failure or delivery success. Cancellation and Referral state remain correct without Transfer. When Phase 7 is added, events published after the actual handler is registered are eligible for normal dispatch. Older `WAITING_FOR_CONSUMER` cancellation events are not automatically replayed: no TransferJourney existed before Phase 7, so they have no Transfer state to reconcile. They remain visible and retained under event-delivery policy. The `transfer.completed.v1` and `transfer.unfulfilled.v1` consumers may resolve only a currently `SUBMITTED` Referral. If cancellation or another terminal outcome already committed, an actual Phase 7 consumer records an audited no-state-change/reconciliation result and never reopens or overwrites the Referral.

Referral owns its logical schema and writes. Submit/revise/cancel/resolve state plus required audit and outbox intent commit atomically. No cross-context foreign keys or direct Directory/Transfer table reads. Directory capability/origin checks use application contracts. Idempotency receipt remains Referral-owned; consumer dedup for Transfer's cancellation reaction is Transfer-owned.

If Referral cancellation commits but event handling is waiting/unavailable, Referral remains cancelled; no Transfer context exists to acknowledge it in Phase 5. In Phase 7, the Transfer command rechecks Referral state before accepting a new action, so event lag cannot authorize a request. If a caller loses a command response, retrying the same key/fingerprint returns its prior safe result.

## Audit, provenance, privacy, errors

Audit create/submit/amend/cancel/resolve actions with actor/service, validated organization/facility, action, internal Referral target reference, purpose, timestamp, result and correlation. Provenance identifies the reporting source and correction reason. No patient data or PatientReference value goes into audit payloads, ordinary logs, generic events, metrics, or traces. PatientReference is sensitive and appears only in an authorized Referral view or a purpose-bound response to a selected receiving participant; no raw source document is stored.

Stable errors include `REFERRAL_NOT_FOUND`, `REFERRAL_STATE_CONFLICT`, `REFERRAL_VERSION_CONFLICT`, `REFERRAL_REQUIREMENT_INVALID`, `REFERRAL_CAPABILITY_UNKNOWN`, `REFERRAL_ORIGIN_INVALID`, `REFERRAL_AMENDMENT_BLOCKED`, `REFERRAL_ALREADY_CANCELLED`, `REFERRAL_ALREADY_RESOLVED`, `REFERRAL_SCOPE_MISMATCH`, and safe `INTERNAL_ERROR`. Responses extend RFC 9457 without raw exception details.

## Concurrency and time

Submit/edit uses a version check in the Referral transaction; exactly one competing revision wins. Cancel racing submit or amend is serialized by the same version/aggregate owner; retrying the losing operation sees explicit terminal/current state. Timestamps distinguish client-asserted care requirement time from server submit/record time and event occurrence from receipt. All instants are UTC; no cross-context global ordering is inferred from wall clocks.

## TDD seams and scenarios

1. Create/submit a synthetic referral with valid Directory capabilities and opaque fake PatientReference.
2. Reject missing required capability, inactive/unknown origin, malformed reference, invalid time window and invalid scope.
3. Confirm submitted content is immutable; amendment adds revision and does not alter prior revision.
4. Verify submit/edit, amend/cancel and duplicate/cancel races produce one valid state with explicit loser error.
5. Exact idempotency retry replays; key reuse with changed fingerprint conflicts; audit failure rolls back protected state.
6. Confirm no name, demographics, diagnosis, narrative, attachment, patient payload in database response snapshot contract, event, logs, metrics, or trace.
7. Verify cancellation event contains only minimum references and consumer retry/dedup seams are ready for Phase 7 without fake production consumer.
8. With no Transfer handler installed, cancellation commits with `WAITING_FOR_CONSUMER`; repeated dispatch does not increment attempts or create failure logs/consumer receipts, and Phase 7 activation does not automatically replay historical waiting events.
9. Verify authorized cross-organization reads/writes, bounded lists, RFC 9457 errors, correlation, and architecture rules against private schema access.

## Implementation order and Phase 5 exit gate

Recommended: structured CareRequirement and reference validation → Referral revision/state model → owner persistence and optimistic concurrency → Phase 2 auth/audit/idempotency → command/query application contract → API and acceptance. Phase 3 must be frozen; Phase 4 may be implemented independently, but neither Matching nor Transfer is required.

**PASS only if** an authorized synthetic origin can create, submit, amend and explicitly close a referral while preserving revision history; patient reference remains opaque/minimal; the lifecycle is race-safe and idempotent; audit and cancellation event behavior are recoverable; local PostgreSQL/API tests and architecture checks pass; no Matching or Transfer behavior is needed to pass.

## Explicit non-goals

No patient identity service, patient clinical record, inferred care requirement, recommendation, receiving decision, reservation, transfer, FHIR/IPS integration, attachment store, analytics, offline synchronization or implementation ticket.
