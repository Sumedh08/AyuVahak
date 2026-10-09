# Phase 3 Specification — Care Service Directory

**Volume:** II — Core Care Coordination  
**Phase boundary:** Directory and discovery only; no capacity, referral, matching, transfer, or external standards connector.  
**Architecture references:** [Volume II architecture](../architecture/volume-2-core-care-coordination.md), [context contracts](../architecture/volume-2-context-contracts.md), [data ownership](../architecture/data-ownership.md), [glossary](../../GLOSSARY.md).

## Objective and outcome

Deliver the coordination-owned Directory projection needed to answer “which organizations, facilities, locations, services, and directly asserted care capabilities are known to this deployment, and where?” Support governed manual curation plus a port that a later adapter can implement. This phase is useful independently; it does not claim current capacity, availability, readiness, suitability, or acceptance.

## Scope and exclusions

**Included:** Organization, Facility, Location, HealthcareService, CareCapability vocabulary/assignments, source and external identifiers, lifecycle/correction, geographic facts, exact/nearby/capability discovery, scoped API/query contracts, audit/provenance, migrations for the real Directory owner, local tests and operational documentation.

**Excluded:** capacity observations/readiness/reservation; Referral/Patient Context; Matching/recommendation; Transfer/Transport; practitioner identity/credentials; generic organization registry; FHIR/mCSD/HAPI/IPS/DHIS2/ OpenHIE adapter; terminology server; facility-to-capability inference; user interface; business analytics.

## Concepts and ownership

Directory owns a refreshable coordination projection plus explicit local corrections. A source registry remains authority for its issued identity. If no registry exists, the deployment's accountable organization is the local source authority. Preserve source identity, update/observation time, verification status, method, and corrections for every assertion.

| Concept | Required meaning and invariant |
| --- | --- |
| Organization | Administrative/legal coordination organization. May have an optional simple parent reference; do not create a general arbitrary relationship graph. |
| Facility | Care-delivery site/operational unit. Has one responsible/managing Organization reference for this coordination projection and zero or more Locations. A Facility is not a legal identity claim independent of its source. |
| Location | Physical point/place. Coordinates are optional; unknown is not `(0,0)`. A Facility can have a designated primary coordination Location where known. |
| HealthcareService | Organized care offering by an Organization at a Facility/Location. It describes an offering, not current ability to accept a case. |
| CareCapability | Deployment-governed code for a relatively stable capability. Code is immutable after creation; display labels may be localized/edited with history; active/inactive lifecycle; optional parent reference does not imply inheritance. |
| FacilityCapabilityAssertion | Direct, attributed statement that a Facility can provide a capability, optionally through a HealthcareService. It carries source, verification state and effective times. Only active `VERIFIED` assertions satisfy Phase 6 hard capability checks. |
| ExternalIdentifier | `(issuer/system, resource type, value)` reference owned by Directory's association to its concept; never replaces internal UUIDv4 or another issuer's identifier. |

Capability codes describe coordination needs, not a universal clinical terminology. Directory may curate local codes and later map them through a context-owned adapter; external terminology mappings are not required in Phase 3.

Capability assertion verification is explicit: `UNVERIFIED`, `VERIFIED`, `DISPUTED`, `WITHDRAWN`, or `SUPERSEDED`. A source is not verified merely because it sent data. In Phase 3's manual baseline, an authorized Directory curator records the review method/source and marks an assertion `VERIFIED`; a later adapter can mark it verified only when a deployment-configured source trust policy allows it. Unconfigured source is `UNVERIFIED`. Only `VERIFIED` + active + effective-at-evaluation assertions count for hard eligibility. Verification is an operational evidence status, not professional credential certification.

## Application behavior

### Commands

| Command | Behavior / authorization |
| --- | --- |
| Create/update Organization | Require a Directory curation authority at platform or applicable organization scope; validate source/parent refs and preserve prior values/provenance. |
| Create/update/deactivate Facility | Require responsible Directory authority; require a managing Organization reference; deactivation prevents new discovery but retains facts referenced by workflow history. |
| Create/update Location / set primary location | Validate finite WGS84 longitude/latitude ranges, coordinate order, and facility ownership; missing coordinate is explicit. |
| Create/update/deactivate HealthcareService | Preserve offering and owner/location references; service state does not assert capacity. |
| Define/deactivate CareCapability code | Require platform-scoped `DirectoryVocabularyAdministration`; code immutable; cannot deactivate a code while existing history depends on it without a deprecation/replacement explanation. |
| Assert/correct/withdraw FacilityCapability | Require authority for owning Facility; include source, method, effective time, and evidence status. Withdrawal/correction is append/supersede, not erasure. |
| Add/remove/supersede ExternalIdentifier association | Require Directory authority, an issuer/resource-type/value tuple, and valid owning-concept scope; prevent duplicate active tuple within a concept type; preserve correction history. |

All mutations require owner-local idempotency for retried external commands, explicit validated organization/facility context, and atomic required audit per Phase 2. Source facts retain provenance; manual facts identify the actor, method, time, and reason. No source write-back occurs.

### Functional authorities and scope checks

Directory registers these functional authority codes with Identity & Access; local job roles remain deployment policy and are never inferred from IdP role claims:

| Authority | Permitted grant scope | Use |
| --- | --- | --- |
| `DirectoryRead` | `PLATFORM`, `ORGANIZATION`, `FACILITY` | Read one or list Directory projections within the exact authorized scope. |
| `DirectoryCuration` | `PLATFORM`, `ORGANIZATION`, `FACILITY` | Create/correct/deactivate Organization, Facility, Location, HealthcareService, capability assertions, and external-identifier associations within scope. Organization creation requires `PLATFORM`; a facility-scoped grant must match both owning Organization and Facility. |
| `DirectoryVocabularyAdministration` | `PLATFORM` | Define/deactivate controlled CareCapability codes. |

The execution scope in each Directory request is explicit and validated. A platform grant may act across the deployment; an organization grant is restricted to that exact Organization and its facilities; a facility grant is restricted to the exact Facility and its matching Organization. A raw caller-proposed identifier is not authority. Facility-to-Organization association is read from the Directory owner and compared to the grant scope; no caller assertion can override it. Vocabulary administration is always a separate platform-scoped authority.

Identity & Access remains the sole owner of grant records and grant/revocation audit. Its protected grant-administration operation continues to require an authenticated actor with platform-scoped `AccessGrantAdministration`; it may create/revoke a registered functional authority at only a scope permitted by that authority. The target grant scope is distinct from the administrator's `PLATFORM` execution scope. Organization and Facility target references are validated against active Directory-owned facts, including the Facility-to-Organization relationship. Grant administration does not become Directory-owned state, and the Directory context never queries grant tables directly. This Phase 3 extension preserves the Phase 2 authorization invariants and is recorded in [ADR 0021](../adr/0021-grant-registered-authorities-at-declared-scope.md).

### Queries

- `GetOrganization`, `GetFacility`, `GetHealthcareService` return only authorized fields with active/deactivated state, source and version.
- `FindFacilities` supports exact organization/facility filters and an optional explicit geographic center/radius plus required CareCapability code set.
- Nearby search uses PostGIS geographic calculations in meters on WGS84 coordinates. The radius boundary is inclusive (`distance <= radius`). Results order by distance, then stable Facility UUIDv4. Invalid radius/coordinates fail validation.
- Required capabilities are ANDed and must be directly asserted, active, and sufficiently verified. No child/parent inheritance and no service-name fuzzy inference.
- A missing destination location cannot be treated as zero distance or as proven out-of-radius. For a radius search it appears only in a separate unlocated/indeterminate result group if the caller is authorized; it is never mixed into the ranked nearby list.
- Query returns only Directory facts and never includes a capacity/readiness statement.
- Search uses bounded pagination, documented maximum page size, deterministic order, and allowlisted filters. No unbounded national listing.

### HTTP resource contract

All routes below are authenticated and scope-checked; command routes require `Idempotency-Key` when identified above, use RFC 9457 errors, and propagate the Phase 1 correlation identifier.

| Method and route | Purpose |
| --- | --- |
| `POST /api/v1/directory/organizations` | Create an Organization projection. |
| `GET /api/v1/directory/organizations/{organizationId}` | Read one authorized Organization. |
| `POST /api/v1/directory/facilities` | Create a Facility with managing Organization reference. |
| `GET /api/v1/directory/facilities/{facilityId}` | Read one Facility and its authorized service/location/capability summary. |
| `PATCH /api/v1/directory/facilities/{facilityId}` | Correct mutable Directory projection attributes with version precondition. |
| `POST /api/v1/directory/facilities/{facilityId}/deactivation` | Explicitly deactivate while preserving referenced history. |
| `POST /api/v1/directory/locations` and `PATCH /api/v1/directory/locations/{locationId}` | Create/correct a Facility-associated Location. |
| `POST /api/v1/directory/healthcare-services` and `PATCH /api/v1/directory/healthcare-services/{serviceId}` | Create/correct an organized care offering. |
| `POST /api/v1/directory/capabilities` | Define a governed CareCapability code. |
| `POST /api/v1/directory/facilities/{facilityId}/capability-assertions` | Assert a direct capability for a Facility. |
| `POST /api/v1/directory/capability-assertions/{assertionId}/corrections` | Correct/withdraw an assertion without destructive edit. |
| `GET /api/v1/directory/capabilities` | Bounded active capability vocabulary query. |
| `GET /api/v1/directory/facilities` | Bounded discovery using exact Organization/Facility filters, or optional geographic near-point/radius filters, plus repeated required-capability filters. |
| `POST /api/v1/directory/concepts/{conceptType}/{conceptId}/external-identifiers` | Associate an issuer/resource-type/value identifier with one supported Directory concept. |
| `GET /api/v1/directory/concepts/{conceptType}/{conceptId}/external-identifiers` | Read bounded current associations or their correction history. |
| `POST /api/v1/directory/external-identifiers/{identifierId}/removal` | Remove an active association with an expected-version precondition and retained history. |
| `POST /api/v1/directory/external-identifiers/{identifierId}/supersession` | Replace an active association with a linked successor and retained history. |

`GET /api/v1/directory/facilities` accepts only `organizationId`, `facilityId`, `centerLongitude`, `centerLatitude`, `radiusMeters`, repeated `requiredCapability`, `pageSize`, and `offset`. Exact and capability filters may be used without geographic parameters. Geographic search requires longitude, latitude, and radius together; supplying only part of the geographic tuple is a validation error. Exact-filter results have deterministic Facility UUID ordering. A geographic response identifies `NEARBY` mode, orders located results by distance then Facility UUID, and keeps unlocated facilities in a separate group; an exact-filter response identifies `DIRECTORY_FILTERED` mode and does not imply distance. Page sizes are bounded, offsets are bounded, and unsupported query keys fail with a stable Directory validation error rather than being silently ignored.

This specification defines the first Directory response contract intended for publication under `/api/v1`; earlier local-only implementation shapes are not compatibility commitments. After publication, incompatible API changes require a new major URI version.

The four external-identifier routes use only these Directory concept types: Organization, Facility, Location, HealthcareService, CareCapability, and FacilityCapabilityAssertion. Add, remove, and supersession commands require `DirectoryCuration`, an explicit validated execution scope, `Idempotency-Key`, provenance, and required audit evidence in the same transaction. The initial association returns `201` with its internal Directory identifier; removal requires `expectedVersion` and returns `204`; supersession requires `expectedVersion`, the replacement issuer/resource type/value, and provenance, then returns `201` with the successor identifier. An active `(concept type, issuer, resource type, value)` tuple is unique across concepts of that type and conflicts with `DIRECTORY_IDENTIFIER_CONFLICT`. Issuer, resource type, value, and concept type are all part of the key; identifiers are not globally unique across other issuers, resource types, or concept types. Version conflicts use `DIRECTORY_VERSION_CONFLICT`. Directory validates each concept's owning Organization/Facility facts and never queries Identity & Access grant tables.

The external-identifier read route requires `DirectoryRead`. By default it returns only current active associations; `includeHistory=true` returns a bounded projection containing current state and retained correction snapshots. `limit` defaults to 50 and is capped at 100; `offset` is capped at 10,000. Reads are scope-checked and audited before disclosure. `DirectorySource` remains the provenance reference for a Directory fact and is distinct from an external identifier associated with that fact. Identifier associations do not provide a master patient index, identity reconciliation, or write-back.

The standard list response contains page metadata and projection facts, not persistence rows. Nearby search documents coordinate order (`longitude`, `latitude`), radius in meters, distance metric, deterministic sort, and unlocated count/group.

### Port and adapter boundary

Directory owns a conceptual `DirectorySourcePort` for later reading source records/updates. The Phase 3 local/manual path is sufficient for acceptance; no external adapter is built. Connector configuration and source mapping live with each future Directory adapter. Shared interoperability libraries may help with protocol details but do not own facility/capability semantics.

## State, persistence, and consistency

Directory owns its own logical schema and Flyway history beginning only when Phase 3 is implemented. Do not pre-create it in earlier phases. No other business context receives a foreign key or direct access to Directory tables. Context references are validated through application operations. A local Directory update and its required audit/provenance/outbox intent commit atomically. Phase 3 emits no domain event because no existing asynchronous consumer needs one; later phases query Directory synchronously.

Use source-attributed current projections plus correction history, not full event sourcing. Deactivation preserves internal references. A change that would invalidate prior evidence creates a new version and does not rewrite recommendation/transfer history owned elsewhere.

## Errors

Use RFC 9457 plus stable codes, without raw exception content: `DIRECTORY_VALIDATION_FAILED`, `DIRECTORY_NOT_FOUND`, `DIRECTORY_INACTIVE`, `DIRECTORY_SCOPE_MISMATCH`, `DIRECTORY_IDENTIFIER_CONFLICT`, `DIRECTORY_CAPABILITY_UNKNOWN`, `DIRECTORY_COORDINATE_INVALID`, `DIRECTORY_POLICY_UNCONFIGURED`, `DIRECTORY_VERSION_CONFLICT`, and safe `INTERNAL_ERROR`. Authorization failures follow Phase 2 non-disclosure behavior. Stale source data is a typed result, not an internal error.

## Test-driven seams and scenarios

Tests use synthetic organizations/facilities/capabilities and disposable PostgreSQL/PostGIS. At minimum:

1. Create an attributed Facility and directly verified Capability; query it by exact Facility and capability.
2. Prove Facility, Location, HealthcareService, Capability and Capacity semantics do not collapse; directory result makes no readiness claim.
3. Execute real PostGIS distance/radius query; prove inclusive boundary, deterministic ties, WGS84 validation, and no missing-coordinate-to-zero behavior.
4. Reject inactive/unverified capability, invalid coordinate, bad issuer scope, unauthorized organization/facility, duplicate conflicting external ID and stale entity-version mutation.
5. Verify idempotent create/update retry, same key/different fingerprint conflict, correction/deactivation history and audit atomicity.
6. Verify page bounds, filter allowlist, safe RFC 9457 failures, correlation, and no sensitive payload in normal logs.
7. Architecture tests prove Directory domain has no Spring/adapter imports and later contexts have no private table/repository access by test fixture or active rule.

## Implementation order and dependencies

Recommended sequence: define Directory concepts/value rules → persistence/migrations and PostGIS behavior → curation commands/provenance/audit → discovery query → HTTP contract → acceptance and architecture tests. No downstream context implementation is required. Phase 4 and Phase 5 may proceed only after Phase 3 acceptance/freeze.

## Phase 3 acceptance gate

**PASS only if** a fresh local environment can create and query a provenance-backed Organization/Facility/Location/service/capability projection; actual PostgreSQL/PostGIS geographic behavior works; capability and geographic queries are deterministic; invalid/missing facts remain explicit; authorization, idempotency, audit, API error and safe-log contracts hold; architecture boundaries run in normal verification; no capacity/referral/matching/transfer/standards adapter or unowned schema exists; documented local verification is reproducible. A green compile without real PostGIS query or scope/failure tests is not PASS.

## Explicit non-goals

No Phase 4–7 code, no empty future module, schema or API, no fabricated clinical capabilities, no national registry claims, no FHIR resource as the Directory domain model, no public integration test, no outbound registry writes, and no patient data.
