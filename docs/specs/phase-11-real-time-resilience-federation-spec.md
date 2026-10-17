# Phase 11 Real-Time Operations Resilience and Federation Specification

## Status and objective

**Design status:** canonical specification frozen only after Volume IV design review. **Implementation status:** not started and not authorized by this design document.

Phase 11 improves timely operational updates, failure recovery and cooperation between independently administered AyuVahak deployments. It preserves the existing owner contexts and keeps each deployment autonomous. The committed owner database state remains the business source of truth.

## Problem and user outcome

Operators need to notice coordination changes promptly without trusting a pushed message as truth. Origin and receiving deployments need to exchange a small number of explicit coordination operations without shared databases or unrestricted replication. Low-connectivity deployments must remain truthful when remote authorities are unreachable.

## Scope

- Optional server-to-client change hints, reconnect and bounded replay.
- Bounded recovery, backpressure and degraded behavior.
- Explicit deployment identity and trusted-peer configuration using Phase 9 safeguards.
- Named federated Directory discovery, remote Capacity evidence/reservation, and receiving Transfer coordination contracts.
- Stable operation identity, deduplication, source version/provenance and unknown-outcome reconciliation.
- Local-only acceptance with synthetic identities and controlled peer endpoints.

## Non-goals

- Shared federation database, cross-deployment SQL, private table replication, general multi-master replication, event sourcing, distributed 2PC, or global event order.
- Mandatory Kafka, RabbitMQ, NATS, Redis, Kubernetes, service mesh or microservice rewrite.
- Generic peer proxy or arbitrary business-caller URL.
- Patient/clinical data federation by default, patient-context retrieval, or IPS propagation.
- Notification delivery as business truth or permanent UI event history.
- Unbounded offline editing/conflict-free replication of Referral, Transfer, Capacity or Transport aggregates.
- Live partner/federation acceptance target, public service, cloud service or public healthcare endpoint.

## User stories

1. As an authorized operations user, I want a timely hint that an allowed coordination resource changed so that I can refresh its current owner-authorized state.
2. As a disconnected user, I want a bounded reconnect cursor or explicit refresh instruction so that dropped notifications do not appear as current facts.
3. As an operator, I want a slow or revoked client stream isolated so that it cannot block business transactions or other subscribers.
4. As a deployment administrator, I want to establish and revoke a peer relationship with explicit operations and scopes so that authentication does not grant broad business authority.
5. As an origin coordinator, I want to request receiving-side coordination using a stable operation identity so that retries after a lost response recover the same outcome.
6. As a receiving organization, I want its local Transfer and Capacity owners to decide and record assessments, acceptance and reservations so that the origin cannot mutate remote private state.
7. As a matching/coordination workflow, I want remote Capacity evidence to carry provenance and a freshness deadline so that stale information cannot appear ready.
8. As an operator during an outage, I want remote-authority operations to remain pending or unknown while local owner facts remain valid so that a partition does not fabricate success or failure.
9. As a deployment administrator, I want peer revocation to stop new inbound work and queued retries while preserving history so that trust changes are effective without erasing evidence.
10. As a support engineer, I want source and local timestamps plus owner sequence information so that clock skew does not reorder facts.

## Source-of-truth and delivery rules

1. The owner transaction commits business truth in its owner database.
2. Existing Phase 2 outbox events remain internal durable propagation, at-least-once and consumer-deduplicated. Per-aggregate order is used only where already required; no global ordering is assumed.
3. Client notifications are non-authoritative hints. A client fetches the current resource through an authorized owner API.
4. Federated operations are named requests/responses through Phase 9. Owner context validates meaning, scope, state and provenance.
5. External partner exchange remains governed by Phase 9 and caller-owner semantics.

## Interactive update contract

### Initial mechanism

Use optional SSE for one-way server-to-client hints. Use conditional polling and ordinary authorized queries as fallback. Do not add WebSocket: Phase 11 has no approved requirement for bidirectional persistent application messaging.

### Notification shape

The payload is limited to a safe notification type, owner resource type, opaque internal resource ID, owner version, scope-bound sequence/cursor and correlation metadata. It must not contain PatientReference, clinical content, an aggregate, a complete domain event, a bearer token, or an external identifier that broadens access. It is a hint that the resource may have changed.

### Authorization and scope

- Authenticate and authorize the requested organization/facility/resource scope when opening a stream.
- Reject wildcard/global scopes unless the active AyuVahak grant explicitly allows that scope.
- Use the normal HTTP authentication boundary; do not put bearer credentials or replay cursors in query strings.
- Revalidate on reconnect and authoritative resource retrieval.
- Bound connection lifetime and reauthorization interval; close or expire an active connection after grant revocation within that bound.

### Replay and backpressure

- Use a cursor scoped to the authorized subscription; cursor sequence is monotonic within that bounded scope, not globally serialized across the platform.
- Retain only a bounded notification projection/window. Expired, invalid, or wrong-scope cursor returns `refresh-required` and the client performs a full authorized refresh.
- Deduplicate projection input by internal event ID. If projection state is lost/reset, invalidate its cursor epoch and require full refresh; do not assume already-delivered or expired outbox rows can rebuild it.
- Bound per-client queue, replay count, response bytes, connection count and executor use.
- Coalesce newer hints for the same resource when safe. If a queue remains full, send `refresh-required` where possible and disconnect.
- Never block an owner commit, outbox worker or global event dispatch on a client.
- SSE write failures, process restarts, and dropped messages are recovered by cursor replay where retained or by refresh/polling.

PostgreSQL `LISTEN`/`NOTIFY` is optional worker wake-up only. The payload carries no business or patient data. Durable outbox/projection state is inspected after listener start/reconnect and remains the recovery source.

## Resilience and low-connectivity behavior

| Failure | Required behavior |
|---|---|
| Database unavailable | Fail readiness; reject owner writes that cannot commit; never return successful mutation response |
| Notification service/projection unavailable | Owner transactions remain valid; expose degraded freshness and fall back to authorized polling/full refresh |
| Outbox worker delayed/restarted | Owner state remains committed; durable pending work resumes with dedup and bounded retry |
| Federation peer unavailable before request | Local-only actions may continue; remote-authority action remains pending/unavailable |
| Response lost after remote operation may have committed | Persist `OUTCOME_UNKNOWN`, query/retry same stable operation ID; never infer accepted/rejected |
| Phase 9 external exchange unavailable | Do not corrupt or roll back unrelated valid owner state; follow owner-specific prerequisite and reconciliation rules |
| Remote evidence expired, source revoked or partial | Mark `STALE`/`UNKNOWN`; do not treat as zero, ready or eligible |
| Clock skew | Record source and local timestamps; order by owner sequence/version where available, not peer wall clock alone |

Offline operation is limited to facts for which the local deployment is the authority. Remote-dependent operations do not become locally authoritative by being queued. Informational cached data is labeled with source and freshness.

## Federation identity and trust

`DeploymentIdentity` is a stable deployment-instance identity, distinct from Organization, Facility, user and partner identities. It is paired with administrator-controlled Phase 9 trust configuration containing peer identity, associated authority/organizations, permitted context/operation/direction/scope, protocol/profile version, configuration version and secret/key references. A cloned restored deployment must not use an identity active elsewhere. Trust can be activated, suspended or revoked; revocation blocks new inbound operations, outbound attempts and retries, and invalidates reusable credentials through Phase 9 while preserving historical state.

Every remote reference is `(deploymentId, context, resourceId)` and may include source version/provenance. UUID equality does not imply identity or authority.

## Federated owner authority

| Fact | Authority |
|---|---|
| Directory facility/service fact | Directory deployment/source that owns the fact |
| Capacity evidence and reservation | Capacity owner at the deployment responsible for the facility |
| Referral and PatientReference | Origin deployment |
| Canonical Transfer journey, destination attempt order, journey chronology | Origin/coordinating deployment |
| Receiving clinical/operational assessment and Acceptance | Receiving deployment's Transfer owner |
| Receiving reservation | Receiving deployment's Capacity owner |
| Transport assignment/status | The one Transport deployment that coordinates that movement; no replicated fleet/provider state |
| Analytics aggregate | Analytics deployment that computes the versioned snapshot, with input/source provenance |

The receiving deployment stores only its local receiving request, distinct clinical/operational assessments and local Acceptance decision. After Acceptance, receiving Transfer invokes its own Capacity owner contract; reservation failure remains accepted-but-unreserved under Volume II semantics and is recovered with the Capacity operation identity. The origin stores the remote outcome as an attributed sourced fact; it does not clone or directly mutate the receiving Transfer aggregate. PatientReference is never sent in a federation message. If selected-recipient context is needed, the existing Phase 8/9 Referral disclosure pathway remains separate and requires Transfer participant validation, Referral authorization and audit.

## Federated operation and remote reservation

The origin creates a stable operation ID before send and records peer, owner operation, correlation/causation, payload fingerprint and configuration version. A receiving owner authenticates the peer, checks the current trust profile and owner-level scope/operation authority, validates semantics, applies an owner-local transaction, writes its outbox intent atomically where needed, and records a dedup receipt. Transfer assessment/Acceptance and Capacity reservation remain separate owner effects and transactions. Stable duplicate identity/fingerprint recovers the prior result; changed fingerprint is conflict/security audit.

For remote receiving/reservation, origin Transfer calls the receiving Transfer owner. The receiving owner invokes its local Capacity owner using the approved Phase 7 contract. Capacity commits locally and returns a local reservation reference/outcome. The origin records the namespaced remote reference and outcome; it never shares SQL or uses 2PC. Lost response is `OUTCOME_UNKNOWN`; status lookup uses the same operation ID. Until resolved, conflicting new destination/reservation attempts remain blocked according to Transfer invariants.

Cross-deployment messages are at-least-once. Dedup key includes authenticated peer, operation/message ID and content fingerprint. Owner version/sequence controls ordering where present. Source `occurredAt` and peer `recordedAt` are retained with local `receivedAt` and `recordedAt`; timestamps are evidence only.

## Privacy, audit and observability

Federation messages contain only opaque coordination references, origin/destination scope, bounded operational state/reason codes, owner version/sequence, source times and correlation/causation. They exclude PatientReference, IPS, clinical summary, diagnosis, names, attachments, raw owner payloads, tokens and credentials. Sensitive peer operations are audited at both owner boundaries. Logs and metrics use bounded partner/operation/outcome values and do not label patient/referral/transfer IDs or raw URLs.

## Architecture enforcement requirements

- Notification and federation adapters do not access another context's persistence.
- Domain packages do not depend on SSE/HTTP/Phase 9 transport types.
- Federation code cannot mutate another deployment's database or bypass its owner command.
- Owner domains remain independent of the notification projection and Analytics.
- No Volume V packages or generic multi-master synchronization API.

## Acceptance criteria

Acceptance runs with multiple local application instances or controlled peer test doubles, synthetic identities and disposable local PostgreSQL only. No public healthcare system, cloud service or external peer is required.

1. Two authorized local clients receive a bounded resource/version hint after an owner commit; neither notification is treated as the resource state.
2. A disconnected client reconnects with an in-window cursor and receives bounded replay; an expired cursor receives `refresh-required` and a full authorized refresh.
3. A grant revoked while connected causes the stream to close/expire within its documented bound; reconnect and resource fetch are denied.
4. A slow client reaches its queue bound without delaying owner writes or other clients; the queue coalesces or emits refresh-required and disconnects.
5. Notification process restart, delayed event worker, dropped wake-up and projection rebuild preserve committed owner state and resume bounded delivery.
6. A peer unavailable before a remote operation yields a pending/unavailable result while local-authority operations continue.
7. Remote acceptance/reservation committed and response dropped yields `OUTCOME_UNKNOWN`; same operation ID status recovery returns the recorded outcome without duplicate reservation.
8. Duplicate exact federated request returns original outcome; same peer/ID with changed fingerprint conflicts and is audited.
9. Revoked peer cannot start new inbound/outbound operation or retry queued work; existing evidence and unknown state remain.
10. Older source sequence after a newer one cannot overwrite owner state; skewed peer timestamps remain attributed evidence.
11. Remote Capacity expiration, partial response, policy mismatch and peer outage produce STALE/UNKNOWN, never READY/zero.
12. Cross-deployment Referral/Transfer scenario retains origin Referral and coordinating Transfer ownership, receiving assessment/Acceptance and reservation ownership, namespaced IDs, causal linkage and no patient content.
13. A Transfer represented at origin and receiving sides does not silently create duplicate aggregate attribution in a test reporting input.
14. Static architecture checks reject cross-context persistence imports, domain HTTP/SSE dependencies and shared-database federation.
15. A valid local owner write succeeds while the notification feed is stopped; the current authorized owner query returns the committed value and connected clients can refresh/poll without an event-delivery dependency.
16. A remote operation held pending during a simulated partition resumes after reconnection with the same operation ID, source identity and causal linkage; local-only actions remain available.

## Test seams

Test at the owner application contract, Phase 9 local exchange, notification feed/query API, and outbox consumer boundary. Use real local PostgreSQL for transaction, cursor, dedup, restart and reservation semantics. Use deterministic local clocks/resolvers and controlled in-process/mock HTTP peers. Public endpoints are forbidden; tests must fail closed on non-local configured targets.

## Operational limits to configure

Deployment configuration supplies connection lifetime, cursor retention, queue/replay/byte limits, peer operation timeouts, freshness TTLs and retry ceilings. Values must be bounded, observable and have safe defaults; no unbounded stream, replay or retry is permitted.

## Further reading

- [Volume IV architecture](../architecture/volume-4-resilient-operations-federation-public-health.md)
- [Context contracts](../architecture/volume-4-context-contracts.md)
- [State machines](../architecture/volume-4-state-machines.md)
- [Privacy matrix](../architecture/volume-4-privacy-matrix.md)
- [Threat model](../architecture/volume-4-threat-model.md)
- [Official research](../research/volume-4-technical-research.md)
