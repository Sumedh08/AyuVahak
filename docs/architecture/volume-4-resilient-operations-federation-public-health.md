# Volume IV Resilient Operations Federation and Public Health

## Purpose and status

This document defines the frozen design boundary for Phase 11, Real-Time Operations, Resilience and Federation, and Phase 12, Public Health Analytics and DHIS2 Integration. It extends the frozen Volumes I–III without changing their ownership or implementation. Phase 11/12 implementation has not started and requires separate human authorization.

Volume IV adds timely change notification, bounded cooperation between independently administered AyuVahak deployments, reproducible operational aggregates, and an approved aggregate export to DHIS2. PostgreSQL owner state remains authoritative. External systems remain independently administered and untrusted for business meaning.

## Product and ownership constraints

AyuVahak remains a care-coordination platform, not an EHR, HIE, distributed database, generic analytics product, public-health warehouse, or stream-processing platform. Frozen owners remain:

| Owner | Authoritative facts |
|---|---|
| Directory | Coordination organizations, facilities, locations, services, capabilities and provenance |
| Capacity | Operational evidence, readiness and reservations |
| Referral | Care requirement, referral lifecycle and opaque PatientReference |
| Matching | Candidate eligibility, recommendation and evidence |
| Transfer | Care-transition journey, receiving coordination and handover |
| Transport | Movement coordination and provider-neutral transport evidence |
| Platform Kernel | Identity, grants, audit, internal events/outbox and owner-local idempotency |
| Phase 9 exchange capability | Technical partner trust, endpoint, transport, authentication and bounded exchange safeguards; no business semantics |
| Analytics (introduced in Phase 12) | Versioned AyuVahak calculation definitions and derived reporting measures/snapshots, quality, release-set disclosure decisions, DHIS2 mapping and export-operation history; no source operational facts and no authority to define public-health interpretation |

No owner private repository or table becomes a cross-context API. No external standard or remote peer changes the home owner of an existing fact.

## Delivery classes

| Class | Purpose | Durability / authority |
|---|---|---|
| Owner transaction | Commit a business fact in its owning context | Durable authority in owner database |
| Internal event/outbox | Propagate committed owner facts to justified internal consumers | Existing Phase 2 transactional outbox, at-least-once, consumer-deduplicated; not a public bus |
| Client notification | Tell an authorized client that a resource may have changed | Best-effort hint; authoritative query remains required |
| Federated operation | Request or report one named operation between trusted deployments | At-least-once, peer-scoped identity, explicit owner response and reconciliation |
| External partner exchange | Technical request to a configured external partner | Phase 9 controls technical trust; calling context owns semantics and business outcome |
| Reporting fact/export | Reproducible aggregate computation and optional downstream delivery | Analytics snapshot is a derived record; DHIS2 is downstream and never operational authority |

These classes do not share one global delivery bus or retry policy.

## Phase 11 architecture

### Timely client updates

The initial interactive delivery is optional Server-Sent Events (SSE) for server-to-client hints, with authorized conditional polling as the fallback and recovery mechanism. The system does not require a browser to remain connected. A notification contains only a notification type, owner resource type, opaque internal resource ID, owner version, bounded scope sequence/cursor, and safe correlation metadata. It contains no patient reference, clinical payload, aggregate, or authority-bearing state.

The client then requests current state through its ordinary owner API. That API repeats authorization and scope checks. SSE does not become a command channel, event log, or source of truth. The connection is authenticated using the normal HTTP security boundary; credentials and cursors are not placed in URLs. Subscriptions are restricted to explicitly authorized scopes and short-lived connections. Authorization is checked at subscription, reconnect, and each resource query; grant revocation closes or expires a stream within the bounded reauthorization interval.

The internal outbox is adequate for durable internal event delivery and recovery. It is not a client replay log: its identifiers are not a global ordered cursor, delivered rows have bounded retention, and its events are not intended for public disclosure. A small platform-owned notification projection assigns monotonically increasing sequence values within a validated notification scope and retains only a bounded replay window. If projection state is lost or reset, its cursor epoch is invalidated and clients receive `refresh-required`; the design does not promise reconstruction from outbox rows that may already have expired. This affects freshness only, never business data. Cursor expiry returns `refresh-required` rather than an unbounded replay.

Per-client queues and replay batches are bounded. A slow client is coalesced to the newest resource version where safe; when a safe coalescing bound is exceeded, the server emits `refresh-required` if possible and closes the stream. It never blocks an owner transaction or global event worker. SSE write failure, notification worker outage, or projection loss leaves committed business writes valid; clients use conditional polling/full refresh.

PostgreSQL `LISTEN`/`NOTIFY` may be used only as a small post-commit worker wake-up hint. The durable outbox/projection remains the recovery source. Process restart, listener setup races, queue pressure, and duplicate/coalesced notifications cannot lose committed work.

### Resilience and bounded offline behavior

Database unavailability fails readiness and rejects writes that cannot commit. A notification failure degrades freshness only; owner queries remain authoritative. A Phase 9 partner outage does not roll back a valid local owner transaction unless a frozen owner rule makes the remote response a prerequisite. Remote-authority actions remain pending or outcome-unknown until reconciled. Informational remote views may be served only with source, `observedAt`, local `receivedAt`, policy version and `validUntil`; stale/unavailable remote Capacity is `STALE`/`UNKNOWN`, never zero or ready.

Low-connectivity operation is classified by authority: local owner facts may be committed locally; actions requiring a remote owner remain blocked/pending; cached remote facts are clearly stale and non-authoritative. There is no arbitrary offline multi-master editing or conflict-free replication of business aggregates.

### Federation

Federation is explicit named owner operations over Phase 9's secure exchange path between independently administered deployments. There is no shared database, private-table replication, global 2PC, implicit trust because software is AyuVahak, or general-purpose peer proxy. Each deployment has a stable `DeploymentIdentity` independent of its health-organization identity. Peer configuration identifies the authenticated deployment, associated authority/organizations, protocol profile, allowed context/operation/direction and scope, trust version, and Phase 9 credential references. A restored clone must not present the same active identity as another live deployment.

Remote references are namespaced `(deploymentId, context, resourceId)` values, optionally accompanied by owner version and source. UUID equality alone does not establish shared identity. Peer authentication identifies a technical peer; every owner contract separately checks operation authority, organization/facility scope, source freshness, policy and audit requirements.

Directory facts remain authoritative at the Directory deployment/source that owns them. A bounded remote Directory projection may retain namespaced identity, source, version/provenance, received time and expiry; it never becomes locally owned. Capacity and reservations remain authoritative at the receiving/owning deployment. A remote Capacity fact has source deployment, observed/received times, `validUntil`, source policy version and remote evidence reference. Expiry, peer outage, revocation, or sequence conflict yields stale/unknown evidence.

The originating deployment owns the Referral and its PatientReference. It also owns the canonical coordinating Transfer journey, destination attempts, journey ordering and local completion view. The receiving deployment owns a minimal `FederatedReceivingRequest`, its distinct local clinical/operational assessments and Acceptance decision. After Acceptance, receiving Transfer invokes its local Capacity owner contract for reservation; reservation failure remains accepted-but-unreserved under Volume II semantics and is recovered using the Capacity operation identity. Transfer and Capacity retain their own transaction and state ownership. A remote accepted or reserved response is a sourced fact; the origin records that outcome and does not clone the receiving aggregate. If selected-recipient patient context is required, it is retrieved only through the separately authorized Referral disclosure contract after Transfer participant validation; it is never embedded in federation messages. Receiving arrival evidence may be reported using a named contract; the origin Transfer validates, attributes and records its own Arrival. Handover remains Transfer-owned. Transport remains owned by the deployment coordinating that movement; no fleet/provider state is replicated.

Remote reservation is an asynchronous owner-to-owner operation, not a distributed transaction. The origin sends one stable operation ID to the receiving Transfer boundary. The receiving Transfer owner validates its local request and records its receiving decision; after Acceptance it invokes the receiving Capacity owner contract with a stable Capacity operation identity. Capacity commits its own reservation. Retrying either named operation recovers the same owner outcome or status. If a response is lost after remote acceptance/reservation, the origin records `OUTCOME_UNKNOWN`, queries the same operation identity, and blocks conflicting reroute/new attempts until reconciliation. A Capacity failure does not become READY and does not erase Acceptance. There is no 2PC or claim that a network timeout means rejection.

Federated messages are at-least-once. Receipts are scoped by authenticated peer, operation/message ID and payload fingerprint. Same identity/fingerprint returns the previous result; changed fingerprint conflicts and is audited. Source sequence/version outranks peer wall-clock order where available. The system records peer `occurredAt`/`recordedAt`, local `receivedAt`, and local `recordedAt`; cross-peer wall clocks alone never reorder business truth. Peer revocation blocks new inbound/outbound operations and retries, invalidates credentials through Phase 9, and preserves historical facts and unresolved unknown outcomes.

### Broker threshold

No Kafka, RabbitMQ, NATS, Redis, service mesh, Kubernetes requirement, or microservices rewrite is part of Phase 11. Reconsider a broker only if measured local acceptance/production-shaped load, after bounded indexes, worker tuning, and per-owner isolation, demonstrates that the existing PostgreSQL outbox cannot satisfy a documented throughput/latency/backlog recovery SLO, or if independently operated consumers need durable fan-out that cannot be safely expressed with the outbox. Any proposal must preserve owner-local transactions, deduplication, ordering requirements, privacy boundaries and local-only testability. A broker does not become business authority.

## Phase 12 architecture

### Analytics ownership and reporting inputs

Phase 12 formally introduces Analytics as a bounded context for derived, minimized, reproducible aggregate facts. It does not own Referral, Transfer, Capacity, Transport, patient data, operational decisions, or generic business intelligence. It does not synchronously gate owner transactions.

Analytics consumes bounded owner-produced reporting snapshot contracts. Each owner computes only approved measure keys within its own persistence boundary and returns a versioned measure result with dimensions, explicit period/scope, source cutoff or owner version/watermark, completeness/quality, and generated time. Analytics has no direct SQL access to Directory, Capacity, Referral, Matching, Transfer or Transport private tables and does not reconstruct raw events into an unofficial owner ledger. Reporting events/outbox may wake or sequence work, but the bounded owner snapshot contract supplies the authoritative reporting input.

Only approved bounded dimensions are allowed; free text, PatientReference, patient/case identifiers, IPS, narrative and arbitrary payload fields are prohibited. Each indicator defines attribution (origin, destination, or separately named dimensions) to prevent cross-deployment double counting. Analytics owns the AyuVahak calculation definition and version; the authorized public-health authority owns official indicator interpretation and target metadata. A local definition records the authority's approved identifier/version when applicable, but does not claim to establish official meaning.

Optional aggregate-to-aggregate federation is in Phase 12 scope. A deployment may submit an immutable aggregate snapshot to an explicitly trusted peer Analytics operation using Phase 9. The receiving Analytics context retains source deployment, source revision, definition fingerprint, period, attribution, dimensions and completeness. Combination is permitted only when an administrator-approved federation mapping establishes exact semantic compatibility across formula/version, timezone/period, attribution and dimensions; otherwise the input is retained as incompatible and not combined. Deduplication uses authenticated source deployment plus stable snapshot identity and content fingerprint. No raw owner facts or histories are exchanged, and federation does not imply a reporting hierarchy or authorize redistribution to DHIS2.

### Reproducible indicators and periods

An immutable `IndicatorDefinition` version records formula/numerator/denominator, source owner measure keys, filters, allowed dimensions, aggregation, reporting calendar, IANA time zone, missing-source semantics, completeness requirements, suppression policy, target mapping eligibility and effective interval. A formula or semantic change creates a new version; historical versions are never silently edited. Demonstration definitions use synthetic data and are not universal or national policy.

Each reporting period uses an explicit interval `[start, end)` in a named IANA time zone and stores the resolved UTC boundaries and time-zone database/version used. `AggregateSnapshot` records indicator/version, period, organization attribution, dimensions, result/status, source cutoff, input completeness, computation time and revision. Analytics retains the exact bounded owner-measure inputs and immutable source snapshot references needed to reproduce the result; it does not retain raw operational events or patient/case rows. Status distinguishes `COMPLETE`, `PARTIAL`, `MISSING_SOURCE`, `STALE_SOURCE`, and `NOT_APPLICABLE` as relevant. Missing/partial source is never converted to zero. Numeric zero is reportable only when the required sources are complete and the definition permits it.

Late facts produce a new snapshot revision within the configured correction window. A finalized period may be reopened only by an explicit authorized correction with cause, prior/new revision linkage and audit. An export already attempted or acknowledged is never erased or silently rewritten; a corrected snapshot has a new stable export identity. Indicator version and source cutoff make every value explainable.

### Aggregate privacy

Aggregate values are not assumed anonymous. Indicator definitions identify sensitive dimensions and require deployment-configured release-set disclosure control, not only per-cell thresholds. The policy must consider complementary cells, totals/subtotals, overlapping periods, dimensions, revisions, prior AyuVahak releases and any known external release baseline that could be differenced. If the external baseline is unknown, sensitive release requires a conservative approved coarsening or review; otherwise it is blocked. It may suppress complementary values, coarsen dimensions, withhold related outputs or require review. No universal threshold or algorithm is hard-coded. If a required policy, release history or safe suppression decision is unavailable, the related release set is not exportable and fails closed. Suppression and quality status are auditable; no free-text grouping is allowed.

DHIS2 export and federated Analytics publication share one Analytics-owned disclosure reservation gate. An immutable disclosure-lineage ID covers every output that may be jointly recombined across destinations, channels, periods, revisions or dimensional projections. The conservative default is the broader privacy-policy/release-scope lineage; narrower lineages require an approved proof of non-overlap. Each output maps to every affected lineage, including related totals, subtotals, complementary cells and alternate projections. If complete mapping cannot be proven, the broader lineage is used or the send fails closed; a narrower lineage is never chosen merely to increase concurrency. Before either path sends a value, a local owner transaction serializes all affected lineages, evaluates the candidate against disclosed values and in-flight reservations, and atomically records the policy/history checkpoint and reservation. All Phase 12 output paths use the same gate. Reservations are treated as potentially disclosed by later evaluations; acknowledged values become disclosed, and `OUTCOME_UNKNOWN` remains potentially disclosed. A reservation may be withdrawn only when the Phase 9 operation proves no request bytes could have left AyuVahak. Retries use the same reservation and operation identity. The lock is never held across network I/O; overlapping keys are acquired in deterministic order. Missing/ambiguous lineage mapping or unavailable reservation store blocks the send. This prevents concurrent individually-small exports from bypassing complementary suppression. Policy/mapping rollover locks old and proposed lineage IDs together and carries prior/in-flight disclosures forward; a policy revision cannot reset disclosure history.

### DHIS2 downstream adapter

The Phase 12 DHIS2 adapter is owned by Analytics and uses Phase 9 partner trust, TLS, endpoint validation, authentication, secret reference, payload bounds, and sanitized exchange telemetry. It supports the configured aggregate DataValueSet profile only. DHIS2 is downstream: successful delivery does not establish operational truth, source completeness, indicator correctness or care-coordination outcome.

The deployment preconfigures and approves target metadata. Versioned mappings bind Analytics indicator/version, organization/facility reference, bounded reporting dimension, reporting period and target partner to DHIS2 data element, organisation unit, category option combination, attribute option combination and dataset where required. External UIDs never become AyuVahak business identity. Phase 12 does not automatically create DHIS2 metadata, programs, data sets, indicators, users or category options.

The initial supported export operation is synchronous and contains exactly one DataValue in one DataValueSet request. Async-import options and job/task IDs are unsupported; a future asynchronous profile must persist the task identity and retain the tuple barrier until terminal task reconciliation. This avoids claiming that DHIS2's documented aggregate import summary provides an unambiguous per-value mapping for a multi-value request. Each operation is bound to one immutable snapshot revision and target DataValue identity. It records target partner/config version, mapping version, operation identity, attempt/outcome and the documented import summary without storing clinical data or unnecessary raw payload. `imported=1` or `updated=1` is delivery acknowledgment for that single value only; `ignored=1` is ambiguous and is not treated as success without an authorized read-back or explicit operator reconciliation. A future profile may allow larger batches only after its documented per-value contract and acceptance evidence make every outcome attributable.

DHIS2 documentation does not establish client-supplied idempotency or durable remote operation lookup for a lost response. Retries use the same local operation identity and are permitted only when the named target profile makes replacement of the exact same DataValue tuple safe. Otherwise outcome remains `OUTCOME_UNKNOWN` pending an authorized read-back or operator reconciliation; a newer value is never overwritten blindly. Corrections create an explicit new local revision and replace the configured target tuple only when the approved target profile establishes exclusive AyuVahak write ownership for the tuple or a documented conditional/versioned update guard. Otherwise automated writes are blocked and only an explicitly authorized operator-mediated path is available. A durable per-tuple barrier prevents a later revision from overtaking an in-flight/unknown older operation, and an unknown predecessor keeps that barrier across restart. Omission never means deletion or zero. Explicit deletion, if ever required, is a separate authorized correction operation and is not part of the default Phase 12 export. Zero requires complete source evidence, approved indicator semantics and known target `zeroIsSignificant` behavior.

The deployment's approved DHIS2 authentication profile is selected per partner and must be supported by Phase 9. No universal client-credentials grant is assumed; credentials remain secret references. Acceptance uses only a local deterministic mock with synthetic metadata and aggregate payloads. A developer-controlled disposable DHIS2 container is optional only if later justified; public/demo DHIS2 is never required.

## Required semantic boundaries

```text
notification ≠ business truth
event ≠ database transaction
cached remote fact ≠ current fact
federation ≠ shared database or unrestricted replication
peer authentication ≠ peer business authority
remote timeout ≠ rejection
analytics projection ≠ operational source of truth
aggregate ≠ automatically anonymous
missing ≠ zero
DHIS2 delivery ≠ indicator correctness
DHIS2 ≠ coordination authority
```

## Related design records

- [Phase 11 specification](../specs/phase-11-real-time-resilience-federation-spec.md)
- [Phase 12 specification](../specs/phase-12-public-health-analytics-dhis2-spec.md)
- [Context contracts](volume-4-context-contracts.md)
- [State machines](volume-4-state-machines.md)
- [Data ownership](volume-4-data-ownership.md)
- [Privacy matrix](volume-4-privacy-matrix.md)
- [Threat model](volume-4-threat-model.md)
- [Official technical research](../research/volume-4-technical-research.md)
