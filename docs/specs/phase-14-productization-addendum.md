# Phase 14 Productization Addendum

**Status:** Authorized by the Volume V master prompt after the sanitized Phase 13 checkpoint was verified on GitHub. This addendum supplements the frozen [Phase 14 Optimization and Intelligence specification](phase-14-optimization-intelligence-spec.md); it does not replace or rewrite that specification. It authorizes only the work in the active Volume V master prompt.

## 1. Problem and outcome

The frozen system is an API-first coordination backend without an end-user interface or downloadable local package. Phase 14 productization adds a usable role-aware web client, explicit local first-run selection, safe synthetic demonstration data, an empty deployment path, bounded optional operator-support intelligence, and a downloadable local distribution. The purpose is to make the existing coordination capabilities understandable and operable while preserving their current owners, authorization, and meaning.

This remains a healthcare care-coordination platform. It is not an EHR, EMR, HIE replacement, clinical decision system, patient portal, dispatch authority, or generic AI platform. Patients are beneficiaries, not direct users in this release.

## 2. Frozen authority and addendum boundary

- The backend remains the authority for authentication-token validation, grants, scopes, authorization, business transitions, audit, source facts, and uncertainty.
- The browser is a presentation client. Hiding a route or button is usability behavior, never authorization.
- Each existing bounded context continues to own its current facts and transitions. The UI calls owner APIs; it does not read persistence, join schemas, implement Matching, infer Capacity, or create alternate lifecycle rules.
- Productization must preserve Referral, Transfer, Transport, Acceptance, Reservation, Arrival, Handover, Capability, Capacity, Availability, and Readiness as distinct concepts.
- Existing Volume I–IV and Phase 13 accepted behavior remains frozen. Any required API seam is a narrow presentation/application contract in its owner; it does not move ownership.
- The original Phase 14 optimization and intelligence safety rules remain binding. This addendum adds product presentation and distribution requirements only.

## 3. Frontend and trust boundary

If no frontend exists, use React, strict TypeScript, and Vite with exact package versions in a lockfile. The build is reproducible, uses no runtime CDN dependencies, and is served from the AyuVahak application origin in the supported distribution. Development may use Vite's local dev server with an explicit API proxy. The browser communicates with authenticated HTTP APIs and the authorized notification stream; it never connects to PostgreSQL or private adapters.

Use the configured external OIDC provider and Authorization Code with PKCE for browser login. The browser is a public client and has no client secret. It sends only a validated access token to AyuVahak APIs; an ID token is never an API credential. Tokens remain in the browser client's short-lived in-memory/session mechanism and are not copied into local storage, application tables, URLs, telemetry, or logs. Issuer and callback locations are deployment configuration, not user-supplied runtime destinations. AyuVahak remains a resource server and application authorization authority; it does not add a password database or become an identity provider. A minimal authenticated session/presentation endpoint may report the subject, validated organization/facility scopes, presentation capabilities, local deployment mode, and safe feature flags. It must not expose secrets or imply the client is trusted to authorize an action.

Role templates are convenience presets translated to the repository's existing granular authorities and scopes. The UI must use actual server-reported capability/scope information. Direct API calls and deep links remain protected by server-side checks.

## 4. Personas and workspaces

The client provides navigation and scoped workspaces for:

- Deployment/System Administrator and Integration/Security Operator: safe system/integration status and authorized configuration operations.
- Organization Administrator: organization, facility, and scoped access administration.
- Referral/Care Coordinator: Referral, deterministic Matching, Transfer initiation/progression, and reconciliation.
- Receiving-Facility Coordinator: incoming receiving work and the separately authorized receiving assessments and Acceptance.
- Capacity Manager: observations, freshness, readiness and authorized reservation operations.
- Transport/EMS Coordinator: Transport request and coordination state; dispatch remains with the transport authority.
- Public-Health/Operational Analyst: aggregate Analytics completeness, revisions, and export operation state, without transactional patient-workflow authority.

Only workspaces backed by real authorized APIs are presented as operational. Missing server capability is shown as unavailable or not configured, not fabricated metrics or a fake workflow. Unknown, stale, partial, denied, unresolved, and unavailable states remain visible and understandable. Semantic HTML, keyboard operation, visible focus, accessible forms/errors, table semantics, and status text independent of color are required; WCAG 2.2 AA is the quality target, not a certification claim.

## 5. Local installation and first-run choice

The downloadable local profile uses a finite, explicit deployment state: `UNCONFIGURED`, `SYNTHETIC_DEMO`, or `EMPTY`. A newly started local package is `UNCONFIGURED`; it never silently seeds business records. Before ordinary local use, the local launcher asks the operator to explicitly select Synthetic Demo or Empty and writes that choice to local installation configuration before starting the services. The application fails closed if the local mode is absent or invalid. There is no unauthenticated browser mode-selection endpoint, and production profiles do not expose a mode-selection or reset endpoint. The web client validates its expected Host/Origin and uses the configured IdP; loopback binding is defense in depth, not an authentication or browser-origin trust mechanism.

**Synthetic Demo** loads a separately versioned, unmistakably synthetic manifest using owner-approved commands/services, records the manifest version, and is idempotent or rejects an already initialized database safely. It includes `SYN-*` organizations, facilities, capabilities, observations, coordination workflows, aggregate data, and mock export states only. Every authenticated demo screen prominently states `SYNTHETIC DEMONSTRATION ENVIRONMENT — NO REAL PATIENT DATA — NOT FOR CLINICAL USE`. A documented demo-only reset/reseed command targets only the installation's dedicated disposable local Demo database/volume. It verifies the local mode, expected Compose project/volume identity, and loopback database endpoint; otherwise it fails closed. It destroys the isolated Demo store as a whole rather than issuing generic cross-context deletes. Reset requires an explicit destructive confirmation and is absent from all production profiles and application APIs.

**Empty** creates no synthetic organization, facility, Capacity evidence, Referral, Matching, Transfer, Transport, Analytics fact, or export. The interface guides a deployment administrator through external identity, one-time first-grant bootstrap, organization/facility setup, access grants, integrations, and readiness checks. No fixed production password or permanent bootstrap bypass is introduced. The existing one-shot operator-controlled grant bootstrap remains the authority for initial access.

Both modes use local-only, loopback-bound PostgreSQL/PostGIS and Keycloak reference services where needed for a downloadable demonstration. Demo identities and any generated local bootstrap credentials are disposable, visibly local-only, never production defaults, and never committed. Empty setup does not equate a Keycloak administrator account with an AyuVahak grant. The UI can explain the one-shot first-grant process but cannot invoke the bootstrap or expose a pre-auth grant endpoint: an operator creates/maps the external IdP identity, then runs the existing bootstrap CLI out of band with the intended subject and explicit confirmation. The CLI remains one-shot and closes after success.

## 6. Optional Operator Support capability

The sole initially implemented intelligence use case is the existing spec's bounded Documentation/Operator Support Assistant. It is disabled by default. The UI offers finite, versioned topic/question keys; it does not accept arbitrary free-form case questions. The capability may read only a finite, versioned allowlist of approved documentation and runbooks. Sources have stable IDs, section locators, and hashes. `.scratch`, generated output, deployment configuration, logs, credentials, external healthcare content, and user/coordination records are excluded.

The provider-neutral adapter accepts only the bounded question key and approved corpus version. It has no model tools, SQL, shell, owner-mutation ports, or caller-selected URL. A deployment administrator selects a named provider profile from an allowlist; the profile fixes the HTTPS scheme, exact host, permitted port, request path/model mapping, and credential reference. The HTTP boundary validates hostname and all resolved IPv4/IPv6 addresses at connection time, rejects loopback/private/link-local/multicast/metadata destinations unless a separately explicit local-test profile is used, pins the verified address to the connection, and verifies TLS hostname/certificate normally. Redirects are disabled by default; any explicitly supported same-origin redirect is revalidated under the same policy. Connect/read/total time, concurrency, request bytes, response bytes, and output length are bounded. Retries and fallback paths repeat destination validation. Secret material is referenced through the Phase 13 secret boundary and is not returned, stored in ordinary application data, or logged. Acceptance uses a deterministic local fake/provider mock configured only by a test profile; no external inference is required or used.

The response schema is bounded and discriminated: `ADVISORY` or `ABSTAINED`, bounded answer, validated source references, capability/corpus version, and bounded reason code. Invalid, unsupported, oversized, stale, ungrounded, or timed-out results abstain or show unavailable. Every non-abstained response is prominently labeled `ADVISORY`, cites supplied sources, says it does not change AyuVahak state, and can be closed/ignored without side effects. No prompt or answer body is retained or logged by default. Users retain ordinary documentation search when the feature is disabled or unavailable.

Clinical diagnosis, treatment, triage, patient/case summaries, risk prediction, patient or clinical inputs, autonomous Matching/routing, Acceptance, reservation, dispatch, Transfer/Transport mutation, authorization, privacy, audit, DHIS2 correction, federation trust, model tools, training on production interactions, and arbitrary prompts remain prohibited. Provider/model/prompt/corpus/purpose/input/output changes require re-evaluation before activation.

## 7. Deterministic optimization

Profile first using the Phase 13 synthetic Profile A/B evidence and inspect relevant PostgreSQL query plans or runtime evidence. Version a synthetic golden semantic corpus before any behavior-affecting change, including exact Matching eligible candidates, exclusions, ranking, explanation, and tie behavior plus representative Capacity, Transfer, Transport, federation, and Analytics outcomes. Any optimization requires before/after equivalence and evidence of a worthwhile measured bottleneck. If no worthwhile bottleneck is demonstrated, record `NO PRODUCTION OPTIMIZATION JUSTIFIED BY CURRENT EVIDENCE`; do not add speculative code. Do not add Redis, Kafka, a search/graph/data warehouse, or other distributed infrastructure without a separate evidence-backed authorization.

## 8. Distribution and repository experience

The supported local bundle contains the prebuilt application and UI, loopback-first Compose configuration, pinned dependency images, mode-selection/start/stop/diagnostic scripts for Windows and documented cross-platform commands, demo manifest, safe `.env.example`, operator/install/demo/empty/AI-support/release-verification guidance, and checksums. It excludes `.scratch`, source/build caches, local toolchains, runtime databases/logs, actual `.env`, credentials, API keys, and private keys. Docker Compose must run using the included artifact without Maven or Node. A native EXE is optional only if maintainable and trustworthy; the ZIP and one-command launcher are canonical if Docker is a prerequisite.

Root documentation must accurately explain purpose, non-goals, stakeholders, installation, mode choice, architecture, standards, privacy/security boundaries, optional intelligence, testing, governance, and current release status. Screenshots must be captured from the real synthetic demo UI. Preserve an existing root license if found; otherwise do not choose one or claim reuse rights. Do not claim DPG registration/certification, legal compliance, clinical approval, or universal production readiness.

## 9. Acceptance and implementation seams

Acceptance uses synthetic fixtures, local PostgreSQL/PostGIS, local Keycloak, mock provider/exchange services, and local browser automation only. It covers clean local Demo and Empty first runs; separate organization scopes; actual backend denial of unauthorized APIs/deep links; each supported persona/workspace; stale/missing/unknown semantics; core workflows; intelligence disabled; fake provider answer/abstention/error/timeout/injection/privacy cases; no model mutation; deterministic golden corpus equivalence; package startup from an unrelated clean directory; and checksums/release evidence. No acceptance contacts public healthcare systems, public FHIR/DHIS2, public cloud, or external model providers.

The minimum backend seams are: current authenticated subject/capability presentation; safe local deployment-mode state that is set out of band by the launcher before application startup; owner APIs required by a usable workspace where such APIs are absent; a UI-safe aggregate Analytics query surface; operator guidance to run the existing one-shot grant bootstrap CLI out of band; and the narrowly bounded Operator Support port/configuration. Every new endpoint uses existing authentication, grants, scope validation, audit, and owner application contracts. No pre-auth bootstrap/mode endpoint is added.

## 10. Design challenge and resolved assumptions

| Challenge | Decision |
|---|---|
| Does a role-aware menu create a second authorization system? | No. It is a view over server-evaluated capabilities; APIs remain authoritative and are tested directly. |
| Does first-run demo selection change production startup? | No. The loopback-first launcher records the explicit local choice before startup; there is no unauthenticated browser mode-selection API. Production profiles remain externally configured and cannot expose mode selection/reset. |
| Does localhost-only binding make a pre-auth endpoint safe from browser attacks? | No. Avoid the pre-auth endpoint; use an out-of-band local launcher and validate Host/Origin for the browser-served local application. |
| Can the browser execute first-grant bootstrap? | No. It guides the operator to the existing one-shot local CLI; no new web grant endpoint or duplicate bootstrap path is introduced. |
| Can demo reset delete state from an empty or production database? | No. Reset requires the dedicated disposable Demo volume/project and local mode markers, then drops that isolated store as a whole; it never runs cross-owner row deletion. |
| Does one interface imply the UI owns domain state? | No. Each workflow calls its existing owner API; where an API is absent, a minimum owner-owned application/API seam is added. |
| Can an LLM turn documentation into operational advice? | It is labeled advisory, finite-topic, cited, non-case-specific, and has no action ports. The no-model documentation path remains. |
| Does an administrator-controlled provider profile make an endpoint safe automatically? | No. The adapter enforces profile allowlisting, HTTPS/host/port, resolved-address pinning, TLS, redirect policy, and bounded request/response/time for every attempt. |
| Does a local downloadable package claim production readiness? | No. It is a reference/local installation; institutional deployment retains the Phase 13 organization-specific gates. |
| Does a missing license permit an open-source claim? | No. Licensing remains a repository-owner governance decision unless an existing explicit license is discovered. |

## 11. Explicit non-goals

No patient portal or patient identity; no alternate identity provider; no duplicate authorization model; no clinical workflow or decision support; no mock UI pretending to be live; no business transitions in the browser; no AI access to patient/case/clinical data; no model tool/action use; no mandatory external LLM; no model dependency in the core; no cloud provisioning; no Kubernetes/Terraform/Helm requirement; no public health-system integration test; no DPG application; no fabricated license or compliance claim; and no Volume VI.
