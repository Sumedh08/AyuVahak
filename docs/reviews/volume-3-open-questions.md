# Volume III design open-question disposition

**Review date:** 2026-10-15

**Status at review time:** Volume III design frozen; implementation had not started when this design-time disposition was recorded. Current implementation and freeze evidence is maintained in the Phase 8–10 and Volume III acceptance records.
**Purpose:** Separate design questions that block a Phase 8–10 implementation from values selected by a deployment and matters outside Volume III.

## Decision timing summary

- **MUST RESOLVE BEFORE IMPLEMENTATION: 0.** The phase specifications define safe behavior for missing partners, unsupported profiles, unavailable sources, absent legal/purpose policy, unknown remote outcomes, and manual transport. Each owner remains authoritative and no public service is required for implementation or acceptance.
- **DEPLOYMENT CONFIGURATION: 8.** These values are deliberately local to a deployment. The safe behavior is explicit when they are absent.
- **DEFERRED TO LATER VOLUME: 4.** These are outside the Phase 8–10 scope and are not prerequisites for these specifications.

## MUST RESOLVE BEFORE IMPLEMENTATION

None.

## Deployment configuration

| Question / value | Why deployment-specific | Safe default / impact if missing | Owner and latest safe decision point |
| --- | --- | --- | --- |
| Which facility/service registry or mCSD partner is authoritative, and what profiles/operations are agreed? | Registry availability, jurisdiction, and governance differ. | Manual Directory curation remains available; no external sync or positive assumption. | Deployment directory authority; before enabling that partner profile. |
| Which FHIR R4 profiles, supported fields, and source operations are permitted for each partner? | FHIR R4 is the baseline, but profile and semantics depend on partner agreement. | Reject unsupported or incomplete input; do not guess a profile. | Owner-context lead and partner governance; before adapter activation. |
| Which organization, codes, terminology versions, and source priorities map to local capabilities or capacity dimensions? | Codes and operations are local; a standard code is not automatically a local policy. | Unmapped safety-relevant evidence remains unknown/indeterminate. | Directory/Capacity/Matching policy owners; before source data can affect matching. |
| What legal basis, purpose, consent reference/decision, and retention policy govern patient-context disclosure? | Rules differ by jurisdiction and deployment. | Deny disclosure if required policy is absent or indeterminate; no cache. | Accountable health authority and privacy officer; before enabling IPS/context access. |
| Which partner authentication profile, issuer, audience, trust anchors, endpoint origin, and secret provider are approved? | Partners may support different mechanisms; no universal provider or cloud secret manager is mandated. | No partner activation or outbound call. | Security/operator and partner owner; before trust profile activation. |
| Which private network ranges and egress paths are allowed? | Healthcare networks may require private addressing while deployment network policy differs. | Deny private targets unless the exact partner path is explicitly approved and network egress is controlled. | Deployment security/network operator; before partner activation. |
| Which provider, dispatch protocol/profile, functional authority mapping, and manual operating procedure apply to Transport? | EMS interfaces and organizational roles are local; no universal live-dispatch standard is assumed. | Manual coordination is available; no live provider operation is attempted. | Transport deployment owner; before provider integration or live dispatch workflow is enabled. |
| Which operational retention windows, payload/page/time limits, clock-skew allowance, retry caps, and alert thresholds are appropriate? | Workload, law, and operational policy are not universal. | Conservative bounded local settings; reject over-limit content, mark stale/unknown, and stop after bounded retries. No value may enable unbounded processing. | Deployment operator with context owners; before production activation of each exchange. |

## Deferred to later volume or explicit future scope

| Question | Why deferred | Owner / latest safe decision point |
| --- | --- | --- |
| Should an interactive AyuVahak client use a particular authorization-code/PKCE application flow? | No frontend/BFF/client UX is included in Phases 8–10. Phase 9 defines only partner exchange and requires secure client authentication for any named external flow. | Client/platform architecture; before a future interactive client is implemented. |
| Should continuous location tracking be introduced? | Phase 10 deliberately has no GPS surveillance or continuous position stream. | Separate product/privacy review; before any future location feature. |
| Should an analytics/public-health export be added? | No DHIS2/analytics integration is in Volume III. | Future authorized volume; before any export design. |
| Should a generic terminology service be operated or consumed online? | No terminology server is required; online availability must not be a safety-critical runtime dependency. | Future terminology scope; before a named use requires it. |

## Interpretation

The specifications are implementation-ready in the sense that implementers have a bounded contract and safe default for the absence of deployment-specific information. “Implementation-ready” does not mean a real partner can be activated without its local governance, configuration, credentials, and conformance agreement. No unresolved product or architecture decision is hidden in the deployment-configuration list.
