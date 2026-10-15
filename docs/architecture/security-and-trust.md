# Security, Privacy, and Trust Assumptions

## Governance assumptions

The jurisdiction and operating model are not selected. Each deployment must name the accountable health authority, data controller(s), operator(s), participating organizations, lawful basis or other applicable authority for processing, consent rules where required, retention schedule, breach process, and cross-border/federation constraints before production use. This document establishes product-level safeguards; it is not legal advice or a jurisdiction-specific compliance claim.

Synthetic identifiers and data are used in examples. Real patient data and production credentials are never required for development planning.

## Identity and authorization

- Authentication is delegated to an external OIDC identity provider. Keycloak is the local reference provider for controlled development/testing; deployments may use a compatible provider. AyuVahak does not create or store home-grown passwords. Keycloak development mode is not a production deployment recipe.
- AyuVahak acts as an API resource server and validates signed access tokens locally against configured exact issuer, AyuVahak audience, trusted signing keys/signature, and token time bounds. An OIDC ID Token is not an API access token. Validation uncertainty, invalid issuer/audience/signature, or an unusable key fails closed; no per-request IdP introspection is assumed.
- Human and machine subjects use separately scoped identities, issuers, audiences, permissions, and revocation paths. Machine access uses a separately scoped service identity (including client-credentials flows where configured); a machine credential does not inherit a human user's authority.
- Matching owner reads use a dedicated deployment-configured machine identity, separately from the human who requests an evaluation. Its owner-held grants are limited to Referral reads at participating origin scopes, Directory reads within the deployment-approved candidate boundary, and Capacity evidence reads at explicitly granted Facility scopes. It has no write authority in those contexts. Missing grants fail closed; there is no automatic scope expansion or user-token forwarding. Matching's command record retains the initiating human actor, while each owner audits the service actor and target under a shared correlation identifier.
- Authentication proves a configured identity; it does not by itself grant access to an organization, optional facility, case, patient context, clinical action, or integration.
- AyuVahak owns scoped application grants. Initial authorization is RBAC over functional authorities and is evaluated using actor, explicit validated organization and optional facility, action, target/context, and purpose where relevant. The model must allow future attribute-based rules for case relationship, purpose, location, time, or sensitivity without making ABAC mandatory initially.
- Role grants are administered by the accountable deployment/health authority, attributable, reviewable, and revocable. Deployment mappings translate local roles/teams/processes to functional authorities; no universal job title or IdP role claim is the sole permission source. Provider registry credentials and application roles are distinct.
- SMART on FHIR is used when a FHIR launch or FHIR-resource authorization integration supports it; AyuVahak still applies its own workflow authorization.
- Deny when identity, execution context, grant, purpose, trusted signing key, or applicable policy cannot be established. Any break-glass path is deployment-defined, limited, justified, time-bounded, and audited.

## Privacy and information handling

- Collect and disclose the minimum necessary Patient Context for the active coordination action.
- Keep a clinical summary purpose-limited, source-attributed, and case-scoped. Do not turn it into a longitudinal chart or central patient store.
- Limit data visible to receiving coordinators, dispatch, transport crews, administrators, analysts, auditors, and operators according to their duties. Administrative or technical privilege does not imply clinical access.
- Keep patient details out of application diagnostics, metrics, notification text, and coordination events unless a documented purpose requires them.
- Normal application logs and metrics contain no patient narrative, clinical payload, access token, password, or private key. Restricted audit records are a distinct, purpose-limited record and do not copy the full clinical payload.
- Protect data in transit and at rest using deployment-approved mechanisms; manage encryption keys and secrets outside source control and do not include secrets in logs or examples.
- Use established, reviewed cryptographic libraries and deployment/platform mechanisms. Do not design or implement home-grown cryptographic algorithms or protocols.
- Apply retention and deletion/restriction by data category. Case closure does not automatically justify indefinite retention of copied clinical content.
- Analytics and public-health exports are aggregate/de-identified by default and need an approved purpose and disclosure review.

## Trust boundaries

- A source is authoritative only for its assigned information category. Trust in its identity and transport does not make its capacity observation current or its content clinically correct.
- Every imported or manually entered fact preserves source, actor/system, event time, received time, and verification status when available.
- Identity providers authenticate subjects; health/provider registries identify organizations or practitioners; neither automatically defines AyuVahak action permissions.
- HIE/interoperability engines route or transform messages. They do not own the persisted referral, acceptance, reservation, or transfer lifecycle in this architecture.
- Facility staff govern their own capability, capacity, readiness, and acceptance. AyuVahak may display and compare those assertions but cannot guarantee real-world performance.
- Route estimates are untrusted operational estimates; they are not dispatch instructions or clinical recommendations.
- Each integration validates the expected source, recipient, scope, format, and authorization. Unknown, conflicting, or stale input is surfaced rather than silently promoted to truth.

## Volume III external-partner trust

External FHIR, directory, clinical-summary, and transport providers are independently administered trust domains. A valid TLS connection, FHIR profile, OAuth token, SMART scope, HIE route, or provider status does not by itself make content true or authorize a business action.

- Phase 9 provides a shared technical trust envelope, not a universal connector/gateway. Owner adapters retain their ports, mappings, external identifier links, reconciliation, and business retry decisions.
- Partner endpoints and token endpoints come from administrator-controlled, versioned configuration. Ordinary requests and external FHIR `Endpoint`/absolute `Reference` values cannot choose network destinations. Validate scheme/host/port/path and resolved addresses, prevent DNS rebinding, disable cross-origin redirects, enforce bounded time/size/pages, and apply egress controls. Loopback/link-local/metadata are denied; private networks require explicit deployment allowlisting because some health-system endpoints are intentionally private.
- Use HTTPS with certificate/hostname validation. Production cannot disable verification. Local tests may use disposable certificates explicitly trusted by that test. XML, if a named partner requires it, disables DTD/external entities and schema retrieval; JSON and XML parsing are size/depth bounded.
- OAuth/SMART client credentials, signed client assertions, and mTLS are separate partner mechanisms selected by bilateral configuration. AyuVahak validates the partner identity and operation scope, then the owning context rechecks its own grants and state. Client credential/private key is never stored as ordinary business data, included in logs, or committed. Tokens are short-lived, bounded-cache only, rotation-aware, and not persisted in business records.
- Suspending/revoking a partner blocks new and queued exchanges, clears/invalidates cached credentials, and stops indefinite retries. A remote outcome that may have committed remains unknown pending safe reconciliation; revocation does not fabricate rejection or success.
- External operation identifiers and duplicate/replay receipts are namespaced by partner and operation. External message replay, HTTP retry, owner command idempotency, and internal event consumer dedup remain separate. No exactly-once claim.
- No exchange persists a full clinical payload by default. A named owner may retain only minimized encrypted retry content if its operation contract requires it and explicitly defines access, expiry, deletion, and audit. The shared trust capability does not become a payload archive.

The [Volume III threat model](volume-3-threat-model.md) gives threats, mitigations, residual risks, and local verification seams. All future tests use local mock endpoints, synthetic payloads, local certificates/tokens, and disposable infrastructure; public healthcare sandboxes are prohibited.

## Safe failure behavior

- Stale or unavailable capacity is unknown for current matching; it is never silently treated as available.
- A request that is queued because of network loss is pending, not delivered, accepted, or reserved.
- Duplicate messages are recognized as the same coordination action where possible; delayed messages preserve both event time and receipt time and cannot silently overwrite a newer state.
- If authentication or authorization is unavailable, privileged actions fail closed. No public/demo server is a test fallback.
- If the network is unavailable during a time-critical transfer, local teams use their authorized clinical/operational fallback such as phone or radio. AyuVahak records the out-of-band decision later with its actual time and source; it does not invent offline consensus.
- Recommendations are advisory and deterministic. No LLM or machine-learning model participates in the safety-critical care-routing path, including clinical suitability, eligibility/destination ranking, capacity/readiness, reservation, acceptance, or dispatch. Any future model use is limited to non-safety-critical analytics under separate governance.
- A human remains responsible for clinical suitability, destination choice, accepting capacity risk, rerouting, and responding to a facility becoming unavailable.

## Audit and accountability

Every safety- or access-relevant action is attributable to the human or machine actor, organization and optional facility context, action, target reference, timestamp, outcome, and correlation identifier; distinguish event time from recorded time where reports may be delayed. Audit data should establish chronology and responsibility without copying the entire clinical payload. For protected mutations, required audit evidence commits atomically with the mutation; failure rolls both back. Sensitive successful reads are not disclosed before their required audit record is durable. Denials remain denials if the separate non-mutating security-audit path is unavailable; report the operational failure without claiming a record was persisted. Restrict and monitor audit access; include privileged configuration changes and accesses to patient context. Retention, correction, export, and tamper-evidence requirements are deployment-specific and must be settled before production.

These mechanisms belong to Phase 2, not Phase 1. See the [Phase 2 platform-kernel architecture](phase-2-platform-kernel.md) and [specification](../specs/phase-2-platform-kernel-spec.md).

Volume III uses these frozen mechanisms for partner configuration audit, sensitive patient-context read audit, and owner event delivery. It does not change Phase 2 ownership or promise exactly-once external exchange. Transport authority and privacy constraints are defined in the [Volume III architecture](volume-3-interoperability-security-transport.md).

## Pre-production governance gates

Before any live deployment, accountable stakeholders must define jurisdictional privacy/security obligations, controller/operator roles, lawful basis/consent, access matrix and break-glass rules, source and freshness policies, encryption/secret management, audit/retention/deletion, incident response, federation agreements, notification channel suitability, and offline reconciliation. These questions are intentionally not guessed in this greenfield constitution.

