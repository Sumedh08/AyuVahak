# Phase 2 Specification — Platform Kernel, Identity & Trust

**Status:** Canonical Phase 2 implementation specification. Design audit and implementation-spec review are recorded in [the Phase 2 design review](../reviews/phase-2-design-review.md). Phase 2 implementation has not started.

## 1. Outcome

Phase 2 will add the smallest coherent platform kernel that later AyuVahak business contexts can trust. It provides explicit actor and organization context, external token authentication, local scoped authorization, durable audit behavior, provenance conventions, internal event contracts and publication recovery, separate command and consumer duplicate protection, and local verification of failure behavior. The conceptual interfaces and persistence owners for each capability are defined in the [Phase 2 architecture](../architecture/phase-2-platform-kernel.md); concrete Java types, production tables, and endpoints remain implementation work.

The product remains a modular monolith and coordination platform. See the [Phase 2 architecture](../architecture/phase-2-platform-kernel.md), [constitution](../architecture/constitution.md), [bounded contexts](../architecture/bounded-contexts.md), and [security and trust](../architecture/security-and-trust.md).

## 2. Scope and exclusions

### Included capabilities

1. Core primitives and actor/reference conventions.
2. Explicit, validated organization/execution context.
3. API resource-server authentication for human and machine access tokens.
4. AyuVahak-owned, scoped RBAC-first authorization over functional authorities.
5. Audit contracts, atomic audit for protected mutations, durable records for sensitive reads and security outcomes.
6. Provenance capture conventions stored with owning facts.
7. Internal domain-event contract and compatibility rules.
8. Transactional outbox, local dispatcher, retry/recovery, and per-aggregate order where required.
9. Idempotency and duplicate protection: command idempotency owned by command boundaries and consumer deduplication owned by each consumer.
10. Kernel failure/recovery verification using local PostgreSQL and local identity/test fixtures only.

### Explicit non-goals

- No user login UI, BFF, browser session architecture, or client-flow selection.
- No home-grown passwords, credentials, cryptography, policy engine, or professional registry.
- No SMART launch, FHIR, IPS, mCSD, DHIS2, HIE, EMS, notification, or partner connector; no patient-summary or clinical-attachment handling.
- No Facility Directory, Capacity, Referral, Matching/destination ranking, Transfer, Transport/ambulance dispatch, Notifications, Analytics, or public-health business behavior.
- No fake production modules, business entities, repositories, migration schemas, or business events to create test subjects.
- No AI/LLM, emergency break-glass workflow, offline synchronization, or federation implementation. Preserve only the authorization extension point needed for a later governed break-glass policy.
- No event sourcing, global event ordering, exactly-once delivery, distributed transaction, Kafka, or cross-context shared data store.
- No production retention periods, RPO/RTO, or scale targets without deployment evidence.
- No healthcare business modules, schemas, APIs, or workflows. Phase 2 implementation tickets are maintained locally under `.scratch/phase-2-platform-kernel/`; no external issue tracker is used.

## 3. Actors and boundaries

| Actor | Input/evidence | Phase 2 responsibility and limit |
| --- | --- | --- |
| Human API client | OIDC-issued bearer access token; explicit organization and optional facility selection/context. | The IdP authenticates the human. AyuVahak validates the token and separately evaluates local permission. Phase 2 does not implement the interactive login client. |
| Service client | Separately scoped OAuth2 client-credentials access token. | Authenticated as a machine principal with least privilege; never implicitly delegated a human's authority. |
| Identity provider | Issuer metadata/JWKS or operator-configured trusted signing-key location. | Trusted for authentication identity only; not the sole source of AyuVahak permissions. Keycloak is a local reference, not a production requirement. |
| Deployment administrator | Governed local organization/role/functional-authority mappings. | Configures/reviews grants and revocation. Local job titles and receiving functions are not frozen globally. |
| Later business context | A command/action, target, purpose, actor and execution context; explicit domain fact after commit. | Calls the authorization boundary, emits minimized audit facts, owns event meaning, and owns command/consumer receipts for its effects. |
| Audit reviewer/operator | Restricted request for audit chronology or delivery health. | Receives only authorized audit and operational information; normal diagnostics do not expose patient content. |
| Local test harness | Synthetic human/machine identities, test-only operation, local PostgreSQL/PostGIS, local Keycloak or deterministic token fixtures. | Demonstrates contracts and failures without contacting a public healthcare system or external IdP. |

## 3.1 Persistence ownership baseline

Phase 2 uses the existing single PostgreSQL database and three private schema owners: `identity_access` for issuer-scoped identity references, platform grants, the bootstrap marker, and grant-command idempotency receipts; `audit` for restricted append-oriented audit chronology; and `platform_eventing` for immutable event envelopes, delivery generations/attempts, and event-replay idempotency receipts. These names specify ownership; Flyway migrations create only schemas that have a real Phase 2 owner. Core primitives, execution context, provenance, and event-contract rules have no standalone schema. A consumer-dedup receipt is stored with its effect owner; Phase 2 uses a test-only fixture and creates no production business schema for it. No generic `shared` schema or cross-context persistence API exists. A capability must use the owning application boundary, never another schema's repository or tables directly. The Identity & Access application operation may enlist identity, audit, and outbox writes in one PostgreSQL transaction through narrow owner-facing capabilities. Non-mutating audit writes commit in a separate short transaction before protected data is returned. See [logical persistence ownership](../architecture/phase-2-platform-kernel.md#logical-persistence-ownership).

## 4. Behavioral requirements and seams

### 4.1 Core primitives

**Required behavior**

- Internal entity references use the frozen opaque UUIDv4 convention when a kernel-owned reference is necessary. External identifiers are `(issuer/source, resource type, value)` scoped references, never interchangeable with internal IDs.
- Time-sensitive records distinguish occurrence/observation time from receipt/recorded time and use an injectable time source for deterministic behavior.
- Correlation identifies a request/work chain; causation identifies the immediate triggering operation/event. Neither authenticates or authorizes.
- Organization, facility, actor, and external identifiers are references with explicit scope, not business entities or a shared master model.
- Domain-specific identifiers and facts remain owned by their context; core has no generic entity inheritance hierarchy, central ID registry, global service, or persistence store.

**Verification seam**

Unit tests use synthetic issuer/org/facility/actor references and a controllable clock. Verify external identifiers from different issuers remain distinct and event/recorded time remain distinguishable.

**Definition of done**

Identifier scope, time semantics, and correlation/causation distinctions are documented and usable without importing Spring, FHIR, or another business context. No healthcare business entity, global service, or shared persistence store is introduced.

### 4.2 Organization and execution context

**Required behavior**

- A context for protected work carries verified actor kind/reference, explicit validated organization, optional validated facility, correlation, request/source kind, and authentication-mechanism category. Purpose is an explicit operation input when policy requires it; target/resource/action remain operation inputs rather than ambient global state.
- Actors may have grants in more than one organization and may act in more than one facility. Scope is selected/derived and validated per action; it is not a single permanent actor property or a commitment to a complex multitenancy scheme.
- A caller-proposed organization/facility is untrusted until matched against active grants and, for facility scope, a trusted organization/facility relationship reference. Missing or conflicting scope denies.
- Context is transport-neutral. HTTP request handling may construct it after authentication; internal jobs and background workers supply it explicitly. Do not couple its definition to servlet/request classes or thread-local state.
- Asynchronous work carries minimized actor/service, organization, optional facility, correlation/causation, source, and purpose references needed for attribution. A worker has its own scoped service identity and never borrows the initiating human's authority by default.

**Verification seam**

Application-boundary tests prove missing, mismatched, unknown, or unauthorized organization/facility context is rejected; a multi-organization actor can act only in the selected granted scope; and background work requires explicit service/org scope without an HTTP request or ambient thread context.

**Definition of done**

Every protected operation and asynchronous worker receives explicit, server-validated context. Missing/untrusted scope denies, callers cannot elevate source/authentication-mechanism fields, and no standalone organization/facility registry or implicit context store is introduced.

### 4.3 Authentication

**Required behavior**

- Treat AyuVahak as an API resource server. Phase 2 uses the verified Phase 1 baseline Spring Boot 4.1.1 / managed Spring Security 7.1.1 and the local reference image `quay.io/keycloak/keycloak:26.8.0`. Deployments may use another compatible OIDC provider. Pin the local tag; verify its digest/provenance before first use. The local Keycloak service is loopback-bound and developer-controlled.
- Validate signed access tokens locally against the exact configured issuer, configured AyuVahak API audience, trusted key/signature, valid time bounds, and an explicit issuer-specific access-token profile. The initial Keycloak profile allows asymmetric `RS256`; never accept `none`, symmetric/HMAC algorithms, or an algorithm outside the configured allowlist.
- Reject ID Tokens as API credentials. Reject malformed, expired, not-yet-valid, wrong-issuer, wrong-audience, unsupported, unsigned, or unverifiable tokens.
- Reject tokens with missing, malformed, or ambiguous required identity claims; token syntax alone is not proof of a valid subject. The Keycloak profile uses issuer + `sub` for human identity and issuer + configured service-only `client_id` for a service principal. Human and service use separate client registrations; the same client ID cannot be classified as both. A different IdP must have an explicit claim/client mapping. Missing or ambiguous principal kind fails closed.
- Use a bounded in-process JWK-set cache of five minutes for the initial profile, matching Spring Security 7.1.1's documented default. A valid JWT whose key is already cached remains verifiable during a temporary IdP/JWKS outage while the key is within the configured cache validity. An unknown `kid` triggers at most one bounded refresh attempt per decoder refresh policy; if the trusted key source is unavailable, return a safe temporary authentication-unavailable failure and deny the request. If the source is reachable but does not publish that key, reject the credential. Do not trust unknown/stale keys or perform per-request token introspection.
- Application startup and liveness must not require the IdP to be reachable. Use configured issuer and JWK-set location/metadata with lazy discovery; token validation remains strict at request time.
- A local AyuVahak grant revocation denies subsequent protected actions, but local JWT validation does not guarantee immediate propagation of a per-user IdP token revocation. Deployment token lifetime, signing-key rotation, issuer disablement, and emergency trust revocation must have an explicit tested policy before live use; do not claim a universal immediate revocation window.
- Keep human and machine identities distinguishable. Machine access uses separately scoped client credentials and maps to a service actor. A machine token cannot inherit an end-user's grant.
- Keep issuer discovery/JWKS configuration and provider-specific claim normalization inside the authentication adapter/configuration boundary.
- The local test realm uses synthetic human and service identities. Human token validation uses signed local JWT/JWKS fixtures or a standards-compatible local flow; actual service-token verification uses Keycloak client credentials. Never enable Resource Owner Password Credentials for test convenience.
- Never persist bearer tokens, refresh tokens, client secrets, private keys, or IdP passwords. No token is logged.

**Verification seam**

With local Keycloak 26.8.0 and synthetic clients, plus signed deterministic human-token/JWKS fixtures and a local JWKS stub, test valid human/service tokens; principal-kind separation; bad signature/key; invalid issuer; wrong audience; expired/not-yet-valid token; ID-token rejection; disallowed algorithm; missing/ambiguous identity claims; cached-key acceptance during temporary JWKS outage; unknown-key fail-closed behavior during outage; and grant revocation. Tests use local infrastructure only. Test that no protected operation proceeds when authentication is uncertain. Record exact resolved dependencies from the Boot 4.1.1 BOM in acceptance evidence.

**Definition of done**

Only configured signed access tokens for the AyuVahak audience establish human/service actor references. Invalid or unverifiable claims fail closed; no token or secret is persisted or logged; provider-specific mechanics remain behind the authentication boundary.

### 4.4 Authorization and functional authorities

**Required behavior**

- Authorization is evaluated after authentication and receives actor kind/reference, organization, optional facility, action, target/context, and purpose when applicable.
- AyuVahak owns scoped grants and grant/revocation chronology. Begin with RBAC over functional authorities. Do not authorize solely from arbitrary provider role claims.
- A configured deployment maps local roles/teams/processes to functions such as Clinical Suitability Authority, Operational Readiness Authority, and Acceptance Authority. A role title is not hard-coded in the generic model.
- A missing, inactive, out-of-scope, or conflicting grant denies. Denial does not reveal sensitive object existence or policy internals.
- A supplied facility must be validated as belonging to the explicit organization using a trusted deployment reference; missing or conflicting reference denies. Phase 2 accepts a reference-only deployment scope catalog/adapter or local synthetic fixture; it creates no organization/facility registry and does not persist a competing directory.
- The initial grant is created only by the selected one-shot local operator command in [ADR 0014](../adr/0014-one-shot-local-grant-bootstrap.md). It provisions the platform-scoped `AccessGrantAdministration` function, records deployment-attested operator and grantee references plus a bootstrap audit record, and stores a permanent bootstrap-completed marker atomically. It refuses after the marker exists even if the grant is later revoked; it starts no HTTP listener and has no default AyuVahak administrator. A successful bootstrap does not grant healthcare or clinical authority.
- The bootstrap invocation is a local non-web application mode that requires explicit operator reference, grantee kind, issuer, subject/client reference, and a typed confirmation naming the grantee. It succeeds only when no bootstrap marker and no grant exist, creates exactly one platform-scoped `AccessGrantAdministration` grant, and writes the audit record and permanent marker in the same PostgreSQL transaction. The marker is never cleared by application behavior. The operator reference is deployment-attested (for example, an operator-supplied staff reference plus local process/host context), not a claim that the operator authenticated through OIDC. The command exits after success and cannot be invoked through HTTP. Recovery after loss of all grant administrators is an explicit database-operator recovery procedure outside the application and must be audited through deployment controls; it is not a hidden bootstrap path.
- Subsequent grant creation, listing, and revocation are genuine Phase 2 platform operations: `POST /api/v1/platform/access-grants`, `GET /api/v1/platform/access-grants`, and `DELETE /api/v1/platform/access-grants/{grantId}`. Every request requires `X-AyuVahak-Execution-Scope: PLATFORM`; the authenticated principal must hold `AccessGrantAdministration`. The API accepts only the three configured Phase 2 platform functional authorities, uses opaque internal UUIDv4 grant IDs, and returns no issuer metadata or policy internals beyond the caller's authorized grant scope. A grant administrator may list, create, or revoke those configured authorities for other principals within `PLATFORM` scope; it cannot grant to itself, alter its own grant, or rely on IdP administrator roles. Mutation, required audit, outbox event, and command-idempotency receipt commit atomically. The list operation durably records its sensitive-read audit before returning results. Missing/invalid scope is a safe `400`; absent/invalid token is `401`; insufficient grant is `403`; an audited denial whose audit write fails remains denied and returns `503`; mutation-audit or outbox insertion failure rolls back and returns `503`; publication failure after commit leaves the grant committed and the outbox item recoverable; exact idempotent replay returns the original safe status and grant reference; key reuse with a different fingerprint returns `409` (`IDEMPOTENCY_KEY_REUSED`); a lock wait that exceeds the bounded request wait returns `409` (`IDEMPOTENCY_IN_PROGRESS`). All failures use RFC 9457 Problem Details and the Phase 1 correlation identifier.
- `GET /api/v1/platform/audit-records` is restricted to `AuditReview`, requires platform scope, records its own sensitive-read audit before returning a bounded page, and returns only the audit fields defined in this specification. There is no general-purpose audit export endpoint.
- The only functional authorities configured by the Phase 2 kernel are `AccessGrantAdministration`, `AuditReview`, and `EventDeliveryOperations`, all platform-scoped. Grant administrators may delegate only these configured authorities, only within their own grant ceiling, and never to themselves; later business contexts own and register their own functional authorities. Phase 2 creates no organization/facility authority catalog.
- Policy evaluation remains behind one narrow application boundary. Preserve future ABAC predicates for case relationship, purpose, time, location, and sensitivity; do not add a general policy engine in this phase.
- Preserve a future governed break-glass extension without bypassing authentication, organization/facility scope, attribution, or audit. Do not implement the emergency workflow in Phase 2.
- Grant creation and revocation are attributable and auditable. The initial implementation reads grant state from the PostgreSQL primary for each decision and does not cache grants, so a committed revocation affects the next authorization decision; database uncertainty fails closed. No unsupported latency number is claimed. The deployment scope catalog is configured as read-only references; Phase 2 does not offer an endpoint to create organizations or facilities.

**Verification seam**

Test permitted and denied synthetic cases across organization, optional facility and organization relationship, action/functional authority, inactive/revoked grant, unknown authority, human vs service actor, and target ownership. Test fail-closed behavior when grant state/reference cannot be safely read. Invoke the one-shot local CLI twice and prove the second invocation refuses after success; prove the command starts no HTTP listener, records an operator reference and bootstrap audit evidence, and has no default grant. Exercise the grant HTTP operations with an explicit platform scope, demonstrate grant-ceiling enforcement and self-grant rejection, exact idempotent replay, key misuse conflict, and audit/outbox atomicity.

**Phase 2 platform API contract**

All new platform routes require a verified bearer access token and `X-AyuVahak-Execution-Scope: PLATFORM`. `POST /api/v1/platform/access-grants` accepts one issuer-scoped human or service principal reference, one configured platform authority, `PLATFORM` scope, and a bounded reason code; it returns `201` with the opaque grant ID. The principal reference is `{kind, issuer, subject}` for a human or `{kind, issuer, clientId}` for a service. `GET /api/v1/platform/access-grants` returns a bounded page of grants for the three configured Phase 2 authorities. `DELETE /api/v1/platform/access-grants/{grantId}` requires a bounded revocation reason code, revokes one active grant, and returns `204`; already-revoked state is not re-mutated. Grant create/revoke require `Idempotency-Key`. `GET /api/v1/platform/audit-records` requires `AuditReview`; `GET /api/v1/platform/event-deliveries` and `POST /api/v1/platform/event-deliveries/{eventId}/replay` require `EventDeliveryOperations`. List operations use bounded pagination (default 50, maximum 100), return only fields within the relevant authority, and are audited before disclosure. Every mutation is audited atomically. The existing non-sensitive system-information route and Phase 1 health/management exposure retain their Phase 1 behavior; no login, organization-directory, healthcare, or patient API is added.

**Definition of done**

The policy boundary produces deterministic allow/deny outcomes from explicit scope and locally owned grants; missing scope/grant denies. Functional authority mapping is deployment-configurable, multi-organization actors are supported without a global tenant model, and privileged grant provisioning is attributable and operator-controlled.

### 4.5 Audit

**Required behavior**

- Record attributable actor, organization and optional facility context, action, target reference, event/recorded timestamp, outcome, and correlation ID for safety/access-relevant actions. Record no clinical payload by default.
- For a protected state mutation, write required audit evidence in the same transaction as the state change. Failure to persist audit evidence aborts the mutation.
- For a successful sensitive read, durably record the access before disclosing the protected result. For a denial/authentication or other non-mutating security outcome, use the separate durable audit path.
- If the non-mutating audit path is unavailable, a denial remains denied; a sensitive read is not released. Surface the audit-path failure safely, alert through operational telemetry, and never claim that the audit was persisted.
- Audit access and privileged grant/configuration changes are themselves attributable. Correction is append-oriented and preserves prior evidence; retention/export are deployment-governed.
- Do not conflate audit with operational logs, provenance, event history, or outbox telemetry.

**Required HTTP outcome contract**

| Condition | Durable action | HTTP outcome |
| --- | --- | --- |
| Invalid/missing access token | Authentication remains denied; a minimized security audit is durably attempted. | `401` `AUTHENTICATION_REQUIRED`; if durable audit persistence fails, `503` `AUDIT_UNAVAILABLE`. If trusted-key retrieval is unavailable for an unknown key, return `503` `AUTHENTICATION_UNAVAILABLE` (or generic `AUDIT_UNAVAILABLE` when the audit write also fails). |
| Authenticated caller lacks required grant | Denial audit is attempted before response. | `403` `AUTHORIZATION_DENIED`; if denial audit persistence fails, `503` `AUDIT_UNAVAILABLE` and the action remains denied. |
| Protected grant mutation succeeds | Grant, audit, outbox event, and idempotency receipt commit together. | `201` for create, `204` for revoke; retry with same key/fingerprint repeats the original outcome. |
| Protected mutation's required audit or outbox write fails | Entire state transaction rolls back. | `503` `AUDIT_UNAVAILABLE` or `EVENT_PUBLICATION_UNAVAILABLE`; never report success. |
| Audited grant listing succeeds | Read-audit record is durably committed before disclosure. | `200` with authorized grants. |
| Grant listing read-audit write fails | No grant rows are disclosed. | `503` `AUDIT_UNAVAILABLE`. |
| Same idempotency key, different request fingerprint | No mutation occurs. | `409` `IDEMPOTENCY_KEY_REUSED`. |
| Same idempotency key is held by an in-flight mutation beyond the configured bounded wait | No second mutation occurs. | `409` `IDEMPOTENCY_IN_PROGRESS`. |

All problem responses use RFC 9457 with stable platform error code and correlation ID; they do not expose SQL, exception text, grant internals, token claims, or protected rows.

**Verification seam**

Against local PostgreSQL, verify (a) grant state, audit, outbox, and idempotency receipt commit together, (b) forced audit/outbox failure rolls back the grant mutation, (c) an audited grant listing is not disclosed if the separate durable read path fails, (d) denial remains denied during audit outage, and (e) audit records are minimized, correlated, attributed, and query access is restricted. The restricted audit-read operation is a genuine platform capability; no test-only business aggregate is required.

**Definition of done**

Mutation and non-mutating audit semantics pass their distinct failure seams; audit evidence is append-oriented, access-controlled, attributable, and payload-minimized. The implementation does not claim capture when persistence failed and does not invent retention duration.

### 4.6 Provenance

**Required behavior**

- The owner of a fact records the provenance needed to interpret it: source/issuer and external reference where applicable, actor/system, observed/event time, received/recorded time, and transformation/verification/confidence or validity information when relevant.
- When meaningful to that fact, provenance includes source type/method and valid-until or freshness metadata; the owning domain defines meaning and policy.
- Provenance stays with the fact and follows it through a projection only when the projection remains attributable. The platform does not store all external payloads.
- Manual entry is a named source with attributable actor and recorded time; it is not silently elevated to registry/system-verified status.
- Raw partner/FHIR payload remains transient unless a later data-ownership decision explicitly authorizes bounded retention.

**Verification seam**

Use test-only source observations to verify source, observation/receipt time, and verification state survive mapping and stale/unknown handling. Assert raw synthetic payload is absent from normal logs and persistent kernel records.

**Definition of done**

Test-owned facts retain sufficient source and temporal evidence to explain their origin and freshness. Provenance remains adjacent to its fact owner; no global raw-source store is added.

### 4.7 Internal domain events

**Required behavior**

- Events are internal notifications of committed facts, not the only source of truth. Business context that emits an event owns its meaning, event name, and compatibility.
- The Phase 2 event framework and outbox target internal AyuVahak consumers only. External exchange requires a context-owned adapter and its explicit mapping, authorization, retry, and reconciliation semantics; the outbox is not a universal integration gateway.
- Follow `<context>.<fact>.v<major>` naming. The shared envelope carries stable event identity, producer/context, occurrence and recorded times, correlation/causation, actor/org execution attribution, and aggregate reference/monotonic sequence when ordering is required.
- The durable envelope is an explicit UTF-8 JSON object stored as PostgreSQL `jsonb`, not Java-native serialization. Its required fields are `eventId` (UUIDv4), `eventType`, `producerContext`, `occurredAt`, `recordedAt`, `correlationId`, `actorKind`, `actorRef`, `executionScope`, and producer-owned `payload`. `causationId` is present when the event was caused by an identified command/event. `aggregateRef` and monotonic `aggregateSequence` are both present when ordering is required. Timestamps are UTC instants. The event type carries its major contract version; breaking meaning/required-field changes require a new major event type. Unknown optional envelope fields may be ignored; consumers must reject unsupported major versions safely.
- No global ordering is promised. For events from one aggregate that require causal order, sequence is monotonic and later unpublished events cannot be considered successfully published first.
- Consumers tolerate at-least-once delivery. Unknown additive metadata is ignored safely; breaking event meaning or required shape uses a new major version and an explicit migration plan.
- Event payloads are minimal and contain no patient narrative by default. Do not publish data merely because it is available in the producer.
- No event-sourcing requirement, global event archive, or mandatory broker is introduced.

**Verification seam**

Grant creation and revocation are the Phase 2 producer-owned platform facts. They emit `identity-access.grant-created.v1` and `identity-access.grant-revoked.v1` with a minimal grant reference and scope metadata in the explicit event envelope. Test stable event naming/version validation, required attribution/correlation metadata, additive compatibility, breaking-major detection/documentation, ordering metadata, and minimization. Any consumer used to exercise duplicate handling is a test-scoped fixture; do not create healthcare business events or a fake production domain.

**Definition of done**

The internal event contract identifies producer-owned fact/version and required correlation/causation metadata, while preserving at-least-once handling and optional per-aggregate sequence. No business-domain event or external event gateway is implemented in the Phase 2 kernel fixture.

### 4.8 Transactional outbox and dispatcher

**Required behavior**

- Grant state, mutation audit, command receipt, and internal publication intent are durable in the same PostgreSQL transaction. A rollback leaves neither a committed grant result nor a publishable outbox record.
- The dispatcher uses PostgreSQL row locks with `SKIP LOCKED`; it claims due work in short transactions and assigns a 60-second lease. Publication occurs outside the database transaction. A worker renews its lease every 20 seconds while handling; after worker exit, another worker may reclaim expired work. A send/ack crash can cause duplicate delivery. A monotonically increasing lease generation prevents an expired worker from acknowledging or overwriting the newer worker's state; duplicate side effects remain possible and consumers must be idempotent.
- Delivery is at least once. A failed delivery is retried after 1, 2, 4, 8… seconds, capped at 5 minutes, for at most 10 attempts; the next failure moves the record to `STALLED`, preserving it for authorized local operator inspection/replay. The retry schedule and attempt cap are configuration values with these safe defaults. There is no automatic record deletion in Phase 2; cleanup requires a later retention decision and may not erase audit or grant history. Operational metrics expose pending count/age, attempt count, and safe failure category without patient payloads.
- Preserve per-aggregate order where required with monotonic sequence: a later event for the same aggregate is ineligible while any earlier event is pending, leased, retrying, or stalled. Independent aggregates can progress. A stalled event therefore blocks only its own aggregate until recovered or explicitly reconciled.
- Delivery acknowledgement means only that the selected internal consumer boundary accepted the message. It does not mean a receiving facility accepted a Transfer Request or reserved capacity.
- One dispatcher worker is enabled by default. Multiple workers are permitted only when PostgreSQL concurrency tests prove independent aggregates progress concurrently and required same-aggregate order is preserved. No broker is required. The Phase 2 dispatcher invokes registered in-process handlers; an event with no registered handler is explicitly treated as accepted by the local event boundary and is not replayed to consumers registered in a later release. External adapters are not registered with this dispatcher.
- `GET /api/v1/platform/event-deliveries` and `POST /api/v1/platform/event-deliveries/{eventId}/replay` are restricted to the platform `EventDeliveryOperations` authority, require `PLATFORM` execution context, and are audited. Replay is idempotent, resets only a `STALLED` delivery generation to pending, and is itself attributable; it does not edit event payloads, reset grant state, or promise delivery success. No default operator grant includes this authority.
- The delivery list returns bounded, paginated operational metadata only (event reference/type, producer, aggregate reference/sequence, delivery state, attempt count, and timestamps); it never returns the payload. Replay requires an `Idempotency-Key` and creates an auditable new delivery generation for the immutable event. Repeating the same key replays the same accepted replay outcome; a different key cannot reset a delivery that is already pending, leased, or delivered.

**Verification seam**

Use PostgreSQL/PostGIS container and a local deterministic consumer stub. Verify grant+audit+outbox commit/rollback; crash after commit before publish; crash after internal consumer effect before local acknowledgment; restart and retry; duplicate delivery; worker lease renewal, expiration, fencing, and reclaim; concurrent worker claims; one aggregate blocked while another progresses; per-aggregate ordering; retry backoff and transition to `STALLED` after 10 failures; authorized replay; safe backlog/age/failure metrics; no sensitive principal claims or patient data in event or diagnostic logs.

**Definition of done**

Publication intent is atomic with its producer fact, survives restart, is retried at least once, preserves required per-aggregate order, and makes terminal/stalled work visible. Internal delivery status never claims a business outcome.

### 4.9 Idempotency and duplicate protection

Command idempotency and event-consumer duplicate protection are one Phase 2 work module with two distinct ownership boundaries. Command owners protect callers retrying a command; consumer owners protect their own effects against at-least-once event delivery. Neither replaces the other, and no global receipt store is created.

#### Command idempotency

**Required behavior**

- A command owner opts in at the operation boundary and scopes an idempotency key to actor/client, organization or platform scope, operation, and target scope. Phase 2 grant create/revoke receipts are owned by Identity & Access; outbox replay receipts are owned by the outbox capability. No cross-context receipt table or API is introduced.
- Store a stable fingerprint of normalized command input and a safe reference/result needed to replay the committed outcome. Do not store raw clinical payload or secret-bearing bodies in a generic receipt.
- Same scope/key/fingerprint returns the same committed outcome/reference; same key with a different fingerprint returns a conflict; concurrent retries yield one effect and a repeatable outcome.
- Grant create/revoke commands require `Idempotency-Key` (1–128 visible ASCII characters). The key is stored as a one-way digest and scoped by issuer/principal, platform scope, operation, and route. The request fingerprint is a stable hash over validated, normalized command fields, excluding correlation metadata. A completed receipt contains only the fingerprint, response status, grant reference, and recorded time; it does not store request or response bodies. The Identity & Access owner retains these low-volume administrative receipts without automatic expiry in Phase 2. A later retention decision may define safe deletion only after the operation retry horizon is documented.
- The idempotency receipt, grant mutation, mutation audit, and outbox intent share one transaction. A unique constraint arbitrates concurrent submissions. An exact retry waits for the first transaction within a five-second database lock wait, then returns its committed safe outcome; if the first transaction rolls back, one retry may proceed. A wait timeout returns `409 IDEMPOTENCY_IN_PROGRESS` and does not create a second effect. A same-key/different-fingerprint request returns `409 IDEMPOTENCY_KEY_REUSED`. Authentication, authorization, and validation failures do not reserve a key. There is no separately committed “in progress” receipt that could become orphaned after a crash.
- Command receipts belong to the owning context, not a global platform store. No production business context/schema is scaffolded to test this in Phase 2.

**Verification seam**

Use the real grant create/revoke operations and local PostgreSQL. Exercise first request, exact retry, mismatched payload, concurrent duplicate, lost-response retry, audit/outbox/transaction rollback, and recovery of an interrupted in-progress attempt. Assert the effect count is one and stored receipts contain no request body, token, or principal claims beyond the issuer-scoped principal reference required for lookup.

**Definition of done**

For an opted-in test operation, same scope/key/fingerprint converges on one effect and safe replay; conflicting reuse is explicit; concurrent/recovered retries cannot create a second effect; receipt retention is documented for that operation without a global TTL.

#### Event-consumer deduplication

**Required behavior**

- Each consumer recognizes a stable event identity within its own scope and records receipt with its effect, atomically when both use the same transaction.
- A successfully applied duplicate is acknowledged without reapplying the effect. A failed consumer effect must remain retryable and cannot be marked complete by a premature receipt.
- Consumers preserve producer sequence/causation data where their contract requires ordering or chronology. A duplicate receipt does not mean every other consumer has processed the event.
- Receipts belong to the consumer-owning context; do not combine them with caller command idempotency keys.

**Verification seam**

Use a test-only local consumer and event publisher. Store its effect and receipt in a test-scoped fixture, not a production business package/schema. Test repeated same event, distinct consumers receiving the same event, failure before effect commit, crash after effect commit/ack loss, replay after restart, and sequence gap/block behavior where order is required.

**Definition of done**

Each test consumer atomically records its completed effect and event identity where possible; successful duplicates do not repeat effects, failed effects remain retryable, and receipts are consumer-owned rather than shared globally.

### 4.10 Kernel recovery and integration

**Required behavior**

- Recovery state is visible and actionable without logging protected content. Database, authentication-provider, audit, and publication health are distinct; a dependency outage does not fabricate a positive authorization or delivery outcome.
- Health/readiness semantics from Phase 1 remain intact. Authentication/key trust failures deny protected operations without necessarily making the entire process liveness fail.
- All tests are deterministic and local. Use local PostgreSQL/PostGIS, local Keycloak where protocol integration is under test, local publisher stubs, and synthetic fixtures. Container image versions are pinned at implementation time and verified against current primary documentation.
- Integration failure tests cover transaction boundaries, retries, concurrency, process interruption/restart, idempotency, per-aggregate order, key rotation/unavailability, and audit-path failures.
- Correlation is preserved across a synthetic command, emitted event, and internal handler. Safe metrics may report authentication failure category, authorization denial, outbox backlog/oldest age, publication attempt/failure category, idempotency conflicts, and consumer failures; do not label metrics with patient identifiers or emit raw tokens, unnecessary usernames, or payloads.

**Verification seam**

Run the complete local Phase 2 suite against disposable infrastructure and inject faults at each durable boundary. A new contributor can reproduce the tests without public services, external accounts, real credentials, patient data, or a separately provisioned database.

**Definition of done**

The synthetic integrated suite demonstrates the required trust, authorization, audit, outbox, idempotency, deduplication, restart, and correlation behaviors against local infrastructure. No business module/schema or prohibited external dependency is present.

## 5. Cross-cutting invariants

1. **Explicit trust chain:** authenticated actor → validated organization/facility execution context → scoped authorization decision → attributable audit/action.
2. **Fail closed:** missing identity, scope, grant, trusted key, or required audit evidence never becomes allow/success.
3. **Atomic mutation accountability:** required mutation audit and protected state commit together.
4. **Distinct non-mutating audit durability:** required sensitive-read/security audit is durable before protected content is disclosed or the action is reported complete.
5. **No false exactly-once claim:** outbox is at-least-once; commands and consumers each provide scoped idempotency.
6. **Per-aggregate order only:** monotonic causal sequence is enforced where needed; no global event order.
7. **Ownership stays local:** no shared persistence API for business facts, command results, or consumer receipts.
8. **Data minimization:** token, secret, raw patient payload, and clinical narrative never enter ordinary logs or generic event/receipt stores.
9. **Optional interoperability:** kernel works without FHIR, a production Keycloak dependency, or a particular national registry.
10. **No implicit ambient context:** request and background work carry explicit actor and organization scope.

## 6. Implementation order and gates

The ten implementation modules follow these dependencies; each remains an in-process ownership boundary, not a separately deployed service:

| Module | Capability | Dependency / implementation gate |
| --- | --- | --- |
| 2.1 | Core primitives | First; no infrastructure dependency beyond the Java baseline. |
| 2.2 | Organization / execution context | Depends on core; implement explicit context creation/validation and transport/background propagation. |
| 2.3 | Authentication | Depends on core; implementation preflight must confirm the selected Boot-managed Spring Security and local Keycloak versions before code is added. |
| 2.4 | Authorization | Depends on execution context and authenticated principal; includes the one-shot bootstrap and genuine grant operations. |
| 2.5 | Audit | Depends on principal/context and participates in the grant command transaction; implement mutation and non-mutating paths distinctly. |
| 2.6 | Provenance | Depends on core; provide reusable fact-adjacent values and test-only owner fixture, with no central persistence. |
| 2.7 | Domain-event framework | Defines versioned envelope and compatibility before an event is persisted. |
| 2.8 | Transactional outbox / dispatcher | Depends on event contract and Phase 1 PostgreSQL transaction foundation; uses grant changes as the real platform producer. |
| 2.9 | Idempotency / duplicate protection | Grant/revoke and replay commands use their owner-local receipts; consumer dedup is exercised by a test-scoped owner. |
| 2.10 | Kernel failure/recovery verification | Integrates all prior seams, fault injection, local Keycloak/PostGIS, architecture rules, and regression gates. |

Each module must pass its behavior-level TDD seam before dependent work is marked complete. Independent modules may run concurrently only after their dependencies are complete and their file/schema ownership does not overlap. Tickets are generated locally from this table and the dependency map; no healthcare context begins after Phase 2.

## 7. Phase 2 acceptance evidence expected later

The future implementation acceptance record must present actual commands and results, not this specification as proof. It must establish:

- authentic human and service access token validation; ID tokens, bad signatures, wrong issuer/audience, expired/not-yet-valid token, and unsafe key state rejected;
- explicit organization/facility scope checked independently from authentication and role claims;
- local functional authority grants, revocation/deny, and audit attribution;
- mutation audit atomicity and rollback; protected-read/denial behavior when non-mutating audit persistence is unavailable;
- provenance retained with a test-owned fact and no centralized raw-payload archive;
- event naming/version/attribution behavior and no global-order claim;
- outbox commit atomicity, crash/restart recovery, retry, duplicate delivery, and per-aggregate ordering while independent aggregates progress;
- handler failure and retry exhaustion remain visible/recoverable, and correlation survives a synthetic command-to-event-to-handler path;
- command retry/fingerprint conflict/concurrency yields one effect;
- consumer duplicate/failure/replay behavior is distinct from command idempotency;
- test/recovery behavior uses local disposable PostgreSQL/PostGIS, local IdP, deterministic publisher/stubs, synthetic data, and no public services;
- architecture tests enforce module ownership and no prohibited cross-context persistence access;
- no business context/schema, FHIR/DHIS2 connector, external mutation, or Phase 3 behavior has been introduced.

No numeric availability, latency, audit retention, outbox age, or idempotency TTL is set here. Those require workload, jurisdiction, and operational evidence.

## 8. Implementation constraints and deployment decisions

The implementation must follow the explicit behavior above. It may choose concrete Java types and exact SQL under the named ownership boundaries, but it may not change token validation, grant scope, HTTP outcomes, audit atomicity, event serialization, lease/retry defaults, idempotency semantics, or local-only test requirements without a reviewed ADR amendment. Recheck stable Keycloak image provenance and the Boot-managed Spring Security dependency immediately before resolving implementation dependencies. Production jurisdictional retention/legal basis and token-revocation windows remain deployment gates; they do not block local Phase 2 implementation.
