# Phase 9 specification — Secure External Health-System Exchange

**Status:** specification frozen. This file is the canonical Phase 9 behavior contract; implementation and acceptance status are recorded in `docs/reviews/phase-9-acceptance.md`.

**Volume:** III.

**Depends on:** frozen Phase 2 trust/audit/event/idempotency contracts and Phase 8 owner-port design. Phase 9 does not centralize domain mappings.
**Architecture pointers:** [Volume III architecture](../architecture/volume-3-interoperability-security-transport.md), [context contracts](../architecture/volume-3-context-contracts.md), [state machines](../architecture/volume-3-state-machines.md), [threat model](../architecture/volume-3-threat-model.md), [security and trust](../architecture/security-and-trust.md), [OAuth/FHIR research](../research/volume-3-standards-research.md).

## Outcome and boundaries

Establish the technical trust boundary used by configured owner-context adapters for outbound requests and named inbound exchanges. It is a platform capability, not a business bounded context, generic HIE, ESB, universal API gateway, workflow engine, or integration product. Directory, Capacity, Referral, and Transport each own their own port, semantic mapping, external identifiers, operation purpose, and business reconciliation. The platform capability enforces transport and partner trust policy only.

Phase 9 does not require any external partner to be onboarded. It does not change Volume II state when a message is merely queued, sent, accepted by HTTP, or acknowledged by an external server.

## Partner trust configuration

An authorized operator-managed trust profile has a stable partner key; administrative owner/organization association; active/suspended/revoked state and configuration version; allowed owner context, operation, direction, protocol, FHIR/profile version where relevant; approved origin and path constraints; authentication mechanism; secret/key reference; TLS policy; timeout and payload/resource/page bounds; and retry class. The control plane can centrally enforce this technical trust envelope. Owner-specific identifier mapping, semantic profile, and business retry meaning stay with owner adapters. This is not a global connector registry.

Configuration is never accepted from an ordinary business request. Changing trust or endpoint facts is attributed, authorized, audited, and versioned. Secret bytes/private keys are never stored in ordinary domain tables or source control. A secret-provider boundary permits deployment binding from protected files, environment injection, Vault, or a cloud secret manager without requiring any specific product. Local development uses disposable test-only credentials and certificates.

## Endpoint safety and SSRF defense

- API callers cannot provide a destination URL, token URL, callback URL, FHIR `Reference` URL, or redirect target to be fetched.
- A connector resolves only an administrator-approved endpoint from the active partner profile. Use HTTPS and normal certificate/hostname validation by default. Production cannot disable certificate validation or use “trust all” TLS.
- Validate scheme, host, port, normalized path, user-info absence, and canonical hostname before connection. Resolve DNS for each connection attempt and inspect the complete answer set. Reject loopback, link-local, multicast, unspecified, and cloud metadata destinations; reject a mixed answer set if any address violates policy. Bind the socket to one of the exact vetted addresses for that attempt, while retaining the configured hostname for TLS SNI and certificate hostname verification; verify the connected peer address. Do not validate one DNS result and let the HTTP client resolve the hostname again. Apply the same rule to redirects and do not reuse a connection across partner/configuration boundaries. Private/RFC1918 destinations are denied unless the deployment explicitly allowlists the exact partner network and egress policy supports it; private addressing is not blanket-rejected where healthcare networks legitimately require it. If the selected client cannot enforce vetted-address connection binding, fail closed or use an enforced egress proxy/firewall whose destination policy is verified at connection time.
- Disable redirects by default. If an approved profile requires redirects, accept only same-origin configured redirects with a strict small hop bound and re-run all endpoint checks for each hop; never forward credentials across origin.
- Apply outbound egress controls at deployment/network boundary as defense in depth. Connection/read/overall timeouts, response size, decompressed size, page count, resource count, nesting depth, and operation time are bounded. No uncontrolled FHIR crawling or arbitrary reference resolution.
- Never fetch XML schemas/entities externally. If an adapter supports XML, disable DTD and external entity resolution, entity expansion, and external schema access; bound parser depth and size. JSON parsers use bounded nesting, collection, and string sizes and safe polymorphic configuration.
- Token and client-auth endpoints are also configured and validated; they are not dynamically followed from untrusted payloads without an independently approved trust profile.

## Authentication and trust separation

Partner authentication is separate from AyuVahak user/service identity and AyuVahak grants. Use only a mechanism explicitly agreed and configured for a partner:

- SMART Backend Services / OAuth client credentials with signed asymmetric client assertion when the FHIR authorization server supports the pre-registration and scope model.
- OAuth client credentials with the partner-approved client authentication method when SMART is not applicable. Asymmetric methods (private-key JWT or mutual TLS) are preferred when feasible; a secret-based method is accepted only through protected secret injection and explicit risk acceptance.
- Mutual TLS may be required by deployment agreement as peer authentication or sender constraint, but it is not interchangeable with OAuth authorization.
- Future interactive clients use authorization code with PKCE where appropriate; Phase 9 does not implement a client UX. Never use the resource-owner password grant.

Validate issuer, audience/resource, signature and trusted keys, expiry/not-before, algorithm allowlist, and configured client/subject binding. Bound token lifetime/cache to deployment policy; refresh/acquire through trusted metadata/configuration; handle rotation and clock skew explicitly. Do not log tokens, assertions, private keys, authorization headers, or credential responses. Never store bearer tokens in ordinary records. Revoke partner use immediately for new/queued work according to trust status, invalidate token/key cache, and require a new reviewed configuration version to reactivate.

An authenticated external peer identifies a partner principal. Each owner operation still checks local partner-operation allowlist, organization scope, owner policy, idempotency, and audit. SMART scopes and OAuth scopes never grant AyuVahak domain permission.

## Outbound operation lifecycle

The owner commits its own intent and stable operation ID. If durable delivery is needed, owner state and its existing internal outbox event are atomic. A worker rechecks partner trust/config version, obtains credentials, performs one bounded attempt, classifies the result, and returns a delivery/reconciliation outcome to the owner. A technical `2xx` means only that the partner endpoint acknowledged the exchange under the configured contract.

Store only operation metadata needed for delivery/recovery: owner reference, operation ID, partner key/config version, direction/profile, state, attempt count/times, next eligible attempt, bounded sanitized error class, correlation/causation. Sensitive full payloads are not retained by default. If a partner requires retryable payload storage, the owner must justify the exact fields, encryption/access, expiry, purge and audit; payload cannot become a generic exchange archive.

Retry rules:

- Retry a plausible transient network failure, timeout before a known response, 429 with bounded `Retry-After`, or selected 5xx only when operation semantics are idempotent or partner provides an idempotency/status-recovery contract.
- Do not blindly retry if a side effect may have committed and partner offers neither idempotency nor authoritative status query. Mark `OUTCOME_UNKNOWN` and require safe reconciliation.
- Do not indefinitely retry credentials/authentication failure, authorization denial, revoked partner, malformed or incompatible payload, local policy rejection, unsupported profile/version, terminology/semantic mapping error, or other deterministic 4xx.
- Use bounded exponential backoff with jitter and maximum attempts/time; terminalize to an operator-visible state. A new corrected business intent gets a new operation identity.

## Inbound exchange and replay

Inbound exchange exists only for a named owner contract. There is no generic webhook receiver. Bind the route/protocol/profile to a configured partner; authenticate the peer (mTLS and/or signed token per partner), validate issuer/audience/time/signature/key version, enforce content type and size limits, and map authenticated partner identity to allowed operations/directions. The owner receives a normalized command with external message ID/version, source time, received time, config/profile version, correlation, and operation identity.

Use partner message ID plus partner namespace and operation type for replay/duplicate recognition where supplied. Persist consumer deduplication under the owning boundary, not a global business receipt store. Same ID/same fingerprint returns the prior outcome; same ID/different fingerprint is a security/conflict condition. Missing replay identity is handled by operation-specific policy; it must not be silently treated as unique forever. Timestamps alone are not replay protection. Signed webhook designs must include nonce/timestamp freshness and signature key rotation if a future partner contract chooses webhooks.

External message replay, HTTP retry, owner command idempotency, event consumer dedup, and partner idempotency are separate concepts with separate owners and keys. Do not promise exactly-once delivery or processing. Deterministic replay applies the same owner validation and returns the original recorded result for a true duplicate.

## Failure taxonomy and partial success

Classify at least: DNS/connect unavailable; TLS/hostname/certificate failure; timeout; rate limited; remote 5xx; remote authentication/authorization failure; revoked partner; invalid credentials; unsupported protocol/version/profile; malformed or oversized payload; unknown/untrusted reference; semantic mapping failure; local policy denial; duplicate/replay; stale/conflicting source; response lost/outcome unknown.

Only transient, idempotent-safe categories retry. A failure fetching an optional IPS summary does not undo Transfer acceptance. A failed directory refresh preserves last projection only with its recorded freshness; matching sees stale/unknown evidence under existing policy. An outbound delivery failure does not roll back an already committed owner fact. Hard prerequisites are declared by the owning specification, not inferred from infrastructure availability.

## Observability and audit

Logs and traces may carry partner key (non-secret), operation category, normalized outcome, correlation, config version, attempt number, duration, and bounded error class. Metrics may label only bounded partner/operation/status categories. No PatientReference, referral/transfer ID, external patient ID, token, authorization header, raw payload, query parameter, or precise patient movement coordinate may be a metric label or ordinary log field.

Audit for trust configuration changes and sensitive exchange records actor/service, organization context, partner, operation, owner reference where permitted, profile/config version, timestamp, correlation, and outcome. It excludes credentials and payloads. Sensitive patient-context reads follow the Referral fail-closed audit contract.

## Local-only verification and acceptance

Every Phase 9 test uses local mocks/stubs, synthetic payloads, local Keycloak or a deterministic local OAuth stub, local generated TLS certificates, and disposable PostgreSQL where persistence/concurrency semantics matter. No public FHIR/SMART sandbox, public HIE, external IdP, public EMS provider, external DNS test, or public routing service is allowed or required.

- Validate trust-profile activation/suspension/revocation and versioned audit.
- Reject caller-supplied URLs; test loopback/private policy, redirect-to-private/origin change, DNS rebinding simulation, metadata/link-local, unexpected schemes/ports, credential forwarding, malformed host normalization, and approved private network exception.
- Simulate DNS returning an allowed address during validation and a blocked address during a second resolution; verify the actual socket connects only to the pinned vetted address, preserves hostname TLS verification, and never reaches the blocked address. Cover pooled-connection and redirect boundaries.
- Test trusted TLS success, wrong hostname/CA, expired/rotated certificate, OAuth assertion signature/issuer/audience/expiry, expired token, key rotation, bounded token cache, and secret redaction. Do not create real credentials.
- Test response/payload/page/resource/depth/time bounds and FHIR absolute reference allow/deny behavior.
- Test duplicate/replay same fingerprint, same external ID with different fingerprint, out-of-order version, 401/403/429/5xx, timeout before send, response lost after remote side effect, retry cap, revocation during retry, restart recovery, and no blind duplicate external mutation.
- Verify local owner state remains valid on optional exchange failure and no external delivery status is misrepresented as business state.
- Verify logs, metrics, traces, audit, and errors contain no tokens or clinical payload.
- Verify architecture rules forbid direct external HTTP from domains, owner-adapter cross-persistence access, and central semantic mapping.

Acceptance requires local, deterministic results and explicit unknown/reconciliation states. It must never depend on an external system being reachable.
